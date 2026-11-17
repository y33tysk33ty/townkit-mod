package townkit.core.nav;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import townkit.api.Subscription;
import townkit.api.nav.PathRule;
import townkit.api.spi.TownKitPathsView;

/**
 * Path rules for addons ({@link townkit.api.nav.TownKitPaths}): owns the one {@link PathRuleTable} and publishes each
 * change to {@link NavBridge}, which Towntopia's rewritten nav sites read. TownKit itself registers no rule. Also
 * registers TownKit's ready-made {@link BubbleElevator} kind, disabled.
 */
public final class CorePaths implements TownKitPathsView {
    private static final Logger LOG = LogUtils.getLogger();

    private final PathRuleTable<Block> table = new PathRuleTable<>(NavBridge::publish);
    private final AtomicBoolean warnedHooksOff = new AtomicBoolean();

    public CorePaths() {
        // ready-made, off until a mod enables it with its own marker (TownKit adds no items)
        TransportKinds.GLOBAL.builtIn("townkit", new BubbleElevator());
    }

    @Override
    public Subscription rule(String owner, Block block, PathRule rule) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(rule, "rule");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner is blank");
        }
        if (RewrittenNavSites.GLOBAL.hooksOff() && warnedHooksOff.compareAndSet(false, true)) {
            LOG.warn("TownKit paths: {} registered a path rule, but every Towntopia hook is switched off in "
                    + "townkit-hooks.properties, so Towntopia's route checks aren't rewritten and no path rule applies", owner);
        }
        return table.add(owner, block, rule);
    }

    @Override
    public boolean active() {
        return NavBridge.ACTIVE;
    }

    @Override
    public Optional<PathRule> effective(Block block) {
        return table.combined(Objects.requireNonNull(block, "block"));
    }
}
