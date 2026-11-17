package townkit.core.nav;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The audited nav sites ({@code townkit/nav-sites.tsv}). Site ids are row indexes in file order; a site absent from
 * the file has id -1 and is never rewritten.
 *
 * <p>File format: {@code #} lines are comments; each other non-blank line is
 * {@code key<TAB>occurrences<TAB>verdict<TAB>note}, with {@code key = owner#method#kind} (see {@link NavSite#key()}),
 * {@code occurrences} how often that key occurs in the method of the audited jar, and verdict {@code REWRITE} or
 * {@code KEEP}. Must not load Minecraft classes (the mixin plugin reads it).
 */
public final class NavSites {
    public static final String RESOURCE = "/townkit/nav-sites.tsv";

    private final List<String> keys;
    private final List<String> owners;
    private final List<NavSite.Kind> kinds;
    private final List<Integer> counts;
    private final List<Boolean> rewrite;
    private final Map<String, Integer> ids = new HashMap<>();
    private final Set<String> rewriteOwners;

    private NavSites(List<String> keys, List<String> owners, List<NavSite.Kind> kinds, List<Integer> counts,
            List<Boolean> rewrite) {
        this.keys = List.copyOf(keys);
        this.owners = List.copyOf(owners);
        this.kinds = List.copyOf(kinds);
        this.counts = List.copyOf(counts);
        this.rewrite = List.copyOf(rewrite);
        Set<String> ro = new LinkedHashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            ids.put(keys.get(i), i);
            if (rewrite.get(i)) {
                ro.add(owners.get(i));
            }
        }
        this.rewriteOwners = Collections.unmodifiableSet(ro);
    }

    /** Reads the file shipped in TownKit's jar. */
    public static NavSites load() {
        try (InputStream in = NavSites.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE);
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static NavSites parse(Reader reader) throws IOException {
        List<String> keys = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        List<NavSite.Kind> kinds = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<Boolean> rewrite = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        BufferedReader in = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = in.readLine()) != null) {
            n++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 3) {
                throw new IllegalArgumentException("line " + n + ": expected key, occurrences, verdict, note: " + line);
            }
            String key = f[0];
            int first = key.indexOf('#');
            int last = key.lastIndexOf('#');
            if (first <= 0 || last <= first) {
                throw new IllegalArgumentException("line " + n + ": bad key " + key);
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("line " + n + ": duplicate site " + key);
            }
            int count;
            try {
                count = Integer.parseInt(f[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("line " + n + ": bad occurrences " + f[1], e);
            }
            if (count < 1) {
                throw new IllegalArgumentException("line " + n + ": occurrences must be at least 1");
            }
            boolean rw = switch (f[2].trim()) {
                case "REWRITE" -> true;
                case "KEEP" -> false;
                default -> throw new IllegalArgumentException("line " + n + ": unknown verdict " + f[2]);
            };
            keys.add(key);
            owners.add(key.substring(0, first));
            kinds.add(NavSite.Kind.valueOf(key.substring(last + 1)));
            counts.add(count);
            rewrite.add(rw);
        }
        return new NavSites(keys, owners, kinds, counts, rewrite);
    }

    /** Site id for {@link NavSite#key()}, or -1 if the site was never audited. */
    public int siteId(String key) {
        Integer id = ids.get(key);
        return id == null ? -1 : id;
    }

    public String key(int siteId) {
        return keys.get(siteId);
    }

    /** Owner relative to {@code com/alex/test/}. */
    public String owner(int siteId) {
        return owners.get(siteId);
    }

    public NavSite.Kind kind(int siteId) {
        return kinds.get(siteId);
    }

    /** How often the key occurs in its method in the audited jar. */
    public int count(int siteId) {
        return counts.get(siteId);
    }

    /** Verdict REWRITE (the bridge replaces it) rather than KEEP. */
    public boolean rewrite(int siteId) {
        return rewrite.get(siteId);
    }

    public int size() {
        return keys.size();
    }

    /** Owners with at least one REWRITE row. */
    public Set<String> rewriteOwners() {
        return rewriteOwners;
    }
}
