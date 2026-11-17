package townkit.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.Subscription;
import townkit.core.building.SiteVerdicts.Type;
import townkit.core.building.SiteVerdicts.Verdict;
import townkit.core.building.StandInTable.Source;

class StandInTableTest {
    // sites: 0 SAFE, 1 NEEDS:inventory, 2 UNSAFE, 3 NEEDS:bed_block
    private static final SiteVerdicts VERDICTS = SiteVerdicts.of(
            List.of("a#safe", "b#inv", "c#unsafe", "d#bed"),
            List.of(new Verdict(Type.SAFE, null), new Verdict(Type.NEEDS, "inventory"),
                    new Verdict(Type.UNSAFE, null), new Verdict(Type.NEEDS, "bed_block")));

    private final Set<String> inventory = new HashSet<>();
    private final Set<String> beds = new HashSet<>();
    private StandInTable<String> table;

    private final BlockChecks<String> checks = (check, block) -> switch (check) {
        case "inventory" -> inventory.contains(block);
        case "bed_block" -> beds.contains(block);
        default -> throw new IllegalStateException(check);
    };

    @BeforeEach
    void setUp() {
        table = new StandInTable<>(VERDICTS, checks);
    }

    @Test
    void ownerOfNamesTheRuleOwner() {
        table.register("pack", "mod:x", "chest", Source.EXPLICIT);
        assertEquals("pack", table.ownerOf("mod:x"));
        assertEquals(null, table.ownerOf("mod:other"));
    }

    @Test
    void safeSiteCounts() {
        Subscription s = table.register("o", "mod:x", "chest", Source.EXPLICIT);
        assertFalse(s.isDisabled());
        assertTrue(table.countsAt("mod:x", 0));
        assertEquals("chest", table.vanillaFor("mod:x"));
        assertFalse(table.isEmpty());
    }

    @Test
    void unsafeSiteSkipped() {
        table.register("o", "mod:x", "chest", Source.EXPLICIT);
        assertFalse(table.countsAt("mod:x", 2));
    }

    @Test
    void needsCheckFollowsBlockChecks() {
        inventory.add("mod:inv");
        table.register("o", "mod:inv", "chest", Source.EXPLICIT);
        table.register("o", "mod:plain", "chest", Source.EXPLICIT);
        assertTrue(table.countsAt("mod:inv", 1));
        assertFalse(table.countsAt("mod:plain", 1));
        assertFalse(table.countsAt("mod:inv", 3));
        beds.add("mod:bed");
        table.register("o", "mod:bed", "bed", Source.EXPLICIT);
        assertTrue(table.countsAt("mod:bed", 3));
    }

    @Test
    void perSiteReport() {
        inventory.add("mod:x");
        table.register("o", "mod:x", "chest", Source.EXPLICIT);
        StandInTable.Report<String> r = table.report("mod:x");
        assertEquals("mod:x", r.modded());
        assertEquals("chest", r.vanilla());
        assertEquals(Source.EXPLICIT, r.source());
        assertEquals(List.of("a#safe", "b#inv"), r.countsAt());
        assertEquals(Set.of("c#unsafe", "d#bed"), r.skippedAt().keySet());
        for (String reason : r.skippedAt().values()) {
            assertFalse(reason.isBlank());
        }
        assertNull(table.report("mod:none"));
    }

    @Test
    void refusedWhenSafeNowhere() {
        SiteVerdicts onlyUnsafe = SiteVerdicts.of(List.of("u"), List.of(new Verdict(Type.UNSAFE, null)));
        StandInTable<String> t = new StandInTable<>(onlyUnsafe, checks);
        Subscription s = t.register("o", "mod:x", "chest", Source.EXPLICIT);
        assertTrue(s.isDisabled());
        assertTrue(t.isEmpty());
        assertNull(t.vanillaFor("mod:x"));
        s.remove();
    }

    @Test
    void explicitBeatsTag() {
        table.replaceTagEntries(Map.of("mod:x", "barrel"));
        assertEquals("barrel", table.vanillaFor("mod:x"));
        assertEquals(Source.COMMON_TAG, table.report("mod:x").source());
        Subscription s = table.register("o", "mod:x", "chest", Source.EXPLICIT);
        assertEquals("chest", table.vanillaFor("mod:x"));
        assertEquals(Source.EXPLICIT, table.report("mod:x").source());
        s.remove();
        assertEquals("barrel", table.vanillaFor("mod:x"));
    }

    @Test
    void conflictingExplicitRefused() {
        Subscription a = table.register("ownerA", "mod:x", "chest", Source.EXPLICIT);
        Subscription b = table.register("ownerB", "mod:x", "barrel", Source.EXPLICIT);
        assertFalse(a.isDisabled());
        assertTrue(b.isDisabled());
        assertEquals("chest", table.vanillaFor("mod:x"));
        Subscription same = table.register("ownerC", "mod:x", "chest", Source.EXPLICIT);
        assertFalse(same.isDisabled());
        a.remove();
        assertEquals("chest", table.vanillaFor("mod:x"));
        same.remove();
        assertNull(table.vanillaFor("mod:x"));
        assertTrue(table.isEmpty());
    }

    @Test
    void removeRestores() {
        Subscription s = table.register("o", "mod:x", "chest", Source.EXPLICIT);
        s.remove();
        s.remove();
        assertNull(table.vanillaFor("mod:x"));
        assertFalse(table.countsAt("mod:x", 0));
        assertTrue(table.isEmpty());
    }

    @Test
    void tagReloadReplacesOnlyTagEntries() {
        table.register("o", "mod:explicit", "chest", Source.EXPLICIT);
        table.replaceTagEntries(Map.of("mod:t1", "barrel"));
        assertEquals("barrel", table.vanillaFor("mod:t1"));
        table.replaceTagEntries(Map.of("mod:t2", "barrel"));
        assertNull(table.vanillaFor("mod:t1"));
        assertFalse(table.countsAt("mod:t1", 0));
        assertEquals("barrel", table.vanillaFor("mod:t2"));
        assertEquals("chest", table.vanillaFor("mod:explicit"));
        table.replaceTagEntries(Map.of());
        assertEquals("chest", table.vanillaFor("mod:explicit"));
        assertFalse(table.isEmpty());
    }

    @Test
    void tagEntriesRecomputeChecks() {
        table.replaceTagEntries(Map.of("mod:x", "barrel"));
        assertFalse(table.countsAt("mod:x", 1));
        inventory.add("mod:x");
        table.replaceTagEntries(Map.of("mod:x", "barrel"));
        assertTrue(table.countsAt("mod:x", 1));
    }

    @Test
    void unknownSiteIdNeverCounts() {
        table.register("o", "mod:x", "chest", Source.EXPLICIT);
        assertFalse(table.countsAt("mod:x", -1));
        assertFalse(table.countsAt("mod:x", 4));
        assertFalse(table.countsAt("mod:x", Integer.MAX_VALUE));
        assertFalse(table.countsAt("mod:other", 0));
    }

    @Test
    void sitesAboutOtherBlocksAreLeftOut() {
        // site a is only about chests, site d only about beds
        StandInTable<String> t = new StandInTable<>(VERDICTS, checks,
                (key, vanilla) -> switch (key) {
                    case "a#safe", "b#inv", "c#unsafe" -> vanilla.equals("chest");
                    default -> vanilla.equals("bed");
                });
        inventory.add("mod:x");
        t.register("o", "mod:x", "chest", Source.EXPLICIT);
        StandInTable.Report<String> r = t.report("mod:x");
        assertEquals(List.of("a#safe", "b#inv"), r.countsAt());
        assertEquals(Set.of("c#unsafe"), r.skippedAt().keySet());
        assertFalse(t.countsAt("mod:x", 3));
    }

    @Test
    void ruleWhoseOnlySitesFailIsRefused() {
        // a hammock as a bed: the only bed site needs bed_block, which a plain block fails
        StandInTable<String> t = new StandInTable<>(VERDICTS, checks, (key, vanilla) -> key.equals("d#bed"));
        Subscription s = t.register("o", "mod:hammock", "bed", Source.EXPLICIT);
        assertTrue(s.isDisabled());
        assertTrue(t.isEmpty());
    }

    @Test
    void recheckReevaluatesRulesAndTagEntries() {
        // rules registered before the real checks are installed computed every NEEDS check as false
        table.register("o", "mod:x", "chest", Source.EXPLICIT);
        table.replaceTagEntries(Map.of("mod:t", "barrel"));
        assertFalse(table.countsAt("mod:x", 1));
        assertFalse(table.countsAt("mod:t", 1));
        inventory.add("mod:x");
        inventory.add("mod:t");
        table.recheck();
        assertTrue(table.countsAt("mod:x", 1));
        assertTrue(table.countsAt("mod:t", 1));
        assertEquals(List.of("a#safe", "b#inv"), table.report("mod:x").countsAt());
    }

    @Test
    void recheckKeepsSubscriptionsWorking() {
        Subscription s = table.register("o", "mod:x", "chest", Source.EXPLICIT);
        table.recheck();
        s.remove();
        assertTrue(table.isEmpty());
    }

    /** A fake whose inventory probe can't decide until {@code probe} is set (like no server level yet). */
    private final Set<String> probeOnly = new HashSet<>();
    private boolean probe;
    private final BlockChecks<String> undecidedChecks = new BlockChecks<>() {
        @Override
        public boolean passes(String check, String block) {
            return check.equals("inventory") && probe && probeOnly.contains(block);
        }

        @Override
        public boolean decided(String check, String block) {
            return !check.equals("inventory") || probe;
        }
    };

    // only site 1 (NEEDS:inventory) is about chests
    private StandInTable<String> chestOnly(BlockChecks<String> c) {
        return new StandInTable<>(VERDICTS, c, (key, vanilla) -> key.equals("b#inv") == vanilla.equals("chest"));
    }

    @Test
    void undecidedRuleStaysPendingThenCounts() {
        probeOnly.add("mod:capchest");
        StandInTable<String> t = chestOnly(undecidedChecks);
        Subscription s = t.register("o", "mod:capchest", "chest", Source.EXPLICIT);
        assertFalse(s.isDisabled(), "kept pending, not refused");
        assertFalse(t.countsAt("mod:capchest", 1));
        assertTrue(t.isEmpty(), "pending rules aren't in the active snapshot");
        assertTrue(t.hasRegistrations());
        probe = true;
        t.recheck();
        assertTrue(t.countsAt("mod:capchest", 1));
        assertFalse(t.isEmpty());
        s.remove();
        assertTrue(t.isEmpty());
        assertFalse(t.hasRegistrations());
    }

    @Test
    void undecidedThenFailingRuleCountsNowhere() {
        StandInTable<String> t = chestOnly(undecidedChecks);
        Subscription s = t.register("o", "mod:plain", "chest", Source.EXPLICIT);
        assertFalse(s.isDisabled());
        probe = true;
        t.recheck();
        assertFalse(t.countsAt("mod:plain", 1));
        assertTrue(t.isEmpty());
        assertNull(t.report("mod:plain"));
        s.remove();
    }

    @Test
    void decidedFailureIsStillRefused() {
        probe = true;
        StandInTable<String> t = chestOnly(undecidedChecks);
        assertTrue(t.register("o", "mod:plain", "chest", Source.EXPLICIT).isDisabled());
        assertFalse(t.hasRegistrations());
    }

    @Test
    void undecidedTagEntryCountsAfterRecheck() {
        probeOnly.add("mod:tagged");
        StandInTable<String> t = chestOnly(undecidedChecks);
        t.replaceTagEntries(Map.of("mod:tagged", "chest"));
        assertTrue(t.isEmpty());
        assertTrue(t.hasRegistrations());
        probe = true;
        t.recheck();
        assertTrue(t.countsAt("mod:tagged", 1));
    }

    @Test
    void emptyTableHasNoRegistrations() {
        assertFalse(table.hasRegistrations());
    }

    @Test
    void unknownCheckNameNeverPasses() {
        SiteVerdicts v = SiteVerdicts.of(List.of("s", "weird"),
                List.of(new Verdict(Type.SAFE, null), new Verdict(Type.NEEDS, "furnace_entity")));
        StandInTable<String> t = new StandInTable<>(v, (check, block) -> true);
        t.register("o", "mod:x", "furnace", Source.EXPLICIT);
        assertTrue(t.countsAt("mod:x", 0));
        assertFalse(t.countsAt("mod:x", 1));
        assertTrue(t.report("mod:x").skippedAt().get("weird").contains("furnace_entity"));
    }

    /** The bridge's fast-path flag follows the published snapshot exactly. */
    @Test
    void activeFlagTracksRegistrationRemovalAndTagEntries() {
        List<Boolean> flags = new java.util.ArrayList<>();
        StandInTable<String> t = new StandInTable<>(VERDICTS, checks, (k, v) -> true, StandInTable.SiteStatus.ALL,
                flags::add);
        Subscription a = t.register("o", "mod:a", "chest", Source.EXPLICIT);
        assertEquals(List.of(true), flags);
        Subscription b = t.register("o", "mod:b", "chest", Source.EXPLICIT);
        a.remove();
        assertEquals(true, flags.get(flags.size() - 1), "one rule left");
        b.remove();
        assertEquals(false, flags.get(flags.size() - 1), "last rule removed");
        t.replaceTagEntries(Map.of("mod:t", "barrel"));
        assertEquals(true, flags.get(flags.size() - 1), "tag entry");
        t.replaceTagEntries(Map.of());
        assertEquals(false, flags.get(flags.size() - 1), "tag entries cleared");
        // a rule that counts nowhere yet (pending) is not active
        StandInTable<String> p = new StandInTable<>(VERDICTS, new BlockChecks<>() {
            @Override
            public boolean passes(String check, String block) {
                return false;
            }

            @Override
            public boolean decided(String check, String block) {
                return false;
            }
        }, (k, v) -> k.equals("b#inv"), StandInTable.SiteStatus.ALL, flags::add);
        flags.clear();
        p.register("o", "mod:p", "chest", Source.EXPLICIT);
        assertEquals(List.of(false), flags);
        // a recheck that leaves the rule counting keeps the flag on
        flags.clear();
        t.register("o", "mod:c", "chest", Source.EXPLICIT);
        t.recheck();
        assertEquals(List.of(true, true), flags);
    }

    /** A site the bridge never runs at is reported as skipped, not counted. */
    @Test
    void reportSkipsSitesThatWereNotRewritten() {
        inventory.add("mod:x");
        Map<Integer, String> notRewritten = new java.util.HashMap<>();
        StandInTable<String> t = new StandInTable<>(VERDICTS, checks, (k, v) -> true,
                (id, key) -> notRewritten.get(id), b -> { });
        t.register("o", "mod:x", "chest", Source.EXPLICIT);
        assertEquals(List.of("a#safe", "b#inv"), t.report("mod:x").countsAt());
        notRewritten.put(1, "not rewritten (class changed)");
        StandInTable.Report<String> r = t.report("mod:x");
        assertEquals(List.of("a#safe"), r.countsAt());
        assertEquals("not rewritten (class changed)", r.skippedAt().get("b#inv"));
        assertEquals("unsafe site", r.skippedAt().get("c#unsafe"), "other reasons kept");
        // the bridge's own answer is unchanged: it is never asked at a site that wasn't rewritten
        assertTrue(t.countsAt("mod:x", 1));
        notRewritten.clear();
        assertEquals(List.of("a#safe", "b#inv"), t.report("mod:x").countsAt());
    }

    /** Upkeep: a rule whose every counted site is in a class that is loaded but not rewritten counts nowhere ever. */
    @Test
    void ruleWithOnlyNotRewrittenSitesIsRefused() {
        inventory.add("mod:x");
        Map<Integer, String> notRewritten = new java.util.HashMap<>();
        notRewritten.put(0, "not rewritten (hooks disabled for this class)");
        notRewritten.put(1, "not rewritten (hooks disabled for this class)");
        StandInTable<String> t = new StandInTable<>(VERDICTS, checks, (k, v) -> true,
                (id, key) -> notRewritten.get(id), b -> { });
        Subscription s = t.register("o", "mod:x", "chest", Source.EXPLICIT);
        assertTrue(s.isDisabled());
        assertTrue(t.isEmpty());
        assertFalse(t.hasRegistrations());
        assertNull(t.report("mod:x"));
        assertNull(t.ownerOf("mod:x"));
    }

    @Test
    void ruleWithOneLiveSiteIsKeptAndSitesInUnloadedClassesCount() {
        inventory.add("mod:x");
        Map<Integer, String> notRewritten = new java.util.HashMap<>();
        notRewritten.put(0, "not rewritten (class changed or its rewrite failed)"); // site 1 is live (or not loaded yet)
        StandInTable<String> t = new StandInTable<>(VERDICTS, checks, (k, v) -> true,
                (id, key) -> notRewritten.get(id), b -> { });
        Subscription s = t.register("o", "mod:x", "chest", Source.EXPLICIT);
        assertFalse(s.isDisabled());
        assertTrue(t.countsAt("mod:x", 1));
    }

    @Test
    void tagEntryWithOnlyNotRewrittenSitesIsNotPublished() {
        StandInTable<String> t = new StandInTable<>(VERDICTS, checks, (k, v) -> true,
                (id, key) -> "not rewritten (hooks disabled in townkit-hooks.properties)", b -> { });
        t.replaceTagEntries(Map.of("mod:t", "barrel"));
        assertTrue(t.isEmpty());
        assertNull(t.vanillaFor("mod:t"));
    }
}
