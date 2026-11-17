package townkit.core.work.forge;

import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.villagers.blacksmith.BlacksmithBalance;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import com.alex.test.village.villagers.miner.MinerBalance;
import com.alex.test.village.villagers.guard.GuardSwordUtils;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.alex.test.village.villagers.tools.ToolSpeed;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.api.work.GearSlot;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.work.RewrittenWorkSites;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkSite;
import townkit.core.work.WorkSites;

/**
 * Switches the forge overlay on for Towntopia's smith, and the hooks it needs beside the class-load rewrites
 * ({@link ForgeBridges}). The overlay is on only while something is registered ({@link ForgeOverlay#touched}) and
 * Towntopia's smith is in {@linkplain #reach() reach} (every smith REWRITE row of {@code work-sites.tsv} rewritten in
 * every smith class, the spare materials in place, the recipe hooks attached); otherwise every bridge answers
 * Towntopia's own value and none of these hooks is attached, so an untouched world runs Towntopia's smith as shipped.
 * <ul>
 *   <li>{@link #TOOL_RECIPE}, {@link #ARMOR_RECIPE}, {@link #RECIPE_FOR} before: the merged recipe at a ladder position
 *   (or for an item), as a Towntopia recipe object; while a plan runs, a recipe the smith can't make (an addon tier
 *   above its level, a smithing-table recipe without a table) answers null.</li>
 *   <li>{@link #PLANNER_INIT} after and {@link #PLANNER_RUN} after: the running plan's smith level and smithing table.</li>
 *   <li>{@link #ORDER_INFO} after: the smith level a Mayor order needs, for the merged recipe.</li>
 *   <li>{@link #IS_FORGE_ITEM}, {@link #FORGE_ITEMS} after: Mayor stock orders accept addon recipes' results and
 *   refuse removed Towntopia recipes.</li>
 * </ul>
 * While addon gear exists: the tool-choice hooks ({@link #ALLOWED}) let villagers pick it up (allowed while their
 * settings allow the Towntopia tier just above it), and the ratings villagers choose tools by put it strictly between its
 * Towntopia neighbours instead of rating it as an unknown (wood) tool: {@link #QUALITY} and {@link #SWORD_QUALITY}
 * (longs), {@link #SPEED_OF} (axes, hoes), {@link #TOOL_SPEED} / {@link #BASE_SWINGS} (pickaxes); {@link #RANK} (an int,
 * used for thresholds such as "fells big trees") gives the Towntopia tier just below.
 */
public final class ForgeHooks {
    static final String TOOL_RECIPE = "village.villagers.blacksmith.behavior.BlacksmithActivity#toolRecipe(ForgeOrderBook.ToolKind,int)";
    static final String ARMOR_RECIPE = "village.villagers.blacksmith.behavior.BlacksmithActivity#armorRecipe(int,int)";
    static final String RECIPE_FOR = "village.villagers.blacksmith.behavior.BlacksmithActivity#recipeFor(Item)";
    static final String ORDER_INFO = "village.villagers.blacksmith.behavior.BlacksmithActivity#orderInfo(Item)";
    static final String IS_FORGE_ITEM = "village.economy.StockReserves#isForgeItem(Item)";
    static final String FORGE_ITEMS = "village.economy.StockReserves#forgeItems()";
    static final String PLANNER_INIT = "village.villagers.blacksmith.behavior.BlacksmithActivity$Planner#<init>"
        + "(Activity.Context,float,ForgeBuilding,boolean,BlacksmithActivity.AwayOrderBook)";
    static final String PLANNER_RUN = "village.villagers.blacksmith.behavior.BlacksmithActivity$Planner#run()";
    static final String PICKAXES = "village.villagers.miner.behavior.MinerActivity#allowedPickaxes(Activity.Context)";
    static final String LUMBER_AXES = "village.villagers.lumberjack.settings.LumberjackSettings#getAllowedAxes()";
    static final String FARMER_AXES = "village.villagers.farmer.settings.FarmerSettings#getAllowedAxes()";
    static final String FARMER_HOES = "village.villagers.farmer.settings.FarmerSettings#getAllowedHoes()";
    static final String GUARD_SWORDS = "village.villagers.guard.settings.GuardSettings#getAllowedSwords()";
    static final String ARMOR_FOR = "village.villagers.guard.GuardArmorUtils#allowedArmorFor(EquipmentSlot)";
    static final String RANK = "village.villagers.tools.ToolSelectionHelper#rank(Item)";
    static final String TOOL_SPEED = "village.villagers.miner.MinerBalance#toolSpeed(Item)";
    static final String BASE_SWINGS = "village.villagers.miner.MinerBalance#baseSwings(Item)";
    static final String QUALITY = "village.villagers.tools.ToolSelectionHelper#quality(ItemStack)";
    static final String SWORD_QUALITY = "village.villagers.guard.GuardSwordUtils#quality(ItemStack)";
    static final String SPEED_OF = "village.villagers.tools.ToolSpeed#of(Item,double)";
    /** The allowed-tool lists and the gear slot each one lists. */
    static final Map<String, GearSlot> ALLOWED = Map.of(PICKAXES, GearSlot.PICKAXE, LUMBER_AXES, GearSlot.AXE, FARMER_AXES, GearSlot.AXE,
        FARMER_HOES, GearSlot.HOE, GUARD_SWORDS, GearSlot.SWORD);
    private static final String BRIDGES = "townkit/core/work/forge/ForgeBridges";
    private static final String SMELT_BRIDGES = "townkit/core/work/forge/SmeltBridges";

    /** Addon gear as Towntopia rates it: the Towntopia items around its tier. */
    /**
     * Addon gear as Towntopia rates it: between the Towntopia items of its slot just below ({@code floor}) and just above
     * ({@code ceil}, also the item whose "allowed" switch it follows) its tier, {@code frac} of the way up; above
     * netherite ({@code over} steps past it) just above the netherite item.
     */
    record Gear(GearSlot slot, @Nullable Item governing, int rank, float speed, int swings, @Nullable ItemStack floor,
            @Nullable ItemStack ceil, double frac, int over) {
        /** A Towntopia rating of this gear: strictly between the floor's and the ceiling's (above it past netherite). */
        long between(long low, long high) {
            if (this.over > 0) return low + 1000L * this.over;
            // no room between the neighbours (equal or one apart): rate it as the floor, never at or above the ceiling
            if (high - low < 2) return low;
            return low + Math.max(1L, Math.min(high - low - 1, Math.round((high - low) * this.frac)));
        }

        double between(double low, double high) {
            if (this.over > 0) return low + 0.01 * this.over;
            if (high <= low) return low;
            return low + (high - low) * this.frac;
        }
    }

    /** Everything the bridges and hooks answer from while the overlay is on; built once per merged view, immutable. */
    static final class Active {
        final ForgeOverlay.View view;
        /** Items of each spare material slot (empty lists for empty slots). */
        final List<List<Item>> spareItems;
        /** Every spare material item (storage counts). */
        final List<Item> storedItems;
        final Map<ForgeOverlay.Resolved, Object> objects;
        @Nullable
        final Object shield;
        @Nullable
        final Object shears;
        @Nullable
        final Object bucket;
        @Nullable
        final Object templateCopy;
        @Nullable
        final Item templateItem;
        /** The smith level Towntopia's tier int needs (Towntopia's own orderLevel). */
        final int[] towntopiaLevels;
        final Set<Item> removedForge;
        final List<Item> addedForge;
        final Map<Item, Gear> addonGear;

        Active(ForgeOverlay.View view, ForgeOverlay overlay) {
            this.view = view;
            List<List<Item>> spares = new ArrayList<>();
            Set<Item> stored = new LinkedHashSet<>();
            for (ForgeOverlay.MaterialDef m : view.spares) {
                List<Item> items = m == null ? List.of() : items(m);
                spares.add(items);
                stored.addAll(items);
            }
            this.spareItems = List.copyOf(spares);
            this.storedItems = List.copyOf(stored);
            int mats = ForgeOverlay.TOWNTOPIA_MATERIALS.size() + ForgeOverlay.SPARES;
            Map<ForgeOverlay.Resolved, Object> objects = new IdentityHashMap<>();
            for (ForgeOverlay.Resolved r : view.recipes) {
                Object own = view.identity() && r.builtIn() ? TowntopiaSmith.original(r.recipe().result()) : null;
                objects.put(r, own != null ? own : TowntopiaSmith.recipe(r, mats));
            }
            this.objects = Collections.unmodifiableMap(objects);
            this.shield = stock(view, "SHIELD");
            this.shears = stock(view, "SHEARS");
            this.bucket = stock(view, "BUCKET");
            this.templateCopy = stock(view, "TEMPLATE_COPY");
            ForgeOverlay.BuiltIns own = overlay.loadTowntopia();
            Item template = null;
            for (Map.Entry<Item, Integer> e : own.stockTiers().entrySet()) {
                if (e.getValue() < 0) template = e.getKey();
            }
            this.templateItem = template;
            this.towntopiaLevels = new int[ForgeOverlay.TOWNTOPIA_TIERS.size()];
            for (int t = 0; t < this.towntopiaLevels.length; t++) {
                int level = BlacksmithBalance.MAX_LEVEL;
                for (int l = 1; l <= BlacksmithBalance.MAX_LEVEL; l++) {
                    if (BlacksmithBalance.maxTier(l) >= t) {
                        level = l;
                        break;
                    }
                }
                this.towntopiaLevels[t] = level;
            }
            Set<Item> removed = new HashSet<>();
            for (net.minecraft.world.item.Item i : towntopiaResults(own)) {
                if (view.recipe(i) == null) removed.add(i);
            }
            this.removedForge = Set.copyOf(removed);
            List<Item> added = new ArrayList<>();
            Map<Item, Gear> gear = new HashMap<>();
            for (ForgeOverlay.Resolved r : view.recipes) {
                Item result = r.recipe().result();
                if (overlay.isTowntopiaRecipe(result)) continue;
                added.add(result);
                if (r.recipe().isGear()) gear.put(result, gear(view, r));
            }
            this.addedForge = List.copyOf(added);
            this.addonGear = Map.copyOf(gear);
        }

        private static List<Item> towntopiaResults(ForgeOverlay.BuiltIns own) {
            List<Item> out = new ArrayList<>();
            for (var r : own.recipes()) out.add(r.result());
            return out;
        }

        @Nullable
        private Object stock(ForgeOverlay.View view, String field) {
            Object original = TowntopiaSmith.stockRecipe(field);
            for (Map.Entry<ForgeOverlay.Resolved, Object> e : this.objects.entrySet()) {
                if (TowntopiaSmith.original(e.getKey().recipe().result()) == original) return e.getValue();
            }
            return null;
        }

        /** The Towntopia items below and above addon gear {@code r}, and the ratings between them. */
        private static Gear gear(ForgeOverlay.View view, ForgeOverlay.Resolved r) {
            GearSlot slot = r.recipe().slot();
            ForgeOverlay.Rung rung = view.rung(r.tier());
            int above = rung == null ? 4 : rung.above();
            Item governing = null;
            for (int t = above; t < ForgeOverlay.TOWNTOPIA_TIERS.size() && governing == null; t++) governing = TowntopiaSmith.vanillaGear(slot, t);
            if (governing == null) governing = TowntopiaSmith.vanillaGear(slot, 4);
            int floorTier = 0;
            for (int t = ForgeOverlay.TOWNTOPIA_TIERS.size() - 1; t >= 0; t--) {
                if (view.position(t) <= r.tier() && TowntopiaSmith.vanillaGear(slot, t) != null) {
                    floorTier = t;
                    break;
                }
            }
            Item floor = TowntopiaSmith.vanillaGear(slot, floorTier);
            int from = view.position(floorTier);
            int to = governing != null && above > floorTier ? view.position(above) : from;
            double frac = to > from ? Math.max(0.0, Math.min(1.0, (double) (r.tier() - from) / (to - from))) : 0.0;
            int over = to > from ? 0 : Math.max(0, r.tier() - from);
            int rank = 0;
            float speed = 2.0F;
            int swings = 5;
            if (floor != null && !slot.isArmor()) {
                rank = ToolSelectionHelper.rank(floor);
                swings = MinerBalance.baseSwings(floor);
                float low = MinerBalance.toolSpeed(floor);
                float high = governing != null ? MinerBalance.toolSpeed(governing) : low;
                speed = over > 0 ? low + 0.5F * over : low + (high - low) * (float) frac;
            }
            return new Gear(slot, governing, rank, speed, swings, floor == null ? null : new ItemStack(floor),
                governing == null ? null : new ItemStack(governing), frac, over);
        }

        @Nullable
        Object object(@Nullable ForgeOverlay.Resolved r) {
            return r == null ? null : this.objects.get(r);
        }
    }

    /** The plan running on this thread: its smith's level and whether its forge has a smithing table. */
    private record Planning(int level, boolean table) {
    }

    private static final ThreadLocal<Planning> PLANNING = new ThreadLocal<>();
    /** Attached hooks by key and side ("key|after"). */
    private static final Map<String, Hooks.Entry> ATTACHED = new LinkedHashMap<>();
    /** Whether Towntopia's smith is in reach; null until known (needs a running server). */
    @Nullable
    private static Boolean reached;
    /** Whether Towntopia's smelting is in reach; null until known. */
    @Nullable
    private static Boolean smeltReached;

    private ForgeHooks() {
    }

    /**
     * Switches the overlay on or off for Towntopia's smith after a change of the forge tables (or tags). On a running
     * server it switches on the server thread, between ticks, so a plan never sees two ladders.
     */
    public static void refresh() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) {
            server.execute(ForgeHooks::refresh);
            return;
        }
        apply();
    }

    private static synchronized void apply() {
        ForgeOverlay o = ForgeOverlay.GLOBAL;
        Active next = null;
        if (o.touched() && reach()) {
            try {
                next = new Active(o.view(), o);
            } catch (RuntimeException e) {
                WorkLog.GLOBAL.once("forge-active|" + e, "[" + ForgeOverlay.RECIPES + "] the forge overlay failed (" + e
                    + "); Towntopia's smith keeps its own lists");
                TownKit.LOG.warn("[TownKit] forge overlay failed", e);
            }
        }
        boolean on = next != null;
        boolean ok = setCore(on);
        if (on && !ok) {
            WorkLog.GLOBAL.once("forge-hooks", "[" + ForgeOverlay.RECIPES + "] a hook the forge overlay needs is missing; "
                + "Towntopia's smith keeps its own lists");
            on = false;
            next = null;
            setCore(false);
        }
        boolean gear = on && !next.addonGear.isEmpty();
        for (Map.Entry<String, GearSlot> e : ALLOWED.entrySet()) {
            GearSlot slot = e.getValue();
            set(e.getKey(), true, gear, call -> afterAllowed(call, slot));
        }
        set(ARMOR_FOR, true, gear, ForgeHooks::afterAllowedArmor);
        set(RANK, true, gear, ForgeHooks::afterRank);
        set(TOOL_SPEED, true, gear, ForgeHooks::afterToolSpeed);
        set(BASE_SWINGS, true, gear, ForgeHooks::afterBaseSwings);
        set(QUALITY, true, gear, call -> afterQuality(call, ToolSelectionHelper::quality));
        set(SWORD_QUALITY, true, gear, call -> afterQuality(call, GuardSwordUtils::quality));
        set(SPEED_OF, true, gear, ForgeHooks::afterSpeedOf);
        ForgeBridges.active = next;
        applySmelt();
    }

    /**
     * Switches the smelting overlay on for Towntopia's smith while something is registered and every smelting REWRITE row
     * ({@link SmeltBridges}) was rewritten; otherwise the bridges answer Towntopia's own values. It needs no hooks.
     */
    private static void applySmelt() {
        SmeltOverlay o = SmeltOverlay.GLOBAL;
        SmeltOverlay.View next = null;
        if (o.touched() && reachSmelt()) {
            try {
                next = o.view();
            } catch (RuntimeException e) {
                WorkLog.GLOBAL.once("smelt-active|" + e, "[" + SmeltOverlay.KIND + "] the smelting overlay failed (" + e
                    + "); the smith smelts Towntopia's three raws only");
                TownKit.LOG.warn("[TownKit] smelting overlay failed", e);
            }
        }
        SmeltBridges.active = next;
    }

    /** Whether the smelting overlay is on for Towntopia's smith right now. */
    public static boolean smeltOn() {
        return SmeltBridges.active != null;
    }

    /** Attaches (or detaches) the hooks the overlay can't run without; false when one of them can't be found. */
    private static boolean setCore(boolean on) {
        boolean ok = set(TOOL_RECIPE, false, on, ForgeHooks::beforeToolRecipe);
        ok &= set(ARMOR_RECIPE, false, on, ForgeHooks::beforeArmorRecipe);
        ok &= set(RECIPE_FOR, false, on, ForgeHooks::beforeRecipeFor);
        ok &= set(PLANNER_INIT, true, on, ForgeHooks::afterPlannerInit);
        ok &= set(PLANNER_RUN, true, on, call -> PLANNING.remove());
        ok &= set(ORDER_INFO, true, on, ForgeHooks::afterOrderInfo);
        ok &= set(IS_FORGE_ITEM, true, on, ForgeHooks::afterIsForgeItem);
        ok &= set(FORGE_ITEMS, true, on, ForgeHooks::afterForgeItems);
        return ok;
    }

    /** Whether the overlay is on for Towntopia's smith right now. */
    public static boolean on() {
        return ForgeBridges.active != null;
    }

    /** The hook keys attached right now (for tests). */
    public static synchronized List<String> attachedKeys() {
        List<String> out = new ArrayList<>();
        for (Hooks.Entry e : ATTACHED.values()) out.add(e.hook.key());
        return out;
    }

    private static boolean set(String key, boolean after, boolean wanted, @Nullable HookListener listener) {
        String slot = key + "|" + after;
        Hooks.Entry e = ATTACHED.get(slot);
        if (wanted && e == null) {
            HookInfo h = HookManifest.get().byKey.get(key);
            if (h == null) {
                WorkLog.GLOBAL.once("forge-hook|" + key, "[" + ForgeOverlay.RECIPES + "] can't find the hook " + key);
                return false;
            }
            ATTACHED.put(slot, Hooks.add(h, TownKit.MODID, listener, after));
        } else if (!wanted && e != null) {
            Hooks.remove(e);
            ATTACHED.remove(slot);
        }
        return true;
    }

    /**
     * Whether TownKit reaches all of Towntopia's smith: every smith REWRITE row of {@code work-sites.tsv} was rewritten
     * (its classes are loaded for that, without initialising them) and the material enum has its spare constants. A
     * partial rewrite is never used: the ladder would disagree between classes. Known once a server runs; logged once
     * when it isn't.
     */
    static synchronized boolean reach() {
        if (reached != null) return reached;
        if (ServerLifecycleHooks.getCurrentServer() == null) return false;
        List<String> missing = new ArrayList<>();
        if (RewrittenSites.GLOBAL.hooksOff()) {
            missing.add("TownKit's hooks are off");
        } else {
            try {
                missing.addAll(missingRows(ForgeHooks::forgeRow));
                int mats = TowntopiaSmith.materialCount();
                if (mats != ForgeOverlay.TOWNTOPIA_MATERIALS.size() + ForgeOverlay.SPARES) missing.add("material enum has " + mats + " constants");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                missing.add(e.toString());
            }
        }
        reached = missing.isEmpty();
        if (!reached) {
            TownKit.LOG.warn("[TownKit] forge: Towntopia's smith is out of TownKit's reach ({}); addon materials, tiers and forge "
                + "recipe changes stay off for it", missing.size() > 5 ? missing.subList(0, 5) + " and " + (missing.size() - 5) + " more" : missing);
        }
        return reached;
    }

    private static boolean forgeRow(WorkSites sites, int id) {
        return sites.kind(id).style == WorkSite.Style.ENUM_EXTEND || sites.bridgeOwner(id).equals(BRIDGES);
    }

    private static boolean smeltRow(WorkSites sites, int id) {
        return sites.bridgeOwner(id).equals(SMELT_BRIDGES);
    }

    /**
     * Whether TownKit reaches Towntopia's smelting: every REWRITE row naming {@link SmeltBridges} was rewritten. Known
     * once a server runs; logged once when it isn't.
     */
    static synchronized boolean reachSmelt() {
        if (smeltReached != null) return smeltReached;
        if (ServerLifecycleHooks.getCurrentServer() == null) return false;
        List<String> missing = new ArrayList<>();
        if (RewrittenSites.GLOBAL.hooksOff()) {
            missing.add("TownKit's hooks are off");
        } else {
            try {
                missing.addAll(missingRows(ForgeHooks::smeltRow));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                missing.add(e.toString());
            }
        }
        smeltReached = missing.isEmpty();
        if (!smeltReached) {
            TownKit.LOG.warn("[TownKit] smelting: Towntopia's smith is out of TownKit's reach ({}); addon raws stay unsmelted by it",
                missing.size() > 5 ? missing.subList(0, 5) + " and " + (missing.size() - 5) + " more" : missing);
        }
        return smeltReached;
    }

    /** The REWRITE rows {@code wanted} picks that weren't rewritten (their classes are loaded for that, not initialised). */
    public static List<String> missingRows(java.util.function.BiPredicate<WorkSites, Integer> wanted) throws ReflectiveOperationException {
        List<String> missing = new ArrayList<>();
        WorkSites sites = WorkSites.load();
        Set<String> owners = new LinkedHashSet<>();
        for (int i = 0; i < sites.size(); i++) {
            if (sites.rewrite(i) && wanted.test(sites, i)) owners.add(sites.owner(i));
        }
        ClassLoader loader = BlacksmithActivity.class.getClassLoader();
        for (String owner : owners) {
            Class.forName("com.alex.test." + owner.replace('/', '.'), false, loader);
            for (String key : RewrittenWorkSites.GLOBAL.missing(sites, owner)) {
                int id = sites.siteId(key);
                if (id >= 0 && wanted.test(sites, id)) missing.add(key);
            }
        }
        return missing;
    }

    /** The items of material {@code m} right now (a tag's current contents). */
    static List<Item> items(ForgeOverlay.MaterialDef m) {
        if (m.item() != null) return List.of(m.item());
        List<Item> out = new ArrayList<>();
        BuiltInRegistries.ITEM.getTag(m.tag()).ifPresent(set -> {
            for (Holder<Item> h : set) out.add(h.value());
        });
        return List.copyOf(out);
    }

    // --- recipes -------------------------------------------------------------------------------------------------------

    /** While a plan runs: whether its smith can make {@code r} (its level for an addon tier, a table for smithing). */
    private static boolean makeable(Active a, @Nullable ForgeOverlay.Resolved r) {
        if (r == null) return false;
        Planning p = PLANNING.get();
        if (p == null) return true;
        if (r.recipe().smithing() && !p.table()) return false;
        ForgeOverlay.Rung rung = a.view.rung(r.tier());
        return rung == null || rung.towntopia() >= 0 || p.level() >= rung.skill();
    }

    private static void beforeToolRecipe(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || TowntopiaSmith.loading || !(call.args()[0] instanceof Enum<?> kind) || !(call.args()[1] instanceof Integer tier)) return;
        ForgeOverlay.Resolved r = a.view.tool(kind.ordinal(), tier);
        call.cancelWith(makeable(a, r) ? a.object(r) : null);
    }

    private static void beforeArmorRecipe(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || TowntopiaSmith.loading || !(call.args()[0] instanceof Integer slot) || !(call.args()[1] instanceof Integer tier)) return;
        ForgeOverlay.Resolved r = a.view.armor(slot, tier);
        call.cancelWith(makeable(a, r) ? a.object(r) : null);
    }

    private static void beforeRecipeFor(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || TowntopiaSmith.loading || !(call.args()[0] instanceof Item item)) return;
        ForgeOverlay.Resolved r = a.view.recipe(item);
        call.cancelWith(makeable(a, r) ? a.object(r) : null);
    }

    private static void afterPlannerInit(HookCall call) {
        PLANNING.remove(); // a plan whose run() threw left its value: never carry it into this one
        if (ForgeBridges.active == null || call.self() == null) return;
        ForgeBuilding forge = call.args()[2] instanceof ForgeBuilding f ? f : null;
        PLANNING.set(new Planning(TowntopiaSmith.plannerLevel(call.self()), forge != null && forge.getSmithingTablePos() != null));
    }

    /** The smith level a Mayor order of the merged recipe needs, and whether it is made at the smithing table. */
    private static void afterOrderInfo(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || !(call.returnValue() instanceof BlacksmithActivity.OrderInfo info) || !(call.args()[0] instanceof Item item)) return;
        ForgeOverlay.Resolved r = a.view.recipe(item);
        if (r == null) return;
        int level;
        if (item == a.templateItem) {
            level = BlacksmithBalance.MAX_LEVEL;
        } else {
            ForgeOverlay.Rung rung = a.view.rung(r.tier());
            level = rung == null ? 1 : rung.towntopia() >= 0 ? a.towntopiaLevels[rung.towntopia()] : rung.skill();
        }
        if (level != info.level() || r.recipe().smithing() != info.smithingTable()) {
            call.setReturnValue(new BlacksmithActivity.OrderInfo(info.count(), info.materials(), level, r.recipe().smithing()));
        }
    }

    private static void afterIsForgeItem(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || !(call.args()[0] instanceof Item item)) return;
        if (a.view.recipe(item) != null) call.setReturnValue(true);
        else if (a.removedForge.contains(item)) call.setReturnValue(false);
    }

    @SuppressWarnings("unchecked")
    private static void afterForgeItems(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || (a.addedForge.isEmpty() && a.removedForge.isEmpty()) || !(call.returnValue() instanceof List<?> list)) return;
        List<Item> out = new ArrayList<>((List<Item>) list);
        out.removeAll(a.removedForge);
        for (Item i : a.addedForge) {
            if (!out.contains(i)) out.add(i);
        }
        call.setReturnValue(out);
    }

    // --- addon gear in villagers' hands ---------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void afterAllowed(HookCall call, GearSlot slot) {
        Active a = ForgeBridges.active;
        if (a == null || !(call.returnValue() instanceof Set<?> set)) return;
        Set<Item> allowed = (Set<Item>) set;
        Set<Item> out = null;
        for (Map.Entry<Item, Gear> e : a.addonGear.entrySet()) {
            Gear g = e.getValue();
            if (g.slot() != slot || g.governing() == null || !allowed.contains(g.governing()) || allowed.contains(e.getKey())) continue;
            if (out == null) out = new LinkedHashSet<>(allowed);
            out.add(e.getKey());
        }
        if (out != null) call.setReturnValue(out);
    }

    private static void afterAllowedArmor(HookCall call) {
        if (!(call.args()[0] instanceof EquipmentSlot slot)) return;
        GearSlot gear = switch (slot) {
            case HEAD -> GearSlot.HELMET;
            case CHEST -> GearSlot.CHESTPLATE;
            case LEGS -> GearSlot.LEGGINGS;
            case FEET -> GearSlot.BOOTS;
            default -> null;
        };
        if (gear != null) afterAllowed(call, gear);
    }

    /**
     * A Towntopia tool rating (a long: {@code ToolSelectionHelper.quality} for storage, butchers and archers,
     * {@code GuardSwordUtils.quality} for guards) of addon gear: strictly between Towntopia's rating of the plain items of
     * its slot just below and above its tier, plus the gear's own enchantment points. Asked of the plain Towntopia items
     * (this hook then does nothing for them), so other addons' hooks on these ratings still count.
     */
    private static void afterQuality(HookCall call, ToLongFunction<ItemStack> towntopia) {
        Active a = ForgeBridges.active;
        if (a == null || !(call.args()[0] instanceof ItemStack stack) || stack.isEmpty()) return;
        Gear g = a.addonGear.get(stack.getItem());
        if (g == null || g.slot().isArmor() || g.floor() == null) return;
        long low = towntopia.applyAsLong(g.floor());
        long high = g.ceil() != null ? towntopia.applyAsLong(g.ceil()) : low;
        call.setReturnValue(g.between(low, high) + ToolSelectionHelper.enchantPoints(stack));
    }

    /** {@code ToolSpeed.of(item, efficiency)} (lumberjacks' and farmers' axes and hoes): between its Towntopia neighbours. */
    private static void afterSpeedOf(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || !(call.args()[0] instanceof Item item) || !(call.args()[1] instanceof Double efficiency)) return;
        Gear g = a.addonGear.get(item);
        if (g == null || g.slot().isArmor() || g.floor() == null) return;
        double low = ToolSpeed.of(g.floor().getItem(), efficiency);
        double high = g.ceil() != null ? ToolSpeed.of(g.ceil().getItem(), efficiency) : low;
        call.setReturnValue(Math.min(ToolSpeed.MAX_SPEED, g.between(low, high)));
    }

    private static void afterRank(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || !(call.args()[0] instanceof Item item)) return;
        Gear g = a.addonGear.get(item);
        if (g != null && !g.slot().isArmor() && Integer.valueOf(0).equals(call.returnValue())) call.setReturnValue(g.rank());
    }

    private static void afterToolSpeed(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || !(call.args()[0] instanceof Item item)) return;
        Gear g = a.addonGear.get(item);
        if (g != null && g.slot() == GearSlot.PICKAXE) call.setReturnValue(g.speed());
    }

    private static void afterBaseSwings(HookCall call) {
        Active a = ForgeBridges.active;
        if (a == null || !(call.args()[0] instanceof Item item)) return;
        Gear g = a.addonGear.get(item);
        if (g != null && g.slot() == GearSlot.PICKAXE) call.setReturnValue(g.swings());
    }
}
