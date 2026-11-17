package townkit.api.work;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * A dish Towntopia's cook makes: {@code ingredients} in, {@code outputPerBatch} of {@code result} out, per batch. Made
 * with {@link #builder}; immutable ({@link #ingredients()} keeps the order the ingredients were added in and can't be
 * changed).
 *
 * <ul>
 *   <li>{@code heated}: cooked on a heat source (smoker, furnace or campfire, Towntopia's choice) rather than at a crafting
 *   table.</li>
 *   <li>{@code nutrition}: Towntopia sets the food value of every dish its cook makes to this (with saturation 0.6),
 *   0 to 20. {@code 0} keeps the item's own food values (use it for a modded food that has its own).</li>
 *   <li>{@code station}: the id of an addon {@link CookStation} ({@link TownKitCooking#station}). The cook makes the
 *   dish only while a kitchen of its village holds one of the station's blocks, and works at that block the way it
 *   works at a crafting table (Towntopia's table batch time; {@code heated} is not used then). A station nobody
 *   registered: the dish is never made.</li>
 *   <li>{@code stock}: how many of the dish the cook keeps in storage by default (the dish's "Stock" row in the cook's
 *   settings starts at this; players change it per cook; Towntopia scales it with the village). At least 1:
 *   Towntopia never cooks a dish whose stock target is 0.</li>
 * </ul>
 *
 * @param result         what the cook makes (also the dish's key: one dish per result item)
 * @param outputPerBatch how many of {@code result} one batch makes, at least 1
 * @param ingredients    what one batch uses up, each count at least 1; never empty
 * @param heated         cooked on a heat source (true) or at a crafting table (false)
 * @param nutrition      the food value Towntopia gives the made dish, 0 (keep the item's own) to 20
 * @param station        the addon station's id, or null for none
 * @param stock          the default stock target, at least 1 ({@link #DEFAULT_STOCK} unless set)
 * @since 0.7.1
 */
public record Dish(Item result, int outputPerBatch, Map<Item, Integer> ingredients, boolean heated, int nutrition,
        @Nullable ResourceLocation station, int stock) {
    /** The highest nutrition a dish may give (a full hunger bar). */
    public static final int MAX_NUTRITION = 20;
    /** The stock target a dish gets unless its builder sets one (Towntopia's own dishes use 8 to 64). */
    public static final int DEFAULT_STOCK = 16;

    /**
     * Checks and copies the values. Throws NullPointerException on a null result, ingredient map, ingredient or count,
     * and IllegalArgumentException when there are no ingredients, a count, {@code outputPerBatch} or {@code stock} is
     * below 1, or {@code nutrition} is outside 0..20.
     */
    public Dish {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(ingredients, "ingredients");
        if (ingredients.isEmpty()) throw new IllegalArgumentException("a dish needs at least one ingredient");
        if (outputPerBatch < 1) throw new IllegalArgumentException("outputPerBatch must be at least 1: " + outputPerBatch);
        if (stock < 1) throw new IllegalArgumentException("stock must be at least 1: " + stock);
        if (nutrition < 0 || nutrition > MAX_NUTRITION) {
            throw new IllegalArgumentException("nutrition must be 0 to " + MAX_NUTRITION + ": " + nutrition);
        }
        Map<Item, Integer> copy = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : ingredients.entrySet()) {
            Item item = Objects.requireNonNull(e.getKey(), "ingredient");
            int count = Objects.requireNonNull(e.getValue(), "ingredient count");
            if (count < 1) throw new IllegalArgumentException("ingredient counts must be at least 1: " + count);
            copy.put(item, count);
        }
        ingredients = Collections.unmodifiableMap(copy);
    }

    /** Starts a dish making {@code result}. Defaults: output 1, not heated, nutrition 5, no station, stock 16. */
    public static Builder builder(Item result) {
        return new Builder(result);
    }

    /** Builds a {@link Dish}; {@link #build()} checks the values. */
    public static final class Builder {
        private final Item result;
        private int output = 1;
        private final Map<Item, Integer> ingredients = new LinkedHashMap<>();
        private boolean heated;
        private int nutrition = 5;
        @Nullable
        private ResourceLocation station;
        private int stock = DEFAULT_STOCK;

        private Builder(Item result) {
            this.result = Objects.requireNonNull(result, "result");
        }

        /** How many one batch makes (default 1). */
        public Builder output(int outputPerBatch) {
            this.output = outputPerBatch;
            return this;
        }

        /** Adds an ingredient (a second call for the same item replaces its count). */
        public Builder ingredient(Item item, int count) {
            this.ingredients.put(Objects.requireNonNull(item, "item"), count);
            return this;
        }

        /** Cooked on a heat source rather than at a crafting table (default false). */
        public Builder heated(boolean heated) {
            this.heated = heated;
            return this;
        }

        /** The food value Towntopia gives the made dish, 0 (keep the item's own) to 20 (default 5). */
        public Builder nutrition(int nutrition) {
            this.nutrition = nutrition;
            return this;
        }

        /** The id of the addon {@link CookStation} the dish is made at (see {@link Dish}), or null for none. */
        public Builder station(@Nullable ResourceLocation station) {
            this.station = station;
            return this;
        }

        /** The default stock target, at least 1 (default {@link #DEFAULT_STOCK}). */
        public Builder stock(int stock) {
            this.stock = stock;
            return this;
        }

        /**
         * Throws IllegalArgumentException when there are no ingredients, a count, the output or the stock is below 1, or
         * nutrition is outside 0..20.
         */
        public Dish build() {
            return new Dish(this.result, this.output, this.ingredients, this.heated, this.nutrition, this.station, this.stock);
        }
    }
}
