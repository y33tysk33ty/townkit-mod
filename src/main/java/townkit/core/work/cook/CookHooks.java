package townkit.core.work.cook;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.api.work.StationContext;
import townkit.core.FieldReader;
import townkit.core.TownKit;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.village.VillagerHandle;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkScreens;

/**
 * The hooks the cook overlay needs beside the class-load rewrites ({@code work-sites.tsv}), each attached only while
 * it has something to do, so a world without cook changes runs Towntopia's cook with none of them:
 * <ul>
 *   <li>{@link #FOR_PROFESSION} after, while the dish list is changed: Towntopia's settings registry (the settings
 *   screens, menus and the away villagers read it; it stored the schema once at class init) answers the merged
 *   schema; while a player's settings window is written, the page that player's client builds ({@link WorkScreens});
 *   never on a client's main thread (its screens: {@code WorkScreensHooks}).</li>
 *   <li>{@link #IS_CANDIDATE} / {@link #IS_ORDER_CANDIDATE} after, while a dish has a station: such a dish is a
 *   candidate only while a kitchen of the village holds one of its station's blocks ({@link Stations}).</li>
 *   <li>{@link #DO_TICK} before, once the dish list has been changed in this run: a cook whose current recipe is a
 *   dish recipe the merged list no longer holds (the dish was changed or removed, e.g. by {@code /reload}, mid-batch)
 *   ends its work there, before the batch makes anything. Towntopia takes a batch's ingredients only when the batch
 *   completes, so they stay in the cook's pack, and its {@code doStop} lets go of the station and any stock order.
 *   The same ends a batch whose station block is gone (or whose station was unregistered).</li>
 * </ul>
 * Station dishes (a station dish is a table dish to Towntopia, see {@link CookOverlay}), while one exists:
 * <ul>
 *   <li>{@link #TABLES} after, inside a cook's tick on a station dish: Towntopia's {@code CookActivity#stationFor}
 *   (private) picks the nearest free block of {@code getCraftingTablePositions()}; it gets the station's blocks
 *   instead, so the cook claims, walks to and faces the station block.</li>
 *   <li>{@link #KITCHEN_OF} after: Towntopia finds the cook's stand through the kitchen holding the block
 *   ({@code KitchenBuilding#hasStation} knows only its own furniture); a station block answers its kitchen.</li>
 *   <li>{@link #SCAN} after: whenever Towntopia rescans a kitchen room, its station blocks are rescanned too.</li>
 *   <li>{@link #DO_TICK} after and {@link #DO_STOP} before (kept once a station dish existed in this run, so a batch
 *   that outlives its dish still stops): a batch at a station block starts the station's handler ({@code workTimer}
 *   rose: a new batch's time was set) and stops it when the batch ends, the cook is stopped, or the block is gone.
 *   {@link #onEntityLeave} and {@link #onServerStopping} (events, {@code CoreWork}) stop it when the cook's entity
 *   leaves its level or the server stops. The open batches live in {@link Stations} (one atomic remove each).</li>
 * </ul>
 */
public final class CookHooks {
    static final String FOR_PROFESSION = "village.villagers.settings.SettingsSchemaRegistry#forProfession(Profession)";
    static final String IS_CANDIDATE =
        "village.villagers.cook.CookPlanner#isCandidate(KitchenStations,CookPlanner.Pantry,CookSettings,CookActivity.Recipe,int)";
    static final String IS_ORDER_CANDIDATE =
        "village.villagers.cook.CookPlanner#isOrderCandidate(KitchenStations,CookPlanner.Pantry,CookSettings,CookActivity.Recipe,int)";
    static final String DO_TICK = "village.villagers.cook.behavior.CookActivity#doTick(Activity.Context)";
    static final String DO_STOP = "village.villagers.cook.behavior.CookActivity#doStop(Activity.Context)";
    static final String TABLES = "village.villagers.cook.VillageKitchens#getCraftingTablePositions()";
    static final String KITCHEN_OF = "village.villagers.cook.VillageKitchens#kitchenOf(BlockPos)";
    static final String SCAN = "village.building.KitchenBuilding#scanFunctionalBlocks(ServerLevel)";

    /** Attached hooks by key and side ("key|after"). */
    private static final Map<String, Hooks.Entry> ATTACHED = new LinkedHashMap<>();
    /** Set once the dish list has been changed in this run: a cook may hold a recipe from before a change from then on. */
    private static boolean everChanged;
    /** Set once a station dish existed in this run: a station batch may be running from then on. */
    private static volatile boolean everStations;
    /** While station dishes exist. */
    private static volatile boolean stationsOn;
    @Nullable
    private static FieldReader<CookActivity.Recipe> recipeField;
    @Nullable
    private static FieldReader<Integer> workTimerField;
    @Nullable
    private static FieldReader<BlockPos> stationField;

    /** The cook whose {@code doTick} runs on this thread, from the before-hook to the after-hook. */
    private static final class Tick {
        @Nullable
        CookActivity cook;
        @Nullable
        Activity.Context ctx;
        int timerBefore;
    }

    private static final ThreadLocal<Tick> TICK = ThreadLocal.withInitial(Tick::new);

    // Running station batches are kept by Stations.GLOBAL, keyed by the cook's activity.

    private CookHooks() {
    }

    /** Attaches what the overlay needs now and detaches the rest. Call after any change of the dish list. */
    public static synchronized void refresh() {
        CookOverlay o = CookOverlay.GLOBAL;
        boolean changed = o.touched();
        if (changed) everChanged = true;
        boolean stations = changed && o.hasStationDishes();
        if (stations) everStations = true;
        stationsOn = stations;
        set(FOR_PROFESSION, true, changed, CookHooks::afterForProfession);
        set(IS_CANDIDATE, true, stations, CookHooks::afterCandidate);
        set(IS_ORDER_CANDIDATE, true, stations, CookHooks::afterCandidate);
        set(DO_TICK, false, everChanged, CookHooks::beforeDoTick);
        set(DO_TICK, true, everStations, CookHooks::afterDoTick);
        set(DO_STOP, false, everStations, CookHooks::beforeDoStop);
        set(TABLES, true, stations, CookHooks::afterTables);
        set(KITCHEN_OF, true, stations, CookHooks::afterKitchenOf);
        set(SCAN, true, stations, CookHooks::afterScan);
        WorkScreens.refresh();
    }

    /** The hook keys attached right now (for tests). */
    public static synchronized List<String> attachedKeys() {
        List<String> out = new ArrayList<>();
        for (Hooks.Entry e : ATTACHED.values()) out.add(e.hook.key());
        return out;
    }

    private static void set(String key, boolean after, boolean wanted, HookListener listener) {
        String slot = key + "|" + after;
        Hooks.Entry e = ATTACHED.get(slot);
        if (wanted && e == null) {
            HookInfo h = HookManifest.get().byKey.get(key);
            if (h == null) {
                WorkLog.GLOBAL.once("cook-hook|" + key, "[" + CookOverlay.KIND + "] can't find the hook " + key
                    + "; that part of the dish overlay is off");
                return;
            }
            ATTACHED.put(slot, Hooks.add(h, TownKit.MODID, listener, after));
        } else if (!wanted && e != null) {
            Hooks.remove(e);
            ATTACHED.remove(slot);
        }
    }

    private static void afterForProfession(HookCall call) {
        // a client's own screens (its main thread) show the rows its server sent: WorkScreensHooks answers there
        if (WorkScreens.onClientThread()) return;
        if (call.returnValue() instanceof Optional<?> o && o.isPresent() && o.get() instanceof SettingsSchema s
                && s.profession() == Profession.COOK) {
            // a settings window being written for a player: the page that player's client builds
            SettingsSchema page = WorkScreens.writing();
            SettingsSchema merged = page != null ? page : CookOverlay.GLOBAL.schemaFor(s);
            if (merged != s) call.setReturnValue(Optional.of(merged));
        }
    }

    /**
     * A station dish is a candidate only while a kitchen of the village holds one of its station's blocks. Fails closed:
     * if the check itself fails, the station dish isn't a candidate (and the hook never throws, so it is never switched
     * off for failing, which would let station dishes through everywhere).
     */
    private static void afterCandidate(HookCall call) {
        if (!Boolean.TRUE.equals(call.returnValue()) || !(call.args()[3] instanceof CookActivity.Recipe r)) return;
        try {
            ResourceLocation station = CookOverlay.GLOBAL.stationOf(r.result());
            if (station == null) return;
            List<KitchenBuilding> kitchens = KitchenAccess.kitchens(call.args()[0]);
            Activity.Context ctx = TICK.get().ctx; // null outside a cook's tick (the away catch-up): last scans only
            boolean ok = ctx != null ? !livePositions(ctx.level(), kitchens, station).isEmpty() : Stations.GLOBAL.candidate(station, kitchens);
            if (!ok) call.setReturnValue(false);
        } catch (RuntimeException e) {
            WorkLog.GLOBAL.once("cook-candidate|" + e.getClass().getName(), "[" + CookOverlay.KIND + "] checking a station dish's "
                + "kitchens failed (" + e + "); station dishes aren't made until it works");
            call.setReturnValue(false);
        }
    }

    /** The blocks of station {@code id} in {@code kitchens} that are still standing (kitchens scanned first if stale). */
    private static List<BlockPos> livePositions(ServerLevel level, List<KitchenBuilding> kitchens, ResourceLocation id) {
        KitchenAccess.scanStale(level, kitchens);
        Stations.Def def = Stations.GLOBAL.get(id);
        if (def == null) return List.of();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : Stations.GLOBAL.positions(kitchens, id)) {
            if (Stations.GLOBAL.matches(def, level.getBlockState(p).getBlock())) out.add(p);
        }
        return out;
    }

    @Nullable
    private static CookActivity.Recipe recipe(CookActivity cook) {
        FieldReader<CookActivity.Recipe> f = recipeField;
        if (f == null) {
            f = FieldReader.of(CookActivity.class, "recipe", CookActivity.Recipe.class);
            recipeField = f;
        }
        return f.available() ? f.read(cook) : null;
    }

    private static int workTimer(CookActivity cook) {
        FieldReader<Integer> f = workTimerField;
        if (f == null) {
            f = FieldReader.of(CookActivity.class, "workTimer", int.class);
            workTimerField = f;
        }
        return f.available() ? f.read(cook) : 0;
    }

    @Nullable
    private static BlockPos station(CookActivity cook) {
        FieldReader<BlockPos> f = stationField;
        if (f == null) {
            f = FieldReader.of(CookActivity.class, "station", BlockPos.class);
            stationField = f;
        }
        return f.available() ? f.read(cook) : null;
    }

    /** The station of the dish {@code cook} works on, or null. */
    @Nullable
    private static ResourceLocation stationId(CookActivity cook) {
        CookActivity.Recipe r = recipe(cook);
        return r == null ? null : CookOverlay.GLOBAL.stationOf(r.result());
    }

    private static void beforeDoTick(HookCall call) {
        if (!(call.self() instanceof CookActivity cook) || !(call.args()[0] instanceof Activity.Context ctx)) return;
        CookActivity.Recipe r = recipe(cook);
        if (r != null && CookOverlay.GLOBAL.isStale(r)) {
            TownKit.LOG.info("[TownKit] a cook's batch of {} ended: that dish was changed or removed", r.result());
            endTick(cook);
            call.cancelWith(Activity.Status.DONE);
            return;
        }
        if (!everStations) return;
        Stations.Batch b = Stations.GLOBAL.openBatch(cook);
        if (b != null && !stillThere(b)) {
            TownKit.LOG.info("[TownKit] a cook's batch of {} ended: its station block at {} is gone", b.ctx().dish(),
                b.ctx().pos().toShortString());
            endTick(cook);
            call.cancelWith(Activity.Status.DONE);
            return;
        }
        Tick t = TICK.get();
        t.cook = cook;
        t.ctx = ctx;
        t.timerBefore = workTimer(cook);
    }

    /** Whether the batch's block is still one of its station's blocks (the station as registered now). */
    private static boolean stillThere(Stations.Batch b) {
        Stations.Def now = Stations.GLOBAL.get(b.def().id());
        return now != null && Stations.GLOBAL.matches(now, b.ctx().level().getBlockState(b.ctx().pos()).getBlock());
    }

    /** Stops {@code cook}'s station batch (if any) and forgets its tick: the after-hook doesn't run on a cancel. */
    private static void endTick(CookActivity cook) {
        stopBatch(cook);
        Tick t = TICK.get();
        if (t.cook == cook) {
            t.cook = null;
            t.ctx = null;
        }
    }

    private static void stopBatch(CookActivity cook) {
        Stations.GLOBAL.stop(cook);
    }

    /**
     * The cook's entity left its level (unloaded, died, changed dimension) without {@code doStop}: its batch's
     * {@code stopped} fires now. Server side; one atomic remove per batch, so a later {@code doStop} doesn't fire again.
     */
    public static void onEntityLeave(Entity entity) {
        if (Stations.GLOBAL.openCount() == 0 || !(entity instanceof NpcEntity npc) || npc.getNpcId() == null) return;
        java.util.UUID id = npc.getNpcId();
        Stations.GLOBAL.stopWhere(ctx -> ctx.cook() != null && id.equals(ctx.cook().id()));
    }

    /** The server stops: every open batch hears {@code stopped}, and nothing keeps a level or cook any longer. */
    public static void onServerStopping() {
        Stations.GLOBAL.stopAll();
        TICK.remove();
    }

    /** A batch starts when {@code workTimer} rises (Towntopia set a new batch's time) and ends when it reaches 0. */
    private static void afterDoTick(HookCall call) {
        Tick t = TICK.get();
        if (t.cook == null || t.cook != call.self()) return;
        CookActivity cook = t.cook;
        Activity.Context ctx = t.ctx;
        int before = t.timerBefore;
        t.cook = null;
        t.ctx = null;
        int after = workTimer(cook);
        boolean fresh = after > 0 && (before <= 0 || after > before);
        if (after <= 0 || fresh) stopBatch(cook);
        if (!fresh || ctx == null) return;
        CookActivity.Recipe r = recipe(cook);
        ResourceLocation id = r == null ? null : CookOverlay.GLOBAL.stationOf(r.result());
        Stations.Def def = id == null ? null : Stations.GLOBAL.get(id);
        BlockPos pos = station(cook);
        if (def == null || pos == null) return;
        Stations.GLOBAL.start(cook, def, new StationContext(ctx.level(), pos.immutable(), VillagerHandle.of(ctx.npc()), r.result()));
    }

    private static void beforeDoStop(HookCall call) {
        if (call.self() instanceof CookActivity cook) stopBatch(cook);
    }

    /** Inside a cook's tick on a station dish: the station's blocks (still standing) instead of the crafting tables. */
    private static void afterTables(HookCall call) {
        Tick t = TICK.get();
        if (t.cook == null || t.ctx == null || !stationsOn) return;
        ResourceLocation id = stationId(t.cook);
        if (id == null) return;
        call.setReturnValue(List.copyOf(livePositions(t.ctx.level(), KitchenAccess.kitchens(call.self()), id)));
    }

    /** A station block's kitchen (Towntopia's kitchens know only their own furniture). */
    private static void afterKitchenOf(HookCall call) {
        if (call.returnValue() != null || !(call.args()[0] instanceof BlockPos pos)) return;
        Object k = Stations.GLOBAL.kitchenOf(KitchenAccess.kitchens(call.self()), pos);
        if (k != null) call.setReturnValue(k);
    }

    /** Towntopia rescanned a kitchen room: so are its station blocks. */
    private static void afterScan(HookCall call) {
        if (call.self() instanceof KitchenBuilding k && call.args()[0] instanceof ServerLevel level) KitchenAccess.scan(level, k);
    }
}
