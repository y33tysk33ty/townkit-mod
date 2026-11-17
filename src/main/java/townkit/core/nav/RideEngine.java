package townkit.core.nav;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.FollowWalk;
import com.alex.test.village.MoveToCommand;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.api.nav.Ride;
import townkit.api.nav.RideStatus;
import townkit.api.nav.TransportKind;
import townkit.api.nav.TransportLink;
import townkit.core.FieldReader;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.village.VillagerHandle;

/**
 * Runs rides over transport links, on the server thread, at most one per villager (keyed by npc id).
 *
 * <ul>
 *   <li>A before-hook on {@code FollowWalk#endOfRoute}: the walk of any Towntopia NPC (a member's own walk, a guard's
 *       chase walk, a traveler that no village holds) that reaches a link cut (kind 4: the next tiles aren't side by
 *       side) whose pair is a link it may ride now starts a ride and answers RUNNING. The ride is keyed on the walk
 *       being ticked; the link's village is the one with a link on that pair in the walker's dimension.</li>
 *   <li>A before-hook on {@code FollowWalk#tick}: while that walk has a ride, the ride ticks instead of the walk
 *       (RUNNING). DONE puts the villager on the far stop B and answers REPLAN: Towntopia plans the rest from B. FAILED,
 *       a throw, {@link Ride#timeoutTicks()} ride ticks (counted only on ticks the walk really runs), or the link no
 *       longer rideable mid-ride (a stop's frame broken or emptied, or the kind's {@code available} false at a refresh:
 *       an elevator drained) falls back: the villager is put on B when B is safe (dry, head room, loaded), else back on
 *       the boarding stop A; the step A -> B is marked failed in Towntopia's map ({@code markStepFailed}, 10 minutes)
 *       and in TownKit's link table (so a full build or refresh doesn't forget it), and the walk answers REPLAN.</li>
 *   <li>Water rescue ({@code VillagerModel#needsWaterRescue}) answers false for a rider (an elevator may be water); the
 *       suppression lapses on its own fixed clock, {@code timeoutTicks + 100} game ticks after the ride started.
 *       Towntopia's stuck-loop check ({@code noteStuckLoop}) is skipped for a rider.</li>
 *   <li>A ride whose walk stops being the villager's active move (another activity took over; checked only when the
 *       rider has a village model and the walk was its active move at the start), whose villager unloads
 *       ({@code VillagerBrain#onPuppetUnloaded}) or is removed ({@code VillageManager#removeVillager}), or whose walk
 *       hasn't ticked for {@code timeoutTicks + 100} game ticks (Towntopia holds walks without ticking them, for
 *       example on frozen ground past every player's simulation distance), ends with the same placement (B if safe,
 *       else A) and its state is dropped. Only a ride that failed while riding marks the link failed: an interruption
 *       or a held walk isn't the link's fault.</li>
 *   <li>A ride whose walk is not a village model's active move (a traveler's, a guard's chase walk) and whose walk was
 *       dropped ends the same way once the walk hasn't ticked for {@link #DROPPED_WALK_TICKS}. Dropped means its owner
 *       let it go: Towntopia calls {@code FollowWalk#closeAllOpenedDoors} on a walk exactly when it drops or replaces it
 *       (TravelerApproach's clear and re-plan, GuardFootwork's dropChaseWalk/dropMapStep, VillagerModel, raiders) and
 *       never on a walk it only holds (a menu open, a dormant village, frozen ground), so a held ride keeps the long
 *       check above. Without that hook a dropped walk's ride ends through the long check, as in 0.6.0.</li>
 *   <li>While riding, the villager's entity carries the tag {@link #RIDE_TAG} (both stops and the dimension). An
 *       entity saved mid-ride (a chunk unload, an autosave before a crash) and loaded again in that dimension is put on
 *       B if safe, else A, and the tag is dropped, so a reload never leaves it mid-ride.</li>
 *   <li>A server stop drops every ride (each told {@code end(ctx, false)}), so nothing of one world reaches the next.</li>
 * </ul>
 *
 * <p>Hooks attach when the first transport kind is enabled and detach (ending any ride) when the last is disabled.
 * Fails soft: a missing Towntopia field or hook leaves rides off with one log line; walks then end at a link as
 * Towntopia ends them (STUCK, re-plan).
 */
public final class RideEngine {
    private static final Logger LOG = LogUtils.getLogger();
    static final String END_OF_ROUTE = "village.FollowWalk#endOfRoute(PathfinderMob,ServerLevel,String)";
    static final String WALK_TICK = "village.FollowWalk#tick(PathfinderMob)";
    static final String NEEDS_RESCUE = "village.VillagerModel#needsWaterRescue()";
    static final String STUCK_LOOP = "village.VillagerModel#noteStuckLoop(NavGraph)";
    static final String UNLOADED = "village.brain.VillagerBrain#onPuppetUnloaded(VillageManager,ServerLevel,NpcEntity)";
    static final String REMOVE_VILLAGER = "village.VillageManager#removeVillager(UUID)";
    /** The entity tag (in NeoForge's persistent data) that marks a villager saved mid-ride: {@code a}, {@code b}. */
    public static final String RIDE_TAG = "townkit:ride";
    /** How long a failed ride keeps its link out of routes: Towntopia's own failed-step time. */
    static final long FAILED_MILLIS = 600_000L;
    /**
     * Game ticks past a ride's timeout after which rescue suppression lapses (counted from the start) and a ride whose
     * walk stopped ticking ends (counted from its last walk tick).
     */
    static final int GRACE_TICKS = 100;
    static final String CLOSE_DOORS = "village.FollowWalk#closeAllOpenedDoors(PathfinderMob)";
    /**
     * Game ticks without a walk tick after which a ride whose walk isn't a village model's active move, and whose owner
     * let the walk go, ends. Towntopia ticks a walking traveler's or guard's walk every tick it ticks it at all;
     * the longest gap it tolerates and then resumes is {@code TravelerGraphWalk.RESUME_GAP_TICKS} (20); plus 20 margin.
     * Public for the GameTests.
     */
    public static final int DROPPED_WALK_TICKS = 40;
    private static final byte LINK_CUT = 4;

    private static final FieldReader<Byte> CUT_KIND = FieldReader.of(FollowWalk.class, "cutKind", byte.class);
    private static final FieldReader<NavNode> CUT_FROM = FieldReader.of(FollowWalk.class, "cutFrom", NavNode.class);
    private static final FieldReader<NavNode> CUT_TO = FieldReader.of(FollowWalk.class, "cutTo", NavNode.class);

    /** One running ride (server thread). */
    private static final class Riding {
        final FollowWalk walker;
        /** The rider's village model; null for an NPC no village holds (a traveler). */
        @Nullable
        final VillagerModel model;
        /** Whether the walk was the model's active move when the ride started (not a guard's chase walk, say). */
        final boolean activeMove;
        final NpcEntity body;
        final ServerLevel level;
        final CoreRideContext ctx;
        @Nullable
        final Ride ride;
        final int timeout;
        final long startedAt;
        /** Game time of the last walk tick that ticked the ride (the start before the first). */
        long lastWalkTick;
        /**
         * Whether the walk's owner let it go mid-ride: Towntopia calls {@code closeAllOpenedDoors} on a walk exactly when
         * it drops or finishes it (TravelerApproach, GuardFootwork, VillagerModel, RaiderMapWalk), never on one it holds.
         */
        boolean released;

        Riding(FollowWalk walker, @Nullable VillagerModel model, NpcEntity body, ServerLevel level, CoreRideContext ctx,
                @Nullable Ride ride, int timeout) {
            this.walker = walker;
            this.model = model;
            this.activeMove = model != null && model.activeMove() == walker;
            this.body = body;
            this.level = level;
            this.ctx = ctx;
            this.ride = ride;
            this.timeout = timeout;
            this.startedAt = level.getGameTime();
            this.lastWalkTick = this.startedAt;
        }
    }

    private static final Map<UUID, Riding> RIDES = new HashMap<>();
    private static final List<Hooks.Entry> ATTACHED = new ArrayList<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private RideEngine() {
    }

    /** Whether the villager {@code npcId} is riding now (server thread). */
    public static boolean riding(UUID npcId) {
        return RIDES.containsKey(npcId);
    }

    /** How many rides are running now (server thread). */
    public static int activeRides() {
        return RIDES.size();
    }

    // ---- attach / detach ----

    /** Attaches the hooks, once (the first enabled kind). */
    static synchronized void attach() {
        if (!ATTACHED.isEmpty()) {
            return;
        }
        if (!CUT_KIND.available() || !CUT_FROM.available() || !CUT_TO.available()) {
            warnOnce("fields", "TownKit transport: FollowWalk's route-cut fields are missing; villagers don't ride links "
                    + "(their walks end there as Towntopia ends them)", null);
            return;
        }
        List<Hooks.Entry> added = new ArrayList<>();
        if (add(added, END_OF_ROUTE, false, RideEngine::beforeEndOfRoute)
                && add(added, WALK_TICK, false, RideEngine::beforeWalkTick)
                && add(added, NEEDS_RESCUE, true, RideEngine::afterNeedsRescue)
                && add(added, STUCK_LOOP, false, RideEngine::beforeStuckLoop)
                && add(added, UNLOADED, false, RideEngine::beforeUnloaded)
                && add(added, REMOVE_VILLAGER, false, RideEngine::beforeRemoved)) {
            // optional: without it a dropped walk's ride ends through the held-walk check, as in 0.6.0
            HookInfo close = HookManifest.get().byKey.get(CLOSE_DOORS);
            if (close != null) {
                added.add(Hooks.add(close, "townkit", RideEngine::beforeCloseDoors, false));
            } else {
                warnOnce("hook-close", "TownKit transport: no hook {}; a ride whose walk is dropped ends only after its "
                        + "timeout", CLOSE_DOORS);
            }
            ATTACHED.addAll(added);
        } else {
            added.forEach(Hooks::remove);
        }
    }

    private static boolean add(List<Hooks.Entry> added, String key, boolean after, HookListener listener) {
        HookInfo h = HookManifest.get().byKey.get(key);
        if (h == null) {
            warnOnce("hook", "TownKit transport: no hook {}; villagers don't ride links (their walks end there as "
                    + "Towntopia ends them)", key);
            return false;
        }
        added.add(Hooks.add(h, "townkit", listener, after));
        return true;
    }

    /** Detaches the hooks when no kind is enabled any more; running rides end first (placed on a stop). */
    static void detachIfUnused() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) {
            server.execute(RideEngine::detachIfUnused);
            return;
        }
        synchronized (RideEngine.class) {
            if (ATTACHED.isEmpty() || TransportKinds.GLOBAL.anyEnabled()) {
                return;
            }
            for (Riding r : List.copyOf(RIDES.values())) {
                endSafely(r);
            }
            ATTACHED.forEach(Hooks::remove);
            ATTACHED.clear();
        }
    }

    // ---- hooks ----

    /** Before {@code FollowWalk#endOfRoute(PathfinderMob, ServerLevel, String)}. */
    private static void beforeEndOfRoute(HookCall call) {
        if (!(call.self() instanceof FollowWalk walker) || !(call.args()[0] instanceof NpcEntity npc)
                || !(call.args()[1] instanceof ServerLevel level)) {
            return;
        }
        Riding had = RIDES.get(npc.getNpcId());
        if (had != null) {
            if (had.walker == walker) {
                call.cancelWith(MoveToCommand.Status.RUNNING);
                return;
            }
            // left over from another walk: it can't go on
            endSafely(had);
        }
        try {
            if (CUT_KIND.read(walker) != LINK_CUT) {
                return;
            }
            NavNode from = CUT_FROM.read(walker);
            NavNode to = CUT_TO.read(walker);
            if (from == null || to == null) {
                return;
            }
            // any Towntopia NPC whose walk is being ticked rides: a member's own walk, a guard's chase walk, a
            // traveler (no village model). The link's village is the one whose route map holds the pair.
            LinkInstaller.Rideable found = LinkInstaller.rideableIn(level.dimension(), from.feetPos(), to.feetPos(),
                    System.currentTimeMillis());
            VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
            VillageManager mgr = found == null ? null : registry.get(found.villageId());
            TransportKind kind = mgr == null ? null : TransportKinds.GLOBAL.live(found.link().kind());
            if (kind == null) {
                return;
            }
            start(walker, modelOf(npc, registry, mgr), npc, level, mgr, found.link(), kind);
            call.cancelWith(MoveToCommand.Status.RUNNING);
        } catch (RuntimeException | LinkageError e) {
            warnOnce("start", "TownKit transport: starting a ride failed; that walk ends as Towntopia ends it (logged "
                    + "once)", e);
        }
    }

    /** {@code npc}'s village model (its own village first, then the link's), or null when no village holds it. */
    @Nullable
    private static VillagerModel modelOf(NpcEntity npc, VillageManagerRegistry registry, VillageManager linkVillage) {
        var own = npc.getVillageLink();
        VillageManager mgr = own == null ? null : registry.get(own.villageId());
        VillagerModel model = mgr == null ? null : mgr.getModel(npc.getNpcId());
        if (model == null) {
            model = linkVillage.getModel(npc.getNpcId());
        }
        return model != null && model.puppet() == npc ? model : null;
    }

    private static void start(FollowWalk walker, @Nullable VillagerModel model, NpcEntity npc, ServerLevel level,
            VillageManager mgr, TransportLink link, TransportKind kind) {
        CoreRideContext ctx = new CoreRideContext(VillagerHandle.of(npc), npc, level, link, mgr);
        Ride ride = null;
        int timeout = 0;
        try {
            ride = kind.ride(ctx);
            timeout = ride == null ? 0 : ride.timeoutTicks();
        } catch (RuntimeException e) {
            warnOnce("ride:" + kind.id(), "TownKit transport: kind {} threw making or timing a ride; its rides fail "
                    + "(logged once)", kind.id(), e);
            ride = null;
        }
        CompoundTag mark = new CompoundTag();
        mark.putLong("a", link.from().asLong());
        mark.putLong("b", link.to().asLong());
        mark.putString("dim", level.dimension().location().toString());
        npc.getPersistentData().put(RIDE_TAG, mark);
        RIDES.put(npc.getNpcId(), new Riding(walker, model, npc, level, ctx, ride, timeout));
    }

    /** Before {@code FollowWalk#tick(PathfinderMob)}: a rider's walk is held while its ride ticks. */
    private static void beforeWalkTick(HookCall call) {
        if (RIDES.isEmpty() || !(call.args()[0] instanceof NpcEntity npc)) {
            return;
        }
        Riding r = RIDES.get(npc.getNpcId());
        if (r == null) {
            return;
        }
        if (r.walker != call.self()) {
            // another walk of this villager took over
            endSafely(r);
            return;
        }
        call.cancelWith(step(r));
    }

    /**
     * One ride tick: RUNNING while it rides, REPLAN once it is over (on B, or on a safe stop after a failure). A ride
     * whose link is no longer rideable (a stop's frame broken or emptied, or not available at the last refresh) fails
     * without ticking.
     */
    private static MoveToCommand.Status step(Riding r) {
        r.lastWalkTick = r.level.getGameTime();
        r.released = false; // a walk that ticks the ride is not dropped
        RideStatus status = RideStatus.FAILED;
        if (r.ride != null && r.ctx.ticks < r.timeout
                && LinkInstaller.stillRideable(r.ctx.manager().getVillageId(), r.ctx.link())) {
            try {
                status = r.ride.tick(r.ctx);
            } catch (RuntimeException e) {
                warnOnce("tick:" + r.ctx.link().kind(), "TownKit transport: a {} ride threw; it failed and its villager "
                        + "was put on a stop (logged once)", r.ctx.link().kind(), e);
                status = RideStatus.FAILED;
            }
            if (status == null) {
                status = RideStatus.FAILED;
            }
        }
        r.ctx.ticks++;
        if (status == RideStatus.RUNNING) {
            return MoveToCommand.Status.RUNNING;
        }
        if (status == RideStatus.DONE) {
            r.ctx.placeAt(r.ctx.link().to());
            end(r, true);
        } else {
            fallBack(r);
            markFailed(r);
            end(r, false);
        }
        return MoveToCommand.Status.REPLAN;
    }

    /** After {@code VillagerModel#needsWaterRescue()}: false for a rider, until the ride's time is well over. */
    private static void afterNeedsRescue(HookCall call) {
        if (RIDES.isEmpty() || !(call.self() instanceof VillagerModel m) || !Boolean.TRUE.equals(call.returnValue())) {
            return;
        }
        Riding r = RIDES.get(m.npcId);
        if (r != null && r.model == m && r.level.getGameTime() - r.startedAt <= (long) r.timeout + GRACE_TICKS) {
            call.setReturnValue(false);
        }
    }

    /** Before {@code VillagerModel#noteStuckLoop(NavGraph)}: no stuck-loop bookkeeping for a rider. */
    private static void beforeStuckLoop(HookCall call) {
        if (!RIDES.isEmpty() && call.self() instanceof VillagerModel m && RIDES.containsKey(m.npcId)) {
            call.cancel();
        }
    }

    /** Before {@code VillagerBrain#onPuppetUnloaded(VillageManager, ServerLevel, NpcEntity)}. */
    private static void beforeUnloaded(HookCall call) {
        if (!RIDES.isEmpty() && call.args()[2] instanceof NpcEntity npc) {
            Riding r = RIDES.get(npc.getNpcId());
            if (r != null) {
                endSafely(r);
            }
        }
    }

    /** Before {@code VillageManager#removeVillager(UUID)}. */
    private static void beforeRemoved(HookCall call) {
        if (!RIDES.isEmpty() && call.args()[0] instanceof UUID id) {
            Riding r = RIDES.get(id);
            if (r != null) {
                endSafely(r);
            }
        }
    }

    /**
     * Before {@code FollowWalk#closeAllOpenedDoors(PathfinderMob)}: the owner of a rider's walk let it go (dropped or
     * replaced it). Only marks the ride; the sweep ends it once the walk is also overdue.
     */
    private static void beforeCloseDoors(HookCall call) {
        if (!RIDES.isEmpty() && call.args()[0] instanceof NpcEntity npc) {
            Riding r = RIDES.get(npc.getNpcId());
            if (r != null && r.walker == call.self()) {
                r.released = true;
            }
        }
    }

    /**
     * Whether a ride's walk was dropped, not held: it isn't a village model's active move (those end through the
     * model checks), its owner let it go ({@code !held}), and it hasn't ticked for more than {@link #DROPPED_WALK_TICKS}.
     */
    static boolean walkDropped(long now, long lastWalkTick, boolean activeMove, boolean held) {
        return !activeMove && !held && now - lastWalkTick > DROPPED_WALK_TICKS;
    }

    // ---- per level tick and entity join (from MarkerFrames' listeners) ----

    /**
     * Server thread, after each level tick: ends the rides in {@code level} whose villager is gone, whose walk is no
     * longer its active move, whose walk (not an active move) was let go by its owner and hasn't ticked for
     * {@link #DROPPED_WALK_TICKS}, or whose walk hasn't ticked for {@code timeoutTicks + 100} game ticks (Towntopia held
     * it). None of these is the link's fault, so no failure mark.
     */
    static void tickLevel(ServerLevel level) {
        if (RIDES.isEmpty()) {
            return;
        }
        try {
            for (Riding r : List.copyOf(RIDES.values())) {
                if (r.level != level) {
                    continue;
                }
                // the model checks only where there is a model; a walk that wasn't the model's active move to begin
                // with (a guard's chase walk) or has no model (a traveler) ends through the dropped-walk check (its
                // owner let it go) or, held, the held-walk check
                boolean modelLeft = r.model != null && (r.model.puppet() != r.body
                        || r.activeMove && r.model.activeMove() != r.walker);
                long now = level.getGameTime();
                if (r.body.isRemoved() || modelLeft || walkDropped(now, r.lastWalkTick, r.activeMove, !r.released)
                        || now - r.lastWalkTick > (long) r.timeout + GRACE_TICKS) {
                    endSafely(r);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            warnOnce("sweep", "TownKit transport: checking running rides failed (logged once)", e);
        }
    }

    /**
     * An entity joined {@code level}: a villager saved mid-ride loses its ride mark and, when it joins the dimension it
     * rode in, is put on a stop (at Towntopia's {@code standY} when its village's route map has the tile).
     */
    static void onJoin(NpcEntity npc, ServerLevel level) {
        CompoundTag data = npc.getPersistentData();
        if (!data.contains(RIDE_TAG)) {
            return;
        }
        CompoundTag mark = data.getCompound(RIDE_TAG);
        data.remove(RIDE_TAG);
        Riding r = RIDES.get(npc.getNpcId());
        if (r != null && r.body != npc) {
            endSafely(r);
        }
        try {
            if (!level.dimension().location().toString().equals(mark.getString("dim"))) {
                return;
            }
            BlockPos a = BlockPos.of(mark.getLong("a"));
            BlockPos b = BlockPos.of(mark.getLong("b"));
            BlockPos feet = safe(level, b) ? b : a;
            var village = npc.getVillageLink();
            VillageManager mgr = village == null ? null : VillageManagerRegistry.get(level.getServer()).get(village.villageId());
            npc.moveTo(feet.getX() + 0.5, CoreRideContext.standY(mgr, feet), feet.getZ() + 0.5);
            npc.setDeltaMovement(Vec3.ZERO);
        } catch (RuntimeException | LinkageError e) {
            warnOnce("join", "TownKit transport: putting a villager saved mid-ride back on a stop failed (logged once)", e);
        }
    }

    /**
     * The server stopped (server thread): every ride is dropped and told {@code end(ctx, false)}; nothing is placed
     * (the world is saved already). Nothing of this server's rides may reach the next world loaded in this game.
     */
    static void serverStopped() {
        List<Riding> all = List.copyOf(RIDES.values());
        RIDES.clear();
        for (Riding r : all) {
            if (r.ride == null) {
                continue;
            }
            try {
                r.ride.end(r.ctx, false);
            } catch (RuntimeException | LinkageError e) {
                warnOnce("end:" + r.ctx.link().kind(), "TownKit transport: a {} ride threw in end() (logged once)",
                        r.ctx.link().kind(), e);
            }
        }
    }

    // ---- ending ----

    /** Ends {@code r} without blaming the link: placed on B if safe, else A (a removed body stays as it is). */
    private static void endSafely(Riding r) {
        fallBack(r);
        end(r, false);
    }

    private static void fallBack(Riding r) {
        if (r.body.isRemoved()) {
            return;
        }
        try {
            TransportLink l = r.ctx.link();
            r.ctx.placeAt(safe(r.level, l.to()) ? l.to() : l.from());
        } catch (RuntimeException | LinkageError e) {
            warnOnce("fallback", "TownKit transport: putting a rider back on a stop failed (logged once)", e);
        }
    }

    /** Dry feet and head, no fluid under the feet, in a loaded chunk. */
    static boolean safe(ServerLevel level, BlockPos feet) {
        return level.hasChunkAt(feet) && NavGraph.isDryWalkable(level, feet);
    }

    /** The step A -> B failed: in Towntopia's current map and in TownKit's link table. */
    private static void markFailed(Riding r) {
        TransportLink l = r.ctx.link();
        try {
            NavGraph graph = r.ctx.manager().getGraph();
            NavNode a = graph == null ? null : graph.nodeAt(l.from().getX(), l.from().getY(), l.from().getZ());
            NavNode b = graph == null ? null : graph.nodeAt(l.to().getX(), l.to().getY(), l.to().getZ());
            if (a != null && b != null) {
                graph.markStepFailed(a, b, FAILED_MILLIS);
            }
        } catch (RuntimeException | LinkageError e) {
            warnOnce("mark", "TownKit transport: marking a failed ride's step in the route map failed (logged once)", e);
        }
        LinkInstaller.markFailed(r.ctx.manager().getVillageId(), l, System.currentTimeMillis() + FAILED_MILLIS);
    }

    /** Drops {@code r}'s state, then tells the ride it is over. */
    private static void end(Riding r, boolean completed) {
        RIDES.remove(r.body.getNpcId(), r);
        r.body.getPersistentData().remove(RIDE_TAG);
        if (r.ride == null) {
            return;
        }
        try {
            r.ride.end(r.ctx, completed);
        } catch (RuntimeException e) {
            warnOnce("end:" + r.ctx.link().kind(), "TownKit transport: a {} ride threw in end() (logged once)",
                    r.ctx.link().kind(), e);
        }
    }

    private static void warnOnce(String key, String msg, @Nullable Object a) {
        if (WARNED.add(key)) {
            LOG.warn(msg, a);
        }
    }

    private static void warnOnce(String key, String msg, Object a, Object b) {
        if (WARNED.add(key)) {
            LOG.warn(msg, a, b);
        }
    }
}
