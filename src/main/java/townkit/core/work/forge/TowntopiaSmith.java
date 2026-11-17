package townkit.core.work.forge;

import com.alex.test.village.villagers.blacksmith.ForgeOrderBook;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import townkit.api.work.ForgeRecipe;
import townkit.api.work.GearSlot;

/**
 * Reflection over Towntopia's smith types TownKit can't name (package-private: {@code BlacksmithActivity$Recipe},
 * {@code $Station}, {@code $Mat}, {@code $Planner}) and its private tables: reads Towntopia's own recipes and gear
 * tiers once, and builds Towntopia recipe objects for the merged view. The one place that knows their shapes.
 */
final class TowntopiaSmith {
    private static final String BEHAVIOR = "com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity$";
    /** Vanilla shovels by Towntopia tier (Towntopia rates them but its smith never makes them). */
    private static final Item[] SHOVELS = {Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL};

    /** True while Towntopia's own recipe methods are read: TownKit's recipe hooks let them answer as shipped. */
    static volatile boolean loading;

    private static final class Refs {
        final Class<?> recipe;
        final Constructor<?> recipeCtor;
        final Method result;
        final Method count;
        final Method tier;
        final Method kind;
        final Method armorSlot;
        final Method cost;
        final Method station;
        final Object[] stations;
        final Method toolRecipe;
        final Method armorRecipe;
        final Field plannerLevel;
        final Class<?> mat;
        final Map<String, Object> stock = new LinkedHashMap<>();
        final Map<ForgeOrderBook.ToolKind, Item[]> toolItems;
        final Item[][] armorItems;
        final List<Set<Item>> tierSets = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Refs() throws ReflectiveOperationException {
            this.recipe = Class.forName(BEHAVIOR + "Recipe", false, BlacksmithActivity.class.getClassLoader());
            this.recipeCtor = this.recipe.getDeclaredConstructors()[0];
            this.recipeCtor.setAccessible(true);
            this.result = accessor("result");
            this.count = accessor("count");
            this.tier = accessor("tier");
            this.kind = accessor("kind");
            this.armorSlot = accessor("armorSlot");
            this.cost = this.recipe.getDeclaredMethod("cost");
            this.cost.setAccessible(true);
            this.station = accessor("station");
            this.stations = Class.forName(BEHAVIOR + "Station", false, BlacksmithActivity.class.getClassLoader()).getEnumConstants();
            this.toolRecipe = BlacksmithActivity.class.getDeclaredMethod("toolRecipe", ForgeOrderBook.ToolKind.class, int.class);
            this.toolRecipe.setAccessible(true);
            this.armorRecipe = BlacksmithActivity.class.getDeclaredMethod("armorRecipe", int.class, int.class);
            this.armorRecipe.setAccessible(true);
            Class<?> planner = Class.forName(BEHAVIOR + "Planner", false, BlacksmithActivity.class.getClassLoader());
            this.plannerLevel = planner.getDeclaredField("level");
            this.plannerLevel.setAccessible(true);
            this.mat = Class.forName(BEHAVIOR + "Mat", false, BlacksmithActivity.class.getClassLoader());
            for (String f : List.of("SHIELD", "SHEARS", "BUCKET", "TEMPLATE_COPY")) {
                Field field = BlacksmithActivity.class.getDeclaredField(f);
                field.setAccessible(true);
                this.stock.put(f, field.get(null));
            }
            Field tools = BlacksmithActivity.class.getDeclaredField("TOOL_ITEMS");
            tools.setAccessible(true);
            this.toolItems = (Map<ForgeOrderBook.ToolKind, Item[]>) tools.get(null);
            Field armor = BlacksmithActivity.class.getDeclaredField("ARMOR_ITEMS");
            armor.setAccessible(true);
            this.armorItems = (Item[][]) armor.get(null);
            for (String f : List.of("STONE", "IRON", "DIAMOND", "NETHERITE")) {
                Field field = ForgeOrderBook.class.getDeclaredField(f);
                field.setAccessible(true);
                this.tierSets.add((Set<Item>) field.get(null));
            }
        }

        private Method accessor(String name) throws NoSuchMethodException {
            Method m = this.recipe.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        }
    }

    @Nullable
    private static volatile Refs refs;
    /** Towntopia's own recipe objects by result (an untouched recipe on an untouched ladder is handed back as is). */
    private static Map<Item, Object> originals = Map.of();

    private TowntopiaSmith() {
    }

    private static Refs refs() {
        Refs r = refs;
        if (r != null) return r;
        synchronized (TowntopiaSmith.class) {
            if (refs == null) {
                try {
                    refs = new Refs();
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new IllegalStateException("TownKit can't read Towntopia's smith (Towntopia changed?): " + e, e);
                }
            }
            return refs;
        }
    }

    /** Towntopia's three spare ores, as its {@code SPARE_ORE} lists them: raw iron, raw gold, raw copper and their ingots. */
    static List<SmeltOverlay.Smelt> smeltBuiltIns() {
        return List.of(new SmeltOverlay.Smelt(Items.RAW_IRON, Items.IRON_INGOT), new SmeltOverlay.Smelt(Items.RAW_GOLD, Items.GOLD_INGOT),
            new SmeltOverlay.Smelt(Items.RAW_COPPER, Items.COPPER_INGOT));
    }

    /** Towntopia's forge recipes, stock tiers and gear tiers (read once; Towntopia's own methods answer as shipped). */
    static ForgeOverlay.BuiltIns builtIns() {
        Refs r = refs();
        List<ForgeRecipe> recipes = new ArrayList<>();
        Map<Item, Object> own = new HashMap<>();
        Map<Item, Integer> stockTiers = new HashMap<>();
        loading = true;
        try {
            for (ForgeOrderBook.ToolKind kind : ForgeOrderBook.ToolKind.values()) {
                for (int t = 0; t < ForgeOverlay.TOWNTOPIA_TIERS.size(); t++) add(r, r.toolRecipe.invoke(null, kind, t), recipes, own);
            }
            for (int slot = 0; slot < ForgeOverlay.ARMOR_SLOTS.size(); slot++) {
                for (int t = 0; t < ForgeOverlay.TOWNTOPIA_TIERS.size(); t++) add(r, r.armorRecipe.invoke(null, slot, t), recipes, own);
            }
            for (Object stock : r.stock.values()) {
                add(r, stock, recipes, own);
                stockTiers.put((Item) r.result.invoke(stock), (Integer) r.tier.invoke(stock));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TownKit can't read Towntopia's forge recipes: " + e, e);
        } finally {
            loading = false;
        }
        Map<Item, Integer> gearTiers = new HashMap<>();
        for (Item[] items : r.toolItems.values()) gearTiers.put(items[0], 0);
        for (Item[] items : r.armorItems) {
            if (items[0] != null) gearTiers.put(items[0], 0);
        }
        gearTiers.put(Items.WOODEN_SHOVEL, 0);
        for (int t = 0; t < r.tierSets.size(); t++) {
            for (Item i : r.tierSets.get(t)) gearTiers.put(i, t + 1);
        }
        originals = Map.copyOf(own);
        return new ForgeOverlay.BuiltIns(List.copyOf(recipes), Map.copyOf(stockTiers), Map.copyOf(gearTiers));
    }

    private static void add(Refs r, @Nullable Object recipe, List<ForgeRecipe> out, Map<Item, Object> own) throws ReflectiveOperationException {
        if (recipe == null) return;
        Item result = (Item) r.result.invoke(recipe);
        ForgeRecipe.Builder b = ForgeRecipe.builder(result).count((Integer) r.count.invoke(recipe));
        int[] cost = (int[]) r.cost.invoke(recipe);
        for (int i = 0; i < Math.min(cost.length, ForgeOverlay.TOWNTOPIA_MATERIALS.size()); i++) {
            if (cost[i] > 0) b.cost(ForgeOverlay.TOWNTOPIA_MATERIALS.get(i), cost[i]);
        }
        Object kind = r.kind.invoke(recipe);
        int armorSlot = (Integer) r.armorSlot.invoke(recipe);
        int tier = (Integer) r.tier.invoke(recipe);
        if (kind != null) {
            b.gear(ForgeOverlay.TOWNTOPIA_TIERS.get(tier), ForgeOverlay.TOOL_KINDS.get(((Enum<?>) kind).ordinal()));
        } else if (armorSlot >= 0) {
            b.gear(ForgeOverlay.TOWNTOPIA_TIERS.get(tier), ForgeOverlay.ARMOR_SLOTS.get(armorSlot));
        }
        b.smithing(((Enum<?>) r.station.invoke(recipe)).ordinal() == 1);
        out.add(b.build());
        own.put(result, recipe);
    }

    /** Towntopia's own recipe object for {@code result}, or null. */
    @Nullable
    static Object original(Item result) {
        return originals.get(result);
    }

    /** Towntopia's own stock recipe object by field name ({@code SHIELD}, {@code SHEARS}, {@code BUCKET}, {@code TEMPLATE_COPY}). */
    static Object stockRecipe(String field) {
        return refs().stock.get(field);
    }

    /** A Towntopia recipe object for {@code r} on the ladder of {@code v}: cost by material slot, tier as a position. */
    static Object recipe(ForgeOverlay.Resolved r, int mats) {
        Refs refs = refs();
        ForgeRecipe f = r.recipe();
        Object kind = r.kind() >= 0 ? ForgeOrderBook.ToolKind.values()[r.kind()] : null;
        int[] cost = Arrays.copyOf(r.cost(), mats);
        try {
            return refs.recipeCtor.newInstance(f.result(), f.count(), r.tier(), kind, r.armorSlot(), cost, refs.stations[f.smithing() ? 1 : 0]);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TownKit can't make a Towntopia forge recipe: " + e, e);
        }
    }

    /** How many constants Towntopia's material enum has (8 as shipped, 8 + spares once TownKit extended it). */
    static int materialCount() {
        return refs().mat.getEnumConstants().length;
    }

    /** A planner's smith level. */
    static int plannerLevel(Object planner) {
        try {
            return refs().plannerLevel.getInt(planner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Towntopia's (or vanilla's, for shovels) item of {@code slot} at Towntopia tier {@code t}, or null (no stone armour). */
    @Nullable
    static Item vanillaGear(GearSlot slot, int t) {
        Refs r = refs();
        if (slot == GearSlot.SHOVEL) return SHOVELS[t];
        int kind = ForgeOverlay.TOOL_KINDS.indexOf(slot);
        if (kind >= 0) return r.toolItems.get(ForgeOrderBook.ToolKind.values()[kind])[t];
        return r.armorItems[ForgeOverlay.ARMOR_SLOTS.indexOf(slot)][t];
    }
}
