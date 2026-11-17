package townkit.api.shop;

import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitShopView;
import townkit.api.village.Village;

/**
 * The Mayor's shop: addon rows, changes to Towntopia's own rows, and the currency of Towntopia's rows.
 *
 * <p>The list a player sees is Towntopia's rows (in Towntopia's order, minus hidden ones) then the addon rows, in the
 * order they were added. Towntopia's rows no addon touched are sold by Towntopia itself, unchanged; TownKit sells the
 * addon rows and the Towntopia rows whose price, condition or currency an addon changed, following Towntopia's rules:
 * the price is taken from the buyer's inventory (with {@link ShopEntry.Builder#boundCurrency bound currency} only the
 * coins bound to the Mayor's village count), and the sold item is bound to the village. Every purchase, Towntopia's or
 * TownKit's, fires {@link townkit.api.event.TownKitEvents#SHOP_BUYING} (which can cancel it) and, once done,
 * {@link townkit.api.event.TownKitEvents#SHOP_BOUGHT}.
 *
 * <p>The shop screen shows each row's currency, greys unavailable rows with their reason as the tooltip, and shows an
 * addon row's description on hover, on clients that have TownKit. A client without it still buys through the server's
 * rules, but sees emeralds drawn for every row.
 *
 * <p>A row whose item, name, currency, price or condition supplier throws shows greyed out (reason
 * {@code townkit.shop.unavailable}; logged once per row) and isn't sold; a {@link #builtInCurrency} supplier that
 * throws greys every Towntopia row it switched. The shop fails closed: while anything is registered, a TownKit failure
 * during a purchase refuses it ("The Mayor can't sell that right now.") rather than letting Towntopia sell a row an
 * addon changed. The price is counted again after {@link townkit.api.event.TownKitEvents#SHOP_BUYING}: a handler that
 * spends the buyer's money makes the purchase fail as "not enough", nothing taken.
 *
 * <p>Register at mod setup or later, from any thread; {@link #offers} is server thread only. With nothing registered
 * (and no shop event listened to), the shop is Towntopia's own and TownKit attaches nothing to it.
 *
 * @since 0.7.0
 */
public final class TownKitShop {
    private TownKitShop() {
    }

    private static TownKitShopView view() {
        return TownKitBackend.get().shop();
    }

    /**
     * Adds {@code entry} to every village's shop until the subscription is removed. {@code owner} names the addon in
     * logs. Throws IllegalArgumentException if {@code owner} is blank or another live entry has the same id.
     */
    public static Subscription add(String owner, ShopEntry entry) {
        return view().add(owner, entry);
    }

    /**
     * Changes Towntopia's own row for {@code builtIn} (a token such as {@code towntopia:farmer_token}) until the
     * subscription is removed. Throws IllegalArgumentException if {@code owner} is blank or Towntopia's shop has no
     * row for that item.
     */
    public static Subscription adjust(String owner, Item builtIn, ShopAdjust adjust) {
        return view().adjust(owner, builtIn, adjust);
    }

    /**
     * Makes Towntopia's own shop rows cost {@code coin} instead of emeralds (bound to the village, like Towntopia's
     * emeralds) until the subscription is removed. Only the shop rows: the replacement Town Hall token stays on
     * emeralds. When several addons do this, the last one registered wins and TownKit logs a warning; removing it brings
     * the previous one back. Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription builtInCurrency(String owner, Supplier<Item> coin) {
        return view().builtInCurrency(owner, coin);
    }

    /**
     * What {@code buyer} would see in {@code village}'s shop right now, in order (Towntopia's rows, then the addon rows
     * in the order they were added); greyed rows included, hidden ones left out. {@code buyer} may be null (no buyer:
     * Towntopia then doesn't count carried tokens in its prices, and conditions see no buyer). Empty for a village
     * that's gone. Server thread only (throws IllegalStateException elsewhere).
     */
    public static List<ShopOffer> offers(Village village, @Nullable ServerPlayer buyer) {
        return view().offers(village, buyer);
    }
}
