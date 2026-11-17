package townkit.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Reads TownKit's own sources so tests can check the API's rules (tiers, boundaries, @since). */
public final class ApiSourceScan {
    private static final Path ROOT = Path.of("src/main/java");

    private ApiSourceScan() {
    }

    /** Every .java file under src/main/java/{@code relativeDir}; empty when the folder doesn't exist. */
    public static List<Path> files(String relativeDir) {
        Path dir = ROOT.resolve(relativeDir);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String text(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
