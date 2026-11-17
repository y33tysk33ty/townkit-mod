package townkit.core.skin;

import com.alex.test.entity.NpcEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import townkit.api.Subscription;
import townkit.api.skin.Look;
import townkit.api.skin.SkinRef;
import townkit.api.spi.TownKitSkinsView;
import townkit.api.village.Villager;
import townkit.api.villager.VillagerDataKey;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.client.skin.LookChoice;
import townkit.core.client.skin.Looks;
import townkit.core.look.LookSync;
import townkit.core.villager.VillagerDataHooks;
import townkit.core.villager.VillagerDataStore;

/**
 * TownKit's side of {@link townkit.api.skin.TownKitSkins}.
 *
 * <p>Looks are client data ({@link Looks#CLIENT}, filled at each client resource reload, and the choice in
 * {@link LookChoice}); on a dedicated server both stay empty.
 *
 * <p>Per-villager skins live on the server: a stack per loaded entity ({@link SkinOverrides}), whose top goes into the
 * entity's {@code VillagerLook} ({@link LookSync}, which sends it to every tracking client). Saved skins are also kept
 * in TownKit's villager data (key {@code townkit:skins}, by Towntopia's npcId, oldest first). A look is dropped with
 * its entity (unload, change of dimension), so when a Towntopia person joins a level with saved skins they are put
 * back on its stack. A skin on an entity the level can't find yet (it is joining: NeoForge fires
 * {@code EntityJoinLevelEvent} before the level holds it; or its section's entities aren't accessible yet) waits in
 * {@link #PENDING} and its look is sent from a server-tick listener once the level holds it (at most
 * {@link #MAX_WAIT} ticks). That listener is added only while something is waiting. Server thread only.
 */
@EventBusSubscriber(modid = TownKit.MODID)
public final class CoreSkins implements TownKitSkinsView {
    /** The villager data id of saved skins. */
    public static final ResourceLocation DATA_ID = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "skins");

    /** Registered on the first {@code set}, so a world where no addon sets a skin registers nothing. */
    private static final class Key {
        static final VillagerDataKey<List<SkinOverrides.Saved>> KEY = VillagerDataHooks.register(DATA_ID, SkinOverrides.Saved.LIST_CODEC);
    }

    /** A saved skin's subscription: the villager and the owner. */
    private record OwnerKey(UUID npcId, String owner) {
    }

    /** The skins of loaded entities (entities compare by identity). */
    private static final SkinOverrides<Entity> LIVE = new SkinOverrides<>();
    /** Loaded bodies that have saved skins, by npcId (a villager's handle can point at an older body). */
    private static final Map<UUID, NpcEntity> BODIES = new HashMap<>();
    /**
     * The serial of the subscription that set each owner's saved skin on a villager in this session. Pruned only by
     * {@code remove} and server stop: one entry per (villager, owner) pair set this session, so it stays small.
     */
    private static final Map<OwnerKey, Long> SAVED_SUBS = new HashMap<>();
    private static long nextSerial = 1;
    /** How long (ticks) an entity with skins may wait to be in its level before its skins are dropped. */
    private static final int MAX_WAIT = 600;
    /**
     * Entities with skins that the level can't find yet (joining, or in a section whose entities aren't accessible
     * yet), and the ticks each may still wait; their look is sent once they are in the level.
     */
    private static final Map<Entity, Integer> PENDING = new HashMap<>();
    private static final Consumer<ServerTickEvent.Post> TICK = e -> sendPending();
    private static boolean ticking;
    private static volatile boolean warnedHooksOff;
    private static volatile boolean warnedUnreadable;
    private static volatile boolean warnedRemoved;
    private static volatile boolean warnedNeverAdded;
    private static volatile boolean warnedGone;

    // --- looks (client) --------------------------------------------------------------------------------------------

    @Override
    public List<Look> looks() {
        return Looks.CLIENT.looks();
    }

    @Override
    public Optional<Look> currentLook() {
        return Looks.CLIENT.current(LookChoice.chosen()).map(Looks.Def::look);
    }

    // --- per-villager skins (server) -------------------------------------------------------------------------------

    @Override
    public Subscription set(String owner, Villager villager, SkinRef skin) {
        checkOwner(owner);
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(skin, "skin");
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("TownKitSkins.set is server thread only");
        warnIfHooksOff(owner);
        if (!villager.exists()) {
            if (!warnedGone) {
                warnedGone = true;
                TownKit.LOG.warn("[TownKit] skins: {} set a skin on {}, which no longer exists: ignored (logged once)", owner, villager);
            }
            return inert();
        }
        UUID npcId = villager.id();
        List<SkinOverrides.Saved> list = new ArrayList<>(villager.data(Key.KEY).orElse(List.of()));
        list.removeIf(s -> s.owner().equals(owner));
        list.add(new SkinOverrides.Saved(owner, skin));
        if (!villager.setData(Key.KEY, list)) return inert();
        long serial = nextSerial++;
        OwnerKey key = new OwnerKey(npcId, owner);
        SAVED_SUBS.put(key, serial);
        NpcEntity body = body(villager);
        if (body != null) {
            LIVE.put(body, owner, true, skin);
            BODIES.put(npcId, body);
            show(body);
        }
        return subscription(CoreSkins::checkRemoveSaved, () -> removeSaved(villager, key, serial));
    }

    /**
     * The villager's body: the handle's, or the one that loaded with saved skins; in its level, or on its way there
     * (not removed, but the level can't find it yet: it is joining). Null when it has none.
     */
    @Nullable
    private static NpcEntity body(Villager villager) {
        Entity e = villager.entity().orElse(null);
        if (e instanceof NpcEntity npc && present(npc)) return npc;
        NpcEntity known = BODIES.get(villager.id());
        return known != null && present(known) ? known : null;
    }

    /** In its server level, or not removed and on its way there. */
    private static boolean present(Entity entity) {
        return !entity.isRemoved() && entity.level() instanceof ServerLevel;
    }

    /** Sends the entity's look now if the level holds it, else once it does ({@link #PENDING}). */
    private static void show(Entity entity) {
        if (inLevel(entity)) {
            PENDING.remove(entity);
            sync(entity);
        } else {
            queue(entity);
        }
    }

    private static void queue(Entity entity) {
        PENDING.put(entity, MAX_WAIT);
        if (!ticking) {
            ticking = true;
            NeoForge.EVENT_BUS.addListener(TICK);
        }
    }

    /** After a skin was removed from {@code entity}: tell clients, and stop waiting if it has none left. */
    private static void removed(Entity entity) {
        if (LIVE.entries(entity).isEmpty()) PENDING.remove(entity);
        if (inLevel(entity)) sync(entity);
    }

    private static void checkRemoveSaved() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) throw new IllegalStateException("removing a TownKitSkins.set subscription is server thread only");
    }

    private static void removeSaved(Villager villager, OwnerKey key, long serial) {
        if (ServerLifecycleHooks.getCurrentServer() == null) return; // stopped: nothing left to change in this session
        // replaced by a later set of the same owner (which owns the saved skin now), or already removed
        if (!SAVED_SUBS.remove(key, serial)) return;
        List<SkinOverrides.Saved> list = new ArrayList<>(villager.data(Key.KEY).orElse(List.of()));
        if (list.removeIf(s -> s.owner().equals(key.owner()))) {
            if (list.isEmpty()) villager.removeData(Key.KEY);
            else villager.setData(Key.KEY, list);
        }
        NpcEntity body = body(villager);
        if (body != null && LIVE.removeOwner(body, key.owner(), true)) removed(body);
    }

    @Override
    public Subscription setTemporary(String owner, Entity entity, SkinRef skin) {
        checkOwner(owner);
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(skin, "skin");
        if (!(entity instanceof NpcEntity)) {
            throw new IllegalArgumentException("TownKitSkins.setTemporary takes one of Towntopia's people (NpcEntity: villagers, the Mayor, "
                + "visitors, nomads), not " + entity.getType());
        }
        checkServerThread(entity, "setTemporary");
        warnIfHooksOff(owner);
        if (!present(entity)) {
            if (!warnedRemoved) {
                warnedRemoved = true;
                TownKit.LOG.warn("[TownKit] skins: {} set a temporary skin on {}, which was removed from its level: ignored (logged once)",
                    owner, entity);
            }
            return inert();
        }
        long serial = LIVE.put(entity, owner, false, skin);
        show(entity);
        return subscription(() -> checkRemoveTemporary(entity), () -> {
            if (sameServer(entity) && LIVE.remove(entity, serial)) removed(entity);
        });
    }

    /** Whether {@code entity}'s server is the one running now (not one stopped by a world switch). */
    private static boolean sameServer(Entity entity) {
        MinecraftServer s = entity.getServer();
        return s != null && s == ServerLifecycleHooks.getCurrentServer();
    }

    /** A temporary skin's removal: server thread only, but quiet once its server has stopped (nothing is held then). */
    private static void checkRemoveTemporary(Entity entity) {
        if (sameServer(entity) && !entity.getServer().isSameThread()) {
            throw new IllegalStateException("removing a TownKitSkins.setTemporary subscription is server thread only");
        }
    }

    @Override
    public Optional<SkinRef> of(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.level().isClientSide()) return Optional.ofNullable(townkit.core.client.ClientLooks.get(entity).skin());
        checkServerThread(entity, "of");
        return LIVE.top(entity);
    }

    /** Puts the entity's winning skin into its look (null for none), which LookSync sends to its trackers. */
    private static void sync(Entity entity) {
        SkinRef top = LIVE.top(entity).orElse(null);
        LookSync.update(entity, l -> l.withSkin(top));
    }

    // --- loading and leaving ---------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc) || !(event.getLevel() instanceof ServerLevel level)) return;
        UUID npcId = npc.getNpcId();
        if (npcId == null) return;
        List<SkinOverrides.Saved> saved = saved(npcId, level.getServer());
        if (saved.isEmpty()) return;
        LIVE.loadSaved(npc, saved);
        BODIES.put(npcId, npc);
        queue(npc);
    }

    /**
     * Sends the look of each waiting entity the level now holds. A removed one is dropped; one still not in its level
     * after {@link #MAX_WAIT} ticks (never added) is dropped with its skins, logged once.
     */
    private static void sendPending() {
        for (Map.Entry<Entity, Integer> p : new ArrayList<>(PENDING.entrySet())) {
            Entity e = p.getKey();
            if (inLevel(e)) {
                PENDING.remove(e);
                sync(e);
            } else if (e.isRemoved() || p.getValue() <= 1) {
                PENDING.remove(e);
                forget(e);
                if (!e.isRemoved() && !warnedNeverAdded) {
                    warnedNeverAdded = true;
                    TownKit.LOG.warn("[TownKit] skins: {} had a skin set but never came into its level within {} ticks: its skins are dropped "
                        + "(logged once)", e, MAX_WAIT);
                }
            } else {
                PENDING.put(e, p.getValue() - 1);
            }
        }
        if (PENDING.isEmpty() && ticking) {
            ticking = false;
            NeoForge.EVENT_BUS.unregister(TICK);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (LIVE.isEmpty() && PENDING.isEmpty() || event.getLevel().isClientSide()) return;
        Entity e = event.getEntity();
        PENDING.remove(e);
        forget(e);
    }

    /** Drops what is held for an entity that is gone. */
    private static void forget(Entity e) {
        LIVE.drop(e);
        if (e instanceof NpcEntity npc && npc.getNpcId() != null) BODIES.remove(npc.getNpcId(), npc);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LIVE.clear();
        BODIES.clear();
        SAVED_SUBS.clear();
        PENDING.clear();
        if (ticking) {
            ticking = false;
            NeoForge.EVENT_BUS.unregister(TICK);
        }
    }

    /**
     * A villager's saved skins (oldest first), read straight from TownKit's villager data (so reading registers no
     * key); empty for none, or for data that can't be read (logged once).
     */
    public static List<SkinOverrides.Saved> saved(UUID npcId, MinecraftServer server) {
        if (server.overworld() == null) return List.of();
        Tag tag = VillagerDataStore.of(server).get(npcId, DATA_ID);
        if (tag == null) return List.of();
        var read = SkinOverrides.Saved.LIST_CODEC.parse(NbtOps.INSTANCE, tag);
        if (read.error().isPresent() && !warnedUnreadable) {
            warnedUnreadable = true;
            TownKit.LOG.warn("[TownKit] saved villager skins can't be read ({}): those villagers are drawn as usual", read.error().get().message());
        }
        return read.result().orElse(List.of());
    }

    // --- checks ----------------------------------------------------------------------------------------------------

    private static boolean inLevel(Entity entity) {
        return !entity.isRemoved() && entity.level() instanceof ServerLevel level && level.getEntity(entity.getId()) == entity;
    }

    private static void checkOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) throw new IllegalArgumentException("owner is blank");
    }

    private static void checkServerThread(Entity entity, String what) {
        MinecraftServer server = entity.getServer();
        if (entity.level().isClientSide() || server == null || !server.isSameThread()) {
            throw new IllegalStateException("TownKitSkins." + what + " is server thread only (and needs a server-side entity)");
        }
    }

    private static void warnIfHooksOff(String owner) {
        if (!warnedHooksOff && RewrittenSites.GLOBAL.hooksOff()) {
            warnedHooksOff = true;
            TownKit.LOG.warn("[TownKit] skins: {} set a villager skin, but every Towntopia hook is switched off in townkit-hooks.properties, "
                + "so clients draw villagers as Towntopia does", owner);
        }
    }

    private static Subscription inert() {
        return subscription(() -> {
        }, () -> {
        });
    }

    /** A subscription whose {@code remove} runs {@code check} (which may throw) every time, then {@code remove} once. */
    private static Subscription subscription(Runnable check, Runnable remove) {
        AtomicBoolean done = new AtomicBoolean();
        return new Subscription() {
            @Override
            public void remove() {
                if (done.get()) return;
                check.run();
                if (done.compareAndSet(false, true)) remove.run();
            }

            @Override
            public boolean isDisabled() {
                return false;
            }
        };
    }

    // --- for tests -------------------------------------------------------------------------------------------------

    /** Whether {@code entity} is waiting for its look to be sent (it isn't in its level yet). */
    public static boolean waiting(Entity entity) {
        return PENDING.containsKey(entity);
    }

    /** Whether any per-villager skin is held or waiting (nothing should be after the tests clean up). */
    public static boolean anyHeld() {
        return !LIVE.isEmpty() || !PENDING.isEmpty() || !BODIES.isEmpty() || ticking;
    }
}
