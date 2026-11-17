package townkit.core.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import townkit.core.TownKit;
import townkit.core.client.anim.MoveHooks;
import townkit.core.client.anim.PropLayer;
import townkit.core.client.skin.LookHooks;
import townkit.core.look.VillagerLook;
import townkit.core.net.VillagerLookPayload;

/**
 * Client side of the per-villager look: the latest {@link VillagerLook} the server sent for each entity, by network
 * id. Client main thread only. Cleared when the entity leaves the client level (the server sends the look again when
 * it is tracked again) and on logout or respawn. Counts the looks that carry a skin, so the skin drawing hooks are
 * attached only while one does ({@link LookHooks#refresh}), and the looks that carry a move, so the move hooks are
 * attached only while one does ({@link MoveHooks#refresh}), and the looks that carry move swaps (same hooks).
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class ClientLooks {
    private static final Int2ObjectMap<VillagerLook> LOOKS = new Int2ObjectOpenHashMap<>();
    /** How many of {@link #LOOKS} carry a skin. */
    private static int skins;
    /** How many of {@link #LOOKS} carry a move. */
    private static int moves;
    /** How many of {@link #LOOKS} carry move swaps. */
    private static int swaps;

    private ClientLooks() {
    }

    /** The entity's look as last sent by the server ({@link VillagerLook#EMPTY} when none). */
    public static VillagerLook get(Entity entity) {
        VillagerLook look = LOOKS.get(entity.getId());
        return look == null ? VillagerLook.EMPTY : look;
    }

    /** Whether any entity's look carries a skin. */
    public static boolean anySkin() {
        return skins > 0;
    }

    /** Whether any entity's look carries a move. */
    public static boolean anyMove() {
        return moves > 0;
    }

    /** Whether any entity's look carries move swaps. */
    public static boolean anySwap() {
        return swaps > 0;
    }

    /** The moves the looks carry now: each look's move and what its swaps play. Client main thread. */
    public static java.util.Set<ResourceLocation> carriedMoves() {
        java.util.Set<ResourceLocation> out = new java.util.HashSet<>();
        if (moves == 0 && swaps == 0) return out;
        for (VillagerLook look : LOOKS.values()) {
            if (look.move() != null) out.add(look.move());
            out.addAll(look.moveSwaps().values());
        }
        return out;
    }

    /** Takes a look the server sent. Called by the payload handler on the client main thread. */
    public static void accept(VillagerLookPayload payload) {
        VillagerLook old = payload.look().isEmpty() ? LOOKS.remove(payload.entityId()) : LOOKS.put(payload.entityId(), payload.look());
        skinsChanged(old, payload.look());
        movesChanged(old, payload.look());
        if (payload.look().bubble() != null) BubbleDrawHooks.ensureAttached();
        if (payload.look().move() != null || !payload.look().moveSwaps().isEmpty()) MoveHooks.moveArrived(payload.entityId());
    }

    /** Keeps the move count when a look goes from {@code old} to {@code now} (null: none). */
    private static void movesChanged(@Nullable VillagerLook old, @Nullable VillagerLook now) {
        boolean before = moves > 0;
        boolean swapsBefore = swaps > 0;
        if (old != null && old.move() != null) moves--;
        if (now != null && now.move() != null) moves++;
        if (old != null && !old.moveSwaps().isEmpty()) swaps--;
        if (now != null && !now.moveSwaps().isEmpty()) swaps++;
        if (before != moves > 0 || swapsBefore != swaps > 0) MoveHooks.refresh();
        else if (!carries(old).equals(carries(now))) PropLayer.refresh();
    }

    /** The move and swap targets a look carries (what decides whether the prop layer is needed). */
    private static java.util.Set<ResourceLocation> carries(@Nullable VillagerLook look) {
        if (look == null || look.move() == null && look.moveSwaps().isEmpty()) return java.util.Set.of();
        java.util.Set<ResourceLocation> out = new java.util.HashSet<>(look.moveSwaps().values());
        if (look.move() != null) out.add(look.move());
        return out;
    }

    /** Keeps the skin count when a look goes from {@code old} to {@code now} (null: none). */
    private static void skinsChanged(@Nullable VillagerLook old, @Nullable VillagerLook now) {
        boolean before = skins > 0;
        if (old != null && old.skin() != null) skins--;
        if (now != null && now.skin() != null) skins++;
        if (before != skins > 0) LookHooks.refresh();
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!LOOKS.isEmpty() && event.getLevel().isClientSide()) {
            VillagerLook old = LOOKS.remove(event.getEntity().getId());
            skinsChanged(old, null);
            movesChanged(old, null);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /** Respawn or a change of dimension: a new client level, whose entities the server tracks (and sends) anew. */
    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        clear();
    }

    private static void clear() {
        LOOKS.clear();
        boolean had = skins > 0;
        skins = 0;
        if (had) LookHooks.refresh();
        boolean hadMoves = moves > 0 || swaps > 0;
        moves = 0;
        swaps = 0;
        if (hadMoves) MoveHooks.refresh();
    }
}
