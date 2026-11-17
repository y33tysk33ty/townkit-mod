package townkit.core.work.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.work.CookStation;
import townkit.api.work.Dish;
import townkit.api.work.StationContext;
import townkit.api.work.StationHandler;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkTable;

/**
 * Cook stations without a game: blocks are bare {@link Block} objects made without a constructor (identity is all the
 * station table needs), with their ids and a tag table handed in; kitchens are plain objects.
 */
class StationsTest {
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

    /** A bare block that names itself. */
    static final class TestBlock extends Block {
        ResourceLocation id;

        private TestBlock() {
            super(null);
        }

        @Override
        public String toString() {
            return String.valueOf(this.id);
        }
    }

    /** Block ids and tags for the bare test blocks. */
    static final class TestBlocks implements Stations.Blocks {
        final Map<Block, ResourceLocation> ids = new HashMap<>();
        final Map<ResourceLocation, Block> byId = new HashMap<>();
        final Map<TagKey<Block>, Set<Block>> tags = new HashMap<>();

        Block block(String id) {
            try {
                TestBlock b = (TestBlock) UNSAFE.allocateInstance(TestBlock.class);
                b.id = ResourceLocation.parse(id);
                this.ids.put(b, b.id);
                this.byId.put(b.id, b);
                return b;
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ResourceLocation id(Block block) {
            return this.ids.get(block);
        }

        @Nullable
        @Override
        public Block byId(ResourceLocation id) {
            return this.byId.get(id);
        }

        @Override
        public boolean is(Block block, TagKey<Block> tag) {
            return this.tags.getOrDefault(tag, Set.of()).contains(block);
        }
    }

    private static TagKey<Block> tag(String id) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.parse(id));
    }

    private static final ResourceLocation POT = ResourceLocation.parse("mymod:pot");
    private static final ResourceLocation KETTLE = ResourceLocation.parse("mymod:kettle");
    private static final TagKey<Block> POTS = tag("mymod:pots");

    private TestBlocks blocks;
    private Block potBlock;
    private Block kettleBlock;
    private Block stone;
    private final List<String> errors = new ArrayList<>();
    private Stations stations;

    @BeforeEach
    void setUp() {
        this.blocks = new TestBlocks();
        this.potBlock = this.blocks.block("mymod:pot_block");
        this.kettleBlock = this.blocks.block("mymod:kettle_block");
        this.stone = this.blocks.block("minecraft:stone");
        this.blocks.tags.put(POTS, Set.of(this.kettleBlock));
        this.stations = new Stations(new WorkTable<>(Stations.KIND, Stations.Def::id), this.blocks,
            (message, t) -> this.errors.add(message));
    }

    /** Java removes a station the way a {@code "remove"} data file does. */
    @Test
    void removeStationHidesIt() {
        this.stations.add("a", POT, CookStation.builder().block(this.potBlock).build());
        WorkTable.Handle h = this.stations.remove("b", POT);
        assertNull(this.stations.get(POT));
        assertTrue(this.stations.isEmpty());
        h.remove();
        assertTrue(this.stations.get(POT) != null, "the removal undone");
    }

    /** One kitchen scanned: {@code found} are the blocks in its room. */
    private void scan(Object kitchen, Map<BlockPos, Block> found) {
        this.stations.scan(kitchen, found.keySet(), found::get);
    }

    @Test
    void matchesBlockAndTag() {
        Stations.Def byBlock = this.stations.add("a", POT, CookStation.builder().block(this.potBlock).build()).def();
        Stations.Def byTag = this.stations.add("b", KETTLE, CookStation.builder().blocks(POTS).build()).def();
        assertTrue(this.stations.matches(byBlock, this.potBlock));
        assertFalse(this.stations.matches(byBlock, this.kettleBlock));
        assertFalse(this.stations.matches(byBlock, this.stone));
        assertTrue(this.stations.matches(byTag, this.kettleBlock));
        assertFalse(this.stations.matches(byTag, this.potBlock));
        assertSame(byBlock, this.stations.get(POT));
        assertSame(byTag, this.stations.get(KETTLE));

        // the builder wants exactly one of block / blocks
        assertThrows(IllegalArgumentException.class, () -> CookStation.builder().build());
        assertThrows(IllegalArgumentException.class, () -> CookStation.builder().block(this.potBlock).blocks(POTS).build());
        assertThrows(NullPointerException.class, () -> CookStation.builder().block(null));

        // positions per kitchen: only the blocks that match, by station
        Object kitchen = new Object();
        BlockPos a = new BlockPos(1, 64, 1);
        BlockPos b = new BlockPos(2, 64, 1);
        BlockPos c = new BlockPos(3, 64, 1);
        scan(kitchen, Map.of(a, this.potBlock, b, this.kettleBlock, c, this.stone));
        assertEquals(List.of(a), this.stations.positions(List.of(kitchen), POT));
        assertEquals(List.of(b), this.stations.positions(List.of(kitchen), KETTLE));
        assertSame(kitchen, this.stations.kitchenOf(List.of(kitchen), a));
        assertNull(this.stations.kitchenOf(List.of(kitchen), c));
    }

    @Test
    void noStationNoCandidate() {
        Object kitchen = new Object();
        BlockPos at = new BlockPos(5, 64, 5);
        // a dish without a station is always a candidate (Towntopia decides)
        assertTrue(this.stations.candidate(null, List.of(kitchen)));
        // a station nobody registered: never
        scan(kitchen, Map.of(at, this.potBlock));
        assertFalse(this.stations.candidate(POT, List.of(kitchen)));
        // registered, but the kitchen has no matching block
        this.stations.add("a", POT, CookStation.builder().block(this.potBlock).build());
        Object bare = new Object();
        scan(bare, Map.of(at, this.stone));
        assertFalse(this.stations.candidate(POT, List.of(bare)));
        assertFalse(this.stations.candidate(POT, List.of()));
        // a scan from before the station was registered is redone: not current
        assertFalse(this.stations.scanned(kitchen));
        scan(kitchen, Map.of(at, this.potBlock));
        assertTrue(this.stations.scanned(kitchen));
        assertTrue(this.stations.candidate(POT, List.of(bare, kitchen)));
        // the block broken and the kitchen rescanned: no longer
        scan(kitchen, Map.of(at, this.stone));
        assertFalse(this.stations.candidate(POT, List.of(kitchen)));

        // the overlay: a station dish is a table dish to Towntopia (the cook works at the station), even if heated
        CookOverlayTest.TestItems items = new CookOverlayTest.TestItems();
        Item stew = items.item("mymod:stew");
        Item bean = items.item("mymod:bean");
        WorkTable<Item, Dish> dishes = new WorkTable<>(CookOverlay.KIND, Dish::result);
        CookOverlay overlay = new CookOverlay(dishes, List::of, () -> CookSettingsSchema.SCHEMA, items);
        dishes.add("a", Dish.builder(stew).ingredient(bean, 2).heated(true).station(POT).build());
        CookActivity.Recipe r = overlay.recipes().get(0);
        assertFalse(r.needsFurnace());
        assertEquals(POT, overlay.stationOf(stew));
    }

    @Test
    void stationCodecRoundTrip() {
        StationCodec codec = new StationCodec(this.blocks);
        ResourceLocation file = ResourceLocation.parse("mymod:pot");
        WorkChange<ResourceLocation, Stations.Def> byTag = codec.read(file,
            json("{\"action\":\"add\",\"id\":\"mymod:pot\",\"blocks\":\"#mymod:pots\"}"));
        assertEquals(WorkChange.Kind.ADD, byTag.kind());
        assertEquals(POT, byTag.key());
        assertEquals(POTS, byTag.entry().station().blocks());
        assertNull(byTag.entry().station().block());
        assertNull(byTag.entry().station().handler());
        assertEquals(json("{\"action\":\"add\",\"id\":\"mymod:pot\",\"blocks\":\"#mymod:pots\"}"), codec.write(byTag.entry()));

        WorkChange<ResourceLocation, Stations.Def> byBlock = codec.read(file,
            json("{\"action\":\"add\",\"id\":\"mymod:kettle\",\"blocks\":\"mymod:pot_block\"}"));
        assertSame(this.potBlock, byBlock.entry().station().block());
        assertEquals(json("{\"action\":\"add\",\"id\":\"mymod:kettle\",\"blocks\":\"mymod:pot_block\"}"), codec.write(byBlock.entry()));
        WorkChange<ResourceLocation, Stations.Def> again = codec.read(file, codec.write(byBlock.entry()));
        assertSame(byBlock.entry().station().block(), again.entry().station().block());
        assertEquals(byBlock.key(), again.key());

        WorkChange<ResourceLocation, Stations.Def> remove = codec.read(file, json("{\"action\":\"remove\",\"id\":\"mymod:pot\"}"));
        assertEquals(WorkChange.Kind.REMOVE, remove.kind());
        assertEquals(POT, remove.key());

        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"add\",\"id\":\"mymod:pot\"}")));
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"add\",\"id\":\"mymod:pot\",\"blocks\":\"mymod:nope\"}")));
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"add\",\"blocks\":\"#mymod:pots\"}")));
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"change\",\"id\":\"mymod:pot\",\"blocks\":\"#mymod:pots\"}")));
    }

    @Test
    void throwingHandlerLoggedOnceAndSwitchedOffAfterTen() {
        int[] calls = {0};
        StationHandler bad = new StationHandler() {
            @Override
            public void started(StationContext ctx) {
                calls[0]++;
                throw new IllegalStateException("boom");
            }
        };
        Stations.Def def = this.stations.add("addon", POT, CookStation.builder().block(this.potBlock).handler(bad).build()).def();
        StationContext ctx = new StationContext(null, BlockPos.ZERO, null, null);
        for (int i = 0; i < 9; i++) this.stations.started(def, ctx);
        assertFalse(def.off());
        assertEquals(1, this.errors.size(), "one log line per owner: " + this.errors);
        this.stations.started(def, ctx);
        assertTrue(def.off());
        this.stations.started(def, ctx);
        this.stations.stopped(def, ctx);
        assertEquals(10, calls[0], "a switched-off handler isn't called");
        assertEquals(2, this.errors.size(), "the switch-off is logged: " + this.errors);
    }

    /**
     * Every way out of a batch (its end, the cook left, the server stops) fires {@code stopped} once, and
     * the server stopping closes every open batch (so no entry keeps a level alive).
     */
    @Test
    void openBatchesStopOnceEachWayOut() {
        int[] started = {0};
        List<BlockPos> stopped = new ArrayList<>();
        StationHandler h = new StationHandler() {
            @Override
            public void started(StationContext ctx) {
                started[0]++;
            }

            @Override
            public void stopped(StationContext ctx) {
                stopped.add(ctx.pos());
            }
        };
        Stations.Def def = this.stations.add("a", POT, CookStation.builder().block(this.potBlock).handler(h).build()).def();
        Object cookA = new Object();
        Object cookB = new Object();
        Object cookC = new Object();
        BlockPos a = new BlockPos(1, 0, 0);
        BlockPos b = new BlockPos(2, 0, 0);
        BlockPos c = new BlockPos(3, 0, 0);
        this.stations.start(cookA, def, new StationContext(null, a, null, null));
        this.stations.start(cookB, def, new StationContext(null, b, null, null));
        this.stations.start(cookC, def, new StationContext(null, c, null, null));
        assertEquals(3, started[0]);
        assertEquals(3, this.stations.openCount());
        assertSame(def, this.stations.openBatch(cookA).def());

        // the cook left its level, then its doStop comes too: one stopped
        assertEquals(1, this.stations.stopWhere(ctx -> ctx.pos().equals(a)));
        assertFalse(this.stations.stop(cookA));
        assertEquals(List.of(a), stopped);
        // a new batch for a cook with one still open stops the old one first
        this.stations.start(cookB, def, new StationContext(null, b.above(), null, null));
        assertEquals(List.of(a, b), stopped);

        // the server stops: every open batch hears stopped once, nothing stays open
        assertEquals(2, this.stations.stopAll());
        assertEquals(0, this.stations.openCount());
        assertEquals(Set.of(a, b, b.above(), c), Set.copyOf(stopped));
        assertEquals(4, stopped.size());
        assertEquals(0, this.stations.stopAll());
        assertFalse(this.stations.stop(cookC));
        assertNull(this.stations.openBatch(cookC));
        assertEquals(4, stopped.size());
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
