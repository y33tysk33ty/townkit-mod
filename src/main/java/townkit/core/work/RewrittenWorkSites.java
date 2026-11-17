package townkit.core.work;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which audited work sites the bridges actually run at, as Towntopia's classes load. Filled by TownKit's mixin plugin;
 * must not load Minecraft classes. Hooks switched off (config) means no class reaches the plugin, so nothing is
 * recorded here.
 */
public final class RewrittenWorkSites {
    /** The running game's record (the mixin plugin writes it). */
    public static final RewrittenWorkSites GLOBAL = new RewrittenWorkSites();

    /** Loaded owners (relative to {@code com/alex/test/}) to the site ids rewritten in them (never mutated). */
    private final Map<String, BitSet> loaded = new ConcurrentHashMap<>();

    RewrittenWorkSites() {
    }

    /** {@code owner} went through the rewrite; {@code ids} were rewritten (merged into what an earlier pass recorded). */
    public void classRewritten(String owner, BitSet ids) {
        this.loaded.merge(owner, (BitSet) ids.clone(), (was, now) -> {
            BitSet both = (BitSet) was.clone();
            both.or(now);
            return both;
        });
    }

    public boolean loaded(String owner) {
        return this.loaded.containsKey(owner);
    }

    public boolean rewritten(int siteId, String owner) {
        BitSet ids = this.loaded.get(owner);
        return ids != null && ids.get(siteId);
    }

    /** {@code owner}'s REWRITE rows not rewritten (all of them while the class hasn't loaded through the plugin). */
    public List<String> missing(WorkSites sites, String owner) {
        BitSet ids = this.loaded.getOrDefault(owner, new BitSet());
        return missing(sites, owner, ids);
    }

    static List<String> missing(WorkSites sites, String owner, BitSet rewritten) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            if (sites.owner(i).equals(owner) && sites.rewrite(i) && !rewritten.get(i)) out.add(sites.key(i));
        }
        return out;
    }
}
