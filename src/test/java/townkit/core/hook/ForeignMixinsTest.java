package townkit.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Naming the other mods' mixins that changed a method before TownKit's rewrite ran (Gash's TownTopia Fix redirects the
 * crafting table check in StorageBuilding#scanFunctionalBlocks, so TownKit's audited check is gone).
 */
class ForeignMixinsTest {
    private static final String OWNER = "com/alex/test/village/building/StorageBuilding";
    private static final String SCAN = "scanFunctionalBlocks(Lnet/minecraft/server/level/ServerLevel;)Z";
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final String SITE = "village/building/StorageBuilding#" + SCAN + "#BLOCK_IS#CRAFTING_TABLE";

    private static MethodNode handler(String name, String mixin, boolean visible) {
        MethodNode h = new MethodNode(Opcodes.ACC_PRIVATE, name, "()Z", null, null);
        AnnotationNode a = new AnnotationNode(MERGED);
        a.values = List.of("mixin", mixin, "priority", 1000, "sessionId", "x");
        if (visible) h.visibleAnnotations = List.of(a);
        else h.invisibleAnnotations = List.of(a);
        h.instructions.add(new InsnNode(Opcodes.ICONST_1));
        h.instructions.add(new InsnNode(Opcodes.IRETURN));
        return h;
    }

    /** StorageBuilding with scanFunctionalBlocks calling the given handlers of its own class. */
    private static ClassNode storage(MethodNode... handlers) {
        ClassNode c = new ClassNode();
        c.name = OWNER;
        MethodNode scan = new MethodNode(Opcodes.ACC_PUBLIC, "scanFunctionalBlocks",
            "(Lnet/minecraft/server/level/ServerLevel;)Z", null, null);
        for (MethodNode h : handlers) {
            scan.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            scan.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, OWNER, h.name, h.desc, false));
            scan.instructions.add(new InsnNode(Opcodes.POP));
            c.methods.add(h);
        }
        scan.instructions.add(new InsnNode(Opcodes.ICONST_0));
        scan.instructions.add(new InsnNode(Opcodes.IRETURN));
        c.methods.add(scan);
        return c;
    }

    @Test
    void namesAnotherModsMixinThatChangedTheMethod() {
        ClassNode c = storage(handler("redirect$abc$recognizeCraftingTable",
            "dev.gasharaujo.towntopiafix.mixin.BuildingCraftingMixin", false));
        assertEquals("another mod's mixin changed it first: dev.gasharaujo.towntopiafix.mixin.BuildingCraftingMixin",
            ForeignMixins.cause(c, List.of(SITE)));
    }

    @Test
    void leavesOutTownKitsOwnMixinsAndListsEachOtherOnce() {
        ClassNode c = storage(
            handler("handler$a$one", "townkit.core.hook.mixin.HookedCommonClasses", true),
            handler("redirect$b$two", "dev.other.mixin.ZMixin", true),
            handler("redirect$c$three", "dev.other.mixin.AMixin", false),
            handler("redirect$d$four", "dev.other.mixin.ZMixin", false));
        assertEquals("another mod's mixin changed it first: dev.other.mixin.AMixin, dev.other.mixin.ZMixin",
            ForeignMixins.cause(c, List.of(SITE, SITE)));
    }

    @Test
    void withNoOtherMixinItKeepsTheOldWording() {
        ClassNode c = storage(handler("plain", "townkit.core.Something", true));
        assertEquals("Towntopia changed, or the rewrite failed", ForeignMixins.cause(c, List.of(SITE)));
    }

    /** The "doesn't match the audit" warnings name the method's other mixins, or keep "Towntopia changed?". */
    @Test
    void auditCauseNamesTheMixinOrKeepsTheOldGuess() {
        ClassNode changed = storage(handler("redirect$f$x", "dev.other.mixin.AMixin", false));
        ClassNode plain = storage();
        assertEquals("another mod's mixin changed it first: dev.other.mixin.AMixin",
            ForeignMixins.auditCause(changed, scan(changed)));
        assertEquals("Towntopia changed?", ForeignMixins.auditCause(plain, scan(plain)));
    }

    private static MethodNode scan(ClassNode c) {
        return c.methods.stream().filter(m -> m.name.equals("scanFunctionalBlocks")).findFirst().orElseThrow();
    }

    @Test
    void readsTheMethodFromAnAuditMismatchEntry() {
        ClassNode c = storage(handler("handler$e$recognizeModdedOre", "dev.gasharaujo.towntopiafix.mixin.MinerActivityMixin", false));
        assertEquals("another mod's mixin changed it first: dev.gasharaujo.towntopiafix.mixin.MinerActivityMixin",
            ForeignMixins.cause(c, List.of("village/building/StorageBuilding#" + SCAN + "#IRETURN 3 (audited 1)")));
    }
}
