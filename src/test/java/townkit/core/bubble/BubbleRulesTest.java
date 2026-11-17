package townkit.core.bubble;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.Valence;

class BubbleRulesTest {
    private static final ResourceLocation HUNGRY = ResourceLocation.parse("towntopia:status_hungry");
    private static final BubbleIcon COIN = new BubbleIcon(ResourceLocation.parse("myaddon:coin"), Valence.POSITIVE);
    private static final BubbleIcon BREAD = new BubbleIcon(ResourceLocation.parse("myaddon:bread"), Valence.NEUTRAL);
    private static final BubbleIcon SAD = new BubbleIcon(ResourceLocation.parse("towntopia:status_sad"), Valence.NEGATIVE);
    private static final UUID ANNA = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private final BubbleRules rules = new BubbleRules();

    @Test
    void nothingRegisteredChangesNothing() {
        assertTrue(rules.isEmpty());
        assertSame(BubbleRules.Decision.NONE, rules.decide(ANNA, HUNGRY));
    }

    @Test
    void hideWins() {
        Runnable hide = rules.add(null, HUNGRY, null);
        rules.add(null, HUNGRY, COIN);
        assertTrue(rules.decide(ANNA, HUNGRY).hidden(), "a hide beats a swap registered after it");
        hide.run();
        assertSame(COIN, rules.decide(ANNA, HUNGRY).replacement(), "the swap applies once the hide is gone");
        rules.add(null, HUNGRY, null);
        assertTrue(rules.decide(ANNA, HUNGRY).hidden(), "and a swap registered before it");
        assertFalse(rules.decide(ANNA, SAD.id()).hidden(), "other icons untouched");
    }

    @Test
    void perVillagerBeatsGlobal() {
        rules.add(null, HUNGRY, null);
        rules.add(ANNA, HUNGRY, COIN);
        assertSame(COIN, rules.decide(ANNA, HUNGRY).replacement(), "Anna's own swap beats the global hide");
        assertTrue(rules.decide(BOB, HUNGRY).hidden(), "Bob gets the global hide");
        rules.add(BOB, HUNGRY, BREAD);
        assertSame(BREAD, rules.decide(BOB, HUNGRY).replacement());
        assertTrue(rules.decide(null, HUNGRY).hidden(), "no villager: global only");
    }

    @Test
    void stackedSwapsUnwind() {
        Runnable first = rules.add(null, HUNGRY, COIN);
        Runnable second = rules.add(null, HUNGRY, BREAD);
        Runnable third = rules.add(null, HUNGRY, SAD);
        assertSame(SAD, rules.decide(ANNA, HUNGRY).replacement(), "last registered wins");
        third.run();
        assertSame(BREAD, rules.decide(ANNA, HUNGRY).replacement(), "removing it restores the one before");
        first.run();
        assertSame(BREAD, rules.decide(ANNA, HUNGRY).replacement(), "removing an older one changes nothing");
        second.run();
        assertSame(BubbleRules.Decision.NONE, rules.decide(ANNA, HUNGRY));
        assertTrue(rules.isEmpty());
        third.run(); // twice: harmless
    }

    @Test
    void idleWeights() {
        Runnable coin = rules.addIdle(COIN, 4);
        rules.addIdle(BREAD, 2);
        RandomSource rnd = RandomSource.create(20261109L);
        Map<String, Integer> counts = new HashMap<>();
        int picks = 10_000;
        for (int i = 0; i < picks; i++) {
            BubbleIcon pick = rules.pickIdle(rnd);
            counts.merge(pick == null ? "towntopia" : pick.id().getPath(), 1, Integer::sum);
        }
        // Towntopia's six idle thoughts weigh 1 each: 6 + 4 + 2 = 12
        assertNear(6 / 12.0, counts, "towntopia", picks);
        assertNear(4 / 12.0, counts, "coin", picks);
        assertNear(2 / 12.0, counts, "bread", picks);
        coin.run();
        for (int i = 0; i < 1000; i++) {
            BubbleIcon pick = rules.pickIdle(rnd);
            assertTrue(pick == null || pick == BREAD, String.valueOf(pick));
        }
    }

    @Test
    void noIdleThoughtsKeepTowntopiasPick() {
        assertFalse(rules.hasIdle());
        assertNull(rules.pickIdle(RandomSource.create(1)));
    }

    private static void assertNear(double expected, Map<String, Integer> counts, String key, int picks) {
        double got = counts.getOrDefault(key, 0) / (double) picks;
        assertTrue(Math.abs(got - expected) <= expected * 0.05, key + ": " + got + " vs " + expected + " " + counts);
    }
}
