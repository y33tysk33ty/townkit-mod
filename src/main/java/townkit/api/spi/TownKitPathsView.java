package townkit.api.spi;

import java.util.Optional;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;
import townkit.api.nav.PathRule;

/** What {@link townkit.api.nav.TownKitPaths} asks TownKit's backend for. Addons use TownKitPaths instead. */
@ApiStatus.Internal
public interface TownKitPathsView {
    Subscription rule(String owner, Block block, PathRule rule);

    boolean active();

    Optional<PathRule> effective(Block block);
}
