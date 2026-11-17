package townkit.core.building;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import townkit.api.Subscription;
import townkit.api.building.BuildingDetails;
import townkit.api.building.StandInReport;
import townkit.api.spi.TownKitBuildingsView;
import townkit.api.village.Village;

/**
 * Stand-in rules for addons ({@link townkit.api.building.TownKitBuildings}), the Minecraft-backed behaviour checks the
 * NEEDS verdicts use, and opt-in recognition of shared {@code c:} tags (rebuilt on every tag reload while on).
 */
public final class CoreBuildings implements TownKitBuildingsView {
    private static final Logger LOG = LogUtils.getLogger();

    /**
     * The real checks. They run while the stand-in table holds its lock, so they never block, never wait for the
     * server and never call back into the table; anything that throws counts as "no". The item-handler capability is
     * probed only on the server thread; elsewhere a block that isn't a {@code Container} is "not decided yet" and
     * its rule waits for the server-thread recheck.
     */
    public static final BlockChecks<Block> CHECKS = new BlockChecks<>() {
        @Override
        public boolean passes(String check, Block block) {
            return CoreBuildings.passes(check, block);
        }

        @Override
        public boolean decided(String check, Block block) {
            return !"inventory".equals(check) || probeServer() != null;
        }
    };

    private static volatile boolean installed;
    private static final AtomicBoolean loggedNoLevel = new AtomicBoolean();
    private static final AtomicBoolean warnedHooksOff = new AtomicBoolean();

    private static final Object TAG_LOCK = new Object();
    private static int tagSubscriptions; // guarded by TAG_LOCK
    private static volatile boolean tagsOn;

    /** Called once from TownKit's constructor, before any addon can register a rule. */
    public static void install() {
        StandIns.install(CHECKS);
        installed = true;
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> onTagsUpdated());
        // rules registered before the server (or off its thread) couldn't probe the item-handler capability: decide
        // them now, on the server thread. Free when nothing was ever registered (no table is even created).
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> StandIns.recheckIfRegistered());
        // building events held for a village not yet registered: a stopping server's drain never runs, so forget them
        // (once per stop; clearing an empty list when the building events were never used)
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) ->
                BuildingEventHooks.onServerStopping());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) ->
                BuildingEventHooks.onServerStopping());
    }

    public static boolean installed() {
        return installed;
    }

    /** True while at least one {@code useCommonTags} subscription is live. */
    public static boolean commonTagsOn() {
        return tagsOn;
    }

    // --- behaviour checks ----------------------------------------------------------------------------------------

    static boolean passes(String check, Block block) {
        try {
            return switch (check) {
                case "bed_block" -> block instanceof BedBlock;
                case "container" -> detachedBlockEntity(block) instanceof Container;
                case "inventory" -> holdsItems(block);
                default -> false;
            };
        } catch (Throwable t) {
            LOG.debug("TownKit building: check {} on {} threw; counts as no", check, block, t);
            return false;
        }
    }

    @Nullable
    private static BlockEntity detachedBlockEntity(Block block) {
        if (!(block instanceof EntityBlock eb)) {
            return null;
        }
        try {
            return eb.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean holdsItems(Block block) {
        BlockEntity be = detachedBlockEntity(block);
        if (be instanceof Container) {
            return true;
        }
        MinecraftServer server = probeServer();
        if (server == null) {
            if (loggedNoLevel.compareAndSet(false, true)) {
                LOG.info("TownKit building: not on the server thread, so the item-handler capability of {} (and any "
                        + "other block checked off it) can't be probed yet; such rules are kept and decided on the "
                        + "server thread", block);
            }
            return false;
        }
        try {
            ServerLevel level = server.overworld();
            return level.getCapability(Capabilities.ItemHandler.BLOCK, BlockPos.ZERO, block.defaultBlockState(), be,
                    null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The running server, only when called on its thread (the only place the capability may be probed). */
    @Nullable
    private static MinecraftServer probeServer() {
        MinecraftServer server = currentServer();
        return server != null && server.isSameThread() ? server : null;
    }

    @Nullable
    private static MinecraftServer currentServer() {
        try {
            return ServerLifecycleHooks.getCurrentServer();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called off the server thread while a server runs: decide pending rules on the server thread soon. */
    private static void recheckOnServerThreadIfNeeded() {
        MinecraftServer server = currentServer();
        if (server != null && !server.isSameThread()) {
            server.execute(StandIns::recheckIfRegistered);
        }
    }

    // --- API -----------------------------------------------------------------------------------------------------

    @Override
    public Subscription countsAs(String owner, Block modded, Block vanilla) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(modded, "modded");
        Objects.requireNonNull(vanilla, "vanilla");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner is blank");
        }
        if (modded == vanilla) {
            throw new IllegalArgumentException(name(modded) + " can't stand in for itself");
        }
        warnIfHooksOff(owner);
        Subscription s = StandIns.table().register(owner, modded, vanilla, StandInTable.Source.EXPLICIT);
        if (s.isDisabled()) {
            if (vanilla instanceof BedBlock && !(modded instanceof BedBlock)) {
                LOG.warn("TownKit building: {}: {} can't be a bed: Towntopia's sleep needs a real BedBlock "
                        + "(head and foot); hammocks and sleeping bags need sleep support, not a stand-in", owner,
                        name(modded));
            }
        } else {
            StandInTable.Report<Block> r = StandIns.table().report(modded);
            if (r == null) {
                recheckOnServerThreadIfNeeded(); // pending: some check couldn't be decided on this thread
            } else {
                LOG.info("TownKit building: {}: {} stands in for {} at {} check site(s) {}; skipped at {} {}", owner,
                        name(modded), name(vanilla), r.countsAt().size(), shortSites(r.countsAt()),
                        r.skippedAt().size(), shortSkips(r.skippedAt()));
            }
        }
        return s;
    }

    /** Once: with every hook off no Towntopia check is rewritten, so no rule can count anywhere. */
    private static void warnIfHooksOff(String owner) {
        if (RewrittenSites.GLOBAL.hooksOff() && warnedHooksOff.compareAndSet(false, true)) {
            LOG.warn("TownKit building: {} registered a stand-in, but every Towntopia hook is switched off in "
                    + "townkit-hooks.properties, so Towntopia's block checks aren't rewritten and no stand-in counts",
                    owner);
        }
    }

    @Override
    public Subscription useCommonTags(String owner) {
        Objects.requireNonNull(owner, "owner");
        warnIfHooksOff(owner);
        synchronized (TAG_LOCK) {
            tagSubscriptions++;
            if (tagSubscriptions == 1) {
                tagsOn = true;
                LOG.info("TownKit building: {} switched on c: tag recognition", owner);
                rebuildTags();
            }
        }
        AtomicBoolean removed = new AtomicBoolean();
        return new Subscription() {
            @Override
            public void remove() {
                if (!removed.compareAndSet(false, true)) {
                    return;
                }
                synchronized (TAG_LOCK) {
                    tagSubscriptions--;
                    if (tagSubscriptions == 0) {
                        tagsOn = false;
                        StandIns.table().replaceTagEntries(Map.of());
                        LOG.info("TownKit building: c: tag recognition off (last subscription removed by {})", owner);
                    }
                }
            }

            @Override
            public boolean isDisabled() {
                return false;
            }
        };
    }

    @Override
    public Optional<StandInReport> standIn(Block modded) {
        if (modded == null || !StandIns.active()) {
            return Optional.empty();
        }
        StandInTable.Report<Block> r = StandIns.table().report(modded);
        if (r == null) {
            return Optional.empty();
        }
        return Optional.of(new StandInReport(r.modded(), r.vanilla(), r.source() == StandInTable.Source.COMMON_TAG,
                r.countsAt(), r.skippedAt()));
    }

    // --- reading buildings ---------------------------------------------------------------------------------------

    @Override
    public Optional<BuildingDetails> at(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        return BuildingReader.at(level, pos);
    }

    @Override
    public List<BuildingDetails> ofType(Village village, ResourceLocation type) {
        return BuildingReader.ofType(village, type);
    }

    @Override
    public Optional<BuildingDetails> details(Village village, UUID frameId) {
        return BuildingReader.details(village, frameId);
    }

    // --- tags ----------------------------------------------------------------------------------------------------

    /** Tag reloads can arrive on the client thread (singleplayer sync): rebuild on the server thread if there is one. */
    private static void onTagsUpdated() {
        if (!tagsOn) {
            return;
        }
        MinecraftServer server = currentServer();
        if (server != null && !server.isSameThread()) {
            server.execute(CoreBuildings::rebuildTagsIfOn);
        } else {
            rebuildTagsIfOn();
        }
    }

    private static void rebuildTagsIfOn() {
        synchronized (TAG_LOCK) {
            if (tagsOn) {
                rebuildTags();
            }
        }
    }

    /** Caller holds TAG_LOCK. */
    private static void rebuildTags() {
        try {
            Map<String, Block> tagToVanilla = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : CommonTagRecognition.TAGS.entrySet()) {
                ResourceLocation id = ResourceLocation.parse(e.getValue());
                if (BuiltInRegistries.BLOCK.containsKey(id)) {
                    tagToVanilla.put(e.getKey(), BuiltInRegistries.BLOCK.get(id));
                }
            }
            Map<Block, Block> entries = CommonTagRecognition.entries(tagToVanilla, tag -> {
                List<Block> members = new ArrayList<>();
                TagKey<Block> key = TagKey.create(Registries.BLOCK, ResourceLocation.parse(tag));
                BuiltInRegistries.BLOCK.getTagOrEmpty(key).forEach(h -> members.add(h.value()));
                return members;
            }, b -> "minecraft".equals(BuiltInRegistries.BLOCK.getKey(b).getNamespace()));
            StandIns.table().replaceTagEntries(entries);
            LOG.info("TownKit building: c: tag recognition: {} modded block(s) found in the tags", entries.size());
            if (!entries.isEmpty()) {
                recheckOnServerThreadIfNeeded();
            }
        } catch (Throwable t) {
            LOG.error("TownKit building: c: tag recognition failed; no tagged block stands in", t);
            StandIns.table().replaceTagEntries(Map.of());
        }
    }

    // --- log helpers ---------------------------------------------------------------------------------------------

    private static String name(Block b) {
        try {
            return BuiltInRegistries.BLOCK.getKey(b).toString();
        } catch (Throwable t) {
            return String.valueOf(b);
        }
    }

    /** {@code owner#method(desc)#kind#target} to {@code Owner.method}. */
    static String shortSite(String key) {
        int hash = key.indexOf('#');
        if (hash < 0) {
            return key;
        }
        String owner = key.substring(key.lastIndexOf('/', hash) + 1, hash);
        int paren = key.indexOf('(', hash);
        String method = key.substring(hash + 1, paren < 0 ? key.indexOf('#', hash + 1) : paren);
        return owner + "." + method;
    }

    private static List<String> shortSites(List<String> keys) {
        return keys.stream().map(CoreBuildings::shortSite).distinct().toList();
    }

    private static List<String> shortSkips(Map<String, String> skipped) {
        List<String> out = new ArrayList<>();
        skipped.forEach((k, why) -> {
            String s = shortSite(k) + " (" + why + ")";
            if (!out.contains(s)) {
                out.add(s);
            }
        });
        return out;
    }
}
