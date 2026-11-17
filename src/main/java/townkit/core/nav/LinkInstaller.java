package townkit.core.nav;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import townkit.api.hooks.HookCall;
import townkit.api.nav.TransportKind;
import townkit.api.nav.TransportLink;
import townkit.api.nav.TransportStop;
import townkit.core.FieldReader;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;

/**
 * Puts transport links into Towntopia's route map, on the server thread.
 *
 * <ul>
 *   <li>When a village's stops or the live kinds change, asks each kind for its links ({@link TransportKind#links}),
 *       keeps the valid ones, adds their edges ({@code NavNode#addNeighbour} both ways) to the current graph, drops
 *       the edges of links that are gone ({@code NavNode#retainNeighbours}), and publishes a new {@link LinkTable}.
 *       When a link's far tile has no node yet, its start tile is marked dirty: the next local update re-fills it,
 *       and the POLL seeding ({@link #seed}) maps the far side.</li>
 *   <li>An after-hook on {@code VillageManager#tickNavRebuild} re-adds a village's edges whenever its graph changes
 *       identity or version (a full build swaps in a new graph; a local update rebuilds tiles in place). Full builds
 *       already have the edges from the fill itself (the POLL seeding), so this is the safety net for local updates.
 *       The same hook refreshes availability ({@link TransportKind#available}) every 40 ticks per village, within
 *       2 ms per pass, and republishes when it changed.</li>
 * </ul>
 *
 * <p>Attached when the first kind is enabled. Fails soft: a missing Towntopia member switches transport off with one
 * log line (nothing is installed). So does a route check transport needs that isn't rewritten
 * ({@code AStarPathfinder#search} STEP, {@code NavCells$1} CELL, {@code NavGraph#floodFillInner} POLL: every hook off
 * in the config, a class switched off, a rewrite failed, a newer Towntopia changed a counted method), checked when the
 * first kind is enabled and again before the first link goes in, and a Towntopia member failing at run time: both go
 * through {@link #switchOff} (installed links published refused, their edges removed best effort).
 */
public final class LinkInstaller {
    private static final Logger LOG = LogUtils.getLogger();
    static final String TICK_NAV_REBUILD = "village.VillageManager#tickNavRebuild(ServerLevel,int,long)";
    private static final long REFRESH_BUDGET_NANOS = 2_000_000L;

    private static final MethodHandle ADD_NEIGHBOUR;
    private static final MethodHandle RETAIN_NEIGHBOURS;
    private static final MethodHandle MAKE_NODE;
    private static final MethodHandle RECORD;
    private static final FieldReader<Long> GRAPH_VERSION;
    @Nullable
    private static final String MISSING;

    static {
        MethodHandle add = null;
        MethodHandle retain = null;
        MethodHandle make = null;
        MethodHandle record = null;
        FieldReader<Long> version = null;
        String missing = null;
        try {
            MethodHandles.Lookup node = MethodHandles.privateLookupIn(NavNode.class, MethodHandles.lookup());
            MethodHandles.Lookup graph = MethodHandles.privateLookupIn(NavGraph.class, MethodHandles.lookup());
            add = node.findVirtual(NavNode.class, "addNeighbour", MethodType.methodType(void.class, NavNode.class));
            retain = node.findVirtual(NavNode.class, "retainNeighbours", MethodType.methodType(void.class,
                    Predicate.class));
            make = graph.findStatic(NavGraph.class, "makeNode", MethodType.methodType(NavNode.class, Level.class,
                    int.class, int.class, int.class));
            record = graph.findStatic(NavGraph.class, "record", MethodType.methodType(void.class, NavNode.class));
            version = FieldReader.of(VillageManager.class, "graphVersion", long.class);
            if (!version.available()) {
                missing = "VillageManager.graphVersion";
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            missing = e.toString();
        }
        ADD_NEIGHBOUR = add;
        RETAIN_NEIGHBOURS = retain;
        MAKE_NODE = make;
        RECORD = record;
        GRAPH_VERSION = version;
        MISSING = missing;
    }

    /** One village's installed links (server thread; {@code links} is also read by the API from any thread). */
    private static final class VillageLinks {
        final ResourceKey<Level> dim;
        volatile List<TransportLink> links = List.of();
        boolean[] available = new boolean[0];
        /** Per link: until when (epoch millis) a failed ride keeps it out of routes; 0 when it never failed. */
        long[] failedUntil = new long[0];
        @Nullable
        NavGraph graph;
        long version = Long.MIN_VALUE;
        int cursor;

        VillageLinks(ResourceKey<Level> dim) {
            this.dim = dim;
        }
    }

    private static final Map<UUID, VillageLinks> VILLAGES = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static volatile boolean dirty;
    private static volatile boolean off;
    /** The pairs published refused since transport switched off ({@link #refuse}; guarded by the class lock). */
    private static List<LinkTable.Link> refused = List.of();
    private static boolean attached;
    /**
     * Far stop tiles (node keys, per dimension) a flood fill couldn't map because their chunk wasn't loaded (server
     * thread). The village after-hook marks the link's start dirty once the chunk is loaded, so the next local update
     * maps them.
     */
    private static final Map<ResourceKey<Level>, Set<Long>> UNLOADED_FAR = new HashMap<>();

    private LinkInstaller() {
    }

    /**
     * Whether transport works: every Towntopia member it needs was found, nothing failed since, and the route checks
     * it needs are rewritten ({@link #gateOpen}).
     */
    static boolean usable() {
        return MISSING == null && !off && gateOpen();
    }

    /** Set once the route checks transport needs are known to be rewritten; never cleared. */
    private static volatile boolean verified;
    private static final String[] NEEDED_CLASSES = {"com.alex.test.village.AStarPathfinder",
            "com.alex.test.village.NavCells$1", "com.alex.test.village.NavGraph"};

    /**
     * Whether the route checks transport needs ({@code AStarPathfinder#search} STEP, {@code NavCells$1} CELL,
     * {@code NavGraph#floodFillInner} POLL; {@link RewrittenNavSites#transportBlocker}) run through the bridge. While
     * one isn't proven yet its class is loaded (not initialised) so TownKit's class-load rewrite reports it; one that
     * isn't rewritten (its class switched off, its rewrite failed, a newer Towntopia changed the method) switches
     * transport off ({@link #switchOff}). One volatile read once verified.
     */
    private static boolean gateOpen() {
        if (verified) {
            return true;
        }
        NavSites sites = NavSitesHolder.SITES;
        if (sites == null) {
            switchOff("nav-sites.tsv can't be read, so its route checks aren't known to be rewritten", null);
            return false;
        }
        for (String name : NEEDED_CLASSES) {
            try {
                Class.forName(name, false, LinkInstaller.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                switchOff("Towntopia's " + name + " is missing", null);
                return false;
            }
        }
        String why = RewrittenNavSites.GLOBAL.transportBlocker(sites);
        if (why == null && !RewrittenNavSites.GLOBAL.transportVerified(sites)) {
            // loaded, but TownKit's class-load rewrite never saw it (its carrier mixin didn't apply)
            why = "a route check it needs never went through TownKit's rewrite";
        }
        if (why != null) {
            switchOff("Towntopia's route checks aren't all rewritten (" + why + ")", null);
            return false;
        }
        verified = true;
        return true;
    }

    /** nav-sites.tsv, read on first use (null when it can't be read). */
    private static final class NavSitesHolder {
        @Nullable
        static final NavSites SITES = load();

        @Nullable
        private static NavSites load() {
            try {
                return NavSites.load();
            } catch (RuntimeException e) {
                LOG.warn("TownKit transport: could not read {}", NavSites.RESOURCE, e);
                return null;
            }
        }
    }

    /** Attaches the hooks and the marker listeners, once (the first enabled kind). */
    static synchronized void attach() {
        if (attached) {
            return;
        }
        attached = true;
        if (MISSING != null) {
            warnOnce("missing", "TownKit transport: a Towntopia member it needs is missing ({}); transport is off and "
                    + "villagers walk as Towntopia makes them", MISSING);
            return;
        }
        NavSites sites = NavSitesHolder.SITES;
        String blocked = sites == null ? "nav-sites.tsv can't be read" : RewrittenNavSites.GLOBAL.transportBlocker(sites);
        if (blocked != null) {
            // no link may go into a route map then: Towntopia's own canStepTo would let a drop or a flat hop through
            // as a plain step (seen in the hooks-off perf runs: searches 15-40x larger). Classes not loaded yet are
            // checked again before the first link goes in (usable()).
            switchOff("a kind was enabled, but Towntopia's route checks aren't all rewritten (" + blocked + ")", null);
            return;
        }
        HookInfo h = HookManifest.get().byKey.get(TICK_NAV_REBUILD);
        if (h == null) {
            warnOnce("hook", "TownKit transport: no hook {}; links are only re-added by full builds and availability is "
                    + "never refreshed", TICK_NAV_REBUILD);
        } else {
            Hooks.add(h, "townkit", LinkInstaller::afterNavRebuild, true);
        }
        MarkerFrames.register();
    }

    /** Switches transport off for good (a Towntopia member failed at run time), logged once. */
    static void fail(String what, Throwable t) {
        switchOff(what + " failed", t);
    }

    /**
     * The one way transport switches off, for good, logged once ({@code reason}, and {@code t} when something threw):
     * a route check it needs isn't rewritten (every hook off in the config, a class switched off, a rewrite failed, a
     * newer Towntopia changed a counted method), or a Towntopia member failed at run time. Every installed link is
     * published refused ({@link #publishOff}: the bridge stays active and refuses those pairs, so an edge left in a
     * route map can't fall back to Towntopia's own step test), then each village's link edges are taken out of its
     * current route map, best effort: removal stops at the first failure (it may be what failed), and the refused
     * table covers whatever is left. Nothing is installed or published afterwards; a server stop publishes an empty
     * table (its route maps are gone).
     */
    static void switchOff(String reason, @Nullable Throwable t) {
        Map<UUID, VillageLinks> had;
        synchronized (LinkInstaller.class) {
            if (off) {
                return;
            }
            off = true;
            // under the lock a recompute installs under (applyEdges), so a link either is in this snapshot or is
            // refused by the recompute itself
            had = Map.copyOf(VILLAGES);
            VILLAGES.clear();
        }
        if (WARNED.add("off")) {
            if (t == null) {
                LOG.warn("TownKit transport: {}; transport is off and villagers walk as Towntopia makes them", reason);
            } else {
                LOG.warn("TownKit transport: {}; transport is off and villagers walk as Towntopia makes them", reason, t);
            }
        }
        List<LinkTable.Link> installed = new ArrayList<>();
        for (VillageLinks vl : had.values()) {
            for (TransportLink l : vl.links) {
                installed.add(new LinkTable.Link(key(l.from()), key(l.to()), l.cost(), l.bothWays(), false, 0L));
            }
        }
        refuse(installed);
        if (had.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        if (server.isSameThread()) {
            removeAllEdges(server, had);
        } else {
            server.execute(() -> removeAllEdges(server, had));
        }
    }

    /**
     * Adds {@code more} to the pairs published refused since transport switched off and publishes them all
     * ({@link #publishOff}): {@link #switchOff}'s installed links, plus the new links of a recompute it interrupted
     * ({@link #applyEdges}).
     */
    static synchronized void refuse(List<LinkTable.Link> more) {
        List<LinkTable.Link> all = new ArrayList<>(refused);
        all.addAll(more);
        refused = List.copyOf(all);
        publishOff(refused);
    }

    /** Publishes {@code installed} with none of it steppable, and no far-stop seeding (see {@link #switchOff}). */
    static void publishOff(List<LinkTable.Link> installed) {
        NavBridge.publishLinks(LinkTable.refusing(installed), Map.of());
    }

    /** Takes every link edge of {@code had} out of its village's current route map; stops at the first failure. */
    private static void removeAllEdges(MinecraftServer server, Map<UUID, VillageLinks> had) {
        if (RETAIN_NEIGHBOURS == null) {
            return;
        }
        try {
            VillageManagerRegistry registry = VillageManagerRegistry.get(server);
            for (Map.Entry<UUID, VillageLinks> e : had.entrySet()) {
                VillageManager mgr = registry.get(e.getKey());
                NavGraph graph = mgr == null ? e.getValue().graph : mgr.getGraph();
                if (graph == null) {
                    continue;
                }
                for (TransportLink l : e.getValue().links) {
                    NavNode a = graph.nodeAt(l.from().getX(), l.from().getY(), l.from().getZ());
                    NavNode b = graph.nodeAt(l.to().getX(), l.to().getY(), l.to().getZ());
                    if (a != null && b != null) {
                        RETAIN_NEIGHBOURS.invokeExact(a, (Predicate<NavNode>) n -> n != b);
                        RETAIN_NEIGHBOURS.invokeExact(b, (Predicate<NavNode>) n -> n != a);
                    }
                }
            }
        } catch (Throwable t) {
            // already off and logged; the refused table covers the edges that are left
        }
    }

    /** The live kinds changed (any thread): every village's links are worked out again on the server thread. */
    static void kindsChanged() {
        dirty = true;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSameThread()) {
            recomputeAll(server);
        }
    }

    /** Server thread, every level tick: catches up on a change made off the server thread. */
    static void tickPending(MinecraftServer server) {
        if (dirty) {
            recomputeAll(server);
        }
    }

    /** A server started: its saved stops get their links on the first level tick. */
    static void serverStarted() {
        dirty = true;
    }

    /** The server stopped: nothing of its links may stay published (another world may load in this game). */
    static void serverStopped() {
        dirty = false;
        VILLAGES.clear();
        UNLOADED_FAR.clear();
        synchronized (LinkInstaller.class) {
            refused = List.of();
            NavBridge.publishLinks(LinkTable.EMPTY, Map.of());
        }
    }

    /** The links in village {@code villageId} now. */
    static List<TransportLink> links(UUID villageId) {
        VillageLinks vl = VILLAGES.get(villageId);
        return vl == null ? List.of() : vl.links;
    }

    /**
     * The link of village {@code villageId} a villager may ride now from the route tile {@code from} to {@code to}
     * (server thread): available, not marked failed at {@code nowMillis}, and taken a way it allows. It comes oriented
     * the way it is ridden: a both-ways link ridden from its {@code to} end comes with its ends swapped. Null when
     * there is none.
     */
    @Nullable
    static TransportLink rideable(UUID villageId, BlockPos from, BlockPos to, long nowMillis) {
        VillageLinks vl = VILLAGES.get(villageId);
        if (vl == null || !usable()) {
            return null;
        }
        List<TransportLink> links = vl.links;
        for (int i = 0; i < links.size(); i++) {
            TransportLink l = links.get(i);
            boolean forward = l.from().equals(from) && l.to().equals(to);
            boolean back = l.bothWays() && l.from().equals(to) && l.to().equals(from);
            if ((forward || back) && vl.available[i] && vl.failedUntil[i] <= nowMillis) {
                return forward ? l : new TransportLink(l.kind(), l.to(), l.from(), l.cost(), true);
            }
        }
        return null;
    }

    /** A link found by {@link #rideableIn}, oriented the way it is ridden, and the village whose link it is. */
    record Rideable(UUID villageId, TransportLink link) {
    }

    /**
     * The link of any village in {@code dim} a walker may ride now from the route tile {@code from} to {@code to}, as
     * {@link #rideable} (server thread). For walkers whose village isn't the link's, or that have none (travelers):
     * claims don't overlap, so at most one village in a dimension has a link on that pair. Null when none does.
     */
    @Nullable
    static Rideable rideableIn(ResourceKey<Level> dim, BlockPos from, BlockPos to, long nowMillis) {
        for (Map.Entry<UUID, VillageLinks> e : VILLAGES.entrySet()) {
            if (e.getValue().dim.equals(dim)) {
                TransportLink l = rideable(e.getKey(), from, to, nowMillis);
                if (l != null) {
                    return new Rideable(e.getKey(), l);
                }
            }
        }
        return null;
    }

    /**
     * A ride over {@code ridden} (either orientation) failed: the link is kept out of routes, both ways, until
     * {@code untilMillis}, and republished (server thread). Kept while the link stays, through rebuilds and
     * availability refreshes.
     */
    static void markFailed(UUID villageId, TransportLink ridden, long untilMillis) {
        VillageLinks vl = VILLAGES.get(villageId);
        int i = vl == null ? -1 : indexOf(vl.links, ridden);
        if (i >= 0 && vl.failedUntil[i] < untilMillis) {
            vl.failedUntil[i] = untilMillis;
            publish();
        }
    }

    /**
     * Whether a ride over {@code ridden} (either orientation) may go on (server thread): it is still one of village
     * {@code villageId}'s links and its kind said it is available at the last refresh. False once a stop of it is gone
     * (a frame broken, a marker taken out), once the kind says it isn't available (an elevator drained), or when
     * transport is off.
     */
    static boolean stillRideable(UUID villageId, TransportLink ridden) {
        VillageLinks vl = VILLAGES.get(villageId);
        if (vl == null || !usable()) {
            return false;
        }
        int i = indexOf(vl.links, ridden);
        return i >= 0 && vl.available[i];
    }

    private static int indexOf(List<TransportLink> links, TransportLink ridden) {
        for (int i = 0; i < links.size(); i++) {
            TransportLink l = links.get(i);
            if (l.kind().equals(ridden.kind()) && (l.from().equals(ridden.from()) && l.to().equals(ridden.to())
                    || l.from().equals(ridden.to()) && l.to().equals(ridden.from()))) {
                return i;
            }
        }
        return -1;
    }

    static void recomputeAll(MinecraftServer server) {
        dirty = false;
        if (!usable()) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            StopStore store = StopStore.of(level);
            for (UUID id : store.villages()) {
                seen.add(id);
                recompute(level, id, store);
            }
        }
        for (UUID id : List.copyOf(VILLAGES.keySet())) {
            if (!seen.contains(id)) {
                ServerLevel level = server.getLevel(VILLAGES.get(id).dim);
                if (level != null) {
                    recompute(level, id, StopStore.of(level));
                } else {
                    VILLAGES.remove(id);
                }
            }
        }
        publish();
    }

    /** A village's stops changed (server thread). */
    static void stopsChanged(ServerLevel level, UUID villageId) {
        if (!usable()) {
            return;
        }
        recompute(level, villageId, StopStore.of(level));
        publish();
    }

    private static void recompute(ServerLevel level, UUID villageId, StopStore store) {
        Map<ResourceLocation, List<TransportStop>> byKind = new LinkedHashMap<>();
        for (TransportStop s : store.stops(villageId)) {
            byKind.computeIfAbsent(s.kind(), k -> new ArrayList<>()).add(s);
        }
        List<TransportLink> out = new ArrayList<>();
        Set<TransportLink> seen = new HashSet<>();
        for (Map.Entry<ResourceLocation, List<TransportStop>> e : byKind.entrySet()) {
            TransportKind kind = TransportKinds.GLOBAL.live(e.getKey());
            if (kind == null) {
                continue;
            }
            List<TransportLink> got;
            try {
                got = kind.links(level, List.copyOf(e.getValue()));
            } catch (RuntimeException ex) {
                warnOnce("links:" + e.getKey(), "TownKit transport: kind {} threw while pairing stops; it has no links "
                        + "in that village (logged once)", e.getKey(), ex);
                continue;
            }
            Set<BlockPos> tiles = new HashSet<>();
            e.getValue().forEach(s -> tiles.add(s.tile()));
            for (TransportLink l : got == null ? List.<TransportLink>of() : got) {
                String bad = l == null ? "a null link" : !l.kind().equals(e.getKey()) ? "another kind's id"
                        : !tiles.contains(l.from()) || !tiles.contains(l.to()) ? "an end that is not one of its stop tiles"
                        : sideBySide(l.from(), l.to()) ? "two tiles side by side" : null;
                if (bad != null) {
                    warnOnce("bad:" + e.getKey() + bad, "TownKit transport: kind {} made a link with {}; dropped (logged "
                            + "once per kind and reason)", e.getKey(), bad);
                } else if (seen.add(l)) {
                    out.add(l);
                }
            }
        }
        VillageManager mgr = VillageManagerRegistry.get(level.getServer()).get(villageId);
        VillageLinks vl = VILLAGES.get(villageId);
        List<TransportLink> old = vl == null ? List.of() : vl.links;
        NavGraph graph = mgr == null ? null : mgr.getGraph();
        // worked out before the install (kinds' code runs outside the installer lock)
        boolean[] available = new boolean[out.size()];
        long[] failedUntil = new long[out.size()];
        long[] wasFailed = vl == null ? new long[0] : vl.failedUntil;
        for (int i = 0; i < out.size(); i++) {
            available[i] = available(level, out.get(i));
            // a link that is still there keeps its failure
            int was = old.indexOf(out.get(i));
            failedUntil[i] = was >= 0 && was < wasFailed.length ? wasFailed[was] : 0L;
        }
        long version = mgr == null ? Long.MIN_VALUE : version(mgr);
        EdgeOps ops = graph == null ? NO_EDGES : new EdgeOps() {
            @Override
            public void add(TransportLink l) {
                addEdge(graph, mgr, l, true);
            }

            @Override
            public void remove(TransportLink l) {
                removeEdge(graph, l);
            }
        };
        // installed only while transport is on (applyEdges checks under the lock switchOff snapshots under)
        applyEdges(ops, old, seen, out, () -> {
            if (out.isEmpty()) {
                VILLAGES.remove(villageId);
                return;
            }
            VillageLinks v = VILLAGES.computeIfAbsent(villageId, id -> new VillageLinks(level.dimension()));
            v.available = available;
            v.failedUntil = failedUntil;
            v.links = List.copyOf(out);
            v.graph = graph;
            v.version = version;
            v.cursor = 0;
        });
    }

    private static final EdgeOps NO_EDGES = new EdgeOps() {
        @Override
        public void add(TransportLink l) {
        }

        @Override
        public void remove(TransportLink l) {
        }
    };

    /** A route map's link edges, as {@link #applyEdges} changes them (a seam for tests). */
    interface EdgeOps {
        void add(TransportLink l);

        void remove(TransportLink l);
    }

    /**
     * Takes out the edges of {@code old} links not in {@code keep}, then adds the edges of {@code out}; returns the
     * links whose edges were added. No edge is added once transport is off (checked before every add). Then, under
     * the installer lock (the one {@link #switchOff} sets {@code off} and snapshots the installed links under),
     * {@code install} runs while transport is still on: a later switch-off finds the new links installed and refuses
     * and removes them itself. When it switched off first (an edge op failed, or another thread), every pair of
     * {@code out} is published refused instead ({@link #refuse}, the same table {@link #switchOff} publishes, as those
     * pairs are in no installed row), and the edges already added are taken out again, best effort (stops at the
     * first failure).
     */
    static List<TransportLink> applyEdges(EdgeOps ops, List<TransportLink> old, Set<TransportLink> keep,
            List<TransportLink> out) {
        return applyEdges(ops, old, keep, out, () -> { });
    }

    /** {@link #applyEdges(EdgeOps, List, Set, List)}, installing the links with {@code install}. */
    static List<TransportLink> applyEdges(EdgeOps ops, List<TransportLink> old, Set<TransportLink> keep,
            List<TransportLink> out, Runnable install) {
        List<TransportLink> added = new ArrayList<>();
        try {
            for (TransportLink l : old) {
                if (off) {
                    break;
                }
                if (!keep.contains(l)) {
                    ops.remove(l);
                }
            }
            for (TransportLink l : out) {
                if (off) {
                    break;
                }
                ops.add(l);
                added.add(l);
            }
        } catch (Throwable t) {
            fail("changing a link edge", t);
        }
        synchronized (LinkInstaller.class) {
            if (!off) {
                install.run();
                return added;
            }
        }
        List<LinkTable.Link> rows = new ArrayList<>(out.size());
        for (TransportLink l : out) {
            rows.add(new LinkTable.Link(key(l.from()), key(l.to()), l.cost(), l.bothWays(), false, 0L));
        }
        refuse(rows);
        try {
            for (TransportLink l : added) {
                ops.remove(l);
            }
        } catch (Throwable t) {
            // already off and logged; the refused table covers the edges that are left
        }
        return added;
    }

    /** Whether transport switched off ({@link #switchOff}). */
    static boolean isOff() {
        return off;
    }

    /** Tests only: transport on again, nothing installed or published. */
    static void resetForTests() {
        synchronized (LinkInstaller.class) {
            off = false;
            refused = List.of();
        }
        VILLAGES.clear();
        NavBridge.publishLinks(LinkTable.EMPTY, Map.of());
    }

    /** Tiles Towntopia itself may join (4 sides, or a ladder rung above or below) can't be a link. */
    static boolean sideBySide(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dz = Math.abs(a.getZ() - b.getZ());
        return dx + dz == 1 || dx + dz == 0 && Math.abs(a.getY() - b.getY()) <= 1;
    }

    private static boolean available(ServerLevel level, TransportLink l) {
        TransportKind kind = TransportKinds.GLOBAL.live(l.kind());
        if (kind == null) {
            return false;
        }
        try {
            return kind.available(level, l);
        } catch (RuntimeException ex) {
            warnOnce("available:" + l.kind(), "TownKit transport: kind {} threw in available(); its link counts as not "
                    + "available (logged once)", l.kind(), ex);
            return false;
        }
    }

    private static long version(VillageManager mgr) {
        return GRAPH_VERSION.read(mgr);
    }

    /**
     * Adds {@code l}'s edge both ways (even one way: the bridge refuses the wrong direction). With {@code markMissing},
     * an end without a node gets the other end's tile marked dirty, so the next local update re-fills it and seeds the
     * far side.
     */
    private static void addEdge(NavGraph graph, VillageManager mgr, TransportLink l, boolean markMissing) {
        NavNode a = graph.nodeAt(l.from().getX(), l.from().getY(), l.from().getZ());
        NavNode b = graph.nodeAt(l.to().getX(), l.to().getY(), l.to().getZ());
        try {
            if (a != null && b != null) {
                ADD_NEIGHBOUR.invokeExact(a, b);
                ADD_NEIGHBOUR.invokeExact(b, a);
            } else if (markMissing && a != null) {
                mgr.markNavigationDirtyAt(l.from());
            } else if (markMissing && b != null && l.bothWays()) {
                mgr.markNavigationDirtyAt(l.to());
            }
        } catch (Throwable t) {
            fail("adding a link edge", t);
        }
    }

    private static void removeEdge(NavGraph graph, TransportLink l) {
        NavNode a = graph.nodeAt(l.from().getX(), l.from().getY(), l.from().getZ());
        NavNode b = graph.nodeAt(l.to().getX(), l.to().getY(), l.to().getZ());
        if (a == null || b == null) {
            return;
        }
        try {
            RETAIN_NEIGHBOURS.invokeExact(a, (Predicate<NavNode>) n -> n != b);
            RETAIN_NEIGHBOURS.invokeExact(b, (Predicate<NavNode>) n -> n != a);
        } catch (Throwable t) {
            fail("removing a link edge", t);
        }
    }

    /** After {@code VillageManager#tickNavRebuild(ServerLevel, int, long)} (server thread). */
    private static void afterNavRebuild(HookCall call) {
        if (!usable() || !(call.self() instanceof VillageManager mgr)) {
            return;
        }
        VillageLinks vl = VILLAGES.get(mgr.getVillageId());
        if (vl == null) {
            return;
        }
        NavGraph graph = mgr.getGraph();
        long v = version(mgr);
        if (graph != null && (graph != vl.graph || v != vl.version)) {
            // the first graph the links meet (after a world load the links may come before the village's route map)
            // marks a missing far side dirty once; later changes don't, so a far tile that can't be mapped can't loop
            boolean first = vl.graph == null;
            for (TransportLink l : vl.links) {
                if (off) {
                    break; // a failed addEdge switched transport off and cleared the graph: add nothing more to it
                }
                addEdge(graph, mgr, l, first);
            }
            vl.graph = graph;
            vl.version = v;
        }
        if (graph != null && !UNLOADED_FAR.isEmpty() && call.args()[0] instanceof ServerLevel sl) {
            Set<Long> far = UNLOADED_FAR.get(sl.dimension());
            if (far != null) {
                retryUnloaded(sl, graph, mgr, vl, far);
                if (far.isEmpty()) {
                    UNLOADED_FAR.remove(sl.dimension());
                }
            }
        }
        if (call.args()[0] instanceof ServerLevel level && call.args()[1] instanceof Integer tick
                && Math.floorMod(tick + mgr.getVillageId().hashCode(), 40) == 0) {
            refresh(level, vl);
        }
    }

    /** Marks a link's start dirty once the far side a fill skipped (chunk not loaded) can be read; still unmapped. */
    private static void retryUnloaded(ServerLevel level, NavGraph graph, VillageManager mgr, VillageLinks vl,
            Set<Long> unloaded) {
        for (TransportLink l : vl.links) {
            retryEnd(level, graph, mgr, l.from(), l.to(), unloaded);
            if (l.bothWays()) {
                retryEnd(level, graph, mgr, l.to(), l.from(), unloaded);
            }
        }
    }

    private static void retryEnd(ServerLevel level, NavGraph graph, VillageManager mgr, BlockPos start, BlockPos far,
            Set<Long> unloaded) {
        long k = key(far);
        if (!unloaded.contains(k) || !level.hasChunkAt(far)) {
            return;
        }
        unloaded.remove(k);
        if (graph.nodeAt(far.getX(), far.getY(), far.getZ()) == null
                && graph.nodeAt(start.getX(), start.getY(), start.getZ()) != null) {
            mgr.markNavigationDirtyAt(start);
        }
    }

    /** Availability again, from where the last pass stopped, within {@link #REFRESH_BUDGET_NANOS}. */
    private static void refresh(ServerLevel level, VillageLinks vl) {
        long start = System.nanoTime();
        List<TransportLink> links = vl.links;
        boolean[] available = vl.available;
        boolean changed = false;
        int i = vl.cursor;
        for (; i < links.size(); i++) {
            if (i > vl.cursor && System.nanoTime() - start > REFRESH_BUDGET_NANOS) {
                break;
            }
            boolean a = available(level, links.get(i));
            changed |= a != available[i];
            available[i] = a;
        }
        vl.cursor = i >= links.size() ? 0 : i;
        if (changed) {
            publish();
        }
    }

    /**
     * Publishes every installed link to the bridge: one table for the search, one per dimension for the fill. Under
     * the installer lock, so a publish that started before a switch-off can't land over its refused table (server
     * thread only; the bridge's readers never take this lock).
     */
    static synchronized void publish() {
        if (off) {
            return;
        }
        List<LinkTable.Link> all = new ArrayList<>();
        Map<ResourceKey<Level>, List<LinkTable.Link>> per = new HashMap<>();
        for (VillageLinks vl : VILLAGES.values()) {
            List<TransportLink> links = vl.links;
            boolean[] available = vl.available;
            long[] failedUntil = vl.failedUntil;
            for (int i = 0; i < links.size(); i++) {
                TransportLink l = links.get(i);
                LinkTable.Link row = new LinkTable.Link(key(l.from()), key(l.to()), l.cost(), l.bothWays(),
                        available[i], failedUntil[i]);
                all.add(row);
                per.computeIfAbsent(vl.dim, k -> new ArrayList<>()).add(row);
            }
        }
        Map<ResourceKey<Level>, LinkTable> byLevel = new HashMap<>();
        per.forEach((dim, rows) -> byLevel.put(dim, LinkTable.of(rows)));
        NavBridge.publishLinks(LinkTable.of(all), Map.copyOf(byLevel));
    }

    static long key(BlockPos feet) {
        return NavNode.key(feet.getX(), feet.getY(), feet.getZ());
    }

    /**
     * The POLL seeding (from {@link NavBridge#polled}, server thread, inside the flood fill): {@code cur} is one end
     * of a link whose other end is node key {@code farKey}. A missing far node is made like Towntopia's own (its
     * {@code makeNode}, then {@code record}, put in {@code nodes} and queued) when {@code mayCreate} (the link may be
     * taken that way) and its tile can be stood on in a loaded chunk; then the edge is added both ways.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void seed(Level level, Map nodes, Queue queue, NavNode cur, long farKey, boolean mayCreate) {
        if (!usable()) {
            return;
        }
        try {
            NavNode far = (NavNode) nodes.get(farKey);
            if (far == null) {
                if (!mayCreate) {
                    return;
                }
                BlockPos feet = BlockPos.of(farKey);
                if (!level.hasChunkAt(feet)) {
                    // mapped later: the after-hook marks the start dirty once the chunk is loaded
                    UNLOADED_FAR.computeIfAbsent(level.dimension(), d -> new HashSet<>()).add(farKey);
                    return;
                }
                NavGraph.StandingCandidate c = NavGraph.standingCandidate(level, feet);
                if (!c.surface() || !c.headroom() || !c.ledgeSafe()) {
                    return;
                }
                far = (NavNode) MAKE_NODE.invokeExact(level, feet.getX(), feet.getZ(), feet.getY() - 1);
                if (far == null) {
                    return;
                }
                nodes.put(farKey, far);
                RECORD.invokeExact(far);
                queue.add(far);
            }
            ADD_NEIGHBOUR.invokeExact(cur, far);
            ADD_NEIGHBOUR.invokeExact(far, cur);
        } catch (Throwable t) {
            fail("seeding a link's far stop", t);
        }
    }

    /**
     * The node Towntopia would make for the tile whose feet are at {@code feet} (not added to any graph), or null
     * when it can't be stood on. For checking a stop tile the graph has no node for yet.
     */
    @Nullable
    static NavNode probe(Level level, BlockPos feet) {
        if (!usable() || !level.hasChunkAt(feet)) {
            return null;
        }
        NavGraph.StandingCandidate c = NavGraph.standingCandidate(level, feet);
        if (!c.surface() || !c.headroom() || !c.ledgeSafe()) {
            return null;
        }
        try {
            return (NavNode) MAKE_NODE.invokeExact(level, feet.getX(), feet.getZ(), feet.getY() - 1);
        } catch (Throwable t) {
            fail("reading a stop tile", t);
            return null;
        }
    }

    private static void warnOnce(String key, String msg, Object a) {
        if (WARNED.add(key)) {
            LOG.warn(msg, a);
        }
    }

    private static void warnOnce(String key, String msg, Object a, Object b) {
        if (WARNED.add(key)) {
            LOG.warn(msg, a, b);
        }
    }
}
