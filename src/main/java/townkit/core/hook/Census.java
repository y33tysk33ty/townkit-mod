package townkit.core.hook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;

/**
 * Counts how often every hook's method runs, so we can see which hooks sit on hot paths and decide what to pare down.
 * While it runs, every hooked call takes the listener path (a few nanoseconds more each), so run it for a few minutes,
 * not permanently. Nothing is counted, and nothing costs extra, while it's off.
 */
public final class Census {
    private static long startTick = -1;
    private static long startNanos;

    private Census() {
    }

    public static synchronized boolean running() {
        return startTick >= 0;
    }

    public static synchronized boolean start(MinecraftServer server) {
        if (running()) return false;
        Hooks.setCensus(true);
        startTick = server.getTickCount();
        startNanos = System.nanoTime();
        return true;
    }

    public record Result(Path file, long ticks, double seconds, long totalCalls, int neverCalled, List<String> top) {
    }

    /** Stops counting, writes {@code townkit/census-<time>.tsv} in the server folder and returns a summary. */
    public static synchronized Result stop(MinecraftServer server) throws IOException {
        if (!running()) return null;
        Hooks.setCensus(false);
        long[] calls = Hooks.calls;
        long ticks = Math.max(1, server.getTickCount() - startTick);
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        startTick = -1;

        HookInfo[] info = HookManifest.get().hooks;
        Integer[] order = new Integer[info.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(calls[b], calls[a]));
        StringBuilder tsv = new StringBuilder("calls\tcalls_per_tick\tkey\tkind\tside\tthread\n");
        long total = 0;
        int never = 0;
        List<String> top = new ArrayList<>();
        for (int id : order) {
            long c = calls[id];
            total += c;
            if (c == 0) never++;
            HookInfo h = info[id];
            tsv.append(c).append('\t').append(String.format(Locale.ROOT, "%.2f", c / (double) ticks)).append('\t').append(h.key())
                .append('\t').append(h.ctor() ? "constructor" : "method").append('\t').append(h.client() ? "client" : "common")
                .append('\t').append(h.thread() == null ? "" : h.thread()).append('\n');
            if (top.size() < 10 && c > 0) top.add(String.format(Locale.ROOT, "%,.0f/tick  %s", c / (double) ticks, h.key()));
        }
        Path dir = server.getServerDirectory().resolve("townkit");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = dir.resolve("census-" + stamp + ".tsv");
        for (int n = 2; Files.exists(file); n++) {
            file = dir.resolve("census-" + stamp + "-" + n + ".tsv"); // two censuses in one second must not overwrite each other
        }
        Files.writeString(file, tsv, StandardCharsets.UTF_8);
        return new Result(file, ticks, seconds, total, never, top);
    }
}
