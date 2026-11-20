package townkit.core.work.cook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.work.Dish;
import townkit.core.work.WorkTable;

/** W13: when a cook's merged dishes make the village's ranchers milk (bare items, as in {@link CookOverlayTest}). */
class MilkDemandTest {
    private CookOverlayTest.TestItems items;
    private Item milk;
    private Item cake;
    private Item pudding;
    private Item sugar;
    private Item oat;
    private CookOverlay overlay;
    private final Map<Item, Integer> stock = new HashMap<>();
    private final Map<Item, Integer> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        this.items = new CookOverlayTest.TestItems();
        this.milk = this.items.item("minecraft:milk_bucket");
        this.cake = this.items.item("minecraft:cake");
        this.pudding = this.items.item("mymod:pudding");
        this.sugar = this.items.item("minecraft:sugar");
        this.oat = this.items.item("mymod:oat");
        this.overlay = new CookOverlay(new WorkTable<>(CookOverlay.KIND, Dish::result), () -> List.<CookActivity.Recipe>of(),
            () -> CookSettingsSchema.SCHEMA, this.items);
    }

    private boolean wants() {
        return MilkDemand.wants(this.overlay, this.milk, this.cake, item -> true, i -> this.stock.getOrDefault(i, 0),
            i -> this.stored.getOrDefault(i, 0));
    }

    @Test
    void noMilkDishFalse() {
        this.stock.put(this.pudding, 16);
        assertFalse(wants(), "nothing registered");
        this.overlay.add("a", Dish.builder(this.pudding).ingredient(this.sugar, 1).build());
        assertFalse(wants(), "a dish without milk");
    }

    @Test
    void stockMetFalse() {
        this.overlay.add("a", Dish.builder(this.pudding).ingredient(this.milk, 1).ingredient(this.sugar, 1).build());
        this.stock.put(this.pudding, 10);
        this.stored.put(this.pudding, 10);
        assertFalse(wants(), "stock reached");
        this.stock.put(this.pudding, 0);
        this.stored.put(this.pudding, 0);
        assertFalse(wants(), "a row at 0 never makes it");
    }

    @Test
    void belowStockTrue() {
        this.overlay.add("a", Dish.builder(this.pudding).ingredient(this.milk, 1).ingredient(this.sugar, 1).build());
        this.stock.put(this.pudding, 10);
        this.stored.put(this.pudding, 9);
        assertTrue(wants());
    }

    @Test
    void notAllowedFalse() {
        this.overlay.add("a", Dish.builder(this.pudding).ingredient(this.milk, 1).build());
        this.stock.put(this.pudding, 10);
        assertFalse(MilkDemand.wants(this.overlay, this.milk, this.cake, item -> false, i -> 10, i -> 0));
    }

    @Test
    void tagDishWithMilkInATagCounts() {
        TagKey<Item> drinks = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:drinks"));
        this.items.tags.put(drinks, List.of(this.oat, this.milk));
        this.overlay.add("a", Dish.builder(this.pudding).ingredient(drinks, 1).build());
        this.stock.put(this.pudding, 4);
        assertTrue(wants());
    }

    @Test
    void cakeDoesNotCount() {
        this.overlay.add("a", Dish.builder(this.cake).ingredient(this.milk, 3).ingredient(this.sugar, 2).build());
        this.stock.put(this.cake, 10);
        assertFalse(wants(), "Towntopia's own cake rule handles cakes");
    }

    @Test
    void sameViewSameAnswerObject() {
        this.overlay.add("a", Dish.builder(this.pudding).ingredient(this.milk, 1).build());
        assertSame(this.overlay.dishesWithInput(this.milk, this.cake), this.overlay.dishesWithInput(this.milk, this.cake));
    }
}
