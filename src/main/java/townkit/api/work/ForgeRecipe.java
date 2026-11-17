package townkit.api.work;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Something Towntopia's smith forges: {@code count} of {@code result} from the materials in {@code cost}. Made with
 * {@link #builder}; immutable ({@link #cost()} keeps the order the materials were added in).
 *
 * <ul>
 *   <li>{@code cost}: smith materials by id ({@link TownKitForge#material}; Towntopia's are {@code towntopia:cobble},
 *   {@code towntopia:iron}, {@code towntopia:leather}, {@code towntopia:diamond}, {@code towntopia:netherite},
 *   {@code towntopia:template}, {@code towntopia:netherrack}, {@code towntopia:wood}), each amount at least 1. A recipe
 *   naming a material nobody registered is skipped (logged once).</li>
 *   <li>{@code tier} and {@code slot} ({@link Builder#gear}): the recipe makes gear of that tier
 *   ({@link TownKitForge#tier}); the smith makes it for the villagers who use that slot when they need one or an
 *   upgrade, and Towntopia rates the item at that tier. Without them the recipe makes a stock item, forged when a
 *   player orders it (Mayor stock orders). A recipe naming a tier nobody registered is skipped (logged once).</li>
 *   <li>{@code smithing}: worked at the forge's smithing table (like Towntopia's netherite gear) instead of the anvil;
 *   the smith makes it only while its forge has a smithing table. It follows Towntopia's netherite rule: the previous
 *   tier's item is <b>not</b> used up. The smith uses up only the materials in {@code cost} (for a netherite-like
 *   upgrade, list the template and the upgrade material there).</li>
 * </ul>
 *
 * @param result   what the smith makes (also the recipe's key: one recipe per result item)
 * @param count    how many one job makes, at least 1
 * @param cost     materials by id to amount, at least one
 * @param tier     the gear tier, or null for a stock item
 * @param slot     the gear slot, or null for a stock item (set together with {@code tier})
 * @param smithing made at the smithing table
 * @since 0.7.1
 */
public record ForgeRecipe(Item result, int count, Map<ResourceLocation, Integer> cost, @Nullable ResourceLocation tier,
        @Nullable GearSlot slot, boolean smithing) {
    /**
     * Checks and copies the values. Throws NullPointerException on a null result, cost map, material or amount, and
     * IllegalArgumentException when {@code cost} is empty, an amount or {@code count} is below 1, or only one of
     * {@code tier} and {@code slot} is set.
     */
    public ForgeRecipe {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(cost, "cost");
        if (cost.isEmpty()) throw new IllegalArgumentException("a forge recipe needs at least one material");
        if (count < 1) throw new IllegalArgumentException("count must be at least 1: " + count);
        if ((tier == null) != (slot == null)) throw new IllegalArgumentException("gear needs both a tier and a slot");
        Map<ResourceLocation, Integer> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Integer> e : cost.entrySet()) {
            Objects.requireNonNull(e.getKey(), "material");
            int n = Objects.requireNonNull(e.getValue(), "amount");
            if (n < 1) throw new IllegalArgumentException("material amounts must be at least 1: " + e.getKey() + " " + n);
            copy.put(e.getKey(), n);
        }
        cost = Collections.unmodifiableMap(copy);
    }

    /** Starts a recipe for {@code result}: count 1, no gear, not at the smithing table. */
    public static Builder builder(Item result) {
        return new Builder(Objects.requireNonNull(result, "result"));
    }

    /** True when the recipe makes gear (it has a tier and a slot). */
    public boolean isGear() {
        return this.tier != null;
    }

    /** Builds a {@link ForgeRecipe}; {@link #build()} checks the values. */
    public static final class Builder {
        private final Item result;
        private int count = 1;
        private final Map<ResourceLocation, Integer> cost = new LinkedHashMap<>();
        @Nullable
        private ResourceLocation tier;
        @Nullable
        private GearSlot slot;
        private boolean smithing;

        private Builder(Item result) {
            this.result = result;
        }

        /** How many one job makes (default 1). */
        public Builder count(int count) {
            this.count = count;
            return this;
        }

        /** Adds {@code amount} of {@code material} to the cost (repeatable; the same material again replaces its amount). */
        public Builder cost(ResourceLocation material, int amount) {
            this.cost.put(Objects.requireNonNull(material, "material"), amount);
            return this;
        }

        /** Makes the recipe gear of {@code tier} for {@code slot}. */
        public Builder gear(ResourceLocation tier, GearSlot slot) {
            this.tier = Objects.requireNonNull(tier, "tier");
            this.slot = Objects.requireNonNull(slot, "slot");
            return this;
        }

        /**
         * Made at the smithing table (default false: at the anvil), only while the forge has one. As with Towntopia's
         * netherite gear, the previous tier's item is not used up: only the cost's materials are.
         */
        public Builder smithing(boolean smithing) {
            this.smithing = smithing;
            return this;
        }

        /** Throws IllegalArgumentException when no material was added, or a count or amount is below 1. */
        public ForgeRecipe build() {
            return new ForgeRecipe(this.result, this.count, this.cost, this.tier, this.slot, this.smithing);
        }
    }
}
