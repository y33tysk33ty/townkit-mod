package townkit.core.work.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.work.ForgeRecipe;
import townkit.api.work.ForgeRecipeChange;
import townkit.api.work.GearSlot;
import townkit.api.work.TierPlace;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;
import townkit.core.work.cook.CookOverlay;

/**
 * The smith's overlay without a game (unit tests can't start Minecraft): bare {@link Item}s made without a constructor,
 * and a small stand-in for Towntopia's own recipes (iron and diamond pickaxes, an iron chestplate, a bucket).
 */
class ForgeOverlayTest {
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final String OWNER = "test";

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
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

    static final class TestItems implements CookOverlay.ItemIds {
        final Map<Item, ResourceLocation> ids = new HashMap<>();
        final Map<ResourceLocation, Item> byId = new HashMap<>();

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
        public String name(Item item) {
            return this.ids.get(item).getPath();
        }
    }

    private static ResourceLocation rl(String s) {
        return ResourceLocation.parse(s);
    }

    private static final ResourceLocation IRON = rl("towntopia:iron");
    private static final ResourceLocation DIAMOND = rl("towntopia:diamond");
    private static final ResourceLocation WOOD = rl("towntopia:wood");
    private static final ResourceLocation STEEL = rl("mymod:steel");

    private TestItems items;
    private Item ironPick;
    private Item diamondPick;
    private Item ironChest;
    private Item bucket;
    private Item leatherCap;
    private Item steelPick;
    private Item steelIngot;
    private ForgeOverlay.BuiltIns builtIns;
    private ForgeOverlay overlay;
    private final List<String> logged = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        this.items = new TestItems();
        this.ironPick = this.items.item("minecraft:iron_pickaxe");
        this.diamondPick = this.items.item("minecraft:diamond_pickaxe");
        this.ironChest = this.items.item("minecraft:iron_chestplate");
        this.bucket = this.items.item("minecraft:bucket");
        this.leatherCap = this.items.item("minecraft:leather_helmet");
        this.steelPick = this.items.item("mymod:steel_pickaxe");
        this.steelIngot = this.items.item("mymod:steel_ingot");
        List<ForgeRecipe> own = List.of(
            ForgeRecipe.builder(this.ironPick).cost(IRON, 3).cost(WOOD, 1).gear(IRON, GearSlot.PICKAXE).build(),
            ForgeRecipe.builder(this.diamondPick).cost(DIAMOND, 3).cost(WOOD, 1).gear(DIAMOND, GearSlot.PICKAXE).build(),
            ForgeRecipe.builder(this.ironChest).cost(IRON, 8).gear(IRON, GearSlot.CHESTPLATE).build(),
            ForgeRecipe.builder(this.bucket).cost(IRON, 3).build());
        this.builtIns = new ForgeOverlay.BuiltIns(own, Map.of(this.bucket, 2),
            Map.of(this.ironPick, 2, this.diamondPick, 3, this.ironChest, 2, this.leatherCap, 0));
        this.overlay = new ForgeOverlay(new WorkTable<>(ForgeOverlay.MATERIALS, ForgeOverlay.MaterialDef::id),
            new WorkTable<>(ForgeOverlay.TIERS, ForgeOverlay.TierDef::id), new WorkTable<>(ForgeOverlay.RECIPES, ForgeRecipe::result),
            () -> this.builtIns, new WorkLog(this.logged::add));
    }

    private ForgeRecipe steelPickRecipe() {
        return ForgeRecipe.builder(this.steelPick).cost(STEEL, 3).cost(WOOD, 1).gear(STEEL, GearSlot.PICKAXE).build();
    }

    private void steel() {
        this.overlay.material(OWNER, new ForgeOverlay.MaterialDef(STEEL, null, this.steelIngot));
        this.overlay.tier(OWNER, new ForgeOverlay.TierDef(STEEL, TierPlace.after(IRON)));
    }

    private List<ResourceLocation> ladder() {
        return this.overlay.view().ladder.stream().map(ForgeOverlay.Rung::id).toList();
    }

    /** A tier placed after stone sits between stone and iron; Towntopia's tiers above it move up. */
    @Test
    void tierAfterStoneSitsBelowIron() {
        ResourceLocation bronze = rl("mymod:bronze");
        Item bronzePick = this.items.item("mymod:bronze_pickaxe");
        this.overlay.material(OWNER, new ForgeOverlay.MaterialDef(STEEL, null, this.steelIngot));
        this.overlay.tier(OWNER, new ForgeOverlay.TierDef(bronze, TierPlace.after(rl("towntopia:stone"))));
        this.overlay.add(OWNER, ForgeRecipe.builder(bronzePick).cost(STEEL, 3).gear(bronze, GearSlot.PICKAXE).build());
        ForgeOverlay.View v = this.overlay.view();
        assertEquals(List.of(WOOD, rl("towntopia:stone"), bronze, IRON, DIAMOND, rl("towntopia:netherite")), ladder());
        assertEquals(1, v.position(1), "stone stays");
        assertEquals(2, v.position(bronze));
        assertEquals(3, v.position(2), "iron moved up one");
        assertEquals(2, v.rung(2).above(), "follows iron");
        assertEquals(1, v.rung(2).skill(), "iron's level by default (any smith)");
        assertEquals(2, v.towntopiaTier(2), "counts as iron for craft points");
        assertSame(bronzePick, v.tool(1, 2).recipe().result());
        assertSame(this.ironPick, v.tool(1, 3).recipe().result(), "the iron pickaxe recipe sits at iron's new position");
        assertEquals(Optional.of(bronze), this.overlay.tierOf(bronzePick));
    }

    /** The brief's "clash log": two tiers placed after the same tier are ordered by registration and one line names both. */
    @Test
    void twoTiersAfterOneTierLogTheClash() {
        this.overlay.tier("addon-a", new ForgeOverlay.TierDef(rl("mymod:a"), TierPlace.after(IRON)));
        this.overlay.tier("addon-b", new ForgeOverlay.TierDef(rl("mymod:b"), TierPlace.after(IRON)));
        assertEquals(List.of(WOOD, rl("towntopia:stone"), IRON, rl("mymod:a"), rl("mymod:b"), DIAMOND, rl("towntopia:netherite")), ladder());
        List<String> clash = this.logged.stream().filter(l -> l.contains("both placed after " + IRON)).toList();
        assertEquals(1, clash.size(), this.logged.toString());
        assertTrue(clash.get(0).contains("mymod:a (addon-a)") && clash.get(0).contains("mymod:b (addon-b)"), clash.get(0));
        this.overlay.view();
        assertEquals(1, this.logged.stream().filter(l -> l.contains("both placed after")).count(), "logged once");
    }

    /** Java removes a material or tier the way a {@code "remove"} data file does. */
    @Test
    void removeMaterialAndTier() {
        steel();
        this.overlay.add(OWNER, steelPickRecipe());
        WorkTable.Handle tier = this.overlay.removeTier("other", STEEL);
        assertEquals(ForgeOverlay.TOWNTOPIA_TIERS, ladder(), "the tier is gone");
        tier.remove();
        assertEquals(List.of(WOOD, rl("towntopia:stone"), IRON, STEEL, DIAMOND, rl("towntopia:netherite")), ladder());
        WorkTable.Handle mat = this.overlay.removeMaterial("other", STEEL);
        assertTrue(this.overlay.view().spares.stream().allMatch(m -> m == null), "the material is gone");
        assertTrue(this.overlay.view().recipe(this.steelPick) == null, "a recipe needing it is skipped");
        mat.remove();
        assertTrue(this.overlay.view().recipe(this.steelPick) != null);
    }

    /** As in data files: Towntopia's own materials and tiers aren't in the tables, so removing one is an unknown removal. */
    @Test
    void removingTowntopiasMaterialOrTierChangesNothing() {
        this.overlay.removeMaterial(OWNER, IRON);
        this.overlay.removeTier(OWNER, IRON);
        assertEquals(ForgeOverlay.TOWNTOPIA_TIERS, ladder());
        assertTrue(this.overlay.materialTable().merged().isEmpty() && this.overlay.tierTable().merged().isEmpty());
        assertSame(this.ironPick, this.overlay.view().tool(1, 2).recipe().result(), "iron recipes still resolve");
    }

    @Test
    void untouchedIsTowntopia() {
        ForgeOverlay.View v = this.overlay.view();
        assertFalse(this.overlay.touched());
        assertFalse(v.touched);
        assertTrue(v.identity());
        assertEquals(ForgeOverlay.TOWNTOPIA_TIERS, ladder());
        for (int t = 0; t < 5; t++) assertEquals(t, v.position(t));
        assertEquals(this.builtIns.recipes(), this.overlay.recipes());
        for (ForgeRecipe r : this.builtIns.recipes()) {
            assertSame(r, v.recipe(r.result()).recipe(), "Towntopia's own recipe object");
            assertTrue(v.recipe(r.result()).builtIn());
        }
        assertSame(this.ironPick, v.tool(1, 2).recipe().result(), "pickaxe (kind 1) at iron (2)");
        assertSame(this.ironChest, v.armor(0, 2).recipe().result(), "chestplate (slot 0) at iron");
        assertEquals(2, v.recipe(this.bucket).tier(), "the bucket keeps Towntopia's stock tier");
        assertFalse(v.hasAddonGear());
        assertTrue(v.spares.stream().allMatch(m -> m == null));
    }

    @Test
    void tierAfterIronSortsBeforeDiamond() {
        steel();
        ForgeOverlay.View v = this.overlay.view();
        assertEquals(List.of(rl("towntopia:wood"), rl("towntopia:stone"), IRON, STEEL, DIAMOND, rl("towntopia:netherite")), ladder());
        assertFalse(v.identity());
        assertEquals(3, v.position(STEEL));
        assertEquals(4, v.position(3), "diamond moved up one");
        assertEquals(5, v.position(4));
        ForgeOverlay.Rung steel = v.rung(3);
        assertEquals(-1, steel.towntopia());
        assertEquals(3, steel.above(), "follows diamond");
        assertEquals(25, steel.skill(), "default skill: diamond's level");
        assertEquals(3, v.towntopiaTier(3), "counts as diamond for craft points");
        assertEquals(2, v.towntopiaTier(2));
        // a second tier after iron sits above the first (registration order), one after it right after it
        this.overlay.tier(OWNER, new ForgeOverlay.TierDef(rl("mymod:bronze"), TierPlace.after(IRON).skill(10)));
        this.overlay.tier(OWNER, new ForgeOverlay.TierDef(rl("mymod:hard_steel"), TierPlace.after(STEEL)));
        assertEquals(List.of(rl("towntopia:wood"), rl("towntopia:stone"), IRON, STEEL, rl("mymod:hard_steel"), rl("mymod:bronze"), DIAMOND,
            rl("towntopia:netherite")), ladder());
        assertEquals(10, this.overlay.view().rung(5).skill());
        // above netherite: follows netherite
        this.overlay.tier(OWNER, new ForgeOverlay.TierDef(rl("mymod:star"), TierPlace.after(rl("towntopia:netherite"))));
        ForgeOverlay.Rung star = this.overlay.view().rung(8);
        assertEquals(rl("mymod:star"), star.id());
        assertEquals(4, star.above());
        assertEquals(75, star.skill());
    }

    @Test
    void tierAfterUnknownSkipped() {
        this.overlay.material(OWNER, new ForgeOverlay.MaterialDef(STEEL, null, this.steelIngot));
        this.overlay.tier(OWNER, new ForgeOverlay.TierDef(STEEL, TierPlace.after(rl("mymod:nope"))));
        this.overlay.add(OWNER, steelPickRecipe());
        ForgeOverlay.View v = this.overlay.view();
        assertTrue(v.identity(), "the tier is not on the ladder");
        assertEquals(-1, v.position(STEEL));
        assertNull(v.recipe(this.steelPick), "its gear recipe is skipped too");
        assertEquals(Optional.empty(), this.overlay.tierOf(this.steelPick));
        // Towntopia's tiers can't be replaced: an add of one is skipped
        this.overlay.tier(OWNER, new ForgeOverlay.TierDef(IRON, TierPlace.after(DIAMOND)));
        assertEquals(ForgeOverlay.TOWNTOPIA_TIERS, ladder());
    }

    @Test
    void recipeWithUnknownMaterialSkipped() {
        this.overlay.tier(OWNER, new ForgeOverlay.TierDef(STEEL, TierPlace.after(IRON)));
        this.overlay.add(OWNER, steelPickRecipe());
        assertNull(this.overlay.view().recipe(this.steelPick), "steel isn't a material yet");
        assertTrue(this.overlay.recipes().stream().anyMatch(r -> r.result() == this.steelPick), "still in the merged list");
        this.overlay.material(OWNER, new ForgeOverlay.MaterialDef(STEEL, null, this.steelIngot));
        ForgeOverlay.View v = this.overlay.view();
        ForgeOverlay.Resolved r = v.recipe(this.steelPick);
        assertNotNull(r);
        assertEquals(0, v.spareSlot(STEEL));
        assertEquals(3, r.cost()[ForgeOverlay.TOWNTOPIA_MATERIALS.size()], "steel's spare slot");
        assertEquals(1, r.cost()[7], "wood");
        assertEquals(3, r.tier());
        assertSame(r, v.tool(1, 3), "the pickaxe between iron and diamond");
        assertTrue(v.hasAddonGear());
        // Towntopia's materials can't be replaced
        this.overlay.material(OWNER, new ForgeOverlay.MaterialDef(IRON, null, this.steelIngot));
        assertEquals(-1, this.overlay.view().spareSlot(IRON));
    }

    @Test
    void changeIronChestplateCost() {
        this.overlay.change(OWNER, this.ironChest, ForgeRecipeChange.builder().cost(IRON, 6).build());
        ForgeOverlay.View v = this.overlay.view();
        ForgeOverlay.Resolved r = v.recipe(this.ironChest);
        assertEquals(6, r.cost()[1]);
        assertFalse(r.builtIn(), "a changed recipe is no longer Towntopia's own object");
        assertSame(r, v.armor(0, 2));
        assertEquals(GearSlot.CHESTPLATE, r.recipe().slot(), "the change keeps the gear");
        assertTrue(v.recipe(this.ironPick).builtIn(), "the others stay Towntopia's");
        assertThrows(IllegalArgumentException.class, () -> ForgeRecipeChange.builder().cost(IRON, 0).build());
    }

    @Test
    void removeBucket() {
        this.overlay.remove(OWNER, this.bucket);
        ForgeOverlay.View v = this.overlay.view();
        assertNull(v.recipe(this.bucket));
        assertFalse(this.overlay.recipes().stream().anyMatch(r -> r.result() == this.bucket));
        assertTrue(this.overlay.isTowntopiaRecipe(this.bucket), "still known as Towntopia's (its stock order is refused)");
        assertTrue(this.overlay.touched());
    }

    @Test
    void tierOfRegisteredGear() {
        steel();
        WorkTable.Handle recipe = this.overlay.add(OWNER, steelPickRecipe());
        assertEquals(Optional.of(STEEL), this.overlay.tierOf(this.steelPick));
        assertEquals(Optional.of(IRON), this.overlay.tierOf(this.ironPick));
        assertEquals(Optional.of(WOOD), this.overlay.tierOf(this.leatherCap), "Towntopia's tier-0 gear");
        assertEquals(Optional.empty(), this.overlay.tierOf(this.steelIngot));
        assertEquals(3, this.overlay.view().gearPosition(this.steelPick));
        // Its tier removed, the pickaxe is rated as Towntopia rates it (unknown)
        WorkTable.Handle tier = this.overlay.tier(OWNER, new ForgeOverlay.TierDef(rl("mymod:other"), TierPlace.after(IRON)));
        tier.remove();
        this.overlay.tierTable().setFileChanges(List.of(WorkChange.remove("data file", STEEL)));
        assertEquals(Optional.empty(), this.overlay.tierOf(this.steelPick));
        assertEquals(-1, this.overlay.view().gearPosition(this.steelPick));
        recipe.remove();
        assertEquals(Optional.of(IRON), this.overlay.tierOf(this.ironPick));
    }

    @Test
    void codecsRoundTrip() {
        ForgeCodecs.Materials mats = new ForgeCodecs.Materials(this.items);
        ForgeCodecs.Tiers tiers = new ForgeCodecs.Tiers();
        ForgeCodecs.Recipes recipes = new ForgeCodecs.Recipes(this.items);
        ResourceLocation file = rl("mymod:f");

        ForgeOverlay.MaterialDef byItem = new ForgeOverlay.MaterialDef(STEEL, null, this.steelIngot);
        assertEquals(byItem, mats.read(file, mats.write(byItem)).entry());
        ForgeOverlay.MaterialDef byTag = new ForgeOverlay.MaterialDef(STEEL, TagKey.create(Registries.ITEM, rl("c:ingots/steel")), null);
        assertEquals(byTag, mats.read(file, mats.write(byTag)).entry());
        assertEquals(byTag, mats.read(file, json("{\"action\":\"add\",\"id\":\"mymod:steel\",\"items\":\"#c:ingots/steel\"}")).entry());
        assertEquals(WorkChange.Kind.REMOVE, mats.read(file, json("{\"action\":\"remove\",\"id\":\"mymod:steel\"}")).kind());
        assertThrows(JsonParseException.class, () -> mats.read(file, json("{\"action\":\"add\",\"id\":\"mymod:steel\",\"items\":\"mymod:nope\"}")));

        ForgeOverlay.TierDef tier = new ForgeOverlay.TierDef(STEEL, TierPlace.after(IRON).skill(30));
        assertEquals(tier, tiers.read(file, tiers.write(tier)).entry());
        assertEquals(new ForgeOverlay.TierDef(STEEL, TierPlace.after(IRON)),
            tiers.read(file, json("{\"action\":\"add\",\"id\":\"mymod:steel\",\"after\":\"towntopia:iron\"}")).entry());
        assertThrows(JsonParseException.class, () -> tiers.read(file, json("{\"action\":\"add\",\"id\":\"mymod:steel\",\"after\":\"towntopia:iron\",\"skill\":0}")));

        ForgeRecipe gear = ForgeRecipe.builder(this.steelPick).count(2).cost(STEEL, 3).cost(WOOD, 2).gear(STEEL, GearSlot.PICKAXE)
            .smithing(true).build();
        assertEquals(gear, recipes.read(file, recipes.write(gear)).entry());
        ForgeRecipe stock = ForgeRecipe.builder(this.bucket).cost(IRON, 2).build();
        assertEquals(stock, recipes.read(file, recipes.write(stock)).entry());
        // the brief's example file
        ForgeRecipe example = recipes.read(file, json("{\"action\":\"add\",\"result\":\"mymod:steel_pickaxe\",\"cost\":{\"mymod:steel\":3,"
            + "\"towntopia:wood\":2},\"tier\":\"mymod:steel\",\"slot\":\"pickaxe\"}")).entry();
        assertEquals(1, example.count());
        assertEquals(GearSlot.PICKAXE, example.slot());
        WorkChange<Item, ForgeRecipe> change = recipes.read(file, json("{\"action\":\"change\",\"result\":\"minecraft:iron_chestplate\","
            + "\"cost\":{\"towntopia:iron\":6}}"));
        assertEquals(WorkChange.Kind.CHANGE, change.kind());
        ForgeRecipe changed = change.change().apply(this.builtIns.recipes().get(2));
        assertEquals(Map.of(IRON, 6), changed.cost());
        assertEquals(WorkChange.Kind.REMOVE, recipes.read(file, json("{\"action\":\"remove\",\"result\":\"minecraft:bucket\"}")).kind());
        assertThrows(JsonParseException.class, () -> recipes.read(file, json("{\"action\":\"add\",\"result\":\"mymod:steel_pickaxe\","
            + "\"cost\":{\"mymod:steel\":3},\"tier\":\"mymod:steel\"}")), "a tier without a slot");
        assertThrows(JsonParseException.class, () -> recipes.read(file, json("{\"action\":\"add\",\"result\":\"mymod:steel_pickaxe\","
            + "\"cost\":{\"mymod:steel\":0}}")), "an amount below 1");
        assertThrows(JsonParseException.class, () -> recipes.read(file, json("{\"action\":\"change\",\"result\":\"minecraft:bucket\"}")),
            "a change that sets nothing");
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
