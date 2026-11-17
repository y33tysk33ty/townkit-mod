package townkit.core.work;

import com.mojang.logging.LogUtils;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;

/** Logs each distinct problem once (merges rebuild often; the same clash must not repeat). */
public final class WorkLog {
    private static final Logger LOG = LogUtils.getLogger();
    /** Used by tables and files unless a test supplies its own. */
    public static final WorkLog GLOBAL = new WorkLog(line -> LOG.warn("[TownKit] {}", line));

    private final Consumer<String> sink;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /** A log with its own sink (tests capture lines this way). */
    public WorkLog(Consumer<String> sink) {
        this.sink = sink;
    }

    /** Logs {@code message} unless a line with the same {@code key} was logged before. */
    public void once(String key, String message) {
        if (this.seen.add(key)) this.sink.accept(message);
    }

    /** Forgets what was logged, so the next reload reports problems again. */
    public void reset() {
        this.seen.clear();
    }
}
