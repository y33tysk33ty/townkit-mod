package townkit.core.event;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.core.Profession;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import townkit.api.Subscription;
import townkit.api.event.TownKitEvents;
import townkit.api.event.TypedEvent;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.core.FieldReader;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.village.Ids;
import townkit.core.village.VillageHandle;
import townkit.core.village.VillagerHandle;
import townkit.core.villager.VillagerDataHooks;

/**
 * Which hook each typed event is built on, and how a hook call becomes the event. The only place that knows; when
 * Towntopia moves a method, this file changes and addons don't.
 */
public final class EventBindings {
    /**
     * {@code map} may return null to skip a call (e.g. a village that isn't set up yet).
     * {@code ready}: anything else the event needs besides its hook (e.g. a private field it reads).
     */
    private record Binding<T>(String hookKey, boolean after, Function<HookCall, T> map, BooleanSupplier ready) {
        Binding(String hookKey, boolean after, Function<HookCall, T> map) {
            this(hookKey, after, map, () -> true);
        }
    }

    /** Towntopia keeps these private; read them fail-soft (each loaded on first use, like the class it reads). */
    private static final class Fields {
        static final FieldReader<VillagerModel> BRAIN_MODEL = FieldReader.of(VillagerBrain.class, "model", VillagerModel.class);
        static final FieldReader<VillageManager> BUILDINGS_VILLAGE =
            FieldReader.of(VillageManager.class, "com.alex.test.village.VillageBuildings", "village", VillageManager.class);
    }

    @SuppressWarnings("deprecation") // BUILDING_CHANGE stays delivered while deprecated
    private static final Map<TypedEvent<?>, Binding<?>> BINDINGS = Map.of(
        TownKitEvents.VILLAGER_REMOVED, new Binding<>(VillagerDataHooks.REMOVE_VILLAGER, false, c -> {
            VillageManager mgr = c.self(VillageManager.class);
            java.util.UUID npcId = c.arg(0, java.util.UUID.class);
            VillagerModel model = npcId == null ? null : mgr.getModel(npcId);
            VillageHandle village = VillageHandle.of(mgr);
            if (model == null || village == null) return null;
            return new TownKitEvents.VillagerRemoved(new VillagerHandle(mgr.getLevel().getServer(), mgr.getVillageId(), npcId, model.puppet()), village);
        }),
        TownKitEvents.ACTIVITY_CHANGE, new Binding<>("village.brain.VillagerBrain#switchTo(Activity,Activity.Context)", false, c -> {
            VillagerBrain brain = c.self(VillagerBrain.class);
            Activity from = brain.currentActivity();
            return new TownKitEvents.ActivityChange(VillagerHandle.of(Fields.BRAIN_MODEL.read(brain)), from == null ? "none" : from.name(),
                c.arg(0, Activity.class).name());
        }, () -> Fields.BRAIN_MODEL.available()),
        TownKitEvents.PROFESSION_CHANGE, new Binding<>("entity.NpcEntity#setProfession(Profession)", false, c -> {
            NpcEntity npc = c.self(NpcEntity.class);
            // Towntopia's readAdditionalSaveData sets the profession while the villager loads from disk, on the
            // entity-deserializer thread: a load isn't a change, and handlers must never run off the server thread
            if (!npc.level().isClientSide() && npc.getServer() != null && !npc.getServer().isSameThread()) return null;
            return new TownKitEvents.ProfessionChange(VillagerHandle.of(npc), Ids.profession(npc.getProfession().name()),
                Ids.profession(c.arg(0, Profession.class).name()));
        }),
        TownKitEvents.BUILDING_CHANGE, new Binding<>("village.VillageBuildings#markNeighboursDirty(Building,UUID,String)", true, c -> {
            VillageHandle village = VillageHandle.of(Fields.BUILDINGS_VILLAGE.read(c.self()));
            return village == null ? null : new TownKitEvents.BuildingChange(village, VillageHandle.info(c.arg(0, Building.class)), c.arg(2, String.class));
        }, () -> Fields.BUILDINGS_VILLAGE.available()),
        TownKitEvents.DAY_ROLLOVER, new Binding<>("village.brain.VillagerBrain#handleDayRollover(ServerLevel,VillageManager,NpcEntity)", true, c -> {
            // the level comes from the call itself: the manager's own level may not be set up yet
            ServerLevel level = c.arg(0, ServerLevel.class);
            VillageManager mgr = c.arg(1, VillageManager.class);
            NpcEntity npc = c.arg(2, NpcEntity.class);
            return new TownKitEvents.DayRollover(new VillagerHandle(level.getServer(), mgr.getVillageId(), npc.getNpcId(), npc),
                new VillageHandle(level.getServer(), mgr.getVillageId(), level.dimension()));
        }),
        // onFounded runs after Towntopia registered the village and set its founder's claim (logFounded runs earlier, and also for a founding that fails)
        TownKitEvents.VILLAGE_FOUNDED, new Binding<>("village.VillageFounding#onFounded(ServerLevel,VillageManager,BlockPos,Direction)", true, c -> {
            VillageHandle village = VillageHandle.of(c.arg(1, VillageManager.class));
            return village == null ? null : new TownKitEvents.VillageFounded(village, c.arg(2, net.minecraft.core.BlockPos.class));
        }));

    /** Times each event's mapping failed (TownKit's fault, not the addon's): logged sparingly. */
    private static final Map<String, AtomicLong> MAPPING_FAILURES = new ConcurrentHashMap<>();

    private EventBindings() {
    }

    /**
     * The hook listener behind one subscription: TownKit builds the event, then the addon's handler gets it. A mapping
     * that throws is TownKit's problem: it's logged and the call skipped, so it never counts against (or switches off)
     * the handler. Only the handler's own exceptions do.
     */
    public static <T> HookListener deliver(String eventName, Function<HookCall, T> map, Consumer<T> handler) {
        return call -> {
            T event;
            try {
                event = map.apply(call);
            } catch (RuntimeException e) {
                long n = MAPPING_FAILURES.computeIfAbsent(eventName, k -> new AtomicLong()).incrementAndGet();
                if (n <= 3 || n % 1000 == 0) {
                    TownKit.LOG.error("[TownKit] couldn't build event {} ({} times so far); skipped, the handler wasn't called", eventName, n, e);
                }
                return;
            }
            if (event != null) handler.accept(event);
        };
    }

    /** Listening to an event TownKit can't deliver logs a warning and gives a subscription that is already switched off. */
    public static <T> Subscription listen(TypedEvent<T> event, String owner, Consumer<T> handler) {
        if (!available(event)) {
            TownKit.LOG.warn("[TownKit] '{}' listens to event {}, which is unavailable ({}): the handler will never run", owner, event.name(),
                RewrittenSites.GLOBAL.hooksOff() ? "Towntopia hooks are switched off in townkit-hooks.properties, or the installed Towntopia differs"
                    : "the installed Towntopia differs");
            return DISABLED;
        }
        if (DirectEvents.isDirect(event)) return DirectEvents.listen(event, owner, handler);
        @SuppressWarnings("unchecked")
        Binding<T> b = (Binding<T>) BINDINGS.get(event);
        return Hooks.add(HookManifest.get().byKey.get(b.hookKey()), owner, deliver(event.name(), b.map(), handler), b.after());
    }

    private static final Subscription DISABLED = new Subscription() {
        @Override
        public void remove() {
        }

        @Override
        public boolean isDisabled() {
            return true;
        }
    };

    /** The manifest stays full with hooks switched off, so it alone can't tell whether a hook will ever fire. */
    static boolean hookBoundAvailable(boolean hooksOff, boolean hookInManifest, BooleanSupplier ready) {
        return !hooksOff && hookInManifest && ready.getAsBoolean();
    }

    public static boolean available(TypedEvent<?> event) {
        if (DirectEvents.isDirect(event)) return DirectEvents.available(event);
        Binding<?> b = BINDINGS.get(event);
        return b != null && hookBoundAvailable(RewrittenSites.GLOBAL.hooksOff(), HookManifest.get().byKey.containsKey(b.hookKey()), b.ready());
    }

    /** The hook an event is built on (for TownKit's own checks, e.g. that the hook is live). */
    @Nullable
    public static String hookKey(TypedEvent<?> event) {
        Binding<?> b = BINDINGS.get(event);
        return b == null ? null : b.hookKey();
    }
}
