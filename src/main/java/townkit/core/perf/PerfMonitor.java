package townkit.core.perf;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import townkit.core.hook.Hooks;
import townkit.core.FieldReader;

/**
 * Always-on, cheap server performance tracking: tick times (last 60 s), spike detection with blame, and the time
 * every TownKit listener costs, per hook and per owning addon. Two nanoTime reads per tick plus listener bookkeeping.
 */
public final class PerfMonitor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int WINDOW = 1200;
    public static final long SPIKE_NANOS = 50_000_000L;

    private static final long[] TICKS = new long[WINDOW];
    private static int ticksSeen;
    private static long tickStart;
    private static long listenerNanosThisTick;
    private static Hooks.Entry worstEntryThisTick;
    private static long worstEntryNanosThisTick;
    private static long spikes;
    private static long spikesFromTownKit;
    private static final List<String> RECENT_SPIKES = new ArrayList<>();

    private PerfMonitor() {
    }

    private static volatile Thread serverThread;

    /**
     * Called by the hook dispatcher after every listener call. Only server-thread time counts toward the tick (worker
     * and client threads run alongside it); every call still counts toward its listener's own totals.
     */
    /**
     * Time spent in addon code TownKit calls outside hook listeners (choice and interrupt providers), per owner. The
     * same time is also inside the "townkit" owner's hook time (its brain hooks call the providers): reported twice.
     */
    private static final Map<String, long[]> OWNER_TIME = new java.util.concurrent.ConcurrentHashMap<>();

    public static void ownerTime(String owner, long nanos) {
        long[] a = OWNER_TIME.computeIfAbsent(owner, k -> new long[2]);
        a[0]++;
        a[1] += nanos;
    }

    public static void listenerTime(Hooks.Entry e, long nanos) {
        if (Thread.currentThread() != serverThread) {
            return;
        }
        listenerNanosThisTick += nanos;
        if (nanos > worstEntryNanosThisTick) {
            worstEntryNanosThisTick = nanos;
            worstEntryThisTick = e;
        }
    }

    @SubscribeEvent
    public static void onTickStart(ServerTickEvent.Pre event) {
        serverThread = Thread.currentThread();
        tickStart = System.nanoTime();
        listenerNanosThisTick = 0L;
        worstEntryNanosThisTick = 0L;
        worstEntryThisTick = null;
    }

    @SubscribeEvent
    public static void onTickEnd(ServerTickEvent.Post event) {
        long dt = System.nanoTime() - tickStart;
        TICKS[ticksSeen % WINDOW] = dt;
        ticksSeen++;
        if (dt >= SPIKE_NANOS) {
            spikes++;
            boolean ours = listenerNanosThisTick > dt / 2;
            if (ours) spikesFromTownKit++;
            String line = String.format(Locale.ROOT, "tick %d: %.0f ms (TownKit listeners %.1f ms%s; Towntopia %s)", event.getServer().getTickCount(),
                dt / 1e6, listenerNanosThisTick / 1e6,
                worstEntryThisTick != null ? ", worst " + worstEntryThisTick.owner + " on " + worstEntryThisTick.hook.key() : "",
                towntopiaPhases(event.getServer()));
            synchronized (RECENT_SPIKES) {
                RECENT_SPIKES.add(line);
                if (RECENT_SPIKES.size() > 10) RECENT_SPIKES.remove(0);
            }
            if (ours) {
                LOGGER.warn("[TownKit] lag spike caused by TownKit listeners: {}", line);
            }
        }
    }

    /** Towntopia's own per-phase tick timings (private): read fail-soft, so a rename only drops this figure. */
    private static final class Phases {
        static final FieldReader<long[]> NANOS = FieldReader.of(VillageManager.class, "tickPhaseNanos", long[].class);
    }

    private static String towntopiaPhases(MinecraftServer server) {
        if (!Phases.NANOS.available()) return "?";
        long total = 0;
        try {
            for (VillageManager m : VillageManagerRegistry.get(server).all()) {
                for (long p : Phases.NANOS.read(m)) total += p;
            }
        } catch (Throwable t) {
            return "?";
        }
        return String.format(Locale.ROOT, "%.1f ms last village ticks", total / 1e6);
    }

    public static void reset() {
        Arrays.fill(TICKS, 0L);
        ticksSeen = 0;
        spikes = 0;
        spikesFromTownKit = 0;
        synchronized (RECENT_SPIKES) {
            RECENT_SPIKES.clear();
        }
        for (Hooks.Entry e : Hooks.allEntries()) {
            e.calls = 0;
            e.nanos = 0;
            e.maxNanos = 0;
        }
    }

    private static long pct(long[] sorted, double q) {
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    public record TickStats(int ticks, double meanMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {
    }

    /** Tick-time statistics over the window (last 1200 ticks, or fewer since reset). */
    public static TickStats stats() {
        int n = Math.min(ticksSeen, WINDOW);
        if (n == 0) return new TickStats(0, 0, 0, 0, 0, 0);
        long[] s = Arrays.copyOf(TICKS, n);
        Arrays.sort(s);
        return new TickStats(n, Arrays.stream(s).average().orElse(0) / 1e6, pct(s, 0.5) / 1e6, pct(s, 0.95) / 1e6, pct(s, 0.99) / 1e6,
            s[n - 1] / 1e6);
    }

    /** Human-readable summary for /townkit perf. */
    public static List<String> report() {
        List<String> out = new ArrayList<>();
        int n = Math.min(ticksSeen, WINDOW);
        if (n == 0) {
            out.add("No ticks measured yet.");
            return out;
        }
        long[] s = Arrays.copyOf(TICKS, n);
        Arrays.sort(s);
        double mean = Arrays.stream(s).average().orElse(0) / 1e6;
        out.add(String.format(Locale.ROOT, "Server ticks (last %d): mean %.2f ms, p95 %.1f, p99 %.1f, max %.1f; est. TPS %.1f", n, mean,
            pct(s, 0.95) / 1e6, pct(s, 0.99) / 1e6, s[n - 1] / 1e6, Math.min(20.0, 1000.0 / Math.max(mean, 0.001))));
        out.add(String.format(Locale.ROOT, "Lag spikes (>= 50 ms): %d since reset, %d caused by TownKit listeners", spikes, spikesFromTownKit));
        Map<String, long[]> byOwner = new HashMap<>();
        List<Hooks.Entry> entries = Hooks.allEntries();
        for (Hooks.Entry e : entries) {
            long[] a = byOwner.computeIfAbsent(e.owner, k -> new long[2]);
            a[0] += e.calls;
            a[1] += e.nanos;
        }
        OWNER_TIME.forEach((owner, t) -> {
            long[] a = byOwner.computeIfAbsent(owner, k -> new long[2]);
            a[0] += t[0];
            a[1] += t[1];
        });
        long ticks = Math.max(1, ticksSeen);
        byOwner.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(8).forEach(en ->
            out.add(String.format(Locale.ROOT, "  addon %s: %.3f ms per tick, %d calls", en.getKey(), en.getValue()[1] / 1e6 / ticks, en.getValue()[0])));
        entries.stream().sorted(Comparator.comparingLong((Hooks.Entry e) -> e.nanos).reversed()).limit(5).forEach(e ->
            out.add(String.format(Locale.ROOT, "  hook %s (%s, %s): %.3f ms per tick, worst %.2f ms, %d calls%s", e.hook.key(), e.after ? "after" : "before",
                e.owner, e.nanos / 1e6 / ticks, e.maxNanos / 1e6, e.calls, e.disabled ? ", SWITCHED OFF after failures" : "")));
        synchronized (RECENT_SPIKES) {
            if (!RECENT_SPIKES.isEmpty()) {
                out.add("Recent spikes:");
                for (String l : RECENT_SPIKES) out.add("  " + l);
            }
        }
        return out;
    }
}
