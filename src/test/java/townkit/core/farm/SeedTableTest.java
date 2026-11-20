package townkit.core.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.farm.SeedSource;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/** {@link SeedTable} and {@link SeedCodec} without a game: the items are bare objects (identity is all they need). */
class SeedTableTest {
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final ResourceLocation FILE = ResourceLocation.parse("mymod:kernels");

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A bare item that names itself. */
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

    private final Map<ResourceLocation, Item> byId = new HashMap<>();
    private final List<String> lines = new ArrayList<>();
    private Item melonSeeds;
    private Item kernels;
    private Item corn;
    private Item riceSeeds;
    private Item rice;
    private SeedTable table;
    private SeedCodec codec;

    private Item item(String id) {
        try {
            TestItem i = (TestItem) UNSAFE.allocateInstance(TestItem.class);
            i.id = ResourceLocation.parse(id);
            this.byId.put(i.id, i);
            return i;
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @BeforeEach
    void setUp() {
        this.melonSeeds = item("minecraft:melon_seeds");
        this.kernels = item("mymod:kernels");
        this.corn = item("mymod:corn");
        this.riceSeeds = item("mymod:rice_seeds");
        this.rice = item("mymod:rice");
        WorkLog log = new WorkLog(this.lines::add);
        this.table = new SeedTable(new WorkTable<>(SeedTable.KIND, SeedTable.Entry::seed), () -> Set.of(this.melonSeeds), log);
        this.codec = new SeedCodec(new SeedCodec.ItemIds() {
            @Nullable
            @Override
            public Item byId(ResourceLocation id) {
                return SeedTableTest.this.byId.get(id);
            }

            @Override
            public ResourceLocation id(Item item) {
                return ((TestItem) item).id;
            }
        }, this.table, log);
    }

    @Test
    void addAndList() {
        this.table.add("a", this.kernels, this.corn, 2);
        assertEquals(new SeedSource(this.corn, 2), this.table.sources().get(this.kernels));
        assertEquals(1, this.table.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> this.table.sources().put(this.riceSeeds, new SeedSource(this.rice, 1)));
        assertTrue(this.lines.isEmpty());
    }

    @Test
    void removeAndUnsubscribe() {
        WorkTable.Handle h = this.table.add("a", this.kernels, this.corn, 2);
        this.table.remove("b", this.kernels);
        assertTrue(this.table.sources().isEmpty(), "a removal wins");
        h.remove();
        assertTrue(this.table.sources().isEmpty());
    }

    @Test
    void towntopiaSeedsCannotBeRemoved() {
        this.table.remove("a", this.melonSeeds);
        this.table.add("a", this.melonSeeds, this.corn, 3);
        assertTrue(this.table.sources().isEmpty(), "Towntopia's seeds are not in the addon table");
        assertEquals(2, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("minecraft:melon_seeds"));
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json(
            "{\"action\":\"remove\",\"seed\":\"minecraft:melon_seeds\"}")));
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json(
            "{\"action\":\"change\",\"seed\":\"minecraft:melon_seeds\",\"seeds_per_produce\":3}")));
    }

    @Test
    void badCountSkipped() {
        this.table.add("a", this.kernels, this.corn, 0);
        this.table.add("a", this.riceSeeds, this.rice, -2);
        assertTrue(this.table.sources().isEmpty());
        assertEquals(2, this.lines.size(), this.lines.toString());
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json(
            "{\"action\":\"add\",\"seed\":\"mymod:kernels\",\"produce\":\"mymod:corn\",\"seeds_per_produce\":0}")));
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json(
            "{\"action\":\"add\",\"seed\":\"mymod:kernels\",\"produce\":\"mymod:corn\",\"seeds_per_produce\":1.5}")));
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json(
            "{\"action\":\"add\",\"seed\":\"mymod:kernels\",\"produce\":\"mymod:nothing\",\"seeds_per_produce\":1}")));
    }

    @Test
    void fileAndJavaMerge() {
        this.table.add("a", this.kernels, this.corn, 2);
        WorkChange<Item, SeedTable.Entry> add = this.codec.read(FILE, json(
            "{\"action\":\"add\",\"seed\":\"mymod:rice_seeds\",\"produce\":\"mymod:rice\",\"seeds_per_produce\":3}"));
        WorkChange<Item, SeedTable.Entry> change = this.codec.read(FILE, json(
            "{\"action\":\"change\",\"seed\":\"mymod:kernels\",\"seeds_per_produce\":4}"));
        this.table.table().setFileChanges(List.of(add, change));
        assertEquals(new SeedSource(this.rice, 3), this.table.sources().get(this.riceSeeds));
        assertEquals(new SeedSource(this.corn, 4), this.table.sources().get(this.kernels));
        assertEquals(List.of(this.kernels, this.riceSeeds), List.copyOf(this.table.sources().keySet()));
        WorkChange<Item, SeedTable.Entry> remove = this.codec.read(FILE, json(
            "{\"action\":\"remove\",\"seed\":\"mymod:kernels\"}"));
        this.table.table().setFileChanges(List.of(add, remove));
        assertNull(this.table.sources().get(this.kernels), "a file's removal wins over Java's add");
        assertFalse(this.table.sources().isEmpty());
    }

    @Test
    void unknownKeyLoggedOnce() {
        this.codec.read(FILE, json("{\"action\":\"add\",\"seed\":\"mymod:kernels\",\"produce\":\"mymod:corn\","
            + "\"seeds_per_produce\":2,\"seed_per_produce\":9}"));
        assertEquals(1, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("seed_per_produce"));
    }
}
