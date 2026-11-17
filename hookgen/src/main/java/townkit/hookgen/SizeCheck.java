package townkit.hookgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookInjector;

/**
 * Runs the mod's real injector over each Towntopia class at build time and measures how much every hooked method grows.
 * The JVM treats method size as a hard line in three places (HotSpot defaults):
 * <ul>
 *   <li>8000 bytes ({@code HugeMethodLimit}): bigger methods are never JIT-compiled and stay interpreted, roughly 10x slower.
 *       A hook that would push a method over it is dropped.</li>
 *   <li>325 bytes ({@code FreqInlineSize}): bigger methods aren't inlined into hot callers. Reported.</li>
 *   <li>35 bytes ({@code MaxInlineSize}): bigger methods aren't inlined into lukewarm callers. Reported.</li>
 * </ul>
 */
final class SizeCheck {
    static final int HUGE = 8000;
    static final int FREQ_INLINE = 325;
    static final int MAX_INLINE = 35;
    private static final int JVM_MAX = 65535;

    private final List<int[]> grown = new ArrayList<>(); // {before, after}
    private final List<String> crossedFreq = new ArrayList<>();
    private final List<String> dropped = new ArrayList<>();
    private int ctorsCrossedMax;

    /** Returns the methods (name + desc) whose hook must be dropped, with the reason. */
    Map<String, String> check(byte[] original, List<MethodNode> candidates) throws Exception {
        Map<String, String> drop = new HashMap<>();
        if (candidates.isEmpty()) return drop;
        ClassNode fresh = new ClassNode();
        new ClassReader(original).accept(fresh, ClassReader.SKIP_FRAMES);
        List<HookInfo> infos = new ArrayList<>();
        for (MethodNode mn : candidates) {
            // ids of this size are what the real build uses (SIPUSH), so the measurement matches the shipped bytecode
            infos.add(new HookInfo(10_000 + infos.size(), "", fresh.name, mn.name, mn.desc, (mn.access & Opcodes.ACC_STATIC) != 0, "",
                null, mn.name.equals("<init>"), false));
        }
        HookInjector.inject(fresh, infos);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        fresh.accept(cw);
        Map<String, Integer> before = CodeSize.of(original);
        Map<String, Integer> after = CodeSize.of(cw.toByteArray());
        for (MethodNode mn : candidates) {
            String id = mn.name + mn.desc;
            int b = before.getOrDefault(id, 0);
            int a = after.getOrDefault(id, 0);
            String where = HookGen.shortName(fresh.name) + "#" + mn.name;
            if (a > JVM_MAX) {
                drop.put(id, "too big for the JVM once hooked");
                this.dropped.add(where + " (" + b + " -> " + a + " bytes)");
            } else if (b <= HUGE && a > HUGE) {
                drop.put(id, "hook would stop the JIT compiling it (over " + HUGE + " bytes)");
                this.dropped.add(where + " (" + b + " -> " + a + " bytes)");
            } else {
                this.grown.add(new int[]{b, a});
                if (b <= FREQ_INLINE && a > FREQ_INLINE) this.crossedFreq.add(where + " (" + b + " -> " + a + ")");
                if (mn.name.equals("<init>") && b <= MAX_INLINE && a > MAX_INLINE) this.ctorsCrossedMax++;
            }
        }
        return drop;
    }

    String report() {
        int n = this.grown.size();
        if (n == 0) return "";
        int[] added = new int[n];
        long total = 0;
        int crossedMax = 0;
        int alreadyHuge = 0;
        for (int i = 0; i < n; i++) {
            int[] g = this.grown.get(i);
            added[i] = g[1] - g[0];
            total += added[i];
            if (g[0] <= MAX_INLINE && g[1] > MAX_INLINE) crossedMax++;
            if (g[0] > HUGE) alreadyHuge++;
        }
        Arrays.sort(added);
        StringBuilder r = new StringBuilder();
        r.append("Code size as callers see it (measured by running the real injector on the jar). A hooked method keeps its body "
            + "byte for byte under a private name and callers reach it through a small wrapper; constructors are hooked in place.\n\n");
        r.append(String.format(Locale.ROOT, "- %d hooked methods: size change seen by callers %+d bytes in total; median %+d, 90th percentile %+d, max %+d bytes%n",
            n, total, added[n / 2], added[(int) (n * 0.9)], added[n - 1]));
        r.append(String.format(Locale.ROOT, "- crossed %d bytes (no longer inlined into lukewarm callers): %d, %d of them constructors%n", MAX_INLINE,
            crossedMax, this.ctorsCrossedMax));
        r.append(String.format(Locale.ROOT, "- crossed %d bytes (no longer inlined into hot callers): %d%n", FREQ_INLINE, this.crossedFreq.size()));
        r.append(String.format(Locale.ROOT, "- over %d bytes (never JIT-compiled, already so before hooking): %d%n", HUGE, alreadyHuge));
        r.append(String.format(Locale.ROOT, "- hooks dropped to keep a method compilable: %d%s%n", this.dropped.size(),
            this.dropped.isEmpty() ? "" : " " + this.dropped));
        return r.toString();
    }
}
