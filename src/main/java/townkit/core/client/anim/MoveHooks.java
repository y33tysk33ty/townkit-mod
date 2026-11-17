package townkit.core.client.anim;

import com.alex.test.animation.FastController;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.loading.object.BakedAnimations;
import townkit.api.anim.Move;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.core.TownKit;
import townkit.core.anim.CoreSwaps;
import townkit.core.anim.MoveFiles;
import townkit.core.anim.TowntopiaMoves;
import townkit.core.client.ClientLooks;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.look.VillagerLook;

/**
 * Client side of moves: reads the move files at each resource reload ({@link Moves#CLIENT}) and plays the move in each
 * villager's look (sent by the server, {@link ClientLooks}).
 *
 * <p>A villager whose look carries a move gets one extra GeckoLib controller, {@value #CONTROLLER}, added last to its
 * animation manager, so it wins on the bones the move keys and Towntopia's controllers keep the rest (walking legs,
 * blinking). It is one of Towntopia's {@code FastController}s: Towntopia's fast pose path drops any villager with
 * another controller type to GeckoLib's slow one. Its handler reads the look every frame: a new {@code moveSeq}
 * restarts the move; a once move is played through to its end, then the handler returns STOP, so the controller
 * stops keying bones and Towntopia's animation shows again (never a held last frame); a move the client can't resolve
 * ({@link Moves#resolve}) plays nothing (Towntopia's animation, logged once per move); no move: STOP. The controller
 * stays on the entity afterwards, stopped (a STOP costs one check per frame); it goes with the entity.
 *
 * <p>Swaps (the look's {@code moveSwaps}, sent by the server): while no move plays, a swap of the villager's current
 * Towntopia move to an addon move plays the addon move looping on the same controller, until Towntopia's move (or the
 * swap) changes. Walking and its gaits are swapped in Towntopia's walk controller instead: an after-hook on its private
 * {@code NpcEntity#hurryClip()} (which the walk controller plays in place of the walk when it isn't null) returns the
 * replacement chosen when the villager started walking (or changed gait, or got another look): the look's swap, then
 * each client move listener ({@code TownKitAnimations.onClientMove}) in turn.
 *
 * <p>Hooks, attached only while some villager's look carries a move or swaps, a client move listener is registered or
 * a prop is registered for a move (props need to know who walks; the walk hook only for swaps, listeners and props):
 * {@code NpcEntity#registerControllers} after (a villager drawn for the first time gets the controller with
 * Towntopia's), {@code VillagerGeoModel#getAnimationResourceFallbacks} after (adds the addon animation files GeckoLib
 * has loaded, where GeckoLib looks a clip name up after Towntopia's own file; a missing file is never listed) and
 * {@code NpcEntity#hurryClip()} after (walking). Render thread.
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class MoveHooks {
    /** The extra controller's name. */
    public static final String CONTROLLER = "townkit_move";
    static final String REGISTER = "entity.NpcEntity#registerControllers(AnimatableManager.ControllerRegistrar)";
    static final String FALLBACKS = "client.VillagerGeoModel#getAnimationResourceFallbacks(PathfinderMob)";
    static final String HURRY = "entity.NpcEntity#hurryClip()";
    /** A villager counts as walking for this many ticks after the walk controller last asked (as Towntopia does). */
    private static final int WALK_TICKS = 3;
    private static final ResourceLocation WALK = Move.towntopia("WALK").id();
    /** Ticks the controller blends into and out of a move. */
    private static final int BLEND = 4;

    private static final FileToIdConverter FILES = FileToIdConverter.json(MoveFiles.FOLDER);

    private enum Slot {
        ADD_CONTROLLER(REGISTER, MoveHooks::afterRegister),
        ADD_FILES(FALLBACKS, MoveHooks::afterFallbacks),
        WALK_SWAP(HURRY, MoveHooks::afterHurry);

        final String key;
        final HookListener listener;

        Slot(String key, HookListener listener) {
            this.key = key;
            this.listener = listener;
        }
    }

    private static final Map<Slot, Hooks.Entry> ATTACHED = new EnumMap<>(Slot.class);
    private static boolean warnedMissing;
    /** Towntopia's fallback array and the one TownKit returned for it (rebuilt when either changes). */
    @Nullable
    private static ResourceLocation[] fallbacksFrom;
    @Nullable
    private static ResourceLocation[] fallbacks;
    /** Bumped at each move file reload, so the fallback array is rebuilt. */
    private static int filesVersion;
    private static int fallbacksVersion = -1;
    /** GeckoLib's baked animations map the fallback array was filtered against (GeckoLib swaps it at each reload). */
    @Nullable
    private static Map<ResourceLocation, BakedAnimations> fallbacksBaked;
    /** How many moves the controllers started (for the client check). */
    private static int started;
    /** How many walk swaps were chosen (for the client check). */
    private static int walkSwaps;
    /**
     * Each villager's move controller state. Render thread. A {@link Player} must never hold its villager (or any
     * entity): a WeakHashMap value that reaches its key keeps it, and its whole client level, forever.
     */
    private static final Map<NpcEntity, Player> PLAYERS = new WeakHashMap<>();
    /** Villagers seen walking, and what they walk with (never the villager itself). Render thread. */
    private static final Map<NpcEntity, Walk> WALKING = new WeakHashMap<>();
    /** Towntopia's move ids by state ordinal (no allocation per frame). */
    private static final ResourceLocation[] STATE_IDS = stateIds();
    /** Towntopia's gait clips -> gait move ids, built on first use. */
    @Nullable
    private static Map<String, ResourceLocation> gaitByClip;
    /** Loop animations by clip name. */
    private static final Map<String, RawAnimation> LOOPS = new HashMap<>();

    private MoveHooks() {
    }

    // --- move files ------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Map<ResourceLocation, MoveFiles.Def>>() {
            @Override
            protected Map<ResourceLocation, MoveFiles.Def> prepare(ResourceManager manager, ProfilerFiller profiler) {
                Map<ResourceLocation, MoveFiles.Def> out = new HashMap<>();
                for (Map.Entry<ResourceLocation, Resource> f : FILES.listMatchingResources(manager).entrySet()) {
                    ResourceLocation id = MoveFiles.idOf(f.getKey());
                    try (Reader reader = f.getValue().openAsReader()) {
                        MoveFiles.parse(id, reader).ifPresent(d -> out.put(id, d));
                    } catch (Exception ex) {
                        TownKit.LOG.warn("[TownKit] move {} ignored: can't read {} ({})", id, f.getKey(), ex.toString());
                    }
                }
                return out;
            }

            @Override
            protected void apply(Map<ResourceLocation, MoveFiles.Def> read, ResourceManager manager, ProfilerFiller profiler) {
                Moves.CLIENT.replace(read);
                filesVersion++;
                PropLayer.refresh();
                if (!read.isEmpty()) TownKit.LOG.info("[TownKit] addon moves: {}", read.keySet());
            }
        });
    }

    // --- attaching -------------------------------------------------------------------------------------------------

    /** Attaches the hooks while some look carries a move, detaches them otherwise. Render thread. */
    public static synchronized void refresh() {
        // walking matters only for walk swaps, client move listeners and registered props (a prop on a walk)
        boolean walks = ClientLooks.anySwap() || Moves.CLIENT.hasListeners() || Props.CLIENT.anyRegistered();
        boolean anyWanted = walks || ClientLooks.anyMove();
        for (Slot slot : Slot.values()) {
            boolean wanted = slot == Slot.WALK_SWAP ? walks : anyWanted;
            Hooks.Entry e = ATTACHED.get(slot);
            if (wanted && e == null) {
                HookInfo h = HookManifest.get().byKey.get(slot.key);
                if (h == null) {
                    if (!warnedMissing) {
                        warnedMissing = true;
                        TownKit.LOG.warn("[TownKit] can't find {}: addon moves may not play", slot.key);
                    }
                    continue;
                }
                ATTACHED.put(slot, Hooks.add(h, TownKit.MODID, slot.listener, true));
            } else if (!wanted && e != null) {
                Hooks.remove(e);
                ATTACHED.remove(slot);
            }
        }
        if (!anyWanted) {
            fallbacks = null;
            fallbacksFrom = null;
            fallbacksBaked = null;
        }
        if (!walks) WALKING.clear();
        PropLayer.refresh();
    }

    /** Refreshes the hooks and the prop layer on the render thread (after a registration from any thread). */
    public static void refreshLater() {
        Minecraft.getInstance().execute(MoveHooks::refresh);
    }

    /** The hooks attached now, as {@code "after <key>"} (for the client check). */
    public static synchronized List<String> attachedKeys() {
        List<String> out = new ArrayList<>();
        ATTACHED.forEach((slot, e) -> out.add("after " + slot.key));
        return out;
    }

    /** A look with a move arrived for this entity id: make sure the entity has the move controller. Main thread. */
    public static void moveArrived(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getEntity(entityId) instanceof NpcEntity npc) ensureController(npc);
    }

    private static void ensureController(NpcEntity npc) {
        try {
            AnimatableManager<NpcEntity> manager = npc.getAnimatableInstanceCache().getManagerForId(npc.getId());
            if (!manager.getAnimationControllers().containsKey(CONTROLLER)) manager.addController(controller(npc));
        } catch (RuntimeException | LinkageError e) {
            TownKit.LOG.warn("[TownKit] can't add the move controller to {} ({}): its moves don't play", npc, e.toString());
        }
    }

    private static AnimationController<NpcEntity> controller(NpcEntity npc) {
        Player player = new Player();
        PLAYERS.put(npc, player);
        return new FastController<>(npc, CONTROLLER, BLEND, player::handle);
    }

    // --- hooks -----------------------------------------------------------------------------------------------------

    private static void afterRegister(HookCall call) {
        if (!(call.self() instanceof NpcEntity npc) || !(call.args()[0] instanceof AnimatableManager.ControllerRegistrar reg)) return;
        reg.add(controller(npc));
    }

    private static void afterFallbacks(HookCall call) {
        if (!(call.returnValue() instanceof ResourceLocation[] from)) return;
        Map<ResourceLocation, BakedAnimations> baked = GeckoLibCache.getBakedAnimations();
        if (from != fallbacksFrom || fallbacks == null || fallbacksVersion != filesVersion || baked != fallbacksBaked) {
            // only files GeckoLib loaded: a missing last fallback makes GeckoLib throw on an unknown clip name
            fallbacks = Moves.CLIENT.fallbacks(from, Moves.CLIENT.animationFiles(), baked::containsKey);
            fallbacksFrom = from;
            fallbacksVersion = filesVersion;
            fallbacksBaked = baked;
        }
        call.setReturnValue(fallbacks);
    }

    /** What a walking villager walks with: chosen when it started walking, changed gait or got another look. */
    private static final class Walk {
        final ResourceLocation from;
        final ResourceLocation chosen;
        @Nullable
        final RawAnimation raw;
        final VillagerLook look;
        int tick;

        Walk(ResourceLocation from, ResourceLocation chosen, @Nullable RawAnimation raw, VillagerLook look) {
            this.from = from;
            this.chosen = chosen;
            this.raw = raw;
            this.look = look;
        }
    }

    private static void afterHurry(HookCall call) {
        if (!(call.self() instanceof NpcEntity npc)) return;
        ResourceLocation from = call.returnValue() instanceof RawAnimation gait ? gaitOf(gait) : WALK;
        if (from == null) return; // a gait clip TownKit doesn't know: Towntopia's
        VillagerLook look = ClientLooks.get(npc);
        Walk w = WALKING.get(npc);
        if (w == null || !w.from.equals(from) || w.look != look || npc.tickCount - w.tick > WALK_TICKS) {
            ResourceLocation chosen = choose(npc, from, look);
            w = new Walk(from, chosen, chosen.equals(from) ? null : loop(chosen), look);
            if (w.raw != null) walkSwaps++;
            WALKING.put(npc, w);
        }
        w.tick = npc.tickCount;
        if (w.raw != null) call.setReturnValue(w.raw);
    }

    /** The look's swap for a client-worked-out move, then each client move listener in turn. */
    private static ResourceLocation choose(NpcEntity npc, ResourceLocation from, VillagerLook look) {
        ResourceLocation swapped = look.moveSwaps().get(from);
        Move m = Move.of(swapped == null ? from : swapped);
        for (Moves.Listener l : Moves.CLIENT.listeners()) {
            try {
                Move r = l.listener().starting(npc, m);
                if (r != null) m = r;
            } catch (Throwable t) {
                Moves.CLIENT.warnListener(l.owner(), t);
            }
        }
        return m.id();
    }

    /** The gait move whose Towntopia clip {@code gait} plays, or null for one TownKit doesn't know. */
    @Nullable
    private static ResourceLocation gaitOf(RawAnimation gait) {
        if (gait.getAnimationStages().isEmpty()) return null;
        if (gaitByClip == null) {
            Map<String, ResourceLocation> m = new HashMap<>();
            for (String g : new String[] {"RUN", "JOG", "SWORD_RUN", "INJURED_RUN"}) {
                String clip = TowntopiaMoves.clip(g);
                if (clip != null) m.put(clip, Move.towntopia(g).id());
            }
            gaitByClip = m;
        }
        return gaitByClip.get(gait.getAnimationStages().get(0).animationName());
    }

    /** A looping animation of {@code move}'s clip, or null when this client can't play it (logged once per move). */
    @Nullable
    private static RawAnimation loop(ResourceLocation move) {
        Moves.Clip clip = Moves.CLIENT.resolve(move, TowntopiaMoves::clip, MoveHooks::clipExists);
        return clip == null ? null : LOOPS.computeIfAbsent(clip.animation(), n -> RawAnimation.begin().thenLoop(n));
    }

    private static ResourceLocation[] stateIds() {
        VillagerAnimationState[] all = VillagerAnimationState.values();
        ResourceLocation[] out = new ResourceLocation[all.length];
        for (int i = 0; i < all.length; i++) out[i] = Move.towntopia(all[i].name()).id();
        return out;
    }

    /** Towntopia's move id of the villager's synced move state. */
    @Nullable
    private static ResourceLocation stateId(NpcEntity npc) {
        VillagerAnimationState s = npc.getAnimation();
        return s == null ? null : STATE_IDS[s.ordinal()];
    }

    /**
     * The move a villager shows now, for its props: a move TownKit plays on it (a play, or a swap of Towntopia's move
     * to an addon move); else its walk or gait (after swaps) while it walks; else Towntopia's move. Render thread.
     */
    @Nullable
    public static ResourceLocation currentMove(NpcEntity npc) {
        Player p = PLAYERS.get(npc);
        if (p != null && p.current != null) return p.current;
        Walk w = WALKING.get(npc);
        if (w != null && npc.tickCount - w.tick <= WALK_TICKS) return w.chosen;
        return stateId(npc);
    }

    // --- playing ---------------------------------------------------------------------------------------------------

    /** Whether GeckoLib has loaded {@code file} and it holds an animation named {@code name}. */
    static boolean clipExists(ResourceLocation file, String name) {
        BakedAnimations baked = GeckoLibCache.getBakedAnimations().get(file);
        return baked != null && baked.getAnimation(name) != null;
    }

    /** One villager's move controller state. */
    private static final class Player {
        /** The look's move sequence this player last started (0: none). */
        private int seq;
        /** The swap this player plays (Towntopia move id -> addon move id), when no move is played. */
        @Nullable
        private ResourceLocation swapFrom;
        @Nullable
        private ResourceLocation swapTo;
        @Nullable
        private RawAnimation raw;
        private boolean once;
        /** The current move is over (a once move played through) or can't be played. */
        private boolean done = true;
        /** The move this controller plays now, or null. */
        @Nullable
        ResourceLocation current;

        PlayState handle(AnimationState<NpcEntity> state) {
            NpcEntity npc = state.getAnimatable();
            VillagerLook look = ClientLooks.get(npc);
            AnimationController<NpcEntity> c = state.getController();
            if (look.move() == null) {
                seq = 0;
                return swap(npc, look, c);
            }
            swapFrom = null;
            swapTo = null;
            if (look.moveSeq() != seq) {
                seq = look.moveSeq();
                once = look.moveOnce();
                Moves.Clip clip = Moves.CLIENT.resolve(look.move(), TowntopiaMoves::clip, MoveHooks::clipExists);
                raw = clip == null ? null : once ? RawAnimation.begin().thenPlay(clip.animation()) : RawAnimation.begin().thenLoop(clip.animation());
                return begin(c, look.move());
            }
            if (done) return stop();
            if (once && c.hasAnimationFinished()) {
                // played through: stop keying bones, so Towntopia's animation shows again (not the last frame)
                return stop();
            }
            c.setAnimation(raw);
            return PlayState.CONTINUE;
        }

        /** No move played: the look's swap of the villager's current Towntopia move, if it has one (not walking). */
        private PlayState swap(NpcEntity npc, VillagerLook look, AnimationController<NpcEntity> c) {
            ResourceLocation from = look.moveSwaps().isEmpty() ? null : stateId(npc);
            ResourceLocation to = from == null || CoreSwaps.CLIENT_DERIVED.contains(from) ? null : look.moveSwaps().get(from);
            if (to == null) {
                swapFrom = null;
                swapTo = null;
                raw = null;
                return stop();
            }
            if (!from.equals(swapFrom) || !to.equals(swapTo)) {
                swapFrom = from;
                swapTo = to;
                once = false;
                raw = loop(to);
                return begin(c, to);
            }
            if (done) return stop();
            c.setAnimation(raw);
            return PlayState.CONTINUE;
        }

        private PlayState begin(AnimationController<NpcEntity> c, ResourceLocation move) {
            done = raw == null;
            if (done) return stop();
            started++;
            current = move;
            c.forceAnimationReset();
            c.setAnimation(raw);
            return PlayState.CONTINUE;
        }

        private PlayState stop() {
            done = true;
            current = null;
            return PlayState.STOP;
        }
    }

    /** A new connection starts clean: the villagers of the level left behind go (with it). */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PLAYERS.clear();
        WALKING.clear();
    }

    // --- for the client check --------------------------------------------------------------------------------------

    /** How many walk swaps were chosen since the game started. */
    public static int walkSwaps() {
        return walkSwaps;
    }

    /** How many moves the controllers started since the game started. */
    public static int started() {
        return started;
    }

    /** The move controller of {@code entity}, or null when it has none. */
    @Nullable
    public static AnimationController<?> controllerOf(Entity entity) {
        if (!(entity instanceof NpcEntity npc)) return null;
        return npc.getAnimatableInstanceCache().getManagerForId(npc.getId()).getAnimationControllers().get(CONTROLLER);
    }
}
