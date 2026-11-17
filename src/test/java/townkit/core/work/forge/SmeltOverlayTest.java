package townkit.core.work.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/** The smith's smelting overlay without a game: bare items, and the three raws Towntopia's smith smelts. */
class SmeltOverlayTest {
    private static final String OWNER = "test";

    private ForgeOverlayTest.TestItems items;
    private Item rawIron;
    private Item ironIngot;
    private Item rawGold;
    private Item goldIngot;
    private Item rawCopper;
    private Item copperIngot;
    private Item rawTin;
    private Item tinIngot;
    private SmeltOverlay overlay;
    private final List<String> logged = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.items = new ForgeOverlayTest.TestItems();
        this.rawIron = this.items.item("minecraft:raw_iron");
        this.ironIngot = this.items.item("minecraft:iron_ingot");
        this.rawGold = this.items.item("minecraft:raw_gold");
        this.goldIngot = this.items.item("minecraft:gold_ingot");
        this.rawCopper = this.items.item("minecraft:raw_copper");
        this.copperIngot = this.items.item("minecraft:copper_ingot");
        this.rawTin = this.items.item("mymod:raw_tin");
        this.tinIngot = this.items.item("mymod:tin_ingot");
        List<SmeltOverlay.Smelt> own = List.of(new SmeltOverlay.Smelt(this.rawIron, this.ironIngot),
            new SmeltOverlay.Smelt(this.rawGold, this.goldIngot), new SmeltOverlay.Smelt(this.rawCopper, this.copperIngot));
        this.overlay = new SmeltOverlay(new WorkTable<>(SmeltOverlay.KIND, SmeltOverlay.Smelt::raw), () -> own, new WorkLog(this.logged::add));
    }

    private static Item[] pair(Item raw, Item result) {
        return new Item[] {raw, result};
    }

    @Test
    void nothingRegisteredIsTowntopias() {
        SmeltOverlay.View v = this.overlay.view();
        assertFalse(this.overlay.touched());
        assertFalse(v.touched());
        assertTrue(v.addons().isEmpty());
        assertEquals(3, v.ore().length, "iron, gold, copper");
        assertSame(this.rawGold, v.ore()[1][0], "gold stays at index 1: the away path reads it there");
        assertTrue(this.logged.isEmpty(), this.logged.toString());
    }

    @Test
    void addedRawCounts() {
        this.overlay.add(OWNER, new SmeltOverlay.Smelt(this.rawTin, this.tinIngot));
        SmeltOverlay.View v = this.overlay.view();
        assertTrue(v.touched());
        assertEquals(List.of(new SmeltOverlay.Smelt(this.rawTin, this.tinIngot)), v.addons());
        assertEquals(4, v.ore().length);
        assertSame(this.rawIron, v.ore()[0][0]);
        assertSame(this.rawGold, v.ore()[1][0]);
        assertSame(this.rawCopper, v.ore()[2][0]);
        assertSame(this.rawTin, v.ore()[3][0]);
        assertSame(this.tinIngot, v.ore()[3][1]);
        assertSame(this.tinIngot, v.resultOf(this.rawTin));
        assertTrue(v.isAddon(this.rawTin));
        assertFalse(v.isAddon(this.rawCopper));
        assertNull(v.resultOf(this.items.item("mymod:other")));
    }

    @Test
    void removeRawCopper() {
        this.overlay.remove(OWNER, this.rawCopper);
        this.overlay.add(OWNER, new SmeltOverlay.Smelt(this.rawTin, this.tinIngot));
        SmeltOverlay.View v = this.overlay.view();
        assertFalse(v.copperOn());
        assertEquals(3, v.ore().length, "iron, gold, tin");
        assertSame(this.rawGold, v.ore()[1][0]);
        assertSame(this.rawTin, v.ore()[2][0]);
        assertTrue(this.logged.isEmpty(), this.logged.toString());
    }

    @Test
    void changeResultOfTowntopiasRaws() {
        this.overlay.table().setFileChanges(List.of(
            WorkChange.change("file", this.rawIron, s -> new SmeltOverlay.Smelt(s.raw(), this.tinIngot)),
            WorkChange.change("file", this.rawCopper, s -> new SmeltOverlay.Smelt(s.raw(), this.tinIngot)),
            WorkChange.change("file", this.rawGold, s -> new SmeltOverlay.Smelt(s.raw(), this.tinIngot))));
        SmeltOverlay.View v = this.overlay.view();
        assertSame(this.tinIngot, v.ironIngot());
        assertSame(this.tinIngot, v.goldIngot());
        assertSame(this.tinIngot, v.copperIngot());
        assertSame(this.tinIngot, v.ore()[0][1]);
        assertTrue(v.addons().isEmpty());
    }

    /** Removing raw iron or raw gold is skipped with one log line; they stay as Towntopia's. */
    @Test
    void removingIronOrGoldIsSkippedAndLogged() {
        this.overlay.remove(OWNER, this.rawIron);
        this.overlay.remove(OWNER, this.rawGold);
        SmeltOverlay.View v = this.overlay.view();
        this.overlay.view();
        assertEquals(3, v.ore().length);
        assertSame(this.rawIron, v.ore()[0][0]);
        assertSame(this.ironIngot, v.ironIngot());
        assertSame(this.goldIngot, v.goldIngot());
        assertEquals(2, this.logged.size(), this.logged.toString());
        assertTrue(this.logged.get(0).contains("unsupported"), this.logged.get(0));
        assertTrue(this.logged.get(1).contains("unsupported"), this.logged.get(1));
        // a rebuild logs nothing more
        this.overlay.add(OWNER, new SmeltOverlay.Smelt(this.rawTin, this.tinIngot));
        this.overlay.view();
        assertEquals(2, this.logged.size(), this.logged.toString());
    }

    @Test
    void addingAnExistingRawReplacesIt() {
        this.overlay.add(OWNER, new SmeltOverlay.Smelt(this.rawTin, this.tinIngot));
        this.overlay.add("other", new SmeltOverlay.Smelt(this.rawTin, this.copperIngot));
        assertSame(this.copperIngot, this.overlay.view().resultOf(this.rawTin));
        assertEquals(1, this.overlay.view().addons().size());
    }

    @Test
    void subscriptionRemovalRestoresTowntopias() {
        WorkTable.Handle h = this.overlay.add(OWNER, new SmeltOverlay.Smelt(this.rawTin, this.tinIngot));
        assertTrue(this.overlay.view().touched());
        h.remove();
        assertFalse(this.overlay.view().touched());
        assertEquals(3, this.overlay.view().ore().length);
    }

    @Test
    void codecRoundTrip() {
        SmeltCodec codec = new SmeltCodec(this.items);
        ResourceLocation file = ResourceLocation.parse("mymod:f");
        SmeltOverlay.Smelt tin = new SmeltOverlay.Smelt(this.rawTin, this.tinIngot);
        assertEquals(tin, codec.read(file, codec.write(tin)).entry());
        assertEquals(tin, codec.read(file, json("{\"action\":\"add\",\"raw\":\"mymod:raw_tin\",\"result\":\"mymod:tin_ingot\"}")).entry());
        WorkChange<Item, SmeltOverlay.Smelt> change = codec.read(file,
            json("{\"action\":\"change\",\"raw\":\"minecraft:raw_copper\",\"result\":\"mymod:tin_ingot\"}"));
        assertEquals(WorkChange.Kind.CHANGE, change.kind());
        assertSame(this.rawCopper, change.key());
        assertEquals(new SmeltOverlay.Smelt(this.rawCopper, this.tinIngot), change.change().apply(new SmeltOverlay.Smelt(this.rawCopper, this.copperIngot)));
        WorkChange<Item, SmeltOverlay.Smelt> remove = codec.read(file, json("{\"action\":\"remove\",\"raw\":\"minecraft:raw_copper\"}"));
        assertEquals(WorkChange.Kind.REMOVE, remove.kind());
        assertSame(this.rawCopper, remove.key());
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"add\",\"raw\":\"mymod:nope\",\"result\":\"mymod:tin_ingot\"}")));
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"add\",\"raw\":\"mymod:raw_tin\"}")), "no result");
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"change\",\"raw\":\"minecraft:raw_copper\"}")), "a change without a result");
    }

    private static Item air() {
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Item) ((sun.misc.Unsafe) f.get(null)).allocateInstance(net.minecraft.world.item.AirItem.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Air on either side, or a raw that smelts into itself, is no entry. */
    @Test
    void airAndSelfSmeltingAreSkipped() {
        Item air = air();
        assertTrue(SmeltOverlay.problem(air, this.tinIngot) != null);
        assertTrue(SmeltOverlay.problem(this.rawTin, air) != null);
        assertTrue(SmeltOverlay.problem(this.rawTin, this.rawTin) != null);
        assertNull(SmeltOverlay.problem(this.rawTin, this.tinIngot));
        SmeltCodec codec = new SmeltCodec(this.items);
        this.items.byId.put(ResourceLocation.parse("minecraft:air"), air);
        this.items.ids.put(air, ResourceLocation.parse("minecraft:air"));
        ResourceLocation file = ResourceLocation.parse("mymod:f");
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"add\",\"raw\":\"minecraft:air\",\"result\":\"mymod:tin_ingot\"}")));
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"add\",\"raw\":\"mymod:raw_tin\",\"result\":\"minecraft:air\"}")));
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"add\",\"raw\":\"mymod:raw_tin\",\"result\":\"mymod:raw_tin\"}")));
        assertThrows(JsonParseException.class, () -> codec.read(file, json("{\"action\":\"change\",\"raw\":\"minecraft:raw_copper\",\"result\":\"minecraft:air\"}")));
    }

    /** The handle of a removal (what removeSmelt hands back) puts the entry back; iron stays logged. */
    @Test
    void removalHandleRestoresCopper() {
        WorkTable.Handle h = this.overlay.remove(OWNER, this.rawCopper);
        assertFalse(this.overlay.view().copperOn());
        h.remove();
        assertTrue(this.overlay.view().copperOn());
        assertSame(this.copperIngot, this.overlay.view().copperIngot());
        WorkTable.Handle iron = this.overlay.remove(OWNER, this.rawIron);
        assertSame(this.rawIron, this.overlay.view().ore()[0][0]);
        assertEquals(1, this.logged.size());
        iron.remove();
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
