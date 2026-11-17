package townkit.api.shop;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * An addon row for the Mayor's shop, added with {@link TownKitShop#add}. Made with {@link #builder}; immutable.
 *
 * <p>Defaults: the item's own name, no description (the tooltip shows the name), price growth on (Towntopia's growth
 * with village size and with how many this village bought), paid in emeralds, only coins bound to the buyer's village
 * count, and the sold item is bound to that village (both as Towntopia does for its own rows).
 *
 * @since 0.7.0
 */
public final class ShopEntry {
    private final ResourceLocation id;
    private final Supplier<Item> item;
    private final int basePrice;
    @Nullable
    private final Component name;
    @Nullable
    private final Component description;
    private final boolean priceGrowth;
    @Nullable
    private final ShopPrice price;
    private final Supplier<Item> currency;
    private final boolean boundCurrency;
    private final boolean bindSold;
    @Nullable
    private final ResourceLocation requires;
    @Nullable
    private final ShopCondition condition;

    private ShopEntry(Builder b) {
        this.id = b.id;
        this.item = b.item;
        this.basePrice = b.basePrice;
        this.name = b.name;
        this.description = b.description;
        this.priceGrowth = b.priceGrowth;
        this.price = b.price;
        this.currency = b.currency;
        this.boundCurrency = b.boundCurrency;
        this.bindSold = b.bindSold;
        this.requires = b.requires;
        this.condition = b.condition;
    }

    /**
     * Starts an entry selling {@code item} for {@code basePrice}. {@code id} names the row (it is the
     * {@link ShopOffer#id()}); it may not use the {@code towntopia} namespace. Throws NullPointerException on a null
     * argument and IllegalArgumentException on a negative price or a {@code towntopia:} id.
     */
    public static Builder builder(ResourceLocation id, Supplier<Item> item, int basePrice) {
        return new Builder(id, item, basePrice);
    }

    public ResourceLocation id() {
        return this.id;
    }

    public Supplier<Item> item() {
        return this.item;
    }

    public int basePrice() {
        return this.basePrice;
    }

    /** The row's name; empty means the item's own name. */
    public Optional<Component> name() {
        return Optional.ofNullable(this.name);
    }

    /** The hover text; empty means the name. */
    public Optional<Component> description() {
        return Optional.ofNullable(this.description);
    }

    public boolean priceGrowth() {
        return this.priceGrowth;
    }

    public Optional<ShopPrice> price() {
        return Optional.ofNullable(this.price);
    }

    public Supplier<Item> currency() {
        return this.currency;
    }

    public boolean boundCurrency() {
        return this.boundCurrency;
    }

    public boolean bindSold() {
        return this.bindSold;
    }

    public Optional<ResourceLocation> requires() {
        return Optional.ofNullable(this.requires);
    }

    public Optional<ShopCondition> condition() {
        return Optional.ofNullable(this.condition);
    }

    @Override
    public String toString() {
        return "ShopEntry[" + this.id + "]";
    }

    /**
     * Builds a {@link ShopEntry}.
     *
     * @since 0.7.0
     */
    public static final class Builder {
        private final ResourceLocation id;
        private final Supplier<Item> item;
        private final int basePrice;
        @Nullable
        private Component name;
        @Nullable
        private Component description;
        private boolean priceGrowth = true;
        @Nullable
        private ShopPrice price;
        private Supplier<Item> currency = () -> Items.EMERALD;
        private boolean boundCurrency = true;
        private boolean bindSold = true;
        @Nullable
        private ResourceLocation requires;
        @Nullable
        private ShopCondition condition;

        private Builder(ResourceLocation id, Supplier<Item> item, int basePrice) {
            this.id = Objects.requireNonNull(id, "id");
            this.item = Objects.requireNonNull(item, "item");
            if (basePrice < 0) throw new IllegalArgumentException("basePrice is negative: " + basePrice);
            if ("towntopia".equals(id.getNamespace())) throw new IllegalArgumentException("shop entry ids may not use the towntopia namespace: " + id);
            this.basePrice = basePrice;
        }

        /** The row's name (default: the item's own name). Resolved on the client, like an item name. */
        public Builder name(Component name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** The hover text (default: the name). Resolved on the client. */
        public Builder description(Component description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /** Whether Towntopia's price growth applies (default true); false keeps the base price. */
        public Builder priceGrowth(boolean on) {
            this.priceGrowth = on;
            return this;
        }

        /** Works the price out per village and buyer, instead of the base or grown price. */
        public Builder price(ShopPrice price) {
            this.price = Objects.requireNonNull(price, "price");
            return this;
        }

        /**
         * What the row is paid in (default emerald). Meant for an addon's own coin item; TownKit accepts any item.
         * Taking the money and the "not enough" text use it.
         */
        public Builder currency(Supplier<Item> currency) {
            this.currency = Objects.requireNonNull(currency, "currency");
            return this;
        }

        /** Whether only currency bound to the buyer's village counts (default true, as Towntopia's emeralds). */
        public Builder boundCurrency(boolean on) {
            this.boundCurrency = on;
            return this;
        }

        /** Whether the sold item is bound to the village (default true, as Towntopia's tokens). */
        public Builder bindSold(boolean on) {
            this.bindSold = on;
            return this;
        }

        /**
         * Greys the row out until the village has a valid building of this type ({@code towntopia:kitchen}, or a type
         * as {@link townkit.api.building.TownKitBuildings#ofType} names it).
         */
        public Builder requires(ResourceLocation buildingType) {
            this.requires = Objects.requireNonNull(buildingType, "buildingType");
            return this;
        }

        /** Any other check; its answer picks shown, greyed with a reason, or hidden. Checked after {@link #requires}. */
        public Builder condition(ShopCondition condition) {
            this.condition = Objects.requireNonNull(condition, "condition");
            return this;
        }

        public ShopEntry build() {
            return new ShopEntry(this);
        }
    }
}
