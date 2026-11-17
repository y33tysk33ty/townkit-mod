package townkit.core.building;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import townkit.api.event.TownKitEvents;
import townkit.api.event.TownKitEvents.AddedHow;
import townkit.api.event.TypedEvent;
import townkit.api.hooks.HookCall;
import townkit.core.FieldReader;
import townkit.core.TownKit;
import townkit.core.event.DirectEvents;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.village.VillageHandle;

/**
 * The Towntopia hooks behind {@code BUILDING_ADDED}, {@code BUILDING_REMOVED}, {@code BUILDING_VALIDITY_CHANGED} and
 * {@code BUILDING_BLOCKS_CHANGED}. Each event attaches its hooks with its first listener and drops them with its last
 * ({@link DirectEvents.Lifecycle}); with no listener nothing is hooked, so Towntopia runs untouched.
 *
 * <p>How the events are found (Towntopia 0.1.2):
 * <ul>
 *   <li><b>Added</b>: around every method that puts into {@code VillageBuildings.buildings} ({@link #SOURCES}), the
 *       map's key set before and after. The "how" is the outermost such method on the stack for that village (a scan
 *       that ends in {@code tryCreateBuilding} is SCANNED, not PLACED).</li>
 *   <li><b>Removed</b>: {@code VillageBuildings.removeBuilding}, the building looked up before and gone after; and
 *       {@code clearTownHallsForRecovery} (a moved town hall token), every building it dropped. Not
 *       {@code clear()}: that only tears down a village (a failed founding, an unload).</li>
 *   <li>ADDED/REMOVED of a village the registry doesn't know yet (founding, save load) are held until it does, and
 *       dropped if it never does (failed founding); see {@link #HELD}. So a handler always sees an existing village.
 *       Towntopia's legacy {@code VillageManager.INSTANCE} is never registered: its building events are dropped.</li>
 *   <li><b>Validity / working blocks</b>: {@code Building.isValid()} and the read API's working positions captured
 *       the first time a building is touched by {@code validate}, {@code rebuild} or {@code setAccessValid} (the only
 *       writers of {@code isValid}/{@code accessValid}, and {@code validate}/{@code rebuild} the only callers of
 *       {@code scanFunctionalBlocks}), compared once the outermost hooked call ends. Methods that rebuild and then
 *       re-check access ({@link #STATE_SCOPES}) are hooked too, so the two steps compare as one. Buildings not
 *       registered when first touched (being created or restored) are left to {@code BUILDING_ADDED}.</li>
 * </ul>
 * Nested hooked calls don't fire twice: per thread, a stack of open calls; events go out when the stack empties. A
 * Towntopia call that throws skips its after-hook; its stale stack is dropped at the next hooked call in a later tick
 * (or after a listener change). Server thread only: calls on other threads are ignored.
 */
public final class BuildingEventHooks {
    private BuildingEventHooks() {
    }

    private static final String VB = "village.VillageBuildings#";
    private static final String B = "village.building.Building#";

    /** Every method with a {@code this.buildings.put} in 0.1.2, and how a building it adds came. */
    static final Map<String, AddedHow> SOURCES = sources();

    private static Map<String, AddedHow> sources() {
        Map<String, AddedHow> m = new LinkedHashMap<>();
        // restoreSavedBuildings and registerLoadedTownHall restore through this
        m.put(VB + "installRestoredBuilding(ServerLevel,UUID,Building,ItemFrame)", AddedHow.RESTORED);
        // a token placed in a frame (processPendingFrameChecks) or a failed token retried (tickFailedTokens)
        m.put(VB + "tryCreateBuilding(ServerLevel,ItemFrame)", AddedHow.PLACED);
        m.put(VB + "tryCreateOutcrop(ServerLevel,ItemFrame,BlockPos,Direction)", AddedHow.PLACED);
        m.put(VB + "tryCreateWatchtower(ServerLevel,ItemFrame,Direction)", AddedHow.PLACED);
        m.put(VB + "tryCreateOpenStall(ServerLevel,ItemFrame,BlockPos,Direction)", AddedHow.PLACED);
        m.put(VB + "tryCreateEnclosure(ServerLevel,ItemFrame,BlockPos,Direction)", AddedHow.PLACED);
        // the existing-buildings scan (rooms, enclosures, outcrops, stalls, towers); outermost wins over PLACED
        m.put(VB + "tryCreateBuildingFromScan(ServerLevel,ItemFrame)", AddedHow.SCANNED);
        m.put(VB + "tryAutoRegisterRoom(ServerLevel,BlockPos)", AddedHow.SCANNED);
        m.put(VB + "tryAutoRegisterEnclosure(ServerLevel,BlockPos)", AddedHow.SCANNED);
        m.put(VB + "scanExistingOutcrops(ServerLevel)", AddedHow.SCANNED);
        m.put(VB + "scanExistingOpenStalls(ServerLevel)", AddedHow.SCANNED);
        return java.util.Collections.unmodifiableMap(m);
    }

    static final String REMOVE = VB + "removeBuilding(UUID,ServerLevel,String,Component,ItemFrame)";
    /** A moved town hall token ({@code restoreOrphan}): drops the old town hall(s) straight from the map. */
    static final String CLEAR_TOWN_HALLS = VB + "clearTownHallsForRecovery()";
    static final String VALIDATE = B + "validate(ServerLevel)";
    static final String REBUILD = B + "rebuild(ServerLevel,NavGraph,Collection)";
    static final String SET_ACCESS = B + "setAccessValid(boolean)";
    /** Rebuild, then set access: compared once at their end so a building never flaps within one call. */
    static final List<String> STATE_SCOPES = List.of(VB + "retryTownHall(ServerLevel,UUID)",
            VB + "rebuildOne(ServerLevel,UUID,Building,boolean,boolean)");

    private enum Kind {
        ADDED, REMOVED, VALIDITY, BLOCKS;

        final int bit = 1 << ordinal();
    }

    private enum Role { SOURCE, REMOVE, STATE, SCOPE }

    private static final Map<Kind, Set<String>> KEYS = keysByKind();
    private static final Map<String, Role> ROLES = roles();

    private static Map<Kind, Set<String>> keysByKind() {
        Map<Kind, Set<String>> m = new EnumMap<>(Kind.class);
        m.put(Kind.ADDED, Set.copyOf(SOURCES.keySet()));
        m.put(Kind.REMOVED, Set.of(REMOVE, CLEAR_TOWN_HALLS));
        Set<String> validity = new LinkedHashSet<>(List.of(VALIDATE, REBUILD, SET_ACCESS));
        validity.addAll(STATE_SCOPES);
        m.put(Kind.VALIDITY, Set.copyOf(validity));
        Set<String> blocks = new LinkedHashSet<>(List.of(VALIDATE, REBUILD)); // setAccessValid never changes blocks
        blocks.addAll(STATE_SCOPES);
        m.put(Kind.BLOCKS, Set.copyOf(blocks));
        return m;
    }

    private static Map<String, Role> roles() {
        Map<String, Role> m = new HashMap<>();
        for (String k : SOURCES.keySet()) m.put(k, Role.SOURCE);
        m.put(REMOVE, Role.REMOVE);
        m.put(CLEAR_TOWN_HALLS, Role.REMOVE);
        for (String k : List.of(VALIDATE, REBUILD, SET_ACCESS)) m.put(k, Role.STATE);
        for (String k : STATE_SCOPES) m.put(k, Role.SCOPE);
        return m;
    }

    private static Kind kind(TypedEvent<?> event) {
        if (event == TownKitEvents.BUILDING_ADDED) return Kind.ADDED;
        if (event == TownKitEvents.BUILDING_REMOVED) return Kind.REMOVED;
        if (event == TownKitEvents.BUILDING_VALIDITY_CHANGED) return Kind.VALIDITY;
        if (event == TownKitEvents.BUILDING_BLOCKS_CHANGED) return Kind.BLOCKS;
        return null;
    }

    /** The Towntopia hooks an event attaches while it has listeners (empty for any other event). */
    public static Set<String> keys(TypedEvent<?> event) {
        Kind k = kind(event);
        return k == null ? Set.of() : KEYS.get(k);
    }

    /** For {@link DirectEvents}: each building event's attach/detach. */
    public static Map<TypedEvent<?>, DirectEvents.Lifecycle> lifecycles() {
        return Map.of(TownKitEvents.BUILDING_ADDED, lifecycle(Kind.ADDED),
                TownKitEvents.BUILDING_REMOVED, lifecycle(Kind.REMOVED),
                TownKitEvents.BUILDING_VALIDITY_CHANGED, lifecycle(Kind.VALIDITY),
                TownKitEvents.BUILDING_BLOCKS_CHANGED, lifecycle(Kind.BLOCKS));
    }

    private static DirectEvents.Lifecycle lifecycle(Kind k) {
        return new DirectEvents.Lifecycle() {
            @Override
            public boolean available() {
                return !RewrittenSites.GLOBAL.hooksOff() && BuildingEventHooks.available(k);
            }

            @Override
            public void attach() {
                BuildingEventHooks.attach(k);
            }

            @Override
            public void detach() {
                BuildingEventHooks.detach(k);
            }
        };
    }

    // ---- Towntopia fields (fail-soft, loaded on first use) ----------------------------------------------------------

    private static final class Fields {
        @SuppressWarnings({"unchecked", "rawtypes"})
        static final FieldReader<Map<Object, Object>> BUILDINGS = (FieldReader) FieldReader.of(VillageManager.class,
                "com.alex.test.village.VillageBuildings", "buildings", Map.class);
        static final FieldReader<VillageManager> VILLAGE = FieldReader.of(VillageManager.class,
                "com.alex.test.village.VillageBuildings", "village", VillageManager.class);
    }

    private static final Map<Kind, Boolean> AVAILABLE = new EnumMap<>(Kind.class);

    private static synchronized boolean available(Kind k) {
        return AVAILABLE.computeIfAbsent(k, x -> {
            List<String> missing = new ArrayList<>();
            for (String key : KEYS.get(x)) {
                if (!HookManifest.get().byKey.containsKey(key)) missing.add(key);
            }
            boolean fields = (x != Kind.ADDED && x != Kind.REMOVED)
                    || (Fields.BUILDINGS.available() && Fields.VILLAGE.available());
            if (!missing.isEmpty() || !fields) {
                TownKit.LOG.warn("[TownKit] building event {} is unavailable on this Towntopia (missing hooks {}, fields {})",
                        x, missing, fields ? "ok" : "VillageBuildings.buildings/village missing");
                return false;
            }
            return true;
        });
    }

    // ---- attach / detach --------------------------------------------------------------------------------------------

    /** Bits of {@link Kind} with listeners. Read on every hooked call. */
    private static volatile int active;
    /** Bumped on every attach/detach: a call stack opened before it is dropped. */
    private static volatile int generation;
    /** Hook key to its {before, after} registrations, while attached. */
    private static final Map<String, Hooks.Entry[]> ATTACHED = new HashMap<>();

    private static synchronized void attach(Kind k) {
        if ((active & k.bit) != 0 || !available(k)) return;
        active |= k.bit;
        generation++;
        for (String key : KEYS.get(k)) {
            if (ATTACHED.containsKey(key)) continue;
            HookInfo h = HookManifest.get().byKey.get(key);
            Role role = ROLES.get(key);
            Hooks.Entry before = Hooks.add(h, TownKit.MODID, call -> enter(role, key, call), false);
            Hooks.Entry after = Hooks.add(h, TownKit.MODID, call -> exit(role, key, call), true);
            ATTACHED.put(key, new Hooks.Entry[] {before, after});
        }
    }

    private static synchronized void detach(Kind k) {
        if ((active & k.bit) == 0) return;
        active &= ~k.bit;
        generation++;
        Set<String> needed = new HashSet<>();
        for (Kind o : Kind.values()) {
            if ((active & o.bit) != 0) needed.addAll(KEYS.get(o));
        }
        for (var it = ATTACHED.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (needed.contains(e.getKey())) continue;
            for (Hooks.Entry en : e.getValue()) Hooks.remove(en);
            it.remove();
        }
    }

    // ---- per-thread call stack --------------------------------------------------------------------------------------

    private static final class Frame {
        final String key;
        final Object self;
        final Role role;
        /** SOURCE, outermost for its village only: the frame ids before. */
        @Nullable
        Set<Object> keysBefore;
        @Nullable
        AddedHow how;
        /** REMOVE: the building(s) that were there before. */
        @Nullable
        List<Building> removing;

        Frame(String key, Object self, Role role) {
            this.key = key;
            this.self = self;
            this.role = role;
        }
    }

    /** A building's state when first touched in a call stack; {@code mgr} null = not registered then (ignored). */
    private record Before(@Nullable VillageManager mgr, boolean valid, @Nullable Set<BlockPos> working) {
    }

    private record Added(Object buildings, UUID frameId, AddedHow how) {
    }

    private record Removed(@Nullable VillageManager mgr, Building building) {
    }

    private static final class Scope {
        final ArrayList<Frame> frames = new ArrayList<>(4);
        int generation;
        int tick;
        final List<Added> added = new ArrayList<>(2);
        final List<Removed> removed = new ArrayList<>(2);
        final IdentityHashMap<Building, Before> captured = new IdentityHashMap<>();

        void reset() {
            this.frames.clear();
            this.added.clear();
            this.removed.clear();
            this.captured.clear();
        }
    }

    private static final ThreadLocal<Scope> SCOPE = ThreadLocal.withInitial(Scope::new);
    private static final AtomicBoolean loggedFailure = new AtomicBoolean();
    private static final AtomicBoolean loggedStale = new AtomicBoolean();

    @Nullable
    private static MinecraftServer serverThread() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread() ? server : null;
    }

    private static void enter(Role role, String key, HookCall call) {
        MinecraftServer server = serverThread();
        if (server == null) return;
        try {
            Scope s = SCOPE.get();
            int gen = generation;
            int tick = server.getTickCount();
            if (!s.frames.isEmpty() && (s.generation != gen || s.tick != tick)) {
                if (s.generation == gen && loggedStale.compareAndSet(false, true)) {
                    TownKit.LOG.warn("[TownKit] a hooked Towntopia building call didn't finish (it threw?): building "
                            + "events from it were dropped");
                }
                s.reset();
            }
            if (s.frames.isEmpty()) {
                s.generation = gen;
                s.tick = tick;
            }
            Object self = call.self();
            Frame f = new Frame(key, self, role);
            int mask = active;
            switch (role) {
                case SOURCE -> {
                    if ((mask & Kind.ADDED.bit) != 0 && !hasSourceFor(s, self)) {
                        Map<Object, Object> map = Fields.BUILDINGS.read(self);
                        f.keysBefore = new HashSet<>(map.keySet());
                        f.how = SOURCES.getOrDefault(key, AddedHow.UNKNOWN);
                    }
                }
                case REMOVE -> {
                    if ((mask & Kind.REMOVED.bit) != 0) {
                        Map<Object, Object> map = Fields.BUILDINGS.read(self);
                        List<Building> before = new ArrayList<>(1);
                        if (key.equals(REMOVE)) {
                            if (map.get(call.arg(0, UUID.class)) instanceof Building b) before.add(b);
                        } else { // clearTownHallsForRecovery: whatever it drops (town halls) shows up on exit
                            for (Object v : map.values()) {
                                if (v instanceof Building b) before.add(b);
                            }
                        }
                        f.removing = before;
                    }
                }
                case STATE -> {
                    if ((mask & (Kind.VALIDITY.bit | Kind.BLOCKS.bit)) != 0 && self instanceof Building b
                            && !s.captured.containsKey(b)) {
                        s.captured.put(b, capture(server, b, mask));
                    }
                }
                case SCOPE -> {
                }
            }
            s.frames.add(f);
        } catch (RuntimeException e) {
            logOnce(e);
            SCOPE.get().reset(); // never let half a call's state leak into the next flush
        }
    }

    private static boolean hasSourceFor(Scope s, Object buildings) {
        for (Frame f : s.frames) {
            if (f.role == Role.SOURCE && f.self == buildings && f.keysBefore != null) return true;
        }
        return false;
    }

    private static Before capture(MinecraftServer server, Building b, int mask) {
        VillageManager m = managerOf(server, b);
        if (m == null) return new Before(null, false, null);
        Set<BlockPos> working = (mask & Kind.BLOCKS.bit) != 0 ? new HashSet<>(BuildingReader.workingPositions(b)) : null;
        return new Before(m, b.isValid(), working);
    }

    /** The village that has this very building registered, or null. */
    @Nullable
    private static VillageManager managerOf(MinecraftServer server, Building b) {
        UUID id = b.getItemFrameId();
        for (VillageManager m : VillageManagerRegistry.get(server).all()) {
            if (m.getBuilding(id) == b) return m;
        }
        return null;
    }

    private static void exit(Role role, String key, HookCall call) {
        MinecraftServer server = serverThread();
        if (server == null) return;
        try {
            Scope s = SCOPE.get();
            Object self = call.self();
            int i = s.frames.size() - 1;
            while (i >= 0 && !(s.frames.get(i).key.equals(key) && s.frames.get(i).self == self)) i--;
            if (i < 0) return; // attached mid-call: no matching before
            Frame f = s.frames.get(i);
            // anything above it never finished (threw): drop it
            s.frames.subList(i, s.frames.size()).clear();
            if (f.role == Role.SOURCE && f.keysBefore != null) {
                for (Object id : Fields.BUILDINGS.read(f.self).keySet()) {
                    if (!f.keysBefore.contains(id) && id instanceof UUID uuid) s.added.add(new Added(f.self, uuid, f.how));
                }
            } else if (f.role == Role.REMOVE && f.removing != null && !f.removing.isEmpty()) {
                Map<Object, Object> map = Fields.BUILDINGS.read(f.self);
                for (Building b : f.removing) {
                    if (map.get(b.getItemFrameId()) != b) s.removed.add(new Removed(Fields.VILLAGE.read(f.self), b));
                }
            }
            if (s.frames.isEmpty()) flush(server, s);
        } catch (RuntimeException e) {
            logOnce(e);
            SCOPE.get().reset(); // never let half a call's state leak into the next flush
        }
    }

    /** The outermost hooked call ended: fire what changed. */
    private static void flush(MinecraftServer server, Scope s) {
        if (s.added.isEmpty() && s.removed.isEmpty() && s.captured.isEmpty()) return;
        List<Added> added = List.copyOf(s.added);
        List<Removed> removed = List.copyOf(s.removed);
        List<Map.Entry<Building, Before>> captured = new ArrayList<>(s.captured.entrySet());
        s.reset(); // a handler that calls back into Towntopia starts a fresh stack
        int mask = active;
        // removals first: a town hall moved in one call (restoreOrphan) never shows two town halls at once
        if ((mask & Kind.REMOVED.bit) != 0) {
            for (Removed r : removed) {
                VillageHandle v = r.mgr() == null ? null : VillageHandle.of(r.mgr());
                if (v == null) continue;
                TownKitEvents.BuildingRemoved event = new TownKitEvents.BuildingRemoved(v, VillageHandle.info(r.building()));
                deliver(server, r.mgr(), () -> DirectEvents.fire(TownKitEvents.BUILDING_REMOVED, event));
            }
        }
        if ((mask & Kind.ADDED.bit) != 0) {
            Set<UUID> done = new HashSet<>();
            for (Added a : added) {
                VillageManager m = Fields.VILLAGE.read(a.buildings());
                Building b = m == null ? null : m.getBuilding(a.frameId());
                VillageHandle v = m == null ? null : VillageHandle.of(m);
                if (b == null || v == null || !done.add(a.frameId())) continue;
                TownKitEvents.BuildingAdded event = new TownKitEvents.BuildingAdded(v, VillageHandle.info(b), a.how());
                deliver(server, m, () -> DirectEvents.fire(TownKitEvents.BUILDING_ADDED, event));
            }
        }
        for (Map.Entry<Building, Before> e : captured) {
            Building b = e.getKey();
            Before before = e.getValue();
            VillageManager m = before.mgr();
            if (m == null || m.getBuilding(b.getItemFrameId()) != b) continue; // new, or removed meanwhile
            boolean valid = b.isValid();
            if ((mask & Kind.VALIDITY.bit) != 0 && valid != before.valid()) {
                VillageHandle v = VillageHandle.of(m);
                if (v != null) {
                    Optional<String> problem = valid ? Optional.empty() : BuildingReader.problem(m, b);
                    TownKitEvents.BuildingValidityChanged event =
                            new TownKitEvents.BuildingValidityChanged(v, VillageHandle.info(b), valid, problem);
                    deliver(server, m, () -> DirectEvents.fire(TownKitEvents.BUILDING_VALIDITY_CHANGED, event));
                }
            }
            if ((mask & Kind.BLOCKS.bit) != 0 && before.working() != null) {
                Set<BlockPos> now = BuildingReader.workingPositions(b);
                Set<BlockPos> plus = new HashSet<>(now);
                plus.removeAll(before.working());
                Set<BlockPos> minus = new HashSet<>(before.working());
                minus.removeAll(now);
                if (!plus.isEmpty() || !minus.isEmpty()) {
                    VillageHandle v = VillageHandle.of(m);
                    if (v != null) {
                        TownKitEvents.BuildingBlocksChanged event = new TownKitEvents.BuildingBlocksChanged(v,
                                VillageHandle.info(b), Set.copyOf(plus), Set.copyOf(minus));
                        deliver(server, m, () -> DirectEvents.fire(TownKitEvents.BUILDING_BLOCKS_CHANGED, event));
                    }
                }
            }
        }
    }

    // ---- events of villages the registry doesn't know yet --------------------------------------------------------

    /**
     * ADDED/REMOVED events of a village that isn't in Towntopia's registry yet. Founding registers the town hall inside
     * {@code VillageManager.initialize}, before {@code VillageManagerRegistry} puts the village (and a failed founding
     * tears the manager down without ever registering it); a save load also initializes before registering. Held
     * events go out, in order, on a server task queued once something is held (no per-tick cost otherwise): delivered
     * if the village is registered by then, dropped if it never was. Server thread only.
     */
    private static final HeldEvents<VillageManager> HELD = new HeldEvents<>();

    /**
     * Fires now, or holds behind earlier held events of the same village / until it is registered. Every building
     * event goes through here, so none overtakes a held one (no validity or blocks event before the ADDED).
     */
    private static void deliver(MinecraftServer server, VillageManager m, Runnable fire) {
        if (!HELD.holds(server, m) && registered(server, m)) {
            fire.run();
            return;
        }
        // tell() always queues (execute() would run it right here on the server thread); runs after this tick.
        // The queue is tied to this server: a new server always gets its own drain (see HeldEvents).
        if (HELD.hold(server, m, fire)) server.tell(new TickTask(server.getTickCount(), () -> drainHeld(server)));
    }

    /**
     * A server is stopping: its queued drain will never run, so forget what it held (and the villages it pins).
     * Registered once at startup by {@link CoreBuildings#install}; it does nothing but clear a list, once per stop.
     */
    static void onServerStopping() {
        HELD.clear();
    }

    private static boolean registered(MinecraftServer server, VillageManager m) {
        return VillageManagerRegistry.get(server).get(m.getVillageId()) == m;
    }

    private static void drainHeld(MinecraftServer server) {
        try {
            int dropped = HELD.drain(server, m -> registered(server, m));
            if (dropped > 0) {
                TownKit.LOG.debug("[TownKit] dropped {} building event(s) of a village that was never created", dropped);
            }
        } catch (RuntimeException e) {
            logOnce(e);
        }
        // anything held during the drain already queued its own drain (HeldEvents.hold)
    }

    private static void logOnce(Throwable t) {
        if (loggedFailure.compareAndSet(false, true)) {
            TownKit.LOG.error("[TownKit] building events: reading Towntopia failed; that call's events were skipped", t);
        }
    }
}
