package townkit.core.building;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import townkit.api.building.BuildingDetails;
import townkit.api.village.Village;
import townkit.core.FieldReader;
import townkit.core.TownKit;
import townkit.core.village.Ids;
import townkit.core.village.VillageHandle;

/**
 * The building read API ({@link townkit.api.building.TownKitBuildings#at} and friends). Reads Towntopia's building
 * objects as they are and never asks Towntopia to scan, validate or rebuild anything.
 *
 * <ul>
 *   <li>{@code valid}: {@code Building.isValid()}, the verdict Towntopia itself uses to light the token
 *       ({@code ItemFrameTokenRegistry.setRegisteredGlint}), announce the building and count it operational: room shape
 *       ({@code navMeshBlocks} non-empty) and access. Towntopia 0.1.2 discards {@code scanFunctionalBlocks}' result.</li>
 *   <li>{@code problem}: only for a building whose shape holds but whose access was turned off:
 *       {@code VillageBuildings.accessFailure(Building)}, called reflectively. It is a pure query over the nav graph
 *       (its only write is the nav table's debug {@code staleQueries} counter, which every nav query bumps). A room
 *       whose shape broke gets no reason, as in Towntopia's own announcement.</li>
 *   <li>{@code roomTiles}: {@code Building.navMeshBlocks}. {@code workingBlocks}: {@code Building.functionalBlocks}
 *       plus the role lists some buildings keep apart from it ({@link #ROLE_FIELDS}: a kitchen's crafting tables are
 *       only in {@code KitchenBuilding.craftingTableBlocks}), with the block now at each position. All through
 *       {@link FieldReader}; a missing field gives an empty set (FieldReader logs it once).</li>
 * </ul>
 * Everything is created on first use, so an unused read API costs nothing.
 */
final class BuildingReader {
    private BuildingReader() {
    }

    private static final class Fields {
        @SuppressWarnings({"unchecked", "rawtypes"})
        static final FieldReader<Set<BlockPos>> ROOM = (FieldReader) FieldReader.of(Building.class, "navMeshBlocks", Set.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        static final FieldReader<Set<BlockPos>> WORKING = (FieldReader) FieldReader.of(Building.class, "functionalBlocks", Set.class);
        /** {@code (VillageManager, Building) -> RegistrationFailure or null}, or null when Towntopia changed. */
        @Nullable
        static final MethodHandle ACCESS_FAILURE = accessFailure();

        @Nullable
        private static MethodHandle accessFailure() {
            try {
                Class<?> vb = Class.forName("com.alex.test.village.VillageBuildings", false,
                        VillageManager.class.getClassLoader());
                MethodHandle buildings = MethodHandles.privateLookupIn(VillageManager.class, MethodHandles.lookup())
                        .findGetter(VillageManager.class, "buildings", vb);
                MethodHandles.Lookup in = MethodHandles.privateLookupIn(vb, MethodHandles.lookup());
                Class<?> failure = Class.forName("com.alex.test.village.chat.RegistrationFailure", false,
                        VillageManager.class.getClassLoader());
                MethodHandle m = in.findVirtual(vb, "accessFailure", MethodType.methodType(failure, Building.class));
                return MethodHandles.filterArguments(m, 0, buildings)
                        .asType(MethodType.methodType(Object.class, VillageManager.class, Building.class));
            } catch (Throwable t) {
                TownKit.LOG.warn("[TownKit] can't call VillageBuildings.accessFailure ({}): building problems read as "
                        + "empty", t.toString());
                return null;
            }
        }
    }

    /**
     * Working blocks Towntopia keeps only in a building's own role lists, not in {@code functionalBlocks} (checked
     * against each class's {@code scanFunctionalBlocks} in 0.1.2): building class simple name to (field, declared
     * type). Lists already mirrored into {@code functionalBlocks} are listed too where cheap; duplicates collapse.
     * Not included on purpose: stand spots, storage indexes and filters, watchtower ground samples (not blocks the
     * building works with).
     */
    static final Map<String, List<Map.Entry<String, Class<?>>>> ROLE_FIELDS = Map.of(
            "KitchenBuilding", List.of(Map.entry("craftingTableBlocks", Set.class)),
            "StorageBuilding", List.of(Map.entry("craftingTableBlocks", Set.class), Map.entry("furnaceBlocks", Set.class),
                    Map.entry("stonecutterBlocks", Set.class), Map.entry("composterBlocks", Set.class)),
            "ForgeBuilding", List.of(Map.entry("furnaceBlocks", Set.class), Map.entry("smithingTables", Set.class),
                    Map.entry("blastFurnaces", Set.class)),
            "ButcheryBuilding", List.of(Map.entry("grindstones", Set.class)),
            "LibraryBuilding", List.of(Map.entry("enchantingTables", Set.class)),
            "BarracksBuilding", List.of(Map.entry("bedBlocks", Set.class)),
            "MageTowerBuilding", List.of(Map.entry("stations", Set.class), Map.entry("bookshelves", Set.class),
                    Map.entry("crystals", Set.class), Map.entry("beds", Set.class)),
            "ArcheryRangeBuilding", List.of(Map.entry("targets", List.class), Map.entry("fletchingTables", List.class)));

    private static final String BUILDING_PACKAGE = "com.alex.test.village.building.";
    private static final Map<Class<?>, List<FieldReader<java.util.Collection<BlockPos>>>> ROLE_READERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<FieldReader<java.util.Collection<BlockPos>>> roleReaders(Class<?> c) {
        return ROLE_READERS.computeIfAbsent(c, k -> {
            List<FieldReader<java.util.Collection<BlockPos>>> out = new ArrayList<>();
            for (Class<?> x = k; x != null && x != Building.class; x = x.getSuperclass()) {
                if (!x.getName().startsWith(BUILDING_PACKAGE)) continue;
                for (Map.Entry<String, Class<?>> f : ROLE_FIELDS.getOrDefault(x.getSimpleName(), List.of())) {
                    FieldReader r = FieldReader.of(x, f.getKey(), f.getValue());
                    if (r.available()) out.add(r);
                }
            }
            return List.copyOf(out);
        });
    }

    /**
     * {@code functionalBlocks} plus the building's role lists, in that order, without duplicates. May be Towntopia's
     * live set: copy it to keep it. Also the "working blocks" of {@code BUILDING_BLOCKS_CHANGED}.
     */
    static Set<BlockPos> workingPositions(Building b) {
        Set<BlockPos> functional = read(Fields.WORKING, b);
        List<FieldReader<java.util.Collection<BlockPos>>> roles = roleReaders(b.getClass());
        if (roles.isEmpty()) return functional;
        Set<BlockPos> all = new java.util.LinkedHashSet<>(functional);
        for (FieldReader<java.util.Collection<BlockPos>> r : roles) {
            try {
                java.util.Collection<BlockPos> c = r.read(b);
                if (c != null) all.addAll(c);
            } catch (Throwable t) {
                logOnce(t);
            }
        }
        return all;
    }

    private static final AtomicBoolean loggedReadFailure = new AtomicBoolean();

    static Optional<BuildingDetails> at(ServerLevel level, BlockPos pos) {
        serverThread(level);
        BlockPos below = pos.below();
        for (VillageManager m : VillageManagerRegistry.get(level.getServer()).all()) {
            if (m.getLevel() != level) continue;
            for (Building b : m.getBuildings()) {
                Set<BlockPos> room = read(Fields.ROOM, b);
                if (room.contains(pos) || room.contains(below) || workingPositions(b).contains(pos)) {
                    return Optional.of(details(m, b, level));
                }
            }
        }
        return Optional.empty();
    }

    static List<BuildingDetails> ofType(Village village, ResourceLocation type) {
        VillageManager m = manager(village);
        if (m == null || type == null) return List.of();
        ServerLevel level = m.getLevel();
        serverThread(level);
        List<BuildingDetails> out = new ArrayList<>();
        for (Building b : m.getBuildings()) {
            if (type.equals(Ids.building(b.getClass()))) out.add(details(m, b, level));
        }
        return out;
    }

    static Optional<BuildingDetails> details(Village village, UUID frameId) {
        VillageManager m = manager(village);
        if (m == null || frameId == null) return Optional.empty();
        ServerLevel level = m.getLevel();
        serverThread(level);
        Building b = m.getBuilding(frameId);
        return b == null ? Optional.empty() : Optional.of(details(m, b, level));
    }

    // -------------------------------------------------------------------------------------------------------------

    @Nullable
    private static VillageManager manager(Village village) {
        return village instanceof VillageHandle h ? h.manager() : null;
    }

    private static void serverThread(@Nullable ServerLevel level) {
        if (level != null && !level.getServer().isSameThread()) {
            throw new IllegalStateException("TownKitBuildings reads are server-thread only (called on "
                    + Thread.currentThread().getName() + ")");
        }
    }

    private static BuildingDetails details(VillageManager m, Building b, @Nullable ServerLevel level) {
        boolean valid = b.isValid();
        Optional<String> problem = valid ? Optional.empty() : problem(m, b);
        return new BuildingDetails(VillageHandle.info(b), valid, problem, read(Fields.ROOM, b),
                level == null ? Map.of() : working(level, workingPositions(b)));
    }

    /** Towntopia's reason an invalid building is invalid, as the read API reports it (also the validity event's). */
    static Optional<String> problem(VillageManager m, Building b) {
        if (!b.isShapeValid() || b.isAccessValid() || Fields.ACCESS_FAILURE == null) return Optional.empty();
        try {
            Object f = Fields.ACCESS_FAILURE.invokeExact(m, b);
            return f instanceof Enum<?> e ? Optional.of(e.name().toLowerCase(Locale.ROOT)) : Optional.empty();
        } catch (Throwable t) {
            logOnce(t);
            return Optional.empty();
        }
    }

    private static Map<ResourceLocation, List<BlockPos>> working(ServerLevel level, Set<BlockPos> positions) {
        Map<ResourceLocation, List<BlockPos>> out = new LinkedHashMap<>();
        for (BlockPos p : positions) {
            if (!level.isLoaded(p)) continue; // never load a chunk from a read
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue; // gone since Towntopia's last scan
            out.computeIfAbsent(countsAs(s.getBlock()), k -> new ArrayList<>()).add(p.immutable());
        }
        return out;
    }

    /** The vanilla block's id for a stand-in, else the block's own id. */
    private static ResourceLocation countsAs(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (!"minecraft".equals(id.getNamespace()) && StandIns.active()) {
            Block vanilla = StandIns.table().vanillaFor(block);
            if (vanilla != null) return BuiltInRegistries.BLOCK.getKey(vanilla);
        }
        return id;
    }

    private static Set<BlockPos> read(FieldReader<Set<BlockPos>> field, Building b) {
        if (!field.available()) return Set.of();
        try {
            Set<BlockPos> s = field.read(b);
            return s == null ? Set.of() : s;
        } catch (Throwable t) {
            logOnce(t);
            return Set.of();
        }
    }

    private static void logOnce(Throwable t) {
        if (loggedReadFailure.compareAndSet(false, true)) {
            TownKit.LOG.warn("[TownKit] reading a Towntopia building failed; that part reads as empty", t);
        }
    }
}
