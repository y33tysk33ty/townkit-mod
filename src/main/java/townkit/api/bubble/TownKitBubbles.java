package townkit.api.bubble;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import townkit.api.Subscription;
import townkit.api.event.TownKitEvents;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitBubblesView;
import townkit.api.village.Villager;

/**
 * Mood icons: bubbles over Towntopia's villagers (and its Mayor and travellers), with Towntopia's icons or addon icons.
 *
 * <p>A pop-up shows one icon for a set time, as a thought or a speech bubble, with the villager's "huh" pitched by the
 * icon's {@link Valence} the way Towntopia does it (unless shown silently). A villager shows one bubble at a time: a
 * pop-up replaces the bubble showing, and a bubble Towntopia shows later replaces an addon pop-up. Towntopia's icons go
 * through Towntopia's own bubble, so every client sees them; addon icons are drawn by TownKit on clients that have
 * TownKit and the icon's file (others see no bubble).
 *
 * <p>Ongoing states ({@link #state}) show like Towntopia's own (hungry, no bed...). Towntopia's own bubbles can be
 * hidden or swapped ({@link #hide}, {@link #swap}), for every villager or for one, and its pool of idle thoughts grown
 * ({@link #addIdleThought}). When Towntopia shows a bubble, the villager's own rules for that icon apply if it has
 * any, otherwise the global ones; then {@link TownKitEvents#BUBBLE_SHOWING} fires, which also fires for addon bubbles.
 * Hides and swaps only touch the bubbles Towntopia shows, never {@link #show}.
 *
 * <p>{@link #icon}, {@link #state}, {@link #hide}, {@link #swap} and {@link #addIdleThought} may be called at mod
 * setup or later, from any thread; {@link #show} and {@link #clear} are server thread only (they throw
 * IllegalStateException elsewhere). With nothing shown or registered, TownKit attaches nothing and ticks nothing.
 * The methods that take an {@code owner} throw IllegalArgumentException for a blank one. Rules for one villager
 * ({@link #hide(String, Villager, BubbleIcon)}, {@link #swap(String, Villager, BubbleIcon, BubbleIcon)}) live in memory
 * only, like every registration here: they aren't saved with the villager, so register them again at each start.
 *
 * <p>With every Towntopia hook switched off ({@code townkit-hooks.properties}), {@link #show} and {@link #clear} of
 * Towntopia's icons still work; addon-icon pop-ups, states, hides, swaps and idle thoughts do nothing (logged once),
 * and {@link TownKitEvents#BUBBLE_SHOWING} is unavailable.
 *
 * @since 0.7.0
 */
public final class TownKitBubbles {
    private TownKitBubbles() {
    }

    private static TownKitBubblesView view() {
        return TownKitBackend.get().bubbles();
    }

    /**
     * Registers the addon icon {@code id} with its valence, for the server (the client draws it from the icon's file,
     * see {@link BubbleIcon}). Calling it again with the same valence returns the same icon. Throws
     * IllegalArgumentException for the {@code towntopia} namespace (use {@link BubbleIcon#towntopia}) or if {@code id}
     * is already registered with another valence.
     */
    public static BubbleIcon icon(ResourceLocation id, Valence valence) {
        return view().icon(id, valence);
    }

    /** Every one of Towntopia's own icons, in Towntopia's order. */
    public static List<BubbleIcon> builtIns() {
        return view().builtIns();
    }

    /**
     * Shows {@code icon} over {@code villager} for {@code ticks} ticks, with the villager's "huh". Same as
     * {@link #show(Entity, BubbleIcon, BubbleKind, int, boolean)} with {@code silent} false.
     */
    public static boolean show(Entity villager, BubbleIcon icon, BubbleKind kind, int ticks) {
        return view().show(villager, icon, kind, ticks, false);
    }

    /**
     * Shows {@code icon} over {@code villager} for {@code ticks} ticks as a {@code kind} bubble, replacing the bubble
     * showing; {@code silent} leaves out the villager's "huh". Returns false (and shows nothing) if {@code villager}
     * isn't a Towntopia NPC or isn't loaded in its level. Throws IllegalArgumentException if {@code ticks} is under 1,
     * if a Towntopia icon is unknown, or if an addon icon wasn't registered with {@link #icon}. Server thread only.
     * {@link TownKitEvents#BUBBLE_SHOWING} fires first: a cancel there also returns false, a replacement shows instead.
     */
    public static boolean show(Entity villager, BubbleIcon icon, BubbleKind kind, int ticks, boolean silent) {
        return view().show(villager, icon, kind, ticks, silent);
    }

    /** Removes the bubble showing over {@code villager}, Towntopia's or an addon's; nothing for other entities. Server thread only. */
    public static void clear(Entity villager) {
        view().clear(villager);
    }

    /**
     * Registers an ongoing state, shown over every villager it is active on (see {@link BubbleState}) until the
     * subscription is removed. Throws IllegalArgumentException if a state with the same id is registered, or if one
     * of its icons is unknown (see {@link #show}).
     */
    public static Subscription state(String owner, BubbleState state) {
        return view().state(owner, state);
    }

    /**
     * Hides Towntopia's icon {@code builtIn} over every villager: Towntopia's bubble with it doesn't show (nor its
     * "huh"), the bubble showing stays. Until the subscription is removed. Throws IllegalArgumentException if
     * {@code builtIn} isn't one of Towntopia's icons.
     */
    public static Subscription hide(String owner, BubbleIcon builtIn) {
        return view().rule(owner, null, builtIn, null);
    }

    /**
     * Shows {@code replacement} (a Towntopia or registered addon icon) wherever Towntopia shows {@code builtIn}, with
     * the same kind and ticks and the "huh" pitched by the replacement. Swaps stack: the last registered wins, and
     * removing it brings back the one before; a hide beats every swap. Throws IllegalArgumentException if
     * {@code builtIn} isn't one of Towntopia's icons or {@code replacement} is unknown.
     */
    public static Subscription swap(String owner, BubbleIcon builtIn, BubbleIcon replacement) {
        return view().rule(owner, null, builtIn, replacement);
    }

    /**
     * {@link #hide(String, BubbleIcon)} for one villager; its own rules for an icon beat the global ones. Not saved:
     * register it again at each start.
     */
    public static Subscription hide(String owner, Villager villager, BubbleIcon builtIn) {
        return view().rule(owner, villager, builtIn, null);
    }

    /**
     * {@link #swap(String, BubbleIcon, BubbleIcon)} for one villager; its own rules for an icon beat the global ones.
     * Not saved: register it again at each start.
     */
    public static Subscription swap(String owner, Villager villager, BubbleIcon builtIn, BubbleIcon replacement) {
        return view().rule(owner, villager, builtIn, replacement);
    }

    /**
     * Adds {@code icon} (a Towntopia or registered addon icon) to the thoughts a content villager has now and then.
     * Towntopia's six idle thoughts weigh 1 each, so an icon of weight 6 comes up as often as all six together.
     * Towntopia's storm and rain thoughts keep their place within its share. Throws IllegalArgumentException if
     * {@code weight} is under 1 or {@code icon} is unknown.
     */
    public static Subscription addIdleThought(String owner, BubbleIcon icon, int weight) {
        return view().addIdleThought(owner, icon, weight);
    }
}
