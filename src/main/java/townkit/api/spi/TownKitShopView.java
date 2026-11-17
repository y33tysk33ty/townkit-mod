package townkit.api.spi;

import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;
import townkit.api.shop.ShopAdjust;
import townkit.api.shop.ShopEntry;
import townkit.api.shop.ShopOffer;
import townkit.api.village.Village;

/** What {@link townkit.api.shop.TownKitShop} asks TownKit's backend for. Addons use TownKitShop instead. */
@ApiStatus.Internal
public interface TownKitShopView {
    Subscription add(String owner, ShopEntry entry);

    Subscription adjust(String owner, Item builtIn, ShopAdjust adjust);

    Subscription builtInCurrency(String owner, Supplier<Item> coin);

    List<ShopOffer> offers(Village village, @Nullable ServerPlayer buyer);
}
