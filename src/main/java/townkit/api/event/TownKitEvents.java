package townkit.api.event;

import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import townkit.api.anim.Move;
import townkit.api.brain.StopReason;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.BubbleKind;
import townkit.api.shop.ShopOffer;
import townkit.api.village.BuildingInfo;
import townkit.api.village.RaidStage;
import townkit.api.village.Village;
import townkit.api.village.VillageState;
import townkit.api.village.Villager;

/**
 * Ready-made events for common moments in a village. Anything not here can be reached through the experimental raw hooks.
 *
 * @since 0.3.0
 */
public final class TownKitEvents {
    private TownKitEvents() {
    }

    /** A villager is about to switch activity. {@code from} is what it's doing now ("none" when idle). */
    public record ActivityChange(Villager villager, String from, String to) {
    }

    /**
     * A villager's profession is about to change. Fires on client and server. On the client {@code villager.village()} is
     * empty and {@code villager.id()} is not the server's id (see {@link Villager#id()}); it also fires for the preview
     * villagers in Towntopia's screens, which aren't in the world. On the server it fires on the server thread only:
     * not while a villager loads from disk (Towntopia sets the loaded villager's profession on a loading thread; that
     * isn't a change, since 0.7.0). A villager that loads on the server thread (a change of dimension, {@code /summon}
     * with NBT) does report: {@code from} is Towntopia's default ({@code towntopia:none}) and {@code to} its saved job.
     */
    public record ProfessionChange(Villager villager, ResourceLocation from, ResourceLocation to) {
    }

    /**
     * A building was registered or removed. {@code change} is "registered" or "removed".
     * Note: Towntopia only reports this for player-registered rooms and for removals, not for buildings restored
     * from a save or enclosures/mines/towers being created.
     *
     * @deprecated since 0.5.0: use {@link #BUILDING_ADDED} (which also covers buildings restored from a save, scanned
     *     rooms, enclosures, mines and towers, and says how the building came) and {@link #BUILDING_REMOVED}. Still
     *     delivered; kept for at least one minor release.
     */
    @Deprecated(since = "0.5.0")
    public record BuildingChange(Village village, BuildingInfo building, String change) {
    }

    /**
     * How a building came to be registered.
     * <ul>
     *   <li>{@code PLACED}: a token was put in a frame (or a failed token was retried) and Towntopia registered it.</li>
     *   <li>{@code SCANNED}: Towntopia's scan of existing rooms, enclosures, outcrops, stalls and towers found it.</li>
     *   <li>{@code RESTORED}: restored from the village's saved buildings (world load, a frame's chunk loading).</li>
     *   <li>{@code UNKNOWN}: Towntopia added it some other way.</li>
     * </ul>
     *
     * @since 0.5.0
     */
    public enum AddedHow {
        PLACED, SCANNED, RESTORED, UNKNOWN
    }

    /**
     * Towntopia registered a building. Fires once per building, after Towntopia finished the call that added it.
     *
     * @since 0.5.0
     */
    public record BuildingAdded(Village village, BuildingInfo building, AddedHow how) {
    }

    /**
     * Towntopia removed a building (its token or frame went, its door broke, the token now belongs elsewhere).
     *
     * @since 0.5.0
     */
    public record BuildingRemoved(Village village, BuildingInfo building) {
    }

    /**
     * A registered building turned valid or invalid in Towntopia's eyes ({@code Building.isValid()}: the room's shape
     * and villagers' access to it; Towntopia 0.1.2 doesn't make validity depend on working blocks, see
     * {@link #BUILDING_BLOCKS_CHANGED} for those). {@code problem} is the read API's reason when invalid (for example
     * {@code room_unreachable}); empty when valid or when Towntopia gives no reason. Not fired for routine re-checks
     * that change nothing, nor for a building that was just added (see {@link #BUILDING_ADDED}).
     *
     * @since 0.5.0
     */
    public record BuildingValidityChanged(Village village, BuildingInfo building, boolean valid, Optional<String> problem) {
    }

    /**
     * A registered building's working blocks changed after a Towntopia scan (a bed added, an oven removed): the same
     * positions the read API lists under {@code workingBlocks}. Both sets are copies; at least one is non-empty.
     *
     * @since 0.5.0
     */
    public record BuildingBlocksChanged(Village village, BuildingInfo building, Set<BlockPos> added, Set<BlockPos> removed) {
    }

    /** A villager's daily rollover just ran (once per in-game day per villager). */
    public record DayRollover(Villager villager, Village village) {
    }

    /**
     * Towntopia forgot a villager: it died or is gone for good. Fires before TownKit drops the villager's data, so
     * that data is still readable here.
     *
     * @since 0.4.0
     */
    public record VillagerRemoved(Villager villager, Village village) {
    }

    /**
     * An addon activity started on a villager (TownKit activities only). A run whose {@code start} threw fires neither
     * this nor {@link AddonActivityStopped}. @since 0.4.0
     */
    public record AddonActivityStarted(Villager villager, ResourceLocation id) {
    }

    /** An addon activity stopped, and why (TownKit activities only). @since 0.4.0 */
    public record AddonActivityStopped(Villager villager, ResourceLocation id, StopReason reason) {
    }

    /**
     * A village's state changed (for example ACTIVE to DORMANT when no player is near). Checked once a second, so up to
     * a second late, so the two events of a quick wake (DORMANT to RECONCILING, then RECONCILING to ACTIVE) can arrive
     * together; a village's first reading is silent. Use {@code to()} rather than a fresh {@link Village#state()}: during
     * the first event of a quick wake, the village can already read ACTIVE.
     *
     * @since 0.6.2
     */
    public record VillageStateChanged(Village village, VillageState from, VillageState to) {
    }

    /**
     * A village's raid stage changed. Stages can be skipped between two checks, so {@code from} and {@code to} are not
     * always neighbours. Checked once a second, so up to a second late; a village's first reading is silent. A raid
     * that starts and ends between two checks fires nothing.
     *
     * @since 0.6.2
     */
    public record RaidStageChanged(Village village, RaidStage from, RaidStage to) {
    }

    /**
     * A raid began: the stage left IDLE. {@code stage} is the first stage seen. Checked once a second, so up to a
     * second late; a village's first reading is silent. A raid that starts and ends between two checks fires nothing.
     *
     * @since 0.6.2
     */
    public record RaidStarted(Village village, RaidStage stage) {
    }

    /**
     * A raid ended: the stage went back to IDLE. Checked once a second, so up to a second late; a village's first
     * reading is silent. A raid that starts and ends between two checks fires nothing, and a listener attached during
     * a raid can see {@code RAID_ENDED} with no {@code RAID_STARTED}. It carries no result (won or lost): Towntopia has no
     * structured public read of how a raid ended.
     *
     * @since 0.6.2
     */
    public record RaidEnded(Village village) {
    }

    /**
     * A village was founded: a player placed a town hall, or Towntopia took over a vanilla village
     * ({@code VanillaVillageTakeover}); both fire this. Fires after Towntopia finished founding it (the village is
     * registered, and a founder Towntopia recorded owns it, so {@link Village#owner()} can be empty), with the town
     * hall's door. Not fired for villages restored from a save. The handler runs inside Towntopia's founding code: don't
     * add or remove Towntopia buildings (or their frames or tokens) from it; defer that to the next tick (e.g.
     * {@code server.execute}). Needs hooks on: with every hook off it is unavailable, like other hook-bound events.
     *
     * @since 0.6.2
     */
    public record VillageFounded(Village village, BlockPos door) {
    }

    /**
     * A player is about to buy {@code offer} from the Mayor of {@code village}: the row is available and the buyer
     * holds enough of its currency; nothing is taken yet. Fires for Towntopia's rows and addon rows. A handler may
     * {@link #cancel(Component) cancel} it: nothing is taken or given and the buyer is told the reason. The first
     * cancel wins; later handlers still run and can read {@link #cancelled()}. Towntopia may still refuse one of its own
     * untouched rows after this, for its own reasons (a Captain token with no open Barracks); then no
     * {@link ShopBought} follows. Server thread.
     *
     * @since 0.7.0
     */
    public static final class ShopBuying {
        private final Village village;
        private final ServerPlayer buyer;
        private final ShopOffer offer;
        @Nullable
        private Component reason;

        public ShopBuying(Village village, ServerPlayer buyer, ShopOffer offer) {
            this.village = Objects.requireNonNull(village, "village");
            this.buyer = Objects.requireNonNull(buyer, "buyer");
            this.offer = Objects.requireNonNull(offer, "offer");
        }

        public Village village() {
            return this.village;
        }

        public ServerPlayer buyer() {
            return this.buyer;
        }

        public ShopOffer offer() {
            return this.offer;
        }

        /** Stops the purchase; {@code reason} is what the buyer is told. Ignored when already cancelled. */
        public void cancel(Component reason) {
            Objects.requireNonNull(reason, "reason");
            if (this.reason == null) this.reason = reason;
        }

        public boolean cancelled() {
            return this.reason != null;
        }

        /** The first cancel's reason; empty when not cancelled. */
        public Optional<Component> reason() {
            return Optional.ofNullable(this.reason);
        }
    }

    /**
     * A player bought {@code offer} from the Mayor of {@code village} and has been paid out: {@code given} is (a copy
     * of) the item handed over (a Barracks also comes with a Captain token, not included here). Server thread.
     *
     * @since 0.7.0
     */
    public record ShopBought(Village village, ServerPlayer buyer, ShopOffer offer, ItemStack given) {
    }

    /**
     * A bubble is about to show over {@code villager}: one Towntopia shows (after addons' hides and swaps, so
     * {@link #icon()} is what would show) or one an addon shows ({@code TownKitBubbles.show}, addon states). A handler
     * may {@link #cancel()} it (nothing shows; the bubble showing stays) or {@link #replace(BubbleIcon) replace} its
     * icon; later handlers see the replacement. The first cancel wins; later handlers still run and can read
     * {@link #cancelled()}. A replacement TownKit doesn't know (an addon icon never registered) is ignored, logged once.
     * Fires for Towntopia's villagers, Mayor and travellers. Server thread.
     *
     * @since 0.7.0
     */
    public static final class BubbleShowing {
        private final Villager villager;
        private BubbleIcon icon;
        private final BubbleKind kind;
        private final int ticks;
        private boolean cancelled;

        public BubbleShowing(Villager villager, BubbleIcon icon, BubbleKind kind, int ticks) {
            this.villager = Objects.requireNonNull(villager, "villager");
            this.icon = Objects.requireNonNull(icon, "icon");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.ticks = ticks;
        }

        public Villager villager() {
            return this.villager;
        }

        /** The icon that will show: the one asked for, or the last {@link #replace replacement}. */
        public BubbleIcon icon() {
            return this.icon;
        }

        public BubbleKind kind() {
            return this.kind;
        }

        /** How long it shows, in ticks. */
        public int ticks() {
            return this.ticks;
        }

        /** Nothing shows. */
        public void cancel() {
            this.cancelled = true;
        }

        public boolean cancelled() {
            return this.cancelled;
        }

        /** Shows {@code icon} instead (a Towntopia icon or a registered addon icon), same kind and ticks. */
        public void replace(BubbleIcon icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
        }
    }

    /**
     * {@code villager} is about to start {@code move}: a move Towntopia sets on the server (after addons' swaps, so
     * {@link #move()} is what would play; not walking and its gaits, which each client works out: see
     * {@code TownKitAnimations.onClientMove}), or an addon's {@code TownKitAnimations.play}. A handler may
     * {@link #cancel()} it (Towntopia's: the villager keeps its current move; a play: nothing plays or is saved) or
     * {@link #replace(Move) replace} it; later handlers see the replacement. The first cancel wins; later handlers still
     * run and can read {@link #cancelled()}. A replacement with one of Towntopia's moves that Towntopia doesn't have is
     * ignored, logged once. For Towntopia's moves it fires when the villager's move changes, not when Towntopia sets the
     * same move again; the answer then holds until it changes. Not fired when a saved loop plays again after a load.
     * Fires for Towntopia's villagers, Mayor and travellers. Server thread.
     *
     * @since 0.7.0
     */
    public static final class MoveStarting {
        private final Villager villager;
        private Move move;
        private boolean cancelled;

        public MoveStarting(Villager villager, Move move) {
            this.villager = Objects.requireNonNull(villager, "villager");
            this.move = Objects.requireNonNull(move, "move");
        }

        public Villager villager() {
            return this.villager;
        }

        /** The move that will play: the one starting, or the last {@link #replace replacement}. */
        public Move move() {
            return this.move;
        }

        /** Nothing starts. */
        public void cancel() {
            this.cancelled = true;
        }

        public boolean cancelled() {
            return this.cancelled;
        }

        /** Plays {@code move} instead (one of Towntopia's moves or an addon move). */
        public void replace(Move move) {
            this.move = Objects.requireNonNull(move, "move");
        }
    }

    public static final TypedEvent<ActivityChange> ACTIVITY_CHANGE = new TypedEvent<>("activity_change", ActivityChange.class);
    public static final TypedEvent<ProfessionChange> PROFESSION_CHANGE = new TypedEvent<>("profession_change", ProfessionChange.class);
    /** @deprecated since 0.5.0: use {@link #BUILDING_ADDED} and {@link #BUILDING_REMOVED}; still delivered. */
    @Deprecated(since = "0.5.0")
    public static final TypedEvent<BuildingChange> BUILDING_CHANGE = new TypedEvent<>("building_change", BuildingChange.class);
    public static final TypedEvent<DayRollover> DAY_ROLLOVER = new TypedEvent<>("day_rollover", DayRollover.class);
    /** @since 0.4.0 */
    public static final TypedEvent<VillagerRemoved> VILLAGER_REMOVED = new TypedEvent<>("villager_removed", VillagerRemoved.class);
    /** @since 0.4.0 */
    public static final TypedEvent<AddonActivityStarted> ADDON_ACTIVITY_STARTED = new TypedEvent<>("addon_activity_started", AddonActivityStarted.class);
    /** @since 0.4.0 */
    public static final TypedEvent<AddonActivityStopped> ADDON_ACTIVITY_STOPPED = new TypedEvent<>("addon_activity_stopped", AddonActivityStopped.class);
    /**
     * Handlers can run inside Towntopia's own loops over buildings: don't add or remove Towntopia buildings (or
     * their frames or tokens) from a handler; defer that to the next tick (e.g. {@code server.execute}).
     *
     * @since 0.5.0
     */
    public static final TypedEvent<BuildingAdded> BUILDING_ADDED = new TypedEvent<>("building_added", BuildingAdded.class);
    /**
     * Handlers can run inside Towntopia's own loops over buildings: don't add or remove Towntopia buildings (or
     * their frames or tokens) from a handler; defer that to the next tick (e.g. {@code server.execute}).
     *
     * @since 0.5.0
     */
    public static final TypedEvent<BuildingRemoved> BUILDING_REMOVED = new TypedEvent<>("building_removed", BuildingRemoved.class);
    /**
     * Handlers can run inside Towntopia's own loops over buildings: don't add or remove Towntopia buildings (or
     * their frames or tokens) from a handler; defer that to the next tick (e.g. {@code server.execute}).
     *
     * @since 0.5.0
     */
    public static final TypedEvent<BuildingValidityChanged> BUILDING_VALIDITY_CHANGED =
            new TypedEvent<>("building_validity_changed", BuildingValidityChanged.class);
    /**
     * Handlers can run inside Towntopia's own loops over buildings: don't add or remove Towntopia buildings (or
     * their frames or tokens) from a handler; defer that to the next tick (e.g. {@code server.execute}).
     *
     * @since 0.5.0
     */
    public static final TypedEvent<BuildingBlocksChanged> BUILDING_BLOCKS_CHANGED =
            new TypedEvent<>("building_blocks_changed", BuildingBlocksChanged.class);
    /** Checked once a second, so up to a second late; a village's first reading is silent. @since 0.6.2 */
    public static final TypedEvent<VillageStateChanged> VILLAGE_STATE_CHANGED =
            new TypedEvent<>("village_state_changed", VillageStateChanged.class);
    /** Checked once a second, so up to a second late; a village's first reading is silent. @since 0.6.2 */
    public static final TypedEvent<RaidStageChanged> RAID_STAGE_CHANGED = new TypedEvent<>("raid_stage_changed", RaidStageChanged.class);
    /** Checked once a second, so up to a second late; a village's first reading is silent. @since 0.6.2 */
    public static final TypedEvent<RaidStarted> RAID_STARTED = new TypedEvent<>("raid_started", RaidStarted.class);
    /** Checked once a second, so up to a second late; a village's first reading is silent. @since 0.6.2 */
    public static final TypedEvent<RaidEnded> RAID_ENDED = new TypedEvent<>("raid_ended", RaidEnded.class);
    /** Fires when a village is founded; see {@link VillageFounded}. @since 0.6.2 */
    public static final TypedEvent<VillageFounded> VILLAGE_FOUNDED = new TypedEvent<>("village_founded", VillageFounded.class);
    /** Before every Mayor shop purchase; can cancel it. See {@link ShopBuying}. @since 0.7.0 */
    public static final TypedEvent<ShopBuying> SHOP_BUYING = new TypedEvent<>("shop_buying", ShopBuying.class);
    /** After every Mayor shop purchase. See {@link ShopBought}. @since 0.7.0 */
    public static final TypedEvent<ShopBought> SHOP_BOUGHT = new TypedEvent<>("shop_bought", ShopBought.class);
    /** Before every bubble over a villager, Towntopia's or an addon's; can cancel or replace it. See {@link BubbleShowing}. @since 0.7.0 */
    public static final TypedEvent<BubbleShowing> BUBBLE_SHOWING = new TypedEvent<>("bubble_showing", BubbleShowing.class);
    /** Before a villager starts a move (Towntopia's on the server, or an addon play); can cancel or replace it. See {@link MoveStarting}. @since 0.7.0 */
    public static final TypedEvent<MoveStarting> MOVE_STARTING = new TypedEvent<>("move_starting", MoveStarting.class);
}
