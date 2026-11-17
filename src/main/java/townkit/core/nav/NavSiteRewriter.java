package townkit.core.nav;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.EnumSet;
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
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;

/**
 * Points Towntopia's audited nav sites ({@code nav-sites.tsv}, verdict REWRITE) at {@link NavBridge}. Runs at class
 * load from TownKit's mixin plugin, after hook injection, so it also sees bodies the injector moved into
 * {@code townkit$<name>} (matched under {@code <name>}); {@code townkit$hooked$} paths are not bodies.
 *
 * <p>Per kind:
 * <ul>
 *   <li>STEP: {@code from.canStepTo(to)} stays (0.7.1); a {@code DUP2} in front keeps {@code from, to} and
 *       {@code NavBridge.afterStep(from, to, result, site)} follows it; inside {@code AStarPathfinder#search} it is
 *       {@code NavBridge.afterSearchStep(from, to, result, goal, rules, site)}, loading the {@code goalNode} parameter
 *       (the third, a {@code NavNode}) and the {@code RouteRules} parameter.</li>
 *   <li>COST: the {@code edgeCost} arguments are stored to fresh locals and reloaded, the original runs, then
 *       {@code NavBridge.cost(edge, cur, nb, rules)} ({@code rules} = the method's {@code RouteRules} parameter, else
 *       null).</li>
 *   <li>LOS: the same store and reload around {@code lineOfSight(graph, from, to, occupied)}, then
 *       {@code NavBridge.lineOfSight(result, graph, from, to)}: the graph is the call's own {@code NavGraph}
 *       argument.</li>
 *   <li>CELL: every {@code IRETURN} of the reachability adapter {@code NavCells$1#kind(from, to,
 *       now)} gets {@code NavBridge.cellKind(result, from, to)} in front, so a never tile is kind 2 (kept by the island
 *       flood, blocked for access and reaches) rather than -1 (which island removal would delete). That method's STEP stays as
 *       Towntopia ships it (KEEP).</li>
 *   <li>PACE: {@code ALOAD} of the method's {@code PathfinderMob} parameter and
 *       {@code NavBridge.pace(speed, npc)} go right before {@code setWantedPosition(x, y, z, speed)}, so only the
 *       speed argument (on top of the stack) changes. Only {@code FollowWalk#tickLine} is a REWRITE row
 *       ({@code tickJoin} is KEEP).</li>
 *   <li>POLL: right after {@code queue.poll()} in {@code NavGraph#floodFillInner}, the method's
 *       {@code Queue}, {@code Map} and {@code Level} parameters are loaded and {@code NavBridge.polled(node, queue,
 *       nodes, level)} hands the polled node back (Towntopia's own {@code CHECKCAST} follows), so a link start seeds
 *       its far stop and link edge while the fill runs.</li>
 * </ul>
 *
 * <p>Insertions only: Towntopia's instruction is never removed or replaced, because another mod's mixin may already
 * have picked it as its target and edit it after this runs. Straight-line code, no new branches.
 *
 * <p>A method is rewritten only when every handled REWRITE key in it occurs as often as the file says (a Towntopia
 * update adding a call would otherwise inherit the audit unseen). The class is planned in full before anything
 * changes; any failure while planning logs once and leaves the whole class as it was. Must not load Minecraft or
 * Towntopia classes: the bridge is only named in the emitted bytecode.
 */
public final class NavSiteRewriter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "com/alex/test/";
    private static final String BODY_PREFIX = "townkit$";
    private static final String HOOKED_PREFIX = "townkit$hooked$";
    static final String BRIDGE = "townkit/core/nav/NavBridge";
    private static final String NODE = PREFIX + "village/NavNode";
    private static final String RULES = PREFIX + "village/RouteRules";
    private static final String GRAPH = PREFIX + "village/NavGraph";
    private static final String STEP_DESC = "(L" + NODE + ";L" + NODE + ";ZI)Z";
    private static final String SEARCH_STEP_DESC = "(L" + NODE + ";L" + NODE + ";ZL" + NODE + ";L" + RULES + ";I)Z";
    private static final String COST_DESC = "(IL" + NODE + ";L" + NODE + ";L" + RULES + ";)I";
    private static final String LOS_DESC = "(ZL" + GRAPH + ";L" + NODE + ";L" + NODE + ";)Z";
    private static final String CELL_DESC = "(IL" + NODE + ";L" + NODE + ";)I";
    /** The kinds rewritten today. */
    static final Set<NavSite.Kind> HANDLED = EnumSet.of(NavSite.Kind.STEP, NavSite.Kind.COST, NavSite.Kind.LOS,
            NavSite.Kind.CELL, NavSite.Kind.PACE, NavSite.Kind.POLL);
    private static final String MOB = "net/minecraft/world/entity/PathfinderMob";
    private static final String PACE_DESC = "(DL" + MOB + ";)D";
    private static final String QUEUE = "java/util/Queue";
    private static final String MAP = "java/util/Map";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String POLL_DESC = "(Ljava/lang/Object;L" + QUEUE + ";L" + MAP + ";L" + LEVEL + ";)Ljava/lang/Object;";
    private static final Set<String> WARNED = new HashSet<>();

    private NavSiteRewriter() {
    }

    /**
     * One planned site. {@code before} goes in front of the instruction, {@code after} follows it; the instruction
     * itself is never removed or replaced (another mod's mixin may hold it and edit it after this runs).
     */
    private record Edit(AbstractInsnNode insn, int siteId, InsnList before, InsnList after) {
    }

    private record MethodPlan(MethodNode method, List<Edit> edits, int maxLocals) {
    }

    /** Rewrites the audited sites of {@code cls}; returns the number of sites rewritten. */
    public static int rewrite(ClassNode cls, NavSites sites) {
        return rewrite(cls, sites, id -> { });
    }

    /** As {@link #rewrite(ClassNode, NavSites)}; {@code onSite} gets the site id of each rewritten site. */
    public static int rewrite(ClassNode cls, NavSites sites, IntConsumer onSite) {
        List<MethodPlan> plans;
        try {
            plans = plan(cls, sites);
        } catch (Throwable t) {
            // keyed per ClassNode: a class loads once in the game
            warnOnce(cls.name + "@" + System.identityHashCode(cls), "TownKit nav: could not plan the nav-site rewrite of {}; its route checks stay as "
                    + "Towntopia ships them", cls.name, t);
            return 0;
        }
        // applying only links prepared nodes into lists; it cannot fail half-way on well-formed input
        int n = 0;
        for (MethodPlan p : plans) {
            InsnList il = p.method.instructions;
            for (Edit e : p.edits) {
                if (e.before.size() > 0) {
                    il.insertBefore(e.insn, e.before);
                }
                if (e.after.size() > 0) {
                    il.insert(e.insn, e.after);
                }
                onSite.accept(e.siteId);
                n++;
            }
            // at most four more stack slots per site (Mixin recomputes; this keeps maxs right regardless)
            p.method.maxStack += 4;
            p.method.maxLocals = p.maxLocals;
        }
        return n;
    }

    private static List<MethodPlan> plan(ClassNode cls, NavSites sites) {
        String owner = cls.name.startsWith(PREFIX) ? cls.name.substring(PREFIX.length()) : cls.name;
        List<MethodPlan> out = new ArrayList<>();
        for (MethodNode m : cls.methods) {
            if (m.instructions.size() == 0 || m.name.startsWith(HOOKED_PREFIX) || callsBridge(m)) {
                // a method that already calls the bridge was rewritten before: every kind keeps Towntopia's
                // instruction, so a second pass would wrap it again
                continue;
            }
            String name = m.name.startsWith(BODY_PREFIX) ? m.name.substring(BODY_PREFIX.length()) : m.name;
            String prefix = owner + "#" + name + m.desc + "#";
            Map<String, Integer> found = new HashMap<>();
            List<AbstractInsnNode> handled = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                NavSite.Kind k = NavSiteScanner.kindOf(owner, name, m.desc, in);
                if (k == null || !HANDLED.contains(k)) {
                    continue;
                }
                String key = prefix + k;
                found.merge(key, 1, Integer::sum);
                int id = sites.siteId(key);
                if (id >= 0 && sites.rewrite(id)) {
                    handled.add(in);
                    ids.add(id);
                }
            }
            if (handled.isEmpty() || !countsMatch(prefix, found, sites)) {
                continue;
            }
            int[] next = {firstFreeLocal(m)};
            List<Edit> edits = new ArrayList<>();
            for (int i = 0; i < handled.size(); i++) {
                edits.add(planSite(owner, name, m, handled.get(i), ids.get(i), next));
            }
            out.add(new MethodPlan(m, edits, Math.max(m.maxLocals, next[0])));
        }
        return out;
    }

    /** Whether {@code m} already calls {@link NavBridge} (it went through this rewrite). */
    private static boolean callsBridge(MethodNode m) {
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(BRIDGE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean countsMatch(String prefix, Map<String, Integer> found, NavSites sites) {
        List<String> wrong = new ArrayList<>();
        found.forEach((key, n) -> {
            int id = sites.siteId(key);
            if (id >= 0 && sites.rewrite(id) && sites.count(id) != n) {
                wrong.add(key.substring(prefix.length()) + " " + n + " (audited " + sites.count(id) + ")");
            }
        });
        if (wrong.isEmpty()) {
            return true;
        }
        warnOnce("count:" + prefix, "TownKit nav: {} doesn't match the audit (Towntopia changed?): {}; its route "
                + "checks stay as Towntopia ships them", prefix, wrong);
        return false;
    }

    private static Edit planSite(String owner, String method, MethodNode m, AbstractInsnNode in, int id, int[] next) {
        NavSite.Kind kind = NavSiteScanner.kindOf(owner, method, m.desc, in);
        if (kind == NavSite.Kind.CELL) {
            int from = paramSlot(m, NODE, 1);
            int to = paramSlot(m, NODE, 2);
            if (from < 0 || to < 0 || stores(m, from) || stores(m, to)) {
                throw new IllegalStateException(owner + "#" + method + m.desc + ": from and to are not plain parameters");
            }
            InsnList before = new InsnList();
            before.add(new VarInsnNode(Opcodes.ALOAD, from));
            before.add(new VarInsnNode(Opcodes.ALOAD, to));
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "cellKind", CELL_DESC, false));
            return new Edit(in, id, before, new InsnList());
        }
        MethodInsnNode mi = (MethodInsnNode) in;
        switch (kind) {
            case STEP: {
                // from, to -> from, to, from.canStepTo(to) -> afterStep(from, to, result, [goal, rules,] site)
                InsnList before = new InsnList();
                before.add(new InsnNode(Opcodes.DUP2));
                InsnList after = new InsnList();
                if (owner.equals(NavSiteScanner.ASTAR_OWNER) && method.equals("search")) {
                    // search(graph, startNode, goalNode, ...): goalNode is the third parameter
                    int goal = slotOfParam(m, 2, NODE);
                    int rules = paramSlot(m, RULES, 1);
                    if (goal < 0 || rules < 0) {
                        throw new IllegalStateException(owner + "#" + method + m.desc
                                + ": no goal NavNode or RouteRules parameter");
                    }
                    after.add(new VarInsnNode(Opcodes.ALOAD, goal));
                    after.add(new VarInsnNode(Opcodes.ALOAD, rules));
                    after.add(new LdcInsnNode(id));
                    after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "afterSearchStep", SEARCH_STEP_DESC,
                            false));
                } else {
                    after.add(new LdcInsnNode(id));
                    after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "afterStep", STEP_DESC, false));
                }
                return new Edit(mi, id, before, after);
            }
            case COST: {
                int[] slots = storeArgs(mi, next);
                Type[] args = Type.getArgumentTypes(mi.desc);
                int cur = argOfType(args, NODE, 1);
                int nb = argOfType(args, NODE, 2);
                if (cur != 0 || nb != 1) {
                    throw new IllegalStateException("edgeCost " + mi.desc + ": cur and nb are not its first arguments");
                }
                InsnList after = new InsnList();
                after.add(new VarInsnNode(Opcodes.ALOAD, slots[cur]));
                after.add(new VarInsnNode(Opcodes.ALOAD, slots[nb]));
                int rules = paramSlot(m, RULES, 1);
                after.add(rules < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, rules));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "cost", COST_DESC, false));
                return new Edit(mi, id, storeAndReload(args, slots), after);
            }
            case LOS: {
                int[] slots = storeArgs(mi, next);
                Type[] args = Type.getArgumentTypes(mi.desc);
                int graph = argOfType(args, GRAPH, 1);
                int from = argOfType(args, NODE, 1);
                int to = argOfType(args, NODE, 2);
                if (graph < 0 || from < 0 || to < 0) {
                    throw new IllegalStateException("lineOfSight " + mi.desc + ": no graph, from and to arguments");
                }
                InsnList after = new InsnList();
                after.add(new VarInsnNode(Opcodes.ALOAD, slots[graph]));
                after.add(new VarInsnNode(Opcodes.ALOAD, slots[from]));
                after.add(new VarInsnNode(Opcodes.ALOAD, slots[to]));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "lineOfSight", LOS_DESC, false));
                return new Edit(mi, id, storeAndReload(args, slots), after);
            }
            case PACE: {
                // the speed (last argument, a double) is on top: pace(speed, npc) replaces it
                int npc = paramSlot(m, MOB, 1);
                if (npc < 0 || stores(m, npc)) {
                    throw new IllegalStateException(owner + "#" + method + m.desc
                            + ": the PathfinderMob is not a plain parameter");
                }
                InsnList before = new InsnList();
                before.add(new VarInsnNode(Opcodes.ALOAD, npc));
                before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "pace", PACE_DESC, false));
                return new Edit(mi, id, before, new InsnList());
            }
            case POLL: {
                // the polled node is on top: polled(node, queue, nodes, level) hands it back
                int queue = paramSlot(m, QUEUE, 1);
                int nodes = paramSlot(m, MAP, 1);
                int level = paramSlot(m, LEVEL, 1);
                if (queue < 0 || nodes < 0 || level < 0 || stores(m, queue) || stores(m, nodes) || stores(m, level)) {
                    throw new IllegalStateException(owner + "#" + method + m.desc
                            + ": the queue, nodes map and level are not plain parameters");
                }
                InsnList after = new InsnList();
                after.add(new VarInsnNode(Opcodes.ALOAD, queue));
                after.add(new VarInsnNode(Opcodes.ALOAD, nodes));
                after.add(new VarInsnNode(Opcodes.ALOAD, level));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "polled", POLL_DESC, false));
                return new Edit(mi, id, new InsnList(), after);
            }
            default:
                throw new IllegalStateException("kind not rewritten: " + kind);
        }
    }

    /** Fresh local slots for each argument of {@code mi} (taken from {@code next}). */
    private static int[] storeArgs(MethodInsnNode mi, int[] next) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        int[] slots = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            slots[i] = next[0];
            next[0] += args[i].getSize();
        }
        return slots;
    }

    /** Stores the call's arguments (top of stack first) into {@code slots}, then loads them back in order. */
    private static InsnList storeAndReload(Type[] args, int[] slots) {
        InsnList il = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) {
            il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), slots[i]));
        }
        for (int i = 0; i < args.length; i++) {
            il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slots[i]));
        }
        return il;
    }

    /** Index of the {@code nth} (1-based) argument of object type {@code internal}, or -1. */
    private static int argOfType(Type[] args, String internal, int nth) {
        int seen = 0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].getSort() == Type.OBJECT && args[i].getInternalName().equals(internal) && ++seen == nth) {
                return i;
            }
        }
        return -1;
    }

    /** Local slot of the method's {@code nth} (1-based) parameter of type {@code internal}, or -1. */
    private static int paramSlot(MethodNode m, String internal, int nth) {
        int slot = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        int seen = 0;
        for (Type t : Type.getArgumentTypes(m.desc)) {
            if (t.getSort() == Type.OBJECT && t.getInternalName().equals(internal) && ++seen == nth) {
                return slot;
            }
            slot += t.getSize();
        }
        return -1;
    }

    /** Whether the method ever stores to local {@code slot}. */
    private static boolean stores(MethodNode m, int slot) {
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof VarInsnNode v && v.var == slot && v.getOpcode() >= Opcodes.ISTORE
                    && v.getOpcode() <= Opcodes.ASTORE) {
                return true;
            }
        }
        return false;
    }

    /** Local slot of parameter {@code index} (0-based) when it has object type {@code internal}, else -1. */
    private static int slotOfParam(MethodNode m, int index, String internal) {
        Type[] params = Type.getArgumentTypes(m.desc);
        if (index >= params.length || params[index].getSort() != Type.OBJECT
                || !params[index].getInternalName().equals(internal)) {
            return -1;
        }
        int slot = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (int i = 0; i < index; i++) {
            slot += params[i].getSize();
        }
        return slot;
    }

    /** A slot past every parameter and every local the method uses. */
    private static int firstFreeLocal(MethodNode m) {
        int free = Type.getArgumentsAndReturnSizes(m.desc) >> 2; // includes "this", so +1 too many for statics
        free = Math.max(free, m.maxLocals);
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof VarInsnNode v) {
                int op = v.getOpcode();
                boolean wide = op == Opcodes.LLOAD || op == Opcodes.DLOAD || op == Opcodes.LSTORE
                        || op == Opcodes.DSTORE;
                free = Math.max(free, v.var + (wide ? 2 : 1));
            } else if (in instanceof IincInsnNode inc) {
                free = Math.max(free, inc.var + 1);
            }
        }
        return free;
    }

    /** The int an instruction pushes (tests). */
    static int intConst(AbstractInsnNode in) {
        if (in instanceof LdcInsnNode l && l.cst instanceof Integer i) {
            return i;
        }
        if (in instanceof IntInsnNode n && (n.getOpcode() == Opcodes.BIPUSH || n.getOpcode() == Opcodes.SIPUSH)) {
            return n.operand;
        }
        if (in.getOpcode() >= Opcodes.ICONST_M1 && in.getOpcode() <= Opcodes.ICONST_5) {
            return in.getOpcode() - Opcodes.ICONST_0;
        }
        throw new IllegalArgumentException("not an int constant: " + in);
    }

    private static void warnOnce(String key, String msg, Object a, Object b) {
        synchronized (WARNED) {
            if (!WARNED.add(key)) {
                return;
            }
        }
        LOGGER.warn(msg, a, b);
    }
}
