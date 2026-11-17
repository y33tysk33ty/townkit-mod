package townkit.core.hook;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Writes TownKit's hooks into Towntopia's bytecode without changing the size of Towntopia's own code, so the JIT inlines
 * and compiles everything exactly as it would unhooked. A hooked method {@code foo} becomes three:
 * <pre>
 *   foo(args)                 // tiny wrapper with foo's name, access and annotations (about 20 bytes)
 *       if (Hooks.STATE[id] == 0) return townkit$foo(args);   // nobody listening: one byte read
 *       return townkit$hooked$foo(args);
 *   townkit$hooked$foo(args)  // private: only runs while something listens (or a census counts calls)
 *       if ((STATE[id] &amp; 1) != 0) { r = Bridges.b_&lt;shape&gt;(id, this, args); if (r != Hooks.PROCEED) return r; }
 *       v = townkit$foo(args);
 *       if ((STATE[id] &amp; 2) != 0) v = Bridges.a&lt;R&gt;_&lt;shape&gt;(v, id, this, args);
 *       return v;
 *   townkit$foo(args)         // private: Towntopia's original body, byte for byte
 * </pre>
 * Listeners see the arguments as passed, even if the body reassigns its parameters. Constructors can't be split
 * (nothing may run before super()), so they get the after-hook in place, before each return.
 * Bridges are shared by every hook with the same erased shape (primitives stay, everything else is Object) and take the
 * hook id as an argument, so one small generated class serves all hooks.
 * Frames are recomputed when Mixin writes the class. Public only so the generator can measure code size at build time.
 */
public final class HookInjector {
    static final String MARKER_FIELD = "townkit$hooks";
    /** Prefix of the renamed original body (shows in stack traces as {@code townkit$foo}). */
    public static final String BODY_PREFIX = "townkit$";
    public static final String HOOKED_PREFIX = "townkit$hooked$";
    private static final String HOOKS = "townkit/core/hook/Hooks";
    /** Bits of Hooks.STATE: has before-listeners, has after-listeners, census counting. */
    public static final int BEFORE_BIT = 1;
    public static final int AFTER_BIT = 2;
    public static final int CENSUS_BIT = 4;
    /** The one generated class holding every bridge, shared by all hooks of the same erased shape. */
    public static final String BRIDGES = "townkit/core/hook/bridge/Bridges";

    private HookInjector() {
    }

    /** Injects every hook of {@code cls} it can find; returns the ids injected. */
    public static List<Integer> inject(ClassNode cls, List<HookInfo> hooks) {
        List<Integer> done = new ArrayList<>();
        boolean itf = (cls.access & Opcodes.ACC_INTERFACE) != 0;
        List<MethodNode> added = new ArrayList<>();
        for (HookInfo h : hooks) {
            for (MethodNode m : cls.methods) {
                if (m.name.equals(h.name()) && m.desc.equals(h.desc()) && (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    if (h.ctor()) {
                        injectConstructor(m, h);
                    } else {
                        split(cls.name, itf, m, h, added);
                    }
                    done.add(h.id());
                    break;
                }
            }
        }
        cls.methods.addAll(added);
        if (!done.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (int id : done) {
                if (ids.length() > 0) ids.append(',');
                ids.append(id);
            }
            cls.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                MARKER_FIELD, "Ljava/lang/String;", null, ids.toString()));
        }
        return done;
    }

    /** Turns {@code m} into the private body and adds the wrapper (taking m's place) and the listener path. */
    private static void split(String owner, boolean itf, MethodNode m, HookInfo h, List<MethodNode> added) {
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        Type[] params = Type.getArgumentTypes(m.desc);
        Type ret = Type.getReturnType(m.desc);
        String name = m.name;
        String bodyName = BODY_PREFIX + name;
        String hookedName = HOOKED_PREFIX + name;
        int keep = Opcodes.ACC_STATIC | Opcodes.ACC_STRICT;

        // the wrapper takes over everything callers and reflection see: name, access, generics, annotations, throws
        MethodNode w = new MethodNode(m.access & ~(Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT),
            name, m.desc, m.signature, m.exceptions.toArray(new String[0]));
        w.visibleAnnotations = m.visibleAnnotations;
        w.invisibleAnnotations = m.invisibleAnnotations;
        w.visibleParameterAnnotations = m.visibleParameterAnnotations;
        w.invisibleParameterAnnotations = m.invisibleParameterAnnotations;
        w.visibleAnnotableParameterCount = m.visibleAnnotableParameterCount;
        w.invisibleAnnotableParameterCount = m.invisibleAnnotableParameterCount;
        w.parameters = m.parameters;
        // receiver and arguments are loaded once and stay on the stack across the branch (smallest wrapper)
        LabelNode slow = new LabelNode();
        InsnList wi = w.instructions;
        loadOwn(wi, isStatic, params);
        wi.add(new FieldInsnNode(Opcodes.GETSTATIC, HOOKS, "STATE", "[B"));
        pushInt(wi, h.id());
        wi.add(new InsnNode(Opcodes.BALOAD));
        wi.add(new JumpInsnNode(Opcodes.IFNE, slow));
        invokeOwn(wi, owner, itf, isStatic, bodyName, m.desc);
        wi.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        wi.add(slow);
        invokeOwn(wi, owner, itf, isStatic, hookedName, m.desc);
        wi.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));

        // the listener path
        MethodNode s = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | (m.access & keep), hookedName, m.desc, null, null);
        InsnList si = s.instructions;
        LabelNode noBefore = new LabelNode();
        LabelNode proceed = new LabelNode();
        LabelNode notCounting = new LabelNode();
        state(si, h.id(), CENSUS_BIT, notCounting);
        pushInt(si, h.id());
        si.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "count", "(I)V", false));
        si.add(notCounting);
        state(si, h.id(), BEFORE_BIT, noBefore);
        pushInt(si, h.id());
        loadSelfAndArgs(si, isStatic, params);
        si.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGES, beforeName(params), beforeDesc(params), false));
        si.add(new InsnNode(Opcodes.DUP));
        si.add(new FieldInsnNode(Opcodes.GETSTATIC, HOOKS, "PROCEED", "Ljava/lang/Object;"));
        si.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, proceed));
        if (ret.getSort() == Type.VOID) {
            si.add(new InsnNode(Opcodes.POP));
            si.add(new InsnNode(Opcodes.RETURN));
        } else {
            unboxOrCast(si, ret);
            si.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        }
        si.add(proceed);
        si.add(new InsnNode(Opcodes.POP));
        si.add(noBefore);
        callOwn(si, owner, itf, isStatic, bodyName, m.desc, params);
        LabelNode noAfter = new LabelNode();
        state(si, h.id(), AFTER_BIT, noAfter);
        if (ret.getSort() == Type.VOID) {
            pushInt(si, h.id());
            loadSelfAndArgs(si, isStatic, params);
            si.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGES, afterName(null, params), afterDesc(params, null), false));
        } else {
            // the return value is on the stack; the bridge takes (ret, id, self, args...) and gives it back
            pushInt(si, h.id());
            loadSelfAndArgs(si, isStatic, params);
            si.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGES, afterName(ret, params), afterDesc(params, ret), false));
            if (isReference(ret) && !ret.getDescriptor().equals("Ljava/lang/Object;")) {
                si.add(new TypeInsnNode(Opcodes.CHECKCAST, ret.getSort() == Type.ARRAY ? ret.getDescriptor() : ret.getInternalName()));
            }
        }
        si.add(noAfter);
        si.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));

        // the original body: untouched code, now private and hidden from reflection-based scanners
        m.name = bodyName;
        m.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | (m.access & (keep | Opcodes.ACC_SYNCHRONIZED));
        m.signature = null;
        m.visibleAnnotations = null;
        m.invisibleAnnotations = null;
        m.visibleParameterAnnotations = null;
        m.invisibleParameterAnnotations = null;
        m.visibleAnnotableParameterCount = 0;
        m.invisibleAnnotableParameterCount = 0;
        m.parameters = null;
        added.add(w);
        added.add(s);
    }

    /** After-hook before every return of a constructor (the object is fully built there). */
    private static void injectConstructor(MethodNode m, HookInfo h) {
        Type[] params = Type.getArgumentTypes(m.desc);
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() == Opcodes.RETURN) returns.add(in);
        }
        for (AbstractInsnNode r : returns) {
            InsnList a = new InsnList();
            LabelNode skip = new LabelNode();
            // constructors never have before-listeners, so any set bit means "after" or "census": no mask needed
            a.add(new FieldInsnNode(Opcodes.GETSTATIC, HOOKS, "STATE", "[B"));
            pushInt(a, h.id());
            a.add(new InsnNode(Opcodes.BALOAD));
            a.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            pushInt(a, h.id());
            loadSelfAndArgs(a, false, params);
            a.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGES, afterName(null, params), afterDesc(params, null), false));
            a.add(skip);
            m.instructions.insertBefore(r, a);
        }
    }

    /** Calls a private method of this class with this method's own receiver and arguments. */
    private static void callOwn(InsnList l, String owner, boolean itf, boolean isStatic, String name, String desc, Type[] params) {
        loadOwn(l, isStatic, params);
        invokeOwn(l, owner, itf, isStatic, name, desc);
    }

    /** Pushes the receiver (if any) and the arguments, as a call to a same-shaped method needs them. */
    private static void loadOwn(InsnList l, boolean isStatic, Type[] params) {
        if (!isStatic) l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = isStatic ? 0 : 1;
        for (Type p : params) {
            l.add(new VarInsnNode(p.getOpcode(Opcodes.ILOAD), slot));
            slot += p.getSize();
        }
    }

    private static void invokeOwn(InsnList l, String owner, boolean itf, boolean isStatic, String name, String desc) {
        l.add(new MethodInsnNode(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner, name, desc, itf));
    }

    /** {@code if ((Hooks.STATE[id] & bit) == 0) goto skip;} (bit is 1, 2 or 4) */
    private static void state(InsnList l, int id, int bit, LabelNode skip) {
        l.add(new FieldInsnNode(Opcodes.GETSTATIC, HOOKS, "STATE", "[B"));
        pushInt(l, id);
        l.add(new InsnNode(Opcodes.BALOAD));
        l.add(new InsnNode(Opcodes.ICONST_0 + bit));
        l.add(new InsnNode(Opcodes.IAND));
        l.add(new JumpInsnNode(Opcodes.IFEQ, skip));
    }

    private static void loadSelfAndArgs(InsnList l, boolean isStatic, Type[] params) {
        if (isStatic) {
            l.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        int slot = isStatic ? 0 : 1;
        for (Type p : params) {
            l.add(new VarInsnNode(p.getOpcode(Opcodes.ILOAD), slot));
            slot += p.getSize();
        }
    }

    private static String erased(Type t) {
        return isReference(t) ? "Ljava/lang/Object;" : t.getDescriptor();
    }

    private static boolean isReference(Type t) {
        return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY;
    }

    // ---- bridge naming, shared with the generator so both always agree ----

    /** One letter per parameter after erasure: the JVM's primitive letters, or L for any object. */
    public static String shape(Type[] params) {
        StringBuilder s = new StringBuilder(params.length);
        for (Type p : params) s.append(letter(p));
        return s.toString();
    }

    private static char letter(Type t) {
        return isReference(t) ? 'L' : t.getDescriptor().charAt(0);
    }

    /** {@code Object b_<shape>(int id, Object self, params...)}: returns Hooks.PROCEED or the value to return instead. */
    public static String beforeName(Type[] params) {
        return "b_" + shape(params);
    }

    /** {@code void aV_<shape>(int id, Object self, params...)} or {@code R a<R>_<shape>(R ret, int id, Object self, params...)}. */
    public static String afterName(Type ret, Type[] params) {
        return "a" + (ret == null || ret.getSort() == Type.VOID ? 'V' : letter(ret)) + "_" + shape(params);
    }

    public static String beforeDesc(Type[] params) {
        StringBuilder d = new StringBuilder("(ILjava/lang/Object;");
        for (Type p : params) d.append(erased(p));
        return d.append(")Ljava/lang/Object;").toString();
    }

    public static String afterDesc(Type[] params, Type ret) {
        StringBuilder d = new StringBuilder("(");
        if (ret != null && ret.getSort() != Type.VOID) d.append(erased(ret));
        d.append("ILjava/lang/Object;");
        for (Type p : params) d.append(erased(p));
        return d.append(')').append(ret == null || ret.getSort() == Type.VOID ? "V" : erased(ret)).toString();
    }

    private static void unboxOrCast(InsnList l, Type t) {
        String box = switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.FLOAT -> "java/lang/Float";
            case Type.LONG -> "java/lang/Long";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
        if (box == null) {
            if (!t.getDescriptor().equals("Ljava/lang/Object;")) {
                l.add(new TypeInsnNode(Opcodes.CHECKCAST, t.getSort() == Type.ARRAY ? t.getDescriptor() : t.getInternalName()));
            }
            return;
        }
        l.add(new TypeInsnNode(Opcodes.CHECKCAST, box));
        String name = switch (t.getSort()) {
            case Type.BOOLEAN -> "booleanValue";
            case Type.BYTE -> "byteValue";
            case Type.CHAR -> "charValue";
            case Type.SHORT -> "shortValue";
            case Type.INT -> "intValue";
            case Type.FLOAT -> "floatValue";
            case Type.LONG -> "longValue";
            default -> "doubleValue";
        };
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, box, name, "()" + t.getDescriptor(), false));
    }

    private static void pushInt(InsnList l, int v) {
        if (v >= -1 && v <= 5) {
            l.add(new InsnNode(Opcodes.ICONST_0 + v));
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            l.add(new IntInsnNode(Opcodes.BIPUSH, v));
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            l.add(new IntInsnNode(Opcodes.SIPUSH, v));
        } else {
            l.add(new LdcInsnNode(v));
        }
    }
}
