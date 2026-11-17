package townkit.api.work;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * An edit of an existing dish, for {@link TownKitCooking#changeDish}: each field is optional, and a field left unset
 * keeps the dish's current value. Ingredients are one field: setting any ingredient replaces the dish's whole ingredient
 * list with the ones set here (in the order set). Made with {@link #builder}; immutable.
 *
 * @since 0.7.1
 */
public final class DishChange {
    @Nullable
    private final Integer output;
    @Nullable
    private final Map<Item, Integer> ingredients;
    @Nullable
    private final Boolean heated;
    @Nullable
    private final Integer nutrition;
    private final boolean stationSet;
    @Nullable
    private final ResourceLocation station;
    @Nullable
    private final Integer stock;

    private DishChange(Builder b) {
        this.output = b.output;
        this.ingredients = b.ingredients.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(b.ingredients));
        this.heated = b.heated;
        this.nutrition = b.nutrition;
        this.stationSet = b.stationSet;
        this.station = b.station;
        this.stock = b.stock;
    }

    /** Starts a change that sets nothing yet. */
    public static Builder builder() {
        return new Builder();
    }

    /** True when the change sets nothing. */
    public boolean isEmpty() {
        return this.output == null && this.ingredients == null && this.heated == null && this.nutrition == null && !this.stationSet
            && this.stock == null;
    }

    /** {@code dish} with this change made. Throws IllegalArgumentException if the result breaks a {@link Dish} rule. */
    public Dish apply(Dish dish) {
        return new Dish(dish.result(),
            this.output != null ? this.output : dish.outputPerBatch(),
            this.ingredients != null ? this.ingredients : dish.ingredients(),
            this.heated != null ? this.heated : dish.heated(),
            this.nutrition != null ? this.nutrition : dish.nutrition(),
            this.stationSet ? this.station : dish.station(),
            this.stock != null ? this.stock : dish.stock());
    }

    /** Builds a {@link DishChange}; {@link #build()} checks the values. */
    public static final class Builder {
        @Nullable
        private Integer output;
        private final Map<Item, Integer> ingredients = new LinkedHashMap<>();
        @Nullable
        private Boolean heated;
        @Nullable
        private Integer nutrition;
        private boolean stationSet;
        @Nullable
        private ResourceLocation station;
        @Nullable
        private Integer stock;

        private Builder() {
        }

        /** How many one batch makes. */
        public Builder output(int outputPerBatch) {
            this.output = outputPerBatch;
            return this;
        }

        /** Sets an ingredient; any ingredient set replaces the dish's whole ingredient list. */
        public Builder ingredient(Item item, int count) {
            this.ingredients.put(Objects.requireNonNull(item, "item"), count);
            return this;
        }

        /** Cooked on a heat source (true) or at a crafting table (false). */
        public Builder heated(boolean heated) {
            this.heated = heated;
            return this;
        }

        /** The food value Towntopia gives the made dish, 0 (keep the item's own) to 20. */
        public Builder nutrition(int nutrition) {
            this.nutrition = nutrition;
            return this;
        }

        /** Sets the addon station's id ({@link CookStation}); {@code null} takes the dish's station away. */
        public Builder station(@Nullable ResourceLocation station) {
            this.stationSet = true;
            this.station = station;
            return this;
        }

        /** The default stock target (the dish's stock row starts at it), at least 1. */
        public Builder stock(int stock) {
            this.stock = stock;
            return this;
        }

        /** Throws IllegalArgumentException for an output, ingredient count or stock below 1, or nutrition outside 0..20. */
        public DishChange build() {
            if (this.output != null && this.output < 1) {
                throw new IllegalArgumentException("outputPerBatch must be at least 1: " + this.output);
            }
            if (this.stock != null && this.stock < 1) throw new IllegalArgumentException("stock must be at least 1: " + this.stock);
            for (int count : this.ingredients.values()) {
                if (count < 1) throw new IllegalArgumentException("ingredient counts must be at least 1: " + count);
            }
            if (this.nutrition != null && (this.nutrition < 0 || this.nutrition > Dish.MAX_NUTRITION)) {
                throw new IllegalArgumentException("nutrition must be 0 to " + Dish.MAX_NUTRITION + ": " + this.nutrition);
            }
            return new DishChange(this);
        }
    }
}
