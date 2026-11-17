package townkit.hookgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import townkit.core.hook.HookInjector;

/**
 * Reads the compiled Towntopia jar and generates one TownKit hook (a before and an after injection point) for every
 * real method, except trivial ones and listed hot paths. Output:
 *   - Mixin classes (one per Towntopia class) under the generated Java source folder
 *   - townkit.hooks.mixins.json and townkit/hooks.json (the hook manifest) under the generated resources folder
 *   - a human-readable hook list for docs
 * Re-run after every Towntopia update; hook keys are stable by class, method name and parameter types.
 *
 * Usage: HookGen <towntopia.jar> <outJava> <outResources> <outDoc.md> <rules.txt> [--core <townkit/core source dir>]
 *        [--gametest <GameTest source dir>] [--sites <building-sites.tsv, nav-sites.tsv or work-sites.tsv>]... [--only <classPrefix,...>]
 */
public final class HookGen {
    private static final String ROOT = "com/alex/test/";
    private static final String GEN_PACKAGE = "townkit.mixin.gen";
    private static final String BRIDGE_PACKAGE = "townkit.core.hook.bridge";
    /** Very large methods get no hook: injecting could push them past the JVM method size limit. */
    private static final int MAX_CODE_INSNS = 12000;
    /** Packages that only exist in the player's game (rendering, screens); their hooks go in the mixin config's client list. */
    private static final String[] CLIENT_PACKAGES = {"client/", "ui/screen/", "ui/guide/"};
    /** Never hooked: Towntopia's own test kit, data generators and mixins. */
    private static final String[] SKIPPED_PACKAGES = {"mixin/", "testkit/", "datagen/"};

    /** One line of hook-rules.txt. {@code hits[0]} counts how often it matched, so rules that match nothing can be reported. */
    record Rule(Pattern cls, Pattern method, String kind, String text, int[] hits) {
    }

    /** A method that gets a hook (before the size check), with its key. */
    record Planned(MethodNode mn, String key) {
    }

    private record Hook(int id, String owner, String name, String desc, boolean isStatic, int access, String key, String thread,
                        boolean ctor, boolean client) {
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        Path outJava = Path.of(args[1]);
        Path outRes = Path.of(args[2]);
        Path outDoc = Path.of(args[3]);
        List<Rule> rules = loadRules(Path.of(args[4]));
        List<String> only = new ArrayList<>();
        Path coreDir = null;
        Path gametestDir = null;
        List<Path> siteFiles = new ArrayList<>();
        for (int i = 5; i < args.length; i++) {
            if (args[i].equals("--only") && i + 1 < args.length) {
                for (String s : args[++i].split(",")) only.add(s.trim().replace('.', '/'));
            } else if (args[i].equals("--core") && i + 1 < args.length) {
                coreDir = Path.of(args[++i]);
            } else if (args[i].equals("--gametest") && i + 1 < args.length) {
                gametestDir = Path.of(args[++i]);
            } else if (args[i].equals("--sites") && i + 1 < args.length) {
                siteFiles.add(Path.of(args[++i]));
            }
        }
        // Hook keys TownKit's core and its GameTests use are always generated, whatever the trim says, and the build fails
        // if one is missing (a GameTest key an exclusion rule names may be missing: those tests check it isn't hooked)
        Set<String> coreOnlyKeys = coreDir == null ? Set.of() : coreHookKeys(coreDir);
        Set<String> testKeys = gametestDir == null ? Set.of() : coreHookKeys(gametestDir);
        // owners TownKit rewrites sites in (building-sites.tsv, nav-sites.tsv, work-sites.tsv): a class left with no hooks is still a carrier target
        Set<String> siteOwners = tsvOwners(siteFiles.toArray(new Path[0]));
        Set<String> coreKeys = new java.util.HashSet<>(coreOnlyKeys);
        coreKeys.addAll(testKeys);

        Map<String, ClassNode> classes = new TreeMap<>();
        Map<String, byte[]> bytes = new HashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || !e.getName().startsWith(ROOT)) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    byte[] b = in.readAllBytes();
                    ClassNode cn = new ClassNode();
                    new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES);
                    classes.put(cn.name, cn);
                    bytes.put(cn.name, b);
                }
            }
        }

        ALL = classes;
        List<Hook> hooks = new ArrayList<>();
        Map<String, List<Hook>> byClass = new LinkedHashMap<>();
        Map<String, Integer> skipped = new TreeMap<>();
        SizeCheck sizes = new SizeCheck();
        List<String> bySignature = new ArrayList<>();
        for (ClassNode cn : classes.values()) {
            String why = skipClass(cn, classes);
            if (why != null) {
                skipped.merge("class: " + why, 1, Integer::sum);
                continue;
            }
            String shortCls = shortName(cn.name);
            if (!only.isEmpty() && only.stream().noneMatch(p -> cn.name.startsWith(ROOT + p))) {
                continue;
            }
            String clientWhy = clientReason(cn, classes);
            boolean client = clientWhy != null;
            if ("client signature".equals(clientWhy)) bySignature.add(shortCls);
            List<Planned> planned = planClass(cn, rules, coreKeys, skipped);
            List<MethodNode> candidates = new ArrayList<>();
            for (Planned p : planned) candidates.add(p.mn());
            Map<String, String> tooBig = sizes.check(bytes.get(cn.name), candidates);
            for (Planned p : planned) {
                MethodNode mn = p.mn();
                String big = tooBig.get(mn.name + mn.desc);
                if (big != null) {
                    skipped.merge("method: " + big, 1, Integer::sum);
                    continue;
                }
                String key = p.key();
                String thread = threadHint(shortCls, mn.name, rules);
                Hook h = new Hook(hooks.size(), cn.name, mn.name, mn.desc, (mn.access & Opcodes.ACC_STATIC) != 0, mn.access, key, thread,
                    mn.name.equals("<init>"), client);
                hooks.add(h);
                byClass.computeIfAbsent(cn.name, k -> new ArrayList<>()).add(h);
            }
        }

        if (only.isEmpty()) {
            Set<String> generated = new java.util.HashSet<>();
            for (Hook h : hooks) generated.add(h.key);
            checkCoreKeys(coreOnlyKeys, generated);
            checkTestKeys(testKeys, generated, rules);
        }

        // ---- write mixins ----
        Path pkgDir = outJava.resolve(GEN_PACKAGE.replace('.', '/'));
        Files.createDirectories(pkgDir);
        try (var old = Files.list(pkgDir)) {
            for (Path p : old.toList()) Files.delete(p); // generated folder only (build/generated), rewritten every run
        }
        Path bridgeDir = outJava.resolve(BRIDGE_PACKAGE.replace('.', '/'));
        Files.createDirectories(bridgeDir);
        try (var old = Files.list(bridgeDir)) {
            for (Path p : old.toList()) Files.delete(p); // generated folder only, rewritten every run
        }
        // Four empty carrier mixins (common/client x class/interface) target every hooked class between them. They exist
        // only so Mixin hands each class to TownKit's plugin, which injects the hooks itself.
        Map<String, Boolean> hookedClient = new TreeMap<>();
        for (Map.Entry<String, List<Hook>> e : byClass.entrySet()) hookedClient.put(e.getKey(), e.getValue().get(0).client);
        CarrierPlan plan = carrierPlan(classes, hookedClient, rules, siteOwners);
        Map<String, List<String>> carriers = plan.carriers();
        List<String> ruleCarriers = plan.ruleCarriers();
        List<String> siteCarriers = plan.siteCarriers();
        List<String> allCarriers = new ArrayList<>(ruleCarriers);
        allCarriers.addAll(siteCarriers);
        Files.writeString(bridgeDir.resolve("Bridges.java"), bridgesSource(hooks), StandardCharsets.UTF_8);
        List<String> mixinNames = new ArrayList<>();
        List<String> clientMixinNames = new ArrayList<>();
        for (Map.Entry<String, List<String>> c : carriers.entrySet()) {
            String name = "Hooked" + c.getKey();
            (c.getKey().startsWith("Client") ? clientMixinNames : mixinNames).add(name);
            Files.writeString(pkgDir.resolve(name + ".java"), carrierSource(name, c.getValue(), c.getKey().endsWith("Interfaces")), StandardCharsets.UTF_8);
        }

        // ---- mixin config ----
        Files.createDirectories(outRes.resolve("townkit"));
        JsonObject cfg = new JsonObject();
        cfg.addProperty("required", false);
        cfg.addProperty("minVersion", "0.8");
        cfg.addProperty("package", GEN_PACKAGE);
        cfg.addProperty("compatibilityLevel", "JAVA_21");
        cfg.addProperty("plugin", "townkit.core.hook.HookMixinPlugin");
        JsonArray list = new JsonArray();
        mixinNames.forEach(list::add);
        cfg.add("mixins", list);
        JsonArray clientList = new JsonArray();
        clientMixinNames.forEach(clientList::add);
        cfg.add("client", clientList);
        JsonObject inj = new JsonObject();
        inj.addProperty("defaultRequire", 0);
        cfg.add("injectors", inj);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(outRes.resolve("townkit.hooks.mixins.json"), gson.toJson(cfg), StandardCharsets.UTF_8);

        // ---- manifest ----
        // Compact and line-based so the game reads it in a few milliseconds. One "C" line per class, then its hooks:
        //   C <tab> owner [<tab> client]
        //   flags <tab> name <tab> desc <tab> key after '#' [<tab> thread]     (ids are the line order)
        // flags: access (P public, R protected, V private, K package), then s = static, c = constructor
        StringBuilder mf = new StringBuilder();
        mf.append("townkit-hooks\t1\n");
        mf.append("from\t").append(jar.getFileName()).append('\n');
        mf.append("fingerprint\t").append(fingerprint(jar)).append('\n');
        mf.append("count\t").append(hooks.size()).append('\n');
        for (Map.Entry<String, List<Hook>> e : byClass.entrySet()) {
            mf.append("C\t").append(e.getKey());
            if (e.getValue().get(0).client) mf.append("\tclient");
            mf.append('\n');
            for (Hook h : e.getValue()) {
                mf.append(accessCode(h.access));
                if (h.isStatic) mf.append('s');
                if (h.ctor) mf.append('c');
                mf.append('\t').append(h.name).append('\t').append(h.desc).append('\t').append(h.key.substring(h.key.indexOf('#') + 1));
                if (h.thread != null) mf.append('\t').append(h.thread);
                mf.append('\n');
            }
        }
        Files.writeString(outRes.resolve("townkit/hooks.tsv"), mf.toString(), StandardCharsets.UTF_8);
        Files.deleteIfExists(outRes.resolve("townkit/hooks.json"));

        // ---- docs ----
        StringBuilder md = new StringBuilder();
        md.append("# TownKit hook list\n\n_Generated by `townkit/hookgen` from `").append(jar.getFileName()).append("`. Do not edit by hand._\n\n");
        md.append(hooks.size()).append(" hooks in ").append(byClass.size()).append(" classes. Use the key with `TownKitHooks.before(key, ...)` / `after(key, ...)`.\n\n");
        md.append("Skipped:\n\n");
        skipped.forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append('\n'));
        md.append("\nCarrier-only classes (no hooks, but TownKit's check-site rewrite needs them; ").append(ruleCarriers.size())
            .append(" from `@carrier` rules, ").append(siteCarriers.size()).append(" added because building-sites.tsv, nav-sites.tsv or work-sites.tsv "
            + "name them as rewritten owners and the trim left them without hooks): ")
            .append(allCarriers.isEmpty() ? "none" : allCarriers.stream().map(c -> "`" + shortName(c) + "`")
                .collect(java.util.stream.Collectors.joining(", ")))
            .append('\n');
        md.append('\n').append(sizes.report());
        List<String> dead = only.isEmpty() ? deadRules(rules) : List.of();
        md.append("\nClasses counted as client-only because their own signatures name Minecraft client types (check these after "
            + "each Towntopia update: a shared class here would lose its server hooks): ")
            .append(bySignature.isEmpty() ? "none" : String.join(", ", bySignature)).append('\n');
        md.append("\nRules in hook-rules.txt that matched nothing: ").append(dead.isEmpty() ? "none" : "`" + String.join("`, `", dead) + "`")
            .append('\n');
        String lastPkg = "";
        for (Map.Entry<String, List<Hook>> e : byClass.entrySet()) {
            String cls = shortName(e.getKey());
            String pkg = cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : "(root)";
            if (!pkg.equals(lastPkg)) {
                md.append("\n## ").append(pkg).append("\n");
                lastPkg = pkg;
            }
            md.append("\n### `").append(cls.substring(cls.lastIndexOf('.') + 1)).append("`\n\n");
            for (Hook h : e.getValue()) {
                md.append("- `").append(h.key).append("` → ").append(Type.getReturnType(h.desc).getClassName().replace("com.alex.test.", ""));
                if (h.ctor) md.append(" · constructor (after only)");
                if (h.client) md.append(" · client only");
                if (h.isStatic) md.append(" · static");
                if (!"public".equals(access(h.access))) md.append(" · ").append(access(h.access));
                if (h.thread != null) md.append(" · **runs on ").append(h.thread).append(" thread**");
                md.append('\n');
            }
        }
        Files.createDirectories(outDoc.getParent());
        Files.writeString(outDoc, md.toString(), StandardCharsets.UTF_8);

        System.out.println("[hookgen] " + hooks.size() + " hooks in " + byClass.size() + " classes (" + skipped + ")");
        System.out.println("[hookgen] " + sizes.report().strip().replace("\n", "\n[hookgen] "));
        System.out.println("[hookgen] hook keys always generated: " + coreOnlyKeys.size() + " used by TownKit's core, " + testKeys.size()
            + " by its GameTests");
        System.out.println("[hookgen] client-only by signature: " + (bySignature.isEmpty() ? "none" : String.join(", ", bySignature)));
        for (String d : dead) {
            System.out.println("[hookgen] WARNING: rule matches nothing on this jar (left over or mistyped?): " + d);
        }
    }

    // ---- filtering ----

    private static String skipClass(ClassNode cn, Map<String, ClassNode> all) {
        String n = cn.name.substring(ROOT.length());
        if ((cn.access & (Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) != 0) return "annotation";
        if ((cn.access & Opcodes.ACC_SYNTHETIC) != 0) return "synthetic";
        if (cn.name.matches(".*\\$\\d+.*")) return "anonymous (unstable names)";
        for (String p : SKIPPED_PACKAGES) {
            if (n.startsWith(p)) return "Towntopia test kit, datagen and mixins";
        }
        return null;
    }

    private static boolean namesClientType(String desc) {
        return desc.contains("Lnet/minecraft/client/") || desc.contains("Lcom/mojang/blaze3d/");
    }

    private static boolean isClient(ClassNode cn, Map<String, ClassNode> all) {
        return clientReason(cn, all) != null;
    }

    /**
     * Why a class is client-only, or null if it isn't: a client package, @OnlyIn(Dist.CLIENT), its own signatures naming
     * Minecraft client types, a client superclass, or being an inner class of client code.
     */
    static String clientReason(ClassNode cn, Map<String, ClassNode> all) {
        String n = cn.name.startsWith(ROOT) ? cn.name.substring(ROOT.length()) : cn.name;
        for (String p : CLIENT_PACKAGES) {
            if (n.startsWith(p)) return "client package";
        }
        if (cn.visibleAnnotations != null) {
            for (var a : cn.visibleAnnotations) {
                if (a.desc.equals("Lnet/neoforged/api/distmarker/OnlyIn;")) return "@OnlyIn";
            }
        }
        // a class whose own signatures name Minecraft client types can't even be listed on a dedicated server
        // (e.g. 0.1.2's DynamicVillagesClient: config-screen lambdas taking a Screen)
        for (MethodNode m : cn.methods) {
            if (namesClientType(m.desc)) return "client signature";
        }
        for (var f : cn.fields) {
            if (namesClientType(f.desc)) return "client signature";
        }
        String sup = cn.superName;
        if (sup != null) {
            if (sup.startsWith("net/minecraft/client/") || sup.startsWith("software/bernie/geckolib/renderer")
                || sup.startsWith("software/bernie/geckolib/model")) return "client superclass";
            ClassNode parent = all.get(sup);
            if (parent != null && parent != cn && isClient(parent, all)) return "client superclass";
        }
        // an inner class of client code is client code
        int dollar = cn.name.indexOf('$');
        if (dollar > 0) {
            ClassNode outer = all.get(cn.name.substring(0, dollar));
            if (outer != null && isClient(outer, all)) return "inner class of client code";
        }
        return null;
    }

    /** All Towntopia classes, for checking whether an array element type can be named from the generated mixins. */
    private static Map<String, ClassNode> ALL = Map.of();

    private static boolean accessible(String internal) {
        ClassNode cn = ALL.get(internal);
        if (cn == null) return true; // Minecraft/JDK/library type: assume public
        if ((cn.access & Opcodes.ACC_PUBLIC) == 0) return false;
        for (var ic : cn.innerClasses) {
            if (ic.name.equals(internal) && ic.outerName != null) {
                return (ic.access & Opcodes.ACC_PUBLIC) != 0 && accessible(ic.outerName);
            }
        }
        return true;
    }

    static String skipMethod(ClassNode cn, MethodNode mn, List<Rule> rules) {
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return "abstract/native";
        if ((mn.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) return "synthetic (lambdas, bridges)";
        if (mn.name.equals("<clinit>")) return "static initializer";
        if (mn.name.startsWith("lambda$") || mn.name.startsWith("access$")) return "synthetic (lambdas, bridges)";
        if (mn.name.equals("equals") || mn.name.equals("hashCode") || mn.name.equals("toString") || mn.name.equals("compareTo")) return "equals/hashCode/toString";
        if ((cn.access & Opcodes.ACC_ENUM) != 0 && (mn.name.equals("values") || mn.name.equals("valueOf") || mn.name.equals("$values"))) return "enum boilerplate";
        int insns = 0;
        for (AbstractInsnNode in : mn.instructions) {
            if (in.getOpcode() >= 0) insns++;
        }
        if (insns > MAX_CODE_INSNS) return "huge method";
        String shortCls = shortName(cn.name);
        for (Rule r : rules) {
            if (!"exclude".equals(r.kind)) continue;
            if (r.cls.matcher(shortCls).matches() && (r.method == null || r.method.matcher(mn.name).matches())) {
                r.hits[0]++;
                return "hot path (rules file)";
            }
        }
        return null;
    }

    /**
     * The methods of one class that get a hook, in class order, with their keys; every method left out is counted in
     * {@code skipped}. Overload suffixes ({@code ~2}, {@code ~3}) are counted over every method that passes
     * {@link #skipMethod}, before the trim categories, so a public key never moves because a private sibling was cut.
     */
    static List<Planned> planClass(ClassNode cn, List<Rule> rules, Set<String> coreKeys, Map<String, Integer> skipped) {
        String shortCls = shortName(cn.name);
        Map<String, Integer> keyUse = new HashMap<>();
        List<Planned> out = new ArrayList<>();
        for (MethodNode mn : cn.methods) {
            String why = skipMethod(cn, mn, rules);
            if (why != null) {
                skipped.merge("method: " + why, 1, Integer::sum);
                continue;
            }
            String key = shortCls + "#" + mn.name + "(" + simpleParams(mn.desc) + ")";
            int n = keyUse.merge(key, 1, Integer::sum);
            if (n > 1) key = key + "~" + n;
            String trim = trimReason(cn, mn);
            if (trim != null && !kept(shortCls, mn.name, rules, coreKeys, key)) {
                skipped.merge("method: " + trim, 1, Integer::sum);
                continue;
            }
            out.add(new Planned(mn, key));
        }
        return out;
    }

    /** Method names that only move a packet to and from bytes, in a {@code network} class. */
    private static final Set<String> PACKET_PLUMBING = Set.of("encode", "decode", "write", "read", "toBytes", "fromBytes");
    /** Static factories of a packet's codec, in a {@code network} class. */
    private static final Set<String> PACKET_CODECS = Set.of("codec", "streamCodec");

    /**
     * The 0.6.1 trim (slimness, not speed): why this method gets no hook although it passed {@link #skipMethod}, or null
     * to hook it. Four categories, judged by shape only and conservative: anything that doesn't match a shape exactly
     * keeps its hook. A {@code keep} rule (or a key core uses) brings a trimmed hook back.
     */
    static String trimReason(ClassNode cn, MethodNode mn) {
        if ((mn.access & Opcodes.ACC_PRIVATE) != 0) return "private method";
        String shortCls = shortName(cn.name);
        if (shortCls.startsWith("network.") || shortCls.contains(".network.")) {
            if (PACKET_PLUMBING.contains(mn.name)) return "packet plumbing";
            if (PACKET_CODECS.contains(mn.name) && (mn.access & Opcodes.ACC_STATIC) != 0) return "packet plumbing";
        }
        List<AbstractInsnNode> code = code(mn);
        if (mn.name.equals("<init>")) {
            return isDataClassConstructor(cn, mn, code) ? "data-class constructor" : null;
        }
        return isPlainGetter(cn, mn, code) || isPlainSetter(cn, mn, code) ? "plain getter/setter" : null;
    }

    /** The real instructions (labels, line numbers and frames left out). */
    private static List<AbstractInsnNode> code(MethodNode mn) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode in : mn.instructions) {
            if (in.getOpcode() >= 0) out.add(in);
        }
        return out;
    }

    private static boolean isReturn(AbstractInsnNode in) {
        return in.getOpcode() >= Opcodes.IRETURN && in.getOpcode() <= Opcodes.ARETURN;
    }

    private static boolean isLoad(AbstractInsnNode in, int slot) {
        return in.getOpcode() >= Opcodes.ILOAD && in.getOpcode() <= Opcodes.ALOAD
            && ((org.objectweb.asm.tree.VarInsnNode) in).var == slot;
    }

    private static boolean ownField(AbstractInsnNode in, int opcode, ClassNode cn) {
        return in.getOpcode() == opcode && ((org.objectweb.asm.tree.FieldInsnNode) in).owner.equals(cn.name);
    }

    /** {@code return this.f;} (or {@code return f;} for a static field of this class), with no parameters. */
    private static boolean isPlainGetter(ClassNode cn, MethodNode mn, List<AbstractInsnNode> code) {
        if (Type.getArgumentTypes(mn.desc).length != 0 || Type.getReturnType(mn.desc).getSort() == Type.VOID) return false;
        if (code.size() == 3) {
            return (mn.access & Opcodes.ACC_STATIC) == 0 && isLoad(code.get(0), 0) && ownField(code.get(1), Opcodes.GETFIELD, cn)
                && isReturn(code.get(2));
        }
        return code.size() == 2 && ownField(code.get(0), Opcodes.GETSTATIC, cn) && isReturn(code.get(1));
    }

    /** {@code this.f = v;} (or {@code f = v;} for a static field of this class), one parameter, returning nothing. */
    private static boolean isPlainSetter(ClassNode cn, MethodNode mn, List<AbstractInsnNode> code) {
        if (Type.getArgumentTypes(mn.desc).length != 1 || Type.getReturnType(mn.desc).getSort() != Type.VOID) return false;
        if ((mn.access & Opcodes.ACC_STATIC) == 0) {
            return code.size() == 4 && isLoad(code.get(0), 0) && isLoad(code.get(1), 1) && ownField(code.get(2), Opcodes.PUTFIELD, cn)
                && code.get(3).getOpcode() == Opcodes.RETURN;
        }
        return code.size() == 3 && isLoad(code.get(0), 0) && ownField(code.get(1), Opcodes.PUTSTATIC, cn)
            && code.get(2).getOpcode() == Opcodes.RETURN;
    }

    /**
     * A constructor of a record, or of a final class extending Object, that calls the no-argument super constructor and
     * then only stores its own parameters into this class's fields. Anything else (a call, a check, a constant, a
     * subclassable class, no field stored at all) keeps the hook.
     */
    private static boolean isDataClassConstructor(ClassNode cn, MethodNode mn, List<AbstractInsnNode> code) {
        boolean record = "java/lang/Record".equals(cn.superName);
        boolean finalValue = (cn.access & Opcodes.ACC_FINAL) != 0 && "java/lang/Object".equals(cn.superName);
        if (!record && !finalValue) return false;
        if (!record) {
            // a plain value holder has nothing but getters (and equals/hashCode/toString): any other instance method
            // (render, tick, a computing method) means the class does something, so its constructor keeps the hook
            for (MethodNode other : cn.methods) {
                if (other == mn || other.name.equals("<init>") || (other.access & Opcodes.ACC_STATIC) != 0) continue;
                if (other.name.equals("equals") || other.name.equals("hashCode") || other.name.equals("toString")) continue;
                if (!isPlainGetter(cn, other, code(other))) return false;
            }
        }
        if (code.size() < 3 || !isLoad(code.get(0), 0)) return false;
        if (!(code.get(1) instanceof org.objectweb.asm.tree.MethodInsnNode sup) || sup.getOpcode() != Opcodes.INVOKESPECIAL
            || !sup.owner.equals(cn.superName) || !sup.name.equals("<init>") || !sup.desc.equals("()V")) return false;
        Set<Integer> paramSlots = new java.util.HashSet<>();
        int slot = 1;
        for (Type t : Type.getArgumentTypes(mn.desc)) {
            paramSlots.add(slot);
            slot += t.getSize();
        }
        int i = 2;
        while (i + 2 < code.size()) {
            AbstractInsnNode value = code.get(i + 1);
            if (!isLoad(code.get(i), 0) || !(value instanceof org.objectweb.asm.tree.VarInsnNode v) || !isLoad(value, v.var)
                || !paramSlots.contains(v.var) || !ownField(code.get(i + 2), Opcodes.PUTFIELD, cn)) return false;
            i += 3;
        }
        // at least one stored parameter: an empty constructor of a final class isn't clearly a value holder
        return i > 2 && i == code.size() - 1 && code.get(i).getOpcode() == Opcodes.RETURN;
    }

    /** A whole hook key: {@code pkg.Class#method(Params)} with an optional {@code ~n}. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.$]+#[A-Za-z0-9_<>$]+\\([^\"#]*\\)(~\\d+)?");

    /** Every hook key the Java sources under {@code dir} (TownKit's core, or its GameTests) ask for. */
    static Set<String> coreHookKeys(Path dir) throws IOException {
        Set<String> out = new java.util.TreeSet<>();
        try (var files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                out.addAll(hookKeysIn(Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    /** One Java token: 'S' a string literal (its raw text between the quotes), 'I' an identifier, 'P' one other char. */
    private record Tok(char kind, String text) {
    }

    /**
     * The hook keys one Java source asks for: whole string literals shaped like a key, and keys built as
     * {@code PREFIX + "method(Params)"} from a {@code static final String PREFIX = "pkg.Class#";} in the same file.
     * Works on real tokens, so comments, escapes and char literals never shift the pairing of quotes.
     */
    static Set<String> hookKeysIn(String source) {
        List<Tok> t = tokenize(source);
        Map<String, String> prefixes = new HashMap<>();
        for (int i = 0; i + 4 < t.size(); i++) {
            if (is(t.get(i), 'I', "String") && t.get(i + 1).kind == 'I' && is(t.get(i + 2), 'P', "=") && t.get(i + 3).kind == 'S'
                && is(t.get(i + 4), 'P', ";") && t.get(i + 3).text.matches("[A-Za-z0-9_.$]+#")) {
                boolean isFinal = false;
                boolean isStatic = false;
                for (int j = Math.max(0, i - 4); j < i; j++) {
                    isFinal |= is(t.get(j), 'I', "final");
                    isStatic |= is(t.get(j), 'I', "static");
                }
                if (isFinal && isStatic) prefixes.put(t.get(i + 1).text, t.get(i + 3).text);
            }
        }
        Set<String> out = new java.util.TreeSet<>();
        for (int i = 0; i < t.size(); i++) {
            Tok k = t.get(i);
            if (k.kind == 'S' && KEY.matcher(k.text).matches()) out.add(k.text);
            if (k.kind == 'I' && prefixes.containsKey(k.text) && i + 2 < t.size() && is(t.get(i + 1), 'P', "+") && t.get(i + 2).kind == 'S') {
                String key = prefixes.get(k.text) + t.get(i + 2).text;
                if (KEY.matcher(key).matches()) out.add(key);
            }
        }
        return out;
    }

    private static boolean is(Tok t, char kind, String text) {
        return t.kind == kind && t.text.equals(text);
    }

    /** A small Java lexer: comments and whitespace dropped, char literals dropped, escapes honoured in strings. */
    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (s.startsWith("//", i)) {
                int end = s.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (s.startsWith("\"\"\"", i)) { // text block
                int j = i + 3;
                while (j < n && !s.startsWith("\"\"\"", j)) j += s.charAt(j) == '\\' ? 2 : 1;
                out.add(new Tok('S', s.substring(i + 3, Math.min(j, n))));
                i = Math.min(j + 3, n);
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) j += s.charAt(j) == '\\' ? 2 : 1;
                if (c == '"') out.add(new Tok('S', s.substring(i + 1, Math.min(j, n))));
                i = Math.min(j + 1, n);
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) j++;
                out.add(new Tok('I', s.substring(i, j)));
                i = j;
            } else {
                out.add(new Tok('P', String.valueOf(c)));
                i++;
            }
        }
        return out;
    }

    /**
     * Fails the build when a hook key TownKit's GameTests ask for was not generated, unless an exclusion rule in
     * hook-rules.txt names it: some GameTests check that a hot path is NOT hooked. Rule hit counts are not touched.
     */
    static void checkTestKeys(Set<String> keys, Set<String> generated, List<Rule> rules) {
        List<String> missing = new ArrayList<>();
        for (String k : keys) {
            if (generated.contains(k)) continue;
            String cls = k.substring(0, k.indexOf('#'));
            String method = k.substring(k.indexOf('#') + 1, k.indexOf('('));
            boolean excluded = rules.stream().anyMatch(r -> "exclude".equals(r.kind) && r.cls.matcher(cls).matches()
                && (r.method == null || r.method.matcher(method).matches()));
            if (!excluded) missing.add(k);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("hook keys TownKit's GameTests use are not generated (renamed in Towntopia?): " + missing);
        }
    }

    /** Fails the build when a hook key core asks for was not generated (trimmed keys are kept, so this means excluded or gone). */
    static void checkCoreKeys(Set<String> coreKeys, Set<String> generated) {
        List<String> missing = new ArrayList<>();
        for (String k : coreKeys) {
            if (!generated.contains(k)) missing.add(k);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("hook keys TownKit's core uses are not generated (renamed in Towntopia, or excluded in "
                + "hook-rules.txt?): " + missing);
        }
    }

    /**
     * Whether a hook the trim would cut is kept anyway: a {@code keep} rule names it, or core uses its key
     * ({@code coreKeys}, filled by the build). Every keep rule is checked so each one's hit count is right.
     */
    static boolean kept(String shortCls, String method, List<Rule> rules, Set<String> coreKeys, String key) {
        boolean keep = coreKeys.contains(key);
        for (Rule r : rules) {
            if ("keep".equals(r.kind) && r.cls.matcher(shortCls).matches() && (r.method == null || r.method.matcher(method).matches())) {
                r.hits[0]++;
                keep = true;
            }
        }
        return keep;
    }

    static String threadHint(String shortCls, String method, List<Rule> rules) {
        for (Rule r : rules) {
            if (r.kind.startsWith("thread:") && r.cls.matcher(shortCls).matches() && (r.method == null || r.method.matcher(method).matches())) {
                r.hits[0]++;
                return r.kind.substring("thread:".length());
            }
        }
        return null;
    }

    private static List<Rule> loadRules(Path file) throws IOException {
        List<Rule> rules = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Rule r = parseRule(raw);
            if (r != null) rules.add(r);
        }
        return rules;
    }

    /** One rules-file line ('*' is a wildcard in the class part, the method part is a regex), or null for blanks and comments. */
    static Rule parseRule(String raw) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) return null;
        String text = line;
        String kind = "exclude";
        if (line.startsWith("@")) {
            int sp = line.indexOf(' ');
            String tag = line.substring(1, sp);
            kind = tag.equals("carrier") ? "carrier" : "thread:" + tag;
            line = line.substring(sp + 1).strip();
        } else if (line.startsWith("keep ")) {
            kind = "keep";
            line = line.substring("keep ".length()).strip();
        }
        String cls = line;
        String method = null;
        int hash = line.indexOf('#');
        if (hash >= 0) {
            cls = line.substring(0, hash);
            method = line.substring(hash + 1);
        }
        return new Rule(Pattern.compile(cls.replace(".", "\\.").replace("$", "\\$").replace("*", ".*")),
            method == null ? null : Pattern.compile(method), kind, text, new int[1]);
    }

    /**
     * Classes named by {@code @carrier} rules that have no hooks: the carrier mixins target them too, so Mixin hands them
     * to TownKit's plugin (which rewrites their nav sites) although nothing is injected. Common side only.
     */
    static List<String> carrierOnly(Map<String, ClassNode> classes, java.util.Set<String> hooked, List<Rule> rules) {
        List<String> out = new ArrayList<>();
        for (Rule r : rules) {
            if (!"carrier".equals(r.kind)) continue;
            for (String name : new java.util.TreeSet<>(classes.keySet())) {
                if (name.startsWith(ROOT) && r.cls.matcher(shortName(name)).matches()) {
                    r.hits[0]++;
                    if (!hooked.contains(name) && !out.contains(name)) out.add(name);
                }
            }
        }
        return out;
    }

    /** Which classes each carrier mixin (CommonClasses, CommonInterfaces, ClientClasses, ClientInterfaces) targets. */
    record CarrierPlan(Map<String, List<String>> carriers, List<String> ruleCarriers, List<String> siteCarriers) {
    }

    /**
     * Assigns every hooked class (name -> client only) and every hook-less carrier (from {@code @carrier} rules, then from
     * the tsv owners) to a carrier mixin. Hook-less carriers go on the common side.
     */
    static CarrierPlan carrierPlan(Map<String, ClassNode> classes, Map<String, Boolean> hookedClient, List<Rule> rules,
                                   Set<String> siteOwners) {
        Map<String, List<String>> carriers = new TreeMap<>();
        for (Map.Entry<String, Boolean> e : hookedClient.entrySet()) {
            boolean isInterface = (classes.get(e.getKey()).access & Opcodes.ACC_INTERFACE) != 0;
            String carrier = (e.getValue() ? "Client" : "Common") + (isInterface ? "Interfaces" : "Classes");
            carriers.computeIfAbsent(carrier, k -> new ArrayList<>()).add(e.getKey());
        }
        // "@carrier" classes have no hooks but still go to the plugin (TownKit's nav-site rewrite runs there)
        List<String> ruleCarriers = carrierOnly(classes, hookedClient.keySet(), rules);
        List<String> siteCarriers = carrierOnly(classes, hookedClient.keySet(), List.of(), siteOwners);
        // load-bearing: NavCells$1 is both an @carrier rule target and a nav-sites REWRITE owner; without this it would be
        // listed twice among HookedCommonClasses' targets
        siteCarriers.removeAll(ruleCarriers);
        List<String> all = new ArrayList<>(ruleCarriers);
        all.addAll(siteCarriers);
        for (String owner : all) {
            boolean isInterface = (classes.get(owner).access & Opcodes.ACC_INTERFACE) != 0;
            carriers.computeIfAbsent("Common" + (isInterface ? "Interfaces" : "Classes"), k -> new ArrayList<>()).add(owner);
        }
        return new CarrierPlan(carriers, ruleCarriers, siteCarriers);
    }

    /**
     * Owners the tsv files need rewritten, as names relative to com/alex/test/: {@code # scope <owner>} lines of
     * building-sites.tsv, and owners of REWRITE rows of nav-sites.tsv and work-sites.tsv (key = owner#method#kind, verdict
     * third column).
     * Owners that only have AUDIT_ONLY or KEEP rows are never rewritten and so need no carrier.
     */
    static Set<String> tsvOwners(Path... files) throws IOException {
        Set<String> out = new java.util.TreeSet<>();
        for (Path f : files) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("# scope ")) {
                    out.add(line.substring("# scope ".length()).strip());
                } else if (!line.startsWith("#") && !line.isBlank()) {
                    String[] cols = line.split("\t");
                    if (cols.length > 2 && cols[0].contains("#") && "REWRITE".equals(cols[2])) {
                        out.add(cols[0].substring(0, cols[0].indexOf('#')));
                    }
                }
            }
        }
        return out;
    }

    /** Classes in the jar named by {@code owners} (relative names) that have no hooks. */
    static List<String> carrierOnly(Map<String, ClassNode> classes, Set<String> hooked, List<Rule> rules, Set<String> owners) {
        List<String> out = carrierOnly(classes, hooked, rules);
        for (String owner : owners) {
            String name = ROOT + owner;
            if (classes.containsKey(name) && !hooked.contains(name) && !out.contains(name)) out.add(name);
        }
        return out;
    }

    /** Rules that matched nothing in this run (as written in the file): left over from an older Towntopia, or mistyped. */
    static List<String> deadRules(List<Rule> rules) {
        List<String> dead = new ArrayList<>();
        for (Rule r : rules) {
            if (r.hits[0] == 0) dead.add(r.text);
        }
        return dead;
    }

    // ---- code generation ----

    /** An empty mixin: it only makes Mixin hand this class to TownKit's plugin, which injects the hooks itself. */
    private static String carrierSource(String mixinName, List<String> owners, boolean isInterface) {
        StringBuilder s = new StringBuilder();
        s.append("// GENERATED by townkit/hookgen. Do not edit; re-run the generator instead.\n");
        s.append("package ").append(GEN_PACKAGE).append(";\n\n");
        s.append("import org.spongepowered.asm.mixin.Mixin;\n\n");
        s.append("@Mixin(remap = false, targets = {\n");
        for (int i = 0; i < owners.size(); i++) {
            s.append("    \"").append(owners.get(i).replace('/', '.')).append(i + 1 < owners.size() ? "\",\n" : "\"\n");
        }
        s.append("})\n");
        s.append(isInterface ? "public interface " : "public abstract class ").append(mixinName).append(" {\n}\n");
        return s.toString();
    }

    /**
     * The static bridges the injected bytecode calls, only while a hook has listeners. One per erased shape (primitives
     * stay, everything else is Object), shared by every hook of that shape; the hook id comes in as an argument.
     * Names and descriptors come from HookInjector, so generator and injector always agree.
     */
    private static String bridgesSource(List<Hook> hooks) {
        TreeMap<String, String> methods = new TreeMap<>();
        for (Hook h : hooks) {
            Type[] params = Type.getArgumentTypes(h.desc);
            Type ret = Type.getReturnType(h.desc);
            StringBuilder sig = new StringBuilder("int id, Object self");
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < params.length; i++) {
                sig.append(", ").append(erased(params[i])).append(" p").append(i);
                if (i > 0) args.append(", ");
                args.append('p').append(i);
            }
            if (!h.ctor) { // a constructor can't be skipped, so it only has an after side
                methods.computeIfAbsent(HookInjector.beforeName(params), n -> "    public static Object " + n + "(" + sig + ") {\n"
                    + "        return Hooks.dispatchBefore(id, self, new Object[]{" + args + "});\n    }\n");
            }
            if (ret.getSort() == Type.VOID) {
                methods.computeIfAbsent(HookInjector.afterName(null, params), n -> "    public static void " + n + "(" + sig + ") {\n"
                    + "        Hooks.dispatchAfter(id, self, new Object[]{" + args + "}, null);\n    }\n");
            } else {
                String r = erased(ret);
                methods.computeIfAbsent(HookInjector.afterName(ret, params), n -> "    public static " + r + " " + n + "(" + r + " ret, " + sig + ") {\n"
                    + "        return (" + boxed(r) + ") Hooks.dispatchAfter(id, self, new Object[]{" + args + "}, ret);\n    }\n");
            }
        }
        StringBuilder s = new StringBuilder();
        s.append("// GENERATED by townkit/hookgen. Do not edit; re-run the generator instead.\n");
        s.append("package ").append(BRIDGE_PACKAGE).append(";\n\n");
        s.append("import townkit.core.hook.Hooks;\n\n");
        s.append("/** Shared bridges from hooked Towntopia code to TownKit's dispatcher, one per erased shape (").append(methods.size())
            .append("). Only called while a hook has listeners. */\n");
        s.append("@SuppressWarnings(\"unused\")\n");
        s.append("public final class Bridges {\n");
        s.append("    private Bridges() {\n    }\n");
        methods.values().forEach(m -> s.append('\n').append(m));
        s.append("}\n");
        return s.toString();
    }

    private static String erased(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> "boolean";
            case Type.BYTE -> "byte";
            case Type.CHAR -> "char";
            case Type.SHORT -> "short";
            case Type.INT -> "int";
            case Type.FLOAT -> "float";
            case Type.LONG -> "long";
            case Type.DOUBLE -> "double";
            default -> "Object";
        };
    }

    private static String boxed(String primitiveOrObject) {
        return switch (primitiveOrObject) {
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "char" -> "Character";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "float" -> "Float";
            case "long" -> "Long";
            case "double" -> "Double";
            default -> "Object";
        };
    }

    // ---- naming ----

    static String shortName(String internal) {
        String n = internal.startsWith(ROOT) ? internal.substring(ROOT.length()) : internal;
        return n.replace('/', '.');
    }

    private static String simpleParams(String desc) {
        StringBuilder b = new StringBuilder();
        Type[] ts = Type.getArgumentTypes(desc);
        for (int i = 0; i < ts.length; i++) {
            if (i > 0) b.append(',');
            String c = ts[i].getClassName();
            String simple = c.substring(c.lastIndexOf('.') + 1).replace('$', '.');
            b.append(simple);
        }
        return b.toString();
    }

    private static String access(int a) {
        if ((a & Opcodes.ACC_PUBLIC) != 0) return "public";
        if ((a & Opcodes.ACC_PROTECTED) != 0) return "protected";
        if ((a & Opcodes.ACC_PRIVATE) != 0) return "private";
        return "package";
    }

    private static char accessCode(int a) {
        if ((a & Opcodes.ACC_PUBLIC) != 0) return 'P';
        if ((a & Opcodes.ACC_PROTECTED) != 0) return 'R';
        if ((a & Opcodes.ACC_PRIVATE) != 0) return 'V';
        return 'K';
    }

    /**
     * Identifies Towntopia's code from the jar's index alone: SHA-256 over "name:crc" of every class, sorted. The game
     * computes the same from the installed jar (townkit.core.VersionGuard) without reading any class.
     */
    static String fingerprint(Path jar) throws Exception {
        TreeMap<String, Long> crcs = new TreeMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.getName().startsWith(ROOT) && e.getName().endsWith(".class")) crcs.put(e.getName(), e.getCrc());
            }
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        crcs.forEach((n, c) -> md.update((n + ":" + Long.toHexString(c) + "\n").getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(md.digest());
    }
}
