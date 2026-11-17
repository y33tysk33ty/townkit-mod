package townkit.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import townkit.api.Subscription;
import townkit.api.nav.PathRule;
import townkit.core.nav.PathRuleTable.Entry;
import townkit.core.nav.PathRuleTable.Snapshot;

class PathRuleTableTest {
    private static final String A = "a";
    private static final String B = "b";

    @Test
    void combineNeverWins() {
        PathRuleTable<String> t = new PathRuleTable<>();
        t.add("one", A, PathRule.cost(0.5));
        t.add("two", A, PathRule.never());
        assertTrue(t.snapshot().villager().get(A).never());
    }

    @Test
    void lowestCostHighestSpeed() {
        PathRuleTable<String> t = new PathRuleTable<>();
        t.add("one", A, PathRule.cost(2));
        t.add("two", A, PathRule.cost(0.8));
        t.add("one", B, PathRule.speed(1.5));
        t.add("two", B, PathRule.speed(1.2));
        assertEquals(51, t.snapshot().villager().get(A).cost64());
        assertEquals(64, t.snapshot().villager().get(A).speed64());
        assertEquals(96, t.snapshot().villager().get(B).speed64());
    }

    @Test
    void speedMakesCheaper() {
        PathRuleTable<String> t = new PathRuleTable<>();
        t.add("one", A, PathRule.speed(2));
        Snapshot<String> s = t.snapshot();
        assertEquals(32, s.villager().get(A).cost64());
        assertEquals(2, s.costScale());
        assertTrue(s.anyPace());
    }

    @Test
    void floorAndCeiling() {
        PathRuleTable<String> t = new PathRuleTable<>();
        t.add("one", A, PathRule.cost(0.1));
        t.add("one", B, PathRule.cost(1000));
        assertEquals(32, t.snapshot().villager().get(A).cost64());
        assertEquals(4096, t.snapshot().villager().get(B).cost64());
    }

    @Test
    void speedClampedToCeiling() {
        PathRuleTable<String> t = new PathRuleTable<>();
        t.add("one", A, PathRule.speed(1e10));
        Snapshot<String> s = t.snapshot();
        assertEquals(4096, s.villager().get(A).speed64());
        assertEquals(32, s.villager().get(A).cost64());
        assertTrue(s.anyPace());
    }

    @Test
    void invalidMultipliersRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathRule.cost(0));
        assertThrows(IllegalArgumentException.class, () -> PathRule.speed(-1));
        assertThrows(IllegalArgumentException.class, () -> PathRule.cost(1).withSpeed(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> PathRule.cost(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> PathRule.speed(1).withCost(Double.NaN));
    }

    @Test
    void scaleCostAdmissible() {
        for (int edge = 1; edge <= 30; edge++) {
            for (int cost64 = 32; cost64 <= 4096; cost64++) {
                for (int scale = 1; scale <= 2; scale++) {
                    if (cost64 * scale >= 64) {
                        assertTrue(PathRuleTable.scaleCost(edge, cost64, scale) >= edge,
                                edge + " " + cost64 + " " + scale);
                    }
                }
            }
        }
        assertEquals(10, PathRuleTable.scaleCost(10, 64, 1));
        assertEquals(20, PathRuleTable.scaleCost(10, 64, 2));
    }

    @Test
    void raiderMapOnlyIncludeRaiders() {
        PathRuleTable<String> t = new PathRuleTable<>();
        t.add("one", A, PathRule.cost(2));
        t.add("one", B, PathRule.cost(3).includeRaiders());
        t.add("one", "c", PathRule.never().includeRaiders());
        Snapshot<String> s = t.snapshot();
        assertNull(s.raider().get(A));
        assertEquals(192, s.raider().get(B).cost64());
        assertNull(s.raider().get("c"));
        assertTrue(s.villager().get("c").never());
    }

    @Test
    void removeRestoresEmpty() {
        AtomicReference<Snapshot<String>> seen = new AtomicReference<>();
        PathRuleTable<String> t = new PathRuleTable<>(seen::set);
        Subscription s1 = t.add("one", A, PathRule.speed(2));
        Subscription s2 = t.add("two", B, PathRule.cost(3));
        assertEquals(2, t.snapshot().costScale());
        s1.remove();
        assertEquals(1, t.snapshot().costScale());
        assertFalse(t.snapshot().anyPace());
        s2.remove();
        s2.remove();
        Snapshot<String> s = t.snapshot();
        assertTrue(s.villager().isEmpty());
        assertTrue(s.raider().isEmpty());
        assertEquals(1, s.costScale());
        assertSame(s, seen.get());
    }

    private static void assertSame(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertSame(a, b);
    }

    @Test
    void concurrentReadersSeeWholeSnapshots() throws Exception {
        PathRuleTable<String> t = new PathRuleTable<>();
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread r = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        Snapshot<String> s = t.snapshot();
                        boolean low = false;
                        for (Entry e : s.villager().values()) {
                            low |= e.cost64() < 64;
                        }
                        for (Entry e : s.raider().values()) {
                            low |= e.cost64() < 64;
                        }
                        assertEquals(low ? 2 : 1, s.costScale());
                        boolean paced = s.villager().values().stream().anyMatch(e -> e.speed64() != 64)
                                || s.raider().values().stream().anyMatch(e -> e.speed64() != 64);
                        assertEquals(paced, s.anyPace());
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            readers.add(r);
            r.start();
        }
        for (int i = 0; i < 1000; i++) {
            Subscription a = t.add("w", A, PathRule.speed(2));
            Subscription b = t.add("w", B, PathRule.cost(0.5).includeRaiders());
            a.remove();
            b.remove();
        }
        stop.set(true);
        for (Thread r : readers) {
            r.join(10_000);
        }
        assertTrue(errors.isEmpty(), () -> String.valueOf(errors));
    }

    @Test
    void combinedFoldsTheRulesOnOneBlock() {
        PathRuleTable<String> t = new PathRuleTable<>();
        assertTrue(t.combined(A).isEmpty());
        t.add("one", A, PathRule.cost(2));
        Subscription s = t.add("two", A, PathRule.cost(0.8).withSpeed(1.5));
        t.add("one", A, PathRule.speed(1.2).includeRaiders());
        t.add("one", B, PathRule.never());
        PathRule a = t.combined(A).orElseThrow();
        assertFalse(a.isNever());
        assertEquals(0.8, a.cost().getAsDouble());
        assertEquals(1.5, a.speed().getAsDouble());
        assertFalse(a.appliesToRaiders());
        PathRule b = t.combined(B).orElseThrow();
        assertTrue(b.isNever());
        assertTrue(b.cost().isEmpty() && b.speed().isEmpty());
        s.remove();
        assertEquals(1.2, t.combined(A).orElseThrow().speed().getAsDouble());
        assertTrue(t.combined("c").isEmpty());
    }
}
