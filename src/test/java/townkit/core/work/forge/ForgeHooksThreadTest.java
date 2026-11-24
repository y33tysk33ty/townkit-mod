package townkit.core.work.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.Test;

/** Where the smith's overlay switch runs when it's asked for from a thread other than the server's. */
class ForgeHooksThreadTest {
    /**
     * A server whose thread is some other thread and that runs handed-in jobs at once on the caller's thread, as a
     * stopped server does (vanilla: {@code scheduleExecutables} is false once {@code isStopped}).
     */
    private static final class StoppedServer extends BlockableEventLoop<Runnable> {
        private final Thread other = new Thread(() -> { });

        StoppedServer() {
            super("stopped-test-server");
        }

        @Override
        public Runnable wrapRunnable(Runnable r) {
            return r;
        }

        @Override
        protected boolean shouldRun(Runnable r) {
            return true;
        }

        @Override
        protected Thread getRunningThread() {
            return this.other;
        }

        @Override
        public boolean scheduleExecutables() {
            return false;
        }
    }

    /** Opening a world right after leaving one: the old, stopped server is still current. The switch runs once. */
    @Test
    void stoppedServerRunsTheSwitchOnceInsteadOfLooping() {
        AtomicInteger runs = new AtomicInteger();
        ForgeHooks.onServerThread(new StoppedServer(), runs::incrementAndGet);
        assertEquals(1, runs.get());
    }

    /** A running server on another thread gets the switch queued, not run on the caller's thread. */
    @Test
    void runningServerQueuesTheSwitchForItsOwnThread() {
        AtomicInteger runs = new AtomicInteger();
        RunningServer server = new RunningServer();
        ForgeHooks.onServerThread(server, runs::incrementAndGet);
        assertEquals(0, runs.get());
        server.runQueued();
        assertEquals(1, runs.get());
    }

    /** Like {@link StoppedServer}, but queues handed-in jobs (vanilla's default from another thread). */
    private static final class RunningServer extends BlockableEventLoop<Runnable> {
        private final Thread other = new Thread(() -> { });

        RunningServer() {
            super("running-test-server");
        }

        void runQueued() {
            this.runAllTasks();
        }

        @Override
        public Runnable wrapRunnable(Runnable r) {
            return r;
        }

        @Override
        protected boolean shouldRun(Runnable r) {
            return true;
        }

        @Override
        protected Thread getRunningThread() {
            return this.other;
        }
    }

    @Test
    void noServerRunsTheSwitchAtOnce() {
        AtomicInteger runs = new AtomicInteger();
        ForgeHooks.onServerThread(null, runs::incrementAndGet);
        assertEquals(1, runs.get());
    }
}
