package townkit.core.building;

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
import javax.annotation.Nullable;

/**
 * The audited check sites ({@code townkit/building-sites.tsv}): per site key, whether a stand-in may pass it.
 * Site ids are row indexes in file order. A site absent from the file has id -1 and never counts a stand-in.
 *
 * <p>File format: {@code #} lines are comments; {@code # scope <owner>} lines list the owners whose sites are
 * rewritten; each other non-blank line is {@code owner<TAB>method<TAB>kind<TAB>target<TAB>verdict<TAB>note[<TAB>count]},
 * with verdict {@code SAFE}, {@code NEEDS:<check>}, {@code UNSAFE} or {@code AUDIT_ONLY}. {@code count} is how many
 * times the key occurs in that method of the audited jar (several checks of one block in one method share a key and
 * so a verdict); the rewriter leaves a method alone when the class it sees has another count. A row without it (older
 * files, hand-made test files) has count -1: not checked.
 */
public final class SiteVerdicts {
    public static final String RESOURCE = "/townkit/building-sites.tsv";
    private static final String SCOPE = "# scope ";

    public enum Type { SAFE, NEEDS, UNSAFE, AUDIT_ONLY }

    /** {@code check} is the behaviour check's name for NEEDS, else null. */
    public record Verdict(Type type, @Nullable String check) {
    }

    private final Set<String> scopedOwners;
    private final List<String> keys;
    private final List<Verdict> verdicts;
    private final List<String> notes;
    private final List<Integer> counts;
    private final Map<String, Integer> ids;

    private SiteVerdicts(Set<String> scopedOwners, List<String> keys, List<Verdict> verdicts, List<String> notes,
            List<Integer> counts) {
        this.scopedOwners = Collections.unmodifiableSet(scopedOwners);
        this.keys = List.copyOf(keys);
        this.verdicts = List.copyOf(verdicts);
        this.notes = List.copyOf(notes);
        this.counts = List.copyOf(counts);
        this.ids = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            ids.put(keys.get(i), i);
        }
    }

    /** Builds a list without a file (unit tests of consumers). */
    static SiteVerdicts of(List<String> keys, List<Verdict> verdicts) {
        if (keys.size() != verdicts.size()) {
            throw new IllegalArgumentException("keys and verdicts differ in size");
        }
        return new SiteVerdicts(Set.of(), keys, verdicts, java.util.Collections.nCopies(keys.size(), ""),
                java.util.Collections.nCopies(keys.size(), -1));
    }

    /** Reads the file shipped in TownKit's jar. */
    public static SiteVerdicts load() {
        try (InputStream in = SiteVerdicts.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE);
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static SiteVerdicts parse(Reader reader) throws IOException {
        Set<String> scope = new LinkedHashSet<>();
        List<String> keys = new ArrayList<>();
        List<Verdict> verdicts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        BufferedReader in = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = in.readLine()) != null) {
            n++;
            if (line.startsWith(SCOPE)) {
                scope.add(line.substring(SCOPE.length()).trim());
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 5) {
                throw new IllegalArgumentException("line " + n + ": expected 6 tab-separated fields: " + line);
            }
            CheckSite.Kind kind = CheckSite.Kind.valueOf(f[2]);
            String key = new CheckSite(f[0], f[1], kind, f[3]).key();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("line " + n + ": duplicate site " + key);
            }
            keys.add(key);
            verdicts.add(verdict(f[4], n));
            notes.add(f.length > 5 ? f[5] : "");
            counts.add(f.length > 6 && !f[6].isBlank() ? count(f[6].trim(), n) : -1);
        }
        return new SiteVerdicts(scope, keys, verdicts, notes, counts);
    }

    private static int count(String text, int line) {
        int c;
        try {
            c = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + line + ": bad count " + text, e);
        }
        if (c < 1) {
            throw new IllegalArgumentException("line " + line + ": count must be at least 1: " + text);
        }
        return c;
    }

    private static Verdict verdict(String text, int line) {
        if (text.startsWith("NEEDS:")) {
            String check = text.substring("NEEDS:".length());
            if (check.isEmpty()) {
                throw new IllegalArgumentException("line " + line + ": NEEDS without a check");
            }
            return new Verdict(Type.NEEDS, check);
        }
        try {
            Type type = Type.valueOf(text);
            if (type == Type.NEEDS) {
                throw new IllegalArgumentException("line " + line + ": NEEDS without a check");
            }
            return new Verdict(type, null);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("line " + line + ": unknown verdict " + text, e);
        }
    }

    /** Site id for {@code CheckSite.key()}, or -1 if the site was never audited. */
    public int siteId(String key) {
        Integer id = ids.get(key);
        return id == null ? -1 : id;
    }

    public Verdict verdict(int siteId) {
        return verdicts.get(siteId);
    }

    public String key(int siteId) {
        return keys.get(siteId);
    }

    /** The audit's reason (may be empty for SAFE rows). */
    public String note(int siteId) {
        return notes.get(siteId);
    }

    /** How often the site's key occurs in its method in the audited jar; -1 when the file doesn't say. */
    public int count(int siteId) {
        return counts.get(siteId);
    }

    public int size() {
        return keys.size();
    }

    /** Owners (relative to {@code com/alex/test/}) whose check sites TownKit rewrites. */
    public Set<String> scopedOwners() {
        return scopedOwners;
    }
}
