package townkit.core.nav;

import townkit.core.TestJars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class NavSiteRewriterTest {
    private static final Path JAR_FILE = Path.of("libs", "towntopia-0.1.2.jar");

    /** The 0.1.2 jar the audit reads; the test is skipped when it isn't in libs/. */
    private static Path jar() {
        return TestJars.require(JAR_FILE);
    }
    private static final String PREFIX = "com/alex/test/";
    private static final String BRIDGE = "townkit/core/nav/NavBridge";
    private static final String NODE = PREFIX + "village/NavNode";
    private static final String ASTAR = "village/AStarPathfinder";
    /**
     * The kinds rewritten (every kind).
     */
    private static final Set<NavSite.Kind> REWRITTEN_NOW = EnumSet.of(NavSite.Kind.STEP, NavSite.Kind.COST,
            NavSite.Kind.LOS, NavSite.Kind.CELL, NavSite.Kind.PACE, NavSite.Kind.POLL);

    private static ClassNode load(ZipFile zip, String owner) throws IOException {
        ClassNode cn = new ClassNode();
        try (InputStream in = zip.getInputStream(zip.getEntry(PREFIX + owner + ".class"))) {
            new ClassReader(in).accept(cn, 0);
        }
        return cn;
    }

    private static ClassNode load(String owner) throws IOException {
        try (ZipFile zip = new ZipFile(jar().toFile())) {
            return load(zip, owner);
        }
    }

    private static int bridgeCalls(ClassNode cn) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof MethodInsnNode mi && mi.owner.equals(BRIDGE)) {
                    assertEquals(Opcodes.INVOKESTATIC, mi.getOpcode());
                    n++;
                }
            }
        }
        return n;
    }

    private static MethodNode method(ClassNode cn, String nameStart) {
        return cn.methods.stream().filter(m -> (m.name + m.desc).startsWith(nameStart)).findFirst().orElseThrow();
    }

    /** Owner -> expected rewritten instruction count, from the tsv (REWRITE rows of the kinds this task rewrites). */
    private static Map<String, Integer> expected(NavSites sites) {
        Map<String, Integer> out = new TreeMap<>();
        for (int id = 0; id < sites.size(); id++) {
            if (sites.rewrite(id) && REWRITTEN_NOW.contains(sites.kind(id))) {
                out.merge(sites.owner(id), sites.count(id), Integer::sum);
            }
        }
        return out;
    }

    @Test
    void rewritesEveryRewriteSiteInJar() throws Exception {
        NavSites sites = NavSites.load();
        Map<String, Integer> expected = expected(sites);
        assertEquals(7, expected.size(), expected.toString());
        Map<String, Integer> got = new TreeMap<>();
        BitSet all = new BitSet();
        try (ZipFile zip = new ZipFile(jar().toFile())) {
            for (String owner : expected.keySet()) {
                ClassNode cn = load(zip, owner);
                BitSet ids = new BitSet();
                int n = NavSiteRewriter.rewrite(cn, sites, ids::set);
                got.put(owner, n);
                assertEquals(n, bridgeCalls(cn), owner);
                all.or(ids);
                // 0.7.1: every STEP bridge call follows Towntopia's own canStepTo, which stays, its operands kept
                // by a DUP2 in front (another mod's mixin may hold that call)
                for (MethodNode m : cn.methods) {
                    for (AbstractInsnNode in : m.instructions) {
                        if (in instanceof MethodInsnNode mi && mi.owner.equals(BRIDGE)
                                && (mi.name.equals("afterStep") || mi.name.equals("afterSearchStep"))) {
                            AbstractInsnNode orig = mi.getPrevious().getPrevious();
                            if (mi.name.equals("afterSearchStep")) {
                                orig = orig.getPrevious().getPrevious();
                            }
                            assertTrue(orig instanceof MethodInsnNode o && o.owner.equals(NODE)
                                    && o.name.equals("canStepTo"), owner + "#" + m.name + ": " + orig);
                            assertEquals(Opcodes.DUP2, orig.getPrevious().getOpcode(), owner + "#" + m.name);
                        }
                    }
                }
            }
        }
        assertEquals(expected, got);
        for (int id = 0; id < sites.size(); id++) {
            boolean want = sites.rewrite(id) && REWRITTEN_NOW.contains(sites.kind(id));
            assertEquals(want, all.get(id), sites.key(id));
        }
        System.out.println("[NavSiteRewriterTest] rewritten per owner: " + got);
    }

    /** A second pass over an already rewritten class changes nothing (COST, LOS, PACE, CELL included). */
    @Test
    void rewriteIsIdempotent() throws Exception {
        NavSites sites = NavSites.load();
        try (ZipFile zip = new ZipFile(jar().toFile())) {
            for (String owner : expected(sites).keySet()) {
                ClassNode cn = load(zip, owner);
                int first = NavSiteRewriter.rewrite(cn, sites);
                assertTrue(first > 0, owner);
                int calls = bridgeCalls(cn);
                BitSet again = new BitSet();
                assertEquals(0, NavSiteRewriter.rewrite(cn, sites, again::set), owner);
                assertTrue(again.isEmpty(), owner);
                assertEquals(calls, bridgeCalls(cn), owner);
            }
        }
    }

    @Test
    void rewrittenClassesVerify() throws Exception {
        NavSites sites = NavSites.load();
        try (ZipFile zip = new ZipFile(jar().toFile())) {
            for (String owner : expected(sites).keySet()) {
                ClassNode cn = load(zip, owner);
                assertTrue(NavSiteRewriter.rewrite(cn, sites) > 0, owner);
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
                byte[] bytes = cw.toByteArray();
                String name = cn.name.replace('/', '.');
                ClassLoader parent = NavSiteRewriterTest.class.getClassLoader();
                ClassLoader throwaway = new ClassLoader(parent) {
                    @Override
                    protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                        if (n.equals(name)) {
                            synchronized (this) {
                                Class<?> c = findLoadedClass(n);
                                return c != null ? c : defineClass(n, bytes, 0, bytes.length);
                            }
                        }
                        return super.loadClass(n, resolve);
                    }
                };
                Class<?> c = Class.forName(name, false, throwaway);
                assertEquals(throwaway, c.getClassLoader());
                // links the class: the JVM verifier runs here
                assertTrue(c.getDeclaredMethods().length > 0, owner);
            }
        }
    }

    @Test
    void keepSitesUntouched() throws Exception {
        NavSites sites = NavSites.load();
        List<String> keepOnly = new ArrayList<>();
        for (int id = 0; id < sites.size(); id++) {
            if (!sites.rewrite(id) && !sites.rewriteOwners().contains(sites.owner(id))) {
                keepOnly.add(sites.owner(id));
            }
        }
        assertTrue(keepOnly.contains("command/VillagerCommands") && keepOnly.contains("village/stuckwatch/StuckReport"),
                keepOnly.toString());
        for (String owner : keepOnly) {
            ClassNode cn = load(owner);
            int before = NavSiteScanner.scan(cn).size();
            assertEquals(0, NavSiteRewriter.rewrite(cn, sites), owner);
            assertEquals(before, NavSiteScanner.scan(cn).size(), owner);
            assertEquals(0, bridgeCalls(cn), owner);
        }
        // inside rewritten owners Towntopia's setWantedPosition calls (PACE only wraps the speed in front) and
        // NavGraph's poll() (POLL only hands the polled node through the bridge after it) stay
        for (String owner : List.of("village/FollowWalk", "village/NavGraph")) {
            ClassNode cn = load(owner);
            long pacePoll = NavSiteScanner.scan(cn).stream()
                    .filter(s -> s.kind() == NavSite.Kind.PACE || s.kind() == NavSite.Kind.POLL).count();
            assertTrue(pacePoll > 0, owner);
            NavSiteRewriter.rewrite(cn, sites);
            assertEquals(pacePoll, NavSiteScanner.scan(cn).stream()
                    .filter(s -> s.kind() == NavSite.Kind.PACE || s.kind() == NavSite.Kind.POLL).count(), owner);
        }
    }

    /** STEP inside search: goalNode (third NavNode parameter, slot 2) and rules (slot 8) are loaded, then the id. */
    @Test
    void searchStepLoadsGoalAndRules() throws Exception {
        ClassNode cn = load(ASTAR);
        NavSites sites = NavSites.load();
        NavSiteRewriter.rewrite(cn, sites);
        MethodNode search = method(cn, "search(");
        MethodInsnNode call = calls(search, "afterSearchStep").get(0);
        assertEquals("(L" + NODE + ";L" + NODE + ";ZL" + NODE + ";L" + PREFIX + "village/RouteRules;I)Z", call.desc);
        AbstractInsnNode id = call.getPrevious();
        assertEquals(sites.siteId(ASTAR + "#" + search.name + search.desc + "#STEP"), NavSiteRewriter.intConst(id));
        assertEquals(8, ((VarInsnNode) id.getPrevious()).var);
        assertEquals(2, ((VarInsnNode) id.getPrevious().getPrevious()).var);
        // Towntopia's canStepTo stays in front, its two operands duplicated before it
        AbstractInsnNode step = id.getPrevious().getPrevious().getPrevious();
        assertTrue(step instanceof MethodInsnNode mi && mi.owner.equals(NODE) && mi.name.equals("canStepTo"));
        assertEquals(Opcodes.DUP2, step.getPrevious().getOpcode());
    }

    /** COST: the original edgeCost still runs; then cur, nb and the rules (null in price) go to NavBridge.cost. */
    @Test
    void costKeepsTheOriginalAndPassesTheRules() throws Exception {
        ClassNode cn = load(ASTAR);
        NavSiteRewriter.rewrite(cn, NavSites.load());
        String costDesc = "(IL" + NODE + ";L" + NODE + ";L" + PREFIX + "village/RouteRules;)I";
        for (String m : List.of("search(", "price(")) {
            MethodNode mn = method(cn, m);
            MethodInsnNode cost = calls(mn, "cost").get(0);
            assertEquals(costDesc, cost.desc);
            AbstractInsnNode rules = cost.getPrevious();
            if (m.equals("price(")) {
                assertEquals(Opcodes.ACONST_NULL, rules.getOpcode());
            } else {
                assertEquals(8, ((VarInsnNode) rules).var);
            }
            AbstractInsnNode orig = rules.getPrevious().getPrevious().getPrevious();
            assertTrue(orig instanceof MethodInsnNode mi && mi.name.equals("edgeCost"), m);
        }
    }

    /** Smooth's lineOfSight runs, then NavBridge.lineOfSight(result, graph, from, to). */
    @Test
    void lineOfSightKeepsTheOriginal() throws Exception {
        ClassNode cn = load(ASTAR);
        NavSiteRewriter.rewrite(cn, NavSites.load());
        MethodInsnNode los = calls(method(cn, "smooth("), "lineOfSight").stream()
                .filter(mi -> mi.owner.equals(BRIDGE)).findFirst().orElseThrow();
        assertEquals("(ZL" + PREFIX + "village/NavGraph;L" + NODE + ";L" + NODE + ";)Z", los.desc);
        AbstractInsnNode orig = los.getPrevious().getPrevious().getPrevious().getPrevious();
        assertTrue(orig instanceof MethodInsnNode mi && mi.owner.equals(PREFIX + ASTAR)
                && mi.name.equals("lineOfSight"), String.valueOf(orig));
    }

    /**
     * The reachability adapter keeps Towntopia's canStepTo; every return of its kind(...) goes through
     * NavBridge.cellKind(result, from, to) instead (from = slot 1, to = slot 2).
     */
    @Test
    void reachabilityAdapterWrapsItsAnswer() throws Exception {
        ClassNode cn = load("village/NavCells$1");
        NavSites sites = NavSites.load();
        int n = NavSiteRewriter.rewrite(cn, sites);
        MethodNode kind = method(cn, "kind(L" + NODE + ";L" + NODE + ";J)I");
        int returns = 0;
        for (AbstractInsnNode in : kind.instructions) {
            if (in.getOpcode() == Opcodes.IRETURN) {
                returns++;
                MethodInsnNode call = (MethodInsnNode) in.getPrevious();
                assertEquals(BRIDGE, call.owner);
                assertEquals("cellKind", call.name);
                assertEquals("(IL" + NODE + ";L" + NODE + ";)I", call.desc);
                assertEquals(2, ((VarInsnNode) call.getPrevious()).var);
                assertEquals(1, ((VarInsnNode) call.getPrevious().getPrevious()).var);
            }
        }
        assertTrue(returns > 0);
        assertEquals(returns, n);
        List<MethodInsnNode> steps = calls(kind, "canStepTo");
        assertEquals(1, steps.size());
        assertEquals(NODE, steps.get(0).owner);
    }

    /**
     * Only FollowWalk#tickLine (the audited REWRITE row) gets NavBridge.pace, right before its
     * setWantedPosition, with the PathfinderMob parameter (slot 1) loaded on top of the speed. tickJoin (KEEP) and
     * every other rewritten owner get no pace call; Towntopia's setWantedPosition calls all stay.
     */
    @Test
    void paceSiteRewrittenOnlyInFollowWalk() throws Exception {
        NavSites sites = NavSites.load();
        String paceDesc = "(DLnet/minecraft/world/entity/PathfinderMob;)D";
        Map<String, Integer> paces = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar().toFile())) {
            for (String owner : sites.rewriteOwners()) {
                ClassNode cn = load(zip, owner);
                NavSiteRewriter.rewrite(cn, sites);
                for (MethodNode m : cn.methods) {
                    for (AbstractInsnNode in : m.instructions) {
                        if (in instanceof MethodInsnNode mi && mi.owner.equals(BRIDGE) && mi.name.equals("pace")) {
                            paces.merge(owner + "#" + m.name, 1, Integer::sum);
                            assertEquals(paceDesc, mi.desc);
                            assertEquals(Opcodes.ALOAD, mi.getPrevious().getOpcode());
                            assertEquals(1, ((VarInsnNode) mi.getPrevious()).var);
                            assertTrue(mi.getNext() instanceof MethodInsnNode next
                                    && next.name.equals("setWantedPosition") && next.desc.equals("(DDDD)V"),
                                    String.valueOf(mi.getNext()));
                        }
                    }
                }
            }
        }
        assertEquals(Map.of("village/FollowWalk#tickLine", 1), paces);
        ClassNode fw = load("village/FollowWalk");
        NavSiteRewriter.rewrite(fw, sites);
        assertEquals(1, calls(method(fw, "tickLine("), "setWantedPosition").size());
        assertEquals(1, calls(method(fw, "tickJoin("), "setWantedPosition").size());
        assertTrue(calls(method(fw, "tickJoin("), "pace").isEmpty());
    }

    /**
     * In floodFillInner (10 parameters) the poll() result goes through NavBridge.polled(node, queue, nodes,
     * level), loading the Queue (slot 2), Map (slot 1) and Level (slot 0) parameters; Towntopia's CHECKCAST follows.
     */
    @Test
    void pollHandsTheNodeThroughTheBridge() throws Exception {
        ClassNode cn = load("village/NavGraph");
        NavSiteRewriter.rewrite(cn, NavSites.load());
        MethodNode fill = cn.methods.stream().filter(m -> m.name.equals("floodFillInner") && m.desc.endsWith(")I"))
                .findFirst().orElseThrow();
        List<MethodInsnNode> polled = calls(fill, "polled");
        assertEquals(1, polled.size());
        MethodInsnNode call = polled.get(0);
        assertEquals(BRIDGE, call.owner);
        assertEquals("(Ljava/lang/Object;Ljava/util/Queue;Ljava/util/Map;Lnet/minecraft/world/level/Level;)Ljava/lang/Object;",
                call.desc);
        assertEquals(0, ((VarInsnNode) call.getPrevious()).var);
        assertEquals(1, ((VarInsnNode) call.getPrevious().getPrevious()).var);
        assertEquals(2, ((VarInsnNode) call.getPrevious().getPrevious().getPrevious()).var);
        AbstractInsnNode poll = call.getPrevious().getPrevious().getPrevious().getPrevious();
        assertTrue(poll instanceof MethodInsnNode mi && mi.name.equals("poll"), String.valueOf(poll));
        assertEquals(Opcodes.CHECKCAST, call.getNext().getOpcode());
        // the 9-parameter overload has no poll()
        MethodNode other = cn.methods.stream().filter(m -> m.name.equals("floodFillInner") && m.desc.endsWith(")V"))
                .findFirst().orElseThrow();
        assertTrue(calls(other, "polled").isEmpty());
    }

    private static List<MethodInsnNode> calls(MethodNode m, String name) {
        List<MethodInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi && mi.name.equals(name)
                    && (mi.owner.equals(BRIDGE) || !name.equals("cost"))) {
                out.add(mi);
            }
        }
        return out;
    }

    // ---- hand-made classes: fail soft, counts, split bodies ----

    private static final String FAKE = PREFIX + ASTAR;

    /** {@code static boolean <name><desc>} returning {@code a.canStepTo(b)} (a, b = the first two parameters). */
    private static MethodNode stepMethod(String name, String desc) {
        MethodNode m = new MethodNode(Opcodes.ACC_STATIC, name, desc, null, null);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NODE, "canStepTo", "(L" + NODE + ";)Z", false));
        m.instructions.add(new InsnNode(Opcodes.IRETURN));
        m.maxStack = 2;
        m.maxLocals = 4;
        return m;
    }

    private static ClassNode fake(MethodNode... methods) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = FAKE;
        cn.superName = "java/lang/Object";
        cn.methods.addAll(List.of(methods));
        return cn;
    }

    private static NavSites rows(Map<String, Integer> keyCounts) throws IOException {
        StringBuilder sb = new StringBuilder("# test\n");
        keyCounts.forEach((k, n) -> sb.append(k).append('\t').append(n).append("\tREWRITE\tnote\n"));
        return NavSites.parse(new StringReader(sb.toString()));
    }

    private static final String TWO = "(L" + NODE + ";L" + NODE + ";)Z";
    private static final String THREE = "(L" + NODE + ";L" + NODE + ";L" + NODE + ";)Z";

    /** A search without a RouteRules parameter can't be planned: the whole class, other methods too, stays as is. */
    @Test
    void failureLeavesClassUntouched() throws Exception {
        ClassNode cn = fake(stepMethod("other", TWO), stepMethod("search", THREE));
        Map<String, Integer> k = new LinkedHashMap<>();
        k.put(ASTAR + "#other" + TWO + "#STEP", 1);
        k.put(ASTAR + "#search" + THREE + "#STEP", 1);
        BitSet ids = new BitSet();
        assertEquals(0, NavSiteRewriter.rewrite(cn, rows(k), ids::set));
        assertTrue(ids.isEmpty());
        assertEquals(0, bridgeCalls(cn));
    }

    @Test
    void countMismatchLeavesTheMethodAlone() throws Exception {
        ClassNode cn = fake(stepMethod("other", TWO));
        assertEquals(0, NavSiteRewriter.rewrite(cn, rows(Map.of(ASTAR + "#other" + TWO + "#STEP", 2))));
        assertEquals(0, bridgeCalls(cn));
    }

    @Test
    void splitBodiesAreRewrittenUnderTheirName() throws Exception {
        ClassNode cn = fake(stepMethod("townkit$other", TWO), stepMethod("townkit$hooked$other", TWO));
        assertEquals(1, NavSiteRewriter.rewrite(cn, rows(Map.of(ASTAR + "#other" + TWO + "#STEP", 1))));
        assertEquals(1, bridgeCalls(cn));
        assertEquals(BRIDGE, calls(method(cn, "townkit$other"), "afterStep").get(0).owner);
        assertEquals(NODE, calls(method(cn, "townkit$other"), "canStepTo").get(0).owner);
    }

    @Test
    void unknownSitesStay() throws Exception {
        ClassNode cn = fake(stepMethod("other", TWO));
        assertEquals(0, NavSiteRewriter.rewrite(cn, rows(Map.of(ASTAR + "#elsewhere" + TWO + "#STEP", 1))));
    }

    // ---- the bridge's fast paths ----

    @Test
    void fastPathUnderInlineLimit() throws Exception {
        byte[] bytes;
        try (InputStream in = NavBridge.class.getResourceAsStream("NavBridge.class")) {
            assertNotNull(in);
            bytes = in.readAllBytes();
        }
        Map<String, Integer> sizes = codeSizes(bytes);
        Map<String, Integer> fast = new TreeMap<>();
        sizes.forEach((m, n) -> {
            String name = m.substring(0, m.indexOf('('));
            if (Set.of("afterStep", "afterSearchStep", "cost", "lineOfSight", "cellKind", "pace", "polled").contains(name)) {
                fast.put(m, n);
            }
        });
        assertEquals(7, fast.size(), fast.toString());
        fast.forEach((m, n) -> assertTrue(n <= 35, m + " is " + n + " bytes"));
        System.out.println("[NavSiteRewriterTest] fast path bytes: " + fast);
    }

    /** Every owner with a REWRITE row is handed to TownKit's mixin plugin (a carrier mixin targets it). */
    @Test
    void everyRewriteOwnerIsACarrierTarget() throws Exception {
        List<String> targets = new ArrayList<>();
        for (String carrier : List.of("HookedCommonClasses", "HookedCommonInterfaces")) {
            try (InputStream in = NavSiteRewriterTest.class.getResourceAsStream("/townkit/mixin/gen/" + carrier
                    + ".class")) {
                assertNotNull(in, carrier);
                ClassNode cn = new ClassNode();
                new ClassReader(in).accept(cn, ClassReader.SKIP_CODE);
                List<AnnotationNode> anns = new ArrayList<>();
                if (cn.invisibleAnnotations != null) {
                    anns.addAll(cn.invisibleAnnotations);
                }
                if (cn.visibleAnnotations != null) {
                    anns.addAll(cn.visibleAnnotations);
                }
                for (AnnotationNode a : anns) {
                    for (int i = 0; a.values != null && i < a.values.size(); i += 2) {
                        if (a.values.get(i).equals("targets")) {
                            for (Object t : (List<?>) a.values.get(i + 1)) {
                                targets.add((String) t);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(targets.size() > 100, "no targets read");
        List<String> missing = new ArrayList<>();
        for (String owner : NavSites.load().rewriteOwners()) {
            if (!targets.contains("com.alex.test." + owner.replace('/', '.'))) {
                missing.add(owner);
            }
        }
        assertEquals(List.of(), missing);
    }

    /** Method name + descriptor -> code length (ASM doesn't expose it); same parser as hookgen's CodeSize. */
    private static Map<String, Integer> codeSizes(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    i++;
                }
                case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.skipNBytes(6);
        in.skipNBytes(2L * in.readUnsignedShort());
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.skipNBytes(6);
            skipAttributes(in);
        }
        Map<String, Integer> out = new HashMap<>();
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String desc = utf8[in.readUnsignedShort()];
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                String attr = utf8[in.readUnsignedShort()];
                int len = in.readInt();
                if (attr.equals("Code")) {
                    in.skipNBytes(4);
                    out.put(name + desc, in.readInt());
                    in.skipNBytes(len - 8L);
                } else {
                    in.skipNBytes(len);
                }
            }
        }
        return out;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.skipNBytes(2);
            in.skipNBytes(in.readInt());
        }
    }
}
