package townkit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * The GameTests, test blocks and their helpers live in the {@code gametest} source set: the release jar carries no
 * test code, and the mod's own sources never name the test package.
 */
class JarContentsTest {
    private static final String TEST_PACKAGE = "townkit.test";

    @Test
    void releaseJarHasNoTestClasses() throws IOException {
        Path jar = Path.of("build", "libs", "townkit-" + modVersion() + ".jar");
        // the test task depends on jar, so a missing jar is a build problem: fail, never skip
        assertTrue(Files.exists(jar), "no release jar at " + jar.toAbsolutePath());
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> testEntries = Collections.list(zip.entries()).stream()
                    .map(e -> e.getName())
                    .filter(n -> n.startsWith("townkit/test/"))
                    .toList();
            assertEquals(List.of(), testEntries, testEntries.size() + " test entries in " + jar);
        }
    }

    @Test
    void mainSourcesNeverNameTheTestPackage() throws IOException {
        Path root = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(root), "missing " + root.toAbsolutePath());
        List<String> hits;
        try (Stream<Path> files = Files.walk(root)) {
            hits = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains(TEST_PACKAGE))
                    .map(p -> root.relativize(p).toString())
                    .toList();
        }
        assertEquals(List.of(), hits, "main sources that name " + TEST_PACKAGE);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new RuntimeException(p.toString(), e);
        }
    }

    private static String modVersion() throws IOException {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of("gradle.properties"))) {
            props.load(r);
        }
        return props.getProperty("mod_version");
    }
}
