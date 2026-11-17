package townkit.core;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Jars in {@code libs/} that some audit tests read. Towntopia's jars aren't part of TownKit's sources, so a test whose
 * jar is missing is skipped (with the reason), not failed.
 */
public final class TestJars {
    private TestJars() {
    }

    /** {@code jar}, or skips the calling test when it isn't there. */
    public static Path require(Path jar) {
        assumeTrue(Files.isRegularFile(jar), jar + " not found: put that Towntopia jar in libs/ to run this audit test");
        return jar;
    }
}
