package townkit.api.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.Test;

/** {@link Dish} and {@link DishChange} rules for tag ingredients and {@code keepContainers} (0.8.0), without a game. */
class DishTest {
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

    static final class BareItem extends Item {
        private BareItem() {
            super(null);
        }
    }

    private static Item item() {
        try {
            return (Item) UNSAFE.allocateInstance(BareItem.class);
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TagKey<Item> tag(String id) {
        return TagKey.create(Registries.ITEM, ResourceLocation.parse(id));
    }

    @Test
    void itemOrTagIngredientRequired() {
        Item result = item();
        assertThrows(IllegalArgumentException.class, () -> new Dish(result, 1, Map.of(), Map.of(), false, 5, null, 16, false));
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(result).build());
        Dish tagOnly = Dish.builder(result).ingredient(tag("c:crops/onion"), 2).build();
        assertTrue(tagOnly.ingredients().isEmpty());
        assertEquals(Map.of(tag("c:crops/onion"), 2), tagOnly.tagIngredients());
    }

    @Test
    void oldConstructorStillWorks() {
        Item result = item();
        Item in = item();
        Dish d = new Dish(result, 2, Map.of(in, 3), true, 7, null, 12);
        assertEquals(Map.of(in, 3), d.ingredients());
        assertTrue(d.tagIngredients().isEmpty());
        assertFalse(d.keepContainers());
        assertEquals(7, d.nutrition());
        assertEquals(12, d.stock());
    }

    @Test
    void tagCountBelowOneRejected() {
        Item result = item();
        Map<TagKey<Item>, Integer> tags = new LinkedHashMap<>();
        tags.put(tag("c:crops/onion"), 0);
        assertThrows(IllegalArgumentException.class, () -> new Dish(result, 1, Map.of(), tags, false, 5, null, 16, false));
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(result).ingredient(tag("c:crops/onion"), 0).build());
        assertThrows(IllegalArgumentException.class, () -> DishChange.builder().ingredient(tag("c:crops/onion"), 0).build());
    }

    @Test
    void builderCarriesTagsAndKeepContainers() {
        Dish d = Dish.builder(item()).ingredient(item(), 1).ingredient(tag("c:milks"), 1).keepContainers(true).build();
        assertEquals(1, d.tagIngredients().size());
        assertTrue(d.keepContainers());
        assertFalse(Dish.builder(item()).ingredient(item(), 1).build().keepContainers());
    }

    @Test
    void changeReplacesBothIngredientMaps() {
        Item result = item();
        Item old = item();
        Item fresh = item();
        Dish base = Dish.builder(result).ingredient(old, 1).ingredient(tag("c:milks"), 1).build();
        Dish itemsOnly = DishChange.builder().ingredient(fresh, 2).build().apply(base);
        assertEquals(Map.of(fresh, 2), itemsOnly.ingredients());
        assertTrue(itemsOnly.tagIngredients().isEmpty());
        Dish tagsOnly = DishChange.builder().ingredient(tag("c:eggs"), 3).build().apply(base);
        assertTrue(tagsOnly.ingredients().isEmpty());
        assertEquals(Map.of(tag("c:eggs"), 3), tagsOnly.tagIngredients());
    }

    @Test
    void changeKeepContainersAloneKeepsIngredients() {
        Dish base = Dish.builder(item()).ingredient(item(), 1).ingredient(tag("c:milks"), 1).build();
        DishChange c = DishChange.builder().keepContainers(true).build();
        assertFalse(c.isEmpty());
        Dish out = c.apply(base);
        assertTrue(out.keepContainers());
        assertEquals(base.ingredients(), out.ingredients());
        assertEquals(base.tagIngredients(), out.tagIngredients());
    }
}
