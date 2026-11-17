package townkit.core.bubble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.BubbleState;
import townkit.api.bubble.Valence;

/** {@link StateTicker} on a fake clock: the timing of Towntopia's {@code ThoughtBubbles.onState}. */
class StateTickerTest {
    private static final BubbleIcon COLD = icon("cold", Valence.NEGATIVE);
    private static final BubbleIcon WARM = icon("warm", Valence.POSITIVE);
    private static final BubbleIcon DRUNK = icon("drunk", Valence.NEUTRAL);

    private final Set<String> nearby = new HashSet<>(Set.of("a"));
    private final Set<String> busy = new HashSet<>();
    private final Set<String> bubbles = new HashSet<>();
    /** (state id, villager) pairs that are active. */
    private final Set<String> active = new HashSet<>();
    private final List<String> shown = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Random random = new Random(7);

    private final StateTicker<String> ticker = new StateTicker<>(new StateTicker.Host<>() {
        @Override
        public boolean active(BubbleState state, String v) {
            if (state.id().getPath().equals("boom")) throw new IllegalStateException("boom");
            return active.contains(state.id().getPath() + "@" + v);
        }

        @Override
        public boolean playerNearby(String v) {
            return nearby.contains(v);
        }

        @Override
        public boolean towntopiaBusy(String v) {
            return busy.contains(v);
        }

        @Override
        public boolean hasBubble(String v) {
            return bubbles.contains(v);
        }

        @Override
        public void show(String v, BubbleIcon icon) {
            shown.add(v + ":" + icon.id().getPath());
        }

        @Override
        public int nextInt(String v, int bound) {
            return random.nextInt(bound);
        }
    }, warnings::add);

    private static BubbleIcon icon(String name, Valence v) {
        return new BubbleIcon(ResourceLocation.fromNamespaceAndPath("myaddon", name), v);
    }

    private static BubbleState state(String name, BubbleIcon icon, int priority) {
        return BubbleState.builder(ResourceLocation.fromNamespaceAndPath("myaddon", name), icon, v -> true).priority(priority).build();
    }

    /** Checks villager {@code v} every 40 ticks from {@code from} up to (not including) {@code to}. */
    private void run(String v, long from, long to) {
        for (long t = from; t < to; t += StateTicker.CHECK_INTERVAL) ticker.check(v, t);
    }

    @Test
    void showsOnStartThenRepeats() {
        ticker.add(state("cold", COLD, 1));
        active.add("cold@a");
        ticker.check("a", 0);
        assertEquals(List.of("a:cold"), shown, "shown on start");
        run("a", 40, 500);
        assertEquals(1, shown.size(), "not again before 500 ticks: " + shown);
        run("a", 500, 1240);
        assertEquals(2, shown.size(), "again by 1200: " + shown);
        assertEquals("a:cold", shown.get(1));
    }

    @Test
    void repeatWaitsForAFreeBubble() {
        ticker.add(state("cold", COLD, 1));
        active.add("cold@a");
        ticker.check("a", 0);
        bubbles.add("a");
        run("a", 40, 2000);
        assertEquals(1, shown.size(), "no repeat over another bubble: " + shown);
    }

    @Test
    void resolvedOnEnd() {
        BubbleState cold = BubbleState.builder(ResourceLocation.fromNamespaceAndPath("myaddon", "cold"), COLD, v -> true).priority(1).resolved(WARM).build();
        ticker.add(cold);
        active.add("cold@a");
        ticker.check("a", 0);
        active.remove("cold@a");
        ticker.check("a", 40);
        assertEquals(List.of("a:cold", "a:warm"), shown);
        run("a", 80, 3000);
        assertEquals(2, shown.size(), "resolved once, nothing after: " + shown);
        assertNull(ticker.current("a"));
    }

    @Test
    void noPlayerNoBubble() {
        ticker.add(state("cold", COLD, 1));
        active.add("cold@a");
        nearby.clear();
        run("a", 0, 3000);
        assertTrue(shown.isEmpty(), "no player within 20 blocks: " + shown);
        // the state was already on when the player came: Towntopia waits for the repeat, so does the ticker
        nearby.add("a");
        ticker.check("a", 3000);
        assertEquals(List.of("a:cold"), shown, "repeat due at once (no repeat timer ran)");
    }

    @Test
    void higherPriorityWins() {
        BubbleState cold = state("cold", COLD, 1);
        BubbleState drunk = state("drunk", DRUNK, 5);
        ticker.add(cold);
        ticker.add(drunk);
        active.add("cold@a");
        active.add("drunk@a");
        ticker.check("a", 0);
        assertEquals(List.of("a:drunk"), shown);
        assertSame(drunk, ticker.current("a"));
    }

    @Test
    void towntopiaOutranksStatesAtZeroOrBelow() {
        BubbleState low = state("cold", COLD, 0);
        ticker.add(low);
        active.add("cold@a");
        busy.add("a");
        ticker.check("a", 0);
        assertTrue(shown.isEmpty(), "Towntopia's own state shows instead: " + shown);
        busy.clear();
        ticker.check("a", 40);
        assertEquals(List.of("a:cold"), shown, "shows once Towntopia's state is fine");
        busy.add("a");
        ticker.check("a", 80);
        assertNull(ticker.current("a"), "outranked again");
        assertEquals(1, shown.size(), "no resolved icon: the state didn't end: " + shown);
    }

    @Test
    void stateAboveZeroOutranksTowntopia() {
        ticker.add(state("cold", COLD, 1));
        active.add("cold@a");
        busy.add("a");
        ticker.check("a", 0);
        assertEquals(List.of("a:cold"), shown);
    }

    @Test
    void throwingCheckIsFalseLoggedOnce() {
        ticker.add(state("boom", COLD, 1));
        run("a", 0, 400);
        assertTrue(shown.isEmpty());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("myaddon:boom"), warnings.get(0));
    }

    @Test
    void removingAStateForgetsIt() {
        Runnable remove = ticker.add(state("cold", COLD, 1));
        active.add("cold@a");
        ticker.check("a", 0);
        remove.run();
        assertNull(ticker.current("a"));
        assertTrue(ticker.isEmpty());
        run("a", 40, 2000);
        assertEquals(1, shown.size(), "nothing after removal: " + shown);
    }

    @Test
    void sameIdTwiceRefused() {
        ticker.add(state("cold", COLD, 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ticker.add(state("cold", WARM, 2)));
    }
}
