package townkit.api.farm;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * A block or a block tag, the way Towntopia's crop files name blocks ({@code "mymod:bush"} or {@code "#mymod:bushes"}).
 * Exactly one of {@code block} and {@code tag} is set.
 *
 * @since 0.8.0
 */
public record BlockRef(@Nullable Block block, @Nullable TagKey<Block> tag) {
    public BlockRef {
        if ((block == null) == (tag == null)) throw new IllegalArgumentException("a BlockRef is a block or a tag, not both or neither");
    }

    public static BlockRef of(Block block) {
        return new BlockRef(Objects.requireNonNull(block, "block"), null);
    }

    public static BlockRef ofTag(TagKey<Block> tag) {
        return new BlockRef(null, Objects.requireNonNull(tag, "tag"));
    }

    /** Whether {@code candidate} is this block, or in this tag (once the server's tags are loaded). */
    public boolean matches(Block candidate) {
        return this.block != null ? this.block == candidate : candidate.builtInRegistryHolder().is(this.tag);
    }
}
