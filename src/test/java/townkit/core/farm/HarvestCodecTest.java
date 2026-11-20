package townkit.core.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.farm.AwayYield;
import townkit.api.farm.BlockRef;
import townkit.api.farm.HarvestAction;
import townkit.api.farm.HarvestRule;
import townkit.api.farm.Holding;
import townkit.api.farm.Ripe;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkLog;

/** {@link HarvestCodec} without a game: blocks and items are bare objects. */
class HarvestCodecTest {
    private static final ResourceLocation FILE = ResourceLocation.parse("ttcompat:blueberry");

    private final HarvestFixture fx = new HarvestFixture();
    private final List<String> lines = new ArrayList<>();
    private Block bush;
    private Block plain;
    private Item berries;
    private HarvestCodec codec;

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @BeforeEach
    void setUp() {
        this.bush = this.fx.bush("hearthandharvest:blueberry_bush");
        this.plain = this.fx.block("mymod:plain");
        this.berries = this.fx.item("hearthandharvest:blueberries");
        this.codec = new HarvestCodec(this.fx.blockAccess(), this.fx.itemAccess(), new WorkLog(this.lines::add));
    }

    @Test
    void parsesWishlistExample() {
        WorkChange<ResourceLocation, HarvestRule> c = this.codec.read(FILE, json("""
            {"action": "add", "id": "ttcompat:hh_blueberry", "blocks": ["hearthandharvest:blueberry_bush"],
             "ripe": {"property": "age", "at_least": 3}, "harvest": {"type": "use", "holding": "empty_hand"},
             "away": {"item": "hearthandharvest:blueberries", "count": [2, 3], "picked_state": {"age": "1"}}}"""));
        assertEquals(WorkChange.Kind.ADD, c.kind());
        HarvestRule r = c.entry();
        assertEquals(ResourceLocation.parse("ttcompat:hh_blueberry"), c.key());
        assertEquals(c.key(), r.id());
        assertEquals(List.of(BlockRef.of(this.bush)), r.blocks());
        assertEquals(new Ripe.AtLeast("age", 3), r.ripe());
        assertEquals(new HarvestAction.Use(Holding.EMPTY_HAND), r.action());
        assertEquals(Optional.of(new AwayYield(this.berries, 2, 3, Map.of("age", "1"))), r.away());
        assertTrue(this.lines.isEmpty(), this.lines.toString());
    }

    @Test
    void defaultsAreTowntopiaRipeAndBreak() {
        HarvestRule r = this.codec.read(FILE, json(
            "{\"action\":\"add\",\"id\":\"ttcompat:x\",\"blocks\":[\"mymod:plain\"]}")).entry();
        assertEquals(new Ripe.Towntopia(), r.ripe());
        assertEquals(new HarvestAction.Break(), r.action());
        assertEquals(Optional.empty(), r.away());
        HarvestRule use = this.codec.read(FILE, json(
            "{\"action\":\"add\",\"id\":\"ttcompat:x\",\"blocks\":\"mymod:plain\",\"harvest\":{\"type\":\"use\"}}")).entry();
        assertEquals(new HarvestAction.Use(Holding.EMPTY_HAND), use.action(), "holding defaults to the empty hand");
    }

    @Test
    void missingPropertySkipped() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json("""
            {"action":"add","id":"ttcompat:x","blocks":["hearthandharvest:blueberry_bush"],
             "ripe":{"property":"ripeness","at_least":1}}""")));
        assertTrue(e.getMessage().contains("ripeness"), e.getMessage());
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json("""
            {"action":"add","id":"ttcompat:x","blocks":["mymod:plain"],"ripe":{"property":"age","at_least":1}}""")));
    }

    @Test
    void valueOutOfRangeSkipped() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json("""
            {"action":"add","id":"ttcompat:x","blocks":["hearthandharvest:blueberry_bush"],
             "ripe":{"property":"age","at_least":7}}""")));
        assertTrue(e.getMessage().contains("7"), e.getMessage());
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json("""
            {"action":"add","id":"ttcompat:x","blocks":["hearthandharvest:blueberry_bush"],
             "ripe":{"property":"age","is":"9"}}""")));
    }

    @Test
    void isRipeAndTagsAreAccepted() {
        HarvestRule r = this.codec.read(FILE, json("""
            {"action":"add","id":"ttcompat:x","blocks":["#mymod:bushes","mymod:plain"],
             "ripe":{"property":"anything","is":"true"}}""")).entry();
        assertEquals(new Ripe.Is("anything", "true"), r.ripe(), "a tag's members are only known later, so the check waits");
        assertEquals(2, r.blocks().size());
        assertEquals("mymod:bushes", r.blocks().get(0).tag().location().toString());
    }

    @Test
    void badFilesThrow() {
        for (String bad : List.of(
            "{\"action\":\"add\",\"blocks\":[\"mymod:plain\"]}",
            "{\"action\":\"add\",\"id\":\"ttcompat:x\"}",
            "{\"action\":\"add\",\"id\":\"ttcompat:x\",\"blocks\":[\"mymod:nothing\"]}",
            "{\"action\":\"add\",\"id\":\"ttcompat:x\",\"blocks\":[\"mymod:plain\"],\"harvest\":{\"type\":\"dance\"}}",
            "{\"action\":\"add\",\"id\":\"ttcompat:x\",\"blocks\":[\"mymod:plain\"],\"harvest\":{\"type\":\"use\",\"holding\":\"hammer\"}}",
            "{\"action\":\"add\",\"id\":\"ttcompat:x\",\"blocks\":[\"mymod:plain\"],\"ripe\":{\"property\":\"age\"}}",
            "{\"action\":\"add\",\"id\":\"ttcompat:x\",\"blocks\":[\"mymod:plain\"],\"away\":{\"item\":\"hearthandharvest:blueberries\","
                + "\"count\":[3,2],\"picked_state\":{\"age\":\"1\"}}}",
            "{\"action\":\"add\",\"id\":\"ttcompat:x\",\"blocks\":[\"mymod:plain\"],\"away\":{\"item\":\"hearthandharvest:nope\","
                + "\"count\":2,\"picked_state\":{\"age\":\"1\"}}}")) {
            assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json(bad)), bad);
        }
    }

    @Test
    void changeAndRemove() {
        HarvestRule base = HarvestRule.builder(ResourceLocation.parse("ttcompat:x")).block(this.bush).build();
        WorkChange<ResourceLocation, HarvestRule> change = this.codec.read(FILE, json("""
            {"action":"change","id":"ttcompat:x","ripe":{"property":"age","at_least":3},"harvest":{"type":"use","holding":"shears"}}"""));
        assertEquals(WorkChange.Kind.CHANGE, change.kind());
        HarvestRule changed = change.change().apply(base);
        assertEquals(new Ripe.AtLeast("age", 3), changed.ripe());
        assertEquals(new HarvestAction.Use(Holding.SHEARS), changed.action());
        assertEquals(base.blocks(), changed.blocks(), "what a change doesn't name stays");
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json("{\"action\":\"change\",\"id\":\"ttcompat:x\"}")));
        WorkChange<ResourceLocation, HarvestRule> remove = this.codec.read(FILE, json("{\"action\":\"remove\",\"id\":\"ttcompat:x\"}"));
        assertEquals(WorkChange.Kind.REMOVE, remove.kind());
        assertEquals(ResourceLocation.parse("ttcompat:x"), remove.key());
    }

    @Test
    void unknownKeyLoggedOnce() {
        this.codec.read(FILE, json("{\"action\":\"add\",\"id\":\"ttcompat:x\",\"blocks\":[\"mymod:plain\"],\"ripen\":1}"));
        assertEquals(1, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("ripen"));
    }
}
