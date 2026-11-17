package townkit.api.shop;

import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * One row of the Mayor's shop as a buyer sees it.
 *
 * @param id        the addon entry's id, or {@code towntopia:<item path>} for Towntopia's own rows
 * @param item      what the row sells
 * @param name      the row's name
 * @param price     how many {@code currency} items it costs right now
 * @param currency  what it is paid in ({@code minecraft:emerald} unless an addon changed it)
 * @param available false for a greyed row, which can't be bought
 * @param reason    why a greyed row is greyed; null for an available row
 * @since 0.7.0
 */
public record ShopOffer(ResourceLocation id, Item item, Component name, int price, Item currency, boolean available, @Nullable Component reason) {
}
