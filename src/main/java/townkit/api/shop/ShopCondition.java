package townkit.api.shop;

/**
 * Decides whether a shop row is shown, shown greyed out with a reason, or hidden, for one village (and buyer). Runs on
 * the server thread each time the Mayor's list is built or a purchase is checked, so keep it cheap. A condition that
 * throws (or returns null) makes the row unavailable (greyed, reason {@code townkit.shop.unavailable}); TownKit logs it
 * once per row. The same goes for a row's item, name, currency or price supplier that throws.
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface ShopCondition {
    ShopCheck check(ShopContext ctx);
}
