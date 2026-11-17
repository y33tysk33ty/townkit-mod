package townkit.api.village;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * A building as it was when read: a plain copy, safe to keep.
 *
 * @param frameId the building's item-frame token id (Towntopia identifies buildings by it)
 * @param type    for example {@code towntopia:kitchen} or {@code towntopia:town_hall}
 * @param door    the building's door
 * @since 0.3.0
 */
public record BuildingInfo(UUID frameId, ResourceLocation type, BlockPos door) {
}
