package townkit.core.building;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.slf4j.Logger;

/**
 * Points Towntopia's audited block checks at the stand-in bridge ({@link StandIns}). Runs at class load from
 * TownKit's mixin plugin, after hook injection, so it also sees the bodies the injector moved into
 * {@code townkit$<name>} (matched under {@code <name>}, the name the audit knows).
 *
 * <p>Only sites with a SAFE or NEEDS verdict are rewritten: UNSAFE and AUDIT_ONLY sites can never count a stand-in, and
 * sites the audit never saw keep Towntopia's own instruction. {@code BLOCK_EQUALS} sites are never touched. An
 * {@code instanceof T} is also kept when its method casts to {@code T} (a pattern binding): a stand-in passing the
 * test would throw at the cast.
 *
 * <p>Insertions only (0.7.1): Towntopia's {@code instanceof} or {@code is} call stays in the method, because another
 * mod's mixin may already have picked it as its target and edit it after this runs (removing it once broke the whole
 * method). Its operands are duplicated in front of it and the bridge after it gets them plus its answer. Straight-line
 * code, no new branches. A method that already calls the bridge is skipped, so a second pass changes nothing.
 *
 * <p>Must not load Minecraft or bridge classes: the bridge is only named in the emitted bytecode. The class is planned
 * in full before anything is changed; any failure while planning logs once and leaves the class as it was.
 */
public final class CheckSiteRewriter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "com/alex/test/";
    private static final String BODY_PREFIX = "townkit$";
    private static final String HOOKED_PREFIX = "townkit$hooked$";
    private static final String BLOCKS = "net/minecraft/world/level/block/Blocks";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String IS_DESC = "(L" + BLOCK + ";)Z";
    static final String BRIDGE = "townkit/core/building/StandIns";
    static final String BRIDGE_IS_DESC = "(L" + BLOCK_STATE + ";L" + BLOCK + ";ZI)Z";
    static final String BRIDGE_INSTANCEOF_DESC = "(Ljava/lang/Object;ZLjava/lang/Class;I)Z";
    private static final Set<String> WARNED = new HashSet<>();

    private CheckSiteRewriter() {
    }

    /** One planned replacement. */
    private record Edit(MethodNode method, AbstractInsnNode insn, int siteId) {
    }

    /** Rewrites the audited sites of {@code cls}; returns the number of instructions rewritten. */
    public static int rewrite(ClassNode cls, SiteVerdicts verdicts) {
        return rewrite(cls, verdicts, id -> { });
    }

    /** As {@link #rewrite(ClassNode, SiteVerdicts)}; {@code onSite} gets the site id of each rewritten instruction. */
    public static int rewrite(ClassNode cls, SiteVerdicts verdicts, IntConsumer onSite) {
        List<Edit> edits;
        try {
            edits = plan(cls, verdicts);
        } catch (Throwable t) {
            warnOnce(cls.name, "TownKit building: could not plan the check-site rewrite of {}; its checks stay as "
                    + "Towntopia ships them", cls.name, t);
            return 0;
        }
        // applying only links prepared nodes into lists; it cannot fail half-way on well-formed input. Towntopia's
        // instruction stays where it is (another mod's mixin may already hold it and edit it later): its operands are
        // duplicated in front of it and the bridge after it takes them and its answer
        Set<MethodNode> touched = new HashSet<>();
        for (Edit e : edits) {
            InsnList il = e.method.instructions;
            InsnList after = new InsnList();
            if (e.insn instanceof TypeInsnNode t) {
                // o -> o, o instanceof T -> afterInstanceOf(o, result, T, site)
                il.insertBefore(e.insn, new InsnNode(Opcodes.DUP));
                after.add(new LdcInsnNode(Type.getObjectType(t.desc)));
                after.add(new LdcInsnNode(e.siteId));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "afterInstanceOf", BRIDGE_INSTANCEOF_DESC,
                        false));
            } else {
                // s, b -> s, b, s.is(b) -> afterIs(s, b, result, site)
                il.insertBefore(e.insn, new InsnNode(Opcodes.DUP2));
                after.add(new LdcInsnNode(e.siteId));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "afterIs", BRIDGE_IS_DESC, false));
            }
            il.insert(e.insn, after);
            if (touched.add(e.method)) {
                // at most three more stack slots per site (Mixin recomputes, this keeps maxs right regardless)
                e.method.maxStack += 3;
            }
            onSite.accept(e.siteId);
        }
        return edits.size();
    }

    /** Whether {@code m} already calls the bridge (an earlier pass rewrote it; Towntopia's checks are still there). */
    private static boolean callsBridge(MethodNode m) {
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(BRIDGE)) {
                return true;
            }
        }
        return false;
    }

    private static List<Edit> plan(ClassNode cls, SiteVerdicts verdicts) {
        String owner = cls.name.startsWith(PREFIX) ? cls.name.substring(PREFIX.length()) : cls.name;
        List<Edit> out = new ArrayList<>();
        for (MethodNode m : cls.methods) {
            if (m.instructions.size() == 0 || m.name.startsWith(HOOKED_PREFIX) || callsBridge(m)) {
                continue;
            }
            String name = m.name.startsWith(BODY_PREFIX) ? m.name.substring(BODY_PREFIX.length()) : m.name;
            String prefix = owner + "#" + name + m.desc + "#";
            List<Edit> mine = new ArrayList<>();
            planMethod(cls, m, prefix, verdicts, mine);
            if (!mine.isEmpty() && countsMatch(cls, m, owner, name + m.desc, prefix, verdicts)) {
                out.addAll(mine);
            }
        }
        return out;
    }

    /**
     * Whether every audited SAFE/NEEDS key of the method occurs as often as the verdict file says. Several checks of
     * one block in one method share a key and a verdict, so a Towntopia update adding another check of that block
     * would otherwise inherit the audited verdict unseen; on a mismatch every site of the method stays as shipped.
     */
    private static boolean countsMatch(ClassNode cls, MethodNode m, String owner, String method, String prefix,
            SiteVerdicts verdicts) {
        Map<CheckSite, Integer> found = CheckSiteScanner.occurrences(owner, method, m, analyze(cls, m));
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<CheckSite, Integer> e : found.entrySet()) {
            int id = usable(verdicts, e.getKey().key());
            int expected = id < 0 ? -1 : verdicts.count(id);
            if (expected >= 0 && expected != e.getValue()) {
                wrong.add(e.getKey().kind() + "#" + e.getKey().target() + " " + e.getValue() + " (audited " + expected
                        + ")");
            }
        }
        if (wrong.isEmpty()) {
            return true;
        }
        warnOnce("count:" + prefix, "TownKit building: {} doesn't match the audit (Towntopia changed?): {}; its checks "
                + "stay as Towntopia ships them", prefix, wrong, null);
        return false;
    }

    private static void planMethod(ClassNode cls, MethodNode m, String prefix, SiteVerdicts verdicts, List<Edit> out) {
        Frame<SourceValue>[] frames = null;
        boolean analysed = false;
        Set<String> casts = null;
        AbstractInsnNode[] insns = m.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode in = insns[i];
            if (in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.INSTANCEOF) {
                int id = usable(verdicts, prefix + CheckSite.Kind.INSTANCEOF + "#" + t.desc);
                if (id < 0) {
                    continue;
                }
                if (casts == null) {
                    casts = casts(insns);
                }
                if (casts.contains(t.desc)) {
                    warnOnce(prefix + t.desc, "TownKit building: {} casts to {} after testing it; that check stays as "
                            + "Towntopia ships it", prefix, t.desc, null);
                    continue;
                }
                out.add(new Edit(m, in, id));
            } else if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && mi.name.equals("is") && mi.desc.equals(IS_DESC) && mi.owner.equals(BLOCK_STATE)) {
                // a receiver typed BlockBehaviour$BlockStateBase would not fit the bridge: such calls stay
                if (!analysed) {
                    analysed = true;
                    frames = analyze(cls, m);
                    if (frames == null) {
                        warnOnce(prefix, "TownKit building: could not analyse {}; its Blocks.is checks stay as "
                                + "Towntopia ships them", prefix, null, null);
                    }
                }
                if (frames == null || frames[i] == null) {
                    continue;
                }
                Frame<SourceValue> f = frames[i];
                String field = blocksField(f.getStack(f.getStackSize() - 1));
                if (field == null) {
                    continue;
                }
                int id = usable(verdicts, prefix + CheckSite.Kind.BLOCK_IS + "#" + field);
                if (id >= 0) {
                    out.add(new Edit(m, in, id));
                }
            }
        }
    }

    /** The site id when the site is audited SAFE or NEEDS, else -1. */
    private static int usable(SiteVerdicts verdicts, String key) {
        int id = verdicts.siteId(key);
        if (id < 0) {
            return -1;
        }
        SiteVerdicts.Type t = verdicts.verdict(id).type();
        return t == SiteVerdicts.Type.SAFE || t == SiteVerdicts.Type.NEEDS ? id : -1;
    }

    /** The one {@code Blocks} field the value comes from, or null (several, or not a field). */
    private static String blocksField(SourceValue v) {
        String found = null;
        for (AbstractInsnNode src : v.insns) {
            if (!(src instanceof FieldInsnNode fi) || fi.getOpcode() != Opcodes.GETSTATIC || !fi.owner.equals(BLOCKS)) {
                return null;
            }
            if (found != null && !found.equals(fi.name)) {
                return null;
            }
            found = fi.name;
        }
        return found;
    }

    private static Set<String> casts(AbstractInsnNode[] insns) {
        Set<String> out = new HashSet<>();
        for (AbstractInsnNode in : insns) {
            if (in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.CHECKCAST) {
                out.add(t.desc);
            }
        }
        return out;
    }

    private static Frame<SourceValue>[] analyze(ClassNode cls, MethodNode m) {
        try {
            return new Analyzer<>(new SourceInterpreter()).analyze(cls.name, m);
        } catch (AnalyzerException ex) {
            return null;
        }
    }

    private static void warnOnce(String key, String msg, Object a, Object b, Throwable t) {
        synchronized (WARNED) {
            if (!WARNED.add(key)) {
                return;
            }
        }
        if (t != null) {
            LOGGER.warn(msg, a, b, t);
        } else {
            LOGGER.warn(msg, a, b);
        }
    }

    private static void warnOnce(String key, String msg, Object a, Throwable t) {
        synchronized (WARNED) {
            if (!WARNED.add(key)) {
                return;
            }
        }
        LOGGER.warn(msg, a, t);
    }
}
