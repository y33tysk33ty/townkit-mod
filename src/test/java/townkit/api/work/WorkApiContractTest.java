package townkit.api.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitWorkView;

/**
 * Contract tests for the stable work API (0.7.1) that need no game: every method of {@link TownKitCooking},
 * {@link TownKitForge} and {@link TownKitMining} hands its arguments to the backend's work view unchanged and returns what
 * it returns, and the value types ({@link Dish}, {@link DishChange}, {@link ForgeRecipe}, {@link ForgeRecipeChange},
 * {@link TierPlace}, {@link GearSlot}, {@link OreEntry}) keep their documented rules. What the methods do in a real village
 * is covered by the GameTests ({@code CookingTests}, {@code ForgeTests}, {@code MiningTests}).
 */
class WorkApiContractTest {
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mymod", "thing");

    private final List<Object[]> calls = new ArrayList<>();
    private final Subscription sub = new Subscription() {
        @Override
        public void remove() {
        }

        @Override
        public boolean isDisabled() {
            return false;
        }
    };

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

    @BeforeEach
    void install() {
        TownKitBackend.resetForTests();
        TownKitWorkView view = (TownKitWorkView) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {TownKitWorkView.class},
            (proxy, method, args) -> {
                Object[] row = new Object[(args == null ? 0 : args.length) + 1];
                row[0] = method.getName();
                if (args != null) System.arraycopy(args, 0, row, 1, args.length);
                this.calls.add(row);
                Class<?> r = method.getReturnType();
                if (r == Subscription.class) return this.sub;
                if (r == List.class) return List.of();
                if (r == Optional.class) return Optional.of(ID);
                throw new UnsupportedOperationException(method.getName());
            });
        TownKitBackend.install((TownKitBackend) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {TownKitBackend.class},
            (proxy, method, args) -> {
                if (method.getName().equals("work")) return view;
                throw new UnsupportedOperationException(method.getName());
            }));
    }

    @AfterEach
    void reset() {
        TownKitBackend.resetForTests();
    }

    private void passedThrough(String viewMethod, Object... args) {
        assertEquals(1, this.calls.size(), viewMethod);
        Object[] row = this.calls.get(0);
        assertEquals(viewMethod, row[0]);
        assertEquals(args.length, row.length - 1, viewMethod);
        for (int i = 0; i < args.length; i++) assertSame(args[i], row[i + 1], viewMethod + " arg " + i);
        this.calls.clear();
    }

    @Test
    void cookingMethodsPassThrough() {
        Item bread = item();
        Dish dish = Dish.builder(bread).ingredient(item(), 2).build();
        DishChange change = DishChange.builder().stock(4).build();

        assertSame(this.sub, TownKitCooking.addDish("o", dish));
        passedThrough("addDish", "o", dish);
        assertSame(this.sub, TownKitCooking.changeDish("o", bread, change));
        passedThrough("changeDish", "o", bread, change);
        assertSame(this.sub, TownKitCooking.removeDish("o", bread));
        passedThrough("removeDish", "o", bread);
        assertTrue(TownKitCooking.dishes().isEmpty());
        passedThrough("dishes");
        assertSame(this.sub, TownKitCooking.station("o", ID, null));
        passedThrough("station", "o", ID, null);
        assertSame(this.sub, TownKitCooking.removeStation("o", ID));
        passedThrough("removeStation", "o", ID);
    }

    @Test
    void forgeMethodsPassThrough() {
        Item sword = item();
        Item raw = item();
        ForgeRecipe recipe = ForgeRecipe.builder(sword).cost(ID, 2).build();
        ForgeRecipeChange change = ForgeRecipeChange.builder().count(2).build();
        TierPlace place = TierPlace.after(ID);

        assertSame(this.sub, TownKitForge.material("o", ID, null));
        passedThrough("forgeMaterial", "o", ID, null);
        assertSame(this.sub, TownKitForge.tier("o", ID, place));
        passedThrough("forgeTier", "o", ID, place);
        assertSame(this.sub, TownKitForge.removeMaterial("o", ID));
        passedThrough("removeForgeMaterial", "o", ID);
        assertSame(this.sub, TownKitForge.removeTier("o", ID));
        passedThrough("removeForgeTier", "o", ID);
        assertSame(this.sub, TownKitForge.addRecipe("o", recipe));
        passedThrough("addForgeRecipe", "o", recipe);
        assertSame(this.sub, TownKitForge.changeRecipe("o", sword, change));
        passedThrough("changeForgeRecipe", "o", sword, change);
        assertSame(this.sub, TownKitForge.removeRecipe("o", sword));
        passedThrough("removeForgeRecipe", "o", sword);
        assertSame(this.sub, TownKitForge.smelt("o", raw, sword));
        passedThrough("smelt", "o", raw, sword);
        assertSame(this.sub, TownKitForge.removeSmelt("o", raw));
        passedThrough("removeSmelt", "o", raw);
        assertEquals(Optional.of(ID), TownKitForge.tierOf(sword));
        passedThrough("forgeTierOf", sword);
    }

    @Test
    void miningMethodsPassThrough() {
        assertSame(this.sub, TownKitMining.ore("o", null));
        passedThrough("ore", "o", null);
        java.util.function.UnaryOperator<OreEntry> change = e -> e;
        assertSame(this.sub, TownKitMining.changeOre("o", ID, change));
        passedThrough("changeOre", "o", ID, change);
        assertSame(this.sub, TownKitMining.removeOre("o", ID));
        passedThrough("removeOre", "o", ID);
        assertTrue(TownKitMining.ores().isEmpty());
        passedThrough("ores");
    }

    @Test
    void everyPublicStaticMethodIsCoveredAbove() {
        int count = 0;
        for (Class<?> c : new Class<?>[] {TownKitCooking.class, TownKitForge.class, TownKitMining.class}) {
            for (Method m : c.getDeclaredMethods()) if (Modifier.isPublic(m.getModifiers())) count++;
        }
        // cooking 6, forge 10, mining 4 (including removeStation, removeMaterial, removeTier, changeOre, removeOre)
        assertEquals(6 + 10 + 4, count, "a public method was added or removed: extend this contract test");
    }

    @Test
    void dishRules() {
        Item bread = item();
        Dish d = Dish.builder(bread).ingredient(item(), 3).build();
        assertEquals(1, d.outputPerBatch());
        assertFalse(d.heated());
        assertEquals(5, d.nutrition());
        assertEquals(Dish.DEFAULT_STOCK, d.stock());
        assertEquals(16, Dish.DEFAULT_STOCK);
        assertEquals(20, Dish.MAX_NUTRITION);
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(bread).build());
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(bread).ingredient(item(), 1).stock(0).build());
        assertThrows(IllegalArgumentException.class, () -> Dish.builder(bread).ingredient(item(), 1).nutrition(21).build());
        assertThrows(UnsupportedOperationException.class, () -> d.ingredients().clear());
    }

    @Test
    void dishChangeKeepsUnsetFields() {
        Item bread = item();
        Dish d = Dish.builder(bread).ingredient(item(), 3).stock(8).nutrition(7).build();
        assertTrue(DishChange.builder().build().isEmpty());
        Dish changed = DishChange.builder().stock(2).build().apply(d);
        assertEquals(2, changed.stock());
        assertEquals(7, changed.nutrition());
        assertEquals(d.ingredients(), changed.ingredients());
        assertThrows(IllegalArgumentException.class, () -> DishChange.builder().stock(0).build());
    }

    @Test
    void forgeRecipeAndPlaceRules() {
        Item sword = item();
        ForgeRecipe r = ForgeRecipe.builder(sword).cost(ID, 3).build();
        assertFalse(r.isGear());
        assertTrue(ForgeRecipe.builder(sword).cost(ID, 3).gear(ID, GearSlot.SWORD).build().isGear());
        assertThrows(IllegalArgumentException.class, () -> ForgeRecipe.builder(sword).build());
        assertTrue(ForgeRecipeChange.builder().build().isEmpty());
        assertEquals(5, ForgeRecipeChange.builder().count(5).build().apply(r).count());
        TierPlace p = TierPlace.after(ID).skill(3);
        assertEquals(ID, p.after());
        assertEquals(3, p.skill().getAsInt());
        assertFalse(TierPlace.after(ID).skill().isPresent());
        assertTrue(GearSlot.CHESTPLATE.isArmor());
        assertFalse(GearSlot.SWORD.isArmor());
    }

    @Test
    void oreRangesAreChecked() {
        assertEquals(-64, OreEntry.MIN_Y);
        assertEquals(320, OreEntry.MAX_Y);
        assertTrue(new OreEntry.Depths(0, 64).contains(10));
        assertFalse(new OreEntry.Depths(0, 64).contains(65));
        assertThrows(IllegalArgumentException.class, () -> new OreEntry.Depths(64, 0));
        assertThrows(IllegalArgumentException.class, () -> new OreEntry.Depths(-65, 0));
        OreEntry.Spread s = new OreEntry.Spread(6.0, 0, 9);
        assertEquals(0.6, s.at(5), 1e-9);
        assertEquals(0.0, s.at(10), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new OreEntry.Spread(0, 0, 9));
    }
}
