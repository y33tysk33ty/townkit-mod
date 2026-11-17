package townkit.core.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.Subscription;
import townkit.api.brain.VillagerActivity;
import townkit.api.village.Villager;
import townkit.core.TownKit;
import townkit.core.village.VillagerHandle;

/**
 * Registered addon activities by id, with their failure counts, and the one place that turns an id into a Towntopia
 * {@link Activity} (an adapter wrapping a fresh addon activity).
 */
public final class ActivityRegistry {
    private static final int MAX_FAILURES = 10;

    private static final class Entry {
        final Function<Villager, VillagerActivity> factory;
        int failures;
        volatile boolean off;

        Entry(Function<Villager, VillagerActivity> factory) {
            this.factory = factory;
        }
    }

    private static final Map<ResourceLocation, Entry> ENTRIES = new ConcurrentHashMap<>();

    private ActivityRegistry() {
    }

    public static Subscription register(ResourceLocation id, Function<Villager, VillagerActivity> factory) {
        Entry entry = new Entry(factory);
        if (ENTRIES.putIfAbsent(id, entry) != null) throw new IllegalArgumentException("Activity " + id + " is already registered");
        return new Subscription() {
            @Override
            public void remove() {
                ENTRIES.remove(id, entry);
            }

            @Override
            public boolean isDisabled() {
                return entry.off;
            }
        };
    }

    public static boolean isRegistered(ResourceLocation id) {
        return ENTRIES.containsKey(id);
    }

    public static boolean any() {
        return !ENTRIES.isEmpty();
    }

    public static boolean isSwitchedOff(ResourceLocation id) {
        Entry e = ENTRIES.get(id);
        return e != null && e.off;
    }

    public static void recordSuccess(ResourceLocation id) {
        Entry e = ENTRIES.get(id);
        if (e != null) e.failures = 0;
    }

    public static void recordFailure(ResourceLocation id) {
        Entry e = ENTRIES.get(id);
        if (e == null) return;
        e.failures++;
        if (e.failures >= MAX_FAILURES && !e.off) {
            e.off = true;
            TownKit.LOG.error("[TownKit] activity {} switched off after {} failures in a row", id, MAX_FAILURES);
        }
    }

    /** A fresh run of {@code id} for this villager as a Towntopia activity; null when unknown, switched off or the factory fails. */
    @Nullable
    public static Activity create(ResourceLocation id, VillagerModel model) {
        Entry e = ENTRIES.get(id);
        if (e == null || e.off) return null;
        VillagerActivity activity;
        try {
            activity = e.factory.apply(VillagerHandle.of(model));
        } catch (RuntimeException ex) {
            TownKit.LOG.error("[TownKit] the factory for activity {} failed", id, ex);
            recordFailure(id);
            return null;
        }
        if (activity == null) return null;
        return new AddonActivity(new AddonRun(id, activity, model));
    }

    @Nullable
    public static ResourceLocation addonId(@Nullable Activity activity) {
        return activity instanceof AddonActivity a ? a.run.id() : null;
    }

    public static boolean allowsRoutineInterruption(@Nullable Activity activity) {
        return activity instanceof AddonActivity a && a.run.allowsRoutineInterruption();
    }
}
