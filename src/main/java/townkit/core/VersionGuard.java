package townkit.core;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import townkit.core.hook.HookManifest;

/**
 * Compares the installed Towntopia jar with the one TownKit's hooks were generated from. A different jar isn't
 * fatal: hooks whose methods still exist keep working, missing ones stay inert. It just says so clearly.
 */
public final class VersionGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum State { MATCH, DIFFERENT, UNKNOWN }

    private static State state = State.UNKNOWN;
    private static String installedVersion = "?";
    private static String detail = "";

    private VersionGuard() {
    }

    private static long checkNanos;

    public static long checkNanos() {
        return checkNanos;
    }

    public static void check() {
        long t0 = System.nanoTime();
        try {
            checkJar();
        } finally {
            checkNanos = System.nanoTime() - t0;
        }
    }

    private static void checkJar() {
        try {
            var container = ModList.get().getModContainerById(BuildInfo.TOWNTOPIA_MODID).orElse(null);
            if (container == null) {
                state = State.UNKNOWN;
                detail = "Towntopia (" + BuildInfo.TOWNTOPIA_MODID + ") is not loaded";
                LOGGER.error("[TownKit] {}", detail);
                return;
            }
            installedVersion = container.getModInfo().getVersion().toString();
            Path jar = container.getModInfo().getOwningFile().getFile().getFilePath();
            String sha = Files.isRegularFile(jar) ? fingerprint(jar) : "";
            String expected = HookManifest.get().fingerprint;
            if (!sha.isEmpty() && sha.equalsIgnoreCase(expected)) {
                state = State.MATCH;
                detail = "hooks were generated from this exact Towntopia code";
                LOGGER.info("[TownKit] Towntopia {} matches the jar TownKit's {} hooks were generated from", installedVersion, HookManifest.get().hooks.length);
            } else {
                state = sha.isEmpty() ? State.UNKNOWN : State.DIFFERENT;
                detail = sha.isEmpty() ? "couldn't read the Towntopia jar at " + jar
                    : "installed Towntopia jar differs from the one hooks were generated from (" + HookManifest.get().generatedFrom + ")";
                LOGGER.warn("[TownKit] {}. Hooks for methods that still exist work; others stay inert. Run /townkit hooks verify.", detail);
            }
        } catch (Throwable t) {
            state = State.UNKNOWN;
            detail = t.toString();
            LOGGER.warn("[TownKit] version check failed: {}", t.toString());
        }
    }

    public static State state() {
        return state;
    }

    public static String installedVersion() {
        return installedVersion;
    }

    public static String detail() {
        return detail;
    }

    /**
     * Same fingerprint as the generator writes (townkit.hookgen.HookGen#fingerprint): SHA-256 over "name:crc" of every
     * Towntopia class, sorted. It reads only the jar's index, never a class, so it takes milliseconds.
     */
    private static String fingerprint(Path jar) throws Exception {
        TreeMap<String, Long> crcs = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith("com/alex/test/") && e.getName().endsWith(".class")) crcs.put(e.getName(), e.getCrc());
            }
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        crcs.forEach((n, c) -> md.update((n + ":" + Long.toHexString(c) + "\n").getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(md.digest());
    }
}
