package townkit.core.event;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import townkit.api.event.TownKitEvents;
import townkit.core.TownKit;
import townkit.core.village.VillageHandle;
import townkit.core.village.VillageReads;

/**
 * Behind {@code VILLAGE_STATE_CHANGED}, {@code RAID_STAGE_CHANGED}, {@code RAID_STARTED} and {@code RAID_ENDED}: once a
 * second, while any of them has a listener, reads every village's state and raid stage and fires what changed. One
 * shared instance serves all four events (the tick listener is registered by the first of them to get a listener and
 * dropped with the last listener of all four). Reads only; it never loads Towntopia state that isn't loaded yet.
 *
 * <p>It can attach before any server exists and outlive a server restart: the server comes from the tick event, and a
 * different server than last time clears the watch so the new world starts from a silent baseline.
 */
public final class VillageWatcher implements DirectEvents.Lifecycle {
    /** The one shared lifecycle. */
    static final VillageWatcher INSTANCE = new VillageWatcher();

    private static final int PERIOD_TICKS = 20;

    private final VillageWatch watch = new VillageWatch();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    /** A poll hit a linkage failure: no more polling, and the events report unavailable. */
    private volatile boolean linkageFailed;
    /** Events currently attached; the tick listener is registered while this is above zero. */
    private int attached;
    private volatile boolean registered;
    /** Set by attach/detach (any thread); the tick listener clears the watch, so only the server thread touches it. */
    private volatile boolean resetRequested;
    /** Server thread only. */
    private WeakReference<MinecraftServer> lastServer = new WeakReference<>(null);

    /** Stable reference: unregistering needs the same object that was registered. */
    private final Consumer<ServerTickEvent.Post> tickListener = this::onServerTick;

    /** Package-private for tests; everything else uses {@link #INSTANCE}. */
    VillageWatcher() {
    }

    /** Whether the tick listener is registered (for tests). */
    public static boolean isAttached() {
        return INSTANCE.registered;
    }

    @Override
    public boolean available() {
        return !this.linkageFailed;
    }

    @Override
    public synchronized void attach() {
        this.attached++;
        if (this.attached == 1) {
            this.resetRequested = true;
            NeoForge.EVENT_BUS.addListener(this.tickListener);
            this.registered = true;
        }
    }

    @Override
    public synchronized void detach() {
        if (this.attached == 0) return;
        this.attached--;
        if (this.attached == 0) {
            NeoForge.EVENT_BUS.unregister(this.tickListener);
            this.registered = false;
            this.resetRequested = true;
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (this.resetRequested || server != this.lastServer.get()) {
            this.resetRequested = false;
            this.watch.clear();
            this.failureLogged.set(false);
            this.lastServer = new WeakReference<>(server);
        }
        if (server.getTickCount() % PERIOD_TICKS != 0) return;
        this.guardedPoll(() -> this.poll(server));
    }

    /**
     * Runs one poll; nothing it throws may reach the server tick. A failed read is retried on the next poll. A linkage
     * failure means Towntopia changed what the poll calls: that won't fix itself, so polling stops for good.
     */
    void guardedPoll(Runnable poll) {
        if (this.linkageFailed) return;
        try {
            poll.run();
        } catch (RuntimeException | LinkageError e) {
            boolean linkage = e instanceof LinkageError;
            if (linkage) this.linkageFailed = true;
            if (this.failureLogged.compareAndSet(false, true) || linkage) {
                TownKit.LOG.error("[TownKit] reading village state for the village events failed{}",
                    linkage ? ": this Towntopia differs, so these events stop until the next start" : " (logged once)", e);
            }
        }
    }

    private void poll(MinecraftServer server) {
        VillageManagerRegistry reg = VillageManagerRegistry.get(server);
        List<VillageWatch.Reading> readings = new ArrayList<>();
        Map<UUID, VillageManager> byId = new HashMap<>();
        for (VillageManager mgr : reg.all()) {
            if (mgr.getLevel() == null) continue;
            UUID id = mgr.getVillageId();
            byId.put(id, mgr);
            readings.add(new VillageWatch.Reading(id, VillageReads.state(reg, id), VillageReads.raidStage(mgr)));
        }
        List<VillageWatch.Change> changes = this.watch.update(readings);
        // all reading is done: handlers may do anything to the villages
        for (VillageWatch.Change c : changes) {
            VillageManager mgr = byId.get(c.id());
            VillageHandle v = mgr == null ? null : VillageHandle.of(mgr);
            if (v == null) continue;
            if (c instanceof VillageWatch.StateChanged s) {
                DirectEvents.fire(TownKitEvents.VILLAGE_STATE_CHANGED, new TownKitEvents.VillageStateChanged(v, s.from(), s.to()));
            } else if (c instanceof VillageWatch.StageChanged s) {
                DirectEvents.fire(TownKitEvents.RAID_STAGE_CHANGED, new TownKitEvents.RaidStageChanged(v, s.from(), s.to()));
            } else if (c instanceof VillageWatch.Started s) {
                DirectEvents.fire(TownKitEvents.RAID_STARTED, new TownKitEvents.RaidStarted(v, s.stage()));
            } else if (c instanceof VillageWatch.Ended) {
                DirectEvents.fire(TownKitEvents.RAID_ENDED, new TownKitEvents.RaidEnded(v));
            }
        }
    }
}
