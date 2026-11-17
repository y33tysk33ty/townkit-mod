package townkit.core.hook;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which hooks are actually in place. the plugin reports each class as its hooks go in (cheap, as classes load);
 * {@link #verify()} checks method by method by looking for the injected handlers on the loaded Towntopia classes.
 */
public final class HookStatus {
    /** Towntopia classes (internal names) that loaded with their hooks written in. */
    private static final Set<String> HOOKED_CLASSES = ConcurrentHashMap.newKeySet();
    /** Towntopia classes (dotted names) left unhooked by config, with the reason. */
    private static final Map<String, String> SKIPPED_CLASSES = new ConcurrentHashMap<>();
    /** Time spent writing hooks into classes as they load (TownKit's share of class loading). */
    static final java.util.concurrent.atomic.AtomicLong injectNanos = new java.util.concurrent.atomic.AtomicLong();

    public static long injectNanos() {
        return injectNanos.get();
    }

    private HookStatus() {
    }

    static void classHooked(String internalName) {
        HOOKED_CLASSES.add(internalName);
    }

    static void classSkipped(String className, String reason) {
        SKIPPED_CLASSES.put(className, reason);
    }

    /** How many hooked Towntopia classes have loaded so far. */
    public static int hookedClasses() {
        return HOOKED_CLASSES.size();
    }

    public static Map<String, String> skippedClasses() {
        return Map.copyOf(SKIPPED_CLASSES);
    }

    public static boolean isApplied(HookInfo h) {
        return HOOKED_CLASSES.contains(h.owner());
    }

    /** {@code clientOnly}: client hooks not checked because this is a dedicated server (those classes don't exist here). */
    public record Verification(int checked, int live, int missing, int classFailed, int clientOnly, List<String> missingKeys,
                               List<String> failedClasses) {
    }

    /**
     * Loads every hooked class (without running its static setup) and checks both injected handlers exist for each
     * hook. Use from a command or test, not on a hot path: it loads hundreds of classes.
     */
    public static Verification verify() {
        HookInfo[] hooks = HookManifest.get().hooks;
        Map<String, Set<Integer>> injected = new ConcurrentHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int live = 0;
        int classFailed = 0;
        int clientOnly = 0;
        boolean onClient = net.neoforged.fml.loading.FMLEnvironment.dist.isClient();
        ClassLoader loader = HookStatus.class.getClassLoader();
        for (HookInfo h : hooks) {
            if (h.client() && !onClient) {
                clientOnly++;
                continue;
            }
            Set<Integer> ids = injected.get(h.owner());
            if (ids == null) {
                ids = ConcurrentHashMap.newKeySet();
                try {
                    // the injector leaves a marker field listing exactly which hooks went into the class
                    Class<?> c = Class.forName(h.ownerClassName(), false, loader);
                    // listing methods links the class, so the JVM's bytecode verifier checks every rewritten method now
                    // (loading alone doesn't), without running the class's static setup
                    c.getDeclaredMethods();
                    java.lang.reflect.Field f = c.getDeclaredField(HookInjector.MARKER_FIELD);
                    f.setAccessible(true);
                    for (String s : ((String) f.get(null)).split(",")) ids.add(Integer.parseInt(s));
                } catch (NoSuchFieldException e) {
                    // class loaded but nothing injected: every hook of it counts as missing
                } catch (Throwable t) {
                    failed.add(h.ownerClassName() + ": " + t);
                    ids.add(-1);
                }
                injected.put(h.owner(), ids);
            }
            if (ids.contains(-1)) {
                classFailed++;
            } else if (ids.contains(h.id())) {
                live++;
            } else {
                missing.add(h.key());
            }
        }
        return new Verification(hooks.length - clientOnly, live, missing.size(), classFailed, clientOnly, missing, failed);
    }
}
