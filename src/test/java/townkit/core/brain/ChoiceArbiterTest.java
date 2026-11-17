package townkit.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChoiceArbiterTest {
    @Test
    void dayHigherWins() {
        assertEquals(0, ChoiceArbiter.pickDay(10F, new float[] {11F}, false));
        assertEquals(1, ChoiceArbiter.pickDay(10F, new float[] {11F, 12F}, false));
    }

    @Test
    void dayTieGoesToTowntopia() {
        assertEquals(-1, ChoiceArbiter.pickDay(10F, new float[] {10F}, false));
    }

    @Test
    void dayNightNever() {
        assertEquals(-1, ChoiceArbiter.pickDay(0F, new float[] {999F}, true));
    }

    @Test
    void dayIgnoresNaNAndZero() {
        assertEquals(-1, ChoiceArbiter.pickDay(-1F, new float[] {Float.NaN, 0F}, false));
        assertEquals(1, ChoiceArbiter.pickDay(-1F, new float[] {Float.NaN, 0.5F}, false));
    }

    @Test
    void eveningLeisureOnly() {
        for (String leisure : new String[] {"TAVERN", "VISIT", "LIBRARY", "HOME"}) {
            assertEquals(0, ChoiceArbiter.pickEvening(leisure, "GATHER", new float[] {1F}), leisure);
        }
        for (String other : new String[] {"DEPOSIT", "CHORES", "EAT", "WASH", "BARD_SET", "BEDTIME", "KEEP"}) {
            assertEquals(-1, ChoiceArbiter.pickEvening(other, "GATHER", new float[] {1F}), other);
        }
        assertEquals(-1, ChoiceArbiter.pickEvening(null, "GATHER", new float[] {1F}));
    }

    @Test
    void eveningNeverAtNight() {
        assertEquals(-1, ChoiceArbiter.pickEvening("TAVERN", "NIGHT", new float[] {1F}));
    }

    @Test
    void eveningTiePicksFirst() {
        assertEquals(0, ChoiceArbiter.pickEvening("TAVERN", "GATHER", new float[] {2F, 2F}));
        assertEquals(-1, ChoiceArbiter.pickEvening("TAVERN", "GATHER", new float[] {0F, -1F}));
    }
}
