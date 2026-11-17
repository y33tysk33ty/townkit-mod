package townkit.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.RouteRules;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The bridge's answers. Blocks, nodes and graphs are made without Minecraft's bootstrap (blocks only need identity:
 * the rule maps compare by identity).
 */
class NavBridgeTest {
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final Block ROAD = block();
    private static final Block WALL = block();
    private static final Block GRASS = block();

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Block block() {
        try {
            return (Block) UNSAFE.allocateInstance(Block.class);
        } catch (InstantiationException e) {
            throw new AssertionError(e);
        }
    }

    /** A flat dry tile (surface y 64, so every step between two of them passes Towntopia's own test). */
    private static NavNode node(int x, int z, Block surface) {
        try {
            Constructor<NavNode> c = NavNode.class.getDeclaredConstructor(int.class, int.class, int.class,
                    Block.class);
            c.setAccessible(true);
            return c.newInstance(x, z, 64, surface);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static NavGraph graph(NavNode... nodes) {
        try {
            NavGraph g = (NavGraph) UNSAFE.allocateInstance(NavGraph.class);
            Map<Long, NavNode> map = new HashMap<>();
            for (NavNode n : nodes) {
                map.put(NavNode.key(n.x, n.feetY, n.z), n);
            }
            Field f = NavGraph.class.getDeclaredField("nodes");
            f.setAccessible(true);
            f.set(g, map);
            return g;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static RouteRules raider() {
        try {
            Constructor<RouteRules> c = RouteRules.class.getDeclaredConstructor(boolean.class, boolean.class,
                    boolean.class, Set.class, it.unimi.dsi.fastutil.longs.LongSet.class);
            c.setAccessible(true);
            return c.newInstance(false, false, false, Set.of(), null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static PathRuleTable.Snapshot<Block> snap(Map<Block, PathRuleTable.Entry> villager,
            Map<Block, PathRuleTable.Entry> raider, int costScale, boolean anyPace) {
        return new PathRuleTable.Snapshot<>(new IdentityHashMap<>(villager), new IdentityHashMap<>(raider), costScale,
                anyPace);
    }

    private static final PathRuleTable.Entry NEVER = new PathRuleTable.Entry(true, 64, 64);
    private static final PathRuleTable.Entry CHEAP = new PathRuleTable.Entry(false, 32, 64);
    private static final PathRuleTable.Entry DEAR = new PathRuleTable.Entry(false, 256, 64);

    @AfterEach
    void clear() {
        NavBridge.publish(snap(Map.of(), Map.of(), 1, false));
        NavBridge.publishLinks(LinkTable.EMPTY, Map.of());
    }

    private static NavNode node(int x, int z, int surfaceY, Block surface) {
        try {
            Constructor<NavNode> c = NavNode.class.getDeclaredConstructor(int.class, int.class, int.class,
                    Block.class);
            c.setAccessible(true);
            return c.newInstance(x, z, surfaceY, surface);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A rewritten STEP site: Towntopia's {@code from.canStepTo(to)} runs, then the bridge gets its answer. */
    private static boolean step(NavNode from, NavNode to) {
        return NavBridge.afterStep(from, to, from.canStepTo(to), 0);
    }

    /** The STEP site in the route search, as {@link #step}. */
    private static boolean searchStep(NavNode from, NavNode to, NavNode goal, RouteRules rules) {
        return NavBridge.afterSearchStep(from, to, from.canStepTo(to), goal, rules, 0);
    }

    private static long key(NavNode n) {
        return NavNode.key(n.x, n.feetY, n.z);
    }

    private static void links(LinkTable.Link... links) {
        NavBridge.publishLinks(LinkTable.of(List.of(links)), Map.of());
    }

    /** A link pair is decided by the link, whatever Towntopia's rise test says (an elevator fails it). */
    @Test
    void linkStepIgnoresTheRise() {
        boolean produce = com.alex.test.village.WalkFeel.noProduceStep;
        com.alex.test.village.WalkFeel.noProduceStep = false;
        try {
            NavNode bottom = node(0, 0, 64, GRASS);
            NavNode top = node(0, 0, 72, GRASS);
            assertFalse(step(bottom, top));
            links(new LinkTable.Link(key(bottom), key(top), 8, false, true, 0));
            assertTrue(NavBridge.ACTIVE);
            assertTrue(step(bottom, top));
            assertTrue(searchStep(bottom, top, null, RouteRules.VILLAGER));
            // raiders never ride
            assertFalse(searchStep(bottom, top, null, raider()));
            // one way: the reverse is refused (Towntopia's own test would allow going down)
            assertTrue(top.canStepTo(bottom));
            assertFalse(step(top, bottom));
        } finally {
            com.alex.test.village.WalkFeel.noProduceStep = produce;
        }
    }

    /** A hop over a wall passes Towntopia's test; an unavailable or failed link must refuse it anyway. */
    @Test
    void unavailableOrFailedLinkIsRefused() {
        NavNode a = node(0, 0, GRASS);
        NavNode b = node(3, 0, GRASS);
        assertTrue(a.canStepTo(b));
        links(new LinkTable.Link(key(a), key(b), 3, true, false, 0));
        assertFalse(step(a, b));
        assertFalse(searchStep(b, a, null, RouteRules.VILLAGER));
        links(new LinkTable.Link(key(a), key(b), 3, true, true, System.currentTimeMillis() + 60_000));
        assertFalse(step(a, b));
        links(new LinkTable.Link(key(a), key(b), 3, true, true, 0));
        assertTrue(step(a, b));
        // other pairs keep Towntopia's answer
        assertTrue(step(a, node(1, 0, GRASS)));
    }

    /** A link onto a never tile is still refused (the search's goal excepted, as for a step). */
    @Test
    void linkOntoNeverIsRefused() {
        NavBridge.publish(snap(Map.of(WALL, NEVER), Map.of(), 1, false));
        NavNode a = node(0, 0, GRASS);
        NavNode wall = node(3, 0, WALL);
        links(new LinkTable.Link(key(a), key(wall), 3, true, true, 0));
        assertFalse(step(a, wall));
        assertFalse(searchStep(a, wall, null, RouteRules.VILLAGER));
        assertTrue(searchStep(a, wall, wall, RouteRules.VILLAGER));
        assertEquals(2, NavBridge.cellKind(0, a, wall));
    }

    /**
     * Transport switched off publishes every installed link refused. The bridge stays active and
     * refuses those pairs (a hop over a wall passes Towntopia's own test, so falling back to it would let it through).
     */
    @Test
    void transportOffRefusesInstalledPairs() {
        NavNode a = node(0, 0, GRASS);
        NavNode b = node(3, 0, GRASS);
        assertTrue(a.canStepTo(b));
        LinkInstaller.publishOff(List.of(new LinkTable.Link(key(a), key(b), 3, true, true, 0)));
        assertTrue(NavBridge.ACTIVE);
        assertFalse(step(a, b));
        assertFalse(step(b, a));
        assertFalse(searchStep(a, b, null, RouteRules.VILLAGER));
        assertEquals(2, NavBridge.cellKind(0, a, b));
        // other pairs keep Towntopia's answer
        assertTrue(step(a, node(1, 0, GRASS)));
    }

    /** A link step costs max(its cost, the x/z distance) x Towntopia's scale x the rule table's cost scale. */
    @Test
    void linkCostIsAtLeastTheDistance() {
        boolean climb = com.alex.test.village.WalkFeel.climbCost;
        try {
            NavNode a = node(0, 0, GRASS);
            NavNode b = node(3, 0, GRASS);
            NavNode up = node(0, 0, 72, GRASS);
            links(new LinkTable.Link(key(a), key(b), 1, true, true, 0), new LinkTable.Link(key(a), key(up), 9, true,
                    true, 0));
            com.alex.test.village.WalkFeel.climbCost = true;
            assertEquals(30, NavBridge.cost(10, a, b, RouteRules.VILLAGER));
            assertEquals(90, NavBridge.cost(10, a, up, RouteRules.VILLAGER));
            com.alex.test.village.WalkFeel.climbCost = false;
            assertEquals(3, NavBridge.cost(1, a, b, null));
            NavBridge.publish(snap(Map.of(GRASS, CHEAP), Map.of(), 2, false));
            assertEquals(6, NavBridge.cost(1, a, b, RouteRules.VILLAGER));
        } finally {
            com.alex.test.village.WalkFeel.climbCost = climb;
        }
    }

    /** In the reachability adapter a steppable link is 0 (Towntopia's -1 for an elevator), any other link pair 2. */
    @Test
    void cellKindFollowsLinks() {
        NavNode bottom = node(0, 0, 64, GRASS);
        NavNode top = node(0, 0, 72, GRASS);
        NavNode a = node(5, 0, GRASS);
        NavNode b = node(8, 0, GRASS);
        links(new LinkTable.Link(key(bottom), key(top), 8, false, true, 0), new LinkTable.Link(key(a), key(b), 3, true,
                false, 0));
        assertEquals(0, NavBridge.cellKind(-1, bottom, top));
        // the wrong way of a one-way link, and an unavailable link: kept by the island flood, not followed
        assertEquals(2, NavBridge.cellKind(0, top, bottom));
        assertEquals(2, NavBridge.cellKind(0, a, b));
        // other pairs keep Towntopia's answer
        assertEquals(-1, NavBridge.cellKind(-1, bottom, a));
        assertEquals(1, NavBridge.cellKind(1, a, node(6, 0, GRASS)));
    }

    /** POLL: with nothing registered the polled node comes back untouched. */
    @Test
    void polledIsTheNodeWhenInactive() {
        NavNode a = node(0, 0, GRASS);
        assertTrue(NavBridge.polled(a, null, null, null) == a);
        assertTrue(NavBridge.polled(null, null, null, null) == null);
    }

    @Test
    void publishSetsTheFlags() {
        assertFalse(NavBridge.ACTIVE);
        NavBridge.publish(snap(Map.of(ROAD, CHEAP), Map.of(), 2, false));
        assertTrue(NavBridge.ACTIVE);
        assertFalse(NavBridge.PACE_ACTIVE);
        NavBridge.publish(snap(Map.of(ROAD, new PathRuleTable.Entry(false, 32, 128)), Map.of(), 2, true));
        assertTrue(NavBridge.PACE_ACTIVE);
        NavBridge.publish(snap(Map.of(), Map.of(), 1, false));
        assertFalse(NavBridge.ACTIVE);
        assertFalse(NavBridge.PACE_ACTIVE);
    }

    @Test
    void nothingRegisteredIsTowntopiasAnswer() {
        NavNode a = node(0, 0, GRASS);
        NavNode b = node(1, 0, WALL);
        assertTrue(step(a, b));
        assertTrue(searchStep(a, b, null, RouteRules.VILLAGER));
        assertEquals(7, NavBridge.cost(7, a, b, RouteRules.VILLAGER));
        assertTrue(NavBridge.lineOfSight(true, graph(a, b), a, b));
        assertFalse(NavBridge.lineOfSight(false, graph(a, b), a, b));
        assertEquals(0.5, NavBridge.pace(0.5, null));
    }

    @Test
    void neverTileRefusesTheStepInto() {
        NavBridge.publish(snap(Map.of(WALL, NEVER), Map.of(), 1, false));
        NavNode a = node(0, 0, GRASS);
        NavNode wall = node(1, 0, WALL);
        assertFalse(step(a, wall));
        assertFalse(searchStep(a, wall, node(5, 5, GRASS), RouteRules.VILLAGER));
        // raiders read never from the villager map too
        assertFalse(searchStep(a, wall, null, raider()));
        // leaving a never tile (the start) is allowed: only the tile stepped onto is checked
        assertTrue(step(wall, a));
        assertTrue(searchStep(wall, a, null, RouteRules.VILLAGER));
    }

    @Test
    void neverGoalIsStillReached() {
        NavBridge.publish(snap(Map.of(WALL, NEVER), Map.of(), 1, false));
        NavNode a = node(0, 0, GRASS);
        NavNode wall = node(1, 0, WALL);
        assertTrue(searchStep(a, wall, wall, RouteRules.VILLAGER));
    }

    @Test
    void towntopiasRefusalStands() {
        NavBridge.publish(snap(Map.of(ROAD, CHEAP), Map.of(), 2, false));
        NavNode low = node(0, 0, GRASS);
        NavNode high;
        try {
            Constructor<NavNode> c = NavNode.class.getDeclaredConstructor(int.class, int.class, int.class,
                    Block.class);
            c.setAccessible(true);
            high = c.newInstance(1, 0, 70, ROAD);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        // the produce-top test would read Blocks (no bootstrap here); it isn't what this test is about
        boolean produce = com.alex.test.village.WalkFeel.noProduceStep;
        com.alex.test.village.WalkFeel.noProduceStep = false;
        try {
            assertFalse(step(low, high));
            assertFalse(searchStep(low, high, high, RouteRules.VILLAGER));
        } finally {
            com.alex.test.village.WalkFeel.noProduceStep = produce;
        }
    }

    @Test
    void costScalesByTheTileSteppedOnto() {
        NavBridge.publish(snap(Map.of(ROAD, CHEAP, WALL, DEAR), Map.of(WALL, DEAR), 2, false));
        NavNode a = node(0, 0, GRASS);
        NavNode road = node(1, 0, ROAD);
        NavNode dear = node(1, 0, WALL);
        NavNode grass = node(1, 0, GRASS);
        // costScale 2 applies to every edge, rule or not
        assertEquals(PathRuleTable.scaleCost(10, 64, 2), NavBridge.cost(10, a, grass, RouteRules.VILLAGER));
        assertEquals(PathRuleTable.scaleCost(10, 32, 2), NavBridge.cost(10, a, road, RouteRules.VILLAGER));
        // rules == null (price) is a villager
        assertEquals(PathRuleTable.scaleCost(10, 32, 2), NavBridge.cost(10, a, road, null));
        // raiders read the raider map: no road rule for them
        assertEquals(PathRuleTable.scaleCost(10, 64, 2), NavBridge.cost(10, a, road, raider()));
        assertEquals(PathRuleTable.scaleCost(10, 256, 2), NavBridge.cost(10, a, dear, raider()));
    }

    @Test
    void lineOfSightRefusesAShortcutOverANeverTile() {
        NavBridge.publish(snap(Map.of(WALL, NEVER), Map.of(), 1, false));
        NavNode from = node(0, 0, GRASS);
        NavNode mid = node(1, 0, WALL);
        NavNode mid2 = node(2, 0, GRASS);
        NavNode to = node(3, 0, GRASS);
        NavGraph g = graph(from, mid, mid2, to);
        assertFalse(NavBridge.lineOfSight(true, g, from, to));
        assertFalse(NavBridge.lineOfSight(false, g, from, to));
    }

    @Test
    void lineOfSightIgnoresTheEndsAndOtherRules() {
        NavBridge.publish(snap(Map.of(WALL, NEVER, ROAD, CHEAP), Map.of(), 2, false));
        // the ends were already allowed by the search (a never start or goal)
        NavNode from = node(0, 0, WALL);
        NavNode mid = node(1, 1, ROAD);
        NavNode to = node(2, 2, WALL);
        NavGraph g = graph(from, mid, to, node(1, 0, GRASS), node(0, 1, GRASS), node(2, 1, GRASS),
                node(1, 2, GRASS));
        assertTrue(NavBridge.lineOfSight(true, g, from, to));
    }

    /**
     * In the reachability adapter a step onto a never tile is kind 2, not -1: the island flood (kinds up to 2) keeps
     * the tile, while access (kinds up to 1, BuildingAccess) and reaches (kind 0) treat it as blocked. Kind 1 (fix
     * round 1) was wrong: Towntopia's access flood follows kind 1, so a building behind never tiles stayed reachable.
     */
    @Test
    void cellKindMarksANeverStepBlocked() {
        NavNode a = node(0, 0, GRASS);
        NavNode wall = node(1, 0, WALL);
        NavNode grass = node(1, 0, GRASS);
        // nothing registered: Towntopia's answer
        for (int k = -1; k <= 2; k++) {
            assertEquals(k, NavBridge.cellKind(k, a, wall));
        }
        NavBridge.publish(snap(Map.of(WALL, NEVER, ROAD, CHEAP), Map.of(), 2, false));
        assertEquals(2, NavBridge.cellKind(0, a, wall));
        assertEquals(2, NavBridge.cellKind(1, a, wall));
        // Towntopia's -1 (no step) and 2 (routable to non-routable) win
        assertEquals(-1, NavBridge.cellKind(-1, a, wall));
        assertEquals(2, NavBridge.cellKind(2, a, wall));
        // no never rule on the tile: Towntopia's answer
        assertEquals(0, NavBridge.cellKind(0, a, grass));
        assertEquals(0, NavBridge.cellKind(0, a, node(1, 0, ROAD)));
        // leaving a never tile: only the tile stepped onto counts
        assertEquals(0, NavBridge.cellKind(0, wall, a));
    }

    /** Speed64 / 64 from the villager map for Towntopia's NPCs, from the raider map for everyone else. */
    @Test
    void paceMultipliesByTheSurfaceRule() {
        PathRuleTable.Entry fast = new PathRuleTable.Entry(false, 43, 96);
        PathRuleTable.Entry raiderFast = new PathRuleTable.Entry(false, 32, 128);
        NavBridge.publish(snap(Map.of(ROAD, fast, WALL, NEVER), Map.of(ROAD, raiderFast), 1, true));
        assertEquals(0.75, NavBridge.paceOn(0.5, ROAD, true), 1e-9);
        assertEquals(1.0, NavBridge.paceOn(0.5, ROAD, false), 1e-9);
        // no rule (or one without a speed) under the feet: the speed stands
        assertEquals(0.5, NavBridge.paceOn(0.5, GRASS, true));
        assertEquals(0.5, NavBridge.paceOn(0.5, WALL, true));
        assertEquals(0.5, NavBridge.paceOn(0.5, WALL, false));
        assertEquals(0.5, NavBridge.paceOn(0.5, null, true));
    }

    /** No speed rule: pace is the speed (only PACE_ACTIVE is read); no mob is the speed too, without a warning. */
    @Test
    void paceIsTheSpeedWithoutASpeedRule() {
        NavBridge.publish(snap(Map.of(ROAD, CHEAP), Map.of(), 2, false));
        assertFalse(NavBridge.PACE_ACTIVE);
        assertEquals(0.5, NavBridge.pace(0.5, null));
        NavBridge.publish(snap(Map.of(ROAD, new PathRuleTable.Entry(false, 43, 96)), Map.of(), 1, true));
        assertTrue(NavBridge.PACE_ACTIVE);
        assertEquals(0.5, NavBridge.pace(0.5, null));
        // an explicit null guard, not a caught NullPointerException (which logged a warning)
        assertFalse(NavBridge.PACE_WARNED.get());
    }

    @Test
    void lineOfSightChecksTheCornersOfADiagonal() {
        NavBridge.publish(snap(Map.of(WALL, NEVER), Map.of(), 1, false));
        NavNode from = node(0, 0, GRASS);
        NavNode to = node(1, 1, GRASS);
        NavGraph g = graph(from, to, node(1, 0, WALL), node(0, 1, GRASS));
        assertFalse(NavBridge.lineOfSight(true, g, from, to));
    }
}
