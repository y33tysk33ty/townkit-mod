package townkit.core.anim;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import townkit.api.Subscription;
import townkit.api.anim.Move;
import townkit.api.anim.TownKitAnimations;
import townkit.core.client.anim.Moves;
import townkit.core.client.anim.Props;
import townkit.api.anim.PlayMode;
import townkit.api.anim.Prop;
import townkit.api.anim.TownKitAnimations.PlayResult;
import townkit.api.brain.ActivityContext;
import townkit.api.brain.ActivityStatus;
import townkit.api.brain.VillagerActivity;
import townkit.api.spi.TownKitAnimationsView;
import townkit.api.village.Villager;
import townkit.api.villager.VillagerDataKey;
import townkit.core.TownKit;
import townkit.core.brain.ActivityRegistry;
import townkit.core.brain.BrainHooks;
import townkit.core.brain.InterruptGate;
import townkit.core.brain.InterruptHooks;
import townkit.core.building.RewrittenSites;
import townkit.core.look.LookSync;
import townkit.core.village.VillagerHandle;
import townkit.core.villager.VillagerDataHooks;
import townkit.core.villager.VillagerDataStore;

/**
 * TownKit's side of {@link townkit.api.anim.TownKitAnimations}: the server.
 *
 * <p>Each loaded entity plays at most one move ({@link MovePlays}); it goes into the entity's {@code VillagerLook}
 * (move id, a sequence number of its own, once or loop), which {@link LookSync} sends to every tracking client, where
 * {@code MoveHooks} plays it. A once play is kept here for its clip's length ({@link MoveLengths}), a loopFor for its
 * ticks; then the look's move is cleared (or the villager's saved loop plays again). Saved loops live in TownKit's
 * villager data (key {@code townkit:moves}, by npcId): one per villager, put back when a body of the villager joins a
 * level. A play on an entity the level can't find yet (it is joining) waits in {@link #PENDING} and is sent once the
 * level holds it. One server-tick listener counts the plays down and sends the waiting ones; it is attached only while
 * something plays or waits.
 *
 * <p>Holding still ({@link PlayMode#holdStill()}): allowed when the villager is idle, at leisure or running an addon
 * activity that allows routine interruptions (TownKit's interrupt rules, {@link InterruptGate}), and not while it is
 * pulled out of water. Then TownKit queues its own activity {@code townkit:hold_still} on the villager's brain, which
 * stands still until the held play ends (or Towntopia's safety and bedtime rules take over). Server thread only.
 */
@EventBusSubscriber(modid = TownKit.MODID)
public final class CoreAnimations implements TownKitAnimationsView {
    /** The villager data id of saved loops. */
    public static final ResourceLocation DATA_ID = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "moves");
    /** TownKit's activity that holds a villager still for a move. */
    public static final ResourceLocation HOLD_ID = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "hold_still");
    /** How long (ticks) an entity with a play may wait to be in its level before the play is dropped. */
    private static final int MAX_WAIT = 600;

    /**
     * A saved loop.
     *
     * @param until the game time it ends at; -1 for a loop until stopped
     */
    public record Saved(String owner, ResourceLocation move, long until) {
        public static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("owner").forGetter(Saved::owner),
            ResourceLocation.CODEC.fieldOf("move").forGetter(Saved::move),
            Codec.LONG.optionalFieldOf("until", -1L).forGetter(Saved::until)).apply(i, Saved::new));

        public Saved {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(move, "move");
        }

        /** Ticks left at game time {@code now}: -1 forever, 0 or less when over. */
        int ticksLeft(long now) {
            return until < 0 ? -1 : (int) Math.max(0, Math.min(Integer.MAX_VALUE, until - now));
        }
    }

    /** Registered on the first saved loop, so a world where no addon saves one registers nothing. */
    private static final class Key {
        static final VillagerDataKey<Saved> KEY = VillagerDataHooks.register(DATA_ID, Saved.CODEC);
    }

    private static final MovePlays<Entity> LIVE = new MovePlays<>();
    /** Entities with a play that the level can't find yet, and the ticks each may still wait. */
    private static final Map<Entity, Integer> PENDING = new HashMap<>();
    private static final Consumer<ServerTickEvent.Post> TICK = e -> tick();
    private static boolean ticking;
    private static boolean holdRegistered;
    private static volatile boolean warnedHooksOff;
    private static volatile boolean warnedGone;
    private static volatile boolean warnedRemoved;
    private static volatile boolean warnedNeverAdded;
    private static volatile boolean warnedUnreadable;

    // --- playing ---------------------------------------------------------------------------------------------------

    @Override
    public PlayResult play(String owner, Entity entity, Move move, PlayMode mode) {
        checkArgs(owner, move, mode);
        Objects.requireNonNull(entity, "entity");
        if (!(entity instanceof NpcEntity)) {
            throw new IllegalArgumentException("TownKitAnimations.play takes one of Towntopia's people (NpcEntity: villagers, the Mayor, "
                + "visitors, nomads), not " + entity.getType());
        }
        checkServerThread(entity, "play");
        warnIfHooksOff(owner);
        if (!present(entity)) {
            if (!warnedRemoved) {
                warnedRemoved = true;
                TownKit.LOG.warn("[TownKit] moves: {} played a move on {}, which was removed from its level: ignored (logged once)", owner, entity);
            }
            return new PlayResult(false, false);
        }
        Move chosen = MoveStartHooks.playStarting(VillagerHandle.of((NpcEntity) entity), move);
        if (chosen == null) return new PlayResult(false, false);
        return new PlayResult(true, start(entity, owner, chosen, mode).held());
    }

    @Override
    public PlayResult play(String owner, Villager villager, Move move, PlayMode mode) {
        checkArgs(owner, move, mode);
        Objects.requireNonNull(villager, "villager");
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("TownKitAnimations.play is server thread only");
        warnIfHooksOff(owner);
        if (!villager.exists()) {
            if (!warnedGone) {
                warnedGone = true;
                TownKit.LOG.warn("[TownKit] moves: {} played a move on {}, which no longer exists: ignored (logged once)", owner, villager);
            }
            return new PlayResult(false, false);
        }
        move = MoveStartHooks.playStarting(villager, move);
        if (move == null) return new PlayResult(false, false);
        if (!mode.isOnce()) {
            long until = mode.loopTicks() == 0 ? -1 : server.overworld().getGameTime() + mode.loopTicks();
            villager.setData(Key.KEY, new Saved(owner, move.id(), until));
        }
        NpcEntity body = villager.entity().orElse(null) instanceof NpcEntity npc && present(npc) ? npc : null;
        if (body == null) return new PlayResult(false, false);
        return new PlayResult(true, start(body, owner, move, mode).held());
    }

    private static MovePlays.Play start(Entity entity, String owner, Move move, PlayMode mode) {
        int onceTicks = mode.isOnce() ? MoveLengths.onceTicks(move.id()) : 0;
        MovePlays.Play p = LIVE.start(entity, owner, move.id(), mode, onceTicks, CoreAnimations::hold);
        show(entity);
        return p;
    }

    @Override
    public void stop(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        checkServerThread(entity, "stop");
        LIVE.stop(entity);
        PENDING.remove(entity);
        if (entity instanceof NpcEntity npc && npc.getNpcId() != null && saved(npc.getNpcId(), entity.getServer()) != null) {
            VillagerHandle.of(npc).removeData(Key.KEY);
        }
        if (inLevel(entity)) sync(entity);
        stopTickingIfIdle();
    }

    @Override
    public void stop(String owner, Entity entity) {
        checkOwner(owner);
        Objects.requireNonNull(entity, "entity");
        checkServerThread(entity, "stop");
        if (entity instanceof NpcEntity npc && npc.getNpcId() != null) removeSavedOf(owner, VillagerHandle.of(npc), npc.getNpcId(), entity.getServer());
        stopOwned(owner, entity);
    }

    @Override
    public void stop(String owner, Villager villager) {
        checkOwner(owner);
        Objects.requireNonNull(villager, "villager");
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("TownKitAnimations.stop is server thread only");
        removeSavedOf(owner, villager, villager.id(), server);
        Entity body = villager.entity().orElse(null);
        if (body != null) stopOwned(owner, body);
    }

    /** Forgets the villager's saved loop if {@code owner} saved it. */
    private static void removeSavedOf(String owner, Villager villager, UUID npcId, @Nullable MinecraftServer server) {
        Saved s = saved(npcId, server);
        if (s != null && s.owner().equals(owner)) villager.removeData(Key.KEY);
    }

    /** Stops the entity's play if {@code owner} started it; another owner's saved loop then plays again. */
    private static void stopOwned(String owner, Entity entity) {
        MovePlays.Play p = LIVE.get(entity).orElse(null);
        if (p == null || !p.owner().equals(owner)) return;
        LIVE.stop(entity);
        PENDING.remove(entity);
        if (!resumeSaved(entity) && inLevel(entity)) sync(entity);
        stopTickingIfIdle();
    }

    /** Puts the entity's play into its look (none: the move cleared), which LookSync sends to its trackers. */
    private static void sync(Entity entity) {
        MovePlays.Play p = LIVE.get(entity).orElse(null);
        LookSync.update(entity, l -> p == null ? l.withMove(null, 0, false) : l.withMove(p.move(), p.seq(), p.once()));
    }

    /** Sends the entity's look now if the level holds it, else once it does. */
    private static void show(Entity entity) {
        if (inLevel(entity)) {
            PENDING.remove(entity);
            sync(entity);
        } else {
            PENDING.put(entity, MAX_WAIT);
        }
        startTicking();
    }

    // --- swaps, props, client listeners ----------------------------------------------------------------------------

    @Override
    public Subscription replace(String owner, Move builtIn, Move replacement) {
        return CoreSwaps.replaceGlobal(owner, builtIn, replacement);
    }

    @Override
    public Subscription replace(String owner, Villager villager, Move builtIn, Move replacement) {
        return CoreSwaps.replace(owner, villager, builtIn, replacement);
    }

    @Override
    public Subscription withProps(String owner, Move move, List<Prop> props) {
        checkOwner(owner);
        Objects.requireNonNull(move, "move");
        Objects.requireNonNull(props, "props");
        List<ResourceLocation> ids = new ArrayList<>(props.size());
        for (Prop p : props) ids.add(Objects.requireNonNull(p, "props holds null").id());
        long serial = Props.CLIENT.addFor(owner, move.id(), ids);
        refreshClient();
        return CoreSwaps.subscription(() -> {
        }, () -> {
            if (Props.CLIENT.remove(serial)) refreshClient();
        });
    }

    @Override
    public Subscription onClientMove(String owner, TownKitAnimations.ClientMoveListener listener) {
        checkOwner(owner);
        Objects.requireNonNull(listener, "listener");
        long serial = Moves.CLIENT.addListener(owner, listener);
        refreshClient();
        return CoreSwaps.subscription(() -> {
        }, () -> {
            if (Moves.CLIENT.removeListener(serial)) refreshClient();
        });
    }

    /** On a client, the move hooks and the prop layer follow the registrations (on the render thread). */
    private static void refreshClient() {
        if (FMLEnvironment.dist.isClient()) townkit.core.client.anim.MoveHooks.refreshLater();
    }

    // --- ticking ---------------------------------------------------------------------------------------------------

    private static void startTicking() {
        if (!ticking) {
            ticking = true;
            NeoForge.EVENT_BUS.addListener(TICK);
        }
    }

    private static void stopTickingIfIdle() {
        if (ticking && LIVE.isEmpty() && PENDING.isEmpty()) {
            ticking = false;
            NeoForge.EVENT_BUS.unregister(TICK);
        }
    }

    private static void tick() {
        if (!PENDING.isEmpty()) sendPending();
        for (Entity e : LIVE.tick()) {
            if (!resumeSaved(e) && inLevel(e)) sync(e);
        }
        stopTickingIfIdle();
    }

    /**
     * Sends the look of each waiting entity the level now holds. A removed one is dropped; one still not in its level
     * after {@link #MAX_WAIT} ticks (never added) is dropped with its play, logged once.
     */
    private static void sendPending() {
        for (Map.Entry<Entity, Integer> p : new ArrayList<>(PENDING.entrySet())) {
            Entity e = p.getKey();
            if (inLevel(e)) {
                PENDING.remove(e);
                sync(e);
            } else if (e.isRemoved() || p.getValue() <= 1) {
                PENDING.remove(e);
                LIVE.drop(e);
                if (!e.isRemoved() && !warnedNeverAdded) {
                    warnedNeverAdded = true;
                    TownKit.LOG.warn("[TownKit] moves: {} had a move played but never came into its level within {} ticks: dropped (logged once)",
                        e, MAX_WAIT);
                }
            } else {
                PENDING.put(e, p.getValue() - 1);
            }
        }
    }

    /**
     * After a play on {@code e} ended: plays the villager's saved loop again if it has one still running (and forgets
     * a saved loop whose time is up). True when a saved loop plays now.
     */
    private static boolean resumeSaved(Entity e) {
        if (!(e instanceof NpcEntity npc) || npc.getNpcId() == null || e.getServer() == null || e.isRemoved()) return false;
        Saved s = saved(npc.getNpcId(), e.getServer());
        if (s == null) return false;
        int left = s.ticksLeft(e.getServer().overworld().getGameTime());
        if (left == 0) {
            VillagerHandle.of(npc).removeData(Key.KEY);
            return false;
        }
        LIVE.restore(e, s.owner(), s.move(), left);
        show(e);
        return true;
    }

    // --- loading and leaving ---------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc) || !(event.getLevel() instanceof ServerLevel level)) return;
        UUID npcId = npc.getNpcId();
        if (npcId == null) return;
        Saved s = saved(npcId, level.getServer());
        if (s == null || s.ticksLeft(level.getServer().overworld().getGameTime()) == 0) return;
        if (LIVE.get(npc).isPresent()) return; // a play started on it before it joined wins
        LIVE.restore(npc, s.owner(), s.move(), s.ticksLeft(level.getServer().overworld().getGameTime()));
        PENDING.put(npc, MAX_WAIT);
        startTicking();
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (LIVE.isEmpty() && PENDING.isEmpty() || event.getLevel().isClientSide()) return;
        LIVE.drop(event.getEntity());
        PENDING.remove(event.getEntity());
        stopTickingIfIdle();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LIVE.clear();
        PENDING.clear();
        MoveLengths.forget();
        if (ticking) {
            ticking = false;
            NeoForge.EVENT_BUS.unregister(TICK);
        }
    }

    /**
     * A villager's saved loop, read straight from TownKit's villager data (so reading registers no key); null for none
     * or for data that can't be read (logged once).
     */
    @Nullable
    public static Saved saved(UUID npcId, @Nullable MinecraftServer server) {
        if (server == null || server.overworld() == null) return null;
        Tag tag = VillagerDataStore.of(server).get(npcId, DATA_ID);
        if (tag == null) return null;
        var read = Saved.CODEC.parse(NbtOps.INSTANCE, tag);
        if (read.error().isPresent() && !warnedUnreadable) {
            warnedUnreadable = true;
            TownKit.LOG.warn("[TownKit] a saved villager move can't be read ({}): those villagers play Towntopia's moves", read.error().get().message());
        }
        return read.result().orElse(null);
    }

    // --- holding still ---------------------------------------------------------------------------------------------

    /** Whether {@code entity} may stand still for a move, and if so queues TownKit's hold activity on its brain. */
    private static boolean hold(Entity entity) {
        if (!(entity instanceof NpcEntity npc) || npc.getNpcId() == null) return false;
        VillagerModel model = VillagerHandle.of(npc).model();
        if (model == null || model.isEscapingWater()) return false;
        Activity current = model.brain().currentActivity();
        boolean ownHold = HOLD_ID.equals(ActivityRegistry.addonId(current));
        if (!ownHold && !InterruptGate.allows(InterruptHooks.kindOf(current), ActivityRegistry.allowsRoutineInterruption(current), false, false)) {
            return false;
        }
        if (!holdRegistered) {
            holdRegistered = true;
            ActivityRegistry.register(HOLD_ID, v -> new HoldStill());
            BrainHooks.ensureAttached();
        }
        if (ownHold) return true; // the running hold keeps going while a held play is live
        Activity a = ActivityRegistry.create(HOLD_ID, model);
        if (a == null) return false;
        model.brain().forceActivity(a);
        return true;
    }

    /** Whether the villager's body plays a move it is held still for. */
    public static boolean holding(Villager villager) {
        Entity e = villager.entity().orElse(null);
        return e != null && LIVE.get(e).map(MovePlays.Play::held).orElse(false);
    }

    /** TownKit's own activity: stand still while a held move plays. Refuses Towntopia's routine activities meanwhile. */
    private static final class HoldStill implements VillagerActivity {
        @Override
        public ResourceLocation id() {
            return HOLD_ID;
        }

        @Override
        public void start(ActivityContext ctx) {
            standStill(ctx);
        }

        @Override
        public ActivityStatus tick(ActivityContext ctx) {
            if (!holding(ctx.villager())) return ActivityStatus.DONE;
            standStill(ctx);
            return ActivityStatus.RUNNING;
        }

        private static void standStill(ActivityContext ctx) {
            if (ctx.villager().entity().orElse(null) instanceof PathfinderMob mob && !mob.getNavigation().isDone()) mob.getNavigation().stop();
        }

        @Override
        public boolean allowsRoutineInterruption() {
            return false;
        }
    }

    // --- checks ----------------------------------------------------------------------------------------------------

    private static boolean inLevel(Entity entity) {
        return !entity.isRemoved() && entity.level() instanceof ServerLevel level && level.getEntity(entity.getId()) == entity;
    }

    /** In its server level, or not removed and on its way there. */
    private static boolean present(Entity entity) {
        return !entity.isRemoved() && entity.level() instanceof ServerLevel;
    }

    private static void checkOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) throw new IllegalArgumentException("owner is blank");
    }

    private static void checkArgs(String owner, Move move, PlayMode mode) {
        checkOwner(owner);
        Objects.requireNonNull(move, "move");
        Objects.requireNonNull(mode, "mode");
    }

    private static void checkServerThread(Entity entity, String what) {
        MinecraftServer server = entity.getServer();
        if (entity.level().isClientSide() || server == null || !server.isSameThread()) {
            throw new IllegalStateException("TownKitAnimations." + what + " is server thread only (and needs a server-side entity)");
        }
    }

    private static void warnIfHooksOff(String owner) {
        if (!warnedHooksOff && RewrittenSites.GLOBAL.hooksOff()) {
            warnedHooksOff = true;
            TownKit.LOG.warn("[TownKit] moves: {} played a move, but every Towntopia hook is switched off in townkit-hooks.properties, so clients "
                + "play Towntopia's animations", owner);
        }
    }

    // --- for tests -------------------------------------------------------------------------------------------------

    /** The play on {@code entity}, if any. */
    public static java.util.Optional<MovePlays.Play> playing(Entity entity) {
        return LIVE.get(entity);
    }

    /** Whether {@code entity} is waiting for its look to be sent (it isn't in its level yet). */
    public static boolean waiting(Entity entity) {
        return PENDING.containsKey(entity);
    }

    /** Whether anything plays, waits, or the tick listener is on (nothing should be after the tests clean up). */
    public static boolean anyHeld() {
        return !LIVE.isEmpty() || !PENDING.isEmpty() || ticking;
    }
}
