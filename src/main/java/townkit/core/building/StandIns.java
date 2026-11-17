package townkit.core.building;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * The bridge Towntopia's rewritten block checks call (see {@link CheckSiteRewriter}). Towntopia's own check stays in
 * place and runs first (so another mod's mixin aimed at it still applies); the bridge gets its answer, and only when
 * that is false, and a stand-in rule exists, does it look at the stand-in table.
 *
 * <p>The table is created on first use (class-holder idiom: lazy and thread-safe) from the shipped verdicts and the
 * {@link BlockChecks} given to {@link #install}. Until something is installed no NEEDS check passes, so only SAFE sites
 * can count. If the verdict file cannot be read the table is empty for good and the checks behave as Towntopia's.
 *
 * <p>Fast path: {@link #afterIs} and {@link #afterInstanceOf} return a true original at once, and when it is false read
 * one static boolean, {@link #ACTIVE}; they never touch the table while it is false. The table sets the flag whenever
 * it publishes (true iff its snapshot is non-empty), under its lock, so it can't drift out of sync with the rules. Both
 * methods stay under HotSpot's 35-byte inlining limit, so the JIT inlines them into Towntopia's checks.
 */
public final class StandIns {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockChecks<Block> NONE = (check, block) -> false;
    private static volatile BlockChecks<Block> checks = NONE;
    private static volatile boolean created;
    /** Site target (a {@code Blocks} field name or an internal class name) to what it resolves to; empty = unknown. */
    private static final Map<String, Optional<Object>> TARGETS = new ConcurrentHashMap<>();

    private StandIns() {
    }

    /** True iff the stand-in table's snapshot is non-empty (set by the table on every publish). */
    static volatile boolean ACTIVE;

    private static final class Holder {
        static final StandInTable<Block> TABLE = create();

        /** Never throws: a failure here would turn every rewritten Towntopia check into a NoClassDefFoundError. */
        private static StandInTable<Block> create() {
            StandInTable<Block> t;
            try {
                t = build();
            } catch (Throwable e) {
                LOGGER.error("TownKit building: could not set up the stand-in table; no modded block will stand in "
                        + "for a vanilla one", e);
                t = new StandInTable<>(SiteVerdicts.of(List.of(), List.of()), NONE, (key, vanilla) -> false);
            }
            created = true;
            return t;
        }

        private static StandInTable<Block> build() {
            SiteVerdicts v;
            try {
                v = SiteVerdicts.load();
            } catch (Throwable t) {
                LOGGER.error("TownKit building: could not read {}; no modded block will stand in for a vanilla one",
                        SiteVerdicts.RESOURCE, t);
                v = SiteVerdicts.of(List.of(), List.of());
            }
            // reads the installed checks on every computation, so install() also works after the table exists
            BlockChecks<Block> delegate = new BlockChecks<>() {
                @Override
                public boolean passes(String check, Block block) {
                    return checks.passes(check, block);
                }

                @Override
                public boolean decided(String check, Block block) {
                    return checks.decided(check, block);
                }
            };
            return new StandInTable<>(v, delegate, StandIns::siteApplies, RewrittenSites.GLOBAL::notCountingReason,
                    active -> ACTIVE = active);
        }
    }

    /**
     * Sets the Minecraft-backed behaviour checks NEEDS verdicts use, and computes any rule that already exists again
     * with them (rules computed before an install saw every NEEDS check fail).
     */
    public static void install(BlockChecks<Block> blockChecks) {
        checks = blockChecks == null ? NONE : blockChecks;
        if (created) {
            Holder.TABLE.recheck();
        }
    }

    /**
     * Computes every rule and tag entry again if any was ever registered (pending ones included). Never creates the
     * table, so it is free when nothing was registered. Callers run it on the server thread.
     */
    public static void recheckIfRegistered() {
        if (created && Holder.TABLE.hasRegistrations()) {
            Holder.TABLE.recheck();
        }
    }

    /**
     * Whether audited site {@code key} ({@code owner#method#kind#target}) checks for {@code vanilla} at all: a
     * {@code Blocks.X} site only for X, an {@code instanceof C} site only for a {@code C}. A target that doesn't
     * resolve applies to nothing (logged once).
     */
    static boolean siteApplies(String key, Block vanilla) {
        int k = key.lastIndexOf('#');
        int j = k <= 0 ? -1 : key.lastIndexOf('#', k - 1);
        if (j < 0) {
            return false;
        }
        String kind = key.substring(j + 1, k);
        String target = key.substring(k + 1);
        Optional<Object> resolved = TARGETS.computeIfAbsent(kind.equals("INSTANCEOF") ? "class:" + target
                : "field:" + target, id -> resolve(kind, target));
        if (resolved.isEmpty()) {
            return false;
        }
        Object r = resolved.get();
        return r instanceof Class<?> c ? c.isInstance(vanilla) : r == vanilla;
    }

    private static Optional<Object> resolve(String kind, String target) {
        try {
            if (kind.equals("INSTANCEOF")) {
                return Optional.of(Class.forName(target.replace('/', '.'), false, Block.class.getClassLoader()));
            }
            return Optional.ofNullable(Blocks.class.getField(target).get(null));
        } catch (Throwable t) {
            LOGGER.warn("TownKit building: check-site target {} {} doesn't resolve; no stand-in counts there", kind,
                    target);
            return Optional.empty();
        }
    }

    public static StandInTable<Block> table() {
        return Holder.TABLE;
    }

    /** True while any stand-in rule exists. */
    public static boolean active() {
        return ACTIVE;
    }

    /** Whether the table exists yet (unit tests: the fast path must not create it). */
    static boolean tableCreated() {
        return created;
    }

    /**
     * Runs right after Towntopia's own {@code s.is(b)}, which answered {@code original} (another mod's mixin may have
     * wrapped or redirected that call; its answer is kept): true when that is, or when {@code s}'s block stands in for
     * {@code b} at {@code site}.
     */
    public static boolean afterIs(BlockState s, @Nullable Block b, boolean original, int site) {
        return original || ACTIVE && slowIs(s, b, site);
    }

    private static boolean slowIs(BlockState s, @Nullable Block b, int site) {
        StandInTable<Block> t = Holder.TABLE;
        Block modded = s.getBlock();
        if (modded == null) {
            return false;
        }
        Block vanilla = t.vanillaFor(modded);
        return vanilla != null && vanilla == b && t.countsAt(modded, site);
    }

    /**
     * Runs right after Towntopia's own {@code o instanceof c}, which answered {@code original} (kept, as in
     * {@link #afterIs}): true when that is, or when {@code o} is a block standing in for a vanilla block that is a
     * {@code c}, at {@code site}. Null is never a stand-in.
     */
    public static boolean afterInstanceOf(@Nullable Object o, boolean original, Class<?> c, int site) {
        return original || ACTIVE && slowInstanceOf(o, c, site);
    }

    private static boolean slowInstanceOf(@Nullable Object o, Class<?> c, int site) {
        if (!(o instanceof Block modded)) {
            return false;
        }
        StandInTable<Block> t = Holder.TABLE;
        Block vanilla = t.vanillaFor(modded);
        return vanilla != null && c.isInstance(vanilla) && t.countsAt(modded, site);
    }
}
