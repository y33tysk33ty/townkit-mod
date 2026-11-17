package townkit.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which audited sites the bridge actually runs at. */
class RewrittenSitesTest {
    private static final String A = "village/building/A";
    private static final String B = "village/building/B";

    private static BitSet ids(int... ids) {
        BitSet b = new BitSet();
        for (int i : ids) {
            b.set(i);
        }
        return b;
    }

    @Test
    void classNotLoadedYetCounts() {
        RewrittenSites r = new RewrittenSites();
        assertNull(r.notCountingReason(0, A + "#m()V#BLOCK_IS#CHEST"));
        assertFalse(r.hooksOff());
    }

    @Test
    void loadedClassCountsOnlyWhereRewritten() {
        RewrittenSites r = new RewrittenSites();
        r.classRewritten(A, ids(0, 2));
        assertNull(r.notCountingReason(0, A + "#m()V#BLOCK_IS#CHEST"));
        assertNull(r.notCountingReason(2, A + "#n()V#BLOCK_IS#CHEST"));
        String why = r.notCountingReason(1, A + "#m()V#BLOCK_IS#BARREL");
        assertTrue(why != null && why.startsWith("not rewritten"), why);
        // another class, not loaded yet, still counts
        assertNull(r.notCountingReason(3, B + "#m()V#BLOCK_IS#CHEST"));
    }

    @Test
    void failedRewriteCountsNowhereInThatClass() {
        RewrittenSites r = new RewrittenSites();
        r.classRewritten(A, new BitSet());
        assertTrue(r.notCountingReason(0, A + "#m()V#BLOCK_IS#CHEST").startsWith("not rewritten"));
    }

    @Test
    void classWithHooksSwitchedOffNeverCounts() {
        RewrittenSites r = new RewrittenSites();
        r.classSkipped(A);
        assertTrue(r.notCountingReason(0, A + "#m()V#BLOCK_IS#CHEST").contains("hooks disabled"));
        assertNull(r.notCountingReason(1, B + "#m()V#BLOCK_IS#CHEST"));
    }

    @Test
    void hooksOffGloballyNeverCounts() {
        RewrittenSites r = new RewrittenSites();
        r.hooksSwitchedOff();
        assertTrue(r.hooksOff());
        assertTrue(r.notCountingReason(0, B + "#m()V#BLOCK_IS#CHEST").contains("hooks disabled"));
    }

    @Test
    void unknownSiteIdNeverCounts() {
        RewrittenSites r = new RewrittenSites();
        assertTrue(r.notCountingReason(-1, A + "#m()V#BLOCK_IS#CHEST") != null);
    }

    @Test
    void missingUsableListsRowsNotRewritten() throws Exception {
        SiteVerdicts v = SiteVerdicts.parse(new StringReader("# scope " + A + "\n"
                + A + "\tm()V\tBLOCK_IS\tCHEST\tSAFE\t\n"
                + A + "\tm()V\tBLOCK_IS\tBARREL\tNEEDS:inventory\t\n"
                + A + "\tm()V\tBLOCK_IS\tSTONE\tUNSAFE\tx\n"
                + A + "\tm()V\tBLOCK_EQUALS\tDIRT\tAUDIT_ONLY\tx\n"
                + B + "\tm()V\tBLOCK_IS\tCHEST\tSAFE\t\n"));
        assertEquals(List.of(A + "#m()V#BLOCK_IS#BARREL"), RewrittenSites.missingUsable(v, A, ids(0)));
        assertEquals(List.of(), RewrittenSites.missingUsable(v, A, ids(0, 1)));
        assertEquals(List.of(B + "#m()V#BLOCK_IS#CHEST"), RewrittenSites.missingUsable(v, B, ids(0, 1)));
    }
}
