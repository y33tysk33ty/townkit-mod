package townkit.core.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TowntopiaMovesTest {
    @Test
    void waveUsesTheCheerClipTowntopiaPlays() {
        assertEquals("ANIM_CHEER", TowntopiaMoves.constant("WAVE"), "Towntopia plays its cheer clip for WAVE");
        assertEquals("ANIM_SALUTE", TowntopiaMoves.constant("SALUTE"), "other states: their own constant");
    }
}
