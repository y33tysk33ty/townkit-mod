package townkit.api.anim;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import townkit.api.Subscription;
import townkit.api.event.TownKitEvents;
import townkit.api.spi.TownKitAnimationsView;
import townkit.api.spi.TownKitBackend;
import townkit.api.village.Villager;

/**
 * Moves (animations) on Towntopia's villagers, seen by every player.
 *
 * <p><b>Addon moves.</b> A move file {@code assets/<ns>/townkit/moves/<name>.json} adds the move {@code <ns>:<name>}:
 * <pre>{@code
 * {"animation_file": "<ns>:animations/townkit/<file>.animation.json",
 *  "animation": "<name of the animation inside that file>"}
 * }</pre>
 * The animation file is a GeckoLib animation made (e.g. in Blockbench) for Towntopia's villager rig
 * ({@code base_villager}: bones such as {@code right_arm}, {@code left_leg}, {@code head}). A move only moves the bones
 * it keys; every other bone keeps what Towntopia plays (a wave keyed on the right arm leaves the legs walking).
 * Animation names are looked up across every animation file the villager model knows, Towntopia's first: give yours a
 * name of their own, e.g. {@code <ns>.<move>}. A move needs TownKit on the client that draws it; a client missing the
 * move's files (unknown id, missing file or animation) draws Towntopia's own animation and logs it once per move.
 *
 * <p><b>Towntopia's moves</b> ({@link Move#towntopia(String)}) play the clip Towntopia uses for that state.
 *
 * <p><b>Playing.</b> A move plays on top of what the villager does ({@link PlayMode}); one move at a time per villager,
 * the latest play replaces the one before. {@link PlayMode#once()} plays the clip once and goes back to Towntopia's
 * animation; the server learns the clip's length from the move's files in the mod jars (Towntopia's from Towntopia's
 * jar); a move only a resource pack defines counts as 5 seconds long on the server (logged once): a longer clip is cut
 * there. {@link PlayMode#loop()} plays until {@link #stop}; {@link PlayMode#loopFor(int)} for a number of ticks.
 *
 * <p><b>Saved loops.</b> A loop played on a {@link Villager} ({@link #play(String, Villager, Move, PlayMode)}) is saved
 * with the villager: it plays again whenever the villager loads (after an unload, a change of dimension or a restart)
 * until it is stopped; a {@code loopFor} loop only until its time is up (game time). One saved loop per villager: a
 * later saved loop replaces it, whoever saved it. A loop saved with {@link PlayMode#holdStill()} doesn't hold still
 * when it plays again after a load. A once play, or any play on an {@link Entity}, is never saved; when it ends, the
 * villager's saved loop (if any) plays again.
 *
 * <p><b>Replacing Towntopia's moves</b> ({@link #replace(String, Move, Move)}): for every villager, or for one
 * ({@link #replace(String, Villager, Move, Move)}, which beats the global swaps for that move); among either, the last
 * registered wins and removing it brings back the one before. A swap to one of Towntopia's own moves changes the move
 * Towntopia sets on the server (every client sees it, with or without TownKit). A swap to an addon move keeps
 * Towntopia's move on the server (its own logic is untouched) and clients with TownKit play the addon move over it:
 * the addon move wins on the bones it keys, Towntopia's move shows on the rest. Walking and its hurried gaits
 * ({@code WALK}, {@code RUN}, {@code JOG}, {@code SWORD_RUN}, {@code INJURED_RUN}) are worked out by each client from
 * the villager's motion, so swapping them is done on the client, keyed by the swaps the server sends (a {@code WALK}
 * swap also covers Towntopia's sad walk). Swaps don't change {@link #play}: a play plays what it names. A villager's
 * own swaps are saved with it (they come back after an unload or a restart) until the subscription is removed; a
 * subscription can't survive a restart, so after one replace the same move again (it replaces your earlier swap for
 * that villager and move) and remove that. Global swaps last until removed (they are not saved: register them again
 * at each start, e.g. from mod setup).
 *
 * <p><b>Events.</b> {@link TownKitEvents#MOVE_STARTING} fires on the server before a villager starts a move Towntopia
 * sets (after the swaps, so it sees what would play) and before each {@link #play}; it can cancel or replace it. For
 * walking and its gaits, which each client works out, listen on the client with {@link #onClientMove}.
 *
 * <p><b>Props</b> ({@link Prop}) are drawn while a move that carries them plays: a move file's {@code "props"} list, or
 * {@link #withProps} for any move, Towntopia's included.
 *
 * <p>{@link #play} and {@link #stop} are server thread only (IllegalStateException elsewhere, and for a client-side
 * entity). Owners are non-blank strings, usually the addon's mod id.
 *
 * @since 0.7.0
 */
public final class TownKitAnimations {
    private TownKitAnimations() {
    }

    /**
     * What a {@code play} call did.
     *
     * @param playing the move plays now (false: the villager isn't loaded, or is gone; a saved loop then plays when
     *                it loads)
     * @param held    the villager was asked to stand still for it ({@link PlayMode#holdStill()}) and that was allowed
     * @since 0.7.0
     */
    public record PlayResult(boolean playing, boolean held) {
    }

    private static TownKitAnimationsView view() {
        return TownKitBackend.get().animations();
    }

    /**
     * Plays {@code move} on one of Towntopia's people ({@code NpcEntity}: villagers, and the Mayor, visitors and nomads)
     * while the entity exists: not saved. An entity that is still joining its level gets it once the level holds it.
     *
     * @throws IllegalArgumentException for a blank owner, or an entity that isn't one of Towntopia's people
     * @throws IllegalStateException    off the server thread, or for a client-side entity
     * @since 0.7.0
     */
    public static PlayResult play(String owner, Entity entity, Move move, PlayMode mode) {
        return view().play(owner, entity, move, mode);
    }

    /**
     * Plays {@code move} on {@code villager}; a loop is saved with the villager (see the class notes), a once play
     * isn't. Works while the villager is unloaded for a loop (it plays when the villager loads; {@code playing} is
     * false); a once play on an unloaded villager does nothing.
     *
     * @throws IllegalArgumentException for a blank owner
     * @throws IllegalStateException    off the server thread
     * @since 0.7.0
     */
    public static PlayResult play(String owner, Villager villager, Move move, PlayMode mode) {
        return view().play(owner, villager, move, mode);
    }

    /**
     * Stops every move on this entity, whoever started it, and forgets its saved loop, whoever saved it: Towntopia's
     * animation plays again. Nothing happens for an entity that plays nothing. To stop only your own moves, use
     * {@link #stop(String, Entity)}.
     *
     * @throws IllegalStateException off the server thread, or for a client-side entity
     * @since 0.7.0
     */
    public static void stop(Entity entity) {
        view().stop(entity);
    }

    /**
     * Stops {@code owner}'s move on this entity and forgets {@code owner}'s saved loop on it; another owner's play and
     * saved loop are left alone (nothing happens when another owner's move plays). When {@code owner}'s play stops and
     * the villager has another owner's saved loop, that loop plays again.
     *
     * @throws IllegalArgumentException for a blank owner
     * @throws IllegalStateException    off the server thread, or for a client-side entity
     * @since 0.7.0
     */
    public static void stop(String owner, Entity entity) {
        view().stop(owner, entity);
    }

    /**
     * Like {@link #stop(String, Entity)} for a villager, loaded or not: {@code owner}'s saved loop is forgotten even
     * while the villager is unloaded (so it won't play when it loads), and {@code owner}'s move stops if it is loaded.
     *
     * @throws IllegalArgumentException for a blank owner
     * @throws IllegalStateException    off the server thread
     * @since 0.7.0
     */
    public static void stop(String owner, Villager villager) {
        view().stop(owner, villager);
    }

    /**
     * Swaps Towntopia's move {@code builtIn} for {@code replacement} (one of Towntopia's moves or an addon move) on every
     * villager, until the subscription is removed (see the class notes). May be called at mod setup or later, from any
     * thread; villagers already loaded switch at once.
     *
     * @throws IllegalArgumentException for a blank owner, or a {@code builtIn} that isn't one of Towntopia's moves
     * @since 0.7.0
     */
    public static Subscription replace(String owner, Move builtIn, Move replacement) {
        return view().replace(owner, builtIn, replacement);
    }

    /**
     * Swaps Towntopia's move {@code builtIn} for {@code replacement} on {@code villager} only; it beats the global swaps
     * for that move. Saved with the villager (loaded or not) until the subscription is removed; one per owner, villager
     * and move: a second call replaces the first (whose subscription then does nothing).
     *
     * @throws IllegalArgumentException for a blank owner, or a {@code builtIn} that isn't one of Towntopia's moves
     * @throws IllegalStateException    off the server thread (removing the subscription too)
     * @since 0.7.0
     */
    public static Subscription replace(String owner, Villager villager, Move builtIn, Move replacement) {
        return view().replace(owner, villager, builtIn, replacement);
    }

    /**
     * Gives {@code move} (an addon move or one of Towntopia's, e.g. {@code Move.towntopia("WALK")}) these props,
     * drawn on clients while it plays, after the props of its move file. Registered on the side it
     * is called on: call it where client code runs (mod setup runs on both; a dedicated server keeps it unused). Any
     * thread.
     *
     * @throws IllegalArgumentException for a blank owner
     * @since 0.7.0
     */
    public static Subscription withProps(String owner, Move move, List<Prop> props) {
        return view().withProps(owner, move, props);
    }

    /**
     * Listens on the client for villagers starting a move the client works out itself: walking and its gaits (see the
     * class notes). Called on the render thread when a villager starts walking (or changes gait), with the move that
     * would play after the server's swaps; return another move to play that instead, or null to keep it. Listeners run
     * in the order registered, each seeing the previous one's choice; the answer is kept until the villager stops or
     * changes gait. A listener that throws is skipped (logged once). Register it where client code runs (mod setup runs
     * on both sides): it is not sent from the server. On a dedicated server it is kept and never called.
     *
     * @throws IllegalArgumentException for a blank owner
     * @since 0.7.0
     */
    public static Subscription onClientMove(String owner, ClientMoveListener listener) {
        return view().onClientMove(owner, listener);
    }

    /**
     * Told on the client when a villager starts a move the client works out itself (see {@link #onClientMove}).
     *
     * @since 0.7.0
     */
    @FunctionalInterface
    public interface ClientMoveListener {
        /** The move to play instead of {@code move} on {@code entity}, or null to keep it. */
        @Nullable
        Move starting(Entity entity, Move move);
    }
}
