package townkit.core.building;

import townkit.core.TestJars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

class CheckSiteRewriterTest {
    private static final String OWNER = "village/building/FakeBuilding";
    private static final String CLS = "com/alex/test/" + OWNER;
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String CAMPFIRE = "net/minecraft/world/level/block/CampfireBlock";
    private static final String DESC = "(L" + STATE + ";Ljava/lang/Object;)Z";
    private static final String BRIDGE = "townkit/core/building/StandIns";

    private static String row(String method, String kind, String target, String verdict) {
        return OWNER + "\t" + method + "\t" + kind + "\t" + target + "\t" + verdict + "\tnote\n";
    }

    private static String row(String method, String kind, String target, String verdict, int count) {
        return OWNER + "\t" + method + "\t" + kind + "\t" + target + "\t" + verdict + "\tnote\t" + count + "\n";
    }

    private static SiteVerdicts verdicts(String rows) throws Exception {
        return SiteVerdicts.parse(new StringReader("# scope " + OWNER + "\n" + rows));
    }

    private static SiteVerdicts bothSafe() throws Exception {
        return verdicts(row("other()V", "BLOCK_IS", "STONE", "SAFE")
                + row("check" + DESC, "BLOCK_IS", "CRAFTING_TABLE", "SAFE")
                + row("check" + DESC, "INSTANCEOF", CAMPFIRE, "NEEDS:inventory"));
    }

    /** {@code boolean <name>(BlockState s, Object o) { return s.is(Blocks.CRAFTING_TABLE) && o instanceof CampfireBlock; }} */
    private static ClassNode fake(String name, boolean castAfter) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = CLS;
        cn.superName = "java/lang/Object";
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, DESC, null, null);
        InsnList il = m.instructions;
        LabelNode no = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/world/level/block/Blocks", "CRAFTING_TABLE",
                "L" + BLOCK + ";"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STATE, "is", "(L" + BLOCK + ";)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, no));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new TypeInsnNode(Opcodes.INSTANCEOF, CAMPFIRE));
        if (castAfter) {
            LabelNode ret = new LabelNode();
            il.add(new JumpInsnNode(Opcodes.IFEQ, no));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, CAMPFIRE));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new JumpInsnNode(Opcodes.GOTO, ret));
            il.add(no);
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(ret);
            il.add(new InsnNode(Opcodes.IRETURN));
        } else {
            il.add(new InsnNode(Opcodes.IRETURN));
            il.add(no);
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new InsnNode(Opcodes.IRETURN));
        }
        m.maxStack = 2;
        m.maxLocals = 2;
        cn.methods.add(m);
        return cn;
    }

    private static MethodNode method(ClassNode cn, String name) {
        return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static boolean hasCall(MethodNode m, String owner, String name) {
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInstanceof(MethodNode m) {
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() == Opcodes.INSTANCEOF) {
                return true;
            }
        }
        return false;
    }

    private static List<Object> ldcs(MethodNode m) {
        List<Object> out = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof LdcInsnNode l) {
                out.add(l.cst);
            }
        }
        return out;
    }

    @Test
    void rewritesBothSites() throws Exception {
        ClassNode cn = fake("check", false);
        assertEquals(2, CheckSiteRewriter.rewrite(cn, bothSafe()));
        MethodNode m = method(cn, "check");
        // 0.7.1: Towntopia's checks stay (another mod's mixin may hold them); the bridge follows each one
        assertTrue(hasCall(m, STATE, "is"));
        assertTrue(hasInstanceof(m));
        assertTrue(hasCall(m, BRIDGE, "afterIs"));
        assertTrue(hasCall(m, BRIDGE, "afterInstanceOf"));
        // site ids are row indexes: CRAFTING_TABLE is row 1, CampfireBlock row 2
        assertEquals(List.of(1, Type.getObjectType(CAMPFIRE), 2), ldcs(m));
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(BRIDGE)) {
                assertEquals(Opcodes.INVOKESTATIC, mi.getOpcode());
                assertEquals(mi.name.equals("afterIs") ? "(L" + STATE + ";L" + BLOCK + ";ZI)Z"
                        : "(Ljava/lang/Object;ZLjava/lang/Class;I)Z", mi.desc);
            } else if (in instanceof MethodInsnNode mi && mi.owner.equals(STATE)) {
                // s, b duplicated in front; the site id and the bridge right after
                assertEquals(Opcodes.DUP2, mi.getPrevious().getOpcode());
                assertEquals(1, ((LdcInsnNode) mi.getNext()).cst);
                assertEquals("afterIs", ((MethodInsnNode) mi.getNext().getNext()).name);
            } else if (in.getOpcode() == Opcodes.INSTANCEOF) {
                assertEquals(Opcodes.DUP, in.getPrevious().getOpcode());
                assertEquals(Type.getObjectType(CAMPFIRE), ((LdcInsnNode) in.getNext()).cst);
                assertEquals("afterInstanceOf", ((MethodInsnNode) in.getNext().getNext().getNext()).name);
            }
        }
    }

    /** A second pass over a rewritten class changes nothing (Towntopia's checks are still there to be found). */
    @Test
    void secondPassChangesNothing() throws Exception {
        ClassNode cn = fake("check", false);
        assertEquals(2, CheckSiteRewriter.rewrite(cn, bothSafe()));
        int size = method(cn, "check").instructions.size();
        List<Integer> ids = new ArrayList<>();
        assertEquals(0, CheckSiteRewriter.rewrite(cn, bothSafe(), ids::add));
        assertTrue(ids.isEmpty());
        assertEquals(size, method(cn, "check").instructions.size());
    }

    @Test
    void leavesUnknownSitesAlone() throws Exception {
        ClassNode cn = fake("check", false);
        SiteVerdicts v = verdicts(row("check" + DESC, "BLOCK_IS", "STONE", "SAFE")
                + row("check(I)V", "INSTANCEOF", CAMPFIRE, "SAFE"));
        assertEquals(0, CheckSiteRewriter.rewrite(cn, v));
        MethodNode m = method(cn, "check");
        assertTrue(hasCall(m, STATE, "is"));
        assertTrue(hasInstanceof(m));
        assertFalse(hasCall(m, BRIDGE, "afterIs"));
        assertTrue(ldcs(m).isEmpty());
    }

    @Test
    void leavesUnsafeAndAuditOnlySitesAlone() throws Exception {
        ClassNode cn = fake("check", false);
        SiteVerdicts v = verdicts(row("check" + DESC, "BLOCK_IS", "CRAFTING_TABLE", "UNSAFE")
                + row("check" + DESC, "INSTANCEOF", CAMPFIRE, "AUDIT_ONLY"));
        assertEquals(0, CheckSiteRewriter.rewrite(cn, v));
        assertTrue(hasCall(method(cn, "check"), STATE, "is"));
        assertTrue(hasInstanceof(method(cn, "check")));
    }

    @Test
    void rewritesSplitBodies() throws Exception {
        ClassNode cn = fake("townkit$check", false);
        assertEquals(2, CheckSiteRewriter.rewrite(cn, bothSafe()));
        assertTrue(hasCall(method(cn, "townkit$check"), BRIDGE, "afterIs"));
    }

    @Test
    void hookedPathIsNotABody() throws Exception {
        ClassNode cn = fake("townkit$hooked$check", false);
        assertEquals(0, CheckSiteRewriter.rewrite(cn, bothSafe()));
    }

    /** A pattern binding casts after the test: a stand-in passing the test would throw there, so it stays. */
    @Test
    void instanceofFollowedByCastStaysAlone() throws Exception {
        ClassNode cn = fake("check", true);
        assertEquals(1, CheckSiteRewriter.rewrite(cn, bothSafe()));
        MethodNode m = method(cn, "check");
        assertTrue(hasInstanceof(m));
        assertTrue(hasCall(m, BRIDGE, "afterIs"));
        assertFalse(hasCall(m, BRIDGE, "afterInstanceOf"));
    }

    @Test
    void failureLeavesClassUntouched() throws Exception {
        ClassNode cn = fake("check", false);
        MethodNode broken = new MethodNode(Opcodes.ACC_PUBLIC, null, "()V", null, null);
        broken.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(broken);
        assertEquals(0, CheckSiteRewriter.rewrite(cn, bothSafe()));
        assertTrue(hasCall(method(cn, "check"), STATE, "is"));
        assertTrue(hasInstanceof(method(cn, "check")));
    }

    @Test
    void verifiesAfterRewrite() throws Exception {
        for (boolean cast : new boolean[] {false, true}) {
            ClassNode cn = fake("check", cast);
            CheckSiteRewriter.rewrite(cn, bothSafe());
            assertVerifies(cn, true);
        }
    }

    /**
     * Sites share a key per method, so a Towntopia update adding a second check of the same
     * block to an audited method would inherit the first one's verdict. When the method's count differs from the
     * file's, every site of that method stays as Towntopia ships it.
     */
    @Test
    void countMismatchLeavesTheMethodAlone() throws Exception {
        ClassNode cn = fake("check", false);
        SiteVerdicts v = verdicts(row("check" + DESC, "BLOCK_IS", "CRAFTING_TABLE", "SAFE", 2)
                + row("check" + DESC, "INSTANCEOF", CAMPFIRE, "SAFE", 1));
        List<Integer> ids = new ArrayList<>();
        assertEquals(0, CheckSiteRewriter.rewrite(cn, v, ids::add));
        assertTrue(ids.isEmpty());
        MethodNode m = method(cn, "check");
        assertTrue(hasCall(m, STATE, "is"));
        assertTrue(hasInstanceof(m));
    }

    @Test
    void matchingCountsRewrite() throws Exception {
        ClassNode cn = fake("townkit$check", false);
        SiteVerdicts v = verdicts(row("check" + DESC, "BLOCK_IS", "CRAFTING_TABLE", "SAFE", 1)
                + row("check" + DESC, "INSTANCEOF", CAMPFIRE, "SAFE", 1));
        assertEquals(2, CheckSiteRewriter.rewrite(cn, v));
    }

    /** ASM's verifier always; the JVM's too when {@code link} (the class is initialized, so only synthetic ones). */
    private static void assertVerifies(ClassNode cn, boolean link) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cn.accept(cw);
        byte[] bytes = cw.toByteArray();
        ClassLoader parent = CheckSiteRewriterTest.class.getClassLoader();
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), parent, false, new PrintWriter(sw));
        assertTrue(sw.toString().isEmpty(), cn.name + ": " + sw);
        if (!link) {
            return;
        }
        // the JVM's own verifier runs when the class is linked
        ClassLoader throwaway = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(cn.name.replace('/', '.'))) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> c = Class.forName(cn.name.replace('/', '.'), true, throwaway);
        assertEquals(throwaway, c.getClassLoader());
    }

    /**
     * Every SAFE/NEEDS BLOCK_IS and INSTANCEOF row of the shipped verdict file is found in Towntopia's jar, and every
     * rewritten scoped class still verifies.
     */
    @Test
    void shippedVerdictsMatchTheJar() throws Exception {
        SiteVerdicts v = SiteVerdicts.load();
        TreeSet<Integer> expected = new TreeSet<>();
        for (int i = 0; i < v.size(); i++) {
            String key = v.key(i);
            SiteVerdicts.Type t = v.verdict(i).type();
            if ((t == SiteVerdicts.Type.SAFE || t == SiteVerdicts.Type.NEEDS) && !key.contains("#BLOCK_EQUALS#")) {
                expected.add(i);
            }
        }
        TreeSet<Integer> found = new TreeSet<>();
        int rewritten = 0;
        try (ZipFile zip = new ZipFile(TestJars.require(Path.of("libs", "towntopia-0.1.2.jar")).toFile())) {
            for (String owner : v.scopedOwners()) {
                ClassNode cn = new ClassNode();
                try (InputStream in = zip.getInputStream(zip.getEntry("com/alex/test/" + owner + ".class"))) {
                    new ClassReader(in).accept(cn, 0);
                }
                rewritten += CheckSiteRewriter.rewrite(cn, v, found::add);
                assertVerifies(cn, false);
            }
        }
        TreeSet<String> missing = new TreeSet<>();
        for (int id : expected) {
            if (!found.contains(id)) {
                missing.add(v.key(id));
            }
        }
        assertTrue(missing.isEmpty(), "rows never matched: " + missing);
        assertTrue(expected.containsAll(found), "rewrote a site that is not SAFE/NEEDS");
        assertTrue(rewritten >= expected.size());
        System.out.println("[CheckSiteRewriterTest] shipped jar: " + found.size() + " sites, " + rewritten
                + " instructions rewritten");
    }
}
