package townkit.core.work.cook;

import com.alex.test.village.building.Building;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.villagers.cook.VillageKitchens;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import townkit.core.FieldReader;
import townkit.core.TownKit;
import townkit.core.work.WorkLog;

/**
 * Reads what {@link Stations} needs from Towntopia's kitchens (private there), failing soft: a renamed field leaves
 * the stations unseen (logged once) instead of crashing.
 * <ul>
 *   <li>{@link #kitchens}: the kitchens of a {@code VillageKitchens} (the cook's view of its village's valid kitchens).</li>
 *   <li>{@link #scan}: a kitchen's room the way Towntopia's {@code KitchenBuilding#scanFunctionalBlocks} walks it (the
 *   room box grown by one, two above, blocks reachable from the room), handed to {@link Stations#scan}.</li>
 * </ul>
 */
final class KitchenAccess {
    private static final FieldReader<List> KITCHENS = FieldReader.of(VillageKitchens.class, "kitchens", List.class);
    private static final FieldReader<Integer> MIN_X = FieldReader.of(Building.class, "minX", int.class);
    private static final FieldReader<Integer> MAX_X = FieldReader.of(Building.class, "maxX", int.class);
    private static final FieldReader<Integer> MIN_Y = FieldReader.of(Building.class, "minY", int.class);
    private static final FieldReader<Integer> MAX_Y = FieldReader.of(Building.class, "maxY", int.class);
    private static final FieldReader<Integer> MIN_Z = FieldReader.of(Building.class, "minZ", int.class);
    private static final FieldReader<Integer> MAX_Z = FieldReader.of(Building.class, "maxZ", int.class);
    @Nullable
    private static final MethodHandle REACHABLE = reachable();

    private KitchenAccess() {
    }

    @Nullable
    private static MethodHandle reachable() {
        try {
            return MethodHandles.privateLookupIn(Building.class, MethodHandles.lookup()).findVirtual(Building.class,
                "isFunctionalBlockAccessibleFromRoom", MethodType.methodType(boolean.class, BlockPos.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            TownKit.LOG.warn("[TownKit] can't read Building.isFunctionalBlockAccessibleFromRoom ({}): cook stations are unseen", e.toString());
            return null;
        }
    }

    /** The kitchens behind {@code kitchens} (empty when unreadable). */
    static List<KitchenBuilding> kitchens(Object kitchens) {
        if (!(kitchens instanceof VillageKitchens vk) || !KITCHENS.available()) return List.of();
        List<?> raw = KITCHENS.read(vk);
        if (raw == null) return List.of();
        List<KitchenBuilding> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof KitchenBuilding k) out.add(k);
        }
        return out;
    }

    /** Scans {@code kitchens} that weren't scanned since the station list changed. */
    static void scanStale(ServerLevel level, List<KitchenBuilding> kitchens) {
        for (KitchenBuilding k : kitchens) {
            if (!Stations.GLOBAL.scanned(k)) scan(level, k);
        }
    }

    /** Scans {@code kitchen}'s room for station blocks (as Towntopia scans it for its own furniture). */
    static void scan(ServerLevel level, KitchenBuilding kitchen) {
        if (REACHABLE == null || !MIN_X.available() || !MAX_X.available() || !MIN_Y.available() || !MAX_Y.available()
                || !MIN_Z.available() || !MAX_Z.available()) {
            WorkLog.GLOBAL.once("cook-station-scan", "[" + Stations.KIND + "] can't read Towntopia's kitchen rooms; cook stations are unseen");
            return;
        }
        List<BlockPos> room = new ArrayList<>();
        try {
            int minX = MIN_X.read(kitchen), maxX = MAX_X.read(kitchen);
            int minY = MIN_Y.read(kitchen), maxY = MAX_Y.read(kitchen);
            int minZ = MIN_Z.read(kitchen), maxZ = MAX_Z.read(kitchen);
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int y = minY - 1; y <= maxY + 2; y++) {
                    for (int z = minZ - 1; z <= maxZ + 1; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if ((boolean) REACHABLE.invokeExact((Building) kitchen, pos)) room.add(pos);
                    }
                }
            }
        } catch (Throwable t) {
            WorkLog.GLOBAL.once("cook-station-scan-failed", "[" + Stations.KIND + "] scanning a kitchen for cook stations failed: " + t);
            return;
        }
        Stations.GLOBAL.scan(kitchen, room, pos -> level.getBlockState(pos).getBlock());
    }
}
