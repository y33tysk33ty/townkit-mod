package townkit.core.brain;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nullable;
import townkit.api.Subscription;
import townkit.core.TownKit;
import townkit.core.perf.PerfMonitor;

/** A registered choice or interrupt provider, with the same isolation as hook listeners and per-owner timing. */
final class ProviderEntry<P> implements Subscription {
    private static final int MAX_FAILURES = 10;

    final String owner;
    final P provider;
    final int interval;
    private final Runnable onRemove;
    int failures;
    volatile boolean off;

    ProviderEntry(String owner, P provider, int interval, Runnable onRemove) {
        this.owner = owner;
        this.provider = provider;
        this.interval = interval;
        this.onRemove = onRemove;
    }

    /** Read something from the provider (e.g. its activity id); a throw counts as a failure and answers null. */
    @Nullable
    <R> R get(Function<P, R> call) {
        Object[] out = {null};
        this.ask(p -> {
            out[0] = call.apply(p);
            return 0D;
        }, 0D);
        @SuppressWarnings("unchecked")
        R r = (R) out[0];
        return r;
    }

    /** Ask the provider; a throw counts as a failure (ten in a row switch it off) and answers {@code fallback}. */
    double ask(ToDoubleFunction<P> call, double fallback) {
        long t0 = System.nanoTime();
        try {
            double v = call.applyAsDouble(this.provider);
            this.failures = 0;
            return v;
        } catch (RuntimeException e) {
            this.failures++;
            if (this.failures <= 3 || this.failures == MAX_FAILURES) {
                TownKit.LOG.error("[TownKit] provider from '{}' failed ({} in a row)", this.owner, this.failures, e);
            }
            if (this.failures >= MAX_FAILURES) {
                this.off = true;
                TownKit.LOG.error("[TownKit] provider from '{}' switched off after {} failures in a row", this.owner, MAX_FAILURES);
            }
            return fallback;
        } finally {
            PerfMonitor.ownerTime(this.owner, System.nanoTime() - t0);
        }
    }

    @Override
    public void remove() {
        this.onRemove.run();
    }

    @Override
    public boolean isDisabled() {
        return this.off;
    }
}
