package townkit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import townkit.core.hook.HookManifest;

/**
 * The 0.6.1 hook trim never removes a hook TownKit's own core uses. Cross-checks hookgen's source collector at run time:
 * every class in {@code townkit.core} that names a hook key in its source is loaded, and every hook key reachable from
 * its static fields (constants, constant-prefix keys, maps, lists and records such as event bindings) must be in the
 * generated manifest, as must every whole key literal in the source.
 */
class TrimSafetyTest {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.$]+#[A-Za-z0-9_<>$]+\\([^\"#]*\\)(~\\d+)?");
    private static final Path CORE = Path.of("src/main/java/townkit/core");
    private static final Path GAMETEST = Path.of("src/gametest/java");

    /**
     * The string literals of a Java source, read the way javac does (independent of hookgen's lexer): comments and char
     * literals skipped, backslash escapes honoured, so an earlier {@code "line\n"} or {@code '"'} never shifts the pairing.
     */
    static List<String> literals(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
            } else if (c == '"' || c == '\'') {
                boolean block = c == '"' && s.startsWith("\"\"\"", i);
                StringBuilder lit = new StringBuilder();
                i += block ? 3 : 1;
                while (i < s.length() && !(block ? s.startsWith("\"\"\"", i) : s.charAt(i) == c)) {
                    if (s.charAt(i) == '\\' && i + 1 < s.length()) {
                        lit.append(s, i, i + 2);
                        i += 2;
                    } else {
                        lit.append(s.charAt(i++));
                    }
                }
                i += block ? 3 : 1;
                if (c == '"') out.add(lit.toString());
            } else {
                i++;
            }
        }
        return out;
    }

    @Test
    void literalsSurviveEscapesAndCharQuotes() {
        assertEquals(List.of("line\\n", "village.Foo#bar(int)"), literals("String a = \"line\\n\"; get(\"village.Foo#bar(int)\");"));
        assertEquals(List.of("village.Foo#bar(int)"), literals("char q = '\"'; get(\"village.Foo#bar(int)\"); // \"x\""));
        assertEquals(List.of("\\\"", "village.Foo#bar(int)"), literals("String q = \"\\\"\"; get(\"village.Foo#bar(int)\");"));
    }

    /**
     * Every hook key TownKit's GameTests use is generated, except keys an exclusion in hook-rules.txt names: those tests
     * check that a hot path is NOT hooked (and the generated manifest must agree).
     */
    @Test
    void everyGametestHookKeyIsGenerated() throws Exception {
        List<Pattern[]> exclusions = new ArrayList<>();
        for (String raw : Files.readAllLines(Path.of("hookgen/hook-rules.txt"))) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("@") || line.startsWith("keep ")) continue;
            int hash = line.indexOf('#');
            String cls = hash < 0 ? line : line.substring(0, hash);
            exclusions.add(new Pattern[]{Pattern.compile(cls.replace(".", "\\.").replace("$", "\\$").replace("*", ".*")),
                hash < 0 ? null : Pattern.compile(line.substring(hash + 1))});
        }
        Set<String> asked = new TreeSet<>();
        try (Stream<Path> files = Files.walk(GAMETEST)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String s : literals(Files.readString(f))) {
                    if (KEY.matcher(s).matches()) asked.add(s);
                }
            }
        }
        assertTrue(asked.contains("village.villagers.farmer.crops.BlockMatcher$Single#<init>(Block)"), asked.toString());
        List<String> missing = new ArrayList<>();
        List<String> excludedButHooked = new ArrayList<>();
        for (String k : asked) {
            String cls = k.substring(0, k.indexOf('#'));
            String method = k.substring(k.indexOf('#') + 1, k.indexOf('('));
            boolean excluded = exclusions.stream().anyMatch(p -> p[0].matcher(cls).matches() && (p[1] == null || p[1].matcher(method).matches()));
            boolean generated = HookManifest.get().byKey.containsKey(k);
            if (!excluded && !generated) missing.add(k);
            if (excluded && generated) excludedButHooked.add(k);
        }
        assertTrue(missing.isEmpty(), "GameTest hook keys not generated: " + missing);
        assertTrue(excludedButHooked.isEmpty(), "excluded keys generated anyway: " + excludedButHooked);
    }

    @Test
    void everyCoreHookKeyIsGenerated() throws Exception {
        Set<String> asked = new TreeSet<>();
        List<String> users = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CORE)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                boolean names = false;
                for (String s : literals(src)) {
                    if (KEY.matcher(s).matches()) {
                        asked.add(s);
                        names = true;
                    } else if (s.matches("[A-Za-z0-9_.$]+#")) {
                        names = true; // a key prefix: the keys built from it are found through the static fields
                    }
                }
                if (names) {
                    String rel = CORE.relativize(f).toString().replace('\\', '/').replace(".java", "").replace('/', '.');
                    users.add("townkit.core." + rel);
                }
            }
        }
        for (String user : users) {
            Class<?> c = Class.forName(user, true, getClass().getClassLoader());
            for (Field fd : c.getDeclaredFields()) {
                if (!Modifier.isStatic(fd.getModifiers())) continue;
                fd.setAccessible(true);
                collect(fd.get(null), asked, 0);
            }
        }
        // every core user listed above shows up
        for (String must : List.of("brain.BrainHooks", "brain.ChoiceHooks", "brain.InterruptHooks", "building.BuildingEventHooks",
            "event.EventBindings", "nav.LinkInstaller", "nav.RideEngine", "villager.VillagerDataHooks")) {
            assertTrue(users.contains("townkit.core." + must), must + " names hook keys: " + users);
        }
        assertTrue(asked.contains("village.VillageBuildings#tryAutoRegisterRoom(ServerLevel,BlockPos)"), asked.toString());
        List<String> missing = asked.stream().filter(k -> !HookManifest.get().byKey.containsKey(k)).toList();
        assertTrue(missing.isEmpty(), "core hook keys not generated: " + missing);
    }

    /**
     * Every owner the tsv files need rewritten (building-sites scope lines, nav-sites REWRITE rows) is hooked or a carrier
     * target: its sites are rewritten only in classes the mixin plugin sees. Owners that appear only in AUDIT_ONLY or KEEP
     * rows (village.NavNode, which hook-rules.txt excludes wholesale) are never rewritten and need no carrier.
     */
    @Test
    void everyTsvOwnerIsHookedOrCarrier() throws Exception {
        Set<String> targets = new TreeSet<>();
        for (String carrier : List.of("HookedCommonClasses", "HookedCommonInterfaces")) {
            try (var in = TrimSafetyTest.class.getResourceAsStream("/townkit/mixin/gen/" + carrier + ".class")) {
                assertTrue(in != null, carrier);
                var cn = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(in).accept(cn, org.objectweb.asm.ClassReader.SKIP_CODE);
                List<org.objectweb.asm.tree.AnnotationNode> anns = new ArrayList<>();
                if (cn.invisibleAnnotations != null) anns.addAll(cn.invisibleAnnotations);
                if (cn.visibleAnnotations != null) anns.addAll(cn.visibleAnnotations);
                for (var a : anns) {
                    for (int i = 0; a.values != null && i < a.values.size(); i += 2) {
                        if (a.values.get(i).equals("targets")) {
                            for (Object t : (List<?>) a.values.get(i + 1)) targets.add((String) t);
                        }
                    }
                }
            }
        }
        assertTrue(targets.size() > 100, "no targets read");
        Set<String> owners = new TreeSet<>(townkit.core.building.SiteVerdicts.load().scopedOwners());
        owners.addAll(townkit.core.nav.NavSites.load().rewriteOwners());
        assertTrue(owners.size() > 20, owners.toString());
        List<String> missing = ownersWithoutTarget(owners, targets);
        assertTrue(missing.isEmpty(), "tsv owners neither hooked nor carrier targets: " + missing);
    }

    /** Owners (relative, slash form) that no carrier mixin targets (dotted names under com.alex.test). */
    static List<String> ownersWithoutTarget(Set<String> owners, Set<String> targets) {
        List<String> missing = new ArrayList<>();
        for (String o : owners) {
            if (!targets.contains("com.alex.test." + o.replace('/', '.'))) missing.add(o);
        }
        return missing;
    }

    @Test
    void aCarrierOnlyTargetSatisfiesTheOwnerCheck() {
        assertEquals(List.of("village/Fake"), ownersWithoutTarget(Set.of("village/Fake"), Set.of()));
        assertEquals(List.of(), ownersWithoutTarget(Set.of("village/Fake"), Set.of("com.alex.test.village.Fake")));
    }

    private static void collect(Object v, Set<String> out, int depth) throws Exception {
        if (v == null || depth > 4) return;
        if (v instanceof String s) {
            if (KEY.matcher(s).matches()) out.add(s);
        } else if (v instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collect(e.getKey(), out, depth + 1);
                collect(e.getValue(), out, depth + 1);
            }
        } else if (v instanceof Collection<?> col) {
            for (Object o : col) collect(o, out, depth + 1);
        } else if (v instanceof Record r) {
            for (RecordComponent rc : r.getClass().getRecordComponents()) {
                var acc = rc.getAccessor();
                acc.setAccessible(true);
                collect(acc.invoke(r), out, depth + 1);
            }
        }
    }
}
