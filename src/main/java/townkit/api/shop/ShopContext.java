package townkit.api.shop;

import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import townkit.api.village.Village;

/**
 * What a {@link ShopPrice} or {@link ShopCondition} sees.
 *
 * @param village    the village whose Mayor sells
 * @param buyer      the player looking or buying; null when the list is built without one
 * @param basePrice  the row's base price (an addon row's {@code basePrice}, or Towntopia's catalogue price)
 * @param grownPrice the base price after Towntopia's growth for this village: its size and how many it already owns
 *                   (for an addon row, how many this village bought from the Mayor)
 * @since 0.7.0
 */
public record ShopContext(Village village, @Nullable ServerPlayer buyer, int basePrice, int grownPrice) {
}
