package townkit.core.building;

import townkit.core.TestJars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

class SiteVerdictsTest {
    private static final Path JAR_FILE = Path.of("libs", "towntopia-0.1.2.jar");

    /** The 0.1.2 jar the audit reads; the test is skipped when it isn't in libs/. */
    private static Path jar() {
        return TestJars.require(JAR_FILE);
    }
    private static final String BED = "net/minecraft/world/level/block/BedBlock";
    private static final Set<String> CHECKS = Set.of("bed_block", "inventory", "furnace_entity", "container");

    private static SiteVerdicts verdicts;
    private static List<CheckSite> scoped;

    @BeforeAll
    static void load() throws Exception {
        verdicts = SiteVerdicts.load();
        scoped = CheckSiteScanner.scanJar(jar(), verdicts.scopedOwners()::contains);
    }

    @Test
    void everyScopedSiteHasAVerdict() {
        List<String> missing = new ArrayList<>();
        for (CheckSite s : scoped) {
            if (verdicts.siteId(s.key()) < 0) {
                missing.add(s.key());
            }
        }
        assertTrue(missing.isEmpty(), "sites without a verdict: " + missing);
    }

    @Test
    void everyRowExistsInJar() {
        Set<String> found = new HashSet<>();
        scoped.forEach(s -> found.add(s.key()));
        List<String> stale = new ArrayList<>();
        for (int id = 0; id < verdicts.size(); id++) {
            if (verdicts.verdict(id).type() != SiteVerdicts.Type.AUDIT_ONLY && !found.contains(verdicts.key(id))) {
                stale.add(verdicts.key(id));
            }
        }
        assertTrue(stale.isEmpty(), "rows not found in the jar's scoped owners: " + stale);
    }

    @Test
    void bedSitesNeedBedBlock() {
        for (CheckSite s : scoped) {
            if (s.target().equals(BED)) {
                SiteVerdicts.Verdict v = verdicts.verdict(verdicts.siteId(s.key()));
                assertTrue(v.type() == SiteVerdicts.Type.UNSAFE
                        || (v.type() == SiteVerdicts.Type.NEEDS && "bed_block".equals(v.check())), s.key() + " " + v);
            }
        }
    }

    @Test
    void kitchenHeatIsSafe() {
        String heat = "village/building/KitchenBuilding#heatSourceOf(Lnet/minecraft/world/level/block/state/BlockState;)"
                + "Lcom/alex/test/village/building/KitchenBuilding$HeatSource;#INSTANCEOF#net/minecraft/world/level/block/";
        for (String cls : List.of("SmokerBlock", "BlastFurnaceBlock", "AbstractFurnaceBlock", "CampfireBlock")) {
            int id = verdicts.siteId(heat + cls);
            assertTrue(id >= 0, cls);
            assertEquals(SiteVerdicts.Type.SAFE, verdicts.verdict(id).type(), cls);
        }
    }

    /** Review item (a): a method whose analysis fails would hide its BLOCK_IS sites. */
    @Test
    void noScopedMethodFailsAnalysis() throws Exception {
        List<String> failed = CheckSiteScanner.unanalyzableInJar(jar(), verdicts.scopedOwners()::contains);
        assertTrue(failed.isEmpty(), "scoped methods the scanner could not analyse: " + failed);
    }

    /** After a Towntopia update: a new building class with block checks must be audited, not silently skipped. */
    @Test
    void everyBuildingPackageSiteIsScoped() throws Exception {
        List<CheckSite> all = CheckSiteScanner.scanJar(jar(), o -> o.startsWith("village/building/"));
        List<String> unscoped = new ArrayList<>();
        for (CheckSite s : all) {
            if (!verdicts.scopedOwners().contains(s.owner())) {
                unscoped.add(s.key());
            }
        }
        assertTrue(unscoped.isEmpty(), "building sites outside the scope: " + unscoped);
    }

    /**
     * A stand-in passing an {@code instanceof} is not an instance of that class: a cast after it would throw.
     * Every counting INSTANCEOF site (bed sites aside: a bed stand-in must be a BedBlock) must have no cast to it.
     */
    @Test
    void countingInstanceofSitesNeverCast() throws Exception {
        List<String> casts = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar().toFile())) {
            for (CheckSite s : scoped) {
                if (s.kind() != CheckSite.Kind.INSTANCEOF) {
                    continue;
                }
                SiteVerdicts.Verdict v = verdicts.verdict(verdicts.siteId(s.key()));
                boolean counts = v.type() == SiteVerdicts.Type.SAFE
                        || (v.type() == SiteVerdicts.Type.NEEDS && !"bed_block".equals(v.check()));
                if (!counts) {
                    continue;
                }
                ClassNode cn = new ClassNode();
                try (InputStream in = zip.getInputStream(zip.getEntry("com/alex/test/" + s.owner() + ".class"))) {
                    new ClassReader(in).accept(cn, 0);
                }
                for (MethodNode m : cn.methods) {
                    if (!(m.name + m.desc).equals(s.method())) {
                        continue;
                    }
                    for (AbstractInsnNode in : m.instructions) {
                        if (in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.CHECKCAST && t.desc.equals(s.target())) {
                            casts.add(s.key());
                        }
                    }
                }
            }
        }
        assertTrue(casts.isEmpty(), "counting sites that cast to the vanilla class: " + casts);
    }

    @Test
    void rowsAreWellFormed() {
        assertTrue(verdicts.size() > 0);
        assertFalse(verdicts.scopedOwners().isEmpty());
        for (int id = 0; id < verdicts.size(); id++) {
            SiteVerdicts.Verdict v = verdicts.verdict(id);
            String key = verdicts.key(id);
            assertEquals(id, verdicts.siteId(key), "duplicate row " + key);
            if (v.type() == SiteVerdicts.Type.NEEDS) {
                assertTrue(CHECKS.contains(v.check()), key + " " + v);
            } else {
                assertEquals(null, v.check(), key);
            }
            if (v.type() == SiteVerdicts.Type.UNSAFE || v.type() == SiteVerdicts.Type.AUDIT_ONLY) {
                assertFalse(verdicts.note(id).isBlank(), "no reason for " + key);
            }
            String owner = key.substring(0, key.indexOf('#'));
            if (verdicts.scopedOwners().contains(owner) && v.type() == SiteVerdicts.Type.AUDIT_ONLY) {
                assertTrue(key.contains("#BLOCK_EQUALS#"), "AUDIT_ONLY in a scoped owner must be BLOCK_EQUALS: " + key);
            }
            if (key.contains("#BLOCK_EQUALS#")) {
                assertEquals(SiteVerdicts.Type.AUDIT_ONLY, v.type(), "BLOCK_EQUALS is never rewritten: " + key);
            }
        }
    }

    @Test
    void unknownKeyIsMinusOne() {
        assertEquals(-1, verdicts.siteId("nope#x()V#BLOCK_IS#CHEST"));
    }

    @Test
    void parseReadsScopeAndRows() throws Exception {
        String text = "# a comment\n"
                + "# scope a/B\n"
                + "a/B\tm()V\tBLOCK_IS\tCHEST\tNEEDS:inventory\tputs items\n"
                + "a/B\tm()V\tINSTANCEOF\tx/Y\tUNSAFE\tcasts\n"
                + "\n"
                + "c/D\tn()V\tBLOCK_EQUALS\tBOOKSHELF\tAUDIT_ONLY\tblueprint\n";
        SiteVerdicts v = SiteVerdicts.parse(new StringReader(text));
        assertEquals(Set.of("a/B"), v.scopedOwners());
        assertEquals(3, v.size());
        int id = v.siteId("a/B#m()V#BLOCK_IS#CHEST");
        assertEquals(0, id);
        assertEquals(new SiteVerdicts.Verdict(SiteVerdicts.Type.NEEDS, "inventory"), v.verdict(id));
        assertEquals(SiteVerdicts.Type.UNSAFE, v.verdict(1).type());
        assertEquals("casts", v.note(1));
        assertEquals(SiteVerdicts.Type.AUDIT_ONLY, v.verdict(2).type());
    }

    @Test
    void parseRejectsBadRows() {
        assertThrows(IllegalArgumentException.class,
                () -> SiteVerdicts.parse(new StringReader("a/B\tm()V\tBLOCK_IS\tCHEST\tMAYBE\tx\n")));
        assertThrows(IllegalArgumentException.class,
                () -> SiteVerdicts.parse(new StringReader("a/B\tm()V\tBLOCK_IS\n")));
        assertThrows(IllegalArgumentException.class,
                () -> SiteVerdicts.parse(new StringReader("a/B\tm()V\tBLOCK_IS\tCHEST\tNEEDS:\tx\n")));
        assertThrows(IllegalArgumentException.class, () -> SiteVerdicts.parse(new StringReader(
                "a/B\tm()V\tBLOCK_IS\tCHEST\tSAFE\t\na/B\tm()V\tBLOCK_IS\tCHEST\tSAFE\t\n")));
    }

    @Test
    void loadedFileHasKitchenTable() {
        int id = verdicts.siteId("village/building/KitchenBuilding#scanFunctionalBlocks(Lnet/minecraft/server/level/ServerLevel;)Z"
                + "#BLOCK_IS#CRAFTING_TABLE");
        assertNotEquals(-1, id);
        assertNotNull(verdicts.verdict(id));
    }

    /** A count column (7th) per row; 6-column rows still parse, count unknown (-1). */
    @Test
    void parseReadsOccurrenceCounts() throws Exception {
        SiteVerdicts v = SiteVerdicts.parse(new StringReader("a/B\tm()V\tBLOCK_IS\tCHEST\tSAFE\t\t2\n"
                + "a/B\tm()V\tBLOCK_IS\tBARREL\tSAFE\tnote\n"));
        assertEquals(2, v.count(0));
        assertEquals(-1, v.count(1));
        assertThrows(IllegalArgumentException.class,
                () -> SiteVerdicts.parse(new StringReader("a/B\tm()V\tBLOCK_IS\tCHEST\tSAFE\t\tmany\n")));
        assertThrows(IllegalArgumentException.class,
                () -> SiteVerdicts.parse(new StringReader("a/B\tm()V\tBLOCK_IS\tCHEST\tSAFE\t\t0\n")));
    }

    /**
     * Every row of a scoped owner records how often its key occurs in the method, and that
     * matches the jar. After a Towntopia update a changed count fails here: re-audit the method (a second check of
     * the same block may be followed by something a stand-in can't survive).
     */
    @Test
    void shippedCountsMatchTheJar() throws Exception {
        java.util.Map<String, Integer> jar = new java.util.HashMap<>();
        try (ZipFile zip = new ZipFile(jar().toFile())) {
            for (String owner : verdicts.scopedOwners()) {
                ClassNode cn = new ClassNode();
                try (InputStream in = zip.getInputStream(zip.getEntry("com/alex/test/" + owner + ".class"))) {
                    new ClassReader(in).accept(cn, 0);
                }
                jar.putAll(CheckSiteScanner.occurrences(cn));
            }
        }
        List<String> wrong = new ArrayList<>();
        for (int id = 0; id < verdicts.size(); id++) {
            String key = verdicts.key(id);
            String owner = key.substring(0, key.indexOf('#'));
            if (!verdicts.scopedOwners().contains(owner)) {
                continue;
            }
            Integer found = jar.get(key);
            if (found == null || found != verdicts.count(id)) {
                wrong.add(key + " tsv=" + verdicts.count(id) + " jar=" + found);
            }
        }
        assertTrue(wrong.isEmpty(), "occurrence counts that don't match the jar: " + wrong);
    }

    /** Targets of the HookedCommonClasses/HookedCommonInterfaces carrier mixins (dotted names), which get no hooks. */
    static Set<String> carrierTargets() throws Exception {
        Set<String> targets = new HashSet<>();
        for (String carrier : List.of("HookedCommonClasses", "HookedCommonInterfaces")) {
            try (InputStream in = SiteVerdictsTest.class.getResourceAsStream("/townkit/mixin/gen/" + carrier + ".class")) {
                assertNotNull(in, carrier);
                ClassNode cn = new ClassNode();
                new ClassReader(in).accept(cn, ClassReader.SKIP_CODE);
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
        return targets;
    }

    /**
     * Scoped owners (relative to com/alex/test/) that are neither in the generated hook manifest nor a carrier target
     * (an owner left with no hooks by the 0.6.1 trim is handed to the plugin as a carrier).
     */
    static List<String> unhookedOwners(Set<String> owners, Set<String> carrierTargets) {
        List<String> out = new ArrayList<>();
        for (String owner : owners) {
            if (!townkit.core.hook.HookManifest.get().byOwner.containsKey("com/alex/test/" + owner)
                && !carrierTargets.contains("com.alex.test." + owner.replace('/', '.'))) {
                out.add(owner);
            }
        }
        return out;
    }

    /**
     * Check sites are rewritten only in classes the plugin sees (the rewrite runs from the hook injector). A scoped owner
     * with neither hooks nor a carrier mixin would be reported as "not loaded yet, counting" forever.
     */
    @Test
    void everyScopedOwnerIsAHookedClass() throws Exception {
        assertTrue(!verdicts.scopedOwners().isEmpty(), "no scoped owners parsed");
        assertEquals(List.of(), unhookedOwners(verdicts.scopedOwners(), carrierTargets()));
    }

    @Test
    void aCarrierOnlyOwnerIsAccepted() {
        Set<String> owners = Set.of("village/building/FakeCarrierOnly");
        assertEquals(List.of("village/building/FakeCarrierOnly"), unhookedOwners(owners, Set.of()));
        assertEquals(List.of(), unhookedOwners(owners, Set.of("com.alex.test.village.building.FakeCarrierOnly")));
    }

    @Test
    void anUnhookedOwnerIsFound() {
        Set<String> owners = new java.util.LinkedHashSet<>(verdicts.scopedOwners());
        owners.add("village/building/NotARealBuilding");
        assertEquals(List.of("village/building/NotARealBuilding"), unhookedOwners(owners, Set.of()));
    }
}
