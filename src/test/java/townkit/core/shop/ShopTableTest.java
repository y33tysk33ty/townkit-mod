package townkit.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import townkit.api.Subscription;
import townkit.api.shop.ShopAdjust;
import townkit.api.shop.ShopCheck;
import townkit.api.shop.ShopCondition;
import townkit.api.shop.ShopPrice;
import townkit.api.village.Village;
import townkit.core.shop.ShopTable.AddonRow;
import townkit.core.shop.ShopTable.BuiltInRow;
import townkit.core.shop.ShopTable.Row;

/** ShopTable with strings for items: Towntopia's rows plus the registered rules give the rows a buyer sees. */
class ShopTableTest {
    private static final String EMERALD = "emerald";
    private static final ResourceLocation FARMER_ID = ResourceLocation.parse("towntopia:farmer_token");
    private static final ResourceLocation HOUSE_ID = ResourceLocation.parse("towntopia:2_bed_house");
    private static final List<BuiltInRow<String>> TOWNTOPIA = List.of(
        new BuiltInRow<>(HOUSE_ID, "house", Component.literal("2 Bed House"), 6, 8),
        new BuiltInRow<>(FARMER_ID, "farmer", Component.literal("Farmer"), 3, 4));

    private final List<ResourceLocation> failures = new ArrayList<>();
    private final ShopTable<String> table = new ShopTable<>((id, t) -> failures.add(id), () -> { });

    /** Grown price for addon rows: base x 2 (stands in for Towntopia's priceAt); no building but {@code built}. */
    private static ShopTable.Env<String> env(@Nullable ResourceLocation built) {
        return new ShopTable.Env<>() {
            @Override
            public Village village() {
                return null;
            }

            @Nullable
            @Override
            public ServerPlayer buyer() {
                return null;
            }

            @Override
            public String emerald() {
                return EMERALD;
            }

            @Override
            public int grown(ResourceLocation addonId, int basePrice) {
                return basePrice * 2;
            }

            @Override
            public boolean hasBuilding(ResourceLocation type) {
                return type.equals(built);
            }
        };
    }

    private static AddonRow<String> addon(String path, int base, boolean growth, @Nullable ShopPrice price, String currency,
            @Nullable ResourceLocation requires, @Nullable ShopCondition condition) {
        Supplier<String> item = () -> path;
        return new AddonRow<>(ResourceLocation.fromNamespaceAndPath("addon", path), item, () -> Component.literal(path), null, base, growth,
            price, () -> currency, true, true, requires, condition);
    }

    private static AddonRow<String> addon(String path, int base) {
        return addon(path, base, true, null, EMERALD, null, null);
    }

    private List<Row<String>> rows() {
        return table.compute(TOWNTOPIA, env(null));
    }

    private static List<String> ids(List<Row<String>> rows) {
        return rows.stream().map(r -> r.id().toString()).toList();
    }

    private static Row<String> row(List<Row<String>> rows, ResourceLocation id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void addonRowsFollowTowntopiaRows() {
        table.add("one", addon("b", 5));
        table.add("two", addon("a", 2));
        List<Row<String>> rows = rows();
        assertEquals(List.of("towntopia:2_bed_house", "towntopia:farmer_token", "addon:b", "addon:a"), ids(rows));
        Row<String> b = rows.get(2);
        assertEquals(10, b.price(), "growth on: grown price");
        assertEquals(EMERALD, b.currency());
        assertTrue(b.available());
        assertFalse(b.builtIn());
        assertTrue(b.changed());
        Row<String> house = rows.get(0);
        assertEquals(8, house.price(), "Towntopia's own price kept");
        assertTrue(house.builtIn());
        assertFalse(house.changed(), "untouched Towntopia row");
        assertEquals(EMERALD, house.currency());
    }

    @Test
    void priceGrowthOffUsesBase() {
        table.add("one", addon("fixed", 5, false, null, EMERALD, null, null));
        assertEquals(5, rows().get(2).price());
    }

    @Test
    void customPriceWins() {
        table.add("one", addon("custom", 5, true, ctx -> ctx.basePrice() + ctx.grownPrice() + 100, EMERALD, null, null));
        assertEquals(5 + 10 + 100, rows().get(2).price(), "price function sees base and grown");
        table.adjust("two", "farmer", ShopAdjust.builder().price(ctx -> ctx.grownPrice() * 10 + ctx.basePrice()).build());
        Row<String> farmer = row(rows(), FARMER_ID);
        assertEquals(43, farmer.price(), "Towntopia row: grown 4, base 3");
        assertTrue(farmer.changed());
        table.add("three", addon("negative", 5, true, ctx -> -3, EMERALD, null, null));
        assertEquals(0, row(rows(), ResourceLocation.parse("addon:negative")).price(), "negative counts as 0");
    }

    @Test
    void hiddenAdjustRemovesRow() {
        table.adjust("one", "house", ShopAdjust.builder().hidden(true).build());
        assertEquals(List.of("towntopia:farmer_token"), ids(rows()));
        assertTrue(table.changes("house"), "TownKit refuses a hidden row's purchase");
        table.adjust("two", "farmer", ShopAdjust.builder().condition(ctx -> ShopCheck.hidden()).build());
        assertTrue(rows().isEmpty());
        table.add("three", addon("never", 1, true, null, EMERALD, null, ctx -> ShopCheck.hidden()));
        assertTrue(rows().isEmpty(), "hidden addon row");
    }

    @Test
    void greyedKeepsRowWithReason() {
        Component why = Component.literal("only at war");
        table.add("one", addon("war", 4, true, null, EMERALD, null, ctx -> ShopCheck.greyed(why)));
        Row<String> war = rows().get(2);
        assertFalse(war.available());
        assertEquals(why, war.reason());
        ResourceLocation kitchen = ResourceLocation.parse("towntopia:kitchen");
        table.add("two", addon("cook", 4, true, null, EMERALD, kitchen, null));
        Row<String> cook = row(rows(), ResourceLocation.parse("addon:cook"));
        assertFalse(cook.available(), "no kitchen yet");
        assertEquals(Component.translatable("townkit.shop.needs_building", "towntopia:kitchen"), cook.reason());
        Row<String> built = row(table.compute(TOWNTOPIA, env(kitchen)), ResourceLocation.parse("addon:cook"));
        assertTrue(built.available(), "with a kitchen");
        assertNull(built.reason());
        table.adjust("three", "farmer", ShopAdjust.builder().condition(ctx -> ShopCheck.greyed(why)).build());
        Row<String> farmer = row(rows(), FARMER_ID);
        assertFalse(farmer.available());
        assertEquals(why, farmer.reason());
    }

    @Test
    void removingSubscriptionRestores() {
        Subscription a = table.add("one", addon("a", 2));
        Subscription h = table.adjust("one", "house", ShopAdjust.builder().hidden(true).build());
        Subscription c = table.currency("one", () -> "coin");
        assertFalse(table.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> table.add("two", addon("a", 9)), "same id twice");
        a.remove();
        h.remove();
        c.remove();
        assertTrue(table.isEmpty());
        List<Row<String>> rows = rows();
        assertEquals(List.of("towntopia:2_bed_house", "towntopia:farmer_token"), ids(rows));
        assertTrue(rows.stream().noneMatch(Row::changed));
        assertTrue(rows.stream().allMatch(r -> r.currency().equals(EMERALD)));
        assertFalse(table.changes("house"));
        table.add("two", addon("a", 9));
        assertEquals(18, rows().get(2).price(), "id free again after removal");
    }

    @Test
    void twoCurrencySwitchesLastWins() {
        Subscription first = table.currency("one", () -> "gold");
        assertEquals("one", table.currencyOwner());
        Subscription second = table.currency("two", () -> "silver");
        assertEquals("two", table.currencyOwner());
        assertTrue(rows().stream().allMatch(r -> r.currency().equals("silver")));
        assertTrue(rows().stream().allMatch(Row::changed), "every Towntopia row is sold by TownKit");
        second.remove();
        assertTrue(rows().stream().allMatch(r -> r.currency().equals("gold")), "second removed: first back");
        first.remove();
        assertNull(table.currencyOwner());
        assertTrue(rows().stream().allMatch(r -> r.currency().equals(EMERALD)));
        table.currency("three", () -> "coin");
        table.add("four", addon("own", 1, true, null, "shell", null, null));
        assertEquals("shell", row(rows(), ResourceLocation.parse("addon:own")).currency(), "addon rows keep their own currency");
    }

    @Test
    void throwingConditionGreys() {
        table.add("one", addon("boom", 1, true, null, EMERALD, null, ctx -> {
            throw new IllegalStateException("addon bug");
        }));
        table.add("two", addon("bad_price", 1, true, ctx -> {
            throw new IllegalStateException("addon bug");
        }, EMERALD, null, null));
        for (int i = 0; i < 3; i++) {
            List<Row<String>> rows = rows();
            for (String path : List.of("boom", "bad_price")) {
                Row<String> r = row(rows, ResourceLocation.fromNamespaceAndPath("addon", path));
                assertFalse(r.available(), path);
                assertEquals(Component.translatable("townkit.shop.unavailable"), r.reason(), path);
            }
        }
        assertEquals(List.of(ResourceLocation.parse("addon:boom"), ResourceLocation.parse("addon:bad_price")), failures, "logged once per entry");
    }

    /** An item, name or currency supplier that throws (e.g. an unregistered DeferredItem) greys its row; compute never throws. */
    @Test
    void throwingSuppliersGreyAddonRow() {
        Supplier<String> boom = () -> {
            throw new IllegalStateException("not registered");
        };
        Supplier<Component> boomName = () -> {
            throw new IllegalStateException("not registered");
        };
        table.add("one", new AddonRow<>(ResourceLocation.parse("addon:bad_item"), boom, () -> Component.literal("x"), null, 1, true, null,
            () -> EMERALD, true, true, null, null));
        table.add("one", new AddonRow<>(ResourceLocation.parse("addon:bad_name"), () -> "thing", boomName, null, 1, true, null,
            () -> EMERALD, true, true, null, null));
        table.add("one", new AddonRow<>(ResourceLocation.parse("addon:bad_coin"), () -> "thing", () -> Component.literal("x"), null, 1, true,
            null, boom, true, true, null, null));
        table.add("two", addon("fine", 1));
        for (int i = 0; i < 3; i++) {
            List<Row<String>> rows = rows();
            for (String path : List.of("bad_item", "bad_name", "bad_coin")) {
                Row<String> r = row(rows, ResourceLocation.fromNamespaceAndPath("addon", path));
                assertFalse(r == null || r.available(), path);
                assertEquals(Component.translatable("townkit.shop.unavailable"), r.reason(), path);
            }
            assertTrue(row(rows, ResourceLocation.parse("addon:fine")).available(), "other rows unaffected");
        }
        assertEquals(List.of(ResourceLocation.parse("addon:bad_item"), ResourceLocation.parse("addon:bad_name"),
            ResourceLocation.parse("addon:bad_coin")), failures, "logged once per entry");
    }

    /** A built-in currency switch whose supplier throws greys every switched Towntopia row (never sells it for emeralds). */
    @Test
    void throwingBuiltInCurrencyGreysSwitchedRows() {
        table.currency("one", () -> {
            throw new IllegalStateException("not registered");
        });
        table.add("two", addon("fine", 1));
        List<Row<String>> rows = rows();
        for (ResourceLocation id : List.of(HOUSE_ID, FARMER_ID)) {
            Row<String> r = row(rows, id);
            assertFalse(r == null || r.available(), id.toString());
            assertTrue(r.changed(), "TownKit still sells (refuses) it: " + id);
        }
        assertTrue(row(rows, ResourceLocation.parse("addon:fine")).available(), "addon rows keep their own currency");
    }
}
