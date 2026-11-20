package townkit.core.work.cook;

import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import townkit.api.work.Dish;
import townkit.api.work.DishChange;
import townkit.core.TownKit;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/**
 * The merged dish list ({@link WorkTable} of kind {@value #KIND}) seen as Towntopia's own types: {@link CookActivity.Recipe}s,
 * the dish and ingredient sets, and the cook's settings schema. Towntopia reads it through the class-load rewrites in
 * {@code work-sites.tsv} (the static {@code on*} bridges below) and {@link CookHooks}.
 *
 * <p>With nothing registered every bridge hands back Towntopia's own object after one boolean read
 * ({@link WorkTable#touched()}). Otherwise the merged view is built once per table version and cached: lookups don't
 * allocate. Towntopia's untouched dishes keep their own {@code Recipe} objects; a changed or added dish gets a new one,
 * so a recipe a cook holds from before a change is no longer {@linkplain #isLive live}.
 *
 * <p>A dish with nutrition 0 keeps the item's own food values: the cook plans with the item's own nutrition
 * ({@link #recipes()}), while the food-value step reads {@link #foodRecipes()}, where the dish is still at 0.
 *
 * <p>A dish with a {@linkplain Dish#station() station} is a table recipe to Towntopia ({@code needsFurnace} false, even
 * when heated): the cook works at the station block the way it works at a crafting table. It is listed everywhere
 * (settings rows, stock orders, {@link #recipes()}) but left out of {@link #plannedRecipesFor} (heat companions and
 * restarts from carried ingredients, which skip Towntopia's candidate check), so the cook takes it up only through
 * {@code CookPlanner}, whose candidate checks {@link CookHooks} gate on the station's blocks ({@link Stations}).
 *
 * <p>A dish with tag ingredients is still one recipe here (its inputs: the first of its {@linkplain CookVariants#variants
 * variants}); {@link CookVariants} swaps in the village's chosen variant where Towntopia reads a recipe's inputs. Its
 * recipe object stays the same while its value and its variants do (a tag reload with the same members keeps it). Every
 * member of its tags is in {@link #ingredients()}. A tag dish whose tag has no members is left out.
 *
 */
public final class CookOverlay {
    /** The data-file kind (folder {@code townkit/cook_dishes}). */
    public static final String KIND = "cook_dishes";
    /** How many earlier merged lists' recipes {@link #isStale} still recognises. */
    static final int HISTORY = 8;
    private static final String OFF_LINE = "\nOFF: never makes it.";
    private static final String HEAT = " Cooked on a smoker (fastest), furnace or campfire.";
    private static final String TABLE = " Made at a crafting table.";
    /** The highest stock Towntopia's stock rows allow (an added dish's row goes higher if its stock does). */
    private static final int STOCK_MAX = 256;
    private static final String STOCK_TEXT = "How many of this dish to keep.\nSet for 10 villagers, and it\ngrows with the village. 0 never\nmakes it.";

    /** Item ids and display names (the registry in game; the unit tests hand their own). */
    public interface ItemIds {
        ResourceLocation id(Item item);

        /** The item with this id, or null when there is none (air counts as none). */
        @Nullable
        Item byId(ResourceLocation id);

        /** The item's name for settings rows. */
        String name(Item item);

        /** The nutrition of the item's own food values, 0 when it has none. */
        int defaultNutrition(Item item);

        /** The items in {@code tag}, in tag order, each once (none when the tag is unknown or not bound yet). */
        default List<Item> tagMembers(TagKey<Item> tag) {
            return List.of();
        }

        /** Whether item tags are bound (a tag dish with an empty tag is reported only then). */
        default boolean tagsBound() {
            return true;
        }
    }

    /** The game's items. */
    public static final ItemIds REGISTRY = new ItemIds() {
        @Override
        public ResourceLocation id(Item item) {
            return BuiltInRegistries.ITEM.getKey(item);
        }

        @Nullable
        @Override
        public Item byId(ResourceLocation id) {
            Item i = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            return i == null || i == Items.AIR ? null : i;
        }

        @Override
        public String name(Item item) {
            return item.getDescription().getString();
        }

        @Override
        public int defaultNutrition(Item item) {
            FoodProperties food = item.components().get(DataComponents.FOOD);
            return food == null ? 0 : food.nutrition();
        }

        @Override
        public List<Item> tagMembers(TagKey<Item> tag) {
            Optional<HolderSet.Named<Item>> set = BuiltInRegistries.ITEM.getTag(tag);
            if (set.isEmpty()) return List.of();
            LinkedHashSet<Item> out = new LinkedHashSet<>();
            for (Holder<Item> h : set.get()) {
                Item i = h.value();
                if (i != null && i != Items.AIR) out.add(i);
            }
            return List.copyOf(out);
        }

        @Override
        public boolean tagsBound() {
            return tagsLoaded;
        }
    };

    /** Set once the server's item tags were (re)loaded ({@link #tagsChanged}). */
    private static volatile boolean tagsLoaded;

    /** The server is stopping: the next world's tags are not loaded yet (a second world in this JVM starts clean). */
    public static void serverStopping() {
        tagsLoaded = false;
    }

    /** The running game's overlay. */
    public static final CookOverlay GLOBAL = new CookOverlay(new WorkTable<>(KIND, Dish::result), CookOverlay::readTowntopia,
        () -> CookSettingsSchema.SCHEMA, REGISTRY);

    /**
     * One merged state, for one table version. Sets and lists are immutable; {@code live} is an identity set;
     * {@code variants}: each tag dish's recipe (by identity) to its input variants (the first is the recipe's inputs);
     * {@code keeps}: the results of the dishes that keep their ingredients' containers.
     */
    private record View(long version, List<CookActivity.Recipe> recipes, List<CookActivity.Recipe> foodRecipes,
            List<CookActivity.Recipe> planned, Set<Item> dishes,
            Set<Item> ingredients, Map<Item, ResourceLocation> stations, Set<CookActivity.Recipe> live,
            SettingsSchema schema, Map<CookActivity.Recipe, List<Map<Item, Integer>>> variants, Set<Item> keeps) {
    }

    /** A tag dish's recipe value with its variants: the recipe object stays the same only while both do. */
    private record TagRecipe(CookActivity.Recipe recipe, List<Map<Item, Integer>> variants) {
    }

    private final WorkTable<Item, Dish> table;
    private final Supplier<List<CookActivity.Recipe>> towntopiaSource;
    private final Supplier<SettingsSchema> schemaSource;
    private final ItemIds items;
    @Nullable
    private volatile List<CookActivity.Recipe> towntopia;
    /** Towntopia's dishes as TownKit dishes, to Towntopia's recipe objects. */
    private Map<Dish, CookActivity.Recipe> builtInRecipes = Map.of();
    @Nullable
    private volatile View view;
    /**
     * The dish recipe objects Towntopia and the last {@link #HISTORY} views handed out, by identity; replaced, never
     * changed. Lets {@link #isStale} tell a dish recipe from before a change from the side jobs {@code CookPlanner}
     * builds itself. Bounded: Towntopia's list plus {@code HISTORY + 1} merged lists.
     */
    private volatile Set<CookActivity.Recipe> known = Set.of();
    /** The recipe lists of the last views, newest last (rebuild only, under the lock). */
    private final java.util.ArrayDeque<List<CookActivity.Recipe>> history = new java.util.ArrayDeque<>();
    /**
     * The recipe object handed out for each recipe value (records compare by value): an equal dish coming back after a
     * rebuild (a reload of the same files, an unrelated registration, a stock-only change) gets the very object a cook
     * may be holding, so its batch isn't ended. Towntopia's own objects first; only current recipes are kept.
     */
    private Map<CookActivity.Recipe, CookActivity.Recipe> canonical = Map.of();
    /** Like {@link #canonical}, for the food view's recipes (a keep-own dish at nutrition 0). */
    private Map<CookActivity.Recipe, CookActivity.Recipe> canonicalFood = Map.of();
    /** Like {@link #canonical}, for tag dishes: keyed by the recipe value and its variants (only current ones kept). */
    private Map<TagRecipe, CookActivity.Recipe> canonicalTag = Map.of();
    /** True while Towntopia's own list is being read (the bridges hand back what they get). */
    private volatile boolean loading;

    public CookOverlay(WorkTable<Item, Dish> table, Supplier<List<CookActivity.Recipe>> towntopia, Supplier<SettingsSchema> schema,
            ItemIds items) {
        this.table = table;
        this.towntopiaSource = towntopia;
        this.schemaSource = schema;
        this.items = items;
    }

    /** The default of Towntopia's stock row for {@code result} ({@link Dish#DEFAULT_STOCK} when it has none). */
    private int towntopiaStock(SettingsSchema schema, Item result) {
        int stock = schema.findRow(CookSettingsSchema.stockId(this.items.id(result).toString()))
            .map(SettingsSchema.Row::defaultInt).orElse(Dish.DEFAULT_STOCK);
        return stock >= 1 ? stock : Dish.DEFAULT_STOCK;
    }

    /** Towntopia's own list, read from its private field (the getter may be rewritten to answer the merged list). */
    @SuppressWarnings("unchecked")
    private static List<CookActivity.Recipe> readTowntopia() {
        try {
            Field f = CookActivity.class.getDeclaredField("RECIPES");
            f.setAccessible(true);
            return (List<CookActivity.Recipe>) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            TownKit.LOG.warn("[TownKit] can't read Towntopia's dish list ({}); using its recipes() getter", e.toString());
            return CookActivity.recipes();
        }
    }

    public WorkTable<Item, Dish> table() {
        return this.table;
    }

    /**
     * Reads Towntopia's dishes into the table, once (before data files load, so a change of a Towntopia dish isn't
     * reported missing). Called at common setup; every reader also calls it.
     */
    public List<CookActivity.Recipe> loadTowntopia() {
        List<CookActivity.Recipe> t = this.towntopia;
        if (t != null) return t;
        synchronized (this) {
            if (this.towntopia == null) {
                List<CookActivity.Recipe> own;
                // the fallback source is Towntopia's (rewritten) getter: its bridge must answer as is meanwhile
                this.loading = true;
                try {
                    own = this.towntopiaSource.get();
                } finally {
                    this.loading = false;
                }
                Map<Dish, CookActivity.Recipe> byDish = new IdentityHashMap<>();
                Map<CookActivity.Recipe, CookActivity.Recipe> canonical = new HashMap<>();
                List<Dish> dishes = new ArrayList<>();
                SettingsSchema schema = this.schemaSource.get();
                for (CookActivity.Recipe r : own) {
                    Dish d = new Dish(r.result(), r.outputPerBatch(), r.inputsPerBatch(), r.needsFurnace(), r.nutrition(), null,
                        towntopiaStock(schema, r.result()));
                    byDish.put(d, r);
                    canonical.putIfAbsent(r, r);
                    dishes.add(d);
                }
                this.builtInRecipes = byDish;
                this.canonical = canonical;
                Set<CookActivity.Recipe> known = Collections.newSetFromMap(new IdentityHashMap<>());
                known.addAll(own);
                this.known = Collections.unmodifiableSet(known);
                this.table.builtIns(List.copyOf(dishes));
                // the exact list Towntopia holds: an untouched overlay hands it back as it is
                this.towntopia = own;
            }
            return this.towntopia;
        }
    }

    // --- registration (TownKitCooking) ---------------------------------------------------------------------------------

    public WorkTable.Handle add(String owner, Dish dish) {
        loadTowntopia();
        return this.table.add(owner, dish);
    }

    public WorkTable.Handle change(String owner, Item result, DishChange change) {
        loadTowntopia();
        return this.table.change(owner, result, change::apply);
    }

    public WorkTable.Handle remove(String owner, Item result) {
        loadTowntopia();
        return this.table.remove(owner, result);
    }

    // --- reads ---------------------------------------------------------------------------------------------------------

    /** False while nothing is registered: Towntopia's objects are used as they are. */
    public boolean touched() {
        return this.table.touched();
    }

    /** Bumped whenever the merged list changes. */
    public long version() {
        return this.table.version();
    }

    /** The merged dishes (Towntopia's 18 as dishes when nothing is registered). */
    public List<Dish> dishes() {
        loadTowntopia();
        return this.table.merged();
    }

    /** The merged list as Towntopia's recipes; Towntopia's own list object when nothing is registered. */
    public List<CookActivity.Recipe> recipes() {
        List<CookActivity.Recipe> own = loadTowntopia();
        return touched() ? current().recipes : own;
    }

    /**
     * The merged list as the food-value step reads it: like {@link #recipes()}, but a dish with nutrition 0 (keep the item's
     * own food values) is at nutrition 0 here, where {@link #recipes()} plans with the item's own nutrition. Towntopia's own
     * list object when nothing is registered. Its recipes are not {@linkplain #isLive live} planning recipes.
     */
    public List<CookActivity.Recipe> foodRecipes() {
        List<CookActivity.Recipe> own = loadTowntopia();
        return touched() ? current().foodRecipes : own;
    }

    public Set<Item> ingredients() {
        return current().ingredients;
    }

    public boolean isDish(Item item) {
        return current().dishes.contains(item);
    }

    /** The station of the dish making {@code result}, or null (no such dish, or no station). */
    @Nullable
    public ResourceLocation stationOf(Item result) {
        return current().stations.get(result);
    }

    /** How many recipe objects {@link #isStale} remembers (tests: it stays bounded). */
    int knownSize() {
        return this.known.size();
    }

    /** Whether the dish making {@code result} keeps its ingredients' containers. */
    public boolean keepsContainers(Item result) {
        return touched() && current().keeps.contains(result);
    }

    /** Whether any merged dish keeps its ingredients' containers. */
    public boolean hasKeepDishes() {
        return touched() && !current().keeps.isEmpty();
    }

    /** Memory of the last {@link #dishesWithInput} answer: the view it was read from, what was asked, the result. */
    private record InputCache(Object view, Item input, Item except, List<Item> results) {
    }

    @Nullable
    private volatile InputCache inputCache;

    /**
     * The results of the merged dishes (other than {@code except}) with {@code input} among the inputs of any of their
     * variants (a tag dish counts when one of its variants has it). Empty while nothing is registered. Kept per view, so
     * asking again allocates nothing.
     */
    public List<Item> dishesWithInput(Item input, Item except) {
        if (!touched()) return List.of();
        View v = current();
        InputCache c = this.inputCache;
        if (c != null && c.view == v && c.input == input && c.except == except) return c.results;
        List<Item> out = new ArrayList<>();
        for (CookActivity.Recipe r : v.recipes) {
            if (r.result() == except || out.contains(r.result())) continue;
            List<Map<Item, Integer>> vs = v.variants.get(r);
            boolean has = vs == null ? r.inputsPerBatch().containsKey(input) : vs.stream().anyMatch(m -> m.containsKey(input));
            if (has) out.add(r.result());
        }
        List<Item> results = out.isEmpty() ? List.of() : List.copyOf(out);
        this.inputCache = new InputCache(v, input, except, results);
        return results;
    }

    public boolean hasStationDishes() {
        return !current().stations.isEmpty();
    }

    /** Whether a dish with tag ingredients is in the merged list (with at least one variant). */
    public boolean hasTagDishes() {
        return touched() && !current().variants.isEmpty();
    }

    /**
     * The input variants of {@code recipe} when it is a tag dish's recipe handed out by the current view (by identity;
     * the first is the recipe's own inputs), else null.
     */
    @Nullable
    public List<Map<Item, Integer>> variantsOf(Object recipe) {
        if (!(recipe instanceof CookActivity.Recipe)) return null;
        Map<CookActivity.Recipe, List<Map<Item, Integer>>> v = current().variants;
        return v.isEmpty() ? null : v.get(recipe);
    }

    /**
     * Item tags were (re)loaded: tag dishes' variants are built again (a new table version) when the merged list has any.
     * A tag dish whose variants changed gets a new recipe object, so a batch on the old one ends ({@link #isStale}).
     */
    public void tagsChanged() {
        if (this.items == REGISTRY) tagsLoaded = true;
        loadTowntopia();
        for (Dish d : this.table.merged()) {
            if (!d.tagIngredients().isEmpty()) {
                this.table.bump();
                return;
            }
        }
    }

    /** Whether {@code recipe} is in the merged list as this very object (a recipe from before a change isn't). */
    public boolean isLive(CookActivity.Recipe recipe) {
        return current().live.contains(recipe);
    }

    /**
     * Whether {@code recipe} is a dish recipe from an earlier merged list that the current one no longer holds (the dish
     * was changed or removed since). Side jobs (sugar, bowls, charcoal) and recipes TownKit never handed out are never
     * stale.
     */
    public boolean isStale(CookActivity.Recipe recipe) {
        View v = current();
        return !v.live.contains(recipe) && this.known.contains(recipe);
    }

    /** The cook's settings schema with the merged dishes' rows; Towntopia's own object when nothing is registered. */
    public SettingsSchema schema() {
        return touched() ? current().schema : this.schemaSource.get();
    }

    // --- bridges: what Towntopia's rewritten reads get (work-sites.tsv) --------------------------------------------

    public List<CookActivity.Recipe> recipesFor(List<CookActivity.Recipe> towntopia) {
        if (!this.table.touched() || this.loading) return towntopia;
        return current().recipes;
    }

    /** The recipe list the food-value step reads: keep-own dishes at nutrition 0. */
    public List<CookActivity.Recipe> foodRecipesFor(List<CookActivity.Recipe> towntopia) {
        if (!this.table.touched() || this.loading) return towntopia;
        return current().foodRecipes;
    }

    /** The recipes the cook may plan from (the merged list without station dishes). */
    public List<CookActivity.Recipe> plannedRecipesFor(List<CookActivity.Recipe> towntopia) {
        if (!this.table.touched() || this.loading) return towntopia;
        return current().planned;
    }

    public Set<Item> dishesFor(Set<Item> towntopia) {
        if (!this.table.touched() || this.loading) return towntopia;
        return current().dishes;
    }

    public Set<Item> ingredientsFor(Set<Item> towntopia) {
        if (!this.table.touched() || this.loading) return towntopia;
        return current().ingredients;
    }

    public SettingsSchema schemaFor(SettingsSchema towntopia) {
        if (!this.table.touched() || this.loading || towntopia == null) return towntopia;
        return current().schema;
    }

    /** Rewritten {@code getstatic CookActivity.RECIPES} (also the body of {@code recipes()}, so every caller of it). */
    public static List<CookActivity.Recipe> onRecipes(List<CookActivity.Recipe> towntopia) {
        try {
            return GLOBAL.recipesFor(towntopia);
        } catch (RuntimeException e) {
            return failed(towntopia, e);
        }
    }

    /** Rewritten {@code getstatic CookActivity.RECIPES} in {@code applyProducedFoodProperties} (the made dish's food value). */
    public static List<CookActivity.Recipe> onFoodRecipes(List<CookActivity.Recipe> towntopia) {
        try {
            return GLOBAL.foodRecipesFor(towntopia);
        } catch (RuntimeException e) {
            return failed(towntopia, e);
        }
    }

    /** Rewritten {@code getstatic CookActivity.RECIPES} in the cook's own planning loops (no station dishes). */
    public static List<CookActivity.Recipe> onPlannedRecipes(List<CookActivity.Recipe> towntopia) {
        try {
            return GLOBAL.plannedRecipesFor(towntopia);
        } catch (RuntimeException e) {
            return failed(towntopia, e);
        }
    }

    /** Rewritten {@code getstatic CookActivity.DISHES}. */
    public static Set<Item> onDishes(Set<Item> towntopia) {
        try {
            return GLOBAL.dishesFor(towntopia);
        } catch (RuntimeException e) {
            return failed(towntopia, e);
        }
    }

    /** Rewritten {@code getstatic CookActivity.INGREDIENTS}. */
    public static Set<Item> onIngredients(Set<Item> towntopia) {
        try {
            return GLOBAL.ingredientsFor(towntopia);
        } catch (RuntimeException e) {
            return failed(towntopia, e);
        }
    }

    /** Rewritten {@code getstatic CookSettingsSchema.SCHEMA} (the schema every {@code CookSettings} reads). */
    public static SettingsSchema onSchema(SettingsSchema towntopia) {
        try {
            return GLOBAL.schemaFor(towntopia);
        } catch (RuntimeException e) {
            return failed(towntopia, e);
        }
    }

    private static <T> T failed(T towntopia, RuntimeException e) {
        WorkLog.GLOBAL.once("cook-bridge|" + e.getClass().getName(), "[" + KIND + "] the merged dish list failed (" + e
            + "); Towntopia's own list is used");
        return towntopia;
    }

    // --- the merged view -------------------------------------------------------------------------------------------

    private View current() {
        loadTowntopia();
        View v = this.view;
        long version = this.table.version();
        if (v != null && v.version == version) return v;
        return rebuild();
    }

    private synchronized View rebuild() {
        long version = this.table.version();
        View v = this.view;
        if (v != null && v.version == version) return v;
        List<CookActivity.Recipe> own = this.towntopia;
        Map<Dish, List<Map<Item, Integer>>> tagVariants = new IdentityHashMap<>();
        Set<Item> tagMembers = new LinkedHashSet<>();
        List<Dish> dishes = expandTags(this.table.merged(), tagVariants, tagMembers);
        boolean touched = this.table.touched();
        List<CookActivity.Recipe> recipes;
        List<CookActivity.Recipe> foodRecipes;
        Map<CookActivity.Recipe, List<Map<Item, Integer>>> variants = new IdentityHashMap<>();
        if (!touched) {
            recipes = own;
            foodRecipes = own;
        } else {
            Map<CookActivity.Recipe, CookActivity.Recipe> next = new HashMap<>();
            Map<TagRecipe, CookActivity.Recipe> nextTag = new HashMap<>();
            for (CookActivity.Recipe r : own) next.putIfAbsent(r, r);
            List<CookActivity.Recipe> out = new ArrayList<>(dishes.size());
            for (Dish d : dishes) {
                List<Map<Item, Integer>> vs = tagVariants.get(d);
                CookActivity.Recipe r;
                if (vs == null) {
                    r = toRecipe(d);
                    next.put(r, r);
                } else {
                    r = toTagRecipe(d, vs, nextTag);
                    variants.put(r, vs);
                }
                out.add(r);
            }
            this.canonical = next;
            this.canonicalTag = nextTag;
            recipes = List.copyOf(out);
            Map<CookActivity.Recipe, CookActivity.Recipe> nextFood = new HashMap<>();
            List<CookActivity.Recipe> food = new ArrayList<>(recipes.size());
            boolean differs = false;
            for (int i = 0; i < recipes.size(); i++) {
                CookActivity.Recipe r = recipes.get(i);
                CookActivity.Recipe f = foodRecipe(dishes.get(i), r, nextFood);
                differs |= f != r;
                food.add(f);
            }
            this.canonicalFood = nextFood;
            foodRecipes = differs ? List.copyOf(food) : recipes;
        }
        List<CookActivity.Recipe> planned = new ArrayList<>(recipes.size());
        Map<Item, ResourceLocation> stations = new HashMap<>();
        Set<Item> results = new HashSet<>();
        Set<Item> ingredients = new LinkedHashSet<>();
        Set<Item> keeps = new HashSet<>();
        for (Dish d : dishes) {
            if (d.station() != null) stations.put(d.result(), d.station());
            if (d.keepContainers()) keeps.add(d.result());
        }
        Set<CookActivity.Recipe> live = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CookActivity.Recipe r : recipes) {
            if (!stations.containsKey(r.result())) planned.add(r);
            results.add(r.result());
            ingredients.addAll(r.inputsPerBatch().keySet());
            live.add(r);
        }
        // every member of a tag dish's tags (also those past the variant cap): never composted, never crafted with
        ingredients.addAll(tagMembers);
        SettingsSchema base = this.schemaSource.get();
        View next = new View(version, recipes, foodRecipes, stations.isEmpty() ? recipes : List.copyOf(planned), Set.copyOf(results),
            Collections.unmodifiableSet(ingredients), Map.copyOf(stations), Collections.unmodifiableSet(live),
            touched ? mergeSchema(base, dishes) : base,
            variants.isEmpty() ? Map.of() : Collections.unmodifiableMap(variants), Set.copyOf(keeps));
        this.history.addLast(recipes);
        while (this.history.size() > HISTORY + 1) this.history.removeFirst();
        Set<CookActivity.Recipe> known = Collections.newSetFromMap(new IdentityHashMap<>());
        known.addAll(own);
        for (List<CookActivity.Recipe> h : this.history) known.addAll(h);
        this.known = Collections.unmodifiableSet(known);
        this.view = next;
        return next;
    }

    /**
     * {@code merged} with each tag dish's variants put in {@code variants} and its tags' members in {@code members}. A tag
     * dish with no variant (a tag without members) is left out, with one log line once tags are bound (before that, every
     * tag is empty). Item-only dishes (and the list itself, when nothing is left out) are untouched.
     */
    private List<Dish> expandTags(List<Dish> merged, Map<Dish, List<Map<Item, Integer>>> variants, Set<Item> members) {
        List<Dish> out = null;
        Map<TagKey<Item>, List<Item>> byTag = new HashMap<>();
        for (int i = 0; i < merged.size(); i++) {
            Dish d = merged.get(i);
            if (d.tagIngredients().isEmpty()) {
                if (out != null) out.add(d);
                continue;
            }
            List<Map<Item, Integer>> vs = CookVariants.variants(d, t -> byTag.computeIfAbsent(t, this.items::tagMembers));
            if (!vs.isEmpty()) {
                variants.put(d, vs);
                for (TagKey<Item> t : d.tagIngredients().keySet()) members.addAll(byTag.get(t));
                if (out != null) out.add(d);
                continue;
            }
            if (out == null) out = new ArrayList<>(merged.subList(0, i));
            if (this.items.tagsBound()) {
                String id = String.valueOf(this.items.id(d.result()));
                List<String> empty = new ArrayList<>();
                for (TagKey<Item> t : d.tagIngredients().keySet()) {
                    if (byTag.get(t) != null && byTag.get(t).isEmpty()) empty.add("#" + t.location());
                }
                WorkLog.GLOBAL.once("tag-dish-empty|" + id + "|" + empty, "[" + KIND + "] " + id + ": no item is in " + empty
                    + ", so the cook can't make it; the dish is left out");
            }
        }
        return out == null ? merged : List.copyOf(out);
    }

    /**
     * The recipe for tag dish {@code d} (inputs: its first variant): the object handed out before for the same recipe
     * value and the same variants, else new. Kept apart from {@link #canonical}, so a change of the members alone makes a
     * new object (a batch on the old one ends).
     */
    private CookActivity.Recipe toTagRecipe(Dish d, List<Map<Item, Integer>> vs, Map<TagRecipe, CookActivity.Recipe> next) {
        CookActivity.Recipe r = new CookActivity.Recipe(d.result(), d.outputPerBatch(), vs.get(0), d.heated() && d.station() == null,
            d.nutrition() > 0 ? d.nutrition() : this.items.defaultNutrition(d.result()));
        TagRecipe key = new TagRecipe(r, vs);
        CookActivity.Recipe before = this.canonicalTag.get(key);
        CookActivity.Recipe out = before != null ? before : r;
        next.put(key, out);
        return out;
    }

    /**
     * The food view's recipe for {@code d} ({@code planning} is its planning recipe): the planning recipe itself, unless
     * the dish keeps the item's own food values and the item has some; then the same recipe at nutrition 0, the object
     * handed out before for an equal one when there is one.
     */
    private CookActivity.Recipe foodRecipe(Dish d, CookActivity.Recipe planning, Map<CookActivity.Recipe, CookActivity.Recipe> next) {
        if (d.nutrition() > 0 || planning.nutrition() == 0) return planning;
        CookActivity.Recipe r = new CookActivity.Recipe(planning.result(), planning.outputPerBatch(), planning.inputsPerBatch(),
            planning.needsFurnace(), 0);
        CookActivity.Recipe before = this.canonicalFood.get(r);
        CookActivity.Recipe out = before != null ? before : r;
        next.put(out, out);
        return out;
    }

    /** The recipe for {@code d}: Towntopia's own object, else the object handed out before for an equal recipe, else new. */
    private CookActivity.Recipe toRecipe(Dish d) {
        CookActivity.Recipe own = this.builtInRecipes.get(d);
        if (own != null) return own;
        // a station dish is worked at its station the way Towntopia works a table dish (heated or not)
        CookActivity.Recipe r = new CookActivity.Recipe(d.result(), d.outputPerBatch(), d.ingredients(),
            d.heated() && d.station() == null, d.nutrition() > 0 ? d.nutrition() : this.items.defaultNutrition(d.result()));
        CookActivity.Recipe before = this.canonical.get(r);
        return before != null ? before : r;
    }

    /**
     * Towntopia's schema with the merged dishes: Towntopia's rows for its untouched dishes as they are; a Towntopia dish
     * an addon changed or replaced keeps its label and stock row, with a "needs" line rebuilt from its ingredients and
     * the dish's {@link Dish#stock()} as the row's default; a removed one loses its switch and stock row; an added dish
     * gets a switch and a stock row (its stock as the default) where Towntopia's dish stock rows are (after the last of
     * them, kept or removed). Rows are keyed as Towntopia keys them (item id; {@code CookSettingsSchema.stockId}).
     */
    private SettingsSchema mergeSchema(SettingsSchema base, List<Dish> dishes) {
        Map<String, Dish> merged = new HashMap<>();
        for (Dish d : dishes) merged.put(this.items.id(d.result()).toString(), d);
        Set<String> builtInIds = new HashSet<>();
        Map<String, String> stockToDish = new HashMap<>();
        for (Dish d : this.builtInRecipes.keySet()) {
            String id = this.items.id(d.result()).toString();
            builtInIds.add(id);
            stockToDish.put(CookSettingsSchema.stockId(id), id);
        }
        List<Dish> added = new ArrayList<>();
        for (Dish d : dishes) {
            if (!builtInIds.contains(this.items.id(d.result()).toString())) added.add(d);
        }

        // pass 1: Towntopia's rows, merged; remember where its dish stock rows were
        List<List<SettingsSchema.Row>> sectionRows = new ArrayList<>();
        int anchorSection = -1;
        int anchor = -1;
        String stockText = STOCK_TEXT;
        for (SettingsSchema.Section s : base.sections()) {
            List<SettingsSchema.Row> rows = new ArrayList<>();
            for (SettingsSchema.Row r : s.rows()) {
                if (r.kind() == SettingsSchema.RowKind.ALLOW_LIST && r.settingId().equals(CookSettingsSchema.ALLOWED_RECIPES)) {
                    rows.add(allowRow(r, merged, builtInIds, added));
                    continue;
                }
                String dishId = stockToDish.get(r.settingId());
                if (dishId == null) {
                    rows.add(r);
                    continue;
                }
                stockText = r.description();
                Dish d = merged.get(dishId);
                if (d != null) rows.add(this.builtInRecipes.containsKey(d) ? r : withDefault(r, d.stock()));
                // the added rows go after the last of Towntopia's dish stock rows, kept or removed
                anchorSection = sectionRows.size();
                anchor = rows.size();
            }
            sectionRows.add(rows);
        }
        if (anchorSection < 0 && !added.isEmpty()) {
            anchorSection = sectionRows.size() - 1;
            anchor = sectionRows.get(anchorSection).size();
            WorkLog.GLOBAL.once("stock-anchor", "[" + KIND + "] Towntopia's cook settings have no dish stock rows; added dishes' "
                + "stock rows go at the end of the last section");
        }

        // pass 2: the added dishes' stock rows, unless a row with that id was emitted already
        Set<String> emitted = new HashSet<>();
        for (List<SettingsSchema.Row> rows : sectionRows) {
            for (SettingsSchema.Row r : rows) emitted.add(r.settingId());
        }
        List<SettingsSchema.Row> extra = new ArrayList<>();
        for (Dish d : added) {
            String id = this.items.id(d.result()).toString();
            String rowId = CookSettingsSchema.stockId(id);
            if (!emitted.add(rowId)) {
                WorkLog.GLOBAL.once("stock-row|" + rowId, "[" + KIND + "] " + id + " shares the stock row '" + rowId
                    + "' with another dish (Towntopia keys stock rows by the item's path): it gets no stock row of its own, "
                    + "so the cook won't make it");
                continue;
            }
            extra.add(SettingsSchema.Row.numericStepper(rowId, this.items.name(d.result()), d.stock(), 0, Math.max(STOCK_MAX, d.stock()), 8)
                .describedAs(stockText));
        }
        if (!extra.isEmpty()) sectionRows.get(anchorSection).addAll(anchor, extra);

        List<SettingsSchema.Section> sections = new ArrayList<>();
        for (int i = 0; i < base.sections().size(); i++) {
            sections.add(new SettingsSchema.Section(base.sections().get(i).title(), List.copyOf(sectionRows.get(i))));
        }
        return new SettingsSchema(base.profession(), base.tagName(), List.copyOf(sections));
    }

    /** {@code r} with {@code stock} as its default (its maximum raised to fit). */
    private static SettingsSchema.Row withDefault(SettingsSchema.Row r, int stock) {
        if (r.defaultInt() == stock) return r;
        return new SettingsSchema.Row(r.kind(), r.settingId(), r.label(), r.defaultBool(), stock, r.minInt(), Math.max(r.maxInt(), stock),
            r.stepInt(), r.tierNames(), r.allowListEntries(), r.description(), r.ticksAsSeconds(), r.keepsOne());
    }

    private SettingsSchema.Row allowRow(SettingsSchema.Row r, Map<String, Dish> merged, Set<String> builtInIds, List<Dish> added) {
        List<SettingsSchema.AllowListEntry> entries = new ArrayList<>();
        for (SettingsSchema.AllowListEntry e : r.allowListEntries()) {
            if (!builtInIds.contains(e.id())) {
                entries.add(e); // not one of Towntopia's dishes: left as it is
                continue;
            }
            Dish d = merged.get(e.id());
            if (d == null) continue; // removed
            if (this.builtInRecipes.containsKey(d)) entries.add(e);
            else entries.add(new SettingsSchema.AllowListEntry(e.id(), e.label(), e.defaultAllowed(), needs(d) + OFF_LINE));
        }
        for (Dish d : added) {
            entries.add(new SettingsSchema.AllowListEntry(this.items.id(d.result()).toString(), this.items.name(d.result()), true,
                needs(d) + OFF_LINE));
        }
        return new SettingsSchema.Row(r.kind(), r.settingId(), r.label(), r.defaultBool(), r.defaultInt(), r.minInt(), r.maxInt(),
            r.stepInt(), r.tierNames(), List.copyOf(entries), r.description(), r.ticksAsSeconds(), r.keepsOne());
    }

    /** "Needs 2 Apple, 1 Sugar and 2 of any #c:berries, and makes 2 at once. Made at a crafting table." */
    String needs(Dish d) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : d.ingredients().entrySet()) parts.add(e.getValue() + " " + this.items.name(e.getKey()));
        for (Map.Entry<TagKey<Item>, Integer> e : d.tagIngredients().entrySet()) parts.add(e.getValue() + " of any #" + e.getKey().location());
        StringBuilder sb = new StringBuilder("Needs ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(i == parts.size() - 1 ? " and " : ", ");
            sb.append(parts.get(i));
        }
        if (d.outputPerBatch() > 1) sb.append(", and makes ").append(d.outputPerBatch()).append(" at once");
        sb.append('.');
        if (d.station() != null) sb.append(" Made at ").append(d.station()).append('.');
        else sb.append(d.heated() ? HEAT : TABLE);
        return sb.toString();
    }
}
