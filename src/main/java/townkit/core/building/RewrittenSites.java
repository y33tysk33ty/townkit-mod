package townkit.core.building;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Which audited check sites the stand-in bridge actually runs at, as Towntopia's classes load. Reports and
 * {@code /townkit building} use it so they never claim a block counts where the check was left as Towntopia ships it.
 *
 * <p>Per scoped owner: not loaded yet (counts: it is rewritten when it loads, if hooks are on for it), loaded and
 * rewritten at some site ids (counts only there), or left unhooked by {@code townkit-hooks.properties} (counts
 * nowhere). Filled by TownKit's mixin plugin; must not load Minecraft classes.
 */
public final class RewrittenSites {
    /** The running game's record (the mixin plugin writes it, reports read it). */
    public static final RewrittenSites GLOBAL = new RewrittenSites();

    static final String HOOKS_OFF = "not rewritten (hooks disabled in townkit-hooks.properties)";
    static final String CLASS_OFF = "not rewritten (hooks disabled for this class)";
    static final String NOT_REWRITTEN = "not rewritten (class changed or its rewrite failed)";

    private volatile boolean hooksOff;
    private final Set<String> skipped = ConcurrentHashMap.newKeySet();
    /** Loaded scoped owners (relative to {@code com/alex/test/}) to the site ids rewritten in them (never mutated). */
    private final Map<String, BitSet> loaded = new ConcurrentHashMap<>();

    RewrittenSites() {
    }

    /** {@code enabled=false} (or {@code -Dtownkit.hooks.enabled=false}): no class is hooked or rewritten. */
    public void hooksSwitchedOff() {
        hooksOff = true;
    }

    public boolean hooksOff() {
        return hooksOff;
    }

    /** {@code owner}'s hooks are off (config), or its hook injection failed: none of its checks are rewritten. */
    public void classSkipped(String owner) {
        skipped.add(owner);
    }

    /** {@code owner} loaded; {@code ids} are the site ids rewritten in it (empty when its rewrite failed). */
    public void classRewritten(String owner, BitSet ids) {
        loaded.put(owner, (BitSet) ids.clone());
    }

    /** Whether {@code owner} has loaded and gone through the rewrite (GameTests). */
    public boolean loaded(String owner) {
        return loaded.containsKey(owner);
    }

    /**
     * Null while a stand-in can count at site {@code siteId} ({@code key} = {@code owner#method#kind#target}); else
     * why it can't. A site whose class hasn't loaded yet counts.
     */
    @Nullable
    public String notCountingReason(int siteId, String key) {
        if (siteId < 0) {
            return "not an audited site";
        }
        if (hooksOff) {
            return HOOKS_OFF;
        }
        int hash = key.indexOf('#');
        String owner = hash < 0 ? key : key.substring(0, hash);
        if (skipped.contains(owner)) {
            return CLASS_OFF;
        }
        BitSet ids = loaded.get(owner);
        return ids == null || ids.get(siteId) ? null : NOT_REWRITTEN;
    }

    /** {@code owner}'s SAFE and NEEDS rows (the ones meant to be rewritten) that are not in {@code rewritten}. */
    public static List<String> missingUsable(SiteVerdicts verdicts, String owner, BitSet rewritten) {
        List<String> out = new ArrayList<>();
        String prefix = owner + "#";
        for (int i = 0; i < verdicts.size(); i++) {
            String key = verdicts.key(i);
            SiteVerdicts.Type t = verdicts.verdict(i).type();
            if (key.startsWith(prefix) && (t == SiteVerdicts.Type.SAFE || t == SiteVerdicts.Type.NEEDS)
                    && !key.contains("#BLOCK_EQUALS#") && !rewritten.get(i)) {
                out.add(key);
            }
        }
        return out;
    }
}
