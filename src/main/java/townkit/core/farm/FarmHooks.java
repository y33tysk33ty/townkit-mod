package townkit.core.farm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import townkit.api.farm.HarvestAction;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.work.WorkLog;

/**
 * The hooks the farm module needs, each attached only while it has something to do, so a world with nothing changed
 * runs Towntopia's farmer with none of them:
 * <ul>
 *   <li>{@link #BONUS_PRODUCT} after, while {@link #FARMER_BONUS_PRODUCE} lists any item: when Towntopia found no item
 *   for the farmer's skill bonus roll, the first drop in the tag is the bonus item.</li>
 *   <li>{@link #FRUIT_FOR} after, {@link #PLAN} after and {@link #CRAFT} before, while {@link SeedTable} has an entry and
 *   Towntopia's seed-crafting helpers were found: the farmer makes seeds from produce for addon crops.</li>
 *   <li>{@link #IS_MATURE}, {@link #PICKABLE} and {@link #FULL_BUSH} after, while a {@link HarvestTable} rule with a ripe
 *   rule of its own holds a block: the rule's answer replaces Towntopia's.</li>
 *   <li>{@link #HARVEST_CROP}, {@link #PICK_BERRIES}, {@link #PICK_GLOW_BERRIES} and {@link #HARVEST_POD} before, while a
 *   rule with a {@code Use} action holds a block: the farmer picks that block by right-click and the plant stays
 *   ({@link UseHarvest}).</li>
 *   <li>{@link #HARVEST_BLOCK} before and after, and {@link #LOSS_POP} before and after, while a rule with a
 *   {@code Break} action holds a block: Towntopia's own harvest of that block runs inside a {@link DropCapture} (opened
 *   before, closed after), so nothing the crop's other parts drop is left on the ground; Towntopia's own "pack full" pops
 *   are not caught.</li>
 *   <li>{@link #AWAY_HARVEST} before, while any rule holds a block (and Towntopia's away steps were found): a {@code Use}
 *   rule's plot is picked (yield, picked state) instead of cut, and a rule block with a same-crop block above is skipped
 *   by the away catch-up ({@link AwayHarvest}).</li>
 * </ul>
 *
 * @since 0.8.0
 */
public final class FarmHooks {
    public static final String BONUS_PRODUCT = "village.villagers.farmer.behavior.FarmerActivity#eligibleBonusProduct(List)";
    public static final String FRUIT_FOR = "village.villagers.farmer.FarmerSeedCrafting#fruitFor(Item)";
    public static final String PLAN = "village.villagers.farmer.FarmerSeedCrafting#plan(ServerLevel,VillageManager,NpcEntity)";
    public static final String CRAFT = "village.villagers.farmer.FarmerSeedCrafting#craft(NpcEntity,VillageManager,Item)";
    public static final String IS_MATURE = "village.villagers.farmer.crops.CropDefinitionRegistry#isMature(BlockState,CropDefinition)";
    public static final String PICKABLE =
        "village.villagers.farmer.FarmerPerennials#pickable(FarmerPerennials.Kind,BlockState,CropDefinition)";
    public static final String FULL_BUSH = "village.villagers.farmer.FarmerPerennials#fullBush(BlockState)";
    public static final String HARVEST_CROP = "village.villagers.farmer.behavior.FarmerActivity#harvestCrop(Activity.Context,FarmerManager.Job)";
    public static final String PICK_BERRIES = "village.villagers.farmer.behavior.FarmerActivity#pickBerries(Activity.Context,BlockPos)";
    public static final String PICK_GLOW_BERRIES = "village.villagers.farmer.behavior.FarmerActivity#pickGlowBerries(Activity.Context,BlockPos)";
    public static final String HARVEST_POD = "village.villagers.farmer.behavior.FarmerActivity#harvestPod(Activity.Context,BlockPos)";
    public static final String HARVEST_BLOCK = "village.villagers.farmer.behavior.FarmerActivity#harvestBlock(Activity.Context,BlockPos,ItemStack)";
    public static final String LOSS_POP = "village.brain.ItemLossLog#pop(Activity.Context,BlockPos,ItemStack,String)";
    public static final String AWAY_HARVEST = "village.economy.away.jobs.FarmerAway$Shift#harvest(AwayFarm.Plot)";
    /** Items in this tag give the farmer's skill bonus roll when they drop from a harvest. */
    public static final TagKey<Item> FARMER_BONUS_PRODUCE =
        TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "farmer_bonus_produce"));

    /** Attached hooks by key and side ("key|after"). */
    private static final Map<String, Hooks.Entry> ATTACHED = new LinkedHashMap<>();

    private FarmHooks() {
    }

    /** Every hook key the farm module names. */
    public static List<String> keys() {
        return List.of(BONUS_PRODUCT, FRUIT_FOR, PLAN, CRAFT, IS_MATURE, PICKABLE, FULL_BUSH, HARVEST_CROP, PICK_BERRIES, PICK_GLOW_BERRIES,
            HARVEST_POD, HARVEST_BLOCK, LOSS_POP, AWAY_HARVEST);
    }

    /** Attaches what the farm module needs now and detaches the rest. Call after a (re)load of data or tags. */
    public static synchronized void refresh() {
        set(BONUS_PRODUCT, true, bonusTagFilled(), FarmHooks::afterBonusProduct);
        boolean seeds = SeedTable.GLOBAL.touched() && SeedCrafting.available();
        set(FRUIT_FOR, true, seeds, SeedCrafting::afterFruitFor);
        set(PLAN, true, seeds, SeedCrafting::afterPlan);
        set(CRAFT, false, seeds, SeedCrafting::beforeCraft);
        boolean ripe = HarvestTable.GLOBAL.hasRipeOverrides();
        set(IS_MATURE, true, ripe, HarvestRipe::afterIsMature);
        set(PICKABLE, true, ripe, HarvestRipe::afterPickable);
        set(FULL_BUSH, true, ripe, HarvestRipe::afterFullBush);
        boolean use = false;
        boolean brk = false;
        for (HarvestTable.Held h : HarvestTable.GLOBAL.held()) {
            if (h.rule().action() instanceof HarvestAction.Use) use = true;
            else brk = true;
        }
        set(HARVEST_CROP, false, use, UseHarvest::beforeHarvestCrop);
        set(PICK_BERRIES, false, use, UseHarvest::beforePick);
        set(PICK_GLOW_BERRIES, false, use, UseHarvest::beforePick);
        set(HARVEST_POD, false, use, UseHarvest::beforePick);
        set(HARVEST_BLOCK, false, brk, UseHarvest::beforeHarvestBlock);
        set(HARVEST_BLOCK, true, brk, UseHarvest::afterHarvestBlock);
        set(LOSS_POP, false, brk, UseHarvest::beforePop);
        set(LOSS_POP, true, brk, UseHarvest::afterPop);
        set(AWAY_HARVEST, false, (use || brk) && AwayHarvest.available(), AwayHarvest::beforeHarvest);
    }

    /** A {@code /reload} or the server's first data load: rules switched off by use-harvest failures are on again. */
    public static void reloaded() {
        UseHarvest.reloaded();
        AwayHarvest.reloaded();
        SeedCrafting.reloaded();
    }

    /** The server is stopping: no break capture may outlive its world. */
    public static void serverStopping() {
        UseHarvest.serverStopping();
    }

    /**
     * After the server started and after every {@code /reload}: reports what only shows once Towntopia's crop definitions
     * and the block tags are loaded. Each line is logged once until the next reload.
     */
    public static void started() {
        if (SeedTable.GLOBAL.touched() && SeedCrafting.available()) SeedCrafting.reportUnplantable();
        HarvestTable.GLOBAL.report();
        HarvestChecks.report();
    }

    /** The hook keys attached right now (for tests). */
    public static synchronized List<String> attachedKeys() {
        List<String> out = new ArrayList<>();
        for (Hooks.Entry e : ATTACHED.values()) out.add(e.hook.key());
        return out;
    }

    /** Whether {@link #FARMER_BONUS_PRODUCE} lists any item now (false before tags are bound). */
    private static boolean bonusTagFilled() {
        try {
            return BuiltInRegistries.ITEM.getTag(FARMER_BONUS_PRODUCE).map(n -> n.size() > 0).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void set(String key, boolean after, boolean wantedNow, HookListener listener) {
        // hooks switched off: no class is woven, so a hook would never be called; none is attached
        boolean wanted = wantedNow && !RewrittenSites.GLOBAL.hooksOff();
        String slot = key + "|" + after;
        Hooks.Entry e = ATTACHED.get(slot);
        if (wanted && e == null) {
            HookInfo h = HookManifest.get().byKey.get(key);
            if (h == null) {
                WorkLog.GLOBAL.once("farm-hook|" + key, "[farm] can't find the hook " + key + "; that part of the farm module is off");
                return;
            }
            ATTACHED.put(slot, Hooks.add(h, TownKit.MODID, listener, after));
        } else if (!wanted && e != null) {
            Hooks.remove(e);
            ATTACHED.remove(slot);
        }
    }

    /** After Towntopia's "which drop gets the skill bonus": no answer yet, so the first tagged drop. */
    private static void afterBonusProduct(HookCall call) {
        if (call.returnValue() != null || !(call.args()[0] instanceof List<?> drops)) return;
        for (Object o : drops) {
            if (o instanceof ItemStack s && !s.isEmpty() && s.is(FARMER_BONUS_PRODUCE)) {
                call.setReturnValue(s.getItem());
                return;
            }
        }
    }
}
