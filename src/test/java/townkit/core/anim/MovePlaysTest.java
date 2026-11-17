package townkit.core.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.anim.PlayMode;

class MovePlaysTest {
    private static final ResourceLocation WAVE = ResourceLocation.parse("war:wave");
    private static final ResourceLocation MARCH = ResourceLocation.parse("war:march");

    private final MovePlays<String> plays = new MovePlays<>();
    private final List<String> asked = new ArrayList<>();
    private final MovePlays.HoldGate<String> allow = key -> {
        asked.add(key);
        return true;
    };
    private final MovePlays.HoldGate<String> refuse = key -> {
        asked.add(key);
        return false;
    };

    @Test
    void onceEndsItself() {
        MovePlays.Play p = plays.start("a", "war", WAVE, PlayMode.once(), 3, allow);
        assertTrue(p.once());
        assertEquals(WAVE, p.move());
        assertEquals(List.of(), plays.tick(), "tick 1");
        assertEquals(List.of(), plays.tick(), "tick 2");
        assertTrue(plays.get("a").isPresent());
        assertEquals(List.of("a"), plays.tick(), "ends after its length");
        assertTrue(plays.get("a").isEmpty());
        assertTrue(plays.isEmpty());
        assertEquals(List.of(), plays.tick(), "ends once");
    }

    @Test
    void loopForEnds() {
        plays.start("a", "war", MARCH, PlayMode.loopFor(5), 99, allow);
        plays.start("b", "war", MARCH, PlayMode.loop(), 99, allow);
        for (int i = 0; i < 4; i++) assertEquals(List.of(), plays.tick());
        assertEquals(List.of("a"), plays.tick(), "loopFor(5) ends after 5 ticks, whatever the once length");
        for (int i = 0; i < 1000; i++) assertEquals(List.of(), plays.tick(), "a plain loop never ends by itself");
        assertTrue(plays.get("b").isPresent());
        assertEquals(-1, plays.get("b").get().ticksLeft());
    }

    @Test
    void stopClears() {
        MovePlays.Play first = plays.start("a", "war", MARCH, PlayMode.loop(), 0, allow);
        assertTrue(plays.stop("a"));
        assertTrue(plays.get("a").isEmpty());
        assertFalse(plays.stop("a"), "nothing left to stop");
        assertTrue(plays.isEmpty());
        MovePlays.Play again = plays.start("a", "war", MARCH, PlayMode.loop(), 0, allow);
        assertNotEquals(first.seq(), again.seq(), "the same move started again is a new play");
        assertTrue(again.seq() != 0, "0 means no move");
        MovePlays.Play replaced = plays.start("a", "other", WAVE, PlayMode.once(), 10, allow);
        assertEquals(replaced, plays.get("a").orElseThrow(), "the latest play replaces the one before");
        plays.drop("a");
        assertTrue(plays.isEmpty());
    }

    @Test
    void holdRefusedStillPlays() {
        MovePlays.Play refused = plays.start("a", "war", WAVE, PlayMode.once().holdStill(), 10, refuse);
        assertEquals(List.of("a"), asked);
        assertFalse(refused.held(), "refused: not held");
        assertTrue(plays.get("a").isPresent(), "... but it plays");
        MovePlays.Play held = plays.start("b", "war", WAVE, PlayMode.once().holdStill(), 10, allow);
        assertTrue(held.held());
        asked.clear();
        MovePlays.Play visual = plays.start("c", "war", WAVE, PlayMode.loop(), 10, allow);
        assertFalse(visual.held());
        assertEquals(List.of(), asked, "no hold asked for: the gate isn't asked");
    }

    @Test
    void restoredLoopKeepsItsTimeLeft() {
        MovePlays.Play p = plays.restore("a", "war", MARCH, 2);
        assertFalse(p.once());
        assertEquals(List.of(), plays.tick());
        assertEquals(List.of("a"), plays.tick());
        MovePlays.Play forever = plays.restore("b", "war", MARCH, -1);
        assertEquals(-1, forever.ticksLeft());
    }
}
