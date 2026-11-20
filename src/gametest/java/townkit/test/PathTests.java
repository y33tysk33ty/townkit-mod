package townkit.test;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.visitor.NomadEntity;
import net.minecraft.world.entity.PathfinderMob;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.AStarPathfinder;
import com.alex.test.village.FollowWalk;
import com.alex.test.village.MoveToCommand;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.RouteRules;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.RoomScanner;
import com.alex.test.village.tokens.VillageTokenBinding;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import townkit.api.nav.Ride;
import townkit.api.nav.RideContext;
import townkit.api.nav.RideStatus;
import townkit.api.nav.TownKitTransport;
import townkit.api.nav.TransportKind;
import townkit.api.nav.TransportLink;
import townkit.api.nav.TransportStop;
import townkit.core.nav.StopStore;
import townkit.core.village.VillageHandle;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import com.alex.test.village.building.BuildingAccess;
import com.alex.test.village.chat.RegistrationFailure;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntPredicate;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import townkit.core.nav.RideEngine;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.nav.PathRule;
import townkit.api.nav.TownKitPaths;
import townkit.core.TownKit;
import townkit.core.hook.Hooks;
import townkit.core.nav.NavBridge;
import townkit.test.BehaviourTests.TestVillage;

/**
 * Path rules in the world: cost, never and the raider toggle change Towntopia's own route search,
 * smoothing and reachability, and nothing changes with no rule. Transport links (a test-only kind, test marker
 * frames) join the route map, survive rebuilds and count for reachability. Rides over a link carry the villager
 * on, and every way a ride can end (failed, timed out, unsafe far stop, interrupted, unloaded) leaves it on a stop with
 * no ride state left. The bubble elevator (up, down, off until enabled, drained or a stop broken mid-ride).
 * Each test has its own village (400 blocks apart) and
 * batch (one simulated player). The tests build in the open field north of the town hall; every rule and hook a test
 * adds is removed when it ends, whatever happens (a leaked never or cost rule would change other tests' routes).
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class PathTests {
    private PathTests() {
    }

    /** Test floor blocks: no other test village uses them, so a rule on one only reaches this test's tiles. */
    static final Block ROAD = Blocks.GRAY_CONCRETE;
    static final Block NEVER = Blocks.RED_CONCRETE;
    static final Block MARK = Blocks.YELLOW_CONCRETE;
    static final Block FAST = Blocks.CYAN_CONCRETE;

    /** Towntopia's own line-of-sight test (private), to show a leg it would have taken. */
    static final MethodHandle LINE_OF_SIGHT;

    static {
        MethodHandle h;
        try {
            h = MethodHandles.privateLookupIn(AStarPathfinder.class, MethodHandles.lookup()).findStatic(AStarPathfinder.class,
                "lineOfSight", MethodType.methodType(boolean.class, NavGraph.class, NavNode.class, NavNode.class, Set.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            TownKit.LOG.error("[TownKit test] can't reach AStarPathfinder#lineOfSight", e);
            h = null;
        }
        LINE_OF_SIGHT = h;
    }

    /** One test: its village, the field origin {@code o} (12 north of the town hall door) and what it registered. */
    static final class Run {
        final GameTestHelper helper;
        final Checks c;
        final TestVillage tv;
        final ServerLevel level;
        final BlockPos o;
        final VillagerModel model;
        final List<Subscription> rules = new ArrayList<>();
        final List<Hooks.Entry> hooks = new ArrayList<>();
        final List<ItemFrame> frames = new ArrayList<>();
        /** Run first in {@link #end}, whatever happened (log appenders, forced chunks). */
        final List<Runnable> cleanups = new ArrayList<>();
        boolean over;

        Run(GameTestHelper helper, Checks c, TestVillage tv) {
            this.helper = helper;
            this.c = c;
            this.tv = tv;
            this.level = helper.getLevel();
            this.o = tv.door().north(12);
            this.model = tv.mgr().getModel(tv.farmer().getNpcId());
            this.model.setBrainSuspended(true);
        }

        Subscription rule(Block block, PathRule rule) {
            Subscription s = TownKitPaths.rule("townkit-test", block, rule);
            rules.add(s);
            return s;
        }

        void place(BlockPos pos, BlockState state) {
            PathSpikeTests.place(tv, level, pos, state);
        }

        /** Makes {@code block} the surface of the tile whose feet are at {@code feet}. */
        void floor(BlockPos feet, Block block) {
            place(feet.below(), block.defaultBlockState());
        }

        /** Re-reads the tile at {@code feet} into the route map (its reachability pieces are regrouped). */
        void refresh(BlockPos feet) {
            tv.mgr().markNavigationDirtyAt(feet);
            tv.mgr().markNavigationDirtyAt(feet.below());
            rebuild();
        }

        void rebuild() {
            tv.mgr().rebuildNow(level);
        }

        NavGraph graph() {
            return tv.mgr().getGraph();
        }

        @Nullable
        AStarPathfinder.Route route(BlockPos from, BlockPos to, boolean smooth, @Nullable RouteRules rules) {
            return AStarPathfinder.findRoute(graph(), from, to, smooth, Set.of(), Set.of(), rules == null ? RouteRules.VILLAGER : rules);
        }

        /** Removes every rule and hook first, then ends the test (finish throws on a failure). */
        void end() {
            if (over) return;
            over = true;
            for (Runnable cleanup : cleanups) {
                try {
                    cleanup.run();
                } catch (RuntimeException e) {
                    c.check("cleanup ran", false, e);
                }
            }
            frames.forEach(f -> {
                if (!f.isRemoved()) f.discard();
            });
            for (Subscription s : rules) {
                try {
                    s.remove();
                } catch (RuntimeException e) {
                    c.check("rule removed", false, e);
                }
            }
            hooks.forEach(Hooks::remove);
            c.check("no rule left behind", !TownKitPaths.active(), TownKitPaths.active());
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }
    }

    static Optional<Run> start(GameTestHelper helper, String name, int x, int z) {
        Checks c = new Checks(name);
        return BehaviourTests.village(helper, x, z).map(tv -> new Run(helper, c, tv));
    }

    /** Runs {@code body} 20 ticks after setup, then ends the test (rules removed) whatever the body did. */
    static void after20(Run r, Runnable body) {
        r.helper.runAfterDelay(20, () -> {
            try {
                body.run();
            } catch (RuntimeException | Error e) {
                r.c.check("no exception", false, e);
            }
            r.end();
        });
    }

    /** Calls {@code step} every tick from 20 ticks after setup until it answers true or {@code limit} ticks pass. */
    static void ticks(Run r, int limit, IntPredicate step) {
        int[] tick = {0};
        r.helper.onEachTick(() -> {
            if (r.over) return;
            boolean done;
            try {
                tick[0]++;
                if (tick[0] < 20) return;
                done = tick[0] - 20 > limit;
                if (done) r.c.check("finished within " + limit + " ticks", false, "timed out");
                else done = step.test(tick[0] - 20);
            } catch (RuntimeException | Error e) {
                r.c.check("no exception", false, e);
                done = true;
            }
            if (done) r.end();
        });
    }

    // ---- layouts ----

    /**
     * Two ways from {@code s} to {@code g} (2 north of it) round a 3-high glass wall: west over grass, 20 steps, through
     * the one-tile gap {@code westCorner} (glass on its west too, so going round it is 32 steps); east over
     * {@link #ROAD}, 24 steps, round x = o.x + 11.
     */
    record TwoRoutes(BlockPos s, BlockPos g, BlockPos westCorner, int westX, int eastX) {
    }

    static TwoRoutes twoRoutes(Run r) {
        BlockPos o = r.o;
        for (int dx = -8; dx <= 10; dx++) {
            for (int dy = 0; dy <= 2; dy++) r.place(o.offset(dx, dy, -1), Blocks.GLASS.defaultBlockState());
        }
        for (int dz = -3; dz <= 1; dz++) {
            for (int dy = 0; dy <= 2; dy++) r.place(o.offset(-10, dy, dz), Blocks.GLASS.defaultBlockState());
        }
        for (int dx = 1; dx <= 11; dx++) {
            r.floor(o.offset(dx, 0, 0), ROAD);
            r.floor(o.offset(dx, 0, -2), ROAD);
        }
        r.floor(o.offset(11, 0, -1), ROAD);
        return new TwoRoutes(o, o.north(2), o.offset(-9, 0, -1), o.getX() - 9, o.getX() + 11);
    }

    static boolean passesX(@Nullable AStarPathfinder.Route route, int x) {
        return route != null && route.tiles().stream().anyMatch(n -> n.x == x);
    }

    static boolean passes(@Nullable AStarPathfinder.Route route, BlockPos p) {
        return route != null && route.tiles().stream().anyMatch(n -> PathSpikeTests.at(n, p));
    }

    static int steps(@Nullable AStarPathfinder.Route route) {
        return route == null ? -1 : route.tiles().size() - 1;
    }

    static List<BlockPos> feet(@Nullable AStarPathfinder.Route route) {
        return route == null ? List.of() : route.tiles().stream().map(NavNode::feetPos).toList();
    }

    static String side(@Nullable AStarPathfinder.Route route, TwoRoutes t) {
        if (route == null) return "no route";
        return steps(route) + " steps, " + (passesX(route, t.westX()) ? "west" : passesX(route, t.eastX()) ? "east" : "neither side");
    }

    static boolean east(@Nullable AStarPathfinder.Route route, TwoRoutes t) {
        return passesX(route, t.eastX()) && !passesX(route, t.westX());
    }

    static boolean west(@Nullable AStarPathfinder.Route route, TwoRoutes t) {
        return passesX(route, t.westX()) && !passesX(route, t.eastX());
    }

    /** Whether the straight leg a -> b touches {@code n}: the x/z tiles Towntopia's line of sight walks (both corners of a diagonal step). */
    static boolean crosses(BlockPos a, BlockPos b, BlockPos n) {
        int x = a.getX();
        int z = a.getZ();
        int tx = b.getX();
        int tz = b.getZ();
        int dx = Math.abs(tx - x);
        int dz = Math.abs(tz - z);
        int sx = x < tx ? 1 : -1;
        int sz = z < tz ? 1 : -1;
        int err = dx - dz;
        while (x != tx || z != tz) {
            int e2 = 2 * err;
            boolean stepX = e2 > -dz;
            boolean stepZ = e2 < dx;
            if (stepX && stepZ && (hit(x + sx, z, n) || hit(x, z + sz, n))) return true;
            if (stepX) {
                err -= dz;
                x += sx;
            }
            if (stepZ) {
                err += dx;
                z += sz;
            }
            if (hit(x, z, n)) return true;
        }
        return false;
    }

    private static boolean hit(int x, int z, BlockPos n) {
        return x == n.getX() && z == n.getZ();
    }

    static boolean bridgeActive() {
        return bridgeFlag("ACTIVE");
    }

    static boolean paceActive() {
        return bridgeFlag("PACE_ACTIVE");
    }

    private static boolean bridgeFlag(String name) {
        try {
            var f = NavBridge.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NavBridge." + name + " unreadable", e);
        }
    }

    // ---- tests ----

    /** A road 24 steps long against plain grass 20 steps long: cost(0.5) on the road block makes the search take the road. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_paths_road")
    public static void roadIsChosen(GameTestHelper helper) {
        Run r = start(helper, "roadIsChosen", 7600, 8800).orElse(null);
        if (r == null) return;
        TwoRoutes t = twoRoutes(r);
        r.rebuild();
        after20(r, () -> {
            AStarPathfinder.Route plain = r.route(t.s(), t.g(), false, null);
            r.c.check("no rule: the plain route (20 steps, west) is taken", west(plain, t) && steps(plain) == 20, side(plain, t));
            r.rule(ROAD, PathRule.cost(0.5));
            AStarPathfinder.Route road = r.route(t.s(), t.g(), false, null);
            r.c.checkHooksOn("cost(0.5) on the road: the road route (24 steps, east) is taken", east(road, t) && steps(road) == 24, side(road, t));
        });
    }

    /** The only shortest route crosses one tile; with never on its block the search goes round it. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_paths_never")
    public static void neverAvoidsTile(GameTestHelper helper) {
        Run r = start(helper, "neverAvoidsTile", 8000, 8800).orElse(null);
        if (r == null) return;
        BlockPos s = r.o.west(5);
        BlockPos g = r.o.east(5);
        BlockPos n = r.o;
        r.floor(n, NEVER);
        r.rebuild();
        after20(r, () -> {
            AStarPathfinder.Route before = r.route(s, g, false, null);
            r.c.check("no rule: the straight 10-step route crosses the tile", passes(before, n) && steps(before) == 10, steps(before));
            r.rule(NEVER, PathRule.never());
            AStarPathfinder.Route after = r.route(s, g, false, null);
            r.c.check("never: a route is still found", after != null, steps(after));
            r.c.checkHooksOn("never: the route goes round the tile", after != null && !passes(after, n), feet(after));
        });
    }

    /** A room whose only way in is over a never tile counts as unreachable for BuildingAccess, and reachable again after remove(). */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_paths_building")
    public static void neverMakesBuildingUnreachable(GameTestHelper helper) {
        Run r = start(helper, "neverMakesBuildingUnreachable", 8400, 8800).orElse(null);
        if (r == null) return;
        BlockPos o = r.o;
        int g = o.getY();
        // a 5x5 room south of o, its door at o facing north; the tile in front of the door is its only way in
        BlockPos door = ScenarioBuilder.buildRoom(r.level, o.getX() - 2, g, o.getZ() + 1, ScenarioBuilder.RoomFurniture.NONE).doorPos();
        for (int x = o.getX() - 3; x <= o.getX() + 3; x++) {
            for (int z = o.getZ(); z <= o.getZ() + 6; z++) {
                for (int y = g - 1; y <= g + 3; y++) r.tv.mgr().markNavigationDirtyAt(new BlockPos(x, y, z));
            }
        }
        r.tv.mgr().markNavigationDirtyAt(door.north().above(2));
        BlockPos outside = door.north();
        r.floor(outside, NEVER);
        r.rebuild();
        Set<BlockPos> roomTiles = new HashSet<>();
        for (int x = o.getX() - 2; x <= o.getX() + 2; x++) {
            for (int z = o.getZ() + 1; z <= o.getZ() + 5; z++) roomTiles.add(new BlockPos(x, g, z));
        }
        BlockPos insidePos = door.south(2);
        after20(r, () -> {
            RegistrationFailure open = BuildingAccess.room(r.graph(), r.tv.door(), door, Direction.SOUTH, roomTiles);
            r.c.check("no rule: the room is reachable", open == null, open);
            r.c.check("no rule: a tile inside is accessible", BuildingAccess.isAccessibleNode(r.graph(), r.tv.door(),
                r.graph().nodeAt(insidePos.getX(), insidePos.getY(), insidePos.getZ())), insidePos);
            Subscription never = r.rule(NEVER, PathRule.never());
            r.refresh(outside);
            RegistrationFailure shut = BuildingAccess.room(r.graph(), r.tv.door(), door, Direction.SOUTH, roomTiles);
            r.c.checkHooksOn("never in front of the door: the room is unreachable", shut == RegistrationFailure.ROOM_UNREACHABLE, shut);
            r.c.checkHooksOn("never in front of the door: a tile inside is not accessible", !BuildingAccess.isAccessibleNode(r.graph(), r.tv.door(),
                r.graph().nodeAt(insidePos.getX(), insidePos.getY(), insidePos.getZ())), insidePos);
            never.remove();
            r.refresh(outside);
            RegistrationFailure again = BuildingAccess.room(r.graph(), r.tv.door(), door, Direction.SOUTH, roomTiles);
            r.c.check("after remove(): the room is reachable again", again == null, again);
            r.c.check("after remove(): a tile inside is accessible again", BuildingAccess.isAccessibleNode(r.graph(), r.tv.door(),
                r.graph().nodeAt(insidePos.getX(), insidePos.getY(), insidePos.getZ())), insidePos);
        });
    }

    /** A villager standing on a never tile walks off it to a normal goal and arrives. */
    @GameTest(template = "empty", timeoutTicks = 800, batch = "townkit_paths_start")
    public static void neverStartNotStranded(GameTestHelper helper) {
        Run r = start(helper, "neverStartNotStranded", 8800, 8800).orElse(null);
        if (r == null) return;
        BlockPos p = r.o.offset(-4, 0, -4);
        BlockPos goal = p.east(8);
        r.floor(p, NEVER);
        r.rebuild();
        NpcEntity npc = r.tv.farmer();
        int[] stuckLoops = {0};
        long[] asked = {-1};
        ticks(r, 600, t -> {
            if (t == 1) {
                r.rule(NEVER, PathRule.never());
                r.refresh(p);
                r.hooks.add(PathSpikeTests.hook(PathSpikeTests.STUCK_LOOP, false, call -> {
                    if (call.self() == r.model) stuckLoops[0]++;
                }));
                NavNode start = r.graph().nodeAt(p.getX(), p.getY(), p.getZ());
                r.c.check("the never tile is a route tile", start != null, PathSpikeTests.fmt(start));
                npc.teleportTo(p.getX() + 0.5, start != null ? start.standY : p.getY(), p.getZ() + 0.5);
                return start == null;
            }
            if (t == 20) {
                r.c.check("standing on the never tile", npc.blockPosition().equals(p), npc.blockPosition().toShortString());
                r.c.check("requestMoveTo a normal goal is accepted", r.model.requestMoveTo(r.graph(), goal, 0.6F), goal.toShortString());
                asked[0] = t;
                return false;
            }
            if (asked[0] < 0) return false;
            boolean arrived = r.model.hasRecentMoveSuccess(goal) || PathSpikeTests.dist(npc, goal) <= 1.5;
            if (!arrived && !r.model.hasRecentMoveFailure(goal) && t - asked[0] < 400) return false;
            r.c.check("walks off the never tile and arrives within 400 ticks", arrived, "after " + (t - asked[0]) + " ticks at "
                + npc.blockPosition().toShortString());
            r.c.check("no stuck loop noted", stuckLoops[0] == 0, stuckLoops[0]);
            return true;
        });
    }

    /** The route search itself still reaches a goal tile that is never (the goal is exempt in the search). */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_paths_goal")
    public static void searchAllowsNeverGoal(GameTestHelper helper) {
        Run r = start(helper, "searchAllowsNeverGoal", 9200, 8800).orElse(null);
        if (r == null) return;
        BlockPos s = r.o.west(5);
        BlockPos n = r.o.east(3);
        r.floor(n, NEVER);
        r.rebuild();
        after20(r, () -> {
            r.rule(NEVER, PathRule.never());
            AStarPathfinder.Route route = r.route(s, n, false, null);
            NavNode last = route == null ? null : route.tiles().get(route.tiles().size() - 1);
            r.c.check("findRoute to a never goal tile succeeds and ends on it", PathSpikeTests.at(last, n), PathSpikeTests.fmt(last));
        });
    }

    /**
     * RequestMoveTo onto a never tile ends (arrives or fails) within a bounded time, with no repeated
     * cuts, no repeated STUCK and no stuck loop (hooks filtered to this test's villager).
     */
    @GameTest(template = "empty", timeoutTicks = 1000, batch = "townkit_paths_goalwalk")
    public static void neverGoalEndsCleanly(GameTestHelper helper) {
        Run r = start(helper, "neverGoalEndsCleanly", 9600, 8800).orElse(null);
        if (r == null) return;
        BlockPos p = r.o.offset(-4, 0, -4);
        BlockPos n = p.east(8);
        r.floor(n, NEVER);
        r.rebuild();
        NpcEntity npc = r.tv.farmer();
        int[] cuts = {0};
        int[] stucks = {0};
        int[] stuckLoops = {0};
        long[] asked = {-1};
        int[] idle = {0};
        boolean[] accepted = {false};
        ticks(r, 800, t -> {
            if (t == 1) {
                r.rule(NEVER, PathRule.never());
                r.refresh(n);
                r.hooks.add(PathSpikeTests.hook(PathSpikeTests.END_OF_ROUTE, false, call -> {
                    if (call.args()[0] != npc) return;
                    FollowWalk w = (FollowWalk) call.self();
                    byte kind = PathSpikeTests.CUT_KIND.read(w);
                    if (kind != 0) {
                        cuts[0]++;
                        TownKit.LOG.info("[TownKit test] neverGoalEndsCleanly: cut kind {} {} -> {}", kind,
                            PathSpikeTests.fmt(PathSpikeTests.CUT_FROM.read(w)), PathSpikeTests.fmt(PathSpikeTests.CUT_TO.read(w)));
                    }
                }));
                r.hooks.add(PathSpikeTests.hook(PathSpikeTests.WALK_TICK, true, call -> {
                    if (call.args()[0] == npc && call.returnValue() == MoveToCommand.Status.STUCK) stucks[0]++;
                }));
                r.hooks.add(PathSpikeTests.hook(PathSpikeTests.STUCK_LOOP, false, call -> {
                    if (call.self() == r.model) stuckLoops[0]++;
                }));
                NavNode start = r.graph().nodeAt(p.getX(), p.getY(), p.getZ());
                npc.teleportTo(p.getX() + 0.5, start != null ? start.standY : p.getY(), p.getZ() + 0.5);
                return false;
            }
            if (t == 20) {
                boolean ok = r.model.requestMoveTo(r.graph(), n, 0.6F);
                TownKit.LOG.info("[TownKit test] neverGoalEndsCleanly: requestMoveTo onto the never tile answered {}", ok);
                accepted[0] = ok;
                asked[0] = t;
                return false;
            }
            if (asked[0] < 0) return false;
            // ended: no walk and no pending search for 40 ticks in a row (a re-request would start one again)
            idle[0] = r.model.isMoving() ? 0 : idle[0] + 1;
            if (idle[0] < 40 && t - asked[0] < 600) return false;
            String outcome = r.model.hasRecentMoveSuccess(n) ? "arrived" : r.model.hasRecentMoveFailure(n) ? "failed" : "neither";
            TownKit.LOG.info("[TownKit test] neverGoalEndsCleanly: {} after {} ticks at {} ({} cuts, {} STUCK, {} stuck loops)", outcome,
                t - asked[0], npc.blockPosition().toShortString(), cuts[0], stucks[0], stuckLoops[0]);
            r.c.check("the move ends within 600 ticks", idle[0] >= 40, outcome + " after " + (t - asked[0]) + " ticks");
            // documented: the search exempts the goal tile, so a direct move onto a never tile is accepted and arrives
            r.c.check("requestMoveTo onto the never tile is accepted", accepted[0], accepted[0]);
            r.c.check("the move arrives (succeeds)", r.model.hasRecentMoveSuccess(n), outcome + " at " + npc.blockPosition().toShortString());
            r.c.check("at most one cut", cuts[0] <= 1, cuts[0]);
            r.c.check("at most one STUCK answer", stucks[0] <= 1, stucks[0]);
            r.c.check("no stuck loop noted", stuckLoops[0] == 0, stuckLoops[0]);
            return true;
        });
    }

    /** A raider search ignores a villager-only cost, follows one with includeRaiders, and never blocks raiders either way. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_paths_raider")
    public static void raiderToggleCostsOnly(GameTestHelper helper) {
        Run r = start(helper, "raiderToggleCostsOnly", 10000, 8800).orElse(null);
        if (r == null) return;
        TwoRoutes t = twoRoutes(r);
        r.floor(t.westCorner(), MARK);
        r.rebuild();
        after20(r, () -> {
            RouteRules raider = RouteRules.raider(r.tv.mgr(), r.graph(), false);
            AStarPathfinder.Route plain = r.route(t.s(), t.g(), false, raider);
            r.c.check("no rule: the raider takes the plain route", west(plain, t), side(plain, t));

            Subscription s = r.rule(ROAD, PathRule.cost(0.5));
            AStarPathfinder.Route villager = r.route(t.s(), t.g(), false, null);
            AStarPathfinder.Route ignores = r.route(t.s(), t.g(), false, raider);
            r.c.checkHooksOn("villager-only cost: villagers take the road", east(villager, t), side(villager, t));
            r.c.check("villager-only cost: the raider ignores it", west(ignores, t), side(ignores, t));
            s.remove();

            s = r.rule(ROAD, PathRule.cost(0.5).includeRaiders());
            AStarPathfinder.Route follows = r.route(t.s(), t.g(), false, raider);
            r.c.checkHooksOn("cost with includeRaiders: the raider takes the road", east(follows, t), side(follows, t));
            s.remove();

            s = r.rule(MARK, PathRule.never());
            AStarPathfinder.Route blocked = r.route(t.s(), t.g(), false, raider);
            r.c.checkHooksOn("never (villager rule): blocks the raider too", east(blocked, t), side(blocked, t));
            s.remove();

            r.rule(MARK, PathRule.never().includeRaiders());
            AStarPathfinder.Route blockedToo = r.route(t.s(), t.g(), false, raider);
            r.c.checkHooksOn("never with includeRaiders: blocks the raider", east(blockedToo, t), side(blockedToo, t));
        });
    }

    /** With nothing registered nothing is active, and a rule added then removed leaves the route as it was. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_paths_inert")
    public static void nothingRegisteredIsInert(GameTestHelper helper) {
        Run r = start(helper, "nothingRegisteredIsInert", 10400, 8800).orElse(null);
        if (r == null) return;
        TwoRoutes t = twoRoutes(r);
        r.rebuild();
        after20(r, () -> {
            r.c.check("nothing registered: active() is false", !TownKitPaths.active(), TownKitPaths.active());
            r.c.check("nothing registered: NavBridge.ACTIVE is false", !bridgeActive(), bridgeActive());
            AStarPathfinder.Route before = r.route(t.s(), t.g(), false, null);
            Subscription s = r.rule(ROAD, PathRule.cost(0.5));
            r.c.check("with a rule: active() is true", TownKitPaths.active(), TownKitPaths.active());
            AStarPathfinder.Route during = r.route(t.s(), t.g(), false, null);
            r.c.checkHooksOn("with a rule: the route changes (the rule is live)", east(during, t) && west(before, t), side(before, t) + " / " + side(during, t));
            s.remove();
            r.c.check("after remove(): active() and NavBridge.ACTIVE are false", !TownKitPaths.active() && !bridgeActive(), TownKitPaths.active());
            AStarPathfinder.Route after = r.route(t.s(), t.g(), false, null);
            r.c.check("after remove(): the same tiles as before any rule", before != null && feet(before).equals(feet(after)),
                side(before, t) + " / " + side(after, t));
        });
    }

    /** Towntopia's smoothing would cut straight across a never tile; with the rule no smoothed leg crosses it. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_paths_smooth")
    public static void smoothedRouteAvoidsNever(GameTestHelper helper) {
        Run r = start(helper, "smoothedRouteAvoidsNever", 10800, 8800).orElse(null);
        if (r == null) return;
        BlockPos s = r.o;
        BlockPos g = r.o.offset(6, 0, -2);
        BlockPos n = r.o.offset(3, 0, -1);
        r.floor(n, NEVER);
        r.rebuild();
        after20(r, () -> {
            r.c.check("layout: the straight leg s -> g crosses the tile", crosses(s, g, n), n.toShortString());
            AStarPathfinder.Route before = r.route(s, g, true, null);
            r.c.check("no rule: smoothing cuts straight from s to g", before != null && before.waypoints().equals(List.of(s, g)),
                before == null ? "null" : before.waypoints());
            r.rule(NEVER, PathRule.never());
            AStarPathfinder.Route after = r.route(s, g, true, null);
            r.c.check("never: a smoothed route is found, ending at g", after != null && after.waypoints().get(after.waypoints().size() - 1).equals(g),
                after == null ? "null" : after.waypoints());
            if (after == null) return;
            r.c.checkHooksOn("never: the traced tiles go round the tile", !passes(after, n), feet(after));
            boolean crossed = false;
            List<BlockPos> w = after.waypoints();
            for (int i = 0; i + 1 < w.size(); i++) crossed |= crosses(w.get(i), w.get(i + 1), n);
            r.c.checkHooksOn("never: no smoothed leg crosses the tile", !crossed && !w.contains(n), w);
            NavGraph graph = r.graph();
            NavNode ns = graph.nodeAt(s.getX(), s.getY(), s.getZ());
            NavNode ng = graph.nodeAt(g.getX(), g.getY(), g.getZ());
            boolean own;
            try {
                own = LINE_OF_SIGHT != null && (boolean) LINE_OF_SIGHT.invoke(graph, ns, ng, Set.of());
            } catch (Throwable e) {
                throw new IllegalStateException("lineOfSight failed", e);
            }
            r.c.check("Towntopia's own line of sight still allows s -> g (only the rule refuses it)", own, own);
        });
    }

    /**
     * A 16-tile straight road of {@link #FAST}. The villager walks it four times, back and forth: twice with
     * no rule, then twice with speed(1.5) on the road block. The average walk with the rule takes at most 0.8 times
     * the average without (1.5x the pace would be about 0.67). Each walk is timed from requestMoveTo to arrival.
     */
    @GameTest(template = "empty", timeoutTicks = 1600, batch = "townkit_paths_speed")
    public static void speedRuleWalksFaster(GameTestHelper helper) {
        Run r = start(helper, "speedRuleWalksFaster", 11200, 8800).orElse(null);
        if (r == null) return;
        BlockPos a = r.o.offset(-8, 0, -4);
        BlockPos b = a.east(16);
        for (int dx = 0; dx <= 16; dx++) r.floor(a.east(dx), FAST);
        r.rebuild();
        NpcEntity npc = r.tv.farmer();
        int[] walk = {0};
        long[] asked = {-1};
        long[] next = {20};
        long[] took = new long[4];
        ticks(r, 1400, t -> {
            if (t == 1) {
                NavNode start = r.graph().nodeAt(a.getX(), a.getY(), a.getZ());
                NavNode end = r.graph().nodeAt(b.getX(), b.getY(), b.getZ());
                r.c.check("both road ends are route tiles", start != null && end != null, PathSpikeTests.fmt(start) + " / " + PathSpikeTests.fmt(end));
                npc.teleportTo(a.getX() + 0.5, start != null ? start.standY : a.getY(), a.getZ() + 0.5);
                return start == null || end == null;
            }
            BlockPos goal = walk[0] % 2 == 0 ? b : a;
            if (asked[0] < 0) {
                if (t < next[0]) return false;
                if (walk[0] == 2) {
                    r.c.check("no rule yet: PACE_ACTIVE is false", !paceActive(), paceActive());
                    r.rule(FAST, PathRule.speed(1.5));
                    r.c.check("speed(1.5): PACE_ACTIVE is true", paceActive(), paceActive());
                }
                boolean ok = r.model.requestMoveTo(r.graph(), goal, 0.5F);
                r.c.check("walk " + (walk[0] + 1) + ": requestMoveTo is accepted", ok, goal.toShortString());
                if (!ok) return true;
                asked[0] = t;
                return false;
            }
            if (r.model.hasRecentMoveFailure(goal)) {
                r.c.check("walk " + (walk[0] + 1) + " arrives", false, "failed at " + npc.blockPosition().toShortString());
                return true;
            }
            if (!r.model.hasRecentMoveSuccess(goal)) return false;
            took[walk[0]] = t - asked[0];
            TownKit.LOG.info("[TownKit test] speedRuleWalksFaster: walk {} ({}) took {} ticks", walk[0] + 1,
                walk[0] < 2 ? "no rule" : "speed 1.5", took[walk[0]]);
            walk[0]++;
            asked[0] = -1;
            next[0] = t + 10;
            if (walk[0] < 4) return false;
            double plain = (took[0] + took[1]) / 2.0;
            double fast = (took[2] + took[3]) / 2.0;
            TownKit.LOG.info("[TownKit test] speedRuleWalksFaster: average {} ticks without the rule, {} with speed(1.5) (ratio {})",
                plain, fast, fast / plain);
            r.c.checkHooksOn("speed(1.5): the average walk takes at most 0.8x as long", fast <= 0.8 * plain,
                "plain " + took[0] + "/" + took[1] + ", fast " + took[2] + "/" + took[3] + " ticks");
            return true;
        });
    }

    // ---- transport ----

    /** The test-only kind: see {@link HopKind}. Registered by the tests themselves, so it never exists in a normal game. */
    static final ResourceLocation HOP = ResourceLocation.fromNamespaceAndPath("townkit_test", "hop");
    static final String TICK_NAV_SLICES = "village.VillageManager#tickNavSlices(ServerLevel,int,long)";
    /** Towntopia's private "rebuild the whole route map next" flag. */
    static final VarHandle FULL_BUILD_REQUESTED;

    static {
        VarHandle h;
        try {
            h = MethodHandles.privateLookupIn(VillageManager.class, MethodHandles.lookup()).findVarHandle(VillageManager.class,
                "fullBuildRequested", boolean.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            TownKit.LOG.error("[TownKit test] can't reach VillageManager.fullBuildRequested", e);
            h = null;
        }
        FULL_BUILD_REQUESTED = h;
    }

    /**
     * {@code townkit_test:hop}: two stops whose tiles are 3 apart along x or z, on one level, link both ways at cost 3.
     * Its rides come from {@code rides}; by default a ride puts the villager on the far tile on its first tick (DONE).
     */
    static final class HopKind implements TransportKind {
        final Function<RideContext, Ride> rides;
        /** What available() answers for every link (a test may close the kind mid-ride). */
        volatile boolean open = true;

        HopKind() {
            this(ctx -> c -> HOP_STEP.tick(c, 1));
        }

        HopKind(Function<RideContext, Ride> rides) {
            this.rides = rides;
        }

        @Override
        public ResourceLocation id() {
            return HOP;
        }

        @Override
        public List<TransportLink> links(ServerLevel level, List<TransportStop> stops) {
            List<TransportLink> out = new ArrayList<>();
            for (int i = 0; i < stops.size(); i++) {
                for (int j = i + 1; j < stops.size(); j++) {
                    BlockPos a = stops.get(i).tile();
                    BlockPos b = stops.get(j).tile();
                    int dx = Math.abs(a.getX() - b.getX());
                    int dz = Math.abs(a.getZ() - b.getZ());
                    if (a.getY() == b.getY() && (dx == 3 && dz == 0 || dz == 3 && dx == 0)) out.add(new TransportLink(HOP, a, b, 3, true));
                }
            }
            return out;
        }

        @Override
        public boolean available(ServerLevel level, TransportLink link) {
            return this.open;
        }

        @Override
        public Ride ride(RideContext ctx) {
            return this.rides.apply(ctx);
        }
    }

    /** Registers the hop kind and (when {@code enable}) enables it with the test marker; both removed when the test ends. */
    static void transport(Run r, boolean enable) {
        transport(r, enable, new HopKind());
    }

    static void transport(Run r, boolean enable, HopKind kind) {
        r.rules.add(TownKitTransport.registerKind("townkit-test", kind));
        if (enable) r.rules.add(TownKitTransport.enable("townkit-test", HOP, TestBlocks.TEST_STOP_MARKER.get()));
    }

    /** A floor item frame (facing up) with a test marker in it, at the tile whose feet are at {@code feet}. */
    static ItemFrame frame(Run r, BlockPos feet) {
        return frame(r, feet, new ItemStack(TestBlocks.TEST_STOP_MARKER.get()));
    }

    static ItemFrame frame(Run r, BlockPos feet, ItemStack stack) {
        ItemFrame f = new ItemFrame(r.level, feet, Direction.UP);
        f.setItem(stack);
        r.level.addFreshEntity(f);
        r.frames.add(f);
        return f;
    }

    static List<TransportLink> links(Run r) {
        return TownKitTransport.links(VillageHandle.of(r.tv.mgr()));
    }

    static List<TransportStop> stops(Run r) {
        return StopStore.of(r.level).stops(r.tv.mgr().getVillageId());
    }

    /** Whether the route steps straight between {@code a} and {@code b} (either way). */
    static boolean hops(@Nullable AStarPathfinder.Route route, BlockPos a, BlockPos b) {
        if (route == null) return false;
        List<NavNode> t = route.tiles();
        for (int i = 0; i + 1 < t.size(); i++) {
            if (PathSpikeTests.at(t.get(i), a) && PathSpikeTests.at(t.get(i + 1), b)
                || PathSpikeTests.at(t.get(i), b) && PathSpikeTests.at(t.get(i + 1), a)) return true;
        }
        return false;
    }

    /** Whether {@code g} has the edge a - b both ways. */
    static boolean edge(NavGraph g, BlockPos a, BlockPos b) {
        NavNode na = g.nodeAt(a.getX(), a.getY(), a.getZ());
        NavNode nb = g.nodeAt(b.getX(), b.getY(), b.getZ());
        return na != null && nb != null && na.hasNeighbour(nb) && nb.hasNeighbour(na);
    }

    static boolean glows(ItemFrame f) {
        return Boolean.TRUE.equals(f.getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
    }

    /** Stops a and b = a.east(3) with a 2-wide, 3-high glass wall between: walking round it takes 5 steps, the hop 1. */
    record Hop(BlockPos a, BlockPos b, BlockPos s, BlockPos g) {
    }

    static Hop hopLayout(Run r) {
        BlockPos a = r.o.west(2);
        BlockPos b = a.east(3);
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 0; dy <= 2; dy++) r.place(a.east(dx).above(dy), Blocks.GLASS.defaultBlockState());
        }
        return new Hop(a, b, a.west(5), b.east(6));
    }

    /** A 5x5 room south of {@code o}, door at o facing north (its inside is south); returns the door. Marks the area dirty. */
    static BlockPos room(Run r) {
        BlockPos o = r.o;
        int g = o.getY();
        BlockPos door = ScenarioBuilder.buildRoom(r.level, o.getX() - 2, g, o.getZ() + 1, ScenarioBuilder.RoomFurniture.NONE).doorPos();
        for (int x = o.getX() - 3; x <= o.getX() + 3; x++) {
            for (int z = o.getZ(); z <= o.getZ() + 6; z++) {
                for (int y = g - 1; y <= g + 3; y++) r.tv.mgr().markNavigationDirtyAt(new BlockPos(x, y, z));
            }
        }
        r.tv.mgr().markNavigationDirtyAt(door.north().above(2));
        return door;
    }

    static Set<BlockPos> roomTiles(Run r) {
        Set<BlockPos> tiles = new HashSet<>();
        for (int x = r.o.getX() - 2; x <= r.o.getX() + 2; x++) {
            for (int z = r.o.getZ() + 1; z <= r.o.getZ() + 5; z++) tiles.add(new BlockPos(x, r.o.getY(), z));
        }
        return tiles;
    }

    static String linkText(Run r) {
        return stops(r).size() + " stops, links " + links(r);
    }

    /** With the hop kind enabled and two marker frames, Towntopia's own route search takes the link (raiders don't). */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_paths_link")
    public static void linkJoinsRouteMap(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "linkJoinsRouteMap", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "linkJoinsRouteMap", 7600, 9200).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        AStarPathfinder.Route[] before = {null};
        ItemFrame[] f = new ItemFrame[2];
        ticks(r, 200, t -> {
            if (t == 1) {
                before[0] = r.route(h.s(), h.g(), false, null);
                r.c.check("no link: the route walks round the wall", before[0] != null && !hops(before[0], h.a(), h.b()), feet(before[0]));
                transport(r, true);
                f[0] = frame(r, h.a());
                f[1] = frame(r, h.b());
                return false;
            }
            if (links(r).isEmpty()) return false;
            TransportLink l = links(r).get(0);
            r.c.check("two stops, one link between their tiles", stops(r).size() == 2 && links(r).size() == 1
                && Set.of(l.from(), l.to()).equals(Set.of(h.a(), h.b())), linkText(r));
            r.c.check("the markers are bound to the village and the frames glow", VillageTokenBinding.matches(f[0].getItem(), r.tv.mgr())
                && VillageTokenBinding.matches(f[1].getItem(), r.tv.mgr()) && glows(f[0]) && glows(f[1]), f[0].getItem());
            r.c.check("active() while a link is installed", TownKitPaths.active(), "");
            r.c.check("the link edge is in the route map", edge(r.graph(), h.a(), h.b()), "");
            AStarPathfinder.Route with = r.route(h.s(), h.g(), false, null);
            r.c.check("findRoute takes the link", hops(with, h.a(), h.b()), feet(with));
            r.c.check("the route over the link is shorter", steps(with) < steps(before[0]), steps(with) + " vs " + steps(before[0]));
            AStarPathfinder.Route raider = r.route(h.s(), h.g(), false, RouteRules.raider(r.tv.mgr(), r.graph(), false));
            r.c.check("raiders never take the link", raider != null && !hops(raider, h.a(), h.b()), feet(raider));
            return true;
        });
    }

    /**
     * After a full build (fullBuildRequested) the link is still used. Checked right at the
     * swap, in an after-hook on tickNavSlices (where the new graph is swapped in), before any tickNavRebuild after-hook
     * could re-add the edge: the flood fill's POLL seeding must have added it.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "townkit_paths_link_rebuild")
    public static void linkSurvivesRebuild(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "linkSurvivesRebuild", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "linkSurvivesRebuild", 8000, 9200).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        NavGraph[] old = {null};
        boolean[] swapped = {false};
        boolean[] edgeAtSwap = {false};
        boolean[] routeAtSwap = {false};
        ticks(r, 1000, t -> {
            if (t == 1) {
                r.c.check("fullBuildRequested is reachable", FULL_BUILD_REQUESTED != null, "");
                if (FULL_BUILD_REQUESTED == null) return true;
                transport(r, true);
                frame(r, h.a());
                frame(r, h.b());
                return false;
            }
            if (old[0] == null) {
                if (links(r).isEmpty()) return false;
                r.c.check("before the rebuild: findRoute takes the link", hops(r.route(h.s(), h.g(), false, null), h.a(), h.b()), "");
                old[0] = r.graph();
                r.hooks.add(PathSpikeTests.hook(TICK_NAV_SLICES, true, call -> {
                    if (call.self() != r.tv.mgr() || swapped[0]) return;
                    NavGraph g = r.tv.mgr().getGraph();
                    if (g == old[0]) return;
                    swapped[0] = true;
                    edgeAtSwap[0] = edge(g, h.a(), h.b());
                    routeAtSwap[0] = hops(AStarPathfinder.findRoute(g, h.s(), h.g(), false, Set.of(), Set.of(), RouteRules.VILLAGER), h.a(), h.b());
                }));
                FULL_BUILD_REQUESTED.set(r.tv.mgr(), true);
                return false;
            }
            if (!swapped[0] && r.graph() == old[0]) return false;
            r.c.check("a full build swapped in a new graph", r.graph() != old[0], "");
            r.c.check("the swap happened in tickNavSlices", swapped[0], "");
            r.c.check("at the swap (before any tickNavRebuild after-hook) the new graph has the link edge", edgeAtSwap[0], "");
            r.c.check("at the swap findRoute takes the link", routeAtSwap[0], "");
            AStarPathfinder.Route after = r.route(h.s(), h.g(), false, null);
            r.c.check("afterwards findRoute still takes the link", hops(after, h.a(), h.b()), feet(after));
            return true;
        });
    }

    /** Collects the log messages that contain {@code part}, while attached to the root logger. */
    static final class LogLines extends AbstractAppender {
        final String part;
        final List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());

        private LogLines(String part) {
            super("townkit-test-loglines", null, null, true, Property.EMPTY_ARRAY);
            this.part = part;
        }

        static LogLines attach(String part) {
            LogLines a = new LogLines(part);
            a.start();
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().addAppender(a, org.apache.logging.log4j.Level.ALL, null);
            ctx.updateLoggers();
            return a;
        }

        void detach() {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().removeAppender(getName());
            ctx.updateLoggers();
            stop();
        }

        @Override
        public void append(LogEvent event) {
            String m = event.getMessage().getFormattedMessage();
            if (m.contains(part)) lines.add(m);
        }
    }

    /**
     * A marker bound to the village but outside its route map's bounds is refused, with one log line;
     * one inside is a stop. The outside frame is placed once its (force-loaded) chunk has its entities loaded, and the
     * test checks the frame is really there, so the refusal is what keeps it from being a stop.
     */
    @GameTest(template = "empty", timeoutTicks = 500, batch = "townkit_paths_link_bounds")
    public static void stopRefusedOutsideBounds(GameTestHelper helper) {
        Run r = start(helper, "stopRefusedOutsideBounds", 8400, 9200).orElse(null);
        if (r == null) return;
        BlockPos[] out = {null};
        int[] placedAt = {-1};
        LogLines log = LogLines.attach("refused the " + HOP);
        r.cleanups.add(log::detach);
        ItemFrame[] high = {null};
        ticks(r, 300, t -> {
            if (t == 1) {
                transport(r, true);
                int x = r.graph().getBoundsMaxX() + 3;
                int z = r.o.getZ();
                boolean wasForced = r.level.getForcedChunks().contains(ChunkPos.asLong(x >> 4, z >> 4));
                r.level.setChunkForced(x >> 4, z >> 4, true);
                if (!wasForced) r.cleanups.add(() -> r.level.setChunkForced(x >> 4, z >> 4, false));
                int feetY = r.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                out[0] = new BlockPos(x, feetY, z);
                r.level.setBlock(out[0].below(), Blocks.STONE.defaultBlockState(), 3);
                r.c.check("layout: the outside frame is outside the route map's bounds", !r.graph().isInsideBounds(out[0]), out[0]);
                return false;
            }
            if (out[0] == null) return false;
            if (placedAt[0] < 0) {
                if (!r.level.areEntitiesLoaded(ChunkPos.asLong(out[0])) && t < 200) {
                    LoadWait.notYet();
                    return false;
                }
                r.c.check("the outside chunk has its entities loaded", r.level.areEntitiesLoaded(ChunkPos.asLong(out[0])), t);
                ItemStack bound = new ItemStack(TestBlocks.TEST_STOP_MARKER.get());
                r.c.check("the marker can be bound to the village", VillageTokenBinding.bind(bound, r.tv.mgr()), "");
                frame(r, out[0], bound);
                frame(r, r.o);
                // an unbound marker hung on a wall 3 above the ground: no routable tile under it (frame block or 1-2 below)
                BlockPos wall = r.o.east(4).above(3);
                r.level.setBlock(wall.north(), Blocks.STONE.defaultBlockState(), 3);
                high[0] = new ItemFrame(r.level, wall, Direction.SOUTH);
                high[0].setItem(new ItemStack(TestBlocks.TEST_STOP_MARKER.get()));
                r.level.addFreshEntity(high[0]);
                r.frames.add(high[0]);
                placedAt[0] = t;
                return false;
            }
            if (t - placedAt[0] < 20) return false;
            List<TransportStop> s = stops(r);
            r.c.check("the outside frame is in the level", !r.level.getEntitiesOfClass(ItemFrame.class, new net.minecraft.world.phys.AABB(out[0])).isEmpty(), out[0]);
            r.c.checkHooksOn("the frame inside the bounds is a stop", s.stream().anyMatch(x -> x.framePos().equals(r.o)), s);
            r.c.check("the frame outside the bounds is not a stop", s.stream().noneMatch(x -> x.framePos().equals(out[0])), s);
            List<String> lines = List.copyOf(log.lines);
            r.c.checkHooksOn("its refusal was logged once, as outside the route map", lines.stream().filter(l -> l.contains(out[0].toShortString())
                && l.contains("outside the village's route map")).count() == 1, lines);
            BlockPos wall = high[0].blockPosition();
            r.c.checkHooksOn("the wall frame with no routable tile is not a stop, logged once", s.stream().noneMatch(x -> x.framePos().equals(wall))
                && lines.stream().filter(l -> l.contains(wall.toShortString()) && l.contains("no routable tile")).count() == 1, lines);
            ItemStack left = high[0].getItem();
            r.c.check("a refused stop leaves its marker unbound and unrenamed", !left.isEmpty() && VillageTokenBinding.read(left) == null
                && !left.has(DataComponents.ITEM_NAME) && !glows(high[0]), left.getComponents());
            return true;
        });
    }

    /** A kind that is registered but not enabled has no stops and no links; enabling makes stops; disabling removes them. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_paths_link_disabled")
    public static void disabledKindHasNoStops(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "disabledKindHasNoStops", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "disabledKindHasNoStops", 8800, 9200).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        Subscription[] en = {null};
        ItemFrame[] f = new ItemFrame[2];
        int[] phase = {0};
        int[] since = {0};
        ticks(r, 400, t -> {
            if (t == 1) {
                transport(r, false);
                f[0] = frame(r, h.a());
                f[1] = frame(r, h.b());
                return false;
            }
            if (phase[0] == 0) {
                if (t < 20) return false;
                r.c.check("not enabled: no stops", stops(r).isEmpty(), linkText(r));
                r.c.check("not enabled: no links, active() false", links(r).isEmpty() && !TownKitPaths.active(), linkText(r));
                r.c.check("not enabled: the frames don't glow", !glows(f[0]) && !glows(f[1]), "");
                r.c.check("not enabled: findRoute walks round", !hops(r.route(h.s(), h.g(), false, null), h.a(), h.b()), "");
                en[0] = TownKitTransport.enable("townkit-test", HOP, TestBlocks.TEST_STOP_MARKER.get());
                r.rules.add(en[0]);
                // frames placed while the kind was off are found when they next join or are touched: place them again
                f[0].discard();
                f[1].discard();
                f[0] = frame(r, h.a());
                f[1] = frame(r, h.b());
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (links(r).isEmpty()) return false;
                r.c.check("enabled: two stops and a link", stops(r).size() == 2 && links(r).size() == 1, linkText(r));
                en[0].remove();
                r.c.check("disabled again: no stops, no links, active() false at once", stops(r).isEmpty() && links(r).isEmpty()
                    && !TownKitPaths.active(), linkText(r));
                r.c.check("disabled again: the link edge is gone", !edge(r.graph(), h.a(), h.b()), "");
                r.c.check("disabled again: findRoute walks round", !hops(r.route(h.s(), h.g(), false, null), h.a(), h.b()), "");
                phase[0] = 2;
                since[0] = t;
                return false;
            }
            if ((glows(f[0]) || glows(f[1])) && t - since[0] < 60) return false;
            r.c.check("disabled again: the frames stop glowing within 60 ticks", !glows(f[0]) && !glows(f[1]), (t - since[0]) + " ticks");
            return true;
        });
    }

    /** Taking the marker out of a frame removes its stop and the link (route map edge included) within 60 ticks. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_paths_link_removed")
    public static void markerRemovedRemovesLink(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "markerRemovedRemovesLink", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "markerRemovedRemovesLink", 9200, 9200).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        ItemFrame[] f = new ItemFrame[2];
        int[] removedAt = {-1};
        ticks(r, 300, t -> {
            if (t == 1) {
                transport(r, true);
                f[0] = frame(r, h.a());
                f[1] = frame(r, h.b());
                return false;
            }
            if (removedAt[0] < 0) {
                if (links(r).isEmpty()) return false;
                r.c.check("with both markers findRoute takes the link", hops(r.route(h.s(), h.g(), false, null), h.a(), h.b()), "");
                r.c.check("the marker in the frame is bound and glows", VillageTokenBinding.read(f[1].getItem()) != null && glows(f[1]),
                    f[1].getItem().getComponents());
                // knocked out of the frame: it drops as an item entity, as when a player hits the frame
                f[1].hurt(r.level.damageSources().generic(), 1.0F);
                r.c.check("the frame is empty now", f[1].getItem().isEmpty(), f[1].getItem());
                removedAt[0] = t;
                return false;
            }
            if (!links(r).isEmpty() && t - removedAt[0] < 60) return false;
            r.c.check("the link is gone within 60 ticks", links(r).isEmpty(), linkText(r) + " after " + (t - removedAt[0]) + " ticks");
            r.c.check("one stop left", stops(r).size() == 1, linkText(r));
            r.c.check("the link edge is gone from the route map", !edge(r.graph(), h.a(), h.b()), "");
            r.c.check("active() is false again", !TownKitPaths.active(), "");
            AStarPathfinder.Route after = r.route(h.s(), h.g(), false, null);
            r.c.check("findRoute walks round again", after != null && !hops(after, h.a(), h.b()), feet(after));
            List<ItemEntity> dropped = r.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(h.b()).inflate(2),
                e -> e.getItem().is(TestBlocks.TEST_STOP_MARKER.get()));
            ItemStack drop = dropped.isEmpty() ? ItemStack.EMPTY : dropped.get(0).getItem();
            r.c.check("the dropped marker is the plain item again: no glint, no binding, stacks with a new one", !drop.isEmpty()
                && !Boolean.TRUE.equals(drop.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) && VillageTokenBinding.read(drop) == null
                && ItemStack.isSameItemSameComponents(drop, new ItemStack(TestBlocks.TEST_STOP_MARKER.get())), drop.getComponents());
            dropped.forEach(ItemEntity::discard);
            return true;
        });
    }

    /** {@code MarkerFrames.reconcile(ServerLevel)}, {@code StopStore.put/get/remove}: private to TownKit's nav package. */
    static final MethodHandle RECONCILE;
    static final MethodHandle STORE_PUT;
    static final MethodHandle STORE_GET;
    static final MethodHandle STORE_REMOVE;

    static {
        MethodHandle reconcile = null;
        MethodHandle put = null;
        MethodHandle get = null;
        MethodHandle remove = null;
        try {
            MethodHandles.Lookup frames = MethodHandles.privateLookupIn(townkit.core.nav.MarkerFrames.class, MethodHandles.lookup());
            MethodHandles.Lookup store = MethodHandles.privateLookupIn(StopStore.class, MethodHandles.lookup());
            reconcile = frames.findStatic(townkit.core.nav.MarkerFrames.class, "reconcile", MethodType.methodType(void.class, ServerLevel.class));
            put = store.findVirtual(StopStore.class, "put", MethodType.methodType(List.class, java.util.UUID.class, TransportStop.class));
            get = store.findVirtual(StopStore.class, "get", MethodType.methodType(TransportStop.class, java.util.UUID.class));
            remove = store.findVirtual(StopStore.class, "remove", MethodType.methodType(TransportStop.class, java.util.UUID.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            TownKit.LOG.error("[TownKit test] can't reach MarkerFrames.reconcile / StopStore", e);
        }
        RECONCILE = reconcile;
        STORE_PUT = put;
        STORE_GET = get;
        STORE_REMOVE = remove;
    }

    /**
     * Minecraft 1.21 loads a chunk's entities after the chunk itself. A stop whose frame chunk is loaded
     * but whose entities are not yet must be skipped by the reconcile, not dropped (that churned links on every world
     * load or player approach and lost the link's failure mark). A stop is recorded in a far chunk never loaded in this
     * world; in one tick the chunk is loaded (its entities are still on their way) and the reconcile runs.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_paths_link_entities")
    public static void stopKeptWhileEntitiesLoad(GameTestHelper helper) {
        Checks c = new Checks("stopKeptWhileEntitiesLoad");
        ServerLevel level = helper.getLevel();
        helper.runAfterDelay(1, () -> {
            try {
                c.check("MarkerFrames.reconcile and StopStore are reachable", RECONCILE != null && STORE_PUT != null
                    && STORE_GET != null && STORE_REMOVE != null, "");
                if (RECONCILE != null && STORE_PUT != null && STORE_GET != null && STORE_REMOVE != null) {
                    ChunkPos chunk = new ChunkPos(-1900, -1900);
                    BlockPos pos = new BlockPos(chunk.getMiddleBlockX(), 0, chunk.getMiddleBlockZ());
                    java.util.UUID frame = java.util.UUID.randomUUID();
                    StopStore store = StopStore.of(level);
                    c.check("precondition: the far chunk was never loaded", !level.hasChunkAt(pos), "");
                    STORE_PUT.invoke(store, frame, new TransportStop(HOP, java.util.UUID.randomUUID(), pos, pos));
                    // loaded at once (FULL); its entity section is read on its own and only lands on a later tick
                    level.getChunk(chunk.x, chunk.z);
                    c.check("precondition: the chunk is loaded and its entities are not yet", level.hasChunkAt(pos)
                        && !level.areEntitiesLoaded(chunk.toLong()),
                        "chunk " + level.hasChunkAt(pos) + ", entities " + level.areEntitiesLoaded(chunk.toLong()));
                    for (int i = 0; i < 3; i++) RECONCILE.invoke(level);
                    c.check("the stop is kept while its frame's entities load", STORE_GET.invoke(store, frame) != null, "");
                    STORE_REMOVE.invoke(store, frame);
                }
            } catch (Throwable e) {
                c.check("no exception", false, e);
            }
            BehaviourTests.finish(helper, c);
        });
    }

    /**
     * A room whose door opens into a glass pocket (B) is unreachable on foot; a hop link from A, 3 north of B over the
     * pocket wall, makes BuildingAccess report it reachable (B had no node: the POLL seeding maps it and the room).
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_paths_link_reach")
    public static void onlyReachableByLinkIsReachable(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "onlyReachableByLinkIsReachable", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "onlyReachableByLinkIsReachable", 9600, 9200).orElse(null);
        if (r == null) return;
        BlockPos door = room(r);
        BlockPos b = door.north();
        BlockPos a = b.north(3);
        for (BlockPos p : List.of(b.west(), b.east(), b.north())) {
            for (int dy = 0; dy <= 2; dy++) r.place(p.above(dy), Blocks.GLASS.defaultBlockState());
        }
        r.rebuild();
        Set<BlockPos> tiles = roomTiles(r);
        boolean[] rebuilt = {false};
        ticks(r, 400, t -> {
            if (t == 1) {
                RegistrationFailure shut = BuildingAccess.room(r.graph(), r.tv.door(), door, Direction.SOUTH, tiles);
                r.c.check("no link: the room is unreachable", shut == RegistrationFailure.ROOM_UNREACHABLE, shut);
                r.c.check("no link: the pocket tile has no node", r.graph().nodeAt(b.getX(), b.getY(), b.getZ()) == null, b);
                transport(r, true);
                frame(r, a);
                frame(r, b);
                return false;
            }
            if (links(r).isEmpty()) return false;
            if (!rebuilt[0]) {
                // the link's start tile was marked dirty when the link went in: the local update re-fills it
                r.rebuild();
                rebuilt[0] = true;
                return false;
            }
            r.c.check("the pocket tile has a node now", r.graph().nodeAt(b.getX(), b.getY(), b.getZ()) != null, b);
            r.c.check("the link edge is in the route map", edge(r.graph(), a, b), "");
            RegistrationFailure open = BuildingAccess.room(r.graph(), r.tv.door(), door, Direction.SOUTH, tiles);
            r.c.check("with the link: BuildingAccess reports the room reachable", open == null, open);
            BlockPos inside = door.south(2);
            r.c.check("with the link: a tile inside is accessible", BuildingAccess.isAccessibleNode(r.graph(), r.tv.door(),
                r.graph().nodeAt(inside.getX(), inside.getY(), inside.getZ())), inside);
            return true;
        });
    }

    /** RoomScanner walks raw neighbours(); a link from the tile in front of a house's door must not change its scan. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_paths_link_room")
    public static void linkDoesNotChangeRoomScan(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "linkDoesNotChangeRoomScan", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "linkDoesNotChangeRoomScan", 10000, 9200).orElse(null);
        if (r == null) return;
        BlockPos door = room(r);
        BlockPos a = door.north();
        BlockPos b = a.north(3);
        r.rebuild();
        Set<BlockPos>[] before = new Set[1];
        ticks(r, 200, t -> {
            if (t == 1) {
                before[0] = RoomScanner.scan(r.level, r.graph(), door, Direction.SOUTH);
                r.c.check("no link: the room scans", before[0] != null && !before[0].isEmpty(), before[0]);
                transport(r, true);
                frame(r, a);
                frame(r, b);
                return false;
            }
            if (links(r).isEmpty()) return false;
            r.c.check("the link edge (from the tile in front of the door) is in the route map", edge(r.graph(), a, b), linkText(r));
            Set<BlockPos> with = RoomScanner.scan(r.level, r.graph(), door, Direction.SOUTH);
            r.c.check("with the link: the room scans the same tiles", Objects.equals(before[0], with),
                (before[0] == null ? "null" : before[0].size()) + " vs " + (with == null ? "null" : with.size()) + " tiles");
            return true;
        });
    }

    // ---- rides ----

    /** What the test rides saw: one entry per ended ride in {@code completed} and {@code endAt}. */
    static final class RideLog {
        int rides;
        int ticks;
        final List<Boolean> completed = new ArrayList<>();
        final List<Vec3> endAt = new ArrayList<>();

        int ends() {
            return this.completed.size();
        }

        String text() {
            return this.rides + " rides, " + this.ticks + " ticks, ends " + this.completed + " at " + this.endAt;
        }
    }

    /** One tick of a test ride; {@code ride} counts the rides of the test from 1. */
    interface Step {
        RideStatus tick(RideContext c, int ride);
    }

    /** The plain hop: the villager is put on the far tile at once. */
    static final Step HOP_STEP = (c, n) -> {
        c.placeAt(c.link().to());
        return RideStatus.DONE;
    };

    /** The hop kind whose rides run {@code step} each tick, time out after {@code timeout} ticks and log into {@code log}. */
    static HopKind logged(RideLog log, int timeout, Step step) {
        return new HopKind(ctx -> {
            int n = ++log.rides;
            return new Ride() {
                @Override
                public RideStatus tick(RideContext c) {
                    log.ticks++;
                    return step.tick(c, n);
                }

                @Override
                public void end(RideContext c, boolean completed) {
                    log.completed.add(completed);
                    log.endAt.add(c.body().position());
                }

                @Override
                public int timeoutTicks() {
                    return timeout;
                }
            };
        });
    }

    /** Whether {@code p} stands on the tile whose feet are at {@code feet}. */
    static boolean on(@Nullable Vec3 p, BlockPos feet) {
        return p != null && BlockPos.containing(p.x, p.y + 0.1, p.z).equals(feet);
    }

    static boolean arrived(Run r, BlockPos goal) {
        return r.model.hasRecentMoveSuccess(goal) || PathSpikeTests.dist(r.tv.farmer(), goal) <= 1.5;
    }

    static boolean noRideLeft(Run r) {
        return !RideEngine.riding(r.tv.farmer().getNpcId()) && RideEngine.activeRides() == 0;
    }

    /** Whether the A* route s -> g (as a villager) takes the link. */
    static boolean routeHops(Run r, Hop h) {
        return hops(r.route(h.s(), h.g(), false, null), h.a(), h.b());
    }

    static boolean stepFailed(Run r, Hop h) {
        NavNode a = r.graph().nodeAt(h.a().getX(), h.a().getY(), h.a().getZ());
        NavNode b = r.graph().nodeAt(h.b().getX(), h.b().getY(), h.b().getZ());
        return a != null && b != null && a.hasFailedStepTo(b);
    }

    /**
     * The hop layout with {@code kind}'s rides: once the link is in, the farmer stands at s and is asked to walk to g;
     * then {@code step} runs every tick with the ticks since the request, until it answers true.
     */
    static void rideScenario(Run r, Hop h, HopKind kind, int limit, IntPredicate step) {
        int[] placedAt = {-1};
        int[] askedAt = {-1};
        ticks(r, limit, t -> {
            if (t == 1) {
                transport(r, true, kind);
                frame(r, h.a());
                frame(r, h.b());
                return false;
            }
            if (placedAt[0] < 0) {
                if (links(r).isEmpty()) return false;
                NavNode ns = r.graph().nodeAt(h.s().getX(), h.s().getY(), h.s().getZ());
                r.tv.farmer().teleportTo(h.s().getX() + 0.5, ns == null ? h.s().getY() : ns.standY, h.s().getZ() + 0.5);
                placedAt[0] = t;
                return false;
            }
            if (askedAt[0] < 0) {
                if (t - placedAt[0] < 10) return false;
                r.c.check("requestMoveTo the goal past B is accepted", r.model.requestMoveTo(r.graph(), h.g(), 0.6F), h.g());
                askedAt[0] = t;
                return false;
            }
            return step.test(t - askedAt[0]);
        });
    }

    /** The walk to a goal past B is cut at the link, the hop ride carries the villager to B and Towntopia walks on from there. */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "townkit_rides_carry")
    public static void rideCarriesAndResumes(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "rideCarriesAndResumes", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "rideCarriesAndResumes", 7600, 9600).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        NpcEntity npc = r.tv.farmer();
        RideLog log = new RideLog();
        int[] stucks = {0};
        double[] minToA = {Double.MAX_VALUE};
        rideScenario(r, h, logged(log, 400, HOP_STEP), 900, t -> {
            if (t == 1) {
                r.hooks.add(PathSpikeTests.hook(PathSpikeTests.WALK_TICK, true, call -> {
                    if (call.args()[0] == npc && call.returnValue() == MoveToCommand.Status.STUCK) stucks[0]++;
                }));
            }
            if (log.ends() > 0) minToA[0] = Math.min(minToA[0], PathSpikeTests.dist(npc, h.a()));
            boolean arrived = arrived(r, h.g());
            if (!arrived && !r.model.hasRecentMoveFailure(h.g()) && t < 600) return false;
            r.c.check("one ride over the link, completed", log.rides == 1 && log.completed.equals(List.of(true)), log.text());
            r.c.check("the ride left the villager on B", !log.endAt.isEmpty() && on(log.endAt.get(0), h.b()), log.text());
            r.c.check("reaches the goal past B", arrived, "after " + t + " ticks at " + npc.blockPosition().toShortString());
            r.c.check("never back at A after the ride", minToA[0] > 1.5, String.format("%.2f", minToA[0]));
            r.c.check("the walker never answered STUCK", stucks[0] == 0, stucks[0]);
            r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
            return true;
        });
    }

    /**
     * A ride that answers FAILED ends on B (B is safe), the step is marked failed, and no route takes the
     * link afterwards: not after an availability refresh, not after a full build (which drops Towntopia's own failed-step
     * memory). The villager walks on to the goal from B.
     */
    @GameTest(template = "empty", timeoutTicks = 1800, batch = "townkit_rides_failed")
    public static void forcedFailureFallsBack(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "forcedFailureFallsBack", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "forcedFailureFallsBack", 8000, 9600).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        RideLog log = new RideLog();
        int[] endedAt = {-1};
        NavGraph[] old = {null};
        rideScenario(r, h, logged(log, 400, (c, n) -> RideStatus.FAILED), 1500, t -> {
            if (endedAt[0] < 0) {
                if (log.ends() == 0 && t < 400) return false;
                r.c.check("one ride, ended as failed", log.rides == 1 && log.completed.equals(List.of(false)) && log.ticks == 1, log.text());
                r.c.check("the fallback put the villager on B", !log.endAt.isEmpty() && on(log.endAt.get(0), h.b()), log.text());
                r.c.check("the step A -> B is marked failed", stepFailed(r, h), "");
                r.c.check("the next route avoids the link", !routeHops(r, h), feet(r.route(h.s(), h.g(), false, null)));
                r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
                endedAt[0] = t;
                return false;
            }
            if (old[0] == null) {
                if (t - endedAt[0] < 45) return false;
                r.c.check("after an availability refresh the route still avoids the link", !routeHops(r, h), "");
                r.c.check("fullBuildRequested is reachable", FULL_BUILD_REQUESTED != null, "");
                if (FULL_BUILD_REQUESTED == null) return true;
                old[0] = r.graph();
                FULL_BUILD_REQUESTED.set(r.tv.mgr(), true);
                return false;
            }
            if (r.graph() == old[0] && t - endedAt[0] < 600) return false;
            r.c.check("a full build swapped in a new graph", r.graph() != old[0], "");
            r.c.check("after the full build the route still avoids the link", !routeHops(r, h),
                feet(r.route(h.s(), h.g(), false, null)) + (stepFailed(r, h) ? " (Towntopia's failed step kept)" : " (only TownKit's mark)"));
            r.c.check("the villager walked on to the goal", arrived(r, h.g()), r.tv.farmer().blockPosition().toShortString());
            r.c.check("still one ride", log.rides == 1, log.text());
            return true;
        });
    }

    /** A ride that never finishes is ended after its timeoutTicks (40 here): fallback to B, step marked failed. */
    @GameTest(template = "empty", timeoutTicks = 1000, batch = "townkit_rides_timeout")
    public static void timeoutFallsBack(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "timeoutFallsBack", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "timeoutFallsBack", 8400, 9600).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        RideLog log = new RideLog();
        rideScenario(r, h, logged(log, 40, (c, n) -> RideStatus.RUNNING), 700, t -> {
            if (log.ends() == 0 && t < 500) return false;
            r.c.check("one ride, ticked exactly 40 times, ended as failed", log.rides == 1 && log.ticks == 40
                && log.completed.equals(List.of(false)), log.text());
            r.c.check("the fallback put the villager on B", !log.endAt.isEmpty() && on(log.endAt.get(0), h.b()), log.text());
            r.c.check("the step A -> B is marked failed", stepFailed(r, h), "");
            r.c.check("the next route avoids the link", !routeHops(r, h), "");
            r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
            return true;
        });
    }

    /** A failed ride whose far stop is unsafe (no head room at B) puts the villager back on A. */
    @GameTest(template = "empty", timeoutTicks = 1000, batch = "townkit_rides_unsafe")
    public static void unsafeBFallsBackToA(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "unsafeBFallsBackToA", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "unsafeBFallsBackToA", 8800, 9600).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        RideLog log = new RideLog();
        BlockPos head = h.b().above();
        r.cleanups.add(() -> r.level.setBlock(head, Blocks.AIR.defaultBlockState(), 3));
        rideScenario(r, h, logged(log, 400, (c, n) -> {
            c.level().setBlock(head, Blocks.GLASS.defaultBlockState(), 3);
            return RideStatus.FAILED;
        }), 700, t -> {
            if (log.ends() == 0 && t < 500) return false;
            r.c.check("B is unsafe now", !NavGraph.isDryWalkable(r.level, h.b()), r.level.getBlockState(head));
            r.c.check("one ride, ended as failed", log.rides == 1 && log.completed.equals(List.of(false)), log.text());
            r.c.check("the fallback put the villager back on A", !log.endAt.isEmpty() && on(log.endAt.get(0), h.a()), log.text());
            r.c.check("the step A -> B is marked failed", stepFailed(r, h), "");
            r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
            return true;
        });
    }

    /**
     * Another activity takes over mid-ride (the walk is stopped and a new one asked for): the ride ends at once, the
     * villager is on a stop, the link is not marked failed (the ride did nothing wrong), and the new walk arrives.
     */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_rides_interrupt")
    public static void interruptedRideEndsSafely(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "interruptedRideEndsSafely", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "interruptedRideEndsSafely", 9200, 9600).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        NpcEntity npc = r.tv.farmer();
        RideLog log = new RideLog();
        int[] interruptedAt = {-1};
        int[] endedAt = {-1};
        rideScenario(r, h, logged(log, 400, (c, n) -> n == 1 ? RideStatus.RUNNING : HOP_STEP.tick(c, n)), 1100, t -> {
            if (interruptedAt[0] < 0) {
                if ((!RideEngine.riding(npc.getNpcId()) || log.ticks < 5) && t < 500) return false;
                r.c.check("riding before the interruption", RideEngine.riding(npc.getNpcId()), log.text());
                r.model.stopMoving();
                r.c.check("the new walk back to s is accepted", r.model.requestMoveTo(r.graph(), h.s(), 0.6F), h.s());
                interruptedAt[0] = t;
                return false;
            }
            if (endedAt[0] < 0) {
                if (log.ends() == 0 && t - interruptedAt[0] < 20) return false;
                r.c.check("the ride ended within 3 ticks, not completed", log.ends() == 1 && !log.completed.get(0)
                    && t - interruptedAt[0] <= 3, log.text() + " after " + (t - interruptedAt[0]) + " ticks");
                Vec3 at = log.endAt.isEmpty() ? null : log.endAt.get(0);
                r.c.check("the villager is on A or B", on(at, h.a()) || on(at, h.b()), at);
                r.c.check("the link is not marked failed", !stepFailed(r, h) && routeHops(r, h), "");
                r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
                endedAt[0] = t;
                return false;
            }
            boolean back = arrived(r, h.s());
            if (!back && !r.model.hasRecentMoveFailure(h.s()) && t - endedAt[0] < 600) return false;
            r.c.check("the new walk arrives at s", back, npc.blockPosition().toShortString());
            r.c.check("no ride state left at the end", RideEngine.activeRides() == 0, log.text());
            return true;
        });
    }

    /**
     * A walk the test ticks itself, the way Towntopia's TravelerApproach (travelers) and GuardFootwork (chase walks) do:
     * planned with the village's route map, a new plan on REPLAN, given up after 3 STUCKs.
     */
    static final class OwnWalk {
        final NpcEntity npc;
        final BlockPos goal;
        @Nullable
        MoveToCommand move;
        int stucks;
        boolean done;
        String last = "";

        OwnWalk(NpcEntity npc, BlockPos goal) {
            this.npc = npc;
            this.goal = goal;
        }

        /** One tick; true once the walk is over (done, or given up). */
        boolean tick(Run r) {
            if (this.move == null) {
                AStarPathfinder.Route route = AStarPathfinder.findRoute(r.graph(), this.npc.blockPosition(), this.goal, false,
                    Set.of(), Set.of());
                this.move = route == null ? null : FollowWalk.forRoute(r.graph(), this.npc, route, 0.6F, false, this.stucks, Set.of());
                if (this.move == null) {
                    this.last = "no route from " + this.npc.blockPosition().toShortString();
                    return ++this.stucks >= 3;
                }
            }
            MoveToCommand.Status s = this.move.tick(this.npc);
            switch (s) {
                case RUNNING:
                    return false;
                case DONE:
                    this.move = null;
                    this.done = true;
                    return true;
                case REPLAN:
                    this.move.closeAllOpenedDoors(this.npc);
                    this.move = null;
                    return false;
                default:
                    this.last = s + " at " + this.npc.blockPosition().toShortString();
                    this.move.closeAllOpenedDoors(this.npc);
                    this.move = null;
                    return ++this.stucks >= 3;
            }
        }
    }

    /**
     * Walks that aren't a village member's active move ride links too. First a Towntopia NPC no village
     * holds (no model, like a traveler), its walk ticked by the test as TravelerApproach does; then the farmer with a
     * walk the test ticks itself, not its model's active move (like a guard's chase walk). Both ride the hop and walk on
     * to the goal past B; neither walk answers STUCK.
     */
    @GameTest(template = "empty", timeoutTicks = 1600, batch = "townkit_rides_modelless")
    public static void walkersWithoutAModelRide(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "walkersWithoutAModelRide", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "walkersWithoutAModelRide", 7600, 10400).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        RideLog log = new RideLog();
        NpcEntity[] stray = {null};
        OwnWalk[] walk = {null, null};
        int[] phase = {0};
        int[] phaseAt = {0};
        ticks(r, 1300, t -> {
            if (t == 1) {
                transport(r, true, logged(log, 400, HOP_STEP));
                frame(r, h.a());
                frame(r, h.b());
                return false;
            }
            NavNode ns = r.graph().nodeAt(h.s().getX(), h.s().getY(), h.s().getZ());
            double sy = ns == null ? h.s().getY() : ns.standY;
            if (phase[0] == 0) {
                if (links(r).isEmpty()) return false;
                NpcEntity n = com.alex.test.DynamicVillages.NPC.get().create(r.level);
                r.c.check("a stray NPC can be made", n != null, "");
                if (n == null) return true;
                n.moveTo(h.s().getX() + 0.5, sy, h.s().getZ() + 0.5, 0.0F, 0.0F);
                // not a village member (as a traveler isn't), so no village links it on join; Towntopia only looks at
                // waiting settlers when it takes over a vanilla village
                n.addTag("dynamicvillages.waiting_settler");
                r.level.addFreshEntity(n);
                stray[0] = n;
                r.cleanups.add(() -> {
                    if (!n.isRemoved()) n.discard();
                });
                r.c.check("the stray NPC has no village and no model", n.getVillageLink() == null
                    && r.tv.mgr().getModel(n.getNpcId()) == null, n.getVillageLink());
                r.c.check("the route s -> g takes the link", routeHops(r, h), feet(r.route(h.s(), h.g(), false, null)));
                phase[0] = 1;
                phaseAt[0] = t;
                return false;
            }
            if (phase[0] == 1) {
                if (t - phaseAt[0] < 10) return false;
                if (walk[0] == null) walk[0] = new OwnWalk(stray[0], h.g());
                boolean over = walk[0].tick(r);
                if (!over && t - phaseAt[0] < 500) return false;
                r.c.check("no model: one ride over the link, completed", log.rides == 1 && log.completed.equals(List.of(true)), log.text());
                r.c.check("no model: the ride left it on B", !log.endAt.isEmpty() && on(log.endAt.get(0), h.b()), log.text());
                r.c.check("no model: it reaches the goal past B", walk[0].done && PathSpikeTests.dist(stray[0], h.g()) <= 1.5,
                    stray[0].blockPosition().toShortString() + " " + walk[0].last);
                r.c.check("no model: its walk never answered STUCK", walk[0].stucks == 0, walk[0].last);
                r.c.check("no model: still no model", r.tv.mgr().getModel(stray[0].getNpcId()) == null, "");
                r.c.check("no model: no ride state left", RideEngine.activeRides() == 0, RideEngine.activeRides());
                stray[0].discard();
                r.tv.farmer().teleportTo(h.s().getX() + 0.5, sy, h.s().getZ() + 0.5);
                phase[0] = 2;
                phaseAt[0] = t;
                return false;
            }
            if (t - phaseAt[0] < 10) return false;
            if (walk[1] == null) {
                walk[1] = new OwnWalk(r.tv.farmer(), h.g());
                r.c.check("not the model's active move", r.model.activeMove() == null, r.model.activeMove());
            }
            boolean over = walk[1].tick(r);
            if (!over && t - phaseAt[0] < 500) return false;
            r.c.check("own walk: a second ride, completed", log.rides == 2 && log.completed.equals(List.of(true, true)), log.text());
            r.c.check("own walk: the ride left the farmer on B", log.endAt.size() == 2 && on(log.endAt.get(1), h.b()), log.text());
            r.c.check("own walk: reaches the goal past B", walk[1].done && PathSpikeTests.dist(r.tv.farmer(), h.g()) <= 1.5,
                r.tv.farmer().blockPosition().toShortString() + " " + walk[1].last);
            r.c.check("own walk: never answered STUCK", walk[1].stucks == 0, walk[1].last);
            r.c.check("own walk: no ride state left", noRideLeft(r), RideEngine.activeRides());
            return true;
        });
    }

    /**
     * The villager's chunk unloads mid-ride while the ride holds it on top of the wall (neither stop).
     * Unloading is done as Minecraft does it: the entity is saved, then removed (UNLOADED_TO_CHUNK; Towntopia unbinds
     * it), and loaded back from the saved data. The ride ends with no state left, and the reloaded villager stands on A
     * or B, not where it was saved.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "townkit_rides_unload")
    public static void unloadMidRide(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "unloadMidRide", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "unloadMidRide", 9600, 9600).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        NpcEntity npc = r.tv.farmer();
        RideLog log = new RideLog();
        BlockPos wallTop = h.a().east().above(3);
        CompoundTag saved = new CompoundTag();
        int[] unloadedAt = {-1};
        int[] reloadedAt = {-1};
        Entity[] back = {null};
        r.cleanups.add(() -> {
            if (back[0] != null && !back[0].isRemoved()) back[0].discard();
        });
        rideScenario(r, h, logged(log, 400, (c, n) -> {
            c.body().setPos(wallTop.getX() + 0.5, wallTop.getY(), wallTop.getZ() + 0.5);
            c.body().setDeltaMovement(Vec3.ZERO);
            return RideStatus.RUNNING;
        }), 900, t -> {
            if (unloadedAt[0] < 0) {
                if ((!RideEngine.riding(npc.getNpcId()) || log.ticks < 5) && t < 500) return false;
                r.c.check("riding on top of the wall before the unload", RideEngine.riding(npc.getNpcId())
                    && on(npc.position(), wallTop), npc.position());
                r.c.check("the villager is marked as riding in its saved data, with its dimension", npc.getPersistentData()
                    .getCompound(RideEngine.RIDE_TAG).getString("dim").equals(r.level.dimension().location().toString()),
                    npc.getPersistentData());
                r.c.check("the entity saves", npc.save(saved), "");
                npc.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                unloadedAt[0] = t;
                return false;
            }
            if (reloadedAt[0] < 0) {
                if ((RideEngine.riding(npc.getNpcId()) || r.model.puppet() != null) && t - unloadedAt[0] < 40) return false;
                r.c.check("the ride ended when the villager unloaded, not completed", log.completed.equals(List.of(false)), log.text());
                r.c.check("no ride state left after the unload", noRideLeft(r), RideEngine.activeRides());
                back[0] = EntityType.loadEntityRecursive(saved, r.level, e -> e);
                r.c.check("the saved villager loads back", back[0] instanceof NpcEntity, back[0]);
                if (!(back[0] instanceof NpcEntity)) return true;
                r.level.addFreshEntity(back[0]);
                reloadedAt[0] = t;
                return false;
            }
            NpcEntity again = (NpcEntity) back[0];
            if (r.model.puppet() != again && t - reloadedAt[0] < 40) return false;
            r.c.check("Towntopia took the reloaded villager back", r.model.puppet() == again, "");
            r.c.check("the reloaded villager stands on A or B", on(again.position(), h.a()) || on(again.position(), h.b()),
                again.position() + " (saved on the wall at " + wallTop.toShortString() + ")");
            r.c.check("its ride mark is gone", !again.getPersistentData().contains(RideEngine.RIDE_TAG), again.getPersistentData());
            r.c.check("no ride state left after the reload", !RideEngine.riding(again.getNpcId()) && RideEngine.activeRides() == 0, "");
            r.c.check("still one ride", log.rides == 1, log.text());
            return true;
        });
    }

    /**
     * B's marker frame is broken mid-ride (the kind's ride itself would run on): the link goes, the ride
     * fails within a few ticks, the villager is put on a stop and no route takes the link any more.
     */
    @GameTest(template = "empty", timeoutTicks = 1000, batch = "townkit_rides_frame")
    public static void frameBrokenMidRideFails(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "frameBrokenMidRideFails", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "frameBrokenMidRideFails", 10400, 9600).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        NpcEntity npc = r.tv.farmer();
        RideLog log = new RideLog();
        int[] brokenAt = {-1};
        rideScenario(r, h, logged(log, 400, (c, n) -> RideStatus.RUNNING), 700, t -> {
            if (brokenAt[0] < 0) {
                if ((!RideEngine.riding(npc.getNpcId()) || log.ticks < 5) && t < 500) return false;
                r.c.check("riding before the frame breaks", RideEngine.riding(npc.getNpcId()), log.text());
                ItemFrame fb = r.frames.stream().filter(f -> f.blockPosition().equals(h.b())).findFirst().orElse(null);
                r.c.check("B's frame found", fb != null, r.frames);
                if (fb == null) return true;
                fb.discard();
                brokenAt[0] = t;
                return false;
            }
            if (log.ends() == 0 && t - brokenAt[0] < 20) return false;
            r.c.check("the link is gone", links(r).isEmpty(), linkText(r));
            r.c.check("the ride failed within 3 ticks", log.completed.equals(List.of(false)) && t - brokenAt[0] <= 3,
                log.text() + " after " + (t - brokenAt[0]) + " ticks");
            Vec3 at = log.endAt.isEmpty() ? null : log.endAt.get(0);
            r.c.check("the villager is on A or B", on(at, h.a()) || on(at, h.b()), at);
            r.c.check("no route takes the link", !routeHops(r, h), "");
            r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
            return true;
        });
    }

    /**
     * The kind's available() turns false mid-ride while its ride would run on. At the
     * next availability refresh the ride fails and falls back; once available() is true again, the failure mark still
     * keeps the link out of routes after the next refresh.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "townkit_rides_closed")
    public static void unavailableMidRideFails(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "unavailableMidRideFails", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "unavailableMidRideFails", 10800, 9600).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        NpcEntity npc = r.tv.farmer();
        RideLog log = new RideLog();
        HopKind kind = logged(log, 400, (c, n) -> RideStatus.RUNNING);
        int[] closedAt = {-1};
        int[] endedAt = {-1};
        rideScenario(r, h, kind, 900, t -> {
            if (closedAt[0] < 0) {
                if ((!RideEngine.riding(npc.getNpcId()) || log.ticks < 5) && t < 500) return false;
                r.c.check("riding before the kind closes", RideEngine.riding(npc.getNpcId()), log.text());
                kind.open = false;
                closedAt[0] = t;
                return false;
            }
            if (endedAt[0] < 0) {
                if (log.ends() == 0 && t - closedAt[0] < 100) return false;
                r.c.check("the ride failed within one refresh (45 ticks)", log.completed.equals(List.of(false))
                    && t - closedAt[0] <= 45, log.text() + " after " + (t - closedAt[0]) + " ticks");
                Vec3 at = log.endAt.isEmpty() ? null : log.endAt.get(0);
                r.c.check("the villager is on A or B", on(at, h.a()) || on(at, h.b()), at);
                r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
                kind.open = true;
                endedAt[0] = t;
                return false;
            }
            if (t - endedAt[0] < 45) return false;
            r.c.check("available again, the link is still not offered after a refresh", !routeHops(r, h),
                feet(r.route(h.s(), h.g(), false, null)));
            r.c.check("still one ride", log.rides == 1, log.text());
            return true;
        });
    }

    /** Towntopia 0.1.2's own {@code VillagerModel#needsWaterRescue} test, without any hook. */
    static boolean wouldRescue(NpcEntity npc) {
        if (!npc.isInWaterOrBubble()) return false;
        BlockPos feet = BlockPos.containing(npc.getX(), npc.getY() + 0.1, npc.getZ());
        BlockPos waist = BlockPos.containing(npc.getX(), npc.getY() + 0.9, npc.getZ());
        net.minecraft.world.level.material.FluidState atFeet = npc.level().getFluidState(feet);
        boolean feetInWater = atFeet.is(net.minecraft.tags.FluidTags.WATER);
        if (!feetInWater && !npc.level().getFluidState(waist).is(net.minecraft.tags.FluidTags.WATER)) return false;
        if (!npc.onGround()) return true;
        if (!feetInWater) return false;
        if (atFeet.hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING)
            && atFeet.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)) return false;
        return feet.getY() + atFeet.getHeight(npc.level(), feet) - npc.getY() > 0.5;
    }

    /** Centre inside the pool's x/z box and below the ground's feet level. */
    static boolean inPool(NpcEntity npc, BlockPos p, int g) {
        double x = npc.getX();
        double z = npc.getZ();
        return x >= p.getX() && x <= p.getX() + 7 && z >= p.getZ() && z <= p.getZ() + 3 && npc.getY() < g;
    }

    /**
     * A ride takes its villager into a 2-deep pool and keeps it there 100 ticks: Towntopia's water rescue is off for that
     * villager only (another villager in the same pool is pulled out as usual), and the ride then ends on B.
     */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_rides_rescue")
    public static void waterRescueSuppressedOnlyForRider(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "waterRescueSuppressedOnlyForRider", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "waterRescueSuppressedOnlyForRider", 10000, 9600).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        int g = r.o.getY();
        // pool: inside x p..p+6, z p..p+2, water at g-2 and g-1, stone floor and rim
        BlockPos p = r.o.offset(-4, 0, -6);
        for (int x = p.getX() - 1; x <= p.getX() + 7; x++) {
            for (int z = p.getZ() - 1; z <= p.getZ() + 3; z++) {
                for (int y = g - 3; y <= g - 1; y++) {
                    boolean inside = x >= p.getX() && x <= p.getX() + 6 && z >= p.getZ() && z <= p.getZ() + 2 && y >= g - 2;
                    r.place(new BlockPos(x, y, z), inside ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState());
                }
            }
        }
        r.rebuild();
        NpcEntity rider = r.tv.farmer();
        NpcEntity other = ScenarioBuilder.spawnVillager(r.level, r.tv.mgr(), r.tv.door().north(3).east(2), Profession.FARMER);
        VillagerModel otherModel = r.tv.mgr().getModel(other.getNpcId());
        otherModel.setBrainSuspended(true);
        r.cleanups.add(() -> {
            r.tv.mgr().removeVillager(other.getNpcId());
            if (!other.isRemoved()) other.discard();
        });
        RideLog log = new RideLog();
        int[] rodeAt = {-1};
        int[] endedAt = {-1};
        int[] suppressed = {0};
        int[] otherAsked = {0};
        int[] otherOut = {-1};
        String[] riderLeft = {null};
        rideScenario(r, h, logged(log, 400, (c, n) -> {
            if (c.ticks() == 0) c.body().teleportTo(p.getX() + 1.5, g - 2, p.getZ() + 1.5);
            if (c.ticks() < 100) return RideStatus.RUNNING;
            c.placeAt(c.link().to());
            return RideStatus.DONE;
        }), 1100, t -> {
            boolean riding = RideEngine.riding(rider.getNpcId());
            if (rodeAt[0] < 0) {
                if (!riding || log.ticks < 1) {
                    if (t < 500) return false;
                    r.c.check("the ride started", false, log.text());
                    return true;
                }
                other.teleportTo(p.getX() + 5.5, g - 2, p.getZ() + 1.5);
                rodeAt[0] = t;
                return false;
            }
            if (otherModel.needsWaterRescue()) otherAsked[0]++;
            if (otherOut[0] < 0 && t - rodeAt[0] > 5 && !inPool(other, p, g) && !other.isInWater() && other.onGround()) otherOut[0] = t - rodeAt[0];
            if (endedAt[0] < 0) {
                if (riding) {
                    if (log.ticks > 1 && !inPool(rider, p, g) && riderLeft[0] == null) riderLeft[0] = "at ride tick " + log.ticks + ": " + rider.position();
                    // Towntopia's own test says "rescue"; the hook answers false
                    if (wouldRescue(rider) && !r.model.needsWaterRescue()) suppressed[0]++;
                    return false;
                }
                r.c.check("the rider stayed in the pool while it rode", riderLeft[0] == null, riderLeft[0]);
                r.c.check("its rescue really was asked for and suppressed", suppressed[0] > 0, suppressed[0]);
                r.c.check("the ride completed on B", log.completed.equals(List.of(true)) && on(log.endAt.get(0), h.b()), log.text());
                r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
                endedAt[0] = t;
            }
            if (otherOut[0] < 0 && t - rodeAt[0] < 400) return false;
            r.c.check("the other villager is rescued as usual", otherOut[0] >= 0,
                otherOut[0] >= 0 ? "out after " + otherOut[0] + " ticks" : "still at " + other.position());
            r.c.check("its rescue was not suppressed", otherAsked[0] > 0, otherAsked[0]);
            return true;
        });
    }

    // ---- bubble elevator ----

    static final String AVOIDED_SURFACE = "village.VillagerModel#isOnAvoidedSurface(NavGraph)";
    static final String BLOCKED = "village.VillagerModel#isPhysicallyBlocked(NpcEntity)";
    static final String RECOVER = "village.VillagerModel#tryRecoverToNearbySafePosition(NavGraph,List,boolean)";
    static final int LIFT_HEIGHT = 8;

    /**
     * An 8-high bubble column {@code col} (its bottom block; feet g) with a platform east of it whose top tiles have
     * feet g+8. Bottom stop {@code a} west of the column on the ground (a sign at its feet and a hanging sign at its
     * head keep the water in), top stop {@code t} east of it on the platform. {@code s}, {@code goal}: the walk.
     */
    record Lift(BlockPos col, BlockPos a, BlockPos t, BlockPos s, BlockPos goal, @Nullable BlockPos door) {
    }

    /**
     * Builds the lift: up (soul sand) with a house on the platform (reachable only by the column), the walk from the
     * ground west of the column into the house; or down (magma) with a long ramp off the platform's east side, the
     * walk from the platform to the ground west of the column.
     */
    static Lift lift(Run r, boolean up) {
        BlockPos o = r.o;
        int g = o.getY();
        int h = LIFT_HEIGHT;
        int cx = o.getX() - 3;
        int cz = o.getZ() - 4;
        BlockPos col = new BlockPos(cx, g, cz);
        for (int x = cx + 1; x <= cx + 9; x++) {
            for (int z = cz - 2; z <= cz + 8; z++) {
                for (int y = g; y < g + h; y++) r.place(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        for (int dy = 0; dy < h; dy++) {
            r.place(new BlockPos(cx, g + dy, cz - 1), Blocks.GLASS.defaultBlockState());
            r.place(new BlockPos(cx, g + dy, cz + 1), Blocks.GLASS.defaultBlockState());
            if (dy >= 3) r.place(new BlockPos(cx - 1, g + dy, cz), Blocks.GLASS.defaultBlockState());
        }
        BlockPos a = new BlockPos(cx - 1, g, cz);
        r.place(a.above(2), Blocks.STONE.defaultBlockState());
        r.place(a, Blocks.OAK_SIGN.defaultBlockState());
        r.place(a.above(), Blocks.OAK_HANGING_SIGN.defaultBlockState());
        r.place(col.below(), (up ? Blocks.SOUL_SAND : Blocks.MAGMA_BLOCK).defaultBlockState());
        for (int dy = 0; dy < h; dy++) {
            r.place(col.above(dy), Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(BubbleColumnBlock.DRAG_DOWN, !up));
        }
        BlockPos t = new BlockPos(cx + 1, g + h, cz);
        if (up) {
            BlockPos door = ScenarioBuilder.buildRoom(r.level, cx + 3, g + h, cz + 2, ScenarioBuilder.RoomFurniture.NONE).doorPos();
            for (int x = cx + 1; x <= cx + 9; x++) {
                for (int z = cz; z <= cz + 8; z++) {
                    for (int y = g + h - 1; y <= g + h + 3; y++) r.tv.mgr().markNavigationDirtyAt(new BlockPos(x, y, z));
                }
            }
            r.tv.mgr().markNavigationDirtyAt(door.north().above(2));
            r.rebuild();
            return new Lift(col, a, t, a.west(5), door.south(2), door);
        }
        // the ramp: 1-block steps down eastward from the platform's east edge
        for (int i = 0; i <= h - 2; i++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                for (int y = g; y <= g + h - 2 - i; y++) r.place(new BlockPos(cx + 10 + i, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        r.rebuild();
        return new Lift(col, a, t, t.east(3), a.west(5), null);
    }

    static Set<BlockPos> liftRoomTiles(Lift l) {
        Set<BlockPos> tiles = new HashSet<>();
        BlockPos d = Objects.requireNonNull(l.door());
        for (int x = d.getX() - 2; x <= d.getX() + 2; x++) {
            for (int z = d.getZ() + 1; z <= d.getZ() + 5; z++) tiles.add(new BlockPos(x, d.getY(), z));
        }
        return tiles;
    }

    @Nullable
    static RegistrationFailure liftRoom(Run r, Lift l) {
        return BuildingAccess.room(r.graph(), r.tv.door(), l.door(), Direction.SOUTH, liftRoomTiles(l));
    }

    static boolean liftHops(Run r, Lift l) {
        return hops(r.route(l.s(), l.goal(), true, null), l.a(), l.t());
    }

    static boolean liftFailed(Run r, Lift l) {
        NavNode a = r.graph().nodeAt(l.a().getX(), l.a().getY(), l.a().getZ());
        NavNode b = r.graph().nodeAt(l.t().getX(), l.t().getY(), l.t().getZ());
        return a != null && b != null && (a.hasFailedStepTo(b) || b.hasFailedStepTo(a));
    }

    /** Within 1 block of the tile's centre across, and of its feet up and down. */
    static boolean near(@Nullable Vec3 p, BlockPos feet) {
        return p != null && Math.hypot(p.x - (feet.getX() + 0.5), p.z - (feet.getZ() + 0.5)) <= 1.0
            && Math.abs(p.y - feet.getY()) <= 1.0;
    }

    /** What a lift test saw of the farmer's rides (watched every tick) and of Towntopia's recoveries while it rode. */
    static final class LiftWatch {
        int rides;
        int startedAt = -1;
        int endedAt = -1;
        Vec3 startPos;
        Vec3 endPos;
        boolean was;
        int suppressed;
        int avoided;
        int blocked;
        int recovers;

        String text() {
            return rides + " rides, from tick " + startedAt + " at " + startPos + " to tick " + endedAt + " at " + endPos;
        }

        String recoveries() {
            return "avoided-surface " + avoided + ", blocked " + blocked + ", recover calls " + recovers;
        }
    }

    /** Hooks counting Towntopia's avoided-surface and blocked-body answers (true) and recover calls for the rider. */
    static void watchRecoveries(Run r, LiftWatch w) {
        NpcEntity npc = r.tv.farmer();
        r.hooks.add(PathSpikeTests.hook(AVOIDED_SURFACE, true, call -> {
            if (call.self() == r.model && RideEngine.riding(npc.getNpcId()) && Boolean.TRUE.equals(call.returnValue())) w.avoided++;
        }));
        r.hooks.add(PathSpikeTests.hook(BLOCKED, true, call -> {
            if (call.self() == r.model && RideEngine.riding(npc.getNpcId()) && Boolean.TRUE.equals(call.returnValue())) w.blocked++;
        }));
        r.hooks.add(PathSpikeTests.hook(RECOVER, false, call -> {
            if (call.self() == r.model && RideEngine.riding(npc.getNpcId())) w.recovers++;
        }));
    }

    /** Called every tick of a lift test: notes ride starts and ends and the suppressed rescue answers. */
    static void watch(Run r, LiftWatch w, int t) {
        NpcEntity npc = r.tv.farmer();
        boolean riding = RideEngine.riding(npc.getNpcId());
        if (riding && !w.was) {
            w.rides++;
            w.startedAt = t;
            w.startPos = npc.position();
        } else if (!riding && w.was) {
            w.endedAt = t;
            w.endPos = npc.position();
        }
        if (riding && wouldRescue(npc) && !r.model.needsWaterRescue()) w.suppressed++;
        w.was = riding;
    }

    /** Enables the bubble elevator with the test marker (removed when the test ends). */
    static void enableLift(Run r) {
        r.rules.add(TownKitTransport.enable("townkit-test", TownKitTransport.BUBBLE_ELEVATOR, TestBlocks.TEST_STOP_MARKER.get()));
    }

    /**
     * The lift layout: enables the elevator, hangs the two stop frames, waits for the link (then a local update maps
     * the far side), puts the farmer at s and asks it to walk to the goal; then {@code step} runs every tick with the
     * ticks since the request. {@code before} runs on the first tick, before anything is enabled.
     */
    static void liftScenario(Run r, Lift l, LiftWatch w, int limit, Runnable before, IntPredicate step) {
        int[] linkedAt = {-1};
        int[] placedAt = {-1};
        int[] askedAt = {-1};
        ticks(r, limit, t -> {
            if (t == 1) {
                before.run();
                watchRecoveries(r, w);
                enableLift(r);
                frame(r, l.a());
                frame(r, l.t());
                return false;
            }
            if (linkedAt[0] < 0) {
                if (links(r).isEmpty()) return t >= 300 && fail(r, "no link within 300 ticks: " + linkText(r));
                r.rebuild();
                linkedAt[0] = t;
                return false;
            }
            if (placedAt[0] < 0) {
                NavNode ns = r.graph().nodeAt(l.s().getX(), l.s().getY(), l.s().getZ());
                r.tv.farmer().teleportTo(l.s().getX() + 0.5, ns == null ? l.s().getY() : ns.standY, l.s().getZ() + 0.5);
                placedAt[0] = t;
                return false;
            }
            if (askedAt[0] < 0) {
                if (t - placedAt[0] < 10) return false;
                r.c.check("requestMoveTo the goal is accepted", r.model.requestMoveTo(r.graph(), l.goal(), 0.6F), l.goal());
                askedAt[0] = t;
                return false;
            }
            int since = t - askedAt[0];
            watch(r, w, since);
            return step.test(since);
        });
    }

    static boolean fail(Run r, String why) {
        r.c.check(why, false, "");
        return true;
    }

    /** The checks every completed lift ride shares; logs the ride time. */
    static void checkCompletedRide(Run r, Lift l, LiftWatch w, BlockPos from, BlockPos to, String name) {
        int took = w.endedAt - w.startedAt;
        TownKit.LOG.info("[TownKit test] {}: the ride took {} ticks ({})", name, took, w.text());
        r.c.check("one ride", w.rides == 1, w.text());
        r.c.check("the walker reached the boarding stop before the cut (smoothing kept it)", near(w.startPos, from), w.text());
        r.c.check("the ride ended on the far stop", near(w.endPos, to), w.text());
        r.c.check("within its timeout (height x 8 + 60)", took > 0 && took <= LIFT_HEIGHT * 8 + 60, took);
        r.c.check("the link is not marked failed", !liftFailed(r, l), "");
        r.c.check("Towntopia's avoided-surface and blocked-body recoveries never fired for the rider", w.avoided == 0
            && w.blocked == 0 && w.recovers == 0, w.recoveries());
        r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
    }

    /**
     * An 8-high soul-sand column carries the villager up to a house on a platform it can't reach on foot: the link
     * goes one way up at cost 16, makes the house reachable, and the villager rides it and walks into the house.
     * Also covers the bubble ride's rescue: its rescue is asked for and suppressed while it rides.
     */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_elevator_up")
    public static void elevatorUp(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "elevatorUp", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "elevatorUp", 7600, 10000).orElse(null);
        if (r == null) return;
        Lift l = lift(r, true);
        LiftWatch w = new LiftWatch();
        liftScenario(r, l, w, 1100, () -> {
            r.c.check("no link: the house on the platform is unreachable", liftRoom(r, l) == RegistrationFailure.ROOM_UNREACHABLE,
                liftRoom(r, l));
        }, t -> {
            if (t == 1) {
                List<TransportLink> ls = links(r);
                TransportLink k = ls.isEmpty() ? null : ls.get(0);
                r.c.check("one link, up from the bottom stop to the top stop, one way, cost 16", ls.size() == 1
                    && k.kind().equals(TownKitTransport.BUBBLE_ELEVATOR) && k.from().equals(l.a()) && k.to().equals(l.t())
                    && !k.bothWays() && k.cost() == 2 * LIFT_HEIGHT, linkText(r));
                r.c.check("with the link: the house is reachable", liftRoom(r, l) == null, liftRoom(r, l));
                r.c.check("the route to the house takes the link", liftHops(r, l), feet(r.route(l.s(), l.goal(), true, null)));
            }
            boolean arrived = arrived(r, l.goal());
            if (!arrived && !r.model.hasRecentMoveFailure(l.goal()) && t < 800) return false;
            r.c.check("arrives in the house", arrived, "after " + t + " ticks at " + r.tv.farmer().blockPosition().toShortString());
            checkCompletedRide(r, l, w, l.a(), l.t(), "elevatorUp");
            r.c.check("its water rescue was asked for and suppressed while it rode", w.suppressed > 0, w.suppressed);
            return true;
        });
    }

    /** A magma column takes the villager down from the platform (a long ramp is the only other way) to the ground. */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_elevator_down")
    public static void elevatorDown(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "elevatorDown", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "elevatorDown", 8000, 10000).orElse(null);
        if (r == null) return;
        Lift l = lift(r, false);
        LiftWatch w = new LiftWatch();
        liftScenario(r, l, w, 1100, () -> {
        }, t -> {
            if (t == 1) {
                List<TransportLink> ls = links(r);
                TransportLink k = ls.isEmpty() ? null : ls.get(0);
                r.c.check("one link, down from the top stop to the bottom stop, one way, cost 16", ls.size() == 1
                    && k.from().equals(l.t()) && k.to().equals(l.a()) && !k.bothWays() && k.cost() == 2 * LIFT_HEIGHT, linkText(r));
                r.c.check("the route down takes the link", hops(r.route(l.s(), l.goal(), true, null), l.t(), l.a()),
                    feet(r.route(l.s(), l.goal(), true, null)));
            }
            boolean arrived = arrived(r, l.goal());
            if (!arrived && !r.model.hasRecentMoveFailure(l.goal()) && t < 800) return false;
            r.c.check("arrives on the ground", arrived, "after " + t + " ticks at " + r.tv.farmer().blockPosition().toShortString());
            checkCompletedRide(r, l, w, l.t(), l.a(), "elevatorDown");
            return true;
        });
    }

    /**
     * TownKit registers the bubble elevator but doesn't enable it: with no enable call the marker frames make no stop
     * and no link, and the house on the platform stays unreachable.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_elevator_off")
    public static void elevatorDisabledByDefault(GameTestHelper helper) {
        Run r = start(helper, "elevatorDisabledByDefault", 8400, 10000).orElse(null);
        if (r == null) return;
        Lift l = lift(r, true);
        ItemFrame[] f = new ItemFrame[2];
        ticks(r, 200, t -> {
            if (t == 1) {
                boolean registered;
                try {
                    Subscription s = TownKitTransport.registerKind("townkit-test", new TransportKind() {
                        @Override
                        public ResourceLocation id() {
                            return TownKitTransport.BUBBLE_ELEVATOR;
                        }

                        @Override
                        public List<TransportLink> links(ServerLevel level, List<TransportStop> stops) {
                            return List.of();
                        }

                        @Override
                        public boolean available(ServerLevel level, TransportLink link) {
                            return false;
                        }

                        @Override
                        public Ride ride(RideContext ctx) {
                            return c -> RideStatus.FAILED;
                        }
                    });
                    s.remove();
                    registered = false;
                } catch (IllegalArgumentException e) {
                    registered = true;
                }
                r.c.check("TownKit has registered the bubble elevator kind", registered, "");
                r.c.check("but not enabled it", townkit.core.nav.TransportKinds.GLOBAL.live(TownKitTransport.BUBBLE_ELEVATOR) == null, "");
                f[0] = frame(r, l.a());
                f[1] = frame(r, l.t());
                return false;
            }
            if (t < 60) return false;
            r.c.check("no stops", stops(r).isEmpty(), linkText(r));
            r.c.check("no links, active() false", links(r).isEmpty() && !TownKitPaths.active(), linkText(r));
            r.c.check("the frames don't glow", !glows(f[0]) && !glows(f[1]), "");
            r.c.check("the house stays unreachable", liftRoom(r, l) == RegistrationFailure.ROOM_UNREACHABLE, liftRoom(r, l));
            return true;
        });
    }

    /**
     * The column is drained while the villager rides up. The ride fails within one availability
     * refresh, the villager is put on a stop, the link is marked failed, and after the next refresh the kind reports
     * the link unavailable and no route takes it.
     */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_elevator_drained")
    public static void elevatorDrainedMidRide(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "elevatorDrainedMidRide", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "elevatorDrainedMidRide", 8800, 10000).orElse(null);
        if (r == null) return;
        Lift l = lift(r, true);
        LiftWatch w = new LiftWatch();
        int[] drainedAt = {-1};
        liftScenario(r, l, w, 1100, () -> {
        }, t -> {
            if (drainedAt[0] < 0) {
                if (!w.was || t - w.startedAt < 5) return t >= 600 && fail(r, "never rode: " + w.text());
                for (int dy = LIFT_HEIGHT - 1; dy >= 0; dy--) r.place(l.col().above(dy), Blocks.AIR.defaultBlockState());
                drainedAt[0] = t;
                return false;
            }
            if (w.endedAt < 0) return t - drainedAt[0] >= 100 && fail(r, "the ride didn't end within 100 ticks of the drain");
            if (t - w.endedAt < 45) return false;
            r.c.check("the ride failed within one refresh (45 ticks) of the drain", w.endedAt - drainedAt[0] <= 45,
                (w.endedAt - drainedAt[0]) + " ticks");
            r.c.check("the villager was put on a stop", near(w.endPos, l.a()) || near(w.endPos, l.t()), w.text());
            r.c.check("the step is marked failed", liftFailed(r, l), "");
            List<TransportLink> ls = links(r);
            var kind = townkit.core.nav.TransportKinds.GLOBAL.live(TownKitTransport.BUBBLE_ELEVATOR);
            r.c.check("after the next refresh the kind reports the link unavailable", !ls.isEmpty() && kind != null
                && !kind.available(r.level, ls.get(0)), linkText(r));
            r.c.check("no route takes the link", !liftHops(r, l), feet(r.route(l.s(), l.goal(), true, null)));
            r.c.check("one ride", w.rides == 1, w.text());
            r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
            return true;
        });
    }

    /** The top stop's frame is broken mid-ride: the link goes, the ride fails at once, the villager is on a stop. */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_elevator_frame")
    public static void markerBrokenMidRide(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "markerBrokenMidRide", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "markerBrokenMidRide", 9200, 10000).orElse(null);
        if (r == null) return;
        Lift l = lift(r, true);
        LiftWatch w = new LiftWatch();
        int[] brokenAt = {-1};
        liftScenario(r, l, w, 1100, () -> {
        }, t -> {
            if (brokenAt[0] < 0) {
                if (!w.was || t - w.startedAt < 5) return t >= 600 && fail(r, "never rode: " + w.text());
                ItemFrame ft = r.frames.stream().filter(f -> f.blockPosition().equals(l.t())).findFirst().orElse(null);
                r.c.check("the top stop's frame found", ft != null, r.frames);
                if (ft == null) return true;
                ft.discard();
                brokenAt[0] = t;
                return false;
            }
            if (w.endedAt < 0) return t - brokenAt[0] >= 60 && fail(r, "the ride didn't end within 60 ticks of the break");
            r.c.check("the ride failed within 3 ticks", w.endedAt - brokenAt[0] <= 3, (w.endedAt - brokenAt[0]) + " ticks");
            r.c.check("the link is gone", links(r).isEmpty(), linkText(r));
            r.c.check("the villager was put on a stop", near(w.endPos, l.a()) || near(w.endPos, l.t()), w.text());
            r.c.check("no route takes the link", !liftHops(r, l), "");
            r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
            return true;
        });
    }

    // ---- 0.6.1: dropped walks, raiders, a real unload, a real traveler, a kind enabled at setup ----

    /**
     * A Towntopia NPC no village holds (no model, like a traveler), standing on {@code feet}; discarded when the test
     * ends. Null (with a failed check) when it can't be made.
     */
    @Nullable
    static NpcEntity stray(Run r, BlockPos feet) {
        NavNode n = r.graph().nodeAt(feet.getX(), feet.getY(), feet.getZ());
        NpcEntity npc = com.alex.test.DynamicVillages.NPC.get().create(r.level);
        r.c.check("a stray NPC can be made", npc != null, "");
        if (npc == null) return null;
        npc.moveTo(feet.getX() + 0.5, n == null ? feet.getY() : n.standY, feet.getZ() + 0.5, 0.0F, 0.0F);
        // not a village member (as a traveler isn't), so no village links it on join (see walkersWithoutAModelRide)
        npc.addTag("dynamicvillages.waiting_settler");
        r.level.addFreshEntity(npc);
        r.cleanups.add(() -> {
            if (!npc.isRemoved()) npc.discard();
        });
        r.c.check("the stray NPC has no village and no model", npc.getVillageLink() == null
            && r.tv.mgr().getModel(npc.getNpcId()) == null, npc.getVillageLink());
        return npc;
    }

    /** Lets {@code walk}'s current move go the way Towntopia's owners drop a walk (TravelerApproach.clear, dropChaseWalk). */
    static void drop(OwnWalk walk) {
        if (walk.move != null) walk.move.closeAllOpenedDoors(walk.npc);
        walk.move = null;
    }

    /**
     * A model-less walker's walk is dropped mid-ride (its owner lets it go and never ticks it again, as
     * TravelerApproach.clear or a guard whose foe is gone do). The ride ends within DROPPED_WALK_TICKS + 20 ticks, not
     * after timeoutTicks + 100; the rider is on a stop, the link isn't marked failed and no ride state is left.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "townkit_rides_dropped")
    public static void droppedWalkEndsRideSoon(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "droppedWalkEndsRideSoon", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "droppedWalkEndsRideSoon", 8000, 10400).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        RideLog log = new RideLog();
        NpcEntity[] npc = {null};
        OwnWalk[] walk = {null};
        int[] spawnedAt = {-1};
        int[] droppedAt = {-1};
        ticks(r, 900, t -> {
            if (t == 1) {
                transport(r, true, logged(log, 400, (c, n) -> RideStatus.RUNNING));
                frame(r, h.a());
                frame(r, h.b());
                return false;
            }
            if (npc[0] == null) {
                if (links(r).isEmpty()) return false;
                npc[0] = stray(r, h.s());
                if (npc[0] == null) return true;
                spawnedAt[0] = t;
                return false;
            }
            if (droppedAt[0] < 0) {
                if (t - spawnedAt[0] < 10) return false;
                if (walk[0] == null) walk[0] = new OwnWalk(npc[0], h.g());
                walk[0].tick(r);
                if ((!RideEngine.riding(npc[0].getNpcId()) || log.ticks < 5) && t - spawnedAt[0] < 500) return false;
                r.c.check("riding before the walk is dropped", RideEngine.riding(npc[0].getNpcId()), log.text());
                drop(walk[0]);
                droppedAt[0] = t;
                return false;
            }
            int since = t - droppedAt[0];
            if (log.ends() == 0 && since <= RideEngine.DROPPED_WALK_TICKS + 20) return false;
            r.c.check("the ride ended within DROPPED_WALK_TICKS + 20 ticks, not completed", log.ends() == 1
                && !log.completed.get(0) && since <= RideEngine.DROPPED_WALK_TICKS + 20, log.text() + " after " + since + " ticks");
            r.c.check("not before the walk was overdue", since >= RideEngine.DROPPED_WALK_TICKS - 5, since + " ticks");
            Vec3 at = log.endAt.isEmpty() ? null : log.endAt.get(0);
            r.c.check("the rider is on A or B", on(at, h.a()) || on(at, h.b()), at);
            r.c.check("the link is not marked failed", !stepFailed(r, h) && routeHops(r, h), "");
            r.c.check("its ride mark is gone", !npc[0].getPersistentData().contains(RideEngine.RIDE_TAG), npc[0].getPersistentData());
            r.c.check("no ride state left", !RideEngine.riding(npc[0].getNpcId()) && RideEngine.activeRides() == 0,
                RideEngine.activeRides());
            return true;
        });
    }

    /**
     * The dropped-walk check must not end a held walk's ride: a model-less walker's walk is held mid-ride (kept, not ticked) for
     * DROPPED_WALK_TICKS + 60 ticks, as Towntopia holds a traveler's walk while a player has its menu open. The ride is
     * still on; once the walk ticks again the ride completes on B and the walk goes on to the goal past B.
     */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_rides_held")
    public static void heldWalkKeepsRide(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "heldWalkKeepsRide", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "heldWalkKeepsRide", 8400, 10400).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        RideLog log = new RideLog();
        boolean[] release = {false};
        NpcEntity[] npc = {null};
        OwnWalk[] walk = {null};
        int[] spawnedAt = {-1};
        int[] heldAt = {-1};
        int[] resumedAt = {-1};
        int hold = RideEngine.DROPPED_WALK_TICKS + 60;
        ticks(r, 1100, t -> {
            if (t == 1) {
                transport(r, true, logged(log, 400, (c, n) -> release[0] ? HOP_STEP.tick(c, n) : RideStatus.RUNNING));
                frame(r, h.a());
                frame(r, h.b());
                return false;
            }
            if (npc[0] == null) {
                if (links(r).isEmpty()) return false;
                npc[0] = stray(r, h.s());
                if (npc[0] == null) return true;
                spawnedAt[0] = t;
                return false;
            }
            if (heldAt[0] < 0) {
                if (t - spawnedAt[0] < 10) return false;
                if (walk[0] == null) walk[0] = new OwnWalk(npc[0], h.g());
                walk[0].tick(r);
                if ((!RideEngine.riding(npc[0].getNpcId()) || log.ticks < 5) && t - spawnedAt[0] < 500) return false;
                r.c.check("riding before the walk is held", RideEngine.riding(npc[0].getNpcId()), log.text());
                heldAt[0] = t;
                return false;
            }
            if (resumedAt[0] < 0) {
                // held: the walk is kept (its doors aren't closed), but nobody ticks it
                if (log.ends() > 0 || !RideEngine.riding(npc[0].getNpcId())) {
                    r.c.check("the held ride stayed on", false, log.text() + " after " + (t - heldAt[0]) + " held ticks");
                    return true;
                }
                if (t - heldAt[0] < hold) return false;
                r.c.check("still riding after " + hold + " held ticks", RideEngine.riding(npc[0].getNpcId())
                    && log.ends() == 0, log.text());
                release[0] = true;
                resumedAt[0] = t;
            }
            boolean over = walk[0].tick(r);
            if (!over && t - resumedAt[0] < 600) return false;
            r.c.check("one ride, completed once the walk ticked again", log.rides == 1 && log.completed.equals(List.of(true)),
                log.text());
            r.c.check("the ride left the walker on B", !log.endAt.isEmpty() && on(log.endAt.get(0), h.b()), log.text());
            r.c.check("it reaches the goal past B", walk[0].done && PathSpikeTests.dist(npc[0], h.g()) <= 1.5,
                npc[0].blockPosition().toShortString() + " " + walk[0].last);
            r.c.check("no ride state left", RideEngine.activeRides() == 0, RideEngine.activeRides());
            return true;
        });
    }

    /** A walk of any PathfinderMob over a route the test plans with {@code rules} (null: the villager's), as RaiderMapWalk ticks one. */
    static final class MobWalk {
        final PathfinderMob mob;
        final BlockPos goal;
        @Nullable
        final RouteRules rules;
        @Nullable
        MoveToCommand move;
        boolean hopped;
        int stucks;
        boolean done;
        String last = "";

        MobWalk(PathfinderMob mob, BlockPos goal, @Nullable RouteRules rules) {
            this.mob = mob;
            this.goal = goal;
            this.rules = rules;
        }

        /** One tick; true once the walk is over (done, or 3 STUCKs or no routes). */
        boolean tick(Run r, Hop h) {
            if (this.move == null) {
                AStarPathfinder.Route route = r.route(this.mob.blockPosition(), this.goal, false, this.rules);
                this.hopped |= hops(route, h.a(), h.b());
                this.move = route == null ? null : FollowWalk.forRoute(r.graph(), this.mob, route, 0.6F, false, this.stucks, Set.of());
                if (this.move == null) {
                    this.last = "no route from " + this.mob.blockPosition().toShortString();
                    return ++this.stucks >= 3;
                }
            }
            MoveToCommand.Status s = this.move.tick(this.mob);
            if (s == MoveToCommand.Status.RUNNING) return false;
            this.last = s + " at " + this.mob.blockPosition().toShortString();
            this.move.closeAllOpenedDoors(this.mob);
            this.move = null;
            if (s == MoveToCommand.Status.DONE) {
                this.done = true;
                return true;
            }
            return s != MoveToCommand.Status.REPLAN && ++this.stucks >= 3;
        }
    }

    /**
     * Raiders never ride: a pillager walks its own raider route past an enabled link (round the wall, next to both stops)
     * to the goal; then it is made to walk the villagers' route, which takes the link, so its walk reaches the link's
     * cut. No ride ever starts (the kind made none, RideEngine has none) and the pillager is never moved over the link.
     */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_rides_raider")
    public static void raiderNeverRides(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "raiderNeverRides", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "raiderNeverRides", 8800, 10400).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        RideLog log = new RideLog();
        net.minecraft.world.entity.monster.Pillager[] raider = {null};
        MobWalk[] walk = {null, null};
        int[] phase = {0};
        int[] phaseAt = {0};
        int[] ridesSeen = {0};
        boolean[] reachedA = {false};
        ticks(r, 1100, t -> {
            if (t == 1) {
                transport(r, true, logged(log, 400, HOP_STEP));
                frame(r, h.a());
                frame(r, h.b());
                return false;
            }
            if (RideEngine.activeRides() > 0) ridesSeen[0]++;
            NavNode ns = r.graph().nodeAt(h.s().getX(), h.s().getY(), h.s().getZ());
            double sy = ns == null ? h.s().getY() : ns.standY;
            if (phase[0] == 0) {
                if (links(r).isEmpty()) return false;
                var p = EntityType.PILLAGER.create(r.level);
                r.c.check("a pillager can be made", p != null, "");
                if (p == null) return true;
                p.moveTo(h.s().getX() + 0.5, sy, h.s().getZ() + 0.5, 0.0F, 0.0F);
                p.setPersistenceRequired();
                p.setInvulnerable(true);
                // only the test's walk moves it (no goals of its own; guards can't kill it)
                p.removeFreeWill();
                r.level.addFreshEntity(p);
                raider[0] = p;
                r.cleanups.add(() -> {
                    if (!p.isRemoved()) p.discard();
                });
                r.c.check("the villagers' route takes the link", routeHops(r, h), feet(r.route(h.s(), h.g(), false, null)));
                phase[0] = 1;
                phaseAt[0] = t;
                return false;
            }
            var p = raider[0];
            if (phase[0] == 1) {
                if (t - phaseAt[0] < 10) return false;
                if (walk[0] == null) walk[0] = new MobWalk(p, h.g(), RouteRules.raider(r.tv.mgr(), r.graph(), false));
                boolean over = walk[0].tick(r, h);
                if (!over && t - phaseAt[0] < 500) return false;
                r.c.check("own route: the raider's route never takes the link", !walk[0].hopped, walk[0].last);
                r.c.check("own route: it walks round the wall to the goal", walk[0].done && p.blockPosition().distManhattan(h.g()) <= 2,
                    p.blockPosition().toShortString() + " " + walk[0].last);
                p.teleportTo(h.s().getX() + 0.5, sy, h.s().getZ() + 0.5);
                phase[0] = 2;
                phaseAt[0] = t;
                return false;
            }
            if (t - phaseAt[0] < 10) return false;
            if (walk[1] == null) walk[1] = new MobWalk(p, h.g(), null);
            if (p.blockPosition().distManhattan(h.a()) <= 1) reachedA[0] = true;
            boolean over = walk[1].tick(r, h);
            // the walk reaches the cut at A and Towntopia ends it there (no ride); give it 200 ticks, then stop
            if (!over && t - phaseAt[0] < 200 && !(reachedA[0] && walk[1].move == null)) return false;
            r.c.check("villagers' route: the walk took the link's pair", walk[1].hopped, walk[1].last);
            r.c.check("villagers' route: the pillager reached the boarding stop A", reachedA[0], p.blockPosition().toShortString());
            r.c.check("no ride ever started", ridesSeen[0] == 0 && log.rides == 0, ridesSeen[0] + " ticks with a ride, " + log.text());
            r.c.check("no ride state left", RideEngine.activeRides() == 0, RideEngine.activeRides());
            return true;
        });
    }

    /**
     * The rider's chunk really unloads mid-ride: the ride holds a model-less walker in a far chunk the test force-loaded;
     * the test drops the force and stops ticking the walk, Minecraft saves the entity and removes it (UNLOADED_TO_CHUNK). The ride ends with no state
     * left; when the chunk is loaded again the saved walker joins, is put on A or B, and its ride mark is gone.
     */
    @GameTest(template = "empty", timeoutTicks = 1900, batch = "townkit_rides_real_unload")
    public static void realUnloadMidRide(GameTestHelper helper) {
        // needs a transport link, which is only built with hooks on: hooks off, the probe checks transport stays off
        if (HooksOff.skip(helper, "realUnloadMidRide", HooksOff.Module.PATHS)) return;
        Run r = start(helper, "realUnloadMidRide", 9200, 10400).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        RideLog log = new RideLog();
        // 240 blocks south of the field: past the village's forced chunks (4 around it) and its route map (144 blocks)
        ChunkPos far = new ChunkPos(new BlockPos(r.o.getX(), 0, r.o.getZ() + 240));
        BlockPos[] spot = {null};
        NpcEntity[] npc = {null};
        OwnWalk[] walk = {null};
        java.util.UUID[] id = {null};
        int[] spawnedAt = {-1};
        int[] unforcedAt = {-1};
        int[] reforcedAt = {-1};
        boolean[] forced = {false};
        r.cleanups.add(() -> {
            if (forced[0]) r.level.setChunkForced(far.x, far.z, false);
            Entity again = id[0] == null ? null : r.level.getEntity(id[0]);
            if (again != null && !again.isRemoved()) again.discard();
        });
        ticks(r, 1700, t -> {
            if (t == 1) {
                r.level.setChunkForced(far.x, far.z, true);
                forced[0] = true;
                r.level.getChunk(far.x, far.z);
                BlockPos top = r.level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, far.getMiddleBlockPosition(0));
                r.level.setBlock(top, Blocks.GLASS.defaultBlockState(), 3);
                spot[0] = top.above();
                BlockPos hold = spot[0];
                transport(r, true, logged(log, 400, (c, n) -> {
                    c.body().setPos(hold.getX() + 0.5, hold.getY(), hold.getZ() + 0.5);
                    c.body().setDeltaMovement(Vec3.ZERO);
                    return RideStatus.RUNNING;
                }));
                frame(r, h.a());
                frame(r, h.b());
                return false;
            }
            if (npc[0] == null) {
                if (links(r).isEmpty()) return false;
                npc[0] = stray(r, h.s());
                if (npc[0] == null) return true;
                id[0] = npc[0].getUUID();
                spawnedAt[0] = t;
                return false;
            }
            NpcEntity body = npc[0];
            if (unforcedAt[0] < 0) {
                if (t - spawnedAt[0] < 10) return false;
                if (walk[0] == null) walk[0] = new OwnWalk(body, h.g());
                walk[0].tick(r);
                boolean inFar = new ChunkPos(body.blockPosition()).equals(far);
                if ((!RideEngine.riding(body.getNpcId()) || log.ticks < 5 || !inFar) && t - spawnedAt[0] < 500) return false;
                r.c.check("riding, held in the far chunk", RideEngine.riding(body.getNpcId()) && inFar, body.position());
                r.c.check("marked as riding, with its dimension", body.getPersistentData().getCompound(RideEngine.RIDE_TAG)
                    .getString("dim").equals(r.level.dimension().location().toString()), body.getPersistentData());
                r.level.setChunkForced(far.x, far.z, false);
                forced[0] = false;
                unforcedAt[0] = t;
                return false;
            }
            if (reforcedAt[0] < 0) {
                if (!body.isRemoved()) {
                    // the walk is no longer ticked: ticking it from here reloaded the far chunk every tick (a one-tick
                    // load ticket), so the chunk never unloaded and the ride timed out instead; now it ends through the unload
                    if (t - unforcedAt[0] < 600) return false;
                    r.c.check("the far chunk unloaded within 600 ticks", false, "still loaded: " + r.level.hasChunk(far.x, far.z));
                    return true;
                }
                r.c.check("removed by the chunk unload", body.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK,
                    body.getRemovalReason());
                if (log.ends() == 0 && t - unforcedAt[0] < 640) return false;
                r.c.check("the ride ended when the walker unloaded, not completed", log.completed.equals(List.of(false)), log.text());
                r.c.check("no ride state left after the unload", RideEngine.activeRides() == 0, RideEngine.activeRides());
                r.level.setChunkForced(far.x, far.z, true);
                forced[0] = true;
                reforcedAt[0] = t;
                return false;
            }
            Entity again = r.level.getEntity(id[0]);
            if (!(again instanceof NpcEntity) && t - reforcedAt[0] < 400) {
                LoadWait.notYet();
                return false;
            }
            r.c.check("the saved walker loads back with its chunk", again instanceof NpcEntity, again);
            if (!(again instanceof NpcEntity back)) return true;
            r.c.check("the reloaded walker stands on A or B", on(back.position(), h.a()) || on(back.position(), h.b()),
                back.position() + " (saved in the far chunk at " + spot[0].toShortString() + ")");
            r.c.check("its ride mark is gone", !back.getPersistentData().contains(RideEngine.RIDE_TAG), back.getPersistentData());
            r.c.check("no ride state left after the reload", !RideEngine.riding(back.getNpcId()) && RideEngine.activeRides() == 0, "");
            r.c.check("still one ride", log.rides == 1, log.text());
            return true;
        });
    }

    /**
     * A real Towntopia traveler rides a link: a NomadEntity of the test village (registered with the village's nomads as
     * Towntopia's spawnNomad does, departure 6000 ticks away, daytime) starts in a glass pocket whose only way out is a
     * hop link from A (in the pocket) to B. Its own NomadLogic/TravelerApproach walk to the town hall door is cut at the
     * link, the ride puts it on B, and Towntopia walks it on towards the town hall.
     */
    @GameTest(template = "empty", timeoutTicks = 1800, batch = "townkit_rides_real_traveler")
    public static void realTravelerRidesLink(GameTestHelper helper) {
        Run r = start(helper, "realTravelerRidesLink", 9600, 10400).orElse(null);
        if (r == null) return;
        long dayTimeBefore = r.level.getDayTime();
        r.level.setDayTime(1000L);
        r.cleanups.add(() -> r.level.setDayTime(dayTimeBefore));
        // pocket: tiles a, a.north(), a.north(2), walled in on every side; b is 3 south of a, outside
        BlockPos a = r.o;
        BlockPos b = a.south(3);
        BlockPos inside = a.north(2);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -3; dz <= 1; dz++) {
                if (dx == 0 && dz > -3 && dz < 1) continue;
                for (int dy = 0; dy <= 2; dy++) r.place(a.offset(dx, dy, dz), Blocks.GLASS.defaultBlockState());
            }
        }
        r.rebuild();
        RideLog log = new RideLog();
        NomadEntity[] nomad = {null};
        boolean[] rebuilt = {false};
        int[] spawnedAt = {-1};
        int[] endedAt = {-1};
        double[] bestZ = {Double.NEGATIVE_INFINITY};
        ticks(r, 1500, t -> {
            if (t == 1) {
                transport(r, true, logged(log, 400, HOP_STEP));
                frame(r, a);
                frame(r, b);
                return false;
            }
            if (links(r).isEmpty()) {
                if (t < 300) return false;
                r.c.checkHooksOn("the link went in", false, linkText(r));
                return true;
            }
            if (!rebuilt[0]) {
                // the pocket is walled in: the link's start tile was marked dirty, the local update maps it
                r.rebuild();
                rebuilt[0] = true;
                return false;
            }
            if (nomad[0] == null) {
                r.c.check("the pocket is in the route map", r.graph().nodeAt(inside.getX(), inside.getY(), inside.getZ()) != null, inside);
                AStarPathfinder.Route out = r.route(inside, b.south(3), false, null);
                r.c.check("the only way out of the pocket is the link", hops(out, a, b), feet(out));
                NomadEntity n = com.alex.test.DynamicVillages.NOMAD.get().create(r.level);
                r.c.check("a nomad can be made", n != null, "");
                if (n == null) return true;
                long now = r.level.getGameTime();
                long departure = now + 6000L;
                n.setDepartureSchedule(departure, (int) Math.floorMod(r.level.getDayTime() + 6000L, 24000L), r.level.getDayTime());
                n.setSpawnOrigin(r.level, inside);
                n.setVillageId(r.tv.mgr().getVillageId());
                NavNode ni = r.graph().nodeAt(inside.getX(), inside.getY(), inside.getZ());
                n.moveTo(inside.getX() + 0.5, ni == null ? inside.getY() : ni.standY, inside.getZ() + 0.5, 0.0F, 0.0F);
                // as NomadSpawnManager#spawnNomad does: the village's nomads know it (else its lifecycle ends it at once)
                r.tv.mgr().nomads().registerLegacyNomad(r.level, n.getUUID(), departure);
                r.c.check("the nomad joins the level", r.level.addFreshEntity(n), "");
                nomad[0] = n;
                r.cleanups.add(() -> {
                    r.tv.mgr().nomads().finishNomad(n.getUUID(), r.level.getGameTime(), "test over");
                    if (!n.isRemoved()) n.discard();
                });
                spawnedAt[0] = t;
                return false;
            }
            NomadEntity n = nomad[0];
            if (n.isRemoved()) {
                r.c.check("the nomad stayed in the world", false, n.getRemovalReason() + ", " + log.text());
                return true;
            }
            if (endedAt[0] < 0) {
                if (log.ends() == 0) {
                    if (t - spawnedAt[0] < 600) return false;
                    r.c.check("the nomad rode the link within 600 ticks", false, log.text() + " at " + n.blockPosition().toShortString());
                    return true;
                }
                r.c.check("a real traveler: no village model", n.getVillageLink() == null
                    && r.tv.mgr().getModel(n.getNpcId()) == null, n.getVillageLink());
                r.c.check("one ride, completed", log.rides == 1 && log.completed.equals(List.of(true)), log.text());
                r.c.check("the ride left it on B", !log.endAt.isEmpty() && on(log.endAt.get(0), b), log.text());
                endedAt[0] = t;
                return false;
            }
            bestZ[0] = Math.max(bestZ[0], n.getZ());
            // south is +z: the town hall door is 12 south of the field origin, 9 south of B
            boolean walkedOn = bestZ[0] >= b.getZ() + 3.5;
            if (!walkedOn && t - endedAt[0] < 400) return false;
            r.c.check("Towntopia walked it on from B towards the town hall", walkedOn,
                String.format("best z %.1f, B z %d, now at %s", bestZ[0], b.getZ(), n.blockPosition().toShortString()));
            r.c.check("still one ride", log.rides == 1, log.text());
            r.c.check("no ride state left", !RideEngine.riding(n.getNpcId()) && RideEngine.activeRides() == 0, RideEngine.activeRides());
            return true;
        });
    }

    /**
     * Transport enabled at mod setup, as the developer docs tell addons: {@code townkit:test_setup_kind} is registered and
     * enabled from TownKitGameTests on FMLCommonSetupEvent (nobody enables it mid-server). Two of its marker frames make
     * a link in a test village, the route takes it, and the farmer's walk rides it to the goal past B.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "townkit_rides_setup_kind")
    public static void kindEnabledAtSetupWorks(GameTestHelper helper) {
        if (TownKitGameTests.perfRun()) {
            // perf runs don't enable the setup kind (it would add transport hooks to the A/B): nothing to test, and no village
            Checks c = new Checks("kindEnabledAtSetupWorks");
            c.check("perf run: the setup kind is not enabled", !TownKitGameTests.SetupKind.enabledAtSetup
                && townkit.core.nav.TransportKinds.GLOBAL.live(TownKitGameTests.SetupKind.ID) == null, "");
            c.finish(helper);
            return;
        }
        Run r = start(helper, "kindEnabledAtSetupWorks", 10000, 10400).orElse(null);
        if (r == null) return;
        Hop h = hopLayout(r);
        r.rebuild();
        int ridesBefore = TownKitGameTests.SetupKind.RIDES.get();
        int[] placedAt = {-1};
        int[] askedAt = {-1};
        ticks(r, 900, t -> {
            if (t == 1) {
                r.c.check("the kind was enabled from FMLCommonSetupEvent", TownKitGameTests.SetupKind.enabledAtSetup, "");
                r.c.check("the kind is live at server time without any test enabling it",
                    townkit.core.nav.TransportKinds.GLOBAL.live(TownKitGameTests.SetupKind.ID) != null, "");
                frame(r, h.a(), new ItemStack(TestBlocks.TEST_SETUP_MARKER.get()));
                frame(r, h.b(), new ItemStack(TestBlocks.TEST_SETUP_MARKER.get()));
                return false;
            }
            if (placedAt[0] < 0) {
                if (links(r).isEmpty()) {
                    if (t < 300) return false;
                    r.c.checkHooksOn("the setup kind's link went in", false, linkText(r));
                    return true;
                }
                TransportLink l = links(r).get(0);
                r.c.check("one link of townkit:test_setup_kind between the stops", links(r).size() == 1
                    && l.kind().equals(TownKitGameTests.SetupKind.ID) && Set.of(l.from(), l.to()).equals(Set.of(h.a(), h.b())),
                    linkText(r));
                r.c.check("the route takes the link", routeHops(r, h), feet(r.route(h.s(), h.g(), false, null)));
                NavNode ns = r.graph().nodeAt(h.s().getX(), h.s().getY(), h.s().getZ());
                r.tv.farmer().teleportTo(h.s().getX() + 0.5, ns == null ? h.s().getY() : ns.standY, h.s().getZ() + 0.5);
                placedAt[0] = t;
                return false;
            }
            if (askedAt[0] < 0) {
                if (t - placedAt[0] < 10) return false;
                r.c.check("requestMoveTo the goal past B is accepted", r.model.requestMoveTo(r.graph(), h.g(), 0.6F), h.g());
                askedAt[0] = t;
                return false;
            }
            boolean arrived = arrived(r, h.g());
            if (!arrived && !r.model.hasRecentMoveFailure(h.g()) && t - askedAt[0] < 600) return false;
            int rides = TownKitGameTests.SetupKind.RIDES.get() - ridesBefore;
            r.c.check("one ride of the setup kind", rides == 1, rides);
            r.c.check("reaches the goal past B", arrived, r.tv.farmer().blockPosition().toShortString());
            r.c.check("no ride state left", noRideLeft(r), RideEngine.activeRides());
            return true;
        });
    }
}
