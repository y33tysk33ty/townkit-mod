package townkit.core.work.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.work.Dish;
import townkit.api.work.DishChange;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkFiles;
import townkit.core.work.WorkTable;

/**
 * The cook overlay without a game: unit tests can't start Minecraft (its registries need FML), so the items here are
 * bare {@link Item} objects made without a constructor (identity is all the overlay needs) and the overlay is handed
 * their ids and names. Towntopia's real settings schema ({@link CookSettingsSchema#SCHEMA}) is used as is.
 */
class CookOverlayTest {
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

    /** A bare item that names itself (Item's own toString needs the registries; log lines print items). */
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

    /** Item ids and names for the bare test items. */
    static final class TestItems implements CookOverlay.ItemIds {
        final Map<Item, ResourceLocation> ids = new HashMap<>();
        final Map<ResourceLocation, Item> byId = new HashMap<>();
        final Map<Item, Integer> defaultNutrition = new HashMap<>();
        /** Item tags and their members, in tag order (a tag not listed has none). */
        final Map<net.minecraft.tags.TagKey<Item>, List<Item>> tags = new HashMap<>();

        @Override
        public List<Item> tagMembers(net.minecraft.tags.TagKey<Item> tag) {
            return this.tags.getOrDefault(tag, List.of());
        }

        Item item(String id) {
            try {
                ResourceLocation rl = ResourceLocation.parse(id);
                TestItem i = (TestItem) UNSAFE.allocateInstance(TestItem.class);
                i.id = rl;
                this.ids.put(i, rl);
                this.byId.put(rl, i);
                return i;
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ResourceLocation id(Item item) {
            return this.ids.get(item);
        }

        @Nullable
        @Override
        public Item byId(ResourceLocation id) {
            return this.byId.get(id);
        }

        @Override
        public int defaultNutrition(Item item) {
            return this.defaultNutrition.getOrDefault(item, 0);
        }

        @Override
        public String name(Item item) {
            String p = this.ids.get(item).getPath();
            return Character.toUpperCase(p.charAt(0)) + p.substring(1).replace('_', ' ');
        }
    }

    private TestItems items;
    private Item bread;
    private Item cake;
    private Item wheat;
    private Item sugar;
    private Item egg;
    private Item milk;
    private Item apple;
    private Item melon;
    private List<CookActivity.Recipe> towntopia;
    private WorkTable<Item, Dish> table;
    private CookOverlay overlay;

    private static Map<Item, Integer> in(Object... pairs) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((Item) pairs[i], (Integer) pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }

    @BeforeEach
    void setUp() {
        this.items = new TestItems();
        this.bread = this.items.item("minecraft:bread");
        this.cake = this.items.item("minecraft:cake");
        this.wheat = this.items.item("minecraft:wheat");
        this.sugar = this.items.item("minecraft:sugar");
        this.egg = this.items.item("minecraft:egg");
        this.milk = this.items.item("minecraft:milk_bucket");
        this.apple = this.items.item("minecraft:apple");
        this.melon = this.items.item("minecraft:melon_slice");
        this.towntopia = List.of(
            new CookActivity.Recipe(this.cake, 1, in(this.wheat, 3, this.sugar, 2, this.egg, 1, this.milk, 3), false, 13),
            new CookActivity.Recipe(this.bread, 1, in(this.wheat, 3), false, 7));
        this.table = new WorkTable<>(CookOverlay.KIND, Dish::result);
        this.overlay = new CookOverlay(this.table, () -> this.towntopia, () -> CookSettingsSchema.SCHEMA, this.items);
    }

    private Dish melonDish(boolean heated) {
        return Dish.builder(this.melon).output(2).ingredient(this.apple, 2).ingredient(this.sugar, 1).heated(heated).nutrition(8).build();
    }

    @Nullable
    private static CookActivity.Recipe recipe(List<CookActivity.Recipe> list, Item result) {
        return list.stream().filter(r -> r.result() == result).findFirst().orElse(null);
    }

    private static Optional<SettingsSchema.AllowListEntry> entry(SettingsSchema s, String id) {
        return s.findRow(CookSettingsSchema.ALLOWED_RECIPES).orElseThrow().allowListEntries().stream()
            .filter(e -> e.id().equals(id)).findFirst();
    }

    @Test
    void nutritionZeroPlansWithItemNutrition() {
        this.items.defaultNutrition.put(this.melon, 6);
        this.overlay.add("a", Dish.builder(this.melon).ingredient(this.apple, 2).nutrition(0).build());
        assertEquals(6, recipe(this.overlay.recipes(), this.melon).nutrition());
        assertEquals(0, recipe(this.overlay.foodRecipes(), this.melon).nutrition());
        // the food view keeps the planning recipes' other dishes as they are
        assertSame(recipe(this.overlay.recipes(), this.bread), recipe(this.overlay.foodRecipes(), this.bread));
        // a food-view recipe is not a live planning recipe, and not a stale one
        CookActivity.Recipe food = recipe(this.overlay.foodRecipes(), this.melon);
        assertFalse(this.overlay.isLive(food));
        assertFalse(this.overlay.isStale(food));
        assertSame(food, recipe(this.overlay.foodRecipesFor(this.towntopia), this.melon));
    }

    @Test
    void nutritionZeroNonFoodIsZeroInBoth() {
        this.overlay.add("a", Dish.builder(this.melon).ingredient(this.apple, 2).nutrition(0).build());
        assertEquals(0, recipe(this.overlay.recipes(), this.melon).nutrition());
        assertEquals(0, recipe(this.overlay.foodRecipes(), this.melon).nutrition());
        assertSame(recipe(this.overlay.recipes(), this.melon), recipe(this.overlay.foodRecipes(), this.melon));
    }

    @Test
    void positiveNutritionSameInBoth() {
        this.items.defaultNutrition.put(this.melon, 6);
        this.overlay.add("a", melonDish(false));
        assertEquals(8, recipe(this.overlay.recipes(), this.melon).nutrition());
        assertEquals(8, recipe(this.overlay.foodRecipes(), this.melon).nutrition());
    }

    @Test
    void untouchedTableFoodViewIsTowntopias() {
        assertSame(this.towntopia, this.overlay.foodRecipes());
        assertSame(this.towntopia, this.overlay.foodRecipesFor(this.towntopia));
    }

    @Test
    void untouchedReturnsTowntopiaList() {
        assertFalse(this.overlay.touched());
        assertSame(this.towntopia, this.overlay.recipes());
        assertSame(this.towntopia, this.overlay.recipesFor(this.towntopia));
        assertSame(this.towntopia, this.overlay.plannedRecipesFor(this.towntopia));
        Set<Item> dishes = Set.of(this.bread, this.cake);
        assertSame(dishes, this.overlay.dishesFor(dishes));
        assertSame(CookSettingsSchema.SCHEMA, this.overlay.schemaFor(CookSettingsSchema.SCHEMA));
        // the API view still answers: Towntopia's dishes as TownKit dishes
        assertEquals(2, this.overlay.dishes().size());
        Dish b = this.overlay.dishes().get(1);
        assertSame(this.bread, b.result());
        assertEquals(Map.of(this.wheat, 3), b.ingredients());
        assertFalse(b.heated());
        assertEquals(7, b.nutrition());
        assertNull(b.station());
        assertTrue(this.overlay.isDish(this.cake));
    }

    @Test
    void addedDishConverts() {
        this.overlay.add("addon", melonDish(true));
        assertTrue(this.overlay.touched());
        List<CookActivity.Recipe> r = this.overlay.recipesFor(this.towntopia);
        assertEquals(3, r.size());
        // Towntopia's own entries stay Towntopia's own objects
        assertSame(this.towntopia.get(0), r.get(0));
        assertSame(this.towntopia.get(1), r.get(1));
        assertEquals(new CookActivity.Recipe(this.melon, 2, in(this.apple, 2, this.sugar, 1), true, 8), r.get(2));
        assertSame(r, this.overlay.recipes());
        assertSame(r, this.overlay.recipesFor(this.towntopia), "cached until something changes");
        assertTrue(this.overlay.isDish(this.melon));
        assertTrue(this.overlay.dishesFor(Set.of(this.bread, this.cake)).contains(this.melon));
        assertTrue(this.overlay.isLive(r.get(2)));
        assertFalse(this.overlay.isLive(new CookActivity.Recipe(this.melon, 2, in(this.apple, 2, this.sugar, 1), true, 8)),
            "a copy is not the live recipe");
    }

    @Test
    void changeBread() {
        long before = this.overlay.version();
        this.overlay.change("addon", this.bread, DishChange.builder().ingredient(this.wheat, 2).build());
        assertTrue(this.overlay.version() > before);
        CookActivity.Recipe b = recipe(this.overlay.recipes(), this.bread);
        assertNotNull(b);
        assertEquals(new CookActivity.Recipe(this.bread, 1, in(this.wheat, 2), false, 7), b);
        assertFalse(this.overlay.isLive(this.towntopia.get(1)), "Towntopia's 3-wheat bread is not live now");
        assertTrue(this.overlay.isStale(this.towntopia.get(1)), "a cook holding it ends that batch");
        assertFalse(this.overlay.isStale(b));
        assertFalse(this.overlay.isStale(new CookActivity.Recipe(this.sugar, 1, in(this.wheat, 1), false, 0)),
            "a side job the planner built itself is never stale");
        assertSame(this.towntopia.get(0), recipe(this.overlay.recipes(), this.cake));
        SettingsSchema s = this.overlay.schema();
        String needs = entry(s, "minecraft:bread").orElseThrow().description();
        assertTrue(needs.contains("2 Wheat"), needs);
        assertEquals("Bread", entry(s, "minecraft:bread").orElseThrow().label(), "Towntopia's label kept");
        assertTrue(s.findRow("stock_bread").isPresent());
    }

    @Test
    void removeCake() {
        this.overlay.remove("addon", this.cake);
        assertNull(recipe(this.overlay.recipes(), this.cake));
        assertFalse(this.overlay.isDish(this.cake));
        assertFalse(this.overlay.dishesFor(Set.of(this.cake, this.bread)).contains(this.cake));
        SettingsSchema s = this.overlay.schemaFor(CookSettingsSchema.SCHEMA);
        assertTrue(entry(s, "minecraft:cake").isEmpty());
        assertTrue(s.findRow("stock_cake").isEmpty());
        // everything else is Towntopia's
        assertTrue(entry(s, "minecraft:bread").isPresent());
        assertTrue(entry(s, "minecraft:rabbit_stew").isPresent());
        assertTrue(s.findRow(CookSettingsSchema.GOLD_RESERVE).isPresent());
        assertEquals(CookSettingsSchema.SCHEMA.tagName(), s.tagName());
    }

    @Test
    void ingredientsFollowMerged() {
        assertEquals(Set.of(this.wheat, this.sugar, this.egg, this.milk), this.overlay.ingredients());
        Set<Item> original = Set.of(this.wheat, this.sugar, this.egg, this.milk);
        assertSame(original, this.overlay.ingredientsFor(original));
        WorkTable.Handle cakeGone = this.overlay.remove("addon", this.cake);
        assertEquals(Set.of(this.wheat), this.overlay.ingredientsFor(original));
        this.overlay.add("addon", melonDish(false));
        assertEquals(Set.of(this.wheat, this.apple, this.sugar), this.overlay.ingredients());
        cakeGone.remove();
        assertEquals(Set.of(this.wheat, this.sugar, this.egg, this.milk, this.apple), this.overlay.ingredients());
    }

    @Test
    void tagDishesWithAnEmptyTagAreLeftOut() {
        // a tag with no members gives no variant: the dish never reaches Towntopia (a recipe with missing inputs)
        net.minecraft.tags.TagKey<Item> milks = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            ResourceLocation.parse("c:milks"));
        this.overlay.add("a", Dish.builder(this.melon).ingredient(this.apple, 1).ingredient(milks, 1).build());
        this.overlay.add("a", Dish.builder(this.milk).ingredient(milks, 1).build());
        assertNull(recipe(this.overlay.recipes(), this.melon));
        assertNull(recipe(this.overlay.foodRecipes(), this.melon));
        assertFalse(this.overlay.isDish(this.melon));
        assertFalse(this.overlay.isDish(this.milk));
        assertTrue(this.overlay.isDish(this.cake));
        assertFalse(this.overlay.ingredients().contains(this.apple));
        assertNotNull(recipe(this.overlay.recipes(), this.bread)); // item-only dishes as before
        // an item-only dish beside it behaves as in 0.7.1
        this.overlay.add("b", Dish.builder(this.egg).ingredient(this.sugar, 1).build());
        assertNotNull(recipe(this.overlay.recipes(), this.egg));
    }

    @Test
    void dishCodecRoundTrip() {
        DishCodec codec = new DishCodec(this.items);
        ResourceLocation file = ResourceLocation.parse("mymod:melon");
        Dish d = Dish.builder(this.melon).output(2).ingredient(this.apple, 2).ingredient(this.sugar, 1).heated(true).nutrition(8)
            .station(ResourceLocation.parse("mymod:pot")).stock(40).build();
        JsonObject json = codec.write(d);
        assertEquals("add", json.get("action").getAsString());
        WorkChange<Item, Dish> back = codec.read(file, json);
        assertEquals(WorkChange.Kind.ADD, back.kind());
        assertSame(this.melon, back.key());
        assertEquals(d, back.entry());
        assertTrue(back.owner().contains("mymod:melon"), back.owner());

        // the brief's own example, field for field
        WorkChange<Item, Dish> ex = codec.read(file, JsonParser.parseString("{\"action\":\"add\",\"result\":\"minecraft:melon_slice\","
            + "\"output\":1,\"ingredients\":{\"minecraft:apple\":2,\"minecraft:sugar\":1},\"heated\":true,\"nutrition\":8,"
            + "\"station\":\"mymod:pot\",\"stock\":24}").getAsJsonObject());
        assertEquals(Dish.builder(this.melon).ingredient(this.apple, 2).ingredient(this.sugar, 1).heated(true).nutrition(8)
            .station(ResourceLocation.parse("mymod:pot")).stock(24).build(), ex.entry());

        // change: only the fields given
        WorkChange<Item, Dish> ch = codec.read(file, JsonParser.parseString(
            "{\"action\":\"change\",\"result\":\"minecraft:bread\",\"nutrition\":9}").getAsJsonObject());
        assertEquals(WorkChange.Kind.CHANGE, ch.kind());
        Dish breadDish = this.overlay.dishes().get(1);
        Dish changed = ch.change().apply(breadDish);
        assertEquals(9, changed.nutrition());
        assertEquals(breadDish.ingredients(), changed.ingredients());
        assertEquals(breadDish.outputPerBatch(), changed.outputPerBatch());

        WorkChange<Item, Dish> rm = codec.read(file, JsonParser.parseString(
            "{\"action\":\"remove\",\"result\":\"minecraft:cake\"}").getAsJsonObject());
        assertEquals(WorkChange.Kind.REMOVE, rm.kind());
        assertSame(this.cake, rm.key());
    }

    @Test
    void badItemSkipped() {
        DishCodec codec = new DishCodec(this.items);
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(ResourceLocation.parse("mymod:good"), JsonParser.parseString(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:apple\":2}}"));
        files.put(ResourceLocation.parse("mymod:no_such_result"), JsonParser.parseString(
            "{\"action\":\"add\",\"result\":\"mymod:nothing\",\"ingredients\":{\"minecraft:apple\":2}}"));
        files.put(ResourceLocation.parse("mymod:no_such_ingredient"), JsonParser.parseString(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"mymod:bean\":2}}"));
        files.put(ResourceLocation.parse("mymod:zero"), JsonParser.parseString(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:apple\":0}}"));
        files.put(ResourceLocation.parse("mymod:half"), JsonParser.parseString(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:apple\":1.5}}"));
        files.put(ResourceLocation.parse("mymod:too_rich"), JsonParser.parseString(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:apple\":1},\"nutrition\":21}"));
        files.put(ResourceLocation.parse("mymod:no_stock"), JsonParser.parseString(
            "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:apple\":1},\"stock\":0}"));
        files.put(ResourceLocation.parse("mymod:empty_change"), JsonParser.parseString(
            "{\"action\":\"change\",\"result\":\"minecraft:bread\"}"));
        List<WorkChange<Item, Dish>> out = WorkFiles.parse(CookOverlay.KIND, files, codec);
        assertEquals(1, out.size());
        assertTrue(out.get(0).owner().contains("mymod:good"));
        this.table.setFileChanges(out);
        assertEquals(3, this.overlay.recipes().size());
        assertThrows(RuntimeException.class, () -> codec.read(ResourceLocation.parse("mymod:x"), JsonParser.parseString(
            "{\"action\":\"add\",\"result\":\"mymod:nothing\",\"ingredients\":{\"minecraft:apple\":2}}").getAsJsonObject()));
    }

    /**
     * Settings rows are keyed the way Towntopia keys them (the item id in "allowed recipes", its
     * {@code stockId} for the stock row), and TownKit only adds or drops rows: a saved value for a dish that is gone
     * stays in the villager's saved settings (TownKit never writes them) and is read again when the dish comes back.
     */
    @Test
    void unknownDishIdKeptInSettings() {
        Item stew = this.items.item("mymod:stew");
        Dish d = Dish.builder(stew).ingredient(this.wheat, 1).build();
        WorkTable.Handle h = this.overlay.add("addon", d);
        SettingsSchema with = this.overlay.schema();
        SettingsSchema.AllowListEntry e = entry(with, "mymod:stew").orElseThrow();
        assertEquals("Stew", e.label());
        assertTrue(e.defaultAllowed());
        assertTrue(e.description().contains("1 Wheat"), e.description());
        SettingsSchema.Row stock = with.findRow(CookSettingsSchema.stockId("mymod:stew")).orElseThrow();
        assertEquals(Dish.DEFAULT_STOCK, stock.defaultInt());
        assertEquals(SettingsSchema.RowKind.NUMERIC_STEPPER, stock.kind());

        h.remove();
        SettingsSchema without = this.overlay.schemaFor(CookSettingsSchema.SCHEMA);
        assertTrue(entry(without, "mymod:stew").isEmpty());
        assertTrue(without.findRow(CookSettingsSchema.stockId("mymod:stew")).isEmpty());
        assertEquals(CookSettingsSchema.SCHEMA.tagName(), without.tagName(), "saved values stay under Towntopia's tag");

        this.overlay.add("addon", d);
        SettingsSchema again = this.overlay.schema();
        assertEquals(e, entry(again, "mymod:stew").orElseThrow(), "same id: the saved switch applies again");
        assertTrue(again.findRow(CookSettingsSchema.stockId("mymod:stew")).isPresent());
    }

    /** A dish with a station is listed (settings, orders) but never planned until a station exists for the cook to use. */
    @Test
    void stationDishNotPlanned() {
        Item stew = this.items.item("mymod:stew");
        ResourceLocation pot = ResourceLocation.parse("mymod:pot");
        this.overlay.add("addon", Dish.builder(stew).ingredient(this.wheat, 1).station(pot).build());
        assertNotNull(recipe(this.overlay.recipes(), stew));
        assertNull(recipe(this.overlay.plannedRecipesFor(this.towntopia), stew));
        assertEquals(pot, this.overlay.stationOf(stew));
        assertNull(this.overlay.stationOf(this.bread));
        assertTrue(this.overlay.hasStationDishes());
        assertTrue(entry(this.overlay.schema(), "mymod:stew").isPresent());
    }

    /**
     * A rebuild that leaves a dish as it was (a reload of the same files, an unrelated
     * registration) keeps its recipe object, so a cook mid-batch on it carries on; only a real change makes it stale.
     */
    @Test
    void unchangedDishKeepsItsRecipeAcrossRebuilds() {
        Item stew = this.items.item("mymod:stew");
        ResourceLocation file = ResourceLocation.parse("mymod:melon");
        this.table.setFileChanges(List.of(WorkChange.add("data file " + file, this.melon, melonDish(false))));
        CookActivity.Recipe melonRecipe = recipe(this.overlay.recipes(), this.melon);
        WorkTable.Handle bread = this.overlay.change("addon", this.bread, DishChange.builder().ingredient(this.wheat, 2).build());
        CookActivity.Recipe breadRecipe = recipe(this.overlay.recipes(), this.bread);

        // the same file again (new Dish objects, equal values) and an unrelated addon registering and leaving
        this.table.setFileChanges(List.of(WorkChange.add("data file " + file, this.melon, melonDish(false))));
        WorkTable.Handle other = this.overlay.add("other", Dish.builder(stew).ingredient(this.wheat, 1).build());
        this.overlay.recipes();
        other.remove();
        assertSame(melonRecipe, recipe(this.overlay.recipes(), this.melon));
        assertSame(breadRecipe, recipe(this.overlay.recipes(), this.bread));
        assertFalse(this.overlay.isStale(melonRecipe));
        assertFalse(this.overlay.isStale(breadRecipe));
        // a stock-only change doesn't touch what a batch makes
        WorkTable.Handle stock = this.overlay.change("addon", this.melon, DishChange.builder().stock(40).build());
        assertSame(melonRecipe, recipe(this.overlay.recipes(), this.melon));
        stock.remove();

        // a real change makes it stale
        this.table.setFileChanges(List.of(WorkChange.add("data file " + file, this.melon, melonDish(true))));
        assertTrue(this.overlay.isStale(melonRecipe));
        bread.remove();
        assertSame(this.towntopia.get(1), recipe(this.overlay.recipes(), this.bread), "Towntopia's own object again");
        assertTrue(this.overlay.isStale(breadRecipe), "back to Towntopia's 3-wheat bread");
    }

    /** What the overlay remembers stays bounded however often the list is rebuilt. */
    @Test
    void knownRecipesStayBounded() {
        for (int i = 0; i < 200; i++) {
            this.table.setFileChanges(List.of(WorkChange.add("data file mymod:melon", this.melon,
                Dish.builder(this.melon).ingredient(this.apple, 1 + i % 50).build())));
            this.overlay.recipes();
        }
        int bound = (CookOverlay.HISTORY + 1) * 3 + this.towntopia.size();
        assertTrue(this.overlay.knownSize() <= bound, "known " + this.overlay.knownSize() + " > " + bound);
    }

    /** With all of Towntopia's (test) dishes removed, an added dish still gets its stock row. */
    @Test
    void addedDishGetsStockRowWithTowntopiasRemoved() {
        this.overlay.remove("pack", this.bread);
        this.overlay.remove("pack", this.cake);
        this.overlay.add("pack", melonDish(false));
        SettingsSchema s = this.overlay.schema();
        SettingsSchema.Row row = s.findRow("stock_melon_slice").orElseThrow();
        assertEquals(Dish.DEFAULT_STOCK, row.defaultInt());
        assertTrue(s.findRow("stock_bread").isEmpty() && s.findRow("stock_cake").isEmpty());
        assertTrue(stockSection(s).rows().contains(row), "in Towntopia's stock section");
    }

    private static SettingsSchema.Section stockSection(SettingsSchema s) {
        return s.sections().stream()
            .filter(sec -> sec.rows().stream().anyMatch(r -> r.settingId().equals(CookSettingsSchema.GOLD_RESERVE)))
            .findFirst().orElseThrow();
    }

    /** A dish's stock is its row's default, also for a Towntopia dish an addon replaces or changes. */
    @Test
    void dishStockIsTheRowDefault() {
        int towntopiaBread = CookSettingsSchema.SCHEMA.findRow("stock_bread").orElseThrow().defaultInt();
        assertEquals(towntopiaBread, this.overlay.dishes().get(1).stock(), "Towntopia's dishes carry their own stock");
        this.overlay.add("addon", Dish.builder(this.melon).ingredient(this.apple, 1).stock(40).build());
        assertEquals(40, this.overlay.schema().findRow("stock_melon_slice").orElseThrow().defaultInt());
        WorkTable.Handle nutrition = this.overlay.change("addon", this.bread, DishChange.builder().nutrition(9).build());
        assertEquals(towntopiaBread, this.overlay.schema().findRow("stock_bread").orElseThrow().defaultInt(), "stock not changed");
        nutrition.remove();
        WorkTable.Handle stock = this.overlay.change("addon", this.bread, DishChange.builder().stock(24).build());
        assertEquals(24, this.overlay.schema().findRow("stock_bread").orElseThrow().defaultInt());
        stock.remove();
        this.overlay.add("addon", Dish.builder(this.bread).ingredient(this.wheat, 1).stock(5).build());
        SettingsSchema.Row replaced = this.overlay.schema().findRow("stock_bread").orElseThrow();
        assertEquals(5, replaced.defaultInt(), "a replacing add uses its own stock");
        assertEquals("Bread", replaced.label());
        // a stock above Towntopia's row maximum still fits
        this.overlay.add("addon", Dish.builder(this.melon).ingredient(this.apple, 1).stock(500).build());
        assertTrue(this.overlay.schema().findRow("stock_melon_slice").orElseThrow().maxInt() >= 500);
    }

    /** A removed Towntopia dish frees its stock row id for an added dish with the same item path. */
    @Test
    void removedDishFreesItsRowId() {
        Item modBread = this.items.item("mymod:bread");
        this.overlay.remove("addon", this.bread);
        this.overlay.add("addon", Dish.builder(modBread).ingredient(this.wheat, 1).stock(12).build());
        SettingsSchema.Row row = this.overlay.schema().findRow("stock_bread").orElseThrow();
        assertEquals(12, row.defaultInt(), "the added dish's row, not Towntopia's");
        assertTrue(entry(this.overlay.schema(), "mymod:bread").isPresent());
        assertTrue(entry(this.overlay.schema(), "minecraft:bread").isEmpty());
    }

    @Test
    void dishBuilderRules() {
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(this.melon).build(), "no ingredients");
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(this.melon).ingredient(this.apple, 0).build());
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(this.melon).ingredient(this.apple, 1).output(0).build());
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(this.melon).ingredient(this.apple, 1).nutrition(21).build());
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(this.melon).ingredient(this.apple, 1).nutrition(-1).build());
        assertThrows(NullPointerException.class, () -> Dish.builder(null));
        Dish d = Dish.builder(this.melon).ingredient(this.apple, 1).build();
        assertEquals(1, d.outputPerBatch());
        assertFalse(d.heated());
        assertEquals(5, d.nutrition());
        assertNull(d.station());
        assertEquals(Dish.DEFAULT_STOCK, d.stock());
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(this.melon).ingredient(this.apple, 1).stock(0).build());
        assertEquals(40, Dish.builder(this.melon).ingredient(this.apple, 1).stock(40).build().stock());
        assertEquals(Dish.DEFAULT_STOCK, DishChange.builder().nutrition(3).build().apply(d).stock(), "unset stock is kept");
        assertEquals(24, DishChange.builder().stock(24).build().apply(d).stock());
        assertThrows(IllegalArgumentException.class, () -> DishChange.builder().stock(0).build());
        assertThrows(UnsupportedOperationException.class, () -> d.ingredients().put(this.sugar, 1));
        // a change keeps what it doesn't set; ingredients, when set, replace the whole list
        Dish c = DishChange.builder().nutrition(9).build().apply(d);
        assertEquals(Map.of(this.apple, 1), c.ingredients());
        assertEquals(9, c.nutrition());
        Dish c2 = DishChange.builder().ingredient(this.sugar, 3).station(ResourceLocation.parse("mymod:pot")).build().apply(d);
        assertEquals(Map.of(this.sugar, 3), c2.ingredients());
        assertEquals(ResourceLocation.parse("mymod:pot"), c2.station());
        assertNull(DishChange.builder().station(null).build().apply(c2).station(), "station(null) clears it");
        assertThrows(IllegalArgumentException.class, () -> DishChange.builder().output(0).build());
    }
}
