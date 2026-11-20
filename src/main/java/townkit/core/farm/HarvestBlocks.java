package townkit.core.farm;

import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * What the harvest rules need to know about blocks: the registry in game, a hand-made one in the unit tests.
 *
 * @since 0.8.0
 */
public interface HarvestBlocks {
    /** The block with this id, or null when there is none (air counts as none). */
    @Nullable
    Block byId(ResourceLocation id);

    ResourceLocation id(Block block);

    /** Hands every block in the tag to {@code out} (none while the server's tags are not loaded). */
    void forEachInTag(TagKey<Block> tag, Consumer<Block> out);

    /** The block's state property of this name, or null. */
    @Nullable
    Property<?> property(Block block, String name);

    /** The game's blocks. */
    HarvestBlocks REGISTRY = new HarvestBlocks() {
        @Nullable
        @Override
        public Block byId(ResourceLocation id) {
            Block b = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            return b == Blocks.AIR ? null : b;
        }

        @Override
        public ResourceLocation id(Block block) {
            return BuiltInRegistries.BLOCK.getKey(block);
        }

        @Override
        public void forEachInTag(TagKey<Block> tag, Consumer<Block> out) {
            BuiltInRegistries.BLOCK.getTag(tag).ifPresent(set -> set.forEach(h -> out.accept(h.value())));
        }

        @Nullable
        @Override
        public Property<?> property(Block block, String name) {
            return block.getStateDefinition().getProperty(name);
        }
    };
}
