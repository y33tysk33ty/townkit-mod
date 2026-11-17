package townkit.core.building;

import townkit.core.TestJars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

class CheckSiteScannerTest {
    private static final Path JAR_FILE = Path.of("libs", "towntopia-0.1.2.jar");

    /** The 0.1.2 jar the audit reads; the test is skipped when it isn't in libs/. */
    private static Path jar() {
        return TestJars.require(JAR_FILE);
    }

    private static List<CheckSite> scan(String prefix) throws Exception {
        return CheckSiteScanner.scanJar(jar(), o -> o.startsWith(prefix));
    }

    private static boolean has(List<CheckSite> sites, CheckSite.Kind kind, String target) {
        return sites.stream().anyMatch(s -> s.kind() == kind && s.target().equals(target));
    }

    @Test
    void kitchenCraftingTableFound() throws Exception {
        List<CheckSite> sites = scan("village/building/KitchenBuilding");
        assertTrue(has(sites, CheckSite.Kind.BLOCK_IS, "CRAFTING_TABLE"), sites.toString());
        assertTrue(has(sites, CheckSite.Kind.INSTANCEOF, "net/minecraft/world/level/block/CampfireBlock"), sites.toString());
    }

    @Test
    void goHomeBedFound() throws Exception {
        List<CheckSite> sites = scan("village/brain/GoHomeActivity");
        assertTrue(has(sites, CheckSite.Kind.INSTANCEOF, "net/minecraft/world/level/block/BedBlock"), sites.toString());
    }

    @Test
    void blockEntityInstanceofIgnored() throws Exception {
        List<CheckSite> sites = scan("village/building/TavernBuilding");
        assertFalse(sites.stream().anyMatch(s -> s.kind() == CheckSite.Kind.INSTANCEOF
                && s.target().endsWith("ChestBlockEntity")), sites.toString());
    }

    @Test
    void navGraphNotInScopeByFilter() throws Exception {
        List<CheckSite> sites = scan("village/building/");
        assertFalse(sites.isEmpty());
        assertTrue(sites.stream().allMatch(s -> s.owner().startsWith("village/building/")));
    }

    /**
     * Writes every site in the whole jar to {@code build/check-sites-all.tsv} (for the audit and after a Towntopia
     * update). Runs only when the environment variable {@code TOWNKIT_DUMP_SITES} is set.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "TOWNKIT_DUMP_SITES", matches = ".+")
    void dumpAllSites() throws Exception {
        List<CheckSite> sites = CheckSiteScanner.scanJar(jar(), o -> true);
        StringBuilder sb = new StringBuilder();
        for (CheckSite s : sites) {
            sb.append(s.owner()).append('\t').append(s.method()).append('\t').append(s.kind()).append('\t')
                    .append(s.target()).append('\n');
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "check-sites-all.tsv"), sb.toString());
        Files.write(Path.of("build", "check-sites-unanalyzable.txt"), CheckSiteScanner.unanalyzableInJar(jar(), o -> true));
    }

    /** Review item (a): a method the analyzer rejects is reported, not silently dropped. */
    @Test
    void unanalyzableMethodIsReported() {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21;
        cn.access = Opcodes.ACC_PUBLIC;
        cn.name = "com/alex/test/x/Bad";
        cn.superName = "java/lang/Object";
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bad", "()V", null, null);
        m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/world/level/block/Blocks", "CHEST",
                "Lnet/minecraft/world/level/block/Block;"));
        m.instructions.add(new InsnNode(Opcodes.POP));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.maxStack = 0; // too small: the analyzer throws
        m.maxLocals = 0;
        cn.methods.add(m);
        assertEquals(List.of("x/Bad#bad()V"), CheckSiteScanner.unanalyzable(cn));
    }

    @Test
    void analyzableClassReportsNothing() throws Exception {
        assertTrue(CheckSiteScanner.unanalyzableInJar(jar(), o -> o.equals("village/building/KitchenBuilding")).isEmpty());
    }

    @Test
    void keyJoinsTheFourParts() {
        CheckSite s = new CheckSite("a/B", "m()V", CheckSite.Kind.BLOCK_IS, "CHEST");
        assertEquals("a/B#m()V#BLOCK_IS#CHEST", s.key());
    }
}
