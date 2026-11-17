package townkit.core.hook;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import townkit.api.hooks.HookListener;
import townkit.api.hooks.HookPoint;
import townkit.api.hooks.HookRegistration;
import townkit.core.perf.PerfMonitor;

/**
 * Runtime side of the hooks. The bytecode injected into Towntopia does one array read per call when nothing listens:
 * {@code if ((Hooks.STATE[id] & bit) != 0) ...} (see HookInjector). Listener arrays are copy-on-write, so dispatch never locks.
 * Internal: addons use {@link townkit.api.hooks.TownKitHooks}.
 */
public final class Hooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_FAILURES = 10;
    static final int BEFORE = HookInjector.BEFORE_BIT;
    static final int AFTER = HookInjector.AFTER_BIT;
    /** Set on every hook while a call census runs (see Census). */
    static final int CENSUS = HookInjector.CENSUS_BIT;

    private static final HookInfo[] INFO = HookManifest.get().hooks;
    /** Bit 1: has before-listeners. Bit 2: has after-listeners. Bit 4: census. Read by the generated code on every call. */
    public static final byte[] STATE = new byte[INFO.length];
    private static final Entry[][] BEFORE_LISTENERS = new Entry[INFO.length][];
    private static final Entry[][] AFTER_LISTENERS = new Entry[INFO.length][];

    private Hooks() {
    }

    /** A registered listener with its owner (for performance attribution) and failure count. */
    public static final class Entry implements HookRegistration {
        public final HookInfo hook;
        public final String owner;
        public final HookListener listener;
        public final boolean after;
        public volatile boolean disabled;
        public int failures;
        // performance counters (approximate on worker-thread hooks)
        public long calls;
        public long nanos;
        public long maxNanos;

        Entry(HookInfo hook, String owner, HookListener listener, boolean after) {
            this.hook = hook;
            this.owner = owner;
            this.listener = listener;
            this.after = after;
        }

        @Override
        public void remove() {
            Hooks.remove(this);
        }

        @Override
        public boolean isDisabled() {
            return this.disabled;
        }

        @Override
        public long calls() {
            return this.calls;
        }

        @Override
        public long failures() {
            return this.failures;
        }
    }

    /** The public view of each hook, made the first time someone asks for it. */
    private static final HookPoint[] POINTS = new HookPoint[INFO.length];

    public static HookPoint point(HookInfo h) {
        HookPoint p = POINTS[h.id()];
        if (p == null) {
            p = new HookPoint(h.key(), h.returns(), h.isStatic(), h.ctor(), h.client(), h.thread());
            POINTS[h.id()] = p; // a race only makes an equal copy
        }
        return p;
    }

    /** Returned by a before-dispatch when the method should run normally (any other value = return this instead). */
    public static final Object PROCEED = new Object() {
        @Override
        public String toString() {
            return "Hooks.PROCEED";
        }
    };

    /** Calls per hook while a census runs (approximate on worker threads). */
    static volatile long[] calls = new long[0];

    /** Called by the injected listener path while a census runs. */
    public static void count(int id) {
        long[] c = calls;
        if (id < c.length) c[id]++;
    }

    // ---- called by the generated bridges (only when the hook has listeners) ----

    public static Object dispatchBefore(int id, @Nullable Object self, Object[] args) {
        Entry[] ls = BEFORE_LISTENERS[id];
        if (ls == null) {
            return PROCEED;
        }
        HookCallImpl call = new HookCallImpl(INFO[id], self, args, false, null);
        for (Entry e : ls) {
            if (!e.disabled) {
                run(e, call);
                if (call.cancelled()) {
                    return call.value(); // later before-listeners don't run on a cancelled call
                }
            }
        }
        return PROCEED;
    }

    @Nullable
    public static Object dispatchAfter(int id, @Nullable Object self, Object[] args, @Nullable Object returning) {
        if ((STATE[id] & CENSUS) != 0 && INFO[id].ctor()) {
            count(id); // constructors have no listener path of their own: count them here
        }
        Entry[] ls = AFTER_LISTENERS[id];
        if (ls == null) {
            return returning;
        }
        HookCallImpl call = new HookCallImpl(INFO[id], self, args, true, returning);
        for (Entry e : ls) {
            if (!e.disabled) {
                run(e, call);
            }
        }
        return call.value();
    }

    private static void run(Entry e, HookCallImpl call) {
        long t0 = System.nanoTime();
        try {
            e.listener.handle(call);
            e.failures = 0;
        } catch (Throwable t) {
            e.failures++;
            if (e.failures <= 3 || e.failures == MAX_FAILURES) {
                LOGGER.error("[TownKit] listener from '{}' on hook {} ({}) failed ({} in a row)", e.owner, e.hook.key(),
                    e.after ? "after" : "before", e.failures, t);
            }
            if (e.failures >= MAX_FAILURES) {
                e.disabled = true;
                LOGGER.error("[TownKit] listener from '{}' on hook {} switched off after {} failures in a row", e.owner, e.hook.key(), MAX_FAILURES);
            }
        } finally {
            long dt = System.nanoTime() - t0;
            e.calls++;
            e.nanos += dt;
            if (dt > e.maxNanos) {
                e.maxNanos = dt;
            }
            PerfMonitor.listenerTime(e, dt);
        }
    }

    // ---- registration ----

    public static synchronized Entry add(HookInfo hook, String owner, HookListener listener, boolean after) {
        Entry entry = new Entry(hook, owner, listener, after);
        Entry[][] table = after ? AFTER_LISTENERS : BEFORE_LISTENERS;
        Entry[] old = table[hook.id()];
        Entry[] grown = old == null ? new Entry[1] : Arrays.copyOf(old, old.length + 1);
        grown[grown.length - 1] = entry;
        table[hook.id()] = grown;
        STATE[hook.id()] |= (byte) (after ? AFTER : BEFORE);
        return entry;
    }

    public static synchronized void remove(Entry entry) {
        Entry[][] table = entry.after ? AFTER_LISTENERS : BEFORE_LISTENERS;
        Entry[] old = table[entry.hook.id()];
        if (old == null) {
            return;
        }
        Entry[] kept = Arrays.stream(old).filter(e -> e != entry).toArray(Entry[]::new);
        table[entry.hook.id()] = kept.length == 0 ? null : kept;
        if (kept.length == 0) {
            STATE[entry.hook.id()] &= (byte) ~(entry.after ? AFTER : BEFORE);
        }
    }

    public static synchronized java.util.List<Entry> allEntries() {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        for (Entry[] ls : BEFORE_LISTENERS) if (ls != null) out.addAll(Arrays.asList(ls));
        for (Entry[] ls : AFTER_LISTENERS) if (ls != null) out.addAll(Arrays.asList(ls));
        return out;
    }

    public static int hookCount() {
        return INFO.length;
    }

    static synchronized void setCensus(boolean on) {
        if (on) {
            calls = new long[INFO.length];
        }
        for (int i = 0; i < STATE.length; i++) {
            STATE[i] = (byte) (on ? STATE[i] | CENSUS : STATE[i] & ~CENSUS);
        }
    }
}
