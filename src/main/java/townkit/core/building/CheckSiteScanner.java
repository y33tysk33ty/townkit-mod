package townkit.core.building;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import org.objectweb.asm.tree.JumpInsnNode;
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
 * Finds every block check in Towntopia's classes (see {@link CheckSite}). Scans the jar as shipped: methods that
 * TownKit's injector splits at runtime do not exist in it.
 */
public final class CheckSiteScanner {
    private static final String PREFIX = "com/alex/test/";
    private static final String BLOCKS = "net/minecraft/world/level/block/Blocks";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String IS_DESC = "(L" + BLOCK + ";)Z";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Superclass lookups through the class loader; null value = unresolvable. */
    private static final Map<String, String> SUPER_CACHE = new HashMap<>();

    private CheckSiteScanner() {
    }

    public static List<CheckSite> scan(ClassNode cls) {
        String owner = cls.name.startsWith(PREFIX) ? cls.name.substring(PREFIX.length()) : cls.name;
        // a set, so one method testing the same target twice is one site
        Set<CheckSite> out = new LinkedHashSet<>();
        for (MethodNode m : cls.methods) {
            scanMethod(cls, owner, m, out);
        }
        return new ArrayList<>(out);
    }

    public static List<CheckSite> scanJar(Path jar, Predicate<String> ownerFilter) throws IOException {
        List<CheckSite> out = new ArrayList<>();
        forEachClass(jar, ownerFilter, cn -> out.addAll(scan(cn)));
        return out;
    }

    /**
     * Methods of {@code cls} whose data-flow analysis fails, as {@code owner#method}: {@link #scan} cannot see their
     * BLOCK_IS and BLOCK_EQUALS sites (it still sees INSTANCEOF ones). Empty for every class javac produces.
     */
    public static List<String> unanalyzable(ClassNode cls) {
        String owner = cls.name.startsWith(PREFIX) ? cls.name.substring(PREFIX.length()) : cls.name;
        List<String> out = new ArrayList<>();
        for (MethodNode m : cls.methods) {
            if (m.instructions.size() > 0 && analyze(cls, m) == null) {
                out.add(owner + "#" + m.name + m.desc);
            }
        }
        return out;
    }

    public static List<String> unanalyzableInJar(Path jar, Predicate<String> ownerFilter) throws IOException {
        List<String> out = new ArrayList<>();
        forEachClass(jar, ownerFilter, cn -> out.addAll(unanalyzable(cn)));
        return out;
    }

    private static void forEachClass(Path jar, Predicate<String> ownerFilter, java.util.function.Consumer<ClassNode> action)
            throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith(PREFIX) || !n.endsWith(".class")) {
                    continue;
                }
                String owner = n.substring(PREFIX.length(), n.length() - ".class".length());
                if (!ownerFilter.test(owner)) {
                    continue;
                }
                ClassNode cn = new ClassNode();
                try (InputStream in = zip.getInputStream(e)) {
                    new ClassReader(in).accept(cn, 0);
                }
                action.accept(cn);
            }
        }
    }

    /** Frames, or null when the analyzer rejects the method. */
    private static Frame<SourceValue>[] analyze(ClassNode cls, MethodNode m) {
        try {
            return new Analyzer<>(new SourceInterpreter()).analyze(cls.name, m);
        } catch (AnalyzerException ex) {
            return null;
        }
    }

    private static void scanMethod(ClassNode cls, String owner, MethodNode m, Set<CheckSite> out) {
        if (m.instructions.size() == 0) {
            return;
        }
        String method = m.name + m.desc;
        Frame<SourceValue>[] frames = analyze(cls, m);
        if (frames == null) {
            LOGGER.warn("TownKit could not analyse {}#{}: its Blocks.is checks are not found (they stay as Towntopia "
                    + "ships them)", owner, method);
        }
        for (CheckSite s : occurrences(owner, method, m, frames).keySet()) {
            if (s.kind() != CheckSite.Kind.INSTANCEOF || isBlockType(s.target())) {
                out.add(s);
            }
        }
    }

    /**
     * How many instructions of each site key the classes of {@code cls} contain, per method as named in {@code cls}
     * (the jar as shipped). INSTANCEOF keys are counted for every type, block or not.
     */
    public static Map<String, Integer> occurrences(ClassNode cls) {
        String owner = cls.name.startsWith(PREFIX) ? cls.name.substring(PREFIX.length()) : cls.name;
        Map<String, Integer> out = new HashMap<>();
        for (MethodNode m : cls.methods) {
            if (m.instructions.size() == 0) {
                continue;
            }
            occurrences(owner, m.name + m.desc, m, analyze(cls, m)).forEach((s, n) -> out.merge(s.key(), n, Integer::sum));
        }
        return out;
    }

    /**
     * The check sites of one method and how many instructions each has: every {@code INSTANCEOF} (any type), every
     * {@code BlockState.is(Block)} per {@code Blocks} field reaching its argument, and every {@code IF_ACMP} per
     * {@code Blocks} field on either side. Without {@code frames} (analysis failed) only INSTANCEOF is seen. The
     * rewriter uses the same count at class load, so the shipped counts and the runtime agree by construction.
     */
    public static Map<CheckSite, Integer> occurrences(String owner, String method, MethodNode m,
            Frame<SourceValue>[] frames) {
        Map<CheckSite, Integer> out = new java.util.LinkedHashMap<>();
        AbstractInsnNode[] insns = m.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode in = insns[i];
            if (in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.INSTANCEOF) {
                out.merge(new CheckSite(owner, method, CheckSite.Kind.INSTANCEOF, t.desc), 1, Integer::sum);
            } else if (frames != null && frames[i] != null) {
                Frame<SourceValue> f = frames[i];
                if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && mi.name.equals("is") && mi.desc.equals(IS_DESC)
                        && (mi.owner.equals(BLOCK_STATE) || mi.owner.equals(STATE_BASE))) {
                    Set<String> fields = new LinkedHashSet<>();
                    addBlocks(f.getStack(f.getStackSize() - 1), fields);
                    for (String fi : fields) {
                        out.merge(new CheckSite(owner, method, CheckSite.Kind.BLOCK_IS, fi), 1, Integer::sum);
                    }
                } else if (in instanceof JumpInsnNode && (in.getOpcode() == Opcodes.IF_ACMPEQ
                        || in.getOpcode() == Opcodes.IF_ACMPNE)) {
                    Set<String> fields = new LinkedHashSet<>();
                    addBlocks(f.getStack(f.getStackSize() - 1), fields);
                    addBlocks(f.getStack(f.getStackSize() - 2), fields);
                    for (String fi : fields) {
                        out.merge(new CheckSite(owner, method, CheckSite.Kind.BLOCK_EQUALS, fi), 1, Integer::sum);
                    }
                }
            }
        }
        return out;
    }

    private static void addBlocks(SourceValue v, Set<String> out) {
        for (AbstractInsnNode src : v.insns) {
            if (src instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC && fi.owner.equals(BLOCKS)) {
                out.add(fi.name);
            }
        }
    }

    /** True for a strict subclass of Block; an unresolvable name counts if it ends in "Block". */
    private static boolean isBlockType(String type) {
        if (type.startsWith("[") || type.equals(BLOCK)) {
            return false;
        }
        String cur = type;
        for (int depth = 0; depth < 64 && cur != null; depth++) {
            if (cur.equals(BLOCK)) {
                return true;
            }
            String sup = superOf(cur);
            if (sup == null) {
                // chain broke: only the first, unresolvable name falls back to the naming rule
                return cur.equals(type) && type.endsWith("Block");
            }
            if (sup.equals("java/lang/Object")) {
                return false;
            }
            cur = sup;
        }
        return false;
    }

    private static String superOf(String type) {
        synchronized (SUPER_CACHE) {
            if (SUPER_CACHE.containsKey(type)) {
                return SUPER_CACHE.get(type);
            }
        }
        String sup = null;
        ClassLoader cl = CheckSiteScanner.class.getClassLoader();
        try (InputStream in = cl == null ? ClassLoader.getSystemResourceAsStream(type + ".class")
                : cl.getResourceAsStream(type + ".class")) {
            if (in != null) {
                sup = new ClassReader(in).getSuperName();
            }
        } catch (IOException ignored) {
            // unresolvable
        }
        synchronized (SUPER_CACHE) {
            SUPER_CACHE.put(type, sup);
        }
        return sup;
    }
}
