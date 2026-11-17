package townkit.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The immutable link snapshot the route search reads: pair lookups, direction, availability, failure time. */
class LinkTableTest {
    private static final long A = 1001L;
    private static final long B = 2002L;
    private static final long C = 3003L;
    private static final long D = -4004L;
    private static final long NOW = 1_000_000L;

    private static LinkTable table(LinkTable.Link... links) {
        return LinkTable.of(List.of(links));
    }

    @Test
    void lookupBothWays() {
        LinkTable t = table(new LinkTable.Link(A, B, 7, true, true, 0));
        int ab = t.indexOf(A, B);
        int ba = t.indexOf(B, A);
        assertTrue(ab >= 0);
        assertTrue(ba >= 0);
        assertTrue(t.steppable(ab, NOW));
        assertTrue(t.steppable(ba, NOW));
        assertEquals(7, t.cost(ab));
        assertEquals(7, t.cost(ba));
        assertEquals(-1, t.indexOf(A, C));
        assertEquals(-1, t.indexOf(C, A));
    }

    /**
     * The table published when transport switches off has every installed pair, both ways, and
     * none is steppable (so the bridge refuses them instead of falling back to Towntopia's own step test).
     */
    @Test
    void refusingKeepsEveryPairRefused() {
        LinkTable t = LinkTable.refusing(List.of(new LinkTable.Link(A, B, 7, true, true, 0),
                new LinkTable.Link(C, D, 2, false, true, 0)));
        assertFalse(t.isEmpty());
        for (long[] p : new long[][] {{A, B}, {B, A}, {C, D}, {D, C}}) {
            int row = t.indexOf(p[0], p[1]);
            assertTrue(row >= 0, p[0] + " -> " + p[1]);
            assertFalse(t.steppable(row, NOW), p[0] + " -> " + p[1]);
        }
        assertTrue(LinkTable.refusing(List.of()).isEmpty());
    }

    @Test
    void oneWayOnlyForward() {
        LinkTable t = table(new LinkTable.Link(A, B, 3, false, true, 0), new LinkTable.Link(C, D, 4, true, true, 0));
        int ab = t.indexOf(A, B);
        int ba = t.indexOf(B, A);
        assertTrue(t.steppable(ab, NOW));
        // the reverse pair is still a link pair (so Towntopia's own step test can't let it through), never steppable
        assertTrue(ba >= 0);
        assertFalse(t.steppable(ba, NOW));
        assertTrue(t.forward(ab));
        assertFalse(t.forward(ba));
        assertTrue(t.forward(t.indexOf(D, C)));
    }

    @Test
    void failedUntilHonoured() {
        LinkTable t = table(new LinkTable.Link(A, B, 3, true, true, NOW + 10_000));
        int ab = t.indexOf(A, B);
        assertFalse(t.steppable(ab, NOW));
        assertFalse(t.steppable(t.indexOf(B, A), NOW + 9_999));
        assertTrue(t.steppable(ab, NOW + 10_000));
        assertTrue(t.steppable(ab, NOW + 20_000));
    }

    @Test
    void unavailableNotSteppable() {
        LinkTable t = table(new LinkTable.Link(A, B, 3, true, false, 0));
        assertTrue(t.indexOf(A, B) >= 0);
        assertFalse(t.steppable(t.indexOf(A, B), NOW));
        assertFalse(t.steppable(t.indexOf(B, A), NOW));
    }

    @Test
    void emptyTableMeansInactive() {
        assertTrue(LinkTable.EMPTY.isEmpty());
        assertTrue(table().isEmpty());
        assertEquals(-1, LinkTable.EMPTY.indexOf(A, B));
        assertEquals(-1, LinkTable.EMPTY.firstFrom(A));
        assertFalse(table(new LinkTable.Link(A, B, 1, false, false, 0)).isEmpty());
        // nothing registered and no link: the bridge is off
        NavBridge.publishLinks(LinkTable.EMPTY, java.util.Map.of());
        NavBridge.publish(new PathRuleTable.Snapshot<>(new java.util.IdentityHashMap<>(), new java.util.IdentityHashMap<>(), 1,
                false));
        assertFalse(NavBridge.ACTIVE);
        NavBridge.publishLinks(table(new LinkTable.Link(A, B, 1, true, true, 0)), java.util.Map.of());
        assertTrue(NavBridge.ACTIVE);
        NavBridge.publishLinks(LinkTable.EMPTY, java.util.Map.of());
        assertFalse(NavBridge.ACTIVE);
    }

    @Test
    void firstFromWalksEveryLinkOfAStart() {
        LinkTable t = table(new LinkTable.Link(A, B, 3, true, true, 0), new LinkTable.Link(A, C, 3, false, true, 0),
                new LinkTable.Link(D, A, 3, false, true, 0));
        List<Long> fromA = new ArrayList<>();
        for (int i = t.firstFrom(A); i >= 0 && i < t.size() && t.from(i) == A; i++) {
            fromA.add(t.to(i));
        }
        // A -> B, A -> C and the reverse of D -> A (one-way: not forward)
        assertEquals(3, fromA.size(), fromA.toString());
        assertTrue(fromA.containsAll(List.of(B, C, D)));
        assertFalse(t.forward(t.indexOf(A, D)));
        assertEquals(-1, t.firstFrom(9999L));
    }

    /**
     * The table is global (node keys carry no dimension): the same pair can come twice, e.g. links on the same
     * coordinates in two dimensions. The stricter row wins: not steppable first, then the later failure, then the
     * higher cost.
     */
    @Test
    void duplicatePairKeepsTheStricterRow() {
        LinkTable t = table(new LinkTable.Link(A, B, 9, true, false, 0), new LinkTable.Link(A, B, 5, true, true, 0),
                new LinkTable.Link(A, B, 4, true, true, 0));
        int ab = t.indexOf(A, B);
        assertFalse(t.steppable(ab, NOW));
        assertEquals(9, t.cost(ab));
        assertEquals(2, t.size());
        LinkTable both = table(new LinkTable.Link(A, B, 4, true, true, 0), new LinkTable.Link(A, B, 6, true, true, 0));
        assertTrue(both.steppable(both.indexOf(A, B), NOW));
        assertEquals(6, both.cost(both.indexOf(A, B)));
        LinkTable failed = table(new LinkTable.Link(A, B, 8, true, true, 0), new LinkTable.Link(A, B, 3, true, true,
                NOW + 5_000));
        assertFalse(failed.steppable(failed.indexOf(A, B), NOW));
        // a one-way link's reverse row (never steppable) beats a both-ways link on the same pair
        LinkTable oneWay = table(new LinkTable.Link(A, B, 3, false, true, 0), new LinkTable.Link(A, B, 3, true, true, 0));
        assertFalse(oneWay.steppable(oneWay.indexOf(B, A), NOW));
        assertTrue(oneWay.steppable(oneWay.indexOf(A, B), NOW));
    }

    @Test
    void noAllocationOnLookup() {
        List<LinkTable.Link> links = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            links.add(new LinkTable.Link(i * 7L, i * 7L + 3, 3 + i % 5, i % 2 == 0, i % 3 != 0, i % 4 == 0 ? NOW + 5 : 0));
        }
        LinkTable t = LinkTable.of(links);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long sink = 0;
        // warm up (JIT, class init) before measuring
        for (int r = 0; r < 3; r++) {
            sink += lookups(t);
        }
        long before = mx.getThreadAllocatedBytes(tid);
        sink += lookups(t);
        long after = mx.getThreadAllocatedBytes(tid);
        assertTrue(sink != 42);
        assertEquals(0, after - before, "bytes allocated by 1e5 lookups");
    }

    private static long lookups(LinkTable t) {
        long sum = 0;
        for (int i = 0; i < 100_000; i++) {
            long from = (i % 250) * 7L;
            int idx = t.indexOf(from, from + 3);
            if (idx >= 0 && t.steppable(idx, NOW)) {
                sum += t.cost(idx);
            }
            sum += t.firstFrom(from);
        }
        return sum;
    }
}
