package townkit.core.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.brain.ActivityStatus;
import townkit.api.brain.StopReason;
import townkit.api.brain.VillagerActivity;
import townkit.api.event.TownKitEvents;
import townkit.core.TownKit;
import townkit.core.event.DirectEvents;
import townkit.core.village.VillagerHandle;

/**
 * One run of an addon activity: the shared logic behind both adapters. Isolates the addon's exceptions, works out why
 * it stopped, delivers {@code stop} once, and fires the started/stopped events.
 */
final class AddonRun {
    private final ResourceLocation id;
    private final VillagerActivity activity;
    private final VillagerHandle villager;
    private final CoreActivityContext ctx;
    /** Set when the run ended itself (DONE/FAILED, or the addon threw). */
    @Nullable
    private StopReason ended;
    private boolean stopped;
    private boolean started;
    /** The addon's start threw: it still gets stop(FAILED), but neither event fires, so listeners only see whole runs. */
    private boolean startFailed;

    AddonRun(ResourceLocation id, VillagerActivity activity, VillagerModel model) {
        this.id = id;
        this.activity = activity;
        this.villager = VillagerHandle.of(model);
        this.ctx = new CoreActivityContext(this.villager, this);
    }

    ResourceLocation id() {
        return this.id;
    }

    boolean allowsRoutineInterruption() {
        return this.activity.allowsRoutineInterruption();
    }

    void start(Activity.Context raw) {
        this.started = true;
        this.ctx.bind(raw);
        try {
            this.activity.start(this.ctx);
        } catch (RuntimeException e) {
            this.startFailed = true;
            this.fail("start", e);
        } finally {
            this.ctx.unbind();
        }
        if (!this.startFailed && DirectEvents.has(TownKitEvents.ADDON_ACTIVITY_STARTED)) {
            DirectEvents.fire(TownKitEvents.ADDON_ACTIVITY_STARTED, new TownKitEvents.AddonActivityStarted(this.villager, this.id));
        }
    }

    Activity.Status tick(Activity.Context raw) {
        if (this.ended != null) return this.ended == StopReason.DONE ? Activity.Status.DONE : Activity.Status.FAILED;
        this.ctx.bind(raw);
        try {
            ActivityStatus status = this.activity.tick(this.ctx);
            if (status == ActivityStatus.DONE || status == ActivityStatus.FAILED) {
                this.ended = status == ActivityStatus.DONE ? StopReason.DONE : StopReason.FAILED;
                ActivityRegistry.recordSuccess(this.id);
                return status == ActivityStatus.DONE ? Activity.Status.DONE : Activity.Status.FAILED;
            }
            return Activity.Status.RUNNING;
        } catch (RuntimeException e) {
            this.fail("tick", e);
            return Activity.Status.FAILED;
        } finally {
            this.ctx.unbind();
        }
    }

    void stop(Activity.Context raw) {
        if (this.stopped) return;
        this.stopped = true;
        // Towntopia may replace a queued or restored run before it ever ticked: the addon never started, so no stop
        if (!this.started) return;
        // like Towntopia's own activities: don't keep walking to this run's goal after it stops
        this.ctx.stopMove(raw);
        StopReason reason = this.ended != null ? this.ended : this.reasonFromTowntopia(raw);
        this.ctx.bind(raw);
        try {
            this.activity.stop(this.ctx, reason);
        } catch (RuntimeException e) {
            this.fail("stop", e);
        } finally {
            this.ctx.unbind();
        }
        if (!this.startFailed && DirectEvents.has(TownKitEvents.ADDON_ACTIVITY_STOPPED)) {
            DirectEvents.fire(TownKitEvents.ADDON_ACTIVITY_STOPPED, new TownKitEvents.AddonActivityStopped(this.villager, this.id, reason));
        }
    }

    /** Towntopia stopped us: the villager was forgotten (removeVillager drops the model first), unloaded, or switched. */
    private StopReason reasonFromTowntopia(Activity.Context raw) {
        if (raw.mgr() == null || raw.mgr().getModel(raw.model().npcId) == null) return StopReason.VILLAGER_GONE;
        if (BrainHooks.isUnloading(raw.brain())) return StopReason.UNLOADED;
        return StopReason.INTERRUPTED;
    }

    private void fail(String where, RuntimeException e) {
        TownKit.LOG.error("[TownKit] activity {} failed in {}", this.id, where, e);
        ActivityRegistry.recordFailure(this.id);
        if (this.ended == null) this.ended = StopReason.FAILED;
    }
}
