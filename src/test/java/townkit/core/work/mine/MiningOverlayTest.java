package townkit.core.work.mine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alex.test.village.villagers.miner.OreHeights;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.work.OreEntry;
import townkit.core.net.WorkListsPayload;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/** The miner's ore overlay without a game: bare blocks made without a constructor, and Towntopia's own miner schema. */
class MiningOverlayTest {
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final String OWNER = "test";
    private static final ResourceLocation FILE = ResourceLocation.parse("mymod:f");

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class TestBlock extends Block {
        ResourceLocation id;

        private TestBlock() {
            super(null);
        }
    }

    static final class TestBlocks implements MiningOverlay.BlockIds {
        final Map<Block, ResourceLocation> ids = new HashMap<>();
        final Map<ResourceLocation, Block> byId = new HashMap<>();

        Block block(String id) {
            try {
                ResourceLocation rl = ResourceLocation.parse(id);
                TestBlock b = (TestBlock) UNSAFE.allocateInstance(TestBlock.class);
                b.id = rl;
                this.ids.put(b, rl);
                this.byId.put(rl, b);
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
        public String name(Block block) {
            return this.ids.get(block).getPath();
        }
    }

    private TestBlocks blocks;
    private Block tinOre;
    private Block deepTinOre;
    private Block zincOre;
    private MiningOverlay overlay;
    private final List<String> logged = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.blocks = new TestBlocks();
        this.tinOre = this.blocks.block("mymod:tin_ore");
        this.deepTinOre = this.blocks.block("mymod:deepslate_tin_ore");
        this.zincOre = this.blocks.block("mymod:zinc_ore");
        this.overlay = new MiningOverlay(new WorkTable<>(MiningOverlay.KIND, OreEntry::id), () -> MinerSettingsSchema.SCHEMA, this.blocks,
            new WorkLog(this.logged::add));
    }

    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    private OreEntry.Builder tin() {
        return OreEntry.builder(id("mymod:tin"), this.tinOre).deepslate(this.deepTinOre);
    }

    private OreEntry.Builder zinc() {
        return OreEntry.builder(id("mymod:zinc"), this.zincOre);
    }

    /** What Towntopia's ores add up to at {@code y}. */
    private static double towntopia(int y) {
        double t = 0;
        for (double d : OreHeights.densities(y)) t += d;
        return t;
    }

    private static SettingsSchema.Row wanted(SettingsSchema s) {
        return s.findRow(MinerSettingsSchema.WANTED_ORES).orElseThrow();
    }

    @Test
    void noOreNoChange() {
        MiningOverlay.View v = this.overlay.view();
        assertFalse(this.overlay.touched());
        assertFalse(v.touched());
        assertTrue(v.ores().isEmpty());
        assertEquals(0, v.steer().length);
        for (int y = -64; y <= 320; y += 16) {
            double t = towntopia(y);
            assertEquals(0.0, v.luckyShare(t, y), "no lucky share at " + y);
            assertEquals(0.0, v.druidShare(t, y), "no druid share at " + y);
            assertNull(v.pickLucky(t, y, 0.0));
            assertNull(v.pickDruid(t, y, 0.0));
        }
        assertFalse(v.isAddonOre(this.tinOre));
        assertSame(MinerSettingsSchema.SCHEMA, this.overlay.schema(), "Towntopia's own schema object");
        assertEquals(7, MinerScreenRows.rows(this.overlay.schema()).size(), "Towntopia's seven rows");
        assertTrue(this.logged.isEmpty(), this.logged.toString());
    }

    @Test
    void luckyRollsOnlyWhenGiven() {
        this.overlay.add(OWNER, tin().depths(0, 64).druid(4.0, 0, 64).build()); // no lucky rolls
        this.overlay.add(OWNER, zinc().luckyRolls(6.0, 0, 64).build());
        MiningOverlay.View v = this.overlay.view();
        double t = towntopia(32);
        double d = 6.0 / 65;
        assertEquals(d / (t + d), v.luckyShare(t, 32), 1e-12, "zinc's share of a roll: w / (T + w)");
        assertEquals(0.0, v.luckyShare(towntopia(100), 100), "outside its range");
        assertNull(v.pickLucky(t, 32, 0.999), "above the share: Towntopia's own ore");
        OreEntry picked = v.pickLucky(t, 32, 0.0);
        assertNotNull(picked);
        assertEquals(id("mymod:zinc"), picked.id(), "tin has no lucky rolls, so it is never rolled");
        // many rolls: only zinc, never tin
        int zinc = 0;
        int tin = 0;
        for (int i = 0; i < 1000; i++) {
            OreEntry e = v.pickLucky(t, 32, i / 1000.0);
            if (e == null) continue;
            if (e.id().equals(id("mymod:zinc"))) zinc++;
            else tin++;
        }
        assertEquals(0, tin);
        assertTrue(zinc > 0 && zinc < 1000, "" + zinc);
        assertEquals(1000 * v.luckyShare(t, 32), zinc, 2.0, "the share of rolls zinc takes");
    }

    @Test
    void severalLuckyOresShareByWeight() {
        this.overlay.add(OWNER, tin().luckyRolls(30.0, 0, 29).build()); // 1.0 per height
        this.overlay.add(OWNER, zinc().luckyRolls(90.0, 0, 29).build()); // 3.0 per height
        MiningOverlay.View v = this.overlay.view();
        double t = towntopia(10);
        double share = 4.0 / (t + 4.0);
        assertEquals(share, v.luckyShare(t, 10), 1e-12);
        int tin = 0;
        int zinc = 0;
        for (int i = 0; i < 4000; i++) {
            OreEntry e = v.pickLucky(t, 10, i / 4000.0);
            if (e == null) continue;
            if (e.id().equals(id("mymod:tin"))) tin++;
            else zinc++;
        }
        assertEquals(3.0, (double) zinc / tin, 0.05, "weights 1 : 3");
    }

    @Test
    void druidSeparate() {
        this.overlay.add(OWNER, tin().druid(8.0, -10, 10).build()); // druid only
        this.overlay.add(OWNER, zinc().luckyRolls(8.0, -10, 10).build()); // lucky only
        MiningOverlay.View v = this.overlay.view();
        double t = towntopia(0);
        assertTrue(v.druidShare(t, 0) > 0);
        assertTrue(v.luckyShare(t, 0) > 0);
        assertEquals(id("mymod:tin"), v.pickDruid(t, 0, 0.0).id(), "the druid places tin only");
        assertEquals(id("mymod:zinc"), v.pickLucky(t, 0, 0.0).id(), "the miner rolls zinc only");
        assertEquals(0.0, v.druidShare(towntopia(30), 30), "outside the druid's range");
    }

    @Test
    void depthsAffectFit() {
        this.overlay.add(OWNER, tin().depths(0, 64).build());
        this.overlay.add(OWNER, zinc().luckyRolls(1.0, 0, 64).build()); // no depths: wanting it steers nothing
        MiningOverlay.View v = this.overlay.view();
        assertEquals(1, v.steer().length);
        assertEquals(id("mymod:tin"), v.steer()[0].id());
        assertEquals(1.0, MiningOverlay.View.fit(v.steer(), 32), "inside the depths");
        assertEquals(1.0, MiningOverlay.View.fit(v.steer(), 0));
        assertEquals(1.0, MiningOverlay.View.fit(v.steer(), 64));
        assertEquals(0.0, MiningOverlay.View.fit(v.steer(), 65), "outside the depths");
        assertEquals(0.0, MiningOverlay.View.fit(v.steer(), -30));
        assertEquals(0.0, MiningOverlay.View.fit(new OreEntry[0], 32), "nothing wanted adds nothing");
        assertTrue(MiningOverlay.View.fit(v.steer(), 10) > MiningOverlay.View.fit(v.steer(), 100), "a mine at tin's depth fits better");
    }

    @Test
    void addonBlocksCountAsOre() {
        this.overlay.add(OWNER, tin().build());
        MiningOverlay.View v = this.overlay.view();
        assertTrue(v.isAddonOre(this.tinOre));
        assertTrue(v.isAddonOre(this.deepTinOre));
        assertFalse(v.isAddonOre(this.zincOre));
        assertSame(this.deepTinOre, v.ores().get(0).deepslate());
        assertSame(this.deepTinOre, v.blockAt(v.ores().get(0), -10), "the deepslate block below Y 0");
        assertSame(this.tinOre, v.blockAt(v.ores().get(0), 10));
        assertSame(this.zincOre, v.blockAt(zinc().build(), -10), "no deepslate block: the stone one");
    }

    @Test
    void wantedOresRowListsTheAddonOre() {
        this.overlay.add(OWNER, tin().depths(0, 64).label(Component.literal("Tin")).build());
        this.overlay.add(OWNER, zinc().build());
        SettingsSchema page = this.overlay.schema();
        assertTrue(page != MinerSettingsSchema.SCHEMA);
        SettingsSchema.Row row = wanted(page);
        List<SettingsSchema.AllowListEntry> entries = row.allowListEntries();
        assertEquals(9, entries.size(), "Towntopia's seven first, then the addons'");
        assertEquals(wanted(MinerSettingsSchema.SCHEMA).entryIds(), row.entryIds().subList(0, 7));
        SettingsSchema.AllowListEntry tin = entries.get(7);
        assertEquals("mymod:tin", tin.id());
        assertEquals("Tin", tin.label());
        assertFalse(tin.defaultAllowed(), "off, like Towntopia's own ores");
        assertTrue(tin.description().contains("Y 0 to 64"), tin.description());
        SettingsSchema.AllowListEntry zinc = entries.get(8);
        assertEquals("zinc_ore", zinc.label(), "no label: the block's name");
        assertFalse(zinc.description().contains("Best mine depth"), zinc.description());
        assertTrue(zinc.description().contains("no best depth"), zinc.description());
        assertTrue(tin.description().startsWith("ON: ") && tin.description().endsWith("OFF: no wish for it."), tin.description());
        // every other row is Towntopia's
        assertEquals(MinerSettingsSchema.SCHEMA.sections().size(), page.sections().size());
        assertEquals(9, MinerScreenRows.rows(this.overlay.schema()).size());
        List<WorkListsPayload.Row> rows = MinerScreenRows.rows(this.overlay.schema());
        assertEquals("mymod:tin", rows.get(7).id());
        assertEquals(0, rows.get(7).stock());
    }

    @Test
    void removalRestoresTowntopias() {
        WorkTable.Handle h = this.overlay.add(OWNER, tin().build());
        assertTrue(this.overlay.touched());
        h.remove();
        assertFalse(this.overlay.touched());
        assertSame(MinerSettingsSchema.SCHEMA, this.overlay.schema());
        assertTrue(this.overlay.view().ores().isEmpty());
    }

    /** Java removes an ore the way a {@code "remove"} data file does. */
    @Test
    void removeOreHidesAnotherOwnersOre() {
        this.overlay.add("adder", tin().build());
        this.overlay.add("adder", zinc().build());
        WorkTable.Handle h = this.overlay.remove("remover", id("mymod:tin"));
        assertEquals(List.of(id("mymod:zinc")), this.overlay.ores().stream().map(OreEntry::id).toList());
        assertFalse(this.overlay.view().isAddonOre(this.tinOre));
        this.overlay.add("late", tin().build()); // a removal wins over a later add too
        assertEquals(1, this.overlay.ores().size());
        h.remove();
        assertEquals(2, this.overlay.ores().size(), "the removal undone");
    }

    /** Like the data files, which skip a file naming one of Towntopia's seven: removing or changing one is skipped (logged). */
    @Test
    void removeOrChangeOfTowntopiasOreIsSkipped() {
        this.overlay.remove(OWNER, id("towntopia:coal")).remove();
        this.overlay.change(OWNER, id("towntopia:iron"), e -> e).remove();
        assertFalse(this.overlay.table().touched(), "nothing registered");
        assertEquals(2, this.logged.size(), this.logged.toString());
        assertTrue(this.logged.get(0).contains("towntopia:coal") && this.logged.get(1).contains("towntopia:iron"), this.logged.toString());
    }

    /** Java changes an ore like a {@code "change"} data file: in merge order, waiting for an ore registered later. */
    @Test
    void changeOreEditsTheOreAndWaitsForIt() {
        WorkTable.Handle h = this.overlay.change("changer", id("mymod:tin"), e -> e.toBuilder().luckyRolls(3.0, 0, 10).build());
        assertTrue(this.overlay.ores().isEmpty());
        this.overlay.add("adder", tin().depths(0, 64).build());
        OreEntry tin = this.overlay.ores().get(0);
        assertEquals(new OreEntry.Spread(3.0, 0, 10), tin.luckyRolls());
        assertEquals(new OreEntry.Depths(0, 64), tin.depths(), "the rest kept");
        h.remove();
        assertNull(this.overlay.ores().get(0).luckyRolls());
    }

    /** A change that gives the ore another id, one of Towntopia's ids or an air block is skipped (logged), not applied. */
    @Test
    void changeOreToABadOreIsSkipped() {
        this.overlay.add("adder", tin().build());
        this.overlay.change("bad", id("mymod:tin"), e -> OreEntry.builder(id("mymod:other"), this.tinOre).build());
        assertEquals(id("mymod:tin"), this.overlay.ores().get(0).id());
        this.overlay.change("bad", id("mymod:tin"), e -> OreEntry.builder(id("towntopia:coal"), this.tinOre).build());
        assertEquals(1, this.overlay.ores().size());
        assertSame(this.tinOre, this.overlay.ores().get(0).stone(), "both bad changes skipped (the table logs them)");
    }

    @Test
    void addingAnIdAgainReplacesIt() {
        this.overlay.add(OWNER, tin().luckyRolls(5.0, 0, 10).build());
        this.overlay.add("other", tin().build());
        assertEquals(1, this.overlay.view().ores().size());
        assertNull(this.overlay.view().ores().get(0).luckyRolls());
    }

    @Test
    void towntopiasOwnOresAreNotChangedOrReplaced() {
        for (String own : List.of("coal", "copper", "iron", "lapis", "redstone", "gold", "diamond")) {
            OreEntry e = OreEntry.builder(id("towntopia:" + own), this.tinOre).build();
            assertNotNull(MiningOverlay.problem(e), own);
            this.overlay.add(OWNER, e);
        }
        assertFalse(this.overlay.touched(), "skipped: nothing registered");
        assertEquals(7, this.logged.size(), "one line each: " + this.logged);
        assertTrue(this.logged.get(0).contains("towntopia:coal"), this.logged.get(0));
        this.overlay.add(OWNER, OreEntry.builder(id("towntopia:coal"), this.tinOre).build());
        assertEquals(7, this.logged.size(), "the same line is logged once");
    }

    @Test
    void codecRoundTrip() {
        OreCodec codec = new OreCodec(this.blocks);
        OreEntry ore = tin().label(Component.literal("Tin")).depths(0, 64).luckyRolls(6.0, 0, 64).druid(2.5, -20, 10).build();
        assertEquals(ore, codec.read(FILE, codec.write(ore)).entry());
        OreEntry plain = zinc().build();
        assertEquals(plain, codec.read(FILE, codec.write(plain)).entry());
        OreEntry fromFile = codec.read(FILE, json("{\"action\":\"add\",\"id\":\"mymod:tin\",\"stone\":\"mymod:tin_ore\","
            + "\"deepslate\":\"mymod:deepslate_tin_ore\",\"depths\":[0,64],\"lucky_rolls\":{\"weight\":6.0,\"low\":0,\"high\":64}}")).entry();
        assertEquals(tin().depths(0, 64).luckyRolls(6.0, 0, 64).build(), fromFile);

        WorkChange<ResourceLocation, OreEntry> change = codec.read(FILE, json("{\"action\":\"change\",\"id\":\"mymod:tin\","
            + "\"depths\":[10,20],\"druid\":{\"weight\":1.0,\"low\":0,\"high\":5},\"deepslate\":null,\"label\":\"Tin!\"}"));
        assertEquals(WorkChange.Kind.CHANGE, change.kind());
        assertEquals(id("mymod:tin"), change.key());
        OreEntry changed = change.change().apply(ore);
        assertEquals(new OreEntry.Depths(10, 20), changed.depths());
        assertEquals(new OreEntry.Spread(1.0, 0, 5), changed.druid());
        assertNull(changed.deepslate(), "null clears it");
        assertEquals(Component.literal("Tin!"), changed.label());
        assertEquals(ore.luckyRolls(), changed.luckyRolls(), "untouched fields stay");
        assertSame(ore.stone(), changed.stone());

        WorkChange<ResourceLocation, OreEntry> remove = codec.read(FILE, json("{\"action\":\"remove\",\"id\":\"mymod:tin\"}"));
        assertEquals(WorkChange.Kind.REMOVE, remove.kind());
        assertEquals(id("mymod:tin"), remove.key());
    }

    /**
     * An unknown key (a typo such as {@code lucky_roll}) is reported with one log line naming the
     * file and the key; a change whose only fields are unknown keys sets nothing and is a bad file.
     */
    @Test
    void codecReportsUnknownKeys() {
        OreCodec codec = new OreCodec(this.blocks, new WorkLog(this.logged::add));
        OreEntry read = codec.read(FILE, json("{\"action\":\"add\",\"id\":\"mymod:tin\",\"stone\":\"mymod:tin_ore\","
            + "\"lucky_roll\":{\"weight\":6.0,\"low\":0,\"high\":64}}")).entry();
        assertNull(read.luckyRolls(), "the typo'd key sets nothing");
        assertEquals(1, this.logged.size(), this.logged.toString());
        assertTrue(this.logged.get(0).contains("lucky_roll") && this.logged.get(0).contains(FILE.toString()), this.logged.get(0));
        codec.read(FILE, json("{\"action\":\"add\",\"id\":\"mymod:tin\",\"stone\":\"mymod:tin_ore\",\"lucky_roll\":1}"));
        assertEquals(1, this.logged.size(), "logged once per file and key");
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"change\",\"id\":\"mymod:tin\","
            + "\"lucky_roll\":{\"weight\":6.0,\"low\":0,\"high\":64}}")), "only unknown keys: the change sets nothing");
    }

    @Test
    void badRangeSkipped() {
        // the builder refuses what the data files must skip
        assertThrows(IllegalArgumentException.class, () -> tin().depths(64, 0));
        assertThrows(IllegalArgumentException.class, () -> tin().depths(-65, 0));
        assertThrows(IllegalArgumentException.class, () -> tin().depths(0, 321));
        assertThrows(IllegalArgumentException.class, () -> tin().luckyRolls(0.0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> tin().luckyRolls(-1.0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> tin().luckyRolls(Double.NaN, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> tin().druid(1.0, 10, 0));
        assertEquals(new OreEntry.Depths(-64, 320), tin().depths(-64, 320).build().depths(), "the edges are inside");

        OreCodec codec = new OreCodec(this.blocks);
        String head = "{\"action\":\"add\",\"id\":\"mymod:tin\",\"stone\":\"mymod:tin_ore\",";
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json(head + "\"depths\":[64,0]}")));
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json(head + "\"depths\":[0]}")), "not a pair");
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json(head + "\"depths\":[0,400]}")));
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json(head + "\"lucky_rolls\":{\"weight\":0,\"low\":0,\"high\":5}}")));
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json(head + "\"lucky_rolls\":{\"weight\":1.0,\"low\":9,\"high\":5}}")));
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json(head + "\"druid\":{\"weight\":1.0,\"low\":0}}")), "no high");
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"add\",\"id\":\"mymod:tin\",\"stone\":\"mymod:nope\"}")),
            "unknown block");
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"add\",\"id\":\"mymod:tin\"}")), "no stone");
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"add\",\"stone\":\"mymod:tin_ore\"}")), "no id");
        // Towntopia's seven stay Towntopia's: add, change and remove of one are skipped
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"add\",\"id\":\"towntopia:iron\",\"stone\":\"mymod:tin_ore\"}")));
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"change\",\"id\":\"towntopia:iron\",\"depths\":[0,5]}")));
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"remove\",\"id\":\"towntopia:iron\"}")));
        // a change is checked as a file is: a bad range or block skips it
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"change\",\"id\":\"mymod:tin\",\"depths\":[5,1]}")));
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"change\",\"id\":\"mymod:tin\",\"stone\":\"mymod:nope\"}")));
        assertThrows(JsonParseException.class, () -> codec.read(FILE, json("{\"action\":\"change\",\"id\":\"mymod:tin\"}")), "sets nothing");
    }

    @Test
    void airStoneIsSkipped() {
        Block air = this.blocks.block("minecraft:air");
        try {
            Block real = (Block) UNSAFE.allocateInstance(AirBlock.class);
            this.blocks.ids.put(real, id("minecraft:air"));
            assertNotNull(MiningOverlay.problem(OreEntry.builder(id("mymod:ghost"), real).build()), "air can't be an ore");
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
        assertNull(MiningOverlay.problem(tin().build()));
        assertNotNull(air);
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
