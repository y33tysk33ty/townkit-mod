package townkit.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewrittenNavSitesTest {
    @Test
    void reasonsFollowWhatWasRewritten() {
        NavSites sites = NavSites.load();
        String owner = "village/building/BuildingAccess";
        int id = -1;
        for (int i = 0; i < sites.size(); i++) {
            if (sites.owner(i).equals(owner)) {
                id = i;
                break;
            }
        }
        assertTrue(id >= 0);
        RewrittenNavSites r = new RewrittenNavSites();
        assertEquals(RewrittenNavSites.NOT_LOADED, r.notRewrittenReason(id, owner));
        BitSet ids = new BitSet();
        r.classRewritten(owner, ids);
        assertEquals(RewrittenNavSites.NOT_REWRITTEN, r.notRewrittenReason(id, owner));
        assertEquals(2, RewrittenNavSites.missing(sites, owner, ids).size());
        ids.set(id);
        r.classRewritten(owner, ids);
        assertNull(r.notRewrittenReason(id, owner));
        assertEquals(1, RewrittenNavSites.missing(sites, owner, ids).size());
        r.classSkipped(owner);
        assertEquals(RewrittenNavSites.CLASS_OFF, r.notRewrittenReason(id, owner));
        r.hooksSwitchedOff();
        assertEquals(RewrittenNavSites.HOOKS_OFF, r.notRewrittenReason(id, owner));
    }

    /**
     * Transport needs AStarPathfinder#search STEP, NavCells$1 CELL and
     * NavGraph#floodFillInner POLL rewritten. Not loaded yet is not a blocker; all three rewritten is verified; a class
     * switched off, a site whose rewrite failed (or whose count changed), or every hook off blocks transport.
     */
    @Test
    void transportNeedsStepCellAndPoll() {
        NavSites sites = NavSites.load();
        String astar = "village/AStarPathfinder";
        String cells = "village/NavCells$1";
        String graph = "village/NavGraph";
        int step = idOf(sites, astar, "search(", NavSite.Kind.STEP);
        int cell = idOf(sites, cells, "kind(", NavSite.Kind.CELL);
        int poll = idOf(sites, graph, "floodFillInner(", NavSite.Kind.POLL);

        RewrittenNavSites r = new RewrittenNavSites();
        assertNull(r.transportBlocker(sites), "nothing loaded yet: not blocked");
        assertFalse(r.transportVerified(sites));
        r.classRewritten(astar, bits(step));
        r.classRewritten(cells, bits(cell));
        assertNull(r.transportBlocker(sites));
        assertFalse(r.transportVerified(sites), "NavGraph not loaded yet");
        r.classRewritten(graph, bits(poll));
        assertNull(r.transportBlocker(sites));
        assertTrue(r.transportVerified(sites));

        // AStarPathfinder loaded, but its search STEP stayed as shipped (a newer Towntopia changed the method)
        RewrittenNavSites changed = new RewrittenNavSites();
        changed.classRewritten(astar, bits(idOf(sites, astar, "search(", NavSite.Kind.COST)));
        String why = changed.transportBlocker(sites);
        assertNotNull(why);
        assertTrue(why.contains("AStarPathfinder#search") && why.contains(RewrittenNavSites.NOT_REWRITTEN), why);

        RewrittenNavSites skipped = new RewrittenNavSites();
        skipped.classSkipped(cells);
        why = skipped.transportBlocker(sites);
        assertNotNull(why);
        assertTrue(why.contains("NavCells$1") && why.contains(RewrittenNavSites.CLASS_OFF), why);

        RewrittenNavSites off = new RewrittenNavSites();
        off.hooksSwitchedOff();
        why = off.transportBlocker(sites);
        assertNotNull(why);
        assertTrue(why.contains(RewrittenNavSites.HOOKS_OFF), why);
        assertFalse(off.transportVerified(sites));
    }

    private static int idOf(NavSites sites, String owner, String methodStart, NavSite.Kind kind) {
        for (int i = 0; i < sites.size(); i++) {
            if (sites.owner(i).equals(owner) && sites.kind(i) == kind
                    && sites.key(i).startsWith(owner + "#" + methodStart)) {
                return i;
            }
        }
        throw new AssertionError("no row " + owner + "#" + methodStart + " " + kind);
    }

    private static BitSet bits(int... ids) {
        BitSet b = new BitSet();
        for (int id : ids) {
            b.set(id);
        }
        return b;
    }

    /** A second rewrite pass over a class (empty) must not drop the sites the first pass rewrote. */
    @Test
    void secondPassDoesNotShrinkSites() {
        RewrittenNavSites r = new RewrittenNavSites();
        r.classRewritten("village.AStarPathfinder", bits(3));
        r.classRewritten("village.AStarPathfinder", new BitSet());
        assertNull(r.notRewrittenReason(3, "village.AStarPathfinder"));
    }

    /** Every kind is rewritten: NavGraph's POLL row is reported when it wasn't rewritten. */
    @Test
    void pollIsReportedMissing() {
        List<String> missing = RewrittenNavSites.missing(NavSites.load(), "village/NavGraph", new BitSet());
        assertEquals(1, missing.size(), missing.toString());
        assertTrue(missing.get(0).endsWith("#POLL"), missing.toString());
    }
}
