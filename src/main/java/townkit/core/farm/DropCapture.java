package townkit.core.farm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import townkit.core.work.WorkLog;

/**
 * Catches the items a call drops into the world, so they can go into the farmer's pack instead of onto the ground.
 *
 * <p>While a capture is open, an {@link EntityJoinLevelEvent} listener (lowest priority, so other mods saw the item
 * first; never for an item another listener already cancelled) cancels every new {@link ItemEntity} that joins the same
 * level inside the box, on the thread that opened the capture, and keeps its stack. Nothing else is touched: other
 * entities, items outside the box, items loaded from disk, items another thread adds, items dropped while the capture is
 * {@linkplain #pause() paused}, and every item before or after the capture. Captures may nest; the innermost one whose
 * box holds the item gets it, else the next one out.
 *
 * <p>Two ways to capture: {@link #during} wraps a call and always ends with it (also when it throws); {@link #open} /
 * {@link #close} span a hooked method from its before-hook to its after-hook. An opened capture whose close never came
 * (the method threw, or another listener cancelled it) is {@linkplain #release released} at the end of the server tick
 * at the latest: its items are put back into the world where they would have been, so none is lost or made up, and no
 * capture outlives the tick.
 *
 * <p>The listeners are registered on first use and stay; with no capture open they are one static read.
 *
 * @since 0.8.0
 */
public final class DropCapture {
    /** One open capture. */
    public static final class Handle {
        final ServerLevel level;
        final AABB box;
        final BlockPos releaseAt;
        final Thread thread;
        final List<ItemStack> caught = new ArrayList<>();
        boolean closed;

        private Handle(ServerLevel level, AABB box, BlockPos releaseAt) {
            this.level = level;
            this.box = box;
            this.releaseAt = releaseAt;
            this.thread = Thread.currentThread();
        }
    }

    private static final Handle[] NONE = new Handle[0];
    /** Open captures, outermost first (copy on write; changed only on the thread that owns them). */
    private static volatile Handle[] open = NONE;
    /** Pause depth of the open captures (the thread that owns them only); back to 0 when the last capture closes. */
    private static int paused;
    private static boolean registered;

    private DropCapture() {
    }

    /**
     * Runs {@code call} and returns the stacks of the items it dropped into {@code level} inside {@code box} (in drop
     * order); those items never enter the world. An exception from the call is thrown on, after the capture ended; the
     * items caught so far are put back into the world at the box's centre first.
     */
    public static List<ItemStack> during(ServerLevel level, AABB box, Runnable call) {
        Handle h = open(level, box, BlockPos.containing(box.getCenter()));
        boolean done = false;
        try {
            call.run();
            done = true;
        } finally {
            if (!done) release(h);
        }
        return close(h);
    }

    /**
     * Opens a capture on this thread; it lasts until {@link #close} or {@link #release} (or, at the latest, the end of
     * the server tick, when it is released). {@code releaseAt} is where caught items go back into the world on release.
     */
    public static Handle open(ServerLevel level, AABB box, BlockPos releaseAt) {
        register();
        Handle h = new Handle(level, box, releaseAt);
        Handle[] now = open;
        Handle[] next = Arrays.copyOf(now, now.length + 1);
        next[now.length] = h;
        open = next;
        return h;
    }

    /** Ends the capture and returns what it caught (empty if it was already closed or released). */
    public static List<ItemStack> close(Handle h) {
        if (h.closed) return List.of();
        h.closed = true;
        Handle[] now = open;
        Handle[] next = Arrays.stream(now).filter(x -> x != h).toArray(Handle[]::new);
        open = next.length == 0 ? NONE : next;
        if (next.length == 0) paused = 0;
        return h.caught;
    }

    /** Ends the capture and puts what it caught back into the world at its release position. */
    public static void release(Handle h) {
        for (ItemStack s : close(h)) Block.popResource(h.level, h.releaseAt, s);
    }

    /** While paused (on the thread that owns the open captures), nothing is caught. Pair with {@link #resume()}. */
    public static void pause() {
        if (active()) paused++;
    }

    /** Ends one {@link #pause()}. */
    public static void resume() {
        if (paused > 0) paused--;
    }

    /** Whether a capture is open right now on this thread. */
    public static boolean active() {
        Handle[] now = open;
        return now.length > 0 && now[now.length - 1].thread == Thread.currentThread();
    }

    /** How many captures are open (for tests). */
    public static int openCount() {
        return open.length;
    }

    private static synchronized void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, DropCapture::onJoin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, e -> sweep());
    }

    /** End of the server tick: no capture may stay open; one still open lost its close (its method threw or was cancelled). */
    private static void sweep() {
        Handle[] now = open;
        if (now.length == 0) return;
        WorkLog.GLOBAL.once("drop-capture-stale", "[farm] a harvest capture was still open at the end of a tick (the harvest threw or "
            + "was cancelled by another listener); its items were put back into the world");
        for (int i = now.length - 1; i >= 0; i--) release(now[i]);
        paused = 0;
    }

    private static void onJoin(EntityJoinLevelEvent event) {
        Handle[] now = open;
        if (now.length == 0 || event.loadedFromDisk() || !(event.getEntity() instanceof ItemEntity item)) return;
        Thread me = Thread.currentThread();
        if (now[now.length - 1].thread != me || paused > 0) return;
        for (int i = now.length - 1; i >= 0; i--) {
            Handle h = now[i];
            if (h.thread == me && h.level == event.getLevel() && h.box.contains(item.position())) {
                ItemStack stack = item.getItem();
                if (!stack.isEmpty()) h.caught.add(stack.copy());
                event.setCanceled(true);
                return;
            }
        }
    }
}
