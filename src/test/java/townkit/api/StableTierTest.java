package townkit.api;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StableTierTest {
    @Test
    void stableTierNeverImportsTowntopia() {
        for (Path f : ApiSourceScan.files("townkit/api")) {
            String p = f.toString().replace('\\', '/');
            if (p.contains("/api/hooks/") || p.contains("/api/spi/")) continue;
            assertFalse(ApiSourceScan.text(f).contains("com.alex.test"), f + " uses a Towntopia type");
        }
    }
}
