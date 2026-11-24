package townkit.core.work;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;

/**
 * Points Towntopia's audited instructions ({@code work-sites.tsv}, REWRITE rows) at their bridges, in the row kind's
 * {@linkplain WorkSite.Style style}: e.g. right after a {@code getstatic}, an {@code invokestatic bridge(T)T} takes
 * Towntopia's value and returns the one to use (Towntopia's own while nothing is registered). Runs at class load from
 * TownKit's mixin plugin, after hook injection, so it also sees bodies the injector moved into {@code townkit$<name>}
 * (matched under {@code <name>}, the name the audit knows).
 *
 * <p>A method is rewritten only when every audited kind in it occurs as often as the file says; otherwise (Towntopia
 * changed it) the whole method stays as shipped and one warning names it. Instructions the audit never saw are left
 * alone. An instruction already next to its bridge call is not rewritten again (a second pass changes nothing).
 * Straight-line insertions only: no new branches, so no stack-map frames change. Towntopia's instruction is never
 * removed, replaced or {@code set}: another mod's mixin may already hold it and edit it after this runs
 * ({@code ForeignInjectorTest} checks it).
 *
 * <p>Must not load Minecraft or bridge classes: the bridges are only named in the emitted bytecode.
 */
public final class WorkSiteRewriter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "com/alex/test/";
    private static final String BODY_PREFIX = "townkit$";
    private static final String HOOKED_PREFIX = "townkit$hooked$";
    /** How many spare constants {@link WorkSite.Style#ENUM_EXTEND} adds to the smith's material enum. */
    public static final int SPARE_MATERIALS = 16;
    /** The name of spare constant {@code i} ({@code TOWNKIT_0} ...). */
    public static final String SPARE_PREFIX = "TOWNKIT_";
    static final String EXTEND = "townkit$extend";
    private static final String MAT_INIT = "(Ljava/lang/String;ILnet/minecraft/world/item/Item;[Lnet/minecraft/world/item/Item;)V";
    private static final Set<String> WARNED = new HashSet<>();

    private WorkSiteRewriter() {
    }

    private record Edit(MethodNode method, AbstractInsnNode insn, int siteId, WorkSite.Kind kind) {
    }

    /** Rewrites the audited instructions of {@code cls}; returns how many instructions were rewritten. */
    public static int rewrite(ClassNode cls, WorkSites sites, IntConsumer onSite) {
        List<Edit> edits;
        try {
            edits = plan(cls, sites);
        } catch (Throwable t) {
            warnOnce(cls.name, "TownKit work: could not plan the rewrite of " + cls.name + "; its reads stay as Towntopia ships them", t);
            return 0;
        }
        Map<MethodNode, Integer> touched = new HashMap<>();
        int n = 0;
        for (Edit e : edits) {
            if (!apply(cls, sites, e)) continue;
            // a dup, or the arguments loaded again before a return bridge (a few slots): kept generous
            touched.merge(e.method, e.kind.style == WorkSite.Style.RETURN_ARGS ? 8 : 2, Math::max);
            onSite.accept(e.siteId);
            n++;
        }
        touched.forEach((m, extra) -> m.maxStack += extra);
        return n;
    }

    private static boolean apply(ClassNode cls, WorkSites sites, Edit e) {
        InsnList list = e.method.instructions;
        String owner = sites.bridgeOwner(e.siteId);
        String name = sites.bridgeName(e.siteId);
        String desc = e.kind.bridgeDesc(e.method.desc);
        switch (e.kind.style) {
            case AFTER -> list.insert(e.insn, new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false));
            case AFTER_ERASED -> {
                InsnList after = new InsnList();
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false));
                after.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getType(e.kind.desc).getInternalName()));
                list.insert(e.insn, after);
            }
            case DUP_OWNER, DUP_ARG, DUP_RECEIVER -> {
                list.insertBefore(e.insn, new InsnNode(Opcodes.DUP));
                list.insert(e.insn, new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false));
            }
            case RETURN_ARGS -> {
                InsnList before = new InsnList();
                int slot = (e.method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                for (Type arg : Type.getArgumentTypes(e.method.desc)) {
                    before.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
                    slot += arg.getSize();
                }
                before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false));
                list.insertBefore(e.insn, before);
            }
            case DUP2_ARGS -> {
                list.insertBefore(e.insn, new InsnNode(Opcodes.DUP2));
                list.insert(e.insn, new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false));
            }
            case ENUM_EXTEND -> {
                if (!addExtend(cls)) return false;
                String arr = "[L" + cls.name + ";";
                list.insert(e.insn, new MethodInsnNode(Opcodes.INVOKESTATIC, cls.name, EXTEND, "(" + arr + ")" + arr, false));
            }
            case AUDIT_ONLY -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Adds {@code townkit$extend(Mat[]) Mat[]} to the enum: the array with {@link #SPARE_MATERIALS} more constants
     * ({@code TOWNKIT_0} ..., no item, no alternatives), built inside the enum itself because enum constructors can only
     * be called from the enum's own code. Straight-line code. False (nothing added) when the enum lacks the constructor
     * TownKit knows.
     */
    private static boolean addExtend(ClassNode cls) {
        for (MethodNode m : cls.methods) {
            if (m.name.equals(EXTEND)) return true;
        }
        boolean ctor = cls.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(MAT_INIT));
        if (!ctor) {
            warnOnce("extend:" + cls.name, "TownKit work: " + cls.name + " has no constructor " + MAT_INIT
                + " (Towntopia changed?); no spare materials", null);
            return false;
        }
        String arr = "[L" + cls.name + ";";
        MethodNode ext = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, EXTEND, "(" + arr + ")" + arr,
            null, null);
        InsnList i = ext.instructions;
        // out = Arrays.copyOf(in, in.length + SPARE_MATERIALS)
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new InsnNode(Opcodes.ARRAYLENGTH));
        i.add(new LdcInsnNode(SPARE_MATERIALS));
        i.add(new InsnNode(Opcodes.IADD));
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "copyOf", "([Ljava/lang/Object;I)[Ljava/lang/Object;", false));
        i.add(new TypeInsnNode(Opcodes.CHECKCAST, arr));
        i.add(new VarInsnNode(Opcodes.ASTORE, 1));
        for (int s = 0; s < SPARE_MATERIALS; s++) {
            // out[in.length + s] = new Mat("TOWNKIT_s", in.length + s, null, new Item[0])
            i.add(new VarInsnNode(Opcodes.ALOAD, 1));
            i.add(new VarInsnNode(Opcodes.ALOAD, 0));
            i.add(new InsnNode(Opcodes.ARRAYLENGTH));
            i.add(new LdcInsnNode(s));
            i.add(new InsnNode(Opcodes.IADD));
            i.add(new TypeInsnNode(Opcodes.NEW, cls.name));
            i.add(new InsnNode(Opcodes.DUP));
            i.add(new LdcInsnNode(SPARE_PREFIX + s));
            i.add(new VarInsnNode(Opcodes.ALOAD, 0));
            i.add(new InsnNode(Opcodes.ARRAYLENGTH));
            i.add(new LdcInsnNode(s));
            i.add(new InsnNode(Opcodes.IADD));
            i.add(new InsnNode(Opcodes.ACONST_NULL));
            i.add(new InsnNode(Opcodes.ICONST_0));
            i.add(new TypeInsnNode(Opcodes.ANEWARRAY, "net/minecraft/world/item/Item"));
            i.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cls.name, "<init>", MAT_INIT, false));
            i.add(new InsnNode(Opcodes.AASTORE));
        }
        i.add(new VarInsnNode(Opcodes.ALOAD, 1));
        i.add(new InsnNode(Opcodes.ARETURN));
        ext.maxStack = 10;
        ext.maxLocals = 2;
        cls.methods.add(ext);
        return true;
    }

    private static List<Edit> plan(ClassNode cls, WorkSites sites) {
        String owner = cls.name.startsWith(PREFIX) ? cls.name.substring(PREFIX.length()) : cls.name;
        List<Edit> out = new ArrayList<>();
        Set<String> bodies = new HashSet<>();
        for (MethodNode m : cls.methods) {
            if (m.name.startsWith(BODY_PREFIX) && !m.name.startsWith(HOOKED_PREFIX)) bodies.add(m.name + m.desc);
        }
        for (MethodNode m : List.copyOf(cls.methods)) {
            if (m.instructions.size() == 0 || m.name.startsWith(HOOKED_PREFIX) || m.name.equals(EXTEND)) continue;
            // a hooked method's wrapper (TownKit's own returns): Towntopia's code is in the body, counted there
            if (bodies.contains(BODY_PREFIX + m.name + m.desc)) continue;
            String name = m.name.startsWith(BODY_PREFIX) ? m.name.substring(BODY_PREFIX.length()) : m.name;
            String prefix = owner + "#" + name + m.desc + "#";
            Map<String, Integer> found = new HashMap<>();
            List<Edit> mine = new ArrayList<>();
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                WorkSite.Kind k = WorkSite.Kind.of(in);
                if (k == null) continue;
                String base = prefix + k.name();
                int occurrence = found.merge(base, 1, Integer::sum);
                if (k.scoped && sites.baseCount(base) < 0) continue; // a constant or return this method's rows don't name
                int id = sites.siteId(k.perOccurrence() ? base + "@" + occurrence : base);
                if (id < 0 || !sites.rewrite(id) || bridged(in, sites, id, k)) continue;
                mine.add(new Edit(m, in, id, k));
            }
            if (mine.isEmpty()) continue;
            List<String> wrong = new ArrayList<>();
            for (Map.Entry<String, Integer> e : found.entrySet()) {
                int audited = sites.baseCount(e.getKey());
                if (audited >= 0 && audited != e.getValue()) wrong.add(e.getKey() + " " + e.getValue() + " (audited " + audited + ")");
            }
            if (wrong.isEmpty()) {
                out.addAll(mine);
            } else {
                warnOnce("count:" + prefix, "TownKit work: " + prefix + " doesn't match the audit (" + townkit.core.hook.ForeignMixins.auditCause(cls, m)
                    + "): " + wrong
                    + "; its reads stay as Towntopia ships them", null);
            }
        }
        return out;
    }

    /** Whether the instruction is already next to its bridge call (an earlier pass rewrote it). */
    private static boolean bridged(AbstractInsnNode in, WorkSites sites, int id, WorkSite.Kind k) {
        AbstractInsnNode near = k.style == WorkSite.Style.RETURN_ARGS ? in.getPrevious() : in.getNext();
        if (!(near instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKESTATIC) return false;
        if (k.style == WorkSite.Style.ENUM_EXTEND) return mi.name.equals(EXTEND);
        return mi.owner.equals(sites.bridgeOwner(id)) && mi.name.equals(sites.bridgeName(id));
    }

    private static void warnOnce(String key, String msg, Throwable t) {
        synchronized (WARNED) {
            if (!WARNED.add(key)) return;
        }
        if (t != null) LOGGER.warn(msg, t);
        else LOGGER.warn(msg);
    }
}
