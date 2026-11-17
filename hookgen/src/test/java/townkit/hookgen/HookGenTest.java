package townkit.hookgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class HookGenTest {
    private static ClassNode cls(String internalName, String... methodDescs) {
        ClassNode cn = new ClassNode();
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        cn.access = Opcodes.ACC_PUBLIC;
        int i = 0;
        for (String desc : methodDescs) {
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "m" + i++, desc, null, null);
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(m);
        }
        return cn;
    }

    @Test
    void ruleThatMatchesNothingIsReportedDead() {
        List<HookGen.Rule> rules = List.of(HookGen.parseRule("village.Foo#m0"), HookGen.parseRule("village.Gone#x"));
        ClassNode foo = cls("com/alex/test/village/Foo", "()V");
        HookGen.skipMethod(foo, foo.methods.get(0), rules);
        assertEquals(List.of("village.Gone#x"), HookGen.deadRules(rules));
    }

    @Test
    void threadRuleThatMarksAHookIsNotDead() {
        List<HookGen.Rule> rules = List.of(HookGen.parseRule("@worker village.Foo#m0"));
        assertEquals("worker", HookGen.threadHint("village.Foo", "m0", rules));
        assertEquals(List.of(), HookGen.deadRules(rules));
    }

    @Test
    void commentsAndBlankLinesAreNotRules() {
        assertNull(HookGen.parseRule("# a comment"));
        assertNull(HookGen.parseRule("   "));
    }

    @Test
    void classNamingClientTypesInItsSignaturesIsClient() {
        ClassNode c = cls("com/alex/test/Boot", "(Lnet/minecraft/client/gui/screens/Screen;)V");
        assertEquals("client signature", HookGen.clientReason(c, Map.of(c.name, c)));
    }

    @Test
    void plainCommonClassIsNotClient() {
        ClassNode c = cls("com/alex/test/village/Plain", "(Lnet/minecraft/server/level/ServerLevel;)V");
        assertNull(HookGen.clientReason(c, Map.of(c.name, c)));
    }

    /**
     * {@code @carrier Class}: a class with no hooks (an anonymous class, say) is still handed to the mixin plugin, so
     * its nav sites can be rewritten. A class that already has hooks isn't listed twice.
     */
    @Test
    void carrierRuleTargetsAClassWithoutHooks() {
        List<HookGen.Rule> rules = List.of(HookGen.parseRule("@carrier village.NavCells$1"),
            HookGen.parseRule("@carrier village.Hooked"), HookGen.parseRule("@carrier village.Gone"));
        assertEquals("carrier", rules.get(0).kind());
        ClassNode anon = cls("com/alex/test/village/NavCells$1", "()V");
        ClassNode hooked = cls("com/alex/test/village/Hooked", "()V");
        Map<String, ClassNode> classes = Map.of(anon.name, anon, hooked.name, hooked);
        assertEquals(List.of("com/alex/test/village/NavCells$1"), HookGen.carrierOnly(classes, java.util.Set.of(hooked.name), rules));
        assertEquals(List.of("@carrier village.Gone"), HookGen.deadRules(rules));
        // a carrier rule is not an exclusion
        assertNull(HookGen.skipMethod(hooked, hooked.methods.get(0), rules));
    }

    @Test
    void clientPackageIsClient() {
        ClassNode c = cls("com/alex/test/client/Thing", "()V");
        assertEquals("client package", HookGen.clientReason(c, Map.of(c.name, c)));
    }

    // ---- 0.6.1 trim categories ----

    private static MethodNode method(int access, String name, String desc, AbstractInsnNode... insns) {
        MethodNode m = new MethodNode(access, name, desc, null, null);
        for (AbstractInsnNode in : insns) m.instructions.add(in);
        return m;
    }

    private static ClassNode emptyClass(String internalName, int access, String superName) {
        ClassNode cn = new ClassNode();
        cn.name = internalName;
        cn.superName = superName;
        cn.access = access;
        return cn;
    }

    /** A body that is not a getter, setter or field store: it calls something. */
    private static AbstractInsnNode[] callingBody(String desc) {
        AbstractInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/alex/test/Util", "work", "()V", false);
        return switch (org.objectweb.asm.Type.getReturnType(desc).getSort()) {
            case org.objectweb.asm.Type.VOID -> new AbstractInsnNode[]{call, new InsnNode(Opcodes.RETURN)};
            case org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.ARRAY ->
                new AbstractInsnNode[]{call, new InsnNode(Opcodes.ACONST_NULL), new InsnNode(Opcodes.ARETURN)};
            default -> new AbstractInsnNode[]{call, new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IRETURN)};
        };
    }

    private static List<String> keys(ClassNode cn, List<HookGen.Rule> rules) {
        return HookGen.planClass(cn, rules, Set.of(), new TreeMap<>()).stream().map(HookGen.Planned::key).toList();
    }

    @Test
    void privateMethodIsTrimmed() {
        ClassNode c = emptyClass("com/alex/test/village/Foo", Opcodes.ACC_PUBLIC, "java/lang/Object");
        MethodNode m = method(Opcodes.ACC_PRIVATE, "helper", "()V", callingBody("()V"));
        MethodNode ctor = method(Opcodes.ACC_PRIVATE, "<init>", "()V", new VarInsnNode(Opcodes.ALOAD, 0),
            new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false), callingBody("()V")[0],
            new InsnNode(Opcodes.RETURN));
        assertEquals("private method", HookGen.trimReason(c, m));
        assertEquals("private method", HookGen.trimReason(c, ctor));
    }

    @Test
    void plainGetterAndSetterAreTrimmed() {
        String owner = "com/alex/test/village/Foo";
        ClassNode c = emptyClass(owner, Opcodes.ACC_PUBLIC, "java/lang/Object");
        LabelNode l = new LabelNode();
        MethodNode getter = method(Opcodes.ACC_PUBLIC, "x", "()I", l, new LineNumberNode(10, l), new VarInsnNode(Opcodes.ALOAD, 0),
            new FieldInsnNode(Opcodes.GETFIELD, owner, "x", "I"), new InsnNode(Opcodes.IRETURN));
        MethodNode setter = method(Opcodes.ACC_PUBLIC, "setX", "(I)V", new VarInsnNode(Opcodes.ALOAD, 0),
            new VarInsnNode(Opcodes.ILOAD, 1), new FieldInsnNode(Opcodes.PUTFIELD, owner, "x", "I"), new InsnNode(Opcodes.RETURN));
        MethodNode computed = method(Opcodes.ACC_PUBLIC, "y", "()I", new VarInsnNode(Opcodes.ALOAD, 0),
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "compute", "()I", false), new InsnNode(Opcodes.IRETURN));
        MethodNode plusOne = method(Opcodes.ACC_PUBLIC, "z", "()I", new VarInsnNode(Opcodes.ALOAD, 0),
            new FieldInsnNode(Opcodes.GETFIELD, owner, "x", "I"), new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IADD),
            new InsnNode(Opcodes.IRETURN));
        assertEquals("plain getter/setter", HookGen.trimReason(c, getter));
        assertEquals("plain getter/setter", HookGen.trimReason(c, setter));
        assertNull(HookGen.trimReason(c, computed));
        assertNull(HookGen.trimReason(c, plusOne));
    }

    private static MethodNode storingCtor(String owner, String superName, boolean callsMethod) {
        List<AbstractInsnNode> body = new java.util.ArrayList<>(List.of(new VarInsnNode(Opcodes.ALOAD, 0),
            new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false),
            new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ILOAD, 1), new FieldInsnNode(Opcodes.PUTFIELD, owner, "a", "I"),
            new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 2)));
        if (callsMethod) {
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            body.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        }
        body.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "b", "Ljava/lang/String;"));
        body.add(new InsnNode(Opcodes.RETURN));
        return method(Opcodes.ACC_PUBLIC, "<init>", "(ILjava/lang/String;)V", body.toArray(new AbstractInsnNode[0]));
    }

    @Test
    void recordAndValueClassConstructorsAreTrimmed() {
        ClassNode rec = emptyClass("com/alex/test/village/Rec", Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD, "java/lang/Record");
        ClassNode value = emptyClass("com/alex/test/village/Value", Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "java/lang/Object");
        ClassNode open = emptyClass("com/alex/test/village/Open", Opcodes.ACC_PUBLIC, "java/lang/Object");
        assertEquals("data-class constructor", HookGen.trimReason(rec, storingCtor(rec.name, "java/lang/Record", false)));
        assertEquals("data-class constructor", HookGen.trimReason(value, storingCtor(value.name, "java/lang/Object", false)));
        assertNull(HookGen.trimReason(rec, storingCtor(rec.name, "java/lang/Record", true)));
        assertNull(HookGen.trimReason(value, storingCtor(value.name, "java/lang/Object", true)));
        // a class that can be subclassed is not a plain value holder: kept (when unsure, keep the hook)
        assertNull(HookGen.trimReason(open, storingCtor(open.name, "java/lang/Object", false)));
    }

    @Test
    void finalClassWithBehaviourKeepsItsConstructorHook() {
        String owner = "com/alex/test/ui/screen/Panel";
        ClassNode panel = emptyClass(owner, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "java/lang/Object");
        panel.methods.add(method(Opcodes.ACC_PUBLIC, "render", "()V", callingBody("()V")));
        assertNull(HookGen.trimReason(panel, storingCtor(owner, "java/lang/Object", false)));
        // a pure value holder (plain getters, equals/hashCode/toString, static helpers) still trims
        ClassNode holder = emptyClass("com/alex/test/village/Holder", Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "java/lang/Object");
        holder.methods.add(method(Opcodes.ACC_PUBLIC, "a", "()I", new VarInsnNode(Opcodes.ALOAD, 0),
            new FieldInsnNode(Opcodes.GETFIELD, holder.name, "a", "I"), new InsnNode(Opcodes.IRETURN)));
        holder.methods.add(method(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", callingBody("()Ljava/lang/String;")));
        holder.methods.add(method(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()V", callingBody("()V")));
        assertEquals("data-class constructor", HookGen.trimReason(holder, storingCtor(holder.name, "java/lang/Object", false)));
    }

    private static final String BUF = "Lnet/minecraft/network/FriendlyByteBuf;";

    private static ClassNode packetClass(String internalName) {
        ClassNode c = emptyClass(internalName, Opcodes.ACC_PUBLIC, "java/lang/Object");
        String self = "L" + internalName + ";";
        int pub = Opcodes.ACC_PUBLIC;
        int pubStatic = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        String[][] ms = {
            {"encode", "(" + BUF + ")V"}, {"decode", "(" + BUF + ")" + self}, {"write", "(" + BUF + ")V"}, {"read", "(" + BUF + ")" + self},
            {"toBytes", "(" + BUF + ")V"}, {"fromBytes", "(" + BUF + ")" + self}, {"codec", "()Lcom/mojang/serialization/Codec;"},
            {"streamCodec", "()Lnet/minecraft/network/codec/StreamCodec;"},
            {"handle", "(Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V"}};
        for (String[] m : ms) {
            boolean isStatic = m[1].startsWith("()") || m[0].equals("decode") || m[0].equals("read") || m[0].equals("fromBytes");
            c.methods.add(method(isStatic ? pubStatic : pub, m[0], m[1], callingBody(m[1])));
        }
        return c;
    }

    private static MethodNode named(ClassNode c, String name) {
        return c.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void packetPlumbingIsTrimmed() {
        ClassNode p = packetClass("com/alex/test/network/FooPacket");
        for (String n : List.of("encode", "decode", "write", "read", "toBytes", "fromBytes", "codec", "streamCodec")) {
            assertEquals("packet plumbing", HookGen.trimReason(p, named(p, n)), n);
        }
        assertNull(HookGen.trimReason(p, named(p, "handle")));
        // same names outside `network` are ordinary methods
        ClassNode other = packetClass("com/alex/test/village/Foo");
        assertNull(HookGen.trimReason(other, named(other, "encode")));
    }

    @Test
    void keepRuleRestoresATrimmedHook() {
        HookGen.Rule keep = HookGen.parseRule("keep network.FooPacket#encode");
        assertEquals("keep", keep.kind());
        List<HookGen.Rule> rules = List.of(keep, HookGen.parseRule("keep network.Gone#x"));
        ClassNode p = packetClass("com/alex/test/network/FooPacket");
        List<String> ks = keys(p, rules);
        assertTrue(ks.contains("network.FooPacket#encode(FriendlyByteBuf)"), ks.toString());
        assertFalse(ks.contains("network.FooPacket#decode(FriendlyByteBuf)"), ks.toString());
        assertTrue(ks.contains("network.FooPacket#handle(IPayloadContext)"), ks.toString());
        assertEquals(List.of("keep network.Gone#x"), HookGen.deadRules(rules));
        // the core-key set (filled from core's sources) keeps a trimmed hook without any rule
        assertTrue(HookGen.kept("network.FooPacket", "decode", List.of(), Set.of("network.FooPacket#decode(FriendlyByteBuf)"),
            "network.FooPacket#decode(FriendlyByteBuf)"));
        assertFalse(HookGen.kept("network.FooPacket", "decode", List.of(), Set.of(), "network.FooPacket#decode(FriendlyByteBuf)"));
    }

    @Test
    void keepRuleDoesNotOverrideHotPath() {
        List<HookGen.Rule> rules = List.of(HookGen.parseRule("village.Foo#helper"), HookGen.parseRule("keep village.Foo#helper"));
        ClassNode c = emptyClass("com/alex/test/village/Foo", Opcodes.ACC_PUBLIC, "java/lang/Object");
        c.methods.add(method(Opcodes.ACC_PRIVATE, "helper", "()V", callingBody("()V")));
        Map<String, Integer> skipped = new TreeMap<>();
        assertEquals(List.of(), HookGen.planClass(c, rules, Set.of(), skipped));
        assertEquals(Map.of("method: hot path (rules file)", 1), skipped);
        assertEquals("hot path (rules file)", HookGen.skipMethod(c, c.methods.get(0), rules));
    }

    /** A key core asks for is generated whatever the four trim categories say, but never past a hot-path exclusion. */
    @Test
    void coreKeyIsNeverTrimmed() {
        ClassNode c = emptyClass("com/alex/test/village/Foo", Opcodes.ACC_PUBLIC, "java/lang/Object");
        c.methods.add(method(Opcodes.ACC_PRIVATE, "restore", "(Ljava/lang/String;)V", callingBody("()V")));
        c.methods.add(method(Opcodes.ACC_PRIVATE, "hot", "()V", callingBody("()V")));
        Set<String> core = Set.of("village.Foo#restore(String)", "village.Foo#hot()");
        List<HookGen.Rule> rules = List.of(HookGen.parseRule("village.Foo#hot"));
        assertEquals(List.of("village.Foo#restore(String)"),
            HookGen.planClass(c, rules, core, new TreeMap<>()).stream().map(HookGen.Planned::key).toList());
        assertEquals(List.of(), HookGen.planClass(c, rules, Set.of(), new TreeMap<>()));
    }

    /** A class the tsv files name as an owner but that ends up with no hooks (all private, say) is still a carrier target. */
    @Test
    void tsvOwnerWithoutHooksBecomesCarrier() {
        ClassNode priv = emptyClass("com/alex/test/village/OnlyPrivate", Opcodes.ACC_PUBLIC, "java/lang/Object");
        priv.methods.add(method(Opcodes.ACC_PRIVATE, "hidden", "()V", callingBody("()V")));
        ClassNode hooked = cls("com/alex/test/village/Hooked", "()V");
        ClassNode other = cls("com/alex/test/village/Other", "()V");
        Map<String, ClassNode> classes = new TreeMap<>(Map.of(priv.name, priv, hooked.name, hooked, other.name, other));
        Set<String> tsv = Set.of("village/OnlyPrivate", "village/Hooked", "village/Gone");
        assertEquals(List.of("com/alex/test/village/OnlyPrivate"),
            HookGen.carrierOnly(classes, Set.of(hooked.name), List.of(), tsv));
        // a tsv owner that has hooks isn't listed twice, and one the jar lacks is ignored
        assertEquals(List.of(), HookGen.carrierOnly(classes, Set.of(hooked.name, priv.name), List.of(), tsv));
    }

    /** The step between carrierOnly and the emitted mixins: tsv-only carriers land in the carrier map, once. */
    @Test
    void carrierPlanPutsTsvCarriersIntoTheCarrierMixins() {
        ClassNode hooked = cls("com/alex/test/village/Hooked", "()V");
        ClassNode clientHooked = cls("com/alex/test/village/ClientHooked", "()V");
        ClassNode priv = emptyClass("com/alex/test/village/OnlyPrivate", Opcodes.ACC_PUBLIC, "java/lang/Object");
        ClassNode privIface = emptyClass("com/alex/test/village/OnlyPrivateIface", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "java/lang/Object");
        ClassNode ruled = cls("com/alex/test/village/Ruled", "()V");
        Map<String, ClassNode> classes = new TreeMap<>();
        for (ClassNode c : List.of(hooked, clientHooked, priv, privIface, ruled)) classes.put(c.name, c);
        Map<String, Boolean> hookedClient = new TreeMap<>(Map.of(hooked.name, false, clientHooked.name, true));
        HookGen.CarrierPlan plan = HookGen.carrierPlan(classes, hookedClient, List.of(HookGen.parseRule("@carrier village.Ruled")),
            Set.of("village/OnlyPrivate", "village/OnlyPrivateIface", "village/Ruled", "village/Hooked"));
        assertEquals(List.of("com/alex/test/village/Hooked", "com/alex/test/village/OnlyPrivate", "com/alex/test/village/Ruled"),
            plan.carriers().get("CommonClasses").stream().sorted().toList());
        assertEquals(List.of("com/alex/test/village/OnlyPrivateIface"), plan.carriers().get("CommonInterfaces"));
        assertEquals(List.of("com/alex/test/village/ClientHooked"), plan.carriers().get("ClientClasses"));
        assertEquals(List.of("com/alex/test/village/Ruled"), plan.ruleCarriers());
        assertEquals(List.of("com/alex/test/village/OnlyPrivate", "com/alex/test/village/OnlyPrivateIface"),
            plan.siteCarriers().stream().sorted().toList());
    }

    /** Owners the rewrites need: building-sites scope lines, and nav-sites REWRITE rows (not KEEP or AUDIT_ONLY ones). */
    @Test
    void tsvOwnersAreTheScopedAndRewrittenOnes() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("tsv");
        java.nio.file.Path b = dir.resolve("b.tsv");
        java.nio.file.Path n = dir.resolve("n.tsv");
        java.nio.file.Files.writeString(b, "# comment\n# scope village/building/A\n#   why\n# scope village/B\n"
            + "village/NavNode\tisX()Z\tINSTANCEOF\tFoo\tAUDIT_ONLY\tnote\t1\n");
        java.nio.file.Files.writeString(n, "# c\nvillage/C#m()V#STEP\t1\tREWRITE\tx\nvillage/D#m()V#STEP\t1\tKEEP\tx\n");
        assertEquals(Set.of("village/building/A", "village/B", "village/C"), HookGen.tsvOwners(b, n));
        // work-sites.tsv rows carry a bridge column before the note: the verdict is still the third column
        java.nio.file.Path w = dir.resolve("w.tsv");
        java.nio.file.Files.writeString(w, "# c\nvillage/E#m()V#RECIPES\t1\tREWRITE\ttownkit/X#b\tnote\nvillage/F#m()V#DISHES\t1\tKEEP\t-\tnote\n");
        assertEquals(Set.of("village/E"), HookGen.tsvOwners(w));
    }

    /** Whole literals and keys built from a same-file {@code static final String} prefix ending in '#'. */
    @Test
    void coreHookKeysFindsLiteralsAndPrefixedKeys() {
        String src = """
            class X {
                static final String RESTORE = "village.brain.VillagerBrain#restoreActivity(String,Activity.Context)";
                private static final String VB = "village.VillageBuildings#";
                static final String SET = VB + "tryAutoRegisterRoom(ServerLevel,BlockPos)";
                void f() { m.put(VB +  "scanExistingOutcrops(ServerLevel)", 1); call("entity.NpcEntity#setProfession(Profession)"); }
                String notKeys() { return owner + "#" + method + "a#b" + "village/NavGraph#floodFillInner#POLL"; }
                // "village.Gone#comment()" is not code
            }
            """;
        assertEquals(Set.of("village.brain.VillagerBrain#restoreActivity(String,Activity.Context)",
            "village.VillageBuildings#tryAutoRegisterRoom(ServerLevel,BlockPos)", "village.VillageBuildings#scanExistingOutcrops(ServerLevel)",
            "entity.NpcEntity#setProfession(Profession)"), HookGen.hookKeysIn(src));
    }

    /** The real core sources: every user (brain, events, nav, villager data, building events) is found. */
    @Test
    void realCoreSourcesAreCollected() throws Exception {
        Set<String> keys = HookGen.coreHookKeys(java.nio.file.Path.of("../src/main/java/townkit/core"));
        for (String k : List.of("village.brain.VillagerBrain#restoreActivity(String,Activity.Context)",
            "village.brain.VillagerBrain#decideNext(Activity.Context,float,float,float,float)",
            "village.brain.VillagerBrain#checkOverrides(Activity.Context)",
            "village.brain.VillagerBrain#switchTo(Activity,Activity.Context)",
            "village.VillageManager#tickNavRebuild(ServerLevel,int,long)", "village.FollowWalk#endOfRoute(PathfinderMob,ServerLevel,String)",
            "village.VillageManager#removeVillager(UUID)", "village.VillageBuildings#tryAutoRegisterRoom(ServerLevel,BlockPos)",
            "village.VillageBuildings#rebuildOne(ServerLevel,UUID,Building,boolean,boolean)", "village.building.Building#setAccessValid(boolean)")) {
            assertTrue(keys.contains(k), k + " in " + keys);
        }
    }

    /** An escaped string literal or a quote char literal before a key must not shift the quote pairing. */
    @Test
    void escapedLiteralBeforeAKeyKeepsTheKey() {
        assertEquals(Set.of("village.Foo#bar(int)"), HookGen.hookKeysIn("String a = \"line\\n\"; get(\"village.Foo#bar(int)\");"));
        assertEquals(Set.of("village.Foo#bar(int)"), HookGen.hookKeysIn("String q = \"\\\"\"; get(\"village.Foo#bar(int)\");"));
        assertEquals(Set.of("village.Foo#bar(int)"), HookGen.hookKeysIn("char c = '\"'; get(\"village.Foo#bar(int)\");"));
        assertEquals(Set.of("village.Foo#bar(int)"), HookGen.hookKeysIn("char c = '\\''; get(\"village.Foo#bar(int)\");"));
    }

    /** TownKit's own GameTests: every key they ask for is collected (constructors, records, private methods). */
    @Test
    void realGametestSourcesAreCollected() throws Exception {
        Set<String> keys = HookGen.coreHookKeys(java.nio.file.Path.of("../src/gametest/java"));
        for (String k : List.of("village.villagers.farmer.crops.BlockMatcher$Single#<init>(Block)",
            "village.villagers.farmer.crops.BlockMatcher$Single#block()", "village.AStarPathfinder$Route#waypoints()",
            "village.VillageManager#tickNavSlices(ServerLevel,int,long)", "village.VillagerModel#isOnAvoidedSurface(NavGraph)",
            "village.VillagerModel#isPhysicallyBlocked(NpcEntity)",
            "village.VillagerModel#tryRecoverToNearbySafePosition(NavGraph,List,boolean)")) {
            assertTrue(keys.contains(k), k + " in " + keys);
        }
    }

    /**
     * A GameTest key is kept and checked like a core key, except that a key an exclusion rule names may be missing:
     * those tests check that the hot path is NOT hooked.
     */
    @Test
    void gametestKeyMayOnlyBeMissingWhenExcluded() {
        List<HookGen.Rule> rules = List.of(HookGen.parseRule("village.NavGraph$*"));
        HookGen.checkTestKeys(Set.of("village.Foo#a()", "village.NavGraph$StandingCandidate#walkable()"), Set.of("village.Foo#a()"), rules);
        assertEquals(List.of("village.NavGraph$*"), HookGen.deadRules(rules), "checking doesn't count as a rule hit");
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> HookGen.checkTestKeys(Set.of("village.Foo#gone()"), Set.of(), rules));
        assertTrue(e.getMessage().contains("village.Foo#gone()"), e.getMessage());
    }

    @Test
    void missingCoreKeyFailsTheBuild() {
        HookGen.checkCoreKeys(Set.of("a#b()"), Set.of("a#b()", "c#d()"));
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> HookGen.checkCoreKeys(Set.of("a#b()", "x#gone()"), Set.of("a#b()")));
        assertTrue(e.getMessage().contains("x#gone()"), e.getMessage());
    }

    @Test
    void overloadSuffixesIgnoreTrimmedMethods() {
        ClassNode c = emptyClass("com/alex/test/village/Foo", Opcodes.ACC_PUBLIC, "java/lang/Object");
        c.methods.add(method(Opcodes.ACC_PRIVATE, "m", "(Ljava/util/List;)I", callingBody("(Ljava/util/List;)I")));
        c.methods.add(method(Opcodes.ACC_PUBLIC, "m", "(Ljava/util/List;)V", callingBody("()V")));
        c.methods.add(method(Opcodes.ACC_PUBLIC, "m", "(Ljava/lang/String;)V", callingBody("()V")));
        List<String> trimmed = keys(c, List.of());
        assertEquals(List.of("village.Foo#m(List)~2", "village.Foo#m(String)"), trimmed);
        // the same keys as when nothing is trimmed (the private overload kept back by a rule): public keys never move
        List<String> untrimmed = keys(c, List.of(HookGen.parseRule("keep village.Foo#m")));
        assertEquals(List.of("village.Foo#m(List)", "village.Foo#m(List)~2", "village.Foo#m(String)"), untrimmed);
        assertEquals(untrimmed.subList(1, 3), trimmed);
    }
}
