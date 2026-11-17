package townkit.test;

/**
 * Pacing for GameTest waits on work the server does off its own thread: loading a re-forced chunk and its entities
 * from disk (a body coming back, a shared village waking up).
 *
 * <p>That work takes wall-clock time, but the GameTest server runs its ticks back to back:
 * {@code GameTestServer.waitUntilNextTick} only runs queued tasks and never sleeps. A wait of 200 or 400 ticks can be
 * over in well under a second (runs logged a whole 400-tick reload wait, and Towntopia's own 400-tick reconcile wait,
 * inside one wall-clock second, and 51 waited ticks in 14 ms) while the chunk or its entities are still on their way
 * from disk. A tick budget alone is then no time budget, and the wait passes or fails on how fast the PC runs ticks.
 *
 * <p>Call {@link #notYet()} on each tick a load wait is still not done: it holds the server thread for
 * {@link #PACE_MS} ms, so a wait of {@code n} ticks lasts at least {@code n * PACE_MS} ms while chunk and entity loading
 * carries on on its own threads. The waits keep their conditions and tick budgets; a wait that is already done costs
 * nothing.
 */
final class LoadWait {
    /** Wall-clock time each waited tick lasts at least: 200 ticks are 4 s or more, 400 ticks 8 s or more. */
    static final long PACE_MS = 20;

    private LoadWait() {
    }

    /** One more tick of a load wait that isn't done yet: give the loading threads {@link #PACE_MS} ms. */
    static void notYet() {
        try {
            Thread.sleep(PACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
