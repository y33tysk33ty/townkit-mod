package townkit.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiTiersTest {
    @Test
    void everyHooksTypeIsExperimental() {
        List<Path> files = ApiSourceScan.files("townkit/api/hooks");
        assertFalse(files.isEmpty(), "townkit.api.hooks has no files");
        for (Path f : files) assertTrue(ApiSourceScan.text(f).contains("@ApiStatus.Experimental"), f + " lacks @ApiStatus.Experimental");
    }

    @Test
    void everyStableTypeHasSince() {
        for (Path f : ApiSourceScan.files("townkit/api")) {
            String p = f.toString().replace('\\', '/');
            if (p.contains("/api/hooks/") || p.contains("/api/spi/")) continue;
            assertTrue(ApiSourceScan.text(f).contains("@since"), f + " lacks @since");
        }
    }

    /** Internal types live in townkit.api.spi, so the stable packages hold only what addons may use. */
    @Test
    void internalTypesLiveInSpi() {
        for (Path f : ApiSourceScan.files("townkit/api")) {
            if (f.toString().replace('\\', '/').contains("/api/spi/")) continue;
            assertFalse(ApiSourceScan.text(f).contains("@ApiStatus.Internal"), f + " is internal but outside townkit.api.spi");
        }
    }

    @Test
    void oldHookPackageIsGone() {
        assertTrue(ApiSourceScan.files("townkit/api/hook").isEmpty());
        assertFalse(Files.exists(Path.of("src/main/java/townkit/api/TownKitHooks.java")));
    }
}
