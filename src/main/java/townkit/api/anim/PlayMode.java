package townkit.api.anim;

/**
 * How a move plays: {@link #once()} and back to what Towntopia plays, {@link #loop()} until stopped, or
 * {@link #loopFor(int)} a number of ticks. By default playing a move is only visual: the villager keeps doing what it
 * was doing (walking, working) and the move plays over it, on the bones it moves. {@link #holdStill()} asks for the
 * villager to stand still while the move plays; that follows TownKit's activity rules and is refused while the
 * villager works, eats, sleeps, talks, runs an evening step, flees or shelters (the move then plays without holding,
 * and {@link TownKitAnimations.PlayResult#held()} says so).
 *
 * <p>Immutable; {@link #holdStill()} returns a new mode.
 *
 * @since 0.7.0
 */
public final class PlayMode {
    private final boolean once;
    /** For a loop: how many ticks (0 = until stopped). */
    private final int ticks;
    private final boolean hold;

    private PlayMode(boolean once, int ticks, boolean hold) {
        this.once = once;
        this.ticks = ticks;
        this.hold = hold;
    }

    /** Play the move once, then go back to what Towntopia plays. Never saved with the villager. */
    public static PlayMode once() {
        return new PlayMode(true, 0, false);
    }

    /** Loop the move until it is stopped ({@link TownKitAnimations#stop}). */
    public static PlayMode loop() {
        return new PlayMode(false, 0, false);
    }

    /**
     * Loop the move for {@code ticks} game ticks (at least 1), then go back to what Towntopia plays.
     *
     * @throws IllegalArgumentException for fewer than 1 tick
     */
    public static PlayMode loopFor(int ticks) {
        if (ticks < 1) throw new IllegalArgumentException("loopFor needs at least 1 tick: " + ticks);
        return new PlayMode(false, ticks, false);
    }

    /** This mode, asking for the villager to stand still while the move plays. */
    public PlayMode holdStill() {
        return new PlayMode(once, ticks, true);
    }

    /** Whether the move plays once. */
    public boolean isOnce() {
        return once;
    }

    /** For a loop, how many ticks it lasts; 0 for a loop until stopped, and for {@link #once()}. */
    public int loopTicks() {
        return ticks;
    }

    /** Whether the villager is asked to stand still while the move plays. */
    public boolean holdsStill() {
        return hold;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlayMode m && m.once == once && m.ticks == ticks && m.hold == hold;
    }

    @Override
    public int hashCode() {
        return (once ? 1 : 0) + 31 * ticks + (hold ? 7 : 0);
    }

    @Override
    public String toString() {
        String base = once ? "once" : ticks == 0 ? "loop" : "loopFor(" + ticks + ")";
        return hold ? base + ".holdStill" : base;
    }
}
