package townkit.core.work.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.work.Dish;
import townkit.core.work.WorkChange;

/** {@link DishCodec}: tag ingredients ({@code #} keys) and {@code keep_containers} (0.8.0). */
class DishCodecTest {
    private static final ResourceLocation FILE = ResourceLocation.parse("mymod:soup");

    private CookOverlayTest.TestItems items;
    private DishCodec codec;
    private Item melon;
    private Item bowl;
    private Item bread;

    private static TagKey<Item> tag(String id) {
        return TagKey.create(Registries.ITEM, ResourceLocation.parse(id));
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @BeforeEach
    void setUp() {
        this.items = new CookOverlayTest.TestItems();
        this.melon = this.items.item("minecraft:melon_slice");
        this.bowl = this.items.item("minecraft:bowl");
        this.bread = this.items.item("minecraft:bread");
        this.codec = new DishCodec(this.items);
    }

    @Test
    void hashKeyIsTag() {
        WorkChange<Item, Dish> add = this.codec.read(FILE, json(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"#c:crops/onion\":1,\"minecraft:bowl\":1}}"));
        Dish d = add.entry();
        assertEquals(1, d.tagIngredients().size());
        assertEquals(1, d.tagIngredients().get(tag("c:crops/onion")));
        assertEquals(1, d.ingredients().size());
        assertEquals(1, d.ingredients().get(this.bowl));
    }

    @Test
    void tagOnlyIngredientsAccepted() {
        WorkChange<Item, Dish> add = this.codec.read(FILE, json(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"#c:crops/onion\":2}}"));
        assertTrue(add.entry().ingredients().isEmpty());
        assertEquals(2, add.entry().tagIngredients().get(tag("c:crops/onion")));
    }

    @Test
    void badTagIdOrCountSkipped() {
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"#Bad Tag\":1}}")));
        // a count below 1 fails in Dish's own check (WorkFiles skips the file on any IllegalArgumentException)
        assertThrows(IllegalArgumentException.class, () -> this.codec.read(FILE, json(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"#c:crops/onion\":0}}")));
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"#c:crops/onion\":1.5}}")));
    }

    @Test
    void keepContainersParsed() {
        Dish on = this.codec.read(FILE, json(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:bowl\":1},\"keep_containers\":true}"))
            .entry();
        assertTrue(on.keepContainers());
        Dish off = this.codec.read(FILE, json(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:bowl\":1}}")).entry();
        assertFalse(off.keepContainers());
        assertThrows(JsonParseException.class, () -> this.codec.read(FILE, json(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:bowl\":1},\"keep_containers\":1}")));
        // a change may set only the flag
        WorkChange<Item, Dish> ch = this.codec.read(FILE, json(
            "{\"action\":\"change\",\"result\":\"minecraft:melon_slice\",\"keep_containers\":true}"));
        Dish base = Dish.builder(this.melon).ingredient(this.bowl, 1).build();
        assertTrue(ch.change().apply(base).keepContainers());
    }

    @Test
    void changeReplacesBothIngredientMaps() {
        Dish base = Dish.builder(this.melon).ingredient(this.bowl, 1).ingredient(tag("c:milks"), 1).build();
        Dish tagsNow = this.codec.read(FILE, json(
            "{\"action\":\"change\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"#c:eggs\":2}}")).change().apply(base);
        assertTrue(tagsNow.ingredients().isEmpty());
        assertEquals(java.util.Map.of(tag("c:eggs"), 2), tagsNow.tagIngredients());
        Dish itemsNow = this.codec.read(FILE, json(
            "{\"action\":\"change\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:bread\":2}}")).change().apply(base);
        assertEquals(java.util.Map.of(this.bread, 2), itemsNow.ingredients());
        assertTrue(itemsNow.tagIngredients().isEmpty());
    }

    @Test
    void writeRoundTripsTagsAndFlag() {
        Dish d = Dish.builder(this.melon).ingredient(this.bowl, 1).ingredient(tag("c:crops/onion"), 2).keepContainers(true).build();
        JsonObject out = this.codec.write(d);
        assertTrue(out.getAsJsonObject("ingredients").has("#c:crops/onion"));
        assertEquals(d, this.codec.read(FILE, out).entry());
    }
}
