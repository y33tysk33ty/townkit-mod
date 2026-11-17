package townkit.core.nav;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Which audited nav sites the bridge actually runs at, as Towntopia's classes load (the nav counterpart of
 * {@code RewrittenSites}). Filled by TownKit's mixin plugin; must not load Minecraft classes.
 */
public final class RewrittenNavSites {
    /** The running game's record (the mixin plugin writes it). */
    public static final RewrittenNavSites GLOBAL = new RewrittenNavSites();

    static final String HOOKS_OFF = "not rewritten (hooks disabled in townkit-hooks.properties)";
    static final String CLASS_OFF = "not rewritten (hooks disabled for this class)";
    static final String NOT_REWRITTEN = "not rewritten (class changed or its rewrite failed)";
    static final String NOT_LOADED = "class not loaded yet";

    private volatile boolean hooksOff;
    private final Set<String> skipped = ConcurrentHashMap.newKeySet();
    /** Loaded owners (relative to {@code com/alex/test/}) to the site ids rewritten in them (never mutated). */
    private final Map<String, BitSet> loaded = new ConcurrentHashMap<>();

    RewrittenNavSites() {
    }

    public void hooksSwitchedOff() {
        hooksOff = true;
    }

    public boolean hooksOff() {
        return hooksOff;
    }

    /** {@code owner}'s hooks are off (config), or its hook injection failed: none of its sites are rewritten. */
    public void classSkipped(String owner) {
        skipped.add(owner);
    }

    /**
     * {@code owner} loaded; {@code ids} are the site ids rewritten in it (empty when its rewrite failed). Merged into
     * what an earlier pass over the class recorded, never shrinking it (a second pass finds its sites already
     * rewritten and reports none).
     */
    public void classRewritten(String owner, BitSet ids) {
        loaded.merge(owner, (BitSet) ids.clone(), (was, now) -> {
            BitSet both = (BitSet) was.clone();
            both.or(now);
            return both;
        });
    }

    /** Whether {@code owner} has loaded and gone through the rewrite. */
    public boolean loaded(String owner) {
        return loaded.containsKey(owner);
    }

    /** Null when site {@code siteId} of {@code owner} runs through the bridge; else why not. */
    @Nullable
    public String notRewrittenReason(int siteId, String owner) {
        if (siteId < 0) {
            return "not an audited site";
        }
        if (hooksOff) {
            return HOOKS_OFF;
        }
        if (skipped.contains(owner)) {
            return CLASS_OFF;
        }
        BitSet ids = loaded.get(owner);
        if (ids == null) {
            return NOT_LOADED;
        }
        return ids.get(siteId) ? null : NOT_REWRITTEN;
    }

    /**
     * The sites transport links can't do without (owner, method name, kind): the route search's step test (else a
     * link pair falls back to Towntopia's {@code canStepTo}, which lets a drop or a flat hop through), the reachability
     * adapter (else a link region is an island and a local update deletes it) and the flood fill's poll (the far-stop
     * seeding).
     */
    private static final String[][] TRANSPORT_SITES = {
            {"village/AStarPathfinder", "search", "STEP"},
            {"village/NavCells$1", "kind", "CELL"},
            {"village/NavGraph", "floodFillInner", "POLL"},
    };

    /**
     * Why transport links must stay off, or null while nothing rules them out: every hook switched off, or one of the
     * sites they need ({@link #TRANSPORT_SITES}) belongs to a class switched off, or loaded without that site
     * rewritten (its rewrite failed, or a newer Towntopia changed the method). A class not loaded yet is not a
     * blocker (it is checked again later; see {@link #transportVerified}).
     */
    @Nullable
    public String transportBlocker(NavSites sites) {
        if (hooksOff) {
            return HOOKS_OFF;
        }
        for (String[] s : TRANSPORT_SITES) {
            int id = transportSite(sites, s);
            String why = id < 0 ? "not in nav-sites.tsv" : notRewrittenReason(id, s[0]);
            if (why != null && !why.equals(NOT_LOADED)) {
                return s[0] + "#" + s[1] + " " + s[2] + ": " + why;
            }
        }
        return null;
    }

    /** Whether every site transport needs ({@link #TRANSPORT_SITES}) has loaded and runs through the bridge. */
    public boolean transportVerified(NavSites sites) {
        for (String[] s : TRANSPORT_SITES) {
            int id = transportSite(sites, s);
            if (id < 0 || notRewrittenReason(id, s[0]) != null) {
                return false;
            }
        }
        return true;
    }

    private static int transportSite(NavSites sites, String[] s) {
        String prefix = s[0] + "#" + s[1] + "(";
        for (int i = 0; i < sites.size(); i++) {
            if (sites.owner(i).equals(s[0]) && sites.kind(i).name().equals(s[2]) && sites.key(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** {@code owner}'s REWRITE rows of the kinds rewritten today that are not in {@code rewritten}. */
    public static List<String> missing(NavSites sites, String owner, BitSet rewritten) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            if (sites.owner(i).equals(owner) && sites.rewrite(i) && NavSiteRewriter.HANDLED.contains(sites.kind(i))
                    && !rewritten.get(i)) {
                out.add(sites.key(i));
            }
        }
        return out;
    }
}
