package townkit.api.shop;

/**
 * Works out a shop row's price for one village (and buyer). Runs on the server thread each time the Mayor's list is
 * built or a purchase is checked, so keep it cheap. A negative result counts as 0. A price function that throws makes
 * the row unavailable (greyed, reason {@code townkit.shop.unavailable}); TownKit logs it once per row.
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface ShopPrice {
    int price(ShopContext ctx);
}
