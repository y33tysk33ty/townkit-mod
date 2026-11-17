package townkit.core.nav;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.RouteRules;
import com.alex.test.village.WalkFeel;
import com.mojang.logging.LogUtils;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

/**
 * The bridge Towntopia's rewritten nav sites call (see {@link NavSiteRewriter} and {@code nav-sites.tsv}). With no
 * path rule and no transport link, every method gives Towntopia's own answer after one static boolean read
 * ({@link #ACTIVE}).
 *
 * <p>Every method runs after Towntopia's own instruction and gets its answer: the instruction stays in Towntopia's
 * code, so another mod's mixin aimed at it still applies.
 *
 * <p>Fast paths: {@link #afterStep}, {@link #afterSearchStep}, {@link #cellKind}, {@link #cost}, {@link #lineOfSight},
 * {@link #pace} and {@link #polled} stay under HotSpot's 35-byte inlining limit (checked by
 * {@code NavSiteRewriterTest.fastPathUnderInlineLimit}), so the JIT inlines them into Towntopia's code.
 *
 * <p>Threads: the route search runs on up to four worker threads. Slow paths read only the published {@link State}
 * and {@link LinkTable} (one volatile read into a local per call), never mutate the snapshot's maps (they are not
 * wrapped read-only), and allocate nothing of their own. {@link #lineOfSight}'s slow path calls Towntopia's
 * {@code NavGraph.nodeAt}, which boxes its {@code long} key exactly as Towntopia's own {@code lineOfSight} (run just
 * before, on the same tiles) does. {@link #polled} runs on the server thread only (the flood fill).
 *
 * <p>Never rules: a tile whose surface block has a never rule can't be stepped onto. Leaving one is allowed (only the
 * tile stepped onto is checked), and in the route search a never goal is still reached. Never is read from the
 * villager map for every walker, raiders included.
 *
 * <p>Transport links: a pair of node keys in the published {@link LinkTable} is a link step. Its answer
 * replaces Towntopia's step test (an elevator fails the rise test, a hop over a wall passes it): steppable only while
 * the link is available, the right way and not marked failed, and never in a raider search. A link onto a never tile
 * is still refused. Pairs not in the table get Towntopia's answer. The table is global (node keys carry no dimension):
 * the link edges themselves are only added in their own dimension's graph, so a pair only matters where such an edge
 * exists.
 */
public final class NavBridge {
    /** Everything the slow paths read, published in one volatile write. */
    private record State(PathRuleTable.Snapshot<Block> snap, boolean anyNever) {
    }

    private static final State EMPTY = new State(new PathRuleTable.Snapshot<>(new IdentityHashMap<>(),
            new IdentityHashMap<>(), 1, false), false);
    private static volatile State state = EMPTY;
    private static volatile LinkTable links = LinkTable.EMPTY;
    /** The same links per dimension, for the flood fill's far-stop seeding ({@link #polled}). */
    private static volatile Map<ResourceKey<Level>, LinkTable> linksByLevel = Map.of();
    private static boolean rulesActive;

    /** The link table the bridge reads now. */
    static LinkTable links() {
        return links;
    }

    /**
     * True while any path rule exists or any transport link is installed (a link whose kind says it is unavailable
     * stays installed, refused, so it can't fall back to Towntopia's own step test). Set only by {@link #publish} and
     * {@link #publishLinks}.
     */
    static volatile boolean ACTIVE;
    /** True while any path rule changes walking speed. Set only by {@link #publish}; read by {@link #pace}. */
    static volatile boolean PACE_ACTIVE;

    private static final Logger LOGGER = LogUtils.getLogger();
    static final AtomicBoolean PACE_WARNED = new AtomicBoolean();

    private NavBridge() {
    }

    /**
     * Makes {@code snap} the rules the bridge reads, and sets {@link #ACTIVE} (the snapshot has any entry, or a link
     * is installed) and {@link #PACE_ACTIVE} ({@code snap.anyPace()}). The path rule table's {@code onPublish} listener
     * calls it, under the table's lock. The snapshot is stored before the flags,
     * so a reader that sees a flag set also sees its snapshot. The maps must not be changed afterwards.
     */
    static synchronized void publish(PathRuleTable.Snapshot<Block> snap) {
        boolean anyNever = false;
        for (PathRuleTable.Entry e : snap.villager().values()) {
            anyNever |= e.never();
        }
        state = new State(snap, anyNever);
        PACE_ACTIVE = snap.anyPace();
        rulesActive = !snap.villager().isEmpty() || !snap.raider().isEmpty();
        ACTIVE = rulesActive || !links.isEmpty();
    }

    /**
     * Makes {@code all} the links the bridge reads ({@code byLevel}: the same links per dimension) and updates
     * {@link #ACTIVE}. Called by {@link LinkInstaller} on the server thread; neither argument may change afterwards.
     */
    static synchronized void publishLinks(LinkTable all, Map<ResourceKey<Level>, LinkTable> byLevel) {
        links = all;
        linksByLevel = byLevel;
        ACTIVE = rulesActive || !all.isEmpty();
    }

    // ---- STEP ----

    /**
     * Runs right after Towntopia's own {@code from.canStepTo(to)}, which answered {@code original} (another mod's mixin
     * may have wrapped it; its answer is kept): that answer, and {@code to} is not a never tile; a link step's answer
     * is the link's. {@code site} is the nav-sites.tsv row.
     */
    public static boolean afterStep(NavNode from, NavNode to, boolean original, int site) {
        return ACTIVE ? slowStep(from, to, original, null, false) : original;
    }

    /**
     * The STEP site inside {@code AStarPathfinder#search}: as {@link #afterStep}, except that a never tile that is the
     * search's goal is allowed, and a link step is refused unless {@code rules} is Towntopia's villager rules (raiders
     * and wide walkers never ride).
     */
    public static boolean afterSearchStep(NavNode from, NavNode to, boolean original, NavNode goal, RouteRules rules,
            int site) {
        return ACTIVE ? slowSearchStep(from, to, original, goal, rules) : original;
    }

    private static boolean slowSearchStep(NavNode from, NavNode to, boolean original, NavNode goal,
            @Nullable RouteRules rules) {
        return slowStep(from, to, original, goal, rules != null && !rules.allowsEverything());
    }

    private static boolean slowStep(NavNode from, NavNode to, boolean original, @Nullable NavNode goal,
            boolean noLinks) {
        State st = state;
        LinkTable lt = links;
        int row = lt.isEmpty() ? -1 : lt.indexOf(key(from), key(to));
        if (row >= 0) {
            // a link step: the link decides, whatever Towntopia's rise test says
            if (noLinks || !lt.steppable(row, System.currentTimeMillis())) {
                return false;
            }
        } else if (!original) {
            return false;
        }
        if (!st.anyNever || to == goal) {
            return true;
        }
        PathRuleTable.Entry e = st.snap.villager().get(to.surfaceBlock);
        return e == null || !e.never();
    }

    private static long key(NavNode n) {
        return NavNode.key(n.x, n.feetY, n.z);
    }

    // ---- CELL ----

    /**
     * The reachability adapter's step kind ({@code NavCells$1#kind}), after Towntopia's own gave {@code original}
     * (-1 no step, 0 open, 1 blocked for now, 2 routable to non-routable). Towntopia's -1 and 2 stand; otherwise a
     * step onto a never tile is 2. Unlike -1 that keeps the tile out of island removal (Towntopia's island flood
     * follows kinds up to 2), while access (kinds up to 1: {@code BuildingAccess}, {@code cells().accessible}) and
     * {@code cells().reaches} (kind 0) treat it as blocked: a goal on, or only behind, never tiles counts as
     * unreachable. Kind 1 would not do: the access flood follows it. Leaving a never tile is unchanged (only
     * {@code to} is checked).
     *
     * <p>A link step is 0 while steppable (where Towntopia's rise test said -1; its other answers stand), else 2 (an unavailable, failed or wrong-way link: kept by the island flood, so a local update doesn't
     * delete the far side, but not followed by access or reaches). A never tile at its far end is still 2.
     */
    public static int cellKind(int original, NavNode from, NavNode to) {
        return ACTIVE ? slowCellKind(original, from, to) : original;
    }

    private static int slowCellKind(int original, NavNode from, NavNode to) {
        LinkTable lt = links;
        int row = lt.isEmpty() ? -1 : lt.indexOf(key(from), key(to));
        if (row >= 0) {
            if (!lt.steppable(row, System.currentTimeMillis())) {
                return 2;
            }
            if (original == -1) {
                // both ends are routable stop tiles (a stop whose tile stops being routable is dropped)
                original = 0;
            }
        }
        if (original == -1 || original == 2) {
            return original;
        }
        State st = state;
        if (!st.anyNever) {
            return original;
        }
        PathRuleTable.Entry e = st.snap.villager().get(to.surfaceBlock);
        return e != null && e.never() ? 2 : original;
    }

    // ---- COST ----

    /**
     * The step cost after Towntopia's {@code edgeCost} gave {@code edge}: scaled by the rule on {@code nb}'s surface
     * block (villager or raider map, picked by {@code rules.allowsEverything()}) and the snapshot's cost scale.
     * {@code rules == null} (the {@code price} sites) means a villager.
     */
    public static int cost(int edge, NavNode cur, NavNode nb, @Nullable RouteRules rules) {
        return ACTIVE ? slowCost(edge, cur, nb, rules) : edge;
    }

    private static int slowCost(int edge, NavNode cur, NavNode nb, @Nullable RouteRules rules) {
        PathRuleTable.Snapshot<Block> s = state.snap;
        LinkTable lt = links;
        int row = lt.isEmpty() ? -1 : lt.indexOf(key(cur), key(nb));
        if (row >= 0) {
            // at least the x/z distance covered, so Towntopia's Manhattan heuristic stays admissible
            int dist = Math.abs(nb.x - cur.x) + Math.abs(nb.z - cur.z);
            int scale = WalkFeel.climbCost ? 10 : 1;
            return PathRuleTable.scaleCost(Math.max(lt.cost(row), dist) * scale, 64, s.costScale());
        }
        IdentityHashMap<Block, PathRuleTable.Entry> map = rules == null || rules.allowsEverything() ? s.villager()
                : s.raider();
        PathRuleTable.Entry e = map.get(nb.surfaceBlock);
        return PathRuleTable.scaleCost(edge, e == null ? 64 : e.cost64(), s.costScale());
    }

    // ---- LOS ----

    /**
     * The line-of-sight test in {@code AStarPathfinder#smooth}, after Towntopia's own gave {@code original}: false
     * also when a never tile lies on the straight leg between {@code from} and {@code to} (the ends themselves were
     * allowed by the search). {@code graph} is smooth's own graph parameter, read only through {@code nodeAt}.
     */
    public static boolean lineOfSight(boolean original, NavGraph graph, NavNode from, NavNode to) {
        return ACTIVE ? slowLineOfSight(original, graph, from, to) : original;
    }

    /** Walks the same tiles as Towntopia's {@code lineOfSight} (Bresenham on x/z, both corners of a diagonal step). */
    private static boolean slowLineOfSight(boolean original, NavGraph graph, NavNode from, NavNode to) {
        State st = state;
        if (!original || !st.anyNever) {
            return original;
        }
        IdentityHashMap<Block, PathRuleTable.Entry> villager = st.snap.villager();
        int y = from.feetY;
        int x = from.x;
        int z = from.z;
        int tx = to.x;
        int tz = to.z;
        int dx = Math.abs(tx - x);
        int dz = Math.abs(tz - z);
        int sx = x < tx ? 1 : -1;
        int sz = z < tz ? 1 : -1;
        int err = dx - dz;
        while (x != tx || z != tz) {
            int e2 = 2 * err;
            boolean stepX = e2 > -dz;
            boolean stepZ = e2 < dx;
            if (stepX && stepZ && (never(villager, graph, from, to, x + sx, y, z)
                    || never(villager, graph, from, to, x, y, z + sz))) {
                return false;
            }
            if (stepX) {
                err -= dz;
                x += sx;
            }
            if (stepZ) {
                err += dx;
                z += sz;
            }
            if (never(villager, graph, from, to, x, y, z)) {
                return false;
            }
        }
        return true;
    }

    private static boolean never(IdentityHashMap<Block, PathRuleTable.Entry> villager, NavGraph graph, NavNode from,
            NavNode to, int x, int y, int z) {
        NavNode n = graph.nodeAt(x, y, z);
        if (n == null || n == from || n == to) {
            return false;
        }
        PathRuleTable.Entry e = villager.get(n.surfaceBlock);
        return e != null && e.never();
    }

    // ---- PACE ----

    /**
     * The PACE site in {@code FollowWalk#tickLine}: the speed handed to {@code setWantedPosition} for {@code npc} on its
     * route line, times the speed rule on the block under its feet. {@code FollowWalk.speed} itself (the run, jump
     * and climb thresholds) is never changed; ladders, the join phase and rides don't come through here.
     */
    public static double pace(double speed, PathfinderMob npc) {
        return PACE_ACTIVE ? slowPace(speed, npc) : speed;
    }

    /**
     * Server thread. Reads the block at {@code BlockPos.containing(x, y - 0.2, z)} (a loaded one only). No mob leaves
     * the speed as it was. Fails soft: a mob or level that can't be read (or a missing Towntopia class) leaves the
     * speed as it was, logged once.
     */
    private static double slowPace(double speed, @Nullable PathfinderMob npc) {
        if (npc == null) {
            return speed;
        }
        try {
            Level level = npc.level();
            if (level.isClientSide()) {
                return speed;
            }
            BlockPos under = BlockPos.containing(npc.getX(), npc.getY() - 0.2, npc.getZ());
            if (!level.isLoaded(under)) {
                return speed;
            }
            return paceOn(speed, level.getBlockState(under).getBlock(), npc instanceof NpcEntity);
        } catch (RuntimeException | LinkageError e) {
            if (PACE_WARNED.compareAndSet(false, true)) {
                LOGGER.warn("TownKit nav: could not read the block under a walker for a speed rule; it walks at its own "
                        + "speed (logged once)", e);
            }
            return speed;
        }
    }

    /**
     * {@code speed} times {@code speed64 / 64} of the rule on {@code under}: from the villager map for Towntopia's
     * NPCs ({@code villager}), else from the raider map. No rule (or one without a speed) leaves it as it was.
     */
    static double paceOn(double speed, @Nullable Block under, boolean villager) {
        if (under == null) {
            return speed;
        }
        PathRuleTable.Snapshot<Block> s = state.snap;
        PathRuleTable.Entry e = (villager ? s.villager() : s.raider()).get(under);
        return e == null || e.speed64() == 64 ? speed : speed * e.speed64() / 64.0;
    }

    // ---- POLL ----

    /**
     * The POLL site: {@code queue.poll()} in {@code NavGraph#floodFillInner} (full builds, sliced builds and local
     * updates), which hands back {@code node} unchanged. While links exist in {@code level}'s dimension and
     * {@code node} is one end of a link, its far end gets its node first (made and queued like Towntopia's own when
     * it is missing and the link may be taken that way) and the link edge both ways, before the fill goes on: a
     * region reachable only by a link is mapped, and the edge is there for every check Towntopia runs right after the
     * build. Server thread.
     */
    public static Object polled(Object node, Queue queue, Map nodes, Level level) {
        return ACTIVE ? slowPolled(node, queue, nodes, level) : node;
    }

    @SuppressWarnings("rawtypes")
    private static Object slowPolled(Object node, Queue queue, Map nodes, Level level) {
        if (!(node instanceof NavNode cur)) {
            return node;
        }
        LinkTable lt = linksByLevel.get(level.dimension());
        if (lt == null) {
            return node;
        }
        long k = key(cur);
        for (int i = lt.firstFrom(k); i >= 0 && i < lt.size() && lt.from(i) == k; i++) {
            LinkInstaller.seed(level, nodes, queue, cur, lt.to(i), lt.forward(i));
        }
        return node;
    }
}
