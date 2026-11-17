package townkit.api.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class MoveTest {
    @Test
    void moves() {
        Move salute = Move.towntopia("SALUTE");
        assertEquals(ResourceLocation.parse("towntopia:salute"), salute.id());
        assertTrue(salute.isTowntopia());
        assertEquals("SALUTE", salute.name());
        assertEquals(salute, Move.of(ResourceLocation.parse("towntopia:salute")));
        Move raise = Move.of(ResourceLocation.parse("war:raise"));
        assertFalse(raise.isTowntopia());
        assertEquals("raise", raise.name());
        assertThrows(IllegalArgumentException.class, () -> Move.towntopia("no way"));
        assertThrows(IllegalArgumentException.class, () -> Move.towntopia(""));
        assertThrows(NullPointerException.class, () -> Move.of(null));
    }

    @Test
    void playModes() {
        assertTrue(PlayMode.once().isOnce());
        assertFalse(PlayMode.once().holdsStill());
        assertTrue(PlayMode.once().holdStill().holdsStill());
        assertEquals(0, PlayMode.loop().loopTicks());
        assertFalse(PlayMode.loop().isOnce());
        assertEquals(40, PlayMode.loopFor(40).loopTicks());
        assertThrows(IllegalArgumentException.class, () -> PlayMode.loopFor(0));
        assertEquals(PlayMode.loop().holdStill(), PlayMode.loop().holdStill());
        assertNotEquals(PlayMode.loop(), PlayMode.loop().holdStill());
        assertEquals("loopFor(40).holdStill", PlayMode.loopFor(40).holdStill().toString());
    }
}
