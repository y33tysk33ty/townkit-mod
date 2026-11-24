package townkit.core.hook;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Names the other mods' mixins that changed a Towntopia method before TownKit's rewrite ran. The plugin's
 * {@code postApply} sees the class after every mixin aimed at it is in, and Mixin marks each method it merges in with
 * {@code @MixinMerged(mixin = "<class>")}; a redirect or injection leaves the method calling such a handler. So an
 * audited site that's gone, next to calls into handlers of another mod, was most likely taken by that mod.
 */
public final class ForeignMixins {
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final String OLD_CAUSE = "Towntopia changed, or the rewrite failed";

    private ForeignMixins() {
    }

    /**
     * Why audited sites of {@code cls} weren't rewritten, for the load warnings. {@code entries} are site keys or audit
     * mismatches ({@code "<owner>#<name><desc>#..."}); the method is the part after the first {@code #}.
     */
    public static String cause(ClassNode cls, Collection<String> entries) {
        Set<String> methods = new TreeSet<>();
        for (String e : entries) {
            String[] parts = e.split("#", 3);
            if (parts.length >= 2) methods.add(parts[1]);
        }
        Set<String> mixins = new TreeSet<>();
        for (MethodNode m : cls.methods) {
            if (methods.contains(m.name + m.desc)) mixins.addAll(in(cls, m));
        }
        return mixins.isEmpty() ? OLD_CAUSE : "another mod's mixin changed it first: " + String.join(", ", mixins);
    }

    /** Why {@code m}'s audited sites don't match the audit, for the "doesn't match the audit" warnings. */
    public static String auditCause(ClassNode cls, MethodNode m) {
        Set<String> mixins = in(cls, m);
        return mixins.isEmpty() ? "Towntopia changed?" : "another mod's mixin changed it first: " + String.join(", ", mixins);
    }

    /** The other mods' mixins whose merged handlers {@code m} calls (TownKit's own left out), sorted. */
    public static Set<String> in(ClassNode cls, MethodNode m) {
        Set<String> out = new TreeSet<>();
        for (AbstractInsnNode in : m.instructions) {
            if (!(in instanceof MethodInsnNode call) || !call.owner.equals(cls.name)) continue;
            for (MethodNode h : cls.methods) {
                if (!h.name.equals(call.name) || !h.desc.equals(call.desc)) continue;
                String mixin = mergedFrom(h);
                if (mixin != null && !mixin.startsWith("townkit.")) out.add(mixin);
            }
        }
        return out;
    }

    private static String mergedFrom(MethodNode h) {
        String v = mergedFrom(h.visibleAnnotations);
        return v != null ? v : mergedFrom(h.invisibleAnnotations);
    }

    private static String mergedFrom(List<AnnotationNode> annotations) {
        if (annotations == null) return null;
        for (AnnotationNode a : annotations) {
            if (!MERGED.equals(a.desc) || a.values == null) continue;
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                if ("mixin".equals(a.values.get(i)) && a.values.get(i + 1) instanceof String s) return s;
            }
        }
        return null;
    }
}
