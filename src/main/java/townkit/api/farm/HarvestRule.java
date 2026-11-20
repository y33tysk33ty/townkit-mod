package townkit.api.farm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * When a crop block Towntopia already knows (from any {@code farmer_crops} file) counts as ripe, and what the farmer does
 * to harvest it ({@link TownKitFarming#harvest}). Towntopia still finds, plans, plants and shows the crop.
 *
 * @param id the rule's id (an addon's own name; data files give it as {@code "id"})
 * @param blocks the blocks the rule is for, at least one
 * @param ripe when the block counts as ripe ({@link Ripe.Towntopia}: as before)
 * @param action how the farmer harvests it ({@link HarvestAction.Break}: as before)
 * @param away what the plot gives while the village is away; none: nothing is rolled
 * @since 0.8.0
 */
public record HarvestRule(ResourceLocation id, List<BlockRef> blocks, Ripe ripe, HarvestAction action, Optional<AwayYield> away) {
    public HarvestRule {
        Objects.requireNonNull(id, "id");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (blocks.isEmpty()) throw new IllegalArgumentException("a harvest rule needs at least one block");
        Objects.requireNonNull(ripe, "ripe");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(away, "away");
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(Objects.requireNonNull(id, "id"));
    }

    public Builder toBuilder() {
        Builder b = new Builder(this.id);
        b.blocks.addAll(this.blocks);
        b.ripe = this.ripe;
        b.action = this.action;
        b.away = this.away;
        return b;
    }

    /** Builds a rule; the defaults are Towntopia's own ripe rule, its own harvest and no away yield. */
    public static final class Builder {
        private final ResourceLocation id;
        private final List<BlockRef> blocks = new ArrayList<>();
        private Ripe ripe = new Ripe.Towntopia();
        private HarvestAction action = new HarvestAction.Break();
        private Optional<AwayYield> away = Optional.empty();

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        public Builder block(Block block) {
            this.blocks.add(BlockRef.of(block));
            return this;
        }

        public Builder blockTag(TagKey<Block> tag) {
            this.blocks.add(BlockRef.ofTag(tag));
            return this;
        }

        /** Replaces the blocks. */
        public Builder blocks(List<BlockRef> refs) {
            this.blocks.clear();
            this.blocks.addAll(refs);
            return this;
        }

        public Builder ripe(Ripe ripe) {
            this.ripe = Objects.requireNonNull(ripe, "ripe");
            return this;
        }

        public Builder action(HarvestAction action) {
            this.action = Objects.requireNonNull(action, "action");
            return this;
        }

        public Builder away(AwayYield away) {
            this.away = Optional.of(Objects.requireNonNull(away, "away"));
            return this;
        }

        public Builder noAway() {
            this.away = Optional.empty();
            return this;
        }

        /** Throws IllegalArgumentException if no block was given. */
        public HarvestRule build() {
            return new HarvestRule(this.id, this.blocks, this.ripe, this.action, this.away);
        }
    }
}
