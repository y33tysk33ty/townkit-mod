package townkit.core.work;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The audited reads of the lists the work overlay replaces ({@code townkit/work-sites.tsv}). Site ids are row indexes
 * in file order; a read absent from the file has id -1 and is never rewritten.
 *
 * <p>File format: {@code #} lines are comments; each other non-blank line is
 * {@code key<TAB>occurrences<TAB>verdict<TAB>bridge<TAB>note}, with {@code key = owner#method#KIND} (see
 * {@link WorkSite#key()}; {@code owner#method#KIND@n} for the n-th occurrence of a {@linkplain WorkSite.Kind#perOccurrence
 * per-occurrence} kind, counted from 1; a KEEP row of such a kind without {@code @n} covers every occurrence in the method),
 * {@code occurrences} how often that kind occurs in the method in the audited jar, verdict
 * {@code REWRITE} (the read's value goes through the bridge) or {@code KEEP} (Towntopia's own read stays), and
 * {@code bridge} the static method a REWRITE row calls, {@code internal/class/Name#method} (it takes and returns the
 * field's type), or {@code -} for KEEP. Must not load Minecraft classes (the mixin plugin reads it).
 */
public final class WorkSites {
    public static final String RESOURCE = "/townkit/work-sites.tsv";

    private final List<String> keys;
    private final List<String> owners;
    private final List<WorkSite.Kind> kinds;
    private final List<Integer> counts;
    private final List<Boolean> rewrite;
    private final List<String> bridges;
    private final List<Integer> occurrences;
    private final Map<String, Integer> ids = new HashMap<>();
    /** {@code owner#method#KIND} (no occurrence) to the audited count of that kind in the method. */
    private final Map<String, Integer> baseCounts = new HashMap<>();
    private final Set<String> rewriteOwners;

    private WorkSites(List<String> keys, List<String> owners, List<WorkSite.Kind> kinds, List<Integer> counts, List<Boolean> rewrite,
            List<String> bridges, List<Integer> occurrences) {
        this.keys = List.copyOf(keys);
        this.owners = List.copyOf(owners);
        this.kinds = List.copyOf(kinds);
        this.counts = List.copyOf(counts);
        this.rewrite = List.copyOf(rewrite);
        this.bridges = List.copyOf(bridges);
        this.occurrences = List.copyOf(occurrences);
        Set<String> ro = new LinkedHashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            this.ids.put(keys.get(i), i);
            this.baseCounts.put(baseKey(i), counts.get(i));
            if (rewrite.get(i)) ro.add(owners.get(i));
        }
        this.rewriteOwners = Collections.unmodifiableSet(ro);
    }

    /** Reads the file shipped in TownKit's jar. */
    public static WorkSites load() {
        try (InputStream in = WorkSites.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static WorkSites parse(Reader reader) throws IOException {
        List<String> keys = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        List<WorkSite.Kind> kinds = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<Boolean> rewrite = new ArrayList<>();
        List<String> bridges = new ArrayList<>();
        List<Integer> occurrences = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BufferedReader in = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = in.readLine()) != null) {
            n++;
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 5) throw new IllegalArgumentException("line " + n + ": expected key, occurrences, verdict, bridge, note: " + line);
            String key = f[0];
            int first = key.indexOf('#');
            int last = key.lastIndexOf('#');
            if (first <= 0 || last <= first) throw new IllegalArgumentException("line " + n + ": bad key " + key);
            if (!seen.add(key)) throw new IllegalArgumentException("line " + n + ": duplicate site " + key);
            int count;
            try {
                count = Integer.parseInt(f[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("line " + n + ": bad occurrences " + f[1], e);
            }
            if (count < 1) throw new IllegalArgumentException("line " + n + ": occurrences must be at least 1");
            boolean rw = switch (f[2].trim()) {
                case "REWRITE" -> true;
                case "KEEP" -> false;
                default -> throw new IllegalArgumentException("line " + n + ": unknown verdict " + f[2]);
            };
            String bridge = f[3].trim();
            if (rw && (bridge.indexOf('#') <= 0 || bridge.endsWith("#"))) {
                throw new IllegalArgumentException("line " + n + ": a REWRITE row needs a bridge class#method: " + bridge);
            }
            String kindPart = key.substring(last + 1);
            int at = kindPart.indexOf('@');
            WorkSite.Kind kind;
            try {
                kind = WorkSite.Kind.valueOf(at < 0 ? kindPart : kindPart.substring(0, at));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + n + ": unknown kind " + kindPart, e);
            }
            int occurrence = 0;
            if (kind.perOccurrence() && at < 0) {
                // a whole-method KEEP row: every occurrence of the kind in the method was audited and none is rewritten
                if (rw) throw new IllegalArgumentException("line " + n + ": a " + kind + " REWRITE row names one occurrence (" + kind + "@n): " + key);
            } else if (kind.perOccurrence()) {
                try {
                    occurrence = Integer.parseInt(kindPart.substring(at + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("line " + n + ": bad occurrence in " + key, e);
                }
                if (occurrence < 1 || occurrence > count) {
                    throw new IllegalArgumentException("line " + n + ": occurrence " + occurrence + " outside 1.." + count);
                }
            } else if (at >= 0) {
                throw new IllegalArgumentException("line " + n + ": only constants name an occurrence: " + key);
            }
            if (rw && kind.style == WorkSite.Style.AUDIT_ONLY) {
                throw new IllegalArgumentException("line " + n + ": " + kind + " rows are KEEP only: " + key);
            }
            keys.add(key);
            owners.add(key.substring(0, first));
            kinds.add(kind);
            counts.add(count);
            rewrite.add(rw);
            bridges.add(rw ? bridge : "");
            occurrences.add(occurrence);
        }
        return new WorkSites(keys, owners, kinds, counts, rewrite, bridges, occurrences);
    }

    /** The audited count of the kind in {@code owner#method#KIND} (no occurrence), or -1 when no row names it. */
    public int baseCount(String baseKey) {
        Integer n = this.baseCounts.get(baseKey);
        return n == null ? -1 : n;
    }

    /** Site id for {@link WorkSite#key()}, or -1 if the read was never audited. */
    public int siteId(String key) {
        Integer id = this.ids.get(key);
        return id == null ? -1 : id;
    }

    public String key(int siteId) {
        return this.keys.get(siteId);
    }

    /** Owner relative to {@code com/alex/test/}. */
    public String owner(int siteId) {
        return this.owners.get(siteId);
    }

    public WorkSite.Kind kind(int siteId) {
        return this.kinds.get(siteId);
    }

    /**
     * For a per-occurrence row, which occurrence of its kind in the method (from 1); 0 for other rows, including a
     * whole-method KEEP row of a constant kind (every occurrence audited, none rewritten).
     */
    public int occurrence(int siteId) {
        return this.occurrences.get(siteId);
    }

    /** The key without its {@code @n} occurrence: {@code owner#method#KIND}. */
    public String baseKey(int siteId) {
        String k = this.keys.get(siteId);
        int at = k.lastIndexOf('@');
        return at > k.lastIndexOf('#') ? k.substring(0, at) : k;
    }

    /** How often the row's kind occurs in its method in the audited jar. */
    public int count(int siteId) {
        return this.counts.get(siteId);
    }

    /** Verdict REWRITE rather than KEEP. */
    public boolean rewrite(int siteId) {
        return this.rewrite.get(siteId);
    }

    /** The bridge's internal class name (REWRITE rows). */
    public String bridgeOwner(int siteId) {
        String b = this.bridges.get(siteId);
        return b.substring(0, b.indexOf('#'));
    }

    /** The bridge's method name (REWRITE rows). */
    public String bridgeName(int siteId) {
        String b = this.bridges.get(siteId);
        return b.substring(b.indexOf('#') + 1);
    }

    public int size() {
        return this.keys.size();
    }

    /** Owners with at least one REWRITE row. */
    public Set<String> rewriteOwners() {
        return this.rewriteOwners;
    }
}
