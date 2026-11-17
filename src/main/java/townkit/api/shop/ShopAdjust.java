package townkit.api.shop;

import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A change to one of Towntopia's own shop rows, applied with {@link TownKitShop#adjust}. Made with {@link #builder};
 * immutable. Several adjustments of one row combine: any {@link Builder#hidden hidden} hides it, every condition must
 * pass (the first that doesn't decides), and the last registered price wins.
 *
 * @since 0.7.0
 */
public final class ShopAdjust {
    @Nullable
    private final ShopPrice price;
    @Nullable
    private final ShopCondition condition;
    private final boolean hidden;

    private ShopAdjust(Builder b) {
        this.price = b.price;
        this.condition = b.condition;
        this.hidden = b.hidden;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<ShopPrice> price() {
        return Optional.ofNullable(this.price);
    }

    public Optional<ShopCondition> condition() {
        return Optional.ofNullable(this.condition);
    }

    public boolean hidden() {
        return this.hidden;
    }

    /**
     * Builds a {@link ShopAdjust}.
     *
     * @since 0.7.0
     */
    public static final class Builder {
        @Nullable
        private ShopPrice price;
        @Nullable
        private ShopCondition condition;
        private boolean hidden;

        private Builder() {
        }

        /** Replaces Towntopia's price ({@link ShopContext#grownPrice()} is what Towntopia would ask). */
        public Builder price(ShopPrice price) {
            this.price = Objects.requireNonNull(price, "price");
            return this;
        }

        /** Adds a condition to the row. */
        public Builder condition(ShopCondition condition) {
            this.condition = Objects.requireNonNull(condition, "condition");
            return this;
        }

        /** Takes the row out of the shop (not listed, not buyable). */
        public Builder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        public ShopAdjust build() {
            return new ShopAdjust(this);
        }
    }
}
