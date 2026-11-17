package townkit.core.nav;

import townkit.core.TestJars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class NavSiteScannerTest {
    private static final Path JAR_FILE = Path.of("libs", "towntopia-0.1.2.jar");

    /** The 0.1.2 jar the audit reads; the test is skipped when it isn't in libs/. */
    private static Path jar() {
        return TestJars.require(JAR_FILE);
    }

    /** key -> occurrences, as found in the jar (one NavSite per instruction). */
    private static Map<String, Integer> jarCounts() throws Exception {
        Map<String, Integer> out = new TreeMap<>();
        for (NavSite s : NavSiteScanner.scanJar(jar())) {
            out.merge(s.key(), 1, Integer::sum);
        }
        return out;
    }

    /** key -> occurrences, from nav-sites.tsv. */
    private static Map<String, Integer> tsvCounts() throws Exception {
        Map<String, Integer> out = new TreeMap<>();
        try (InputStream in = NavSiteScannerTest.class.getResourceAsStream("/townkit/nav-sites.tsv")) {
            assertNotNull(in, "nav-sites.tsv missing");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t", -1);
                assertEquals(4, cols.length, "row needs key, occurrences, verdict, note: " + line);
                assertTrue(cols[2].equals("REWRITE") || cols[2].equals("KEEP"), "bad verdict: " + line);
                assertFalse(cols[3].isBlank(), "row needs a note: " + line);
                assertEquals(null, out.put(cols[0], Integer.parseInt(cols[1])), "duplicate row: " + cols[0]);
            }
        }
        return out;
    }

    @Test
    void scanJarFindsEveryKind() throws Exception {
        List<NavSite> sites = NavSiteScanner.scanJar(jar());
        for (NavSite.Kind k : NavSite.Kind.values()) {
            assertTrue(sites.stream().anyMatch(s -> s.kind() == k), "no " + k + " site");
        }
        assertTrue(sites.stream().anyMatch(s -> s.owner().equals("village/AStarPathfinder")
                && s.method().startsWith("search") && s.kind() == NavSite.Kind.COST), sites.toString());
    }

    @Test
    void tsvMatchesJar() throws Exception {
        Map<String, Integer> jar = jarCounts();
        Map<String, Integer> tsv = tsvCounts();
        List<String> problems = new ArrayList<>();
        jar.forEach((k, n) -> {
            Integer t = tsv.get(k);
            if (t == null) {
                problems.add("missing row: " + k + " (" + n + ")");
            } else if (!t.equals(n)) {
                problems.add("count " + k + ": tsv " + t + ", jar " + n);
            }
        });
        tsv.keySet().stream().filter(k -> !jar.containsKey(k)).forEach(k -> problems.add("stale row: " + k));
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void paceOnlyInFollowWalk() throws Exception {
        List<NavSite> pace = NavSiteScanner.scanJar(jar()).stream().filter(s -> s.kind() == NavSite.Kind.PACE)
                .collect(Collectors.toList());
        assertFalse(pace.isEmpty());
        assertTrue(pace.stream().allMatch(s -> s.owner().equals("village/FollowWalk")), pace.toString());
    }

    /** The smoothing shortcut's line-of-sight test is a site (a shortcut may cross a never tile). */
    @Test
    void losOnlyInSmooth() throws Exception {
        List<NavSite> los = NavSiteScanner.scanJar(jar()).stream().filter(s -> s.kind() == NavSite.Kind.LOS)
                .collect(Collectors.toList());
        assertEquals(1, los.size(), los.toString());
        assertEquals("village/AStarPathfinder", los.get(0).owner());
        assertTrue(los.get(0).method().startsWith("smooth("), los.toString());
    }

    /**
     * The reachability adapter's answer ({@code NavCells$1#kind}'s returns) is its own site kind, so a
     * never tile maps to kind 1 (blocked for now) instead of -1 (no step, which cuts it out as an island).
     */
    @Test
    void cellOnlyInTheReachabilityAdapter() throws Exception {
        List<NavSite> cell = NavSiteScanner.scanJar(jar()).stream().filter(s -> s.kind() == NavSite.Kind.CELL)
                .collect(Collectors.toList());
        assertFalse(cell.isEmpty());
        assertTrue(cell.stream().allMatch(s -> s.key().equals("village/NavCells$1#kind(Lcom/alex/test/village/NavNode;"
                + "Lcom/alex/test/village/NavNode;J)I#CELL")), cell.toString());
    }

    @Test
    void keyJoinsTheThreeParts() {
        assertEquals("a/B#m()V#STEP", new NavSite("a/B", "m()V", NavSite.Kind.STEP).key());
    }

    /**
     * Writes the jar's site keys and counts to {@code build/nav-sites-all.tsv} (for the audit and after a Towntopia
     * update). Runs only when the environment variable {@code TOWNKIT_DUMP_SITES} is set.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "TOWNKIT_DUMP_SITES", matches = ".+")
    void dumpAllSites() throws Exception {
        StringBuilder sb = new StringBuilder();
        jarCounts().forEach((k, n) -> sb.append(k).append('\t').append(n).append('\n'));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "nav-sites-all.tsv"), sb.toString());
    }
}
