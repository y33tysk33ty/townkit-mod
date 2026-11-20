package townkit.core.work.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.work.Dish;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/**
 * W15 tag ingredients without a game: a tag dish's recipe variants (the product of its tags' members), the one recipe
 * per dish the overlay hands Towntopia, and the swap of its inputs inside a village's context (the bare items and
 * overlay of {@link CookOverlayTest}).
 */
class CookVariantsTest {
    private CookOverlayTest.TestItems items;
    private Item a;
    private Item b;
    private Item c;
    private Item x;
    private Item y;
    private Item z;
    private Item salt;
    private Item stew;
    private Item bread;
    private Item wheat;
    private TagKey<Item> veg;
    private TagKey<Item> herbs;
    private WorkTable<Item, Dish> table;
    private CookOverlay overlay;
    private CookVariants variants;
    private long now;

    private static TagKey<Item> tag(String id) {
        return TagKey.create(Registries.ITEM, ResourceLocation.parse(id));
    }

    private static Map<Item, Integer> in(Object... pairs) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((Item) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    @BeforeEach
    void setUp() {
        this.items = new CookOverlayTest.TestItems();
        this.a = this.items.item("mymod:a");
        this.b = this.items.item("mymod:b");
        this.c = this.items.item("mymod:c");
        this.x = this.items.item("mymod:x");
        this.y = this.items.item("mymod:y");
        this.z = this.items.item("mymod:z");
        this.salt = this.items.item("mymod:salt");
        this.stew = this.items.item("mymod:stew");
        this.bread = this.items.item("minecraft:bread");
        this.wheat = this.items.item("minecraft:wheat");
        this.veg = tag("c:test_veg");
        this.herbs = tag("c:test_herbs");
        this.items.tags.put(this.veg, List.of(this.a, this.b));
        this.items.tags.put(this.herbs, List.of(this.x));
        List<CookActivity.Recipe> towntopia = List.of(new CookActivity.Recipe(this.bread, 1, in(this.wheat, 3), false, 7));
        this.table = new WorkTable<>(CookOverlay.KIND, Dish::result);
        this.overlay = new CookOverlay(this.table, () -> towntopia, () -> CookSettingsSchema.SCHEMA, this.items);
        this.variants = new CookVariants(this.overlay);
    }

    /** One stew: 1 salt, 2 of the veg tag. */
    private Dish vegStew() {
        return Dish.builder(this.stew).ingredient(this.salt, 1).ingredient(this.veg, 2).nutrition(8).build();
    }

    @Nullable
    private CookActivity.Recipe recipe(Item result) {
        return this.overlay.recipes().stream().filter(r -> r.result() == result).findFirst().orElse(null);
    }

    /** Enters {@code village}'s context with these storage + pack counts; {@code running}: the cook's running dish. */
    private void enter(Object village, Object cook, @Nullable Item running, Map<Item, Integer> counts) {
        this.variants.enter(village, cook, () -> this.now, cook, running, () -> counts);
    }

    @Test
    void productInTagOrder() {
        Dish d = Dish.builder(this.stew).ingredient(this.veg, 1).ingredient(this.herbs, 1).build();
        List<Map<Item, Integer>> v = CookVariants.variants(d, this.items::tagMembers);
        assertEquals(List.of(in(this.a, 1, this.x, 1), in(this.b, 1, this.x, 1)), v);
        // fixed ingredients come first in every variant, with the dish's counts
        List<Map<Item, Integer>> s = CookVariants.variants(vegStew(), this.items::tagMembers);
        assertEquals(List.of(in(this.salt, 1, this.a, 2), in(this.salt, 1, this.b, 2)), s);
        assertEquals(List.of(this.salt, this.a), new ArrayList<>(s.get(0).keySet()), "fixed first, then the tags in order");
    }

    @Test
    void capAtEight() {
        this.items.tags.put(this.veg, List.of(this.a, this.b, this.c));
        this.items.tags.put(this.herbs, List.of(this.x, this.y, this.z));
        Dish d = Dish.builder(this.stew).ingredient(this.veg, 1).ingredient(this.herbs, 1).build();
        List<String> lines = new ArrayList<>();
        WorkLog log = new WorkLog(lines::add);
        List<Map<Item, Integer>> v = CookVariants.variants(d, this.items::tagMembers, log);
        assertEquals(CookVariants.MAX_VARIANTS, v.size());
        assertEquals(8, CookVariants.MAX_VARIANTS);
        assertEquals(in(this.a, 1, this.x, 1), v.get(0));
        assertEquals(in(this.a, 1, this.y, 1), v.get(1), "the first tag varies slowest");
        assertEquals(in(this.c, 1, this.y, 1), v.get(7));
        CookVariants.variants(d, this.items::tagMembers, log);
        assertEquals(1, lines.size(), "logged once: " + lines);
        assertTrue(lines.get(0).contains("mymod:stew"), lines.get(0));
    }

    @Test
    void emptyTagSkipsDish() {
        TagKey<Item> none = tag("c:nothing");
        Dish d = Dish.builder(this.stew).ingredient(this.salt, 1).ingredient(none, 1).build();
        assertTrue(CookVariants.variants(d, this.items::tagMembers).isEmpty());
        this.overlay.add("addon", d);
        assertNull(recipe(this.stew));
        assertFalse(this.overlay.isDish(this.stew));
        assertFalse(this.overlay.hasTagDishes());
        assertNotNull(recipe(this.bread));
    }

    @Test
    void oneRecipePerDishWithTheFirstVariant() {
        this.overlay.add("addon", vegStew());
        List<CookActivity.Recipe> stews = this.overlay.recipes().stream().filter(r -> r.result() == this.stew).toList();
        assertEquals(1, stews.size());
        assertEquals(in(this.salt, 1, this.a, 2), stews.get(0).inputsPerBatch());
        assertTrue(this.overlay.hasTagDishes());
        assertEquals(2, this.overlay.variantsOf(stews.get(0)).size());
        assertNull(this.overlay.variantsOf(recipe(this.bread)), "an item-only dish has no variants");
        // the food view and the planning view still line up dish by dish
        assertEquals(this.overlay.recipes().size(), this.overlay.foodRecipes().size());
    }

    @Test
    void onInputsOutsideContextIsFirstVariant() {
        this.overlay.add("addon", vegStew());
        CookActivity.Recipe r = recipe(this.stew);
        assertNotNull(r);
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
        // a recipe TownKit didn't hand out (a side job, Towntopia's own) is answered as it is
        Map<Item, Integer> own = in(this.wheat, 3);
        assertSame(own, this.variants.inputs(new CookActivity.Recipe(this.bread, 1, own, false, 7), own));
    }

    @Test
    void choosesMostStocked() {
        this.overlay.add("addon", vegStew());
        CookActivity.Recipe r = recipe(this.stew);
        Object village = new Object();
        Object cook = new Object();
        enter(village, cook, null, Map.of(this.salt, 5, this.a, 2, this.b, 9));
        assertEquals(in(this.salt, 1, this.b, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exit(cook);
        // only variants whose inputs are all there: a batch needs 2, so a (2) is there and b (1) isn't
        this.now++;
        enter(village, cook, null, Map.of(this.salt, 5, this.a, 2, this.b, 1));
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exit(cook);
        // after exit: the first variant again
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
    }

    /** Sets an item's crafting remainder, the way Towntopia's registry does. */
    private static void remainder(Item item, Item left) throws Exception {
        java.lang.reflect.Field f = Item.class.getDeclaredField("craftingRemainingItem");
        f.setAccessible(true);
        f.set(item, left);
    }

    /** A tag dish that keeps containers: the remainders follow the variant the village actually consumes. */
    @Test
    void keepContainersFollowTheVariantConsumed() throws Exception {
        Item bucket = this.items.item("minecraft:bucket");
        Item bottle = this.items.item("minecraft:glass_bottle");
        remainder(this.a, bucket);
        remainder(this.b, bottle);
        this.overlay.add("addon", Dish.builder(this.stew).ingredient(this.salt, 1).ingredient(this.veg, 2).keepContainers(true).build());
        assertTrue(this.overlay.keepsContainers(this.stew));
        CookActivity.Recipe r = recipe(this.stew);
        Object village = new Object();
        Object cook = new Object();
        // the village has more of b: the batch consumes b, so the bottle comes back, not the bucket
        enter(village, cook, null, Map.of(this.salt, 5, this.a, 2, this.b, 9));
        Map<Item, Integer> consumed = this.variants.inputs(r, r.inputsPerBatch());
        assertEquals(in(this.salt, 1, this.b, 2), consumed);
        assertEquals(Map.of(bottle, 2), Containers.remainderCounts(consumed));
        this.variants.exit(cook);
        // the other village has more of a: the bucket
        enter(new Object(), cook, null, Map.of(this.salt, 5, this.a, 9, this.b, 2));
        assertEquals(Map.of(bucket, 2), Containers.remainderCounts(this.variants.inputs(r, r.inputsPerBatch())));
        this.variants.exit(cook);
    }

    @Test
    void choiceIsPerVillage() {
        this.overlay.add("addon", vegStew());
        CookActivity.Recipe r = recipe(this.stew);
        Object v1 = new Object();
        Object v2 = new Object();
        Object cook = new Object();
        enter(v1, cook, null, Map.of(this.salt, 5, this.a, 2, this.b, 9));
        assertEquals(in(this.salt, 1, this.b, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exit(cook);
        enter(v2, cook, null, Map.of(this.salt, 5, this.a, 9, this.b, 2));
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exit(cook);
    }

    @Test
    void keepsChoiceWhileBatchRuns() {
        this.overlay.add("addon", vegStew());
        CookActivity.Recipe r = recipe(this.stew);
        Object village = new Object();
        Object cook = new Object();
        Object other = new Object();
        // the cook plans and starts a batch of a (its recipe is set by the end of the tick)
        enter(village, cook, null, Map.of(this.salt, 5, this.a, 6, this.b, 2));
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exitCook(cook, this.stew);
        // the batch runs; the pantry now favours b: her batch stays on a (fetch and consume agree)
        for (int t = 0; t < 5; t++) {
            this.now++;
            enter(village, cook, this.stew, Map.of(this.salt, 5, this.a, 2, this.b, 20));
            assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()), "tick " + t);
            this.variants.exitCook(cook, this.stew);
        }
        // the batch ends: the choice follows the pantry again
        this.now++;
        enter(village, cook, null, Map.of(this.salt, 5, this.a, 2, this.b, 20));
        assertEquals(in(this.salt, 1, this.b, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exitCook(cook, null);
    }

    /** The kept variant is the running cook's own: another cook of the village chooses from what she has. */
    @Test
    void anotherCookChoosesFreelyWhileOneRuns() {
        this.overlay.add("addon", vegStew());
        CookActivity.Recipe r = recipe(this.stew);
        Object village = new Object();
        Object cookA = new Object();
        Object cookB = new Object();
        enter(village, cookA, null, Map.of(this.salt, 5, this.a, 6));
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exitCook(cookA, this.stew);
        this.now++;
        // cook B: only b in her pack and storage
        enter(village, cookB, null, Map.of(this.salt, 5, this.b, 4));
        assertEquals(in(this.salt, 1, this.b, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exitCook(cookB, this.stew);
        // both batches keep their own variant
        this.now++;
        enter(village, cookA, this.stew, Map.of(this.salt, 5, this.b, 20));
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exitCook(cookA, this.stew);
        enter(village, cookB, this.stew, Map.of(this.salt, 5, this.a, 20));
        assertEquals(in(this.salt, 1, this.b, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exitCook(cookB, this.stew);
    }

    /**
     * The away cook's day chooses afresh at each batch (the batch's first read, {@code shortOf}); the batch's other reads
     * agree with it. a=6, b=10, 2 per batch: 8 batches, like the live cook (not 5 from one variant).
     */
    @Test
    void awayDayRechoosesEachBatch() {
        this.overlay.add("addon", vegStew());
        CookActivity.Recipe r = recipe(this.stew);
        Map<Item, Integer> stock = new HashMap<>(Map.of(this.salt, 100, this.a, 6, this.b, 10));
        Object away = new Object();
        this.variants.enter(new Object(), away, () -> this.now, null, null, () -> new HashMap<>(stock));
        int batches = 0;
        for (int guard = 0; guard < 20; guard++) {
            Map<Item, Integer> first = this.variants.batchInputs(r, r.inputsPerBatch());
            boolean there = first.entrySet().stream().allMatch(e -> stock.getOrDefault(e.getKey(), 0) >= e.getValue());
            if (!there) break;
            Map<Item, Integer> take = this.variants.inputs(r, r.inputsPerBatch());
            assertEquals(first, take, "the batch's reads agree");
            take.forEach((i, n) -> stock.merge(i, -n, Integer::sum));
            batches++;
        }
        this.variants.exit(away);
        assertEquals(8, batches, "left: " + stock);
        assertEquals(0, stock.get(this.a));
        assertEquals(0, stock.get(this.b));
    }

    @Test
    void aContextLeftByAThrowingTickIsIgnoredNextTick() {
        this.overlay.add("addon", vegStew());
        CookActivity.Recipe r = recipe(this.stew);
        Object cook = new Object();
        enter(new Object(), cook, null, Map.of(this.salt, 5, this.a, 2, this.b, 9));
        assertEquals(in(this.salt, 1, this.b, 2), this.variants.inputs(r, r.inputsPerBatch()));
        // no exit (the tick threw): from the next tick on, reads outside a context get the first variant
        this.now++;
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
        // and the next cook's enter replaces it
        Object v2 = new Object();
        enter(v2, cook, null, Map.of(this.salt, 5, this.a, 9, this.b, 2));
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exit(cook);
    }

    /**
     * The brain's "does the cook have work" check (outside the cook's tick) runs in a context too; inside a cook's tick
     * of the same village it leaves the tick's context alone.
     */
    @Test
    void workCheckContextNestsInsideATick() {
        this.overlay.add("addon", vegStew());
        CookActivity.Recipe r = recipe(this.stew);
        Object village = new Object();
        Object check = new Object();
        Map<Item, Integer> onlyB = Map.of(this.salt, 5, this.b, 9);
        this.variants.enterCheck(village, check, () -> this.now, () -> onlyB);
        assertEquals(in(this.salt, 1, this.b, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exit(check);
        assertEquals(in(this.salt, 1, this.a, 2), this.variants.inputs(r, r.inputsPerBatch()));
        // inside a cook's tick: the check doesn't replace (nor end) the tick's context
        Object cook = new Object();
        enter(village, cook, null, onlyB);
        this.variants.enterCheck(village, check, () -> this.now, () -> Map.of(this.salt, 5, this.a, 9));
        this.variants.exit(check);
        assertEquals(in(this.salt, 1, this.b, 2), this.variants.inputs(r, r.inputsPerBatch()));
        this.variants.exit(cook);
    }

    @Test
    void ingredientsUnionHoldsAllMembers() {
        this.items.tags.put(this.veg, List.of(this.a, this.b, this.c));
        this.items.tags.put(this.herbs, List.of(this.x, this.y, this.z));
        this.overlay.add("addon", Dish.builder(this.stew).ingredient(this.salt, 1).ingredient(this.veg, 1).ingredient(this.herbs, 1)
            .build());
        // every member, also those past the 8-variant cap (c with z): the farmer never composts any of them
        assertEquals(Set.of(this.wheat, this.salt, this.a, this.b, this.c, this.x, this.y, this.z), this.overlay.ingredients());
    }

    @Test
    void tagDishRecipeStableAcrossRebuildsNewWhenMembersChange() {
        this.overlay.add("addon", vegStew());
        CookActivity.Recipe r = recipe(this.stew);
        // an unrelated change and a tag reload with the same members: the same object (a cook mid-batch carries on)
        WorkTable.Handle h = this.overlay.add("other", Dish.builder(this.items.item("mymod:pie")).ingredient(this.wheat, 1).build());
        this.overlay.recipes();
        h.remove();
        this.overlay.tagsChanged();
        assertSame(r, recipe(this.stew));
        assertFalse(this.overlay.isStale(r));
        // the tag gains c (first variant unchanged): a new recipe, the old one is stale (its batch ends)
        long before = this.overlay.version();
        this.items.tags.put(this.veg, List.of(this.a, this.b, this.c));
        this.overlay.tagsChanged();
        assertTrue(this.overlay.version() > before, "a tag reload bumps the cook table's version");
        CookActivity.Recipe next = recipe(this.stew);
        assertNotSame(r, next);
        assertEquals(r, next, "equal values: same first variant");
        assertEquals(3, this.overlay.variantsOf(next).size());
        assertTrue(this.overlay.isStale(r));
    }

    @Test
    void needsLineNamesTheTag() {
        String line = this.overlay.needs(vegStew());
        assertTrue(line.startsWith("Needs 1 Salt and 2 of any #c:test_veg"), line);
        String only = this.overlay.needs(Dish.builder(this.stew).ingredient(this.veg, 1).build());
        assertTrue(only.startsWith("Needs 1 of any #c:test_veg."), only);
    }

    @Test
    void bridgeAnswersTowntopiaWhileOff() {
        Map<Item, Integer> own = new HashMap<>(in(this.wheat, 3));
        assertSame(own, CookVariants.onInputs(new Object(), own));
    }
}
