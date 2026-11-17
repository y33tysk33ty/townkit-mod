package townkit.core.brain;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.hooks.HookListener;
import townkit.core.TownKit;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;

/**
 * Hooks that let addon activities live in Towntopia's brain: restoring them by id after an unload, and knowing when a
 * brain is unloading (so a stop can say UNLOADED). Attached with the first registered activity, removed with the last.
 * Server thread only.
 */
public final class BrainHooks {
    static final String RESTORE = "village.brain.VillagerBrain#restoreActivity(String,Activity.Context)";
    static final String UNLOADED = "village.brain.VillagerBrain#onPuppetUnloaded(VillageManager,ServerLevel,NpcEntity)";
    static final String FORCE = "village.brain.VillagerBrain#forceActivity(Activity)";

    private static final List<Hooks.Entry> ATTACHED = new ArrayList<>();
    /** The brain whose puppet is being unloaded right now (server thread). */
    @Nullable
    private static VillagerBrain unloading;

    private BrainHooks() {
    }

    static boolean isUnloading(@Nullable VillagerBrain brain) {
        return brain != null && brain == unloading;
    }

    public static synchronized void ensureAttached() {
        if (!ATTACHED.isEmpty()) return;
        add(RESTORE, true, call -> {
            if (!(call.args()[0] instanceof String name) || name.indexOf(':') < 0) return;
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id == null || !ActivityRegistry.isRegistered(id) || !(call.args()[1] instanceof Activity.Context ctx)) return;
            Activity restored = ActivityRegistry.create(id, ctx.model());
            if (restored != null) call.setReturnValue(restored);
        });
        add(UNLOADED, false, call -> unloading = (VillagerBrain) call.self());
        add(UNLOADED, true, call -> unloading = null);
        // Towntopia queues its routine activities (conversations, dances, chats) through forceActivity; a run that
        // opted out of routine interruptions refuses them. Safety (flee, shelter, hunger, bedtime) doesn't come this way.
        add(FORCE, false, call -> {
            Activity current = ((VillagerBrain) call.self()).currentActivity();
            if (ActivityRegistry.addonId(current) != null && !ActivityRegistry.allowsRoutineInterruption(current)
                && ActivityRegistry.addonId((Activity) call.args()[0]) == null) {
                call.cancel();
            }
        });
    }

    public static synchronized void detachIfUnused() {
        if (ActivityRegistry.any()) return;
        ATTACHED.forEach(Hooks::remove);
        ATTACHED.clear();
        unloading = null;
    }

    private static void add(String key, boolean after, HookListener listener) {
        HookInfo h = HookManifest.get().byKey.get(key);
        if (h == null) {
            TownKit.LOG.warn("[TownKit] can't find {}: addon activities lose that part (restore after unload, or the UNLOADED stop reason)", key);
            return;
        }
        ATTACHED.add(Hooks.add(h, "townkit", listener, after));
    }
}
