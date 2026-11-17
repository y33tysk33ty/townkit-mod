package townkit.api.nav;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * A transport stop: a marker item of transport kind {@code kind} in an item frame at {@code framePos}, bound to the
 * village {@code villageId}. {@code tile} is the feet position of the route tile the stop resolves to: the tile under
 * the frame (the frame's own block, or one or two blocks below it), where villagers board and leave rides. TownKit
 * makes stops from the frames players place; addons only read them.
 *
 * @since 0.6.0
 */
public record TransportStop(ResourceLocation kind, UUID villageId, BlockPos framePos, BlockPos tile) {
    public TransportStop {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(villageId, "villageId");
        framePos = Objects.requireNonNull(framePos, "framePos").immutable();
        tile = Objects.requireNonNull(tile, "tile").immutable();
    }
}
