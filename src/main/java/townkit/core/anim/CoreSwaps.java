package townkit.core.anim;

import com.alex.test.entity.NpcEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import townkit.api.anim.Move;
import townkit.api.village.Villager;
import townkit.api.villager.VillagerDataKey;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.look.LookSync;
import townkit.core.villager.VillagerDataHooks;
import townkit.core.villager.VillagerDataStore;

/**
 * TownKit's side of {@code TownKitAnimations.replace}: the server.
 *
 * <p>The swaps live in one {@link MoveSwaps} table, keyed by Towntopia's npcId for a villager's own. A villager's own
 * swaps are also saved in TownKit's villager data (key {@code townkit:move_swaps}, oldest first) and read into the table
 * when a body of the villager first joins a level in this session (or when an addon changes them).
 *
 * <p>Where each swap is done: a swap to one of Towntopia's moves (other than walking and its gaits) is done here, on the
 * server ({@link MoveStartHooks}: Towntopia's {@code setAnimation} sets the replacement instead). Every other swap
 * (to an addon move, and any swap of walking and its gaits, which clients work out from motion) goes to clients in
 * each villager's {@code VillagerLook} ({@code moveSwaps}, sent by {@link LookSync}); clients play it there. A one-off
 * swap a {@code MOVE_STARTING} handler chose (an addon move for one start) joins that entity's look until Towntopia
 * changes its move. A body that joins a level with swaps waits in {@link #PENDING} until the level holds it (one
 * server-tick listener, attached only while something waits). Global swaps: any thread (the looks are refreshed on the
 * server thread); a villager's own: server thread only.
 */
@EventBusSubscriber(modid = TownKit.MODID)
public final class CoreSwaps {
    /** The villager data id of a villager's own saved swaps. */
    public static final ResourceLocation DATA_ID = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "move_swaps");
    /** Moves each client works out from motion: their swaps are done on the client. */
    public static final Set<ResourceLocation> CLIENT_DERIVED = Set.of(Move.towntopia("WALK").id(), Move.towntopia("RUN").id(),
        Move.towntopia("JOG").id(), Move.towntopia("SWORD_RUN").id(), Move.towntopia("INJURED_RUN").id());
    private static final int MAX_WAIT = 600;

    /** A saved swap of one villager. */
    public record Saved(String owner, ResourceLocation from, ResourceLocation to) {
        public static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("owner").forGetter(Saved::owner),
            ResourceLocation.CODEC.fieldOf("from").forGetter(Saved::from),
            ResourceLocation.CODEC.fieldOf("to").forGetter(Saved::to)).apply(i, Saved::new));
        public static final Codec<List<Saved>> LIST_CODEC = CODEC.listOf();
    }

    /** Registered on the first per-villager swap, so a world where no addon swaps one registers nothing. */
    private static final class Key {
        static final VillagerDataKey<List<Saved>> KEY = VillagerDataHooks.register(DATA_ID, Saved.LIST_CODEC);
    }

    /** One villager's swap of one move by one owner. */
    private record OwnerKey(UUID npcId, String owner, ResourceLocation from) {
    }

    static final MoveSwaps<UUID> TABLE = new MoveSwaps<>();
    /** Villagers whose saved swaps are in {@link #TABLE} this session. Server thread. */
    private static final Set<UUID> LOADED = new HashSet<>();
    /** The table serial of each villager's swap per owner and move (saved ones read at load included). Server thread. */
    private static final Map<OwnerKey, Long> SERIALS = new HashMap<>();
    /** One-off swaps chosen by a MOVE_STARTING handler, per entity: Towntopia move id -> addon move id. Server thread. */
    private static final Map<Entity, Map.Entry<ResourceLocation, ResourceLocation>> ONE_OFF = new HashMap<>();
    private static final Map<Entity, Integer> PENDING = new HashMap<>();
    /** Loaded bodies of villagers with swaps of their own, by npcId (a villager's handle can point at an older body). */
    private static final Map<UUID, NpcEntity> BODIES = new HashMap<>();
    private static final Consumer<ServerTickEvent.Post> TICK = e -> sendPending();
    private static boolean ticking;
    private static volatile boolean warnedHooksOff;
    private static volatile boolean warnedUnreadable;
    private static volatile boolean warnedGone;
    private static final Set<ResourceLocation> WARNED_UNKNOWN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CoreSwaps() {
    }

    // --- registering -----------------------------------------------------------------------------------------------

    /** {@code TownKitAnimations.replace(owner, builtIn, replacement)}: any thread. */
    public static Subscription replaceGlobal(String owner, Move builtIn, Move replacement) {
        check(owner, builtIn, replacement);
        warnIfHooksOff(owner);
        long serial = TABLE.add(null, owner, builtIn.id(), replacement.id());
        changed();
        return subscription(() -> {
        }, () -> {
            if (TABLE.remove(serial)) changed();
        });
    }

    /** {@code TownKitAnimations.replace(owner, villager, builtIn, replacement)}: server thread. */
    public static Subscription replace(String owner, Villager villager, Move builtIn, Move replacement) {
        check(owner, builtIn, replacement);
        Objects.requireNonNull(villager, "villager");
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("TownKitAnimations.replace(owner, villager, ...) is server thread only");
        warnIfHooksOff(owner);
        if (!villager.exists()) {
            if (!warnedGone) {
                warnedGone = true;
                TownKit.LOG.warn("[TownKit] moves: {} swapped a move on {}, which no longer exists: ignored (logged once)", owner, villager);
            }
            return subscription(() -> {
            }, () -> {
            });
        }
        UUID npcId = villager.id();
        ensureLoaded(npcId, server);
        OwnerKey key = new OwnerKey(npcId, owner, builtIn.id());
        Long old = SERIALS.remove(key);
        if (old != null) TABLE.remove(old);
        long serial = TABLE.add(npcId, owner, builtIn.id(), replacement.id());
        SERIALS.put(key, serial);
        List<Saved> list = new ArrayList<>(villager.data(Key.KEY).orElse(List.of()));
        list.removeIf(s -> s.owner().equals(owner) && s.from().equals(builtIn.id()));
        list.add(new Saved(owner, builtIn.id(), replacement.id()));
        villager.setData(Key.KEY, list);
        MoveStartHooks.refresh();
        showBody(villager);
        return subscription(() -> {
            MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
            if (s != null && !s.isSameThread()) throw new IllegalStateException("removing a TownKitAnimations.replace(owner, villager, ...) subscription is server thread only");
        }, () -> {
            if (ServerLifecycleHooks.getCurrentServer() != server || !SERIALS.remove(key, serial)) return; // stopped, or replaced since
            TABLE.remove(serial);
            List<Saved> now = new ArrayList<>(villager.data(Key.KEY).orElse(List.of()));
            if (now.removeIf(s -> s.owner().equals(owner) && s.from().equals(key.from()))) {
                if (now.isEmpty()) villager.removeData(Key.KEY);
                else villager.setData(Key.KEY, now);
            }
            MoveStartHooks.refresh();
            showBody(villager);
            if (!TABLE.has(key.npcId())) BODIES.remove(key.npcId());
        });
    }

    /** Refreshes the look of the villager's body: the handle's, or the one that loaded with its swaps. */
    private static void showBody(Villager villager) {
        Entity e = villager.entity().orElse(null);
        if (e != null && !e.isRemoved()) {
            show(e);
            if (e instanceof NpcEntity npc) BODIES.put(villager.id(), npc);
            return;
        }
        NpcEntity known = BODIES.get(villager.id());
        if (known != null && !known.isRemoved()) show(known);
    }

    private static void check(String owner, Move builtIn, Move replacement) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(builtIn, "builtIn");
        Objects.requireNonNull(replacement, "replacement");
        if (owner.isBlank()) throw new IllegalArgumentException("owner is blank");
        if (!builtIn.isTowntopia()) throw new IllegalArgumentException("only Towntopia's moves can be replaced, not " + builtIn.id());
        warnIfUnknown(builtIn);
        if (replacement.isTowntopia()) warnIfUnknown(replacement);
        if (CLIENT_DERIVED.contains(replacement.id()) && !CLIENT_DERIVED.contains(builtIn.id())
            && WARNED_UNKNOWN.add(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "gait/" + builtIn.id().getPath() + "/" + replacement.id().getPath()))) {
            TownKit.LOG.warn("[TownKit] moves: {} swapped {} for the gait {}, which Towntopia only plays while walking: this swap does nothing "
                + "(logged once)", owner, builtIn.name(), replacement.name());
        }
    }

    /** Logs once per move a Towntopia move that is neither one of Towntopia's moves nor a gait: it never matches. */
    static void warnIfUnknown(Move move) {
        if (!CLIENT_DERIVED.contains(move.id()) && MoveStartHooks.stateOf(move) == null && WARNED_UNKNOWN.add(move.id())) {
            TownKit.LOG.warn("[TownKit] moves: Towntopia has no move {}: swaps of it or to it do nothing (logged once)", move.name());
        }
    }

    /** After a global change: refresh the hook and every loaded villager's look (server thread). */
    private static void changed() {
        MoveStartHooks.refresh();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        if (server.isSameThread()) showAll(server);
        else server.execute(() -> showAll(server));
    }

    private static void showAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof NpcEntity) show(e);
            }
        }
    }

    // --- what the server and clients do ----------------------------------------------------------------------------

    /**
     * The move Towntopia's move {@code from} is swapped for on the server ({@link MoveStartHooks}), or null: not swapped,
     * or swapped on clients (walking and its gaits, or a swap to an addon move: the server keeps Towntopia's move).
     */
    @Nullable
    static ResourceLocation serverSwap(NpcEntity npc, ResourceLocation from) {
        if (TABLE.isEmpty() || CLIENT_DERIVED.contains(from)) return null;
        UUID id = npc.getNpcId();
        ResourceLocation to = TABLE.lookup(id, from);
        return to != null && "towntopia".equals(to.getNamespace()) ? to : null;
    }

    /** The swaps the entity's clients do: walking and gaits, and swaps to addon moves; then its one-off. */
    static Map<ResourceLocation, ResourceLocation> clientSwaps(Entity entity) {
        Map<ResourceLocation, ResourceLocation> out = new LinkedHashMap<>();
        if (!TABLE.isEmpty()) {
            UUID id = entity instanceof NpcEntity npc ? npc.getNpcId() : null;
            TABLE.effective(id).forEach((from, to) -> {
                if (CLIENT_DERIVED.contains(from) || !"towntopia".equals(to.getNamespace())) out.put(from, to);
            });
        }
        Map.Entry<ResourceLocation, ResourceLocation> one = ONE_OFF.get(entity);
        if (one != null) out.put(one.getKey(), one.getValue());
        return out;
    }

    /** A MOVE_STARTING handler chose addon move {@code to} for this start of {@code from}: clients play it. */
    static void setOneOff(Entity entity, ResourceLocation from, ResourceLocation to) {
        Map.Entry<ResourceLocation, ResourceLocation> e = Map.entry(from, to);
        if (e.equals(ONE_OFF.put(entity, e))) return;
        show(entity);
    }

    /** Towntopia changed the entity's move: a one-off swap is over. */
    static void clearOneOff(Entity entity) {
        if (ONE_OFF.remove(entity) != null) show(entity);
    }

    /** Puts the entity's client swaps into its look now if the level holds it, else once it does. */
    private static void show(Entity entity) {
        if (inLevel(entity)) {
            PENDING.remove(entity);
            sync(entity);
        } else if (!entity.isRemoved()) {
            PENDING.put(entity, MAX_WAIT);
            if (!ticking) {
                ticking = true;
                NeoForge.EVENT_BUS.addListener(TICK);
            }
        }
    }

    private static void sync(Entity entity) {
        Map<ResourceLocation, ResourceLocation> swaps = clientSwaps(entity);
        LookSync.update(entity, l -> l.withMoveSwaps(swaps));
    }

    private static void sendPending() {
        for (Map.Entry<Entity, Integer> p : new ArrayList<>(PENDING.entrySet())) {
            Entity e = p.getKey();
            if (inLevel(e)) {
                PENDING.remove(e);
                sync(e);
            } else if (e.isRemoved() || p.getValue() <= 1) {
                PENDING.remove(e);
            } else {
                PENDING.put(e, p.getValue() - 1);
            }
        }
        if (PENDING.isEmpty() && ticking) {
            ticking = false;
            NeoForge.EVENT_BUS.unregister(TICK);
        }
    }

    // --- loading and leaving ---------------------------------------------------------------------------------------

    /** Reads the villager's saved swaps into the table, once per session. Server thread. */
    private static void ensureLoaded(UUID npcId, MinecraftServer server) {
        if (!LOADED.add(npcId)) return;
        List<Saved> saved = saved(npcId, server);
        for (Saved s : saved) {
            OwnerKey key = new OwnerKey(npcId, s.owner(), s.from());
            Long old = SERIALS.remove(key);
            if (old != null) TABLE.remove(old);
            SERIALS.put(key, TABLE.add(npcId, s.owner(), s.from(), s.to()));
        }
        if (!saved.isEmpty()) MoveStartHooks.refresh();
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (npc.getNpcId() != null) {
            ensureLoaded(npc.getNpcId(), level.getServer());
            if (TABLE.has(npc.getNpcId())) BODIES.put(npc.getNpcId(), npc);
        }
        if (!TABLE.isEmpty() && !clientSwaps(npc).isEmpty()) show(npc);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || PENDING.isEmpty() && ONE_OFF.isEmpty() && BODIES.isEmpty()) return;
        PENDING.remove(event.getEntity());
        ONE_OFF.remove(event.getEntity());
        if (event.getEntity() instanceof NpcEntity npc && npc.getNpcId() != null) BODIES.remove(npc.getNpcId(), npc);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TABLE.clearKeyed();
        LOADED.clear();
        SERIALS.clear();
        ONE_OFF.clear();
        PENDING.clear();
        BODIES.clear();
        if (ticking) {
            ticking = false;
            NeoForge.EVENT_BUS.unregister(TICK);
        }
        MoveStartHooks.refresh();
    }

    /** A villager's saved swaps (oldest first), read straight from TownKit's villager data; empty for none or unreadable. */
    public static List<Saved> saved(UUID npcId, MinecraftServer server) {
        if (server.overworld() == null) return List.of();
        Tag tag = VillagerDataStore.of(server).get(npcId, DATA_ID);
        if (tag == null) return List.of();
        var read = Saved.LIST_CODEC.parse(NbtOps.INSTANCE, tag);
        if (read.error().isPresent() && !warnedUnreadable) {
            warnedUnreadable = true;
            TownKit.LOG.warn("[TownKit] saved villager move swaps can't be read ({}): those villagers play Towntopia's moves", read.error().get().message());
        }
        return read.result().orElse(List.of());
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static boolean inLevel(Entity entity) {
        return !entity.isRemoved() && entity.level() instanceof ServerLevel level && level.getEntity(entity.getId()) == entity;
    }

    private static void warnIfHooksOff(String owner) {
        if (!warnedHooksOff && RewrittenSites.GLOBAL.hooksOff()) {
            warnedHooksOff = true;
            TownKit.LOG.warn("[TownKit] moves: {} swapped a move, but every Towntopia hook is switched off in townkit-hooks.properties, so "
                + "villagers play Towntopia's moves", owner);
        }
    }

    /** A subscription whose {@code remove} runs {@code check} (which may throw) every time, then {@code remove} once. */
    static Subscription subscription(Runnable check, Runnable remove) {
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

    /** Whether any swap is registered (global or per villager this session) or waiting to be sent. */
    public static boolean anyHeld() {
        return !TABLE.isEmpty() || !PENDING.isEmpty() || !ONE_OFF.isEmpty() || !BODIES.isEmpty() || ticking;
    }

    /** Whether {@code entity} waits for its swaps to be sent. */
    public static boolean waiting(Entity entity) {
        return PENDING.containsKey(entity);
    }
}
