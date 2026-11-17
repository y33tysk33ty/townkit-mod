package townkit.core.building;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import townkit.api.Subscription;

/**
 * Which modded blocks stand in for which vanilla blocks, and at which audited check sites each one counts.
 *
 * <p>Writers (register, remove, replaceTagEntries) lock; readers never do. Every change publishes a new immutable,
 * fully precomputed snapshot through a volatile field, so {@link #vanillaFor} and {@link #countsAt} allocate
 * nothing and are safe from any thread.
 *
 * @param <B> block type (strings in unit tests)
 */
public final class StandInTable<B> {
    private static final Logger LOG = LoggerFactory.getLogger("townkit");

    public enum Source { EXPLICIT, COMMON_TAG }

    /** What a stand-in counts at and why it skips the rest (site keys; reasons). */
    public record Report<B>(B modded, B vanilla, Source source, List<String> countsAt,
            Map<String, String> skippedAt) {
    }

    /**
     * Whether an audited site's check is about a vanilla block at all: a {@code Blocks.X} site only for X, an
     * {@code instanceof C} site only for blocks that are a {@code C}. Sites that don't apply are left out of a
     * stand-in's counts and report (they could never pass for it anyway).
     */
    @FunctionalInterface
    public interface SiteFilter<B> {
        boolean applies(String siteKey, B vanilla);
    }

    /**
     * Whether the bridge runs at an audited site at all right now (its check was rewritten, or its class hasn't loaded
     * yet and will be). Read when a report is made, not when a rule is computed: classes load lazily.
     */
    @FunctionalInterface
    public interface SiteStatus {
        /** Every site is live (unit tests). */
        SiteStatus ALL = (siteId, siteKey) -> null;

        /** Null while a stand-in can count at the site; else why it can't (e.g. its check was not rewritten). */
        @Nullable
        String notCountingReason(int siteId, String siteKey);
    }

    /** Told, under the table's lock, whether the published snapshot is non-empty after every change. */
    @FunctionalInterface
    public interface ActiveListener {
        ActiveListener NONE = active -> { };

        void activeChanged(boolean active);
    }

    /**
     * One computed stand-in: immutable. {@code pending}: some NEEDS check failed only because it couldn't be decided
     * yet ({@link BlockChecks#decided}), so a later {@link #recheck} may count it at more sites.
     */
    private record Entry<B>(B vanilla, Source source, BitSet counts, Report<B> report, boolean pending) {
    }

    private static final class Rule<B> {
        final String owner;
        final B modded;
        /** Replaced only under the table's lock (by {@link #recheck}). */
        Entry<B> entry;

        Rule(String owner, B modded, Entry<B> entry) {
            this.owner = owner;
            this.modded = modded;
            this.entry = entry;
        }
    }

    private final SiteVerdicts verdicts;
    private final BlockChecks<B> checks;
    private final SiteFilter<B> filter;
    private final SiteStatus status;
    private final ActiveListener activeListener;
    private final Object lock = new Object();
    private final List<Rule<B>> rules = new ArrayList<>();
    private final Set<String> warnedChecks = new HashSet<>();
    private Map<B, B> tagSource = Map.of();
    private Map<B, Entry<B>> tagEntries = Map.of();
    private volatile Map<B, Entry<B>> snapshot = Map.of();
    private volatile boolean registrations;

    /** Every site applies to every vanilla block (unit tests). */
    public StandInTable(SiteVerdicts verdicts, BlockChecks<B> checks) {
        this(verdicts, checks, (key, vanilla) -> true);
    }

    public StandInTable(SiteVerdicts verdicts, BlockChecks<B> checks, SiteFilter<B> filter) {
        this(verdicts, checks, filter, SiteStatus.ALL, ActiveListener.NONE);
    }

    public StandInTable(SiteVerdicts verdicts, BlockChecks<B> checks, SiteFilter<B> filter, SiteStatus status,
            ActiveListener activeListener) {
        this.verdicts = verdicts;
        this.checks = checks;
        this.filter = filter;
        this.status = status;
        this.activeListener = activeListener;
    }

    public Subscription register(String owner, B modded, B vanilla, Source source) {
        synchronized (lock) {
            for (Rule<B> r : rules) {
                if (r.modded.equals(modded) && !r.entry.vanilla.equals(vanilla)) {
                    LOG.warn("TownKit building: {} wants {} to stand in for {} but {} already made it stand in for {};"
                            + " keeping the first rule", owner, modded, vanilla, r.owner, r.entry.vanilla);
                    return refused();
                }
            }
            Entry<B> entry = compute(modded, vanilla, source);
            if (entry.counts.isEmpty() && !entry.pending) {
                LOG.warn("TownKit building: {}'s rule for {} (as {}) counts at no check site; ignored", owner, modded,
                        vanilla);
                return refused();
            }
            String dead = deadReason(entry);
            if (dead != null) {
                LOG.warn("TownKit building: {}'s rule for {} (as {}) counts at no check site that is rewritten ({}); "
                        + "ignored", owner, modded, vanilla, dead);
                return refused();
            }
            if (entry.pending) {
                LOG.info("TownKit building: {}'s rule for {} (as {}): some checks can't be decided yet (no server "
                        + "level on this thread); kept and decided again on the server thread", owner, modded,
                        vanilla);
            }
            Rule<B> rule = new Rule<>(owner, modded, entry);
            rules.add(rule);
            publish();
            return new Subscription() {
                @Override
                public void remove() {
                    synchronized (lock) {
                        if (rules.remove(rule)) {
                            publish();
                        }
                    }
                }

                @Override
                public boolean isDisabled() {
                    return false;
                }
            };
        }
    }

    private static Subscription refused() {
        return new Subscription() {
            @Override
            public void remove() {
            }

            @Override
            public boolean isDisabled() {
                return true;
            }
        };
    }

    /** Replaces every {@link Source#COMMON_TAG} entry (modded block to vanilla block); explicit rules stay. */
    public void replaceTagEntries(Map<B, B> entries) {
        synchronized (lock) {
            tagSource = Map.copyOf(entries);
            tagEntries = computeTags();
            publish();
        }
    }

    /**
     * Computes every rule and tag entry again with the current {@link BlockChecks} (after they were installed or can
     * now probe more, e.g. once a server level exists). Subscriptions stay valid.
     */
    public void recheck() {
        synchronized (lock) {
            for (Rule<B> r : rules) {
                boolean counted = !r.entry.counts.isEmpty();
                r.entry = compute(r.modded, r.entry.vanilla, r.entry.source);
                if (r.entry.counts.isEmpty() && (counted || !r.entry.pending)) {
                    // kept (its subscription stays live and a later recheck may count it again), but not active
                    LOG.warn("TownKit building: {}'s rule for {} (as {}) now counts at no check site{}", r.owner,
                            r.modded, r.entry.vanilla, r.entry.pending ? " yet" : "; inactive until a recheck changes that");
                }
            }
            tagEntries = computeTags();
            publish();
        }
    }

    /** Caller holds the lock. */
    private Map<B, Entry<B>> computeTags() {
        Map<B, Entry<B>> next = new HashMap<>();
        for (Map.Entry<B, B> e : tagSource.entrySet()) {
            Entry<B> entry = compute(e.getKey(), e.getValue(), Source.COMMON_TAG);
            if (!entry.counts.isEmpty() && deadReason(entry) == null) {
                next.put(e.getKey(), entry); // pending/empty ones stay in tagSource and are computed again on recheck
            }
        }
        return next;
    }

    /**
     * Non-null when the entry counts at sites but every one of them is in a class that is loaded and not rewritten
     * (hooks off, class off, count mismatch, failed rewrite): nothing can ever change that for this JVM, so the
     * rule counts nowhere. Sites in classes not loaded yet report null (they will be rewritten), and an entry that is
     * still pending may yet gain sites, so neither is dead. The result is the first such reason.
     */
    @Nullable
    private String deadReason(Entry<B> entry) {
        if (entry.pending || entry.counts.isEmpty()) {
            return null;
        }
        String first = null;
        for (int i = entry.counts.nextSetBit(0); i >= 0; i = entry.counts.nextSetBit(i + 1)) {
            String why = status.notCountingReason(i, verdicts.key(i));
            if (why == null) {
                return null;
            }
            if (first == null) {
                first = why;
            }
        }
        return first;
    }

    /** Caller holds the lock. Only entries that count somewhere are published; pending/empty rules wait. */
    private void publish() {
        Map<B, Entry<B>> next = new HashMap<>(tagEntries);
        for (int i = rules.size() - 1; i >= 0; i--) {
            Entry<B> e = rules.get(i).entry;
            if (!e.counts.isEmpty()) {
                next.put(rules.get(i).modded, e);
            }
        }
        snapshot = next;
        registrations = !rules.isEmpty() || !tagSource.isEmpty();
        activeListener.activeChanged(!next.isEmpty());
    }

    /**
     * True when any rule or tag entry is registered, counting or not (pending ones too). A volatile read; used to skip
     * rechecks when nothing was ever registered.
     */
    public boolean hasRegistrations() {
        return registrations;
    }

    /** Caller holds the lock. */
    private Entry<B> compute(B modded, B vanilla, Source source) {
        int n = verdicts.size();
        BitSet counts = new BitSet(n);
        List<String> counted = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        Map<String, Boolean> cache = new HashMap<>();
        boolean pending = false;
        for (int i = 0; i < n; i++) {
            SiteVerdicts.Verdict v = verdicts.verdict(i);
            String key = verdicts.key(i);
            if (!filter.applies(key, vanilla)) {
                continue;
            }
            String skip = null;
            switch (v.type()) {
                case SAFE -> {
                }
                case NEEDS -> {
                    String check = v.check();
                    if (check == null || !BlockChecks.KNOWN.contains(check)) {
                        if (warnedChecks.add(String.valueOf(check))) {
                            LOG.warn("TownKit building: site {} needs unknown check '{}'; no stand-in counts there",
                                    key, check);
                        }
                        skip = "unknown check " + check;
                    } else if (!cache.computeIfAbsent(check, c -> checks.passes(c, modded))) {
                        if (checks.decided(check, modded)) {
                            skip = "fails check " + check;
                        } else {
                            skip = "check " + check + " not decided yet";
                            pending = true;
                        }
                    }
                }
                case UNSAFE -> skip = "unsafe site";
                case AUDIT_ONLY -> skip = "audit only";
            }
            if (skip == null) {
                counts.set(i);
                counted.add(key);
            } else {
                skipped.put(key, skip);
            }
        }
        Report<B> report = new Report<>(modded, vanilla, source, List.copyOf(counted),
                java.util.Collections.unmodifiableMap(skipped));
        return new Entry<>(vanilla, source, counts, report, pending);
    }

    @Nullable
    public B vanillaFor(B modded) {
        Entry<B> e = snapshot.get(modded);
        return e == null ? null : e.vanilla;
    }

    /** True when {@code modded} stands in at that audited site; false for ids -1 or out of range. */
    public boolean countsAt(B modded, int siteId) {
        if (siteId < 0) {
            return false;
        }
        Entry<B> e = snapshot.get(modded);
        return e != null && e.counts.get(siteId);
    }

    public boolean isEmpty() {
        return snapshot.isEmpty();
    }

    /**
     * Where {@code modded} counts and why it is skipped elsewhere, as of now: a site whose check the bridge doesn't run
     * at ({@link SiteStatus}) moves from the counted sites to the skipped ones with that reason.
     */
    @Nullable
    public Report<B> report(B modded) {
        Entry<B> e = snapshot.get(modded);
        if (e == null) {
            return null;
        }
        Report<B> r = e.report;
        List<String> counted = null;
        Map<String, String> skipped = null;
        for (int i = 0; i < r.countsAt().size(); i++) {
            String key = r.countsAt().get(i);
            String why = status.notCountingReason(verdicts.siteId(key), key);
            if (why != null && counted == null) {
                counted = new ArrayList<>(r.countsAt().subList(0, i));
                skipped = new LinkedHashMap<>(r.skippedAt());
            }
            if (why != null) {
                skipped.put(key, why);
            } else if (counted != null) {
                counted.add(key);
            }
        }
        if (counted == null) {
            return r;
        }
        return new Report<>(r.modded(), r.vanilla(), r.source(), List.copyOf(counted),
                java.util.Collections.unmodifiableMap(skipped));
    }

    /** Who registered the explicit rule that makes {@code modded} stand in now; null for a tag entry or no rule. */
    @Nullable
    public String ownerOf(B modded) {
        synchronized (lock) {
            for (Rule<B> r : rules) {
                if (r.modded.equals(modded) && !r.entry.counts.isEmpty()) {
                    return r.owner;
                }
            }
            return null;
        }
    }
}
