package townkit.test.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import townkit.core.TownKit;

/**
 * One client check run, as a scene's steps see it: server work, named results, checks, notes and screenshots. Every
 * method runs on the client main thread (the steps are driven from the client tick); {@link #onServer} hands work to
 * the integrated server's thread.
 */
public final class CheckRun {
    /**
     * One step of a scene. {@link #run} is called once per client tick until it returns true; a step still not done
     * after {@code timeoutTicks} fails the run.
     */
    public interface Step {
        String name();

        int timeoutTicks();

        boolean run(CheckRun run);
    }

    final String scene;
    final List<String> notes = new ArrayList<>();
    final List<String> screenshots = new ArrayList<>();
    boolean failed;
    private final Map<String, CompletableFuture<Object>> results = new HashMap<>();

    CheckRun(String scene) {
        this.scene = scene;
    }

    /** A step that runs {@code action} once. */
    public static Step act(String name, Consumer<CheckRun> action) {
        return step(name, 1, r -> {
            action.accept(r);
            return true;
        });
    }

    /** A step that waits until {@code condition} holds (checked once per client tick), for at most {@code timeoutTicks}. */
    public static Step await(String name, int timeoutTicks, Predicate<CheckRun> condition) {
        return step(name, timeoutTicks, condition);
    }

    /** A step that waits {@code ticks} client ticks. */
    public static Step pause(String name, int ticks) {
        int[] left = {ticks};
        return step(name, ticks + 1, r -> --left[0] <= 0);
    }

    private static Step step(String name, int timeoutTicks, Predicate<CheckRun> body) {
        return new Step() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int timeoutTicks() {
                return timeoutTicks;
            }

            @Override
            public boolean run(CheckRun run) {
                return body.test(run);
            }
        };
    }

    public Minecraft mc() {
        return Minecraft.getInstance();
    }

    public MinecraftServer server() {
        MinecraftServer s = mc().getSingleplayerServer();
        if (s == null) throw new IllegalStateException("no integrated server");
        return s;
    }

    /** Runs {@code work} on the server thread; its result is kept under {@code key} ({@link #ready}, {@link #result}). */
    public void onServer(String key, Function<MinecraftServer, Object> work) {
        MinecraftServer s = server();
        CompletableFuture<Object> f = new CompletableFuture<>();
        results.put(key, f);
        s.execute(() -> {
            try {
                f.complete(work.apply(s));
            } catch (Throwable t) {
                TownKit.LOG.error("[TownKit client check] server work '{}' failed", key, t);
                f.completeExceptionally(t);
            }
        });
    }

    /** Whether the server work under {@code key} has finished. Throws if it failed, which fails the run. */
    public boolean ready(String key) {
        CompletableFuture<Object> f = results.get(key);
        if (f == null || !f.isDone()) return false;
        f.join();
        return true;
    }

    /** The result of the finished server work under {@code key}. */
    @SuppressWarnings("unchecked")
    public <T> T result(String key) {
        return (T) results.get(key).join();
    }

    /** Records a client-side assertion; a failed one fails the run. */
    public void check(String what, boolean ok, Object detail) {
        note((ok ? "PASS " : "FAIL ") + what + " (" + detail + ")");
        if (!ok) failed = true;
    }

    public void note(String text) {
        TownKit.LOG.info("[TownKit client check] {}: {}", scene, text);
        notes.add(text);
    }

    /** Saves a screenshot of the current frame as {@code screenshots/<scene>-<n>.png} in the run folder (written in the background). */
    public void shot() {
        String file = scene + "-" + (screenshots.size() + 1) + ".png";
        Screenshot.grab(mc().gameDirectory, file, mc().getMainRenderTarget(), c -> TownKit.LOG.info("[TownKit client check] {}", c.getString()));
        screenshots.add("screenshots/" + file);
        note("screenshot " + file);
    }
}
