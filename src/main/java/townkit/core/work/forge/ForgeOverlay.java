package townkit.core.work.forge;

import com.alex.test.village.villagers.blacksmith.BlacksmithBalance;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import townkit.api.work.ForgeRecipe;
import townkit.api.work.ForgeRecipeChange;
import townkit.api.work.GearSlot;
import townkit.api.work.TierPlace;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkSiteRewriter;
import townkit.core.work.WorkTable;

/**
 * The smith's merged lists: materials ({@value #MATERIALS}), gear tiers ({@value #TIERS}) and forge recipes
 * ({@value #RECIPES}), each a {@link WorkTable}, merged into one cached {@link View} in TownKit's own terms: the tier
 * ladder (Towntopia's five tiers with the addon tiers placed between and above them), the material slots (Towntopia's
 * eight, then the addon materials in {@link #SPARES} spare slots) and the recipes resolved against both. Towntopia's
 * side ({@link ForgeHooks}, {@link ForgeBridges}) reads the view; nothing here touches Towntopia's classes, so the unit
 * tests run it without a game.
 *
 * <p>Ladder: every Towntopia tier, then right after it the addon tiers placed after it in registration order, each
 * followed by the tiers placed after it. A tier placed after one that isn't on the ladder is skipped (logged once).
 * With no addon tier the ladder is Towntopia's 0..4 and positions are Towntopia's own ints. A recipe naming an unknown
 * material or tier is skipped (logged once). Towntopia's own materials and tiers can't be replaced or removed.
 */
public final class ForgeOverlay {
    public static final String MATERIALS = "forge_materials";
    public static final String TIERS = "forge_tiers";
    public static final String RECIPES = "forge_recipes";
    /** How many addon materials can be in use at once (the spare constants added to Towntopia's material enum). */
    public static final int SPARES = WorkSiteRewriter.SPARE_MATERIALS;

    private static ResourceLocation towntopia(String path) {
        return ResourceLocation.fromNamespaceAndPath("towntopia", path);
    }

    /** Towntopia's gear tiers by its tier int (0 wood .. 4 netherite). */
    public static final List<ResourceLocation> TOWNTOPIA_TIERS = List.of(towntopia("wood"), towntopia("stone"), towntopia("iron"),
        towntopia("diamond"), towntopia("netherite"));
    /** Towntopia's smith materials by enum ordinal. */
    public static final List<ResourceLocation> TOWNTOPIA_MATERIALS = List.of(towntopia("cobble"), towntopia("iron"),
        towntopia("leather"), towntopia("diamond"), towntopia("netherite"), towntopia("template"), towntopia("netherrack"),
        towntopia("wood"));
    /** The smith level each Towntopia tier needs (Towntopia: any level makes iron, 25 diamond, 75 netherite). */
    static final int[] TOWNTOPIA_SKILL = {1, 1, 1, BlacksmithBalance.DIAMOND_LEVEL, BlacksmithBalance.NETHERITE_LEVEL};
    /** Towntopia's tool kinds (its {@code ForgeOrderBook.ToolKind} order). */
    static final List<GearSlot> TOOL_KINDS = List.of(GearSlot.AXE, GearSlot.PICKAXE, GearSlot.HOE, GearSlot.SWORD);
    /** Towntopia's armour slots (its {@code ForgeOrderBook.ARMOR_SLOTS} order: chest, head, legs, feet). */
    static final List<GearSlot> ARMOR_SLOTS = List.of(GearSlot.CHESTPLATE, GearSlot.HELMET, GearSlot.LEGGINGS, GearSlot.BOOTS);

    /** A registered smith material: its items are those of {@code tag}, or the one {@code item}. */
    public record MaterialDef(ResourceLocation id, @Nullable TagKey<Item> tag, @Nullable Item item) {
        public MaterialDef {
            if ((tag == null) == (item == null)) throw new IllegalArgumentException("a material has a tag or one item");
        }
    }

    /** A registered gear tier at its place. */
    public record TierDef(ResourceLocation id, TierPlace place) {
    }

    /**
     * Towntopia's own forge recipes in TownKit's terms, with what they can't say: the tier int Towntopia gives its stock
     * recipes (shield, shears and bucket 2, template copy -1) and Towntopia's own tier of its tools and armour (wooden
     * tools and leather armour 0).
     */
    public record BuiltIns(List<ForgeRecipe> recipes, Map<Item, Integer> stockTiers, Map<Item, Integer> gearTiers) {
    }

    /**
     * One rung of the ladder.
     *
     * @param towntopia Towntopia's tier int, or -1 for an addon tier
     * @param above     the Towntopia tier this one follows for settings and craft points: itself for Towntopia's, else
     *                  the next Towntopia tier above it (netherite above netherite)
     * @param skill     the smith level it needs
     */
    public record Rung(ResourceLocation id, int towntopia, int above, int skill) {
    }

    /**
     * A merged recipe resolved against the ladder and the material slots.
     *
     * @param tier      the ladder position (gear), Towntopia's stock tier as a position, or -1
     * @param kind      Towntopia's tool kind ordinal, or -1
     * @param armorSlot Towntopia's armour slot index, or -1
     * @param cost      amount by material slot (Towntopia's 8, then the spares)
     * @param builtIn   the recipe is Towntopia's own, unchanged
     */
    public record Resolved(ForgeRecipe recipe, int tier, int kind, int armorSlot, int[] cost, boolean builtIn) {
    }

    /** The merged state for one set of table versions; immutable. */
    public static final class View {
        final long key;
        public final boolean touched;
        public final List<Rung> ladder;
        public final Map<ResourceLocation, Integer> positions;
        /** Ladder position of each Towntopia tier int. */
        final int[] towntopiaPos;
        /** The addon materials by spare slot (null where empty). */
        public final List<MaterialDef> spares;
        public final List<Resolved> recipes;
        final Map<Item, Resolved> byResult;
        final Resolved[][] tools;
        final Resolved[][] armor;
        /** Gear recipe results to their ladder position. */
        final Map<Item, Integer> gear;

        View(long key, boolean touched, List<Rung> ladder, List<MaterialDef> spares, List<Resolved> recipes, Map<Item, Resolved> byResult,
                Resolved[][] tools, Resolved[][] armor) {
            this.key = key;
            this.touched = touched;
            this.ladder = List.copyOf(ladder);
            Map<ResourceLocation, Integer> pos = new HashMap<>();
            this.towntopiaPos = new int[TOWNTOPIA_TIERS.size()];
            for (int i = 0; i < ladder.size(); i++) {
                pos.put(ladder.get(i).id(), i);
                if (ladder.get(i).towntopia() >= 0) this.towntopiaPos[ladder.get(i).towntopia()] = i;
            }
            this.positions = Collections.unmodifiableMap(pos);
            this.spares = Collections.unmodifiableList(new ArrayList<>(spares));
            this.recipes = List.copyOf(recipes);
            this.byResult = Collections.unmodifiableMap(byResult);
            this.tools = tools;
            this.armor = armor;
            Map<Item, Integer> gear = new HashMap<>();
            for (Resolved r : recipes) {
                if (r.recipe().isGear()) gear.put(r.recipe().result(), r.tier());
            }
            this.gear = Collections.unmodifiableMap(gear);
        }

        /** True while the ladder is Towntopia's own (no addon tier): positions are Towntopia's tier ints. */
        public boolean identity() {
            return this.ladder.size() == TOWNTOPIA_TIERS.size();
        }

        public int size() {
            return this.ladder.size();
        }

        /** The ladder position of Towntopia's tier int {@code t} (0..4); other values as they are. */
        public int position(int t) {
            return t >= 0 && t < this.towntopiaPos.length ? this.towntopiaPos[t] : t;
        }

        /** The ladder position of tier {@code id}, or -1. */
        public int position(ResourceLocation id) {
            Integer p = this.positions.get(id);
            return p == null ? -1 : p;
        }

        /** Towntopia's tier int a ladder position counts as (an addon tier: the Towntopia tier just above it). */
        public int towntopiaTier(int position) {
            if (position < 0) return position;
            Rung r = this.ladder.get(Math.min(position, this.ladder.size() - 1));
            return r.towntopia() >= 0 ? r.towntopia() : r.above();
        }

        @Nullable
        public Rung rung(int position) {
            return position >= 0 && position < this.ladder.size() ? this.ladder.get(position) : null;
        }

        /** The recipe making {@code result}, or null. */
        @Nullable
        public Resolved recipe(Item result) {
            return this.byResult.get(result);
        }

        /** The tool recipe of Towntopia's tool kind {@code kind} at ladder position {@code position}, or null. */
        @Nullable
        public Resolved tool(int kind, int position) {
            return kind >= 0 && kind < this.tools.length && position >= 0 && position < this.tools[kind].length ? this.tools[kind][position] : null;
        }

        /** The armour recipe of Towntopia's armour slot {@code slot} at ladder position {@code position}, or null. */
        @Nullable
        public Resolved armor(int slot, int position) {
            return slot >= 0 && slot < this.armor.length && position >= 0 && position < this.armor[slot].length ? this.armor[slot][position] : null;
        }

        /** The ladder position of a gear recipe's result, or -1. */
        public int gearPosition(Item item) {
            Integer p = this.gear.get(item);
            return p == null ? -1 : p;
        }

        /** The spare slot of addon material {@code id} (0..SPARES-1), or -1. */
        public int spareSlot(ResourceLocation id) {
            for (int i = 0; i < this.spares.size(); i++) {
                MaterialDef m = this.spares.get(i);
                if (m != null && m.id().equals(id)) return i;
            }
            return -1;
        }

        /** Whether any addon gear (a gear recipe whose result isn't one of Towntopia's own recipes) is in the view. */
        public boolean hasAddonGear() {
            for (Resolved r : this.recipes) {
                if (r.recipe().isGear() && !r.builtIn()) return true;
            }
            return false;
        }
    }

    /** The running game's overlay. */
    public static final ForgeOverlay GLOBAL = new ForgeOverlay(new WorkTable<>(MATERIALS, MaterialDef::id), new WorkTable<>(TIERS, TierDef::id),
        new WorkTable<>(RECIPES, ForgeRecipe::result), TowntopiaSmith::builtIns, WorkLog.GLOBAL);

    private final WorkTable<ResourceLocation, MaterialDef> materials;
    private final WorkTable<ResourceLocation, TierDef> tiers;
    private final WorkTable<Item, ForgeRecipe> recipes;
    private final Supplier<BuiltIns> builtInSource;
    private final WorkLog log;
    @Nullable
    private volatile BuiltIns builtIns;
    /** Towntopia's recipes by result, to tell an unchanged one (the very object) from a changed or added one. */
    private Map<Item, ForgeRecipe> builtInByResult = Map.of();
    @Nullable
    private volatile View view;

    public ForgeOverlay(WorkTable<ResourceLocation, MaterialDef> materials, WorkTable<ResourceLocation, TierDef> tiers,
            WorkTable<Item, ForgeRecipe> recipes, Supplier<BuiltIns> builtIns, WorkLog log) {
        this.log = log;
        this.materials = materials;
        this.tiers = tiers;
        this.recipes = recipes;
        this.builtInSource = builtIns;
    }

    public WorkTable<ResourceLocation, MaterialDef> materialTable() {
        return this.materials;
    }

    public WorkTable<ResourceLocation, TierDef> tierTable() {
        return this.tiers;
    }

    public WorkTable<Item, ForgeRecipe> recipeTable() {
        return this.recipes;
    }

    /** Reads Towntopia's recipes into the recipe table, once (before data files load). Every reader also calls it. */
    public BuiltIns loadTowntopia() {
        BuiltIns b = this.builtIns;
        if (b != null) return b;
        synchronized (this) {
            if (this.builtIns == null) {
                BuiltIns own = this.builtInSource.get();
                Map<Item, ForgeRecipe> by = new HashMap<>();
                for (ForgeRecipe r : own.recipes()) by.put(r.result(), r);
                this.builtInByResult = by;
                this.recipes.builtIns(List.copyOf(own.recipes()));
                this.builtIns = own;
            }
            return this.builtIns;
        }
    }

    // --- registration (TownKitForge) ------------------------------------------------------------------------------------

    public WorkTable.Handle material(String owner, MaterialDef material) {
        return this.materials.add(owner, material);
    }

    public WorkTable.Handle tier(String owner, TierDef tier) {
        return this.tiers.add(owner, tier);
    }

    /**
     * Removes the material {@code id} (a removal wins over every add), like a {@code "remove"} data file. Towntopia's own
     * materials aren't in the table: removing one changes nothing (logged once as an unknown removal), as in data files.
     */
    public WorkTable.Handle removeMaterial(String owner, ResourceLocation id) {
        return this.materials.remove(owner, id);
    }

    /** Removes the tier {@code id}, like a {@code "remove"} data file (Towntopia's own tiers: as {@link #removeMaterial}). */
    public WorkTable.Handle removeTier(String owner, ResourceLocation id) {
        return this.tiers.remove(owner, id);
    }

    public WorkTable.Handle add(String owner, ForgeRecipe recipe) {
        loadTowntopia();
        return this.recipes.add(owner, recipe);
    }

    public WorkTable.Handle change(String owner, Item result, ForgeRecipeChange change) {
        loadTowntopia();
        return this.recipes.change(owner, result, change::apply);
    }

    public WorkTable.Handle remove(String owner, Item result) {
        loadTowntopia();
        return this.recipes.remove(owner, result);
    }

    // --- reads -----------------------------------------------------------------------------------------------------------

    /** False while nothing is registered in any of the three tables: Towntopia's smith runs on its own objects. */
    public boolean touched() {
        return this.materials.touched() || this.tiers.touched() || this.recipes.touched();
    }

    /** The merged recipes (Towntopia's when nothing is registered); immutable. */
    public List<ForgeRecipe> recipes() {
        loadTowntopia();
        return this.recipes.merged();
    }

    /** The tier {@code item} is rated at: its gear recipe's tier, else Towntopia's own tier of it, else empty. */
    public Optional<ResourceLocation> tierOf(Item item) {
        View v = view();
        int p = v.gearPosition(item);
        if (p >= 0) return Optional.of(v.ladder.get(p).id());
        Integer t = loadTowntopia().gearTiers().get(item);
        return t == null ? Optional.empty() : Optional.of(TOWNTOPIA_TIERS.get(t));
    }

    /** Whether {@code item} has one of Towntopia's own recipes (changed or not). */
    public boolean isTowntopiaRecipe(Item item) {
        loadTowntopia();
        return this.builtInByResult.containsKey(item);
    }

    /** The merged view (cached per table versions; built off the hot path, then lookups only). */
    public View view() {
        loadTowntopia();
        long key = key();
        View v = this.view;
        if (v != null && v.key == key) return v;
        return rebuild();
    }

    private long key() {
        // each table's version only grows; the sum changes whenever one does
        return this.materials.version() * 1_000_003L + this.tiers.version() * 10_007L + this.recipes.version();
    }

    private synchronized View rebuild() {
        long key = key();
        View v = this.view;
        if (v != null && v.key == key) return v;
        BuiltIns own = loadTowntopia();
        List<Rung> ladder = ladder();
        Map<ResourceLocation, Integer> pos = new HashMap<>();
        for (int i = 0; i < ladder.size(); i++) pos.put(ladder.get(i).id(), i);
        int[] towntopiaPos = new int[TOWNTOPIA_TIERS.size()];
        for (int i = 0; i < ladder.size(); i++) {
            if (ladder.get(i).towntopia() >= 0) towntopiaPos[ladder.get(i).towntopia()] = i;
        }

        // materials: Towntopia's eight by ordinal, then the addon ones in the spare slots
        Map<ResourceLocation, Integer> slots = new HashMap<>();
        for (int i = 0; i < TOWNTOPIA_MATERIALS.size(); i++) slots.put(TOWNTOPIA_MATERIALS.get(i), i);
        List<MaterialDef> spares = new ArrayList<>(Collections.nCopies(SPARES, (MaterialDef) null));
        int next = 0;
        for (MaterialDef m : this.materials.merged()) {
            if (slots.containsKey(m.id())) {
                this.log.once("forge-builtin-material|" + m.id(), "[" + MATERIALS + "] " + m.id()
                    + " is one of Towntopia's own materials; they can't be replaced");
                continue;
            }
            if (next >= SPARES) {
                this.log.once("forge-spares|" + m.id(), "[" + MATERIALS + "] more than " + SPARES
                    + " addon materials; " + m.id() + " is skipped");
                continue;
            }
            slots.put(m.id(), TOWNTOPIA_MATERIALS.size() + next);
            spares.set(next++, m);
        }

        // recipes resolved against both
        int size = ladder.size();
        Resolved[][] tools = new Resolved[TOOL_KINDS.size()][size];
        Resolved[][] armor = new Resolved[ARMOR_SLOTS.size()][size];
        List<Resolved> resolved = new ArrayList<>();
        Map<Item, Resolved> byResult = new LinkedHashMap<>();
        for (ForgeRecipe r : this.recipes.merged()) {
            Resolved res = resolve(r, own, pos, towntopiaPos, slots);
            if (res == null) continue;
            if (res.kind() >= 0) place(tools[res.kind()], res, r);
            if (res.armorSlot() >= 0) place(armor[res.armorSlot()], res, r);
            resolved.add(res);
            byResult.put(r.result(), res);
        }
        View built = new View(key, touched(), ladder, spares, resolved, byResult, tools, armor);
        this.view = built;
        return built;
    }

    private void place(Resolved[] at, Resolved res, ForgeRecipe r) {
        Resolved before = at[res.tier()];
        if (before != null) {
            this.log.once("forge-gear-clash|" + before.recipe().result() + "|" + r.result(), "[" + RECIPES + "] " + r.result()
                + " and " + before.recipe().result() + " are both " + r.slot() + " gear of one tier; the smith makes " + r.result());
        }
        at[res.tier()] = res;
    }

    @Nullable
    private Resolved resolve(ForgeRecipe r, BuiltIns own, Map<ResourceLocation, Integer> pos, int[] towntopiaPos,
            Map<ResourceLocation, Integer> slots) {
        int[] cost = new int[TOWNTOPIA_MATERIALS.size() + SPARES];
        for (Map.Entry<ResourceLocation, Integer> c : r.cost().entrySet()) {
            Integer slot = slots.get(c.getKey());
            if (slot == null) {
                this.log.once("forge-unknown-material|" + r.result() + "|" + c.getKey(), "[" + RECIPES + "] the recipe for "
                    + r.result() + " needs the material " + c.getKey() + ", which nobody registered; recipe skipped");
                return null;
            }
            cost[slot] += c.getValue();
        }
        int tier = -1;
        int kind = -1;
        int armorSlot = -1;
        if (r.isGear()) {
            Integer p = pos.get(r.tier());
            if (p == null) {
                this.log.once("forge-unknown-tier|" + r.result() + "|" + r.tier(), "[" + RECIPES + "] the recipe for "
                    + r.result() + " is gear of the tier " + r.tier() + ", which isn't on the ladder; recipe skipped");
                return null;
            }
            tier = p;
            kind = TOOL_KINDS.indexOf(r.slot());
            armorSlot = ARMOR_SLOTS.indexOf(r.slot());
        } else {
            Integer t = own.stockTiers().get(r.result());
            tier = t == null || t < 0 || t >= towntopiaPos.length ? -1 : towntopiaPos[t];
        }
        ForgeRecipe builtIn = this.builtInByResult.get(r.result());
        return new Resolved(r, tier, kind, armorSlot, cost, builtIn == r);
    }

    /** Towntopia's tiers with the addon tiers placed among them. */
    private List<Rung> ladder() {
        Map<ResourceLocation, List<TierDef>> after = new LinkedHashMap<>();
        Set<ResourceLocation> towntopia = new HashSet<>(TOWNTOPIA_TIERS);
        List<TierDef> all = new ArrayList<>();
        for (TierDef t : this.tiers.merged()) {
            if (towntopia.contains(t.id())) {
                this.log.once("forge-builtin-tier|" + t.id(), "[" + TIERS + "] " + t.id()
                    + " is one of Towntopia's own tiers; they can't be replaced");
                continue;
            }
            all.add(t);
            List<TierDef> siblings = after.computeIfAbsent(t.place().after(), k -> new ArrayList<>());
            if (!siblings.isEmpty()) {
                // the brief's "clash log": both stay, ordered by registration (the later one higher)
                TierDef below = siblings.get(siblings.size() - 1);
                this.log.once("forge-tier-clash|" + t.place().after() + "|" + below.id() + "|" + t.id(), "[" + TIERS + "] " + below.id()
                    + " (" + this.tiers.owner(below.id()) + ") and " + t.id() + " (" + this.tiers.owner(t.id()) + ") are both placed after "
                    + t.place().after() + "; " + t.id() + " sits above " + below.id() + " (registration order)");
            }
            siblings.add(t);
        }
        List<ResourceLocation> order = new ArrayList<>();
        Set<ResourceLocation> placed = new HashSet<>();
        for (ResourceLocation v : TOWNTOPIA_TIERS) {
            order.add(v);
            placed.add(v);
            placeAfter(v, after, order, placed);
        }
        Map<ResourceLocation, TierDef> defs = new HashMap<>();
        for (TierDef t : all) {
            defs.put(t.id(), t);
            if (!placed.contains(t.id())) {
                this.log.once("forge-unplaced-tier|" + t.id(), "[" + TIERS + "] " + t.id() + " is placed after "
                    + t.place().after() + ", which isn't on the ladder; tier skipped");
            }
        }
        List<Rung> ladder = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            ResourceLocation id = order.get(i);
            int t = TOWNTOPIA_TIERS.indexOf(id);
            if (t >= 0) {
                ladder.add(new Rung(id, t, t, TOWNTOPIA_SKILL[t]));
                continue;
            }
            int above = TOWNTOPIA_TIERS.size() - 1;
            for (int j = i + 1; j < order.size(); j++) {
                int u = TOWNTOPIA_TIERS.indexOf(order.get(j));
                if (u >= 0) {
                    above = u;
                    break;
                }
            }
            TierPlace place = defs.get(id).place();
            ladder.add(new Rung(id, -1, above, place.skill().orElse(TOWNTOPIA_SKILL[above])));
        }
        return ladder;
    }

    private static void placeAfter(ResourceLocation id, Map<ResourceLocation, List<TierDef>> after, List<ResourceLocation> order,
            Set<ResourceLocation> placed) {
        List<TierDef> children = after.get(id);
        if (children == null) return;
        for (TierDef c : children) {
            if (!placed.add(c.id())) continue;
            order.add(c.id());
            placeAfter(c.id(), after, order, placed);
        }
    }
}
