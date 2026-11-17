package townkit.api.work;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * An addon's cooking station, for show: which block (or block tag) counts as the station, and an optional
 * {@link StationHandler} for visuals. Register it with {@link TownKitCooking#station}; a {@link Dish} names it by id
 * ({@link Dish#station()}). Made with {@link #builder()}; immutable.
 *
 * <p>Towntopia's cook makes a dish with a station only while one of its village's kitchens holds a matching block
 * (TownKit finds them when Towntopia scans the kitchen room, on any block change there). The cook walks to the block,
 * stands at it and works there with Towntopia's own batch timing (a crafting-table batch), taking the ingredients from
 * its own pack as usual. TownKit never puts items into the block or runs it.
 *
 * @since 0.7.1
 */
public final class CookStation {
    @Nullable
    private final Block block;
    @Nullable
    private final TagKey<Block> blocks;
    @Nullable
    private final StationHandler handler;

    private CookStation(@Nullable Block block, @Nullable TagKey<Block> blocks, @Nullable StationHandler handler) {
        this.block = block;
        this.blocks = blocks;
        this.handler = handler;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The station block, or null when the station is a {@linkplain #blocks() tag}. */
    @Nullable
    public Block block() {
        return this.block;
    }

    /** The block tag whose blocks are the station, or null when the station is one {@linkplain #block() block}. */
    @Nullable
    public TagKey<Block> blocks() {
        return this.blocks;
    }

    /** The visuals callback, or null for none. */
    @Nullable
    public StationHandler handler() {
        return this.handler;
    }

    @Override
    public String toString() {
        return "CookStation[" + (this.block != null ? this.block : "#" + Objects.requireNonNull(this.blocks).location()) + "]";
    }

    /** Builds a {@link CookStation}: exactly one of {@link #block} or {@link #blocks} is required. */
    public static final class Builder {
        @Nullable
        private Block block;
        @Nullable
        private TagKey<Block> blocks;
        @Nullable
        private StationHandler handler;

        private Builder() {
        }

        /** One block is the station. */
        public Builder block(Block block) {
            this.block = Objects.requireNonNull(block, "block");
            return this;
        }

        /** Every block in {@code tag} is the station. */
        public Builder blocks(TagKey<Block> tag) {
            this.blocks = Objects.requireNonNull(tag, "tag");
            return this;
        }

        /** Visuals while the cook works there (optional). */
        public Builder handler(@Nullable StationHandler handler) {
            this.handler = handler;
            return this;
        }

        /** Throws IllegalArgumentException unless exactly one of {@link #block} or {@link #blocks} was set. */
        public CookStation build() {
            if ((this.block == null) == (this.blocks == null)) {
                throw new IllegalArgumentException("a cook station needs exactly one of block(...) or blocks(...)");
            }
            return new CookStation(this.block, this.blocks, this.handler);
        }
    }
}
