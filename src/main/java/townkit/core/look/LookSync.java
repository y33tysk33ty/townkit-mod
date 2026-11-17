package townkit.core.look;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.VisibleForTesting;
import townkit.core.TownKit;
import townkit.core.net.VillagerLookPayload;

/**
 * Server side of the per-villager look: holds each entity's current {@link VillagerLook} and keeps tracking clients up
 * to date. A change goes to every player tracking the entity; a player who starts tracking it (joins late, walks into
 * range) gets the current look. Players whose client lacks TownKit's payload get nothing. An entity's look is dropped
 * when it leaves its level. Server thread only. With no look set nothing is held and nothing is sent.
 */
@EventBusSubscriber(modid = TownKit.MODID)
public final class LookSync {
    /** Who tracks an entity, and how a payload reaches a player. Swappable for tests only. */
    public interface Channel {
        List<ServerPlayer> watching(Entity entity);

        void send(ServerPlayer to, VillagerLookPayload payload);
    }

    private static final Channel NETWORK = new Channel() {
        @Override
        public List<ServerPlayer> watching(Entity entity) {
            return entity.level() instanceof ServerLevel level ? level.getChunkSource().chunkMap.getPlayersWatching(entity) : List.of();
        }

        @Override
        public void send(ServerPlayer to, VillagerLookPayload payload) {
            // the payload is optional: a client without TownKit (or a fake player) never negotiated it
            if (!(to instanceof FakePlayer) && to.connection.hasChannel(VillagerLookPayload.TYPE)) {
                PacketDistributor.sendToPlayer(to, payload);
            }
        }
    };

    /** Non-empty looks only. Entity has identity equality, so this is keyed by the entity object itself. */
    private static final Map<Entity, VillagerLook> LOOKS = new HashMap<>();
    private static Channel channel = NETWORK;

    private LookSync() {
    }

    /** The entity's current look ({@link VillagerLook#EMPTY} when none is set). */
    public static VillagerLook look(Entity entity) {
        return LOOKS.getOrDefault(entity, VillagerLook.EMPTY);
    }

    /**
     * Changes the entity's look to {@code change} applied to its current one and sends it to every player tracking
     * the entity. Sends nothing when the result equals the current look, and does nothing for an entity that isn't
     * in its level (removed, or never added). Server thread only.
     */
    public static void update(Entity entity, UnaryOperator<VillagerLook> change) {
        Objects.requireNonNull(entity, "entity");
        if (entity.level().isClientSide()) {
            throw new IllegalArgumentException("LookSync.update needs a server-side entity: " + entity);
        }
        // an entity that left its level (stale reference, late task) or was never added to it: nothing would ever drop
        // its entry again (EntityLeaveLevelEvent fires once), and nobody tracks it, so store and send nothing
        if (!inLevel(entity)) {
            LOOKS.remove(entity);
            return;
        }
        VillagerLook old = look(entity);
        VillagerLook now = Objects.requireNonNull(change.apply(old), "change returned null");
        if (now.equals(old)) {
            return;
        }
        if (now.isEmpty()) LOOKS.remove(entity);
        else LOOKS.put(entity, now);
        VillagerLookPayload payload = new VillagerLookPayload(entity.getId(), now);
        for (ServerPlayer p : channel.watching(entity)) {
            channel.send(p, payload);
        }
    }

    /** Whether the entity is alive in its server level (the level knows it by its id). */
    private static boolean inLevel(Entity entity) {
        return !entity.isRemoved() && entity.level() instanceof ServerLevel level && level.getEntity(entity.getId()) == entity;
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (LOOKS.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entity target = event.getTarget();
        VillagerLook look = LOOKS.get(target);
        if (look != null) {
            channel.send(player, new VillagerLookPayload(target.getId(), look));
        }
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!LOOKS.isEmpty() && !event.getLevel().isClientSide()) {
            LOOKS.remove(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOOKS.clear();
    }

    /** Puts {@code replacement} in place of the network channel and returns the one it replaced. GameTests only. */
    @VisibleForTesting
    public static Channel swapChannelForTests(Channel replacement) {
        Channel old = channel;
        channel = Objects.requireNonNull(replacement, "replacement");
        return old;
    }
}
