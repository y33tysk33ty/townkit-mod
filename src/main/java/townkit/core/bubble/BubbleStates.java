package townkit.core.bubble;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.ThoughtState;
import com.alex.test.village.social.ThoughtBubbles;
import com.alex.test.village.social.VillagerMemory;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.BubbleState;
import townkit.api.hooks.HookCall;
import townkit.core.TownKit;
import townkit.core.village.VillagerHandle;

/**
 * Addon states in the game: the {@link StateTicker} over Towntopia's NPCs, driven by a server-tick listener that is on
 * only while a state is registered. Each tick it checks the NPCs within 20 blocks of a player whose turn it is (one in
 * {@link StateTicker#CHECK_INTERVAL} by entity id), so each is checked once every 40 ticks. Towntopia's own state per
 * NPC comes from the {@code onState} hook ({@link #beforeOnState}). Server thread, except {@link #add}.
 */
@EventBusSubscriber(modid = TownKit.MODID)
public final class BubbleStates {
    private static final double PLAYER_RANGE = 20.0;
    private static final int STATE_TICKS = 70;

    static final StateTicker<NpcEntity> TICKER = new StateTicker<>(new StateTicker.Host<>() {
        @Override
        public boolean active(BubbleState state, NpcEntity npc) {
            return state.active().test(VillagerHandle.of(npc));
        }

        @Override
        public boolean playerNearby(NpcEntity npc) {
            return npc.level() instanceof ServerLevel level && level.hasNearbyAlivePlayer(npc.getX(), npc.getY(), npc.getZ(), PLAYER_RANGE);
        }

        @Override
        public boolean towntopiaBusy(NpcEntity npc) {
            return BUSY.containsKey(npc);
        }

        @Override
        public boolean hasBubble(NpcEntity npc) {
            return npc.hasBubble() || CoreBubbles.hasAddonBubble(npc);
        }

        @Override
        public void show(NpcEntity npc, BubbleIcon icon) {
            CoreBubbles.showState(npc, icon, STATE_TICKS);
        }

        @Override
        public int nextInt(NpcEntity npc, int bound) {
            return npc.getRandom().nextInt(bound);
        }
    }, TownKit.LOG::warn);

    /** NPCs whose Towntopia state isn't "fine", as of their last {@code onState}. Server thread. */
    private static final Map<NpcEntity, Boolean> BUSY = new WeakHashMap<>();
    private static final Consumer<ServerTickEvent.Post> TICK = e -> tick(e.getServer());
    private static boolean ticking;
    /** The NPC whose {@code onState} runs now (Towntopia's idle thought is picked inside it). Server thread. */
    @Nullable
    private static NpcEntity thinking;

    private BubbleStates() {
    }

    /** Registers {@code state}; the result removes it. */
    static Runnable add(BubbleState state) {
        Runnable remove = TICKER.add(state);
        refresh();
        return () -> {
            remove.run();
            refresh();
        };
    }

    private static synchronized void refresh() {
        boolean wanted = !TICKER.isEmpty();
        if (wanted && !ticking) {
            ticking = true;
            NeoForge.EVENT_BUS.addListener(TICK);
        } else if (!wanted && ticking) {
            ticking = false;
            NeoForge.EVENT_BUS.unregister(TICK);
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) server.execute(BubbleStates::forgetAll);
        }
        BubbleHooks.refresh();
    }

    /** Drops what is kept per NPC (server thread), unless a state was registered again meanwhile. */
    private static void forgetAll() {
        if (!TICKER.isEmpty()) return;
        TICKER.clearTracks();
        BUSY.clear();
    }

    private static void tick(MinecraftServer server) {
        int turn = Math.floorMod(server.getTickCount(), StateTicker.CHECK_INTERVAL);
        if (turn == 0) TICKER.retain(npc -> !npc.isRemoved());
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) continue;
            Set<NpcEntity> seen = players.size() > 1 ? new HashSet<>() : null;
            for (ServerPlayer p : players) {
                if (p.isSpectator() || !p.isAlive()) continue;
                List<NpcEntity> near = level.getEntitiesOfClass(NpcEntity.class, p.getBoundingBox().inflate(PLAYER_RANGE),
                    n -> Math.floorMod(n.getId(), StateTicker.CHECK_INTERVAL) == turn && n.distanceToSqr(p) < PLAYER_RANGE * PLAYER_RANGE);
                for (NpcEntity npc : near) {
                    if (seen == null || seen.add(npc)) TICKER.check(npc, level.getGameTime());
                }
            }
        }
    }

    /**
     * Before Towntopia's {@code ThoughtBubbles.onState}: notes Towntopia's state and the NPC thinking; when an addon
     * state shows on the NPC and outranks Towntopia's (priority above 0, or Towntopia's is "fine"), the call is replaced
     * by the same call with {@code suppressed} true, so Towntopia keeps its memory and timers but shows nothing.
     */
    static void beforeOnState(HookCall call) {
        Object[] a = call.args();
        if (!(call.self() instanceof ThoughtBubbles bubbles) || !(a[0] instanceof NpcEntity npc) || !(a[1] instanceof ServerLevel level)) return;
        thinking = npc;
        if (TICKER.isEmpty()) return;
        ThoughtState state = (ThoughtState) a[3];
        boolean busy = state != null && state != ThoughtState.OK;
        if (busy) BUSY.put(npc, Boolean.TRUE);
        else BUSY.remove(npc);
        if (Boolean.TRUE.equals(a[5])) return;
        BubbleState showing = TICKER.current(npc);
        if (showing == null || showing.priority() <= 0 && busy) return;
        call.cancel();
        bubbles.onState(npc, level, (VillagerMemory) a[2], state, (Float) a[4], true);
    }

    /** The NPC whose {@code onState} runs now, if any. */
    @Nullable
    static NpcEntity thinking() {
        return thinking;
    }

    static void forgetThinking() {
        thinking = null;
    }

    /** Whether the states' tick listener is on (for tests). */
    public static synchronized boolean ticking() {
        return ticking;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TICKER.clearTracks();
        BUSY.clear();
        thinking = null;
    }
}
