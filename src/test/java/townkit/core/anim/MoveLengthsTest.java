package townkit.core.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MoveLengthsTest {
    @Test
    void readsAnimationLengths() throws Exception {
        String file = "{\"format_version\": \"1.8.0\", \"animations\": {"
            + "\"war.raise\": {\"loop\": false, \"animation_length\": 1.5, \"bones\": {\"right_arm\": {\"rotation\": {\"0.0\": [0, 0, 0]}}}},"
            + "\"war.march\": {\"loop\": true, \"bones\": {}},"
            + "\"salute\": {\"bones\": {}, \"animation_length\": 1}}}";
        Map<String, Double> lengths = MoveLengths.read(new StringReader(file));
        assertEquals(1.5, lengths.get("war.raise"));
        assertEquals(1.0, lengths.get("salute"), "length after the bones");
        assertTrue(!lengths.containsKey("war.march"), "no length given: unknown");
        assertEquals(30 + MoveLengths.MARGIN, MoveLengths.ticks(1.5));
        assertEquals(21 + MoveLengths.MARGIN, MoveLengths.ticks(1.01), "rounded up");
    }
}
