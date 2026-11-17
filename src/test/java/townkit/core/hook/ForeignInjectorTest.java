package townkit.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;
import townkit.core.building.CheckSiteRewriter;
import townkit.core.building.SiteVerdicts;
import townkit.core.nav.NavSiteRewriter;
import townkit.core.nav.NavSites;
import townkit.core.work.WorkSiteRewriter;
import townkit.core.work.WorkSites;

/**
 * Another mod's mixin may aim at the same instruction TownKit rewrites. Mixin (and MixinExtras' late
 * injectors such as {@code @WrapOperation}) find their target instruction before TownKit's plugin {@code postApply}
 * runs and edit it afterwards. If a rewriter had removed that node, the later edit ({@code InsnList.set} on a node no
 * longer in the list) drops the whole method body and the class fails to verify (the TownTopia Integrations crash).
 * These tests capture the target node first, run all three rewriters as the mixin plugin does, then make the foreign
 * edit and check the method is whole and the class verifies.
 */
class ForeignInjectorTest {
    private static final Path JAR = Path.of("libs", "towntopia-0.1.3.jar");
    private static final String PREFIX = "com/alex/test/";
    private static final String SMITH = "village/villagers/blacksmith/behavior/BlacksmithActivity";
    private static final String BLAST = "net/minecraft/world/level/block/BlastFurnaceBlock";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String NODE = PREFIX + "village/NavNode";
    private static final String STAND_INS = "townkit/core/building/StandIns";
    private static final String NAV_BRIDGE = "townkit/core/nav/NavBridge";
    private static final String COOK_OVERLAY = "townkit/core/work/cook/CookOverlay";

    private static ClassNode load(ZipFile zip, String owner) throws Exception {
        ZipEntry e = zip.getEntry(PREFIX + owner + ".class");
        assertNotNull(e, owner + " not in the jar");
        ClassNode cn = new ClassNode();
        try (InputStream in = zip.getInputStream(e)) {
            new ClassReader(in).accept(cn, 0);
        }
        return cn;
    }

    private static ClassNode load(String owner) throws Exception {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            return load(zip, owner);
        }
    }

    /** The three rewrites, in the mixin plugin's order. */
    private static void rewriteAll(ClassNode cn) {
        CheckSiteRewriter.rewrite(cn, SiteVerdicts.load());
        NavSiteRewriter.rewrite(cn, NavSites.load());
        WorkSiteRewriter.rewrite(cn, WorkSites.load(), id -> { });
    }

    private static MethodNode method(ClassNode cn, String name) {
        return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static AbstractInsnNode find(MethodNode m, Predicate<AbstractInsnNode> p) {
        for (AbstractInsnNode in : m.instructions) {
            if (p.test(in)) {
                return in;
            }
        }
        throw new AssertionError("no such instruction in " + m.name);
    }

    /** A static call with the same stack effect as {@code in}, as a wrap or redirect handler would be. */
    private static MethodInsnNode handlerFor(AbstractInsnNode in) {
        String desc;
        if (in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.INSTANCEOF) {
            desc = "(Ljava/lang/Object;)Z";
        } else if (in instanceof MethodInsnNode mi) {
            desc = mi.getOpcode() == Opcodes.INVOKESTATIC ? mi.desc
                    : "(L" + mi.owner + ";" + mi.desc.substring(1);
        } else if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC) {
            desc = "()" + fi.desc;
        } else {
            throw new AssertionError("no handler shape for " + in);
        }
        return new MethodInsnNode(Opcodes.INVOKESTATIC, "foreign/ClashMixin", "handler", desc, false);
    }

    /**
     * Captures the instruction {@code pick} names, rewrites the class, then replaces that instruction with a handler
     * call the way a foreign {@code @WrapOperation}/{@code @Redirect} would ({@code InsnList.set}). The method must stay
     * whole and the class must verify. {@code bridge} is the TownKit class whose call the rewrite puts right after the
     * target (null for a site TownKit leaves alone): checked first, so the test can't pass because nothing was rewritten.
     */
    private static void foreignEditAfterRewrite(String owner, String methodName, Predicate<AbstractInsnNode> pick)
            throws Exception {
        foreignEditAfterRewrite(owner, methodName, pick, null);
    }

    private static void foreignEditAfterRewrite(String owner, String methodName, Predicate<AbstractInsnNode> pick,
            String bridge) throws Exception {
        ClassNode cn = load(owner);
        MethodNode m = method(cn, methodName);
        AbstractInsnNode target = find(m, pick);
        int sizeBefore = m.instructions.size();
        AbstractInsnNode first = m.instructions.getFirst();
        AbstractInsnNode last = m.instructions.getLast();
        rewriteAll(cn);
        assertTrue(contains(m.instructions, target), owner + "#" + methodName + ": the rewrite removed the instruction "
                + "another mod's mixin holds");
        if (bridge != null) {
            assertTrue(bridgeCallFollows(target, bridge), owner + "#" + methodName + ": no call to " + bridge
                    + " right after the target: the site wasn't rewritten, so this test would pass for nothing; calls after it: "
                    + callsAfter(target));
        }
        m.instructions.set(target, handlerFor(target));
        assertIntact(m.instructions);
        assertSame(first, m.instructions.getFirst(), "method start lost");
        assertSame(last, m.instructions.getLast(), "method end lost");
        assertTrue(m.instructions.size() >= sizeBefore, "instructions lost");
        assertVerifies(cn);
    }

    /** Whether a static call to {@code bridge} is among the few real instructions right after {@code target}. */
    private static boolean bridgeCallFollows(AbstractInsnNode target, String bridge) {
        int seen = 0;
        for (AbstractInsnNode in = target.getNext(); in != null && seen < 6; in = in.getNext()) {
            if (in.getOpcode() < 0) {
                continue; // labels, line numbers, frames
            }
            seen++;
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && mi.owner.equals(bridge)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> callsAfter(AbstractInsnNode target) {
        List<String> out = new ArrayList<>();
        int seen = 0;
        for (AbstractInsnNode in = target.getNext(); in != null && seen < 6; in = in.getNext()) {
            if (in.getOpcode() < 0) {
                continue;
            }
            seen++;
            if (in instanceof MethodInsnNode mi) {
                out.add(mi.owner + "." + mi.name);
            }
        }
        return out;
    }

    private static boolean contains(InsnList il, AbstractInsnNode node) {
        for (AbstractInsnNode in = il.getFirst(); in != null; in = in.getNext()) {
            if (in == node) {
                return true;
            }
        }
        return false;
    }

    /** Forward and backward links agree and match {@code size()}. */
    private static void assertIntact(InsnList il) {
        int n = 0;
        AbstractInsnNode prev = null;
        for (AbstractInsnNode in = il.getFirst(); in != null; in = in.getNext()) {
            assertSame(prev, in.getPrevious(), "broken back link");
            prev = in;
            n++;
        }
        assertSame(prev, il.getLast());
        assertEquals(il.size(), n, "list size and links disagree");
    }

    private static void assertVerifies(ClassNode cn) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                try {
                    return super.getCommonSuperClass(a, b);
                } catch (Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
        cn.accept(cw);
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(cw.toByteArray()), ForeignInjectorTest.class.getClassLoader(), false,
                new PrintWriter(sw));
        assertTrue(sw.toString().isEmpty(), cn.name + ": " + sw);
    }

    private static boolean instanceOfBlast(AbstractInsnNode in) {
        return in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.INSTANCEOF && t.desc.equals(BLAST);
    }

    /** Integrations' BlacksmithBlastMixin: {@code @WrapOperation} of {@code instanceof BlastFurnaceBlock}. */
    @Test
    void wrapOfBeginStationWorkInstanceof() throws Exception {
        foreignEditAfterRewrite(SMITH, "beginStationWork", ForeignInjectorTest::instanceOfBlast, STAND_INS);
    }

    /** Integrations' BlacksmithOfflineBlastMixin: the same in the away catch-up. */
    @Test
    void wrapOfOfflineSmeltTicksInstanceof() throws Exception {
        foreignEditAfterRewrite(SMITH + "$Offline", "smeltTicks", ForeignInjectorTest::instanceOfBlast, STAND_INS);
    }

    /** Integrations' FletchTableMixin: {@code @Redirect} of {@code BlockState.is(Blocks.CRAFTING_TABLE)}. */
    @Test
    void redirectOfFletchWorkAnimationIs() throws Exception {
        foreignEditAfterRewrite("village/villagers/archer/behavior/FletchArrowsActivity", "workAnimation",
                in -> in instanceof MethodInsnNode mi && mi.owner.equals(STATE) && mi.name.equals("is")
                        && mi.desc.equals("(L" + BLOCK + ";)Z") && in.getPrevious() instanceof FieldInsnNode f
                        && f.name.equals("CRAFTING_TABLE"), STAND_INS);
    }

    /** A nav STEP site: {@code canStepTo} in the route search. */
    @Test
    void redirectOfSearchCanStepTo() throws Exception {
        foreignEditAfterRewrite("village/AStarPathfinder", "search",
                in -> in instanceof MethodInsnNode mi && mi.owner.equals(NODE) && mi.name.equals("canStepTo"), NAV_BRIDGE);
    }

    /** A work site: the cook's {@code RECIPES} read. */
    @Test
    void wrapOfCookRecipesRead() throws Exception {
        foreignEditAfterRewrite("village/villagers/cook/behavior/CookActivity", "recipes",
                in -> in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.name.equals("RECIPES"), COOK_OVERLAY);
    }

    /**
     * The rule itself, for every class any of the three rewriters touches: after the rewrite every original
     * instruction node is still in its method, in its original order (insertions only).
     */
    @Test
    void rewritersNeverRemoveAnOriginalInstruction() throws Exception {
        TreeSet<String> owners = new TreeSet<>(SiteVerdicts.load().scopedOwners());
        owners.addAll(NavSites.load().rewriteOwners());
        owners.addAll(WorkSites.load().rewriteOwners());
        List<String> problems = new ArrayList<>();
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            for (String owner : owners) {
                if (zip.getEntry(PREFIX + owner + ".class") == null) {
                    continue;
                }
                ClassNode cn = load(zip, owner);
                Map<MethodNode, AbstractInsnNode[]> before = new IdentityHashMap<>();
                for (MethodNode m : cn.methods) {
                    before.put(m, m.instructions.toArray());
                }
                rewriteAll(cn);
                before.forEach((m, orig) -> {
                    AbstractInsnNode in = m.instructions.getFirst();
                    for (AbstractInsnNode o : orig) {
                        while (in != null && in != o) {
                            in = in.getNext();
                        }
                        if (in == null) {
                            problems.add(owner + "#" + m.name + m.desc + ": lost " + describe(o));
                            return;
                        }
                    }
                    assertIntact(m.instructions);
                });
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " methods lost an original instruction:\n"
                + String.join("\n", problems));
    }

    private static String describe(AbstractInsnNode in) {
        if (in instanceof MethodInsnNode mi) {
            return mi.owner + "." + mi.name + mi.desc;
        }
        if (in instanceof TypeInsnNode t) {
            return "opcode " + t.getOpcode() + " " + t.desc;
        }
        return "opcode " + in.getOpcode() + " " + in.getClass().getSimpleName();
    }
}
