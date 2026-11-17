package townkit.api.work;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import townkit.api.village.Villager;

/**
 * One batch at an addon cook station, as {@link StationHandler} sees it.
 *
 * @param level the cook's level
 * @param pos   the station block the cook works at
 * @param cook  the cook
 * @param dish  what the batch makes (the dish's result item)
 * @since 0.7.1
 */
public record StationContext(ServerLevel level, BlockPos pos, Villager cook, Item dish) {
}
