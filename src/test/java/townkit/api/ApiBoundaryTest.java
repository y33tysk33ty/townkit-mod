package townkit.api;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApiBoundaryTest {
    @Test
    void apiNeverMentionsCoreOrMixin() {
        for (Path f : ApiSourceScan.files("townkit/api")) {
            String s = ApiSourceScan.text(f);
            assertFalse(s.contains("townkit.core."), f + " mentions townkit.core");
            assertFalse(s.contains("townkit.mixin."), f + " mentions townkit.mixin");
        }
    }
}
