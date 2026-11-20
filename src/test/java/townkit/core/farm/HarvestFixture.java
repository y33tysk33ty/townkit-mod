package townkit.core.farm;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/** Bare blocks and items (identity is all the harvest rules need without a game), and a registry of them. */
final class HarvestFixture {
    private static final sun.misc.Unsafe UNSAFE = unsafe();

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A bare block that names itself and has the given properties. */
    static final class TestBlock extends Block {
        ResourceLocation id;
        Map<String, Property<?>> properties;

        private TestBlock() {
            super(null);
        }

        @Override
        public String toString() {
            return String.valueOf(this.id);
        }
    }

    static final class TestItem extends Item {
        ResourceLocation id;

        private TestItem() {
            super(null);
        }

        @Override
        public String toString() {
            return String.valueOf(this.id);
        }
    }

    final Map<ResourceLocation, Block> blocks = new HashMap<>();
    final Map<ResourceLocation, Item> items = new HashMap<>();
    final Map<ResourceLocation, List<Block>> tags = new HashMap<>();

    TestBlock block(String id, Property<?>... properties) {
        try {
            TestBlock b = (TestBlock) UNSAFE.allocateInstance(TestBlock.class);
            b.id = ResourceLocation.parse(id);
            b.properties = new HashMap<>();
            for (Property<?> p : properties) b.properties.put(p.getName(), p);
            this.blocks.put(b.id, b);
            return b;
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }

    TestBlock bush(String id) {
        return block(id, IntegerProperty.create("age", 0, 3));
    }

    TestBlock lit(String id) {
        return block(id, BooleanProperty.create("lit"));
    }

    Item item(String id) {
        try {
            TestItem i = (TestItem) UNSAFE.allocateInstance(TestItem.class);
            i.id = ResourceLocation.parse(id);
            this.items.put(i.id, i);
            return i;
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }

    void tag(String id, Block... members) {
        this.tags.put(ResourceLocation.parse(id), new ArrayList<>(List.of(members)));
    }

    /** The blocks as the rules see them. */
    HarvestBlocks blockAccess() {
        return new HarvestBlocks() {
            @Nullable
            @Override
            public Block byId(ResourceLocation id) {
                return HarvestFixture.this.blocks.get(id);
            }

            @Override
            public ResourceLocation id(Block block) {
                return ((TestBlock) block).id;
            }

            @Override
            public void forEachInTag(TagKey<Block> tag, Consumer<Block> out) {
                List<Block> members = HarvestFixture.this.tags.get(tag.location());
                if (members != null) members.forEach(out);
            }

            @Nullable
            @Override
            public Property<?> property(Block block, String name) {
                return ((TestBlock) block).properties.get(name);
            }
        };
    }

    SeedCodec.ItemIds itemAccess() {
        return new SeedCodec.ItemIds() {
            @Nullable
            @Override
            public Item byId(ResourceLocation id) {
                return HarvestFixture.this.items.get(id);
            }

            @Override
            public ResourceLocation id(Item item) {
                return ((TestItem) item).id;
            }
        };
    }
}
