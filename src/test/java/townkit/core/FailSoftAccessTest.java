package townkit.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import townkit.api.ApiSourceScan;
import org.junit.jupiter.api.Test;

/**
 * A required mixin accessor crashes the game when Towntopia renames the field it reads. TownKit reads Towntopia's
 * private fields with FieldReader instead, which fails soft.
 */
class FailSoftAccessTest {
    @Test
    void noRequiredAccessorMixins() throws Exception {
        List<Path> accessors = ApiSourceScan.files("townkit/mixin/access");
        assertTrue(accessors.isEmpty(), "accessor mixins left: " + accessors);
        String config = Files.readString(Path.of("src/main/resources/townkit.mixins.json"));
        assertTrue(config.replaceAll("\\s", "").contains("\"mixins\":[]"), "townkit.mixins.json still lists mixins");
    }
}
