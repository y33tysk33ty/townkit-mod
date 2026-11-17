package townkit.api.work;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * An edit of an existing forge recipe, for {@link TownKitForge#changeRecipe}: each field is optional, and a field left
 * unset keeps the recipe's current value. The cost is one field: setting any material replaces the recipe's whole cost
 * with the ones set here. The gear tier and slot are not changed (add a recipe for that). Made with {@link #builder};
 * immutable.
 *
 * @since 0.7.1
 */
public final class ForgeRecipeChange {
    @Nullable
    private final Integer count;
    @Nullable
    private final Map<ResourceLocation, Integer> cost;
    @Nullable
    private final Boolean smithing;

    private ForgeRecipeChange(Builder b) {
        this.count = b.count;
        this.cost = b.cost.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(b.cost));
        this.smithing = b.smithing;
    }

    /** Starts a change that sets nothing yet. */
    public static Builder builder() {
        return new Builder();
    }

    /** True when the change sets nothing. */
    public boolean isEmpty() {
        return this.count == null && this.cost == null && this.smithing == null;
    }

    /** {@code recipe} with this change made. Throws IllegalArgumentException if the result breaks a {@link ForgeRecipe} rule. */
    public ForgeRecipe apply(ForgeRecipe recipe) {
        return new ForgeRecipe(recipe.result(),
            this.count != null ? this.count : recipe.count(),
            this.cost != null ? this.cost : recipe.cost(),
            recipe.tier(), recipe.slot(),
            this.smithing != null ? this.smithing : recipe.smithing());
    }

    /** Builds a {@link ForgeRecipeChange}; {@link #build()} checks the values. */
    public static final class Builder {
        @Nullable
        private Integer count;
        private final Map<ResourceLocation, Integer> cost = new LinkedHashMap<>();
        @Nullable
        private Boolean smithing;

        private Builder() {
        }

        /** How many one job makes. */
        public Builder count(int count) {
            this.count = count;
            return this;
        }

        /** Sets a material's amount; any material set replaces the recipe's whole cost. */
        public Builder cost(ResourceLocation material, int amount) {
            this.cost.put(Objects.requireNonNull(material, "material"), amount);
            return this;
        }

        /** Made at the smithing table (true) or the anvil (false). */
        public Builder smithing(boolean smithing) {
            this.smithing = smithing;
            return this;
        }

        /** Throws IllegalArgumentException for a count or amount below 1. */
        public ForgeRecipeChange build() {
            if (this.count != null && this.count < 1) throw new IllegalArgumentException("count must be at least 1: " + this.count);
            for (Map.Entry<ResourceLocation, Integer> e : this.cost.entrySet()) {
                if (e.getValue() < 1) throw new IllegalArgumentException("material amounts must be at least 1: " + e.getKey() + " " + e.getValue());
            }
            return new ForgeRecipeChange(this);
        }
    }
}
