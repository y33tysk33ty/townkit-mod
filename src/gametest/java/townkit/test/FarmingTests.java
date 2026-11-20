package townkit.test;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.farmer.FarmerManager;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;
import townkit.api.hooks.HookRegistration;
import townkit.api.hooks.TownKitHooks;
import townkit.core.farm.DropCapture;
import townkit.core.farm.UseHarvest;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.farmer.FarmerSeedCrafting;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.alex.test.village.villagers.farmer.behavior.FarmerActivity;
import townkit.api.Subscription;
import townkit.api.farm.AwayYield;
import townkit.api.farm.HarvestAction;
import townkit.api.farm.HarvestRule;
import townkit.api.farm.Holding;
import townkit.api.farm.Ripe;
import townkit.api.farm.SeedSource;
import townkit.api.farm.TownKitFarming;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.farm.FarmHooks;
import townkit.test.BehaviourTests.TestVillage;

/**
 * Contract tests for the farm module. They call Towntopia's own (private) bonus-roll choice,
 * {@code FarmerActivity.eligibleBonusProduct}, the same way the farmer's harvest does: the hook runs inside it, so
 * what comes back is what the harvest would roll the farmer's skill bonus on. The seed tests (W3) call Towntopia's own
 * {@code FarmerSeedCrafting.fruitFor/plan/craft} the way the farmer's activity does: the hooks run inside them.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class FarmingTests {
    private FarmingTests() {
    }

    @Nullable
    private static Item bonusFor(List<ItemStack> drops) throws ReflectiveOperationException {
        Method m = FarmerActivity.class.getDeclaredMethod("eligibleBonusProduct", List.class);
        m.setAccessible(true);
        try {
            return (Item) m.invoke(null, drops);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    /** A drop in {@code #townkit:farmer_bonus_produce} (test data) is the bonus item; a Towntopia crop still is. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_farm_bonus")
    public static void bonusTagGivesBonusRoll(GameTestHelper helper) throws ReflectiveOperationException {
        Checks c = new Checks("bonusTagGivesBonusRoll");
        c.check("Towntopia's own crop is still chosen", bonusFor(List.of(new ItemStack(Items.WHEAT))) == Items.WHEAT, "");
        c.check("no drop, no bonus", bonusFor(List.of()) == null, "");
        if (RewrittenSites.GLOBAL.hooksOff()) {
            c.check("hooks off: the tagged drop gets no bonus", bonusFor(List.of(new ItemStack(TestBlocks.TEST_PRODUCE.get()))) == null, "");
            c.check("hooks off: the bonus hook is not attached", !FarmHooks.attachedKeys().contains(FarmHooks.BONUS_PRODUCT), "");
        } else {
            c.check("the bonus hook is attached", FarmHooks.attachedKeys().contains(FarmHooks.BONUS_PRODUCT), FarmHooks.attachedKeys());
            c.check("the tagged drop is the bonus item",
                bonusFor(List.of(new ItemStack(TestBlocks.TEST_PRODUCE.get()))) == TestBlocks.TEST_PRODUCE.get(), "");
            c.check("the first tagged drop is chosen among others",
                bonusFor(List.of(new ItemStack(TestBlocks.TEST_PRODUCE_PLAIN.get()), new ItemStack(TestBlocks.TEST_PRODUCE.get())))
                    == TestBlocks.TEST_PRODUCE.get(), "");
            c.check("Towntopia's answer wins over the tag",
                bonusFor(List.of(new ItemStack(Items.CARROT), new ItemStack(TestBlocks.TEST_PRODUCE.get()))) == Items.CARROT, "");
        }
        c.finish(helper);
    }

    /** A drop that isn't in the tag gets no bonus. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_farm_bonus")
    public static void untaggedNoBonus(GameTestHelper helper) throws ReflectiveOperationException {
        Checks c = new Checks("untaggedNoBonus");
        c.check("an untagged drop gets no bonus", bonusFor(List.of(new ItemStack(TestBlocks.TEST_PRODUCE_PLAIN.get()))) == null, "");
        c.finish(helper);
    }

    // --- W3: seeds from produce ----------------------------------------------------------------------------------------

    private static final String SEED_OWNER = "townkit-test-seeds";

    private static boolean seedHooksAttached() {
        List<String> keys = FarmHooks.attachedKeys();
        return keys.contains(FarmHooks.FRUIT_FOR) || keys.contains(FarmHooks.PLAN) || keys.contains(FarmHooks.CRAFT);
    }

    private static ItemStack bound(Item item, int count, VillageManager mgr) {
        ItemStack s = new ItemStack(item, count);
        VillageTokenBinding.bind(s, mgr);
        return s;
    }

    private static int carried(NpcEntity npc, Item item) {
        int n = 0;
        for (ItemStack s : npc.getInventory().getSlots()) if (s.is(item)) n += s.getCount();
        return n;
    }

    /** A village with a farmer, a storage room and a farm (empty tilled plots beside water), at ({@code x}, {@code z}). */
    @Nullable
    private static TestVillage seedVillage(GameTestHelper helper, int x, int z) {
        TestVillage tv = BehaviourTests.village(helper, x, z).orElse(null);
        if (tv == null) return null;
        BehaviourTests.pinDayTime(helper, 6000);
        ServerLevel level = helper.getLevel();
        int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        ScenarioBuilder.buildFarm(level, tv.mgr(), x + 12, feetY, z - 22);
        return tv;
    }

    private static void seedEnd(GameTestHelper helper, Checks c, TestVillage tv, List<Subscription> subs) {
        subs.forEach(Subscription::remove);
        BehaviourTests.forget(tv);
        BehaviourTests.finish(helper, c);
    }

    private static int needing(ServerLevel level, TestVillage tv, Item seed) {
        return tv.mgr().farmers().plotsNeeding(level, tv.mgr(), FarmerSettings.of(tv.farmer()), seed);
    }

    /**
     * The corn field has no kernels anywhere and corn waits in storage: the farmer's seed plan asks for the corn, then,
     * carrying it, makes 16 kernels from 8 corn (2 per corn), and with kernels in her pack plans nothing more.
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "townkit_farm_seed_corn")
    public static void cornMakesKernels(GameTestHelper helper) {
        Checks c = new Checks("cornMakesKernels");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = seedVillage(helper, 19200, 9600);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Item kernels = TestBlocks.TEST_KERNELS.get();
        Item corn = TestBlocks.TEST_CORN.get();
        subs.add(TownKitFarming.seedFrom(SEED_OWNER, kernels, corn, 2));
        if (RewrittenSites.GLOBAL.hooksOff()) {
            c.check("hooks off: the seed hooks are not attached", !seedHooksAttached(), FarmHooks.attachedKeys());
            c.check("hooks off: Towntopia has no produce for the kernels", FarmerSeedCrafting.fruitFor(kernels) == null, "");
            seedEnd(helper, c, tv, subs);
            return;
        }
        c.check("the seed hooks are attached", FarmHooks.attachedKeys().containsAll(List.of(FarmHooks.FRUIT_FOR, FarmHooks.PLAN,
            FarmHooks.CRAFT)), FarmHooks.attachedKeys());
        c.check("seedSources lists the entry", new SeedSource(corn, 2).equals(TownKitFarming.seedSources().get(kernels)),
            TownKitFarming.seedSources());
        c.check("fruitFor(kernels) is the corn", FarmerSeedCrafting.fruitFor(kernels) == corn, "");
        BuildingTests.waitFor(helper, 20, 1200, () -> needing(level, tv, kernels) > 0, () -> {
            c.check("a plot needs the kernels", needing(level, tv, kernels) > 0, needing(level, tv, kernels));
            c.check("no corn in storage yet, so no plan", FarmerSeedCrafting.plan(level, tv.mgr(), tv.farmer()) == null, "");
            c.check("stocked the corn", ScenarioBuilder.stockStorage(level, tv.mgr(), bound(corn, 8, tv.mgr())) == 8, "");
            FarmerSeedCrafting.Plan plan = FarmerSeedCrafting.plan(level, tv.mgr(), tv.farmer());
            c.check("the farmer plans to make kernels from storage corn", plan != null && plan.seed() == kernels
                && plan.fruit() == corn && plan.storage() != null && plan.fruitNeeded() == 8, plan);
            c.check("she carries no kernels yet", carried(tv.farmer(), kernels) == 0, "");
            tv.farmer().getInventory().addItem(bound(corn, 8, tv.mgr()));
            FarmerSeedCrafting.Plan carrying = FarmerSeedCrafting.plan(level, tv.mgr(), tv.farmer());
            c.check("carrying the corn, no fetch is needed", carrying != null && carrying.storage() == null, carrying);
            int made = FarmerSeedCrafting.craft(tv.farmer(), tv.mgr(), kernels);
            c.check("she made 16 kernels", made == 16 && carried(tv.farmer(), kernels) == 16, made + " made, "
                + carried(tv.farmer(), kernels) + " carried");
            c.check("from the 8 corn she carried", carried(tv.farmer(), corn) == 0, carried(tv.farmer(), corn));
            c.check("with kernels in her pack nothing more is planned", FarmerSeedCrafting.plan(level, tv.mgr(), tv.farmer()) == null,
                "");
            seedEnd(helper, c, tv, subs);
        });
    }

    /** Towntopia's melon and pumpkin seeds stay Towntopia's: entries for them are skipped and its own crafting is unchanged. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_farm_seed_melon")
    public static void melonUnchanged(GameTestHelper helper) {
        Checks c = new Checks("melonUnchanged");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = seedVillage(helper, 19600, 9600);
        if (tv == null) return;
        subs.add(TownKitFarming.seedFrom(SEED_OWNER, TestBlocks.TEST_KERNELS.get(), TestBlocks.TEST_CORN.get(), 2));
        subs.add(TownKitFarming.seedFrom(SEED_OWNER, Items.MELON_SEEDS, TestBlocks.TEST_CORN.get(), 5));
        subs.add(TownKitFarming.removeSeedFrom(SEED_OWNER, Items.PUMPKIN_SEEDS));
        c.check("melon seeds are not in the table", !TownKitFarming.seedSources().containsKey(Items.MELON_SEEDS),
            TownKitFarming.seedSources());
        c.check("Towntopia's melon produce is its own", FarmerSeedCrafting.fruitFor(Items.MELON_SEEDS) == Items.MELON_SLICE, "");
        c.check("Towntopia's pumpkin produce is its own", FarmerSeedCrafting.fruitFor(Items.PUMPKIN_SEEDS) == Items.PUMPKIN, "");
        tv.farmer().getInventory().addItem(bound(Items.MELON_SLICE, 4, tv.mgr()));
        int made = FarmerSeedCrafting.craft(tv.farmer(), tv.mgr(), Items.MELON_SEEDS);
        c.check("Towntopia still makes one melon seed per slice", made == 4 && carried(tv.farmer(), Items.MELON_SEEDS) == 4
            && carried(tv.farmer(), Items.MELON_SLICE) == 0, made + " made");
        seedEnd(helper, c, tv, subs);
    }

    /** With no entry registered the seed hooks are off and Towntopia's seed crafting knows nothing of the kernels. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_farm_seed_none")
    public static void nothingRegisteredUnchanged(GameTestHelper helper) {
        Checks c = new Checks("nothingRegisteredUnchanged");
        TestVillage tv = seedVillage(helper, 20000, 9600);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Item kernels = TestBlocks.TEST_KERNELS.get();
        c.check("no seed source", TownKitFarming.seedSources().isEmpty(), TownKitFarming.seedSources());
        c.check("the seed hooks are not attached", !seedHooksAttached(), FarmHooks.attachedKeys());
        ScenarioBuilder.stockStorage(level, tv.mgr(), bound(TestBlocks.TEST_CORN.get(), 8, tv.mgr()));
        tv.farmer().getInventory().addItem(bound(TestBlocks.TEST_CORN.get(), 8, tv.mgr()));
        c.check("no produce for the kernels", FarmerSeedCrafting.fruitFor(kernels) == null, "");
        c.check("no plan", FarmerSeedCrafting.plan(level, tv.mgr(), tv.farmer()) == null, "");
        c.check("no kernels made", FarmerSeedCrafting.craft(tv.farmer(), tv.mgr(), kernels) == 0
            && carried(tv.farmer(), kernels) == 0, "");
        seedEnd(helper, c, tv, new ArrayList<>());
    }

    // --- W1: harvest rules ----------------------------------------------------------------------------------------------

    private static final String HARVEST_OWNER = "townkit-test-harvest";
    private static final ResourceLocation RULE_ID = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_bush_rule");

    private static BlockState bush(Block block, int age) {
        return block.defaultBlockState().setValue(SweetBerryBushBlock.AGE, age);
    }

    /** Towntopia's {@code FarmerPerennials.pickable(Kind.BERRY, state, definition)} (package-private), as the farmer's scan calls it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean pickable(BlockState state, CropDefinition definition) throws ReflectiveOperationException {
        Class<?> perennials = Class.forName("com.alex.test.village.villagers.farmer.FarmerPerennials");
        Class<?> kind = Class.forName("com.alex.test.village.villagers.farmer.FarmerPerennials$Kind");
        Method m = perennials.getDeclaredMethod("pickable", kind, BlockState.class, CropDefinition.class);
        m.setAccessible(true);
        return (boolean) call(m, Enum.valueOf((Class) kind, "BERRY"), state, definition);
    }

    /** Towntopia's private {@code FarmerPerennials.fullBush(state)}. */
    private static boolean fullBush(BlockState state) throws ReflectiveOperationException {
        Method m = Class.forName("com.alex.test.village.villagers.farmer.FarmerPerennials").getDeclaredMethod("fullBush", BlockState.class);
        m.setAccessible(true);
        return (boolean) call(m, state);
    }

    private static Object call(Method m, Object... args) throws ReflectiveOperationException {
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    private static boolean ripeHooksAttached() {
        List<String> keys = FarmHooks.attachedKeys();
        return keys.contains(FarmHooks.IS_MATURE) || keys.contains(FarmHooks.PICKABLE) || keys.contains(FarmHooks.FULL_BUSH);
    }

    /**
     * A rule {@code age >= 3} on the test bush (picked by Towntopia's own bush code at age 2): age 2 is not ripe and not
     * pickable, age 3 is both and a full bush; changing the rule to {@code age >= 1} moves the line; removing it gives
     * Towntopia's answers back and detaches the hooks.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_farm_harvest")
    public static void ripeAtLeastRule(GameTestHelper helper) throws ReflectiveOperationException {
        Checks c = new Checks("ripeAtLeastRule");
        Block bushBlock = TestBlocks.TEST_BUSH.get();
        CropDefinition def = CropDefinitionRegistry.byCropBlock(bushBlock);
        c.check("the test crop file is loaded", def != null, "");
        if (def == null) {
            c.finish(helper);
            return;
        }
        BlockState age0 = bush(bushBlock, 0);
        BlockState age1 = bush(bushBlock, 1);
        BlockState age2 = bush(bushBlock, 2);
        BlockState age3 = bush(bushBlock, 3);
        c.check("no rule: age 2 is pickable (Towntopia's own rule for bushes)", pickable(age2, def), "");
        c.check("no rule: age 2 is not mature", !CropDefinitionRegistry.isMature(age2, def), "");
        c.check("no rule: the ripe hooks are not attached", !ripeHooksAttached(), FarmHooks.attachedKeys());
        Subscription sub = TownKitFarming.harvest(HARVEST_OWNER, HarvestRule.builder(RULE_ID).block(bushBlock)
            .ripe(new Ripe.AtLeast("age", 3)).build());
        try {
            c.check("harvests() lists the rule", TownKitFarming.harvests().stream().anyMatch(r -> r.id().equals(RULE_ID)), "");
            c.check("harvestFor finds it by block", RULE_ID.equals(TownKitFarming.harvestFor(age2).map(HarvestRule::id).orElse(null)), "");
            c.check("harvestFor of a vanilla bush is empty", TownKitFarming.harvestFor(bush(Blocks.SWEET_BERRY_BUSH, 3)).isEmpty(), "");
            if (RewrittenSites.GLOBAL.hooksOff()) {
                c.check("hooks off: the ripe hooks are not attached", !ripeHooksAttached(), FarmHooks.attachedKeys());
                c.check("hooks off: Towntopia's answer stays (age 2 pickable)", pickable(age2, def), "");
            } else {
                c.check("the ripe hooks are attached", FarmHooks.attachedKeys().containsAll(List.of(FarmHooks.IS_MATURE, FarmHooks.PICKABLE,
                    FarmHooks.FULL_BUSH)), FarmHooks.attachedKeys());
                c.check("age 2 is not ripe", !CropDefinitionRegistry.isMature(age2, def), "");
                c.check("age 3 is ripe", CropDefinitionRegistry.isMature(age3, def), "");
                c.check("age 2 is not pickable (Towntopia alone says yes)", !pickable(age2, def), "");
                c.check("age 3 is pickable", pickable(age3, def), "");
                c.check("age 3 is a full bush", fullBush(age3), "");
                c.check("age 2 is not a full bush", !fullBush(age2), "");
                c.check("a vanilla bush at age 2 is still pickable", pickable(bush(Blocks.SWEET_BERRY_BUSH, 2),
                    CropDefinitionRegistry.byCropBlock(Blocks.SWEET_BERRY_BUSH)), "");
                TownKitFarming.changeHarvest(HARVEST_OWNER, RULE_ID, r -> r.toBuilder().ripe(new Ripe.AtLeast("age", 1)).build());
                c.check("changed to age >= 1: age 1 is ripe", CropDefinitionRegistry.isMature(age1, def), "");
                c.check("changed to age >= 1: age 0 is not", !CropDefinitionRegistry.isMature(age0, def), "");
                c.check("changed to age >= 1: age 1 is pickable", pickable(age1, def), "");
                c.check("the full bush line stays at the highest age", !fullBush(age2) && fullBush(age3), "");
            }
        } finally {
            sub.remove();
        }
        c.check("removed: age 1 is not ripe again", !CropDefinitionRegistry.isMature(age1, def), "");
        c.check("removed: age 2 is pickable again", pickable(age2, def), "");
        c.check("removed: the ripe hooks are detached", !ripeHooksAttached(), FarmHooks.attachedKeys());
        c.finish(helper);
    }

    /** A rule that keeps Towntopia's own ripe rule (only picks by use) needs no ripe hook and changes no answer. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_farm_harvest")
    public static void useRuleKeepsTowntopiasRipe(GameTestHelper helper) throws ReflectiveOperationException {
        Checks c = new Checks("useRuleKeepsTowntopiasRipe");
        Block bushBlock = TestBlocks.TEST_BUSH.get();
        CropDefinition def = CropDefinitionRegistry.byCropBlock(bushBlock);
        Subscription sub = TownKitFarming.harvest(HARVEST_OWNER, HarvestRule.builder(ResourceLocation.fromNamespaceAndPath(TownKit.MODID,
            "test_use_rule")).block(bushBlock).action(new HarvestAction.Use(Holding.SHEARS)).build());
        try {
            c.check("the rule is found", TownKitFarming.harvestFor(bush(bushBlock, 0)).isPresent(), "");
            c.check("its action is stored", new HarvestAction.Use(Holding.SHEARS).equals(
                TownKitFarming.harvestFor(bush(bushBlock, 0)).orElseThrow().action()), "");
            c.check("no ripe hook is attached", !ripeHooksAttached(), FarmHooks.attachedKeys());
            c.check("age 2 stays pickable", def != null && pickable(bush(bushBlock, 2), def), "");
        } finally {
            sub.remove();
        }
        c.check("removed: nothing is found", TownKitFarming.harvestFor(bush(bushBlock, 0)).isEmpty(), "");
        c.finish(helper);
    }

    /** With no rule at all the vanilla sweet berry bush is picked at age 2 and Towntopia's answers are untouched. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_farm_harvest")
    public static void noRuleSweetBerriesUnchanged(GameTestHelper helper) throws ReflectiveOperationException {
        Checks c = new Checks("noRuleSweetBerriesUnchanged");
        CropDefinition sweet = CropDefinitionRegistry.byCropBlock(Blocks.SWEET_BERRY_BUSH);
        c.check("Towntopia's sweet berry definition is there", sweet != null, "");
        if (sweet != null) {
            c.check("age 2 is ripe", pickable(bush(Blocks.SWEET_BERRY_BUSH, 2), sweet), "");
            c.check("age 1 is not", !pickable(bush(Blocks.SWEET_BERRY_BUSH, 1), sweet), "");
            c.check("age 3 is a full bush", fullBush(bush(Blocks.SWEET_BERRY_BUSH, 3)), "");
            c.check("age 2 is not a full bush", !fullBush(bush(Blocks.SWEET_BERRY_BUSH, 2)), "");
        }
        c.check("no harvest rule", TownKitFarming.harvests().isEmpty(), TownKitFarming.harvests());
        c.check("no ripe hook is attached", !ripeHooksAttached(), FarmHooks.attachedKeys());
        c.finish(helper);
    }

    // --- W1: use-harvest and break capture -----------------------------------------------------------------------------

    private static final String USE_BATCH = "townkit_farm_use";
    private static final int USE_X = 20400;
    private static final int USE_Z = 9600;
    private static final String K_EXTRA_FOOD = "village.villagers.farmer.FarmerBalance#extraFoodChance(int)";

    /**
     * The use-harvest tests' villages (one farmer each) by x, each made by the first test of its batch that runs. A batch
     * needs a village of its own: the runner unloads a village between batches, and a second one can't be built in its place.
     */
    private static final java.util.Map<Integer, TestVillage> USE_VILLAGES = new java.util.HashMap<>();

    /** One use-harvest test: the shared farmer with an empty pack, her context, a spot of its own, and what to undo. */
    private static final class UseScene {
        final GameTestHelper helper;
        final Checks c;
        final TestVillage tv;
        final ServerLevel level;
        final Activity.Context ctx;
        final FarmerActivity activity = new FarmerActivity();
        /** The crop's position (its soil is one below). */
        final BlockPos pos;
        final List<Subscription> subs = new ArrayList<>();
        final List<HookRegistration> hooks = new ArrayList<>();
        /** Set by a body that ends the scene itself later (in a scheduled task). */
        boolean deferEnd;

        UseScene(GameTestHelper helper, Checks c, TestVillage tv, int villageX, int spot) {
            this.helper = helper;
            this.c = c;
            this.tv = tv;
            this.level = helper.getLevel();
            VillagerModel m = tv.mgr().getModel(tv.farmer().getNpcId());
            this.ctx = new Activity.Context(m.brain(), m, tv.mgr(), this.level, tv.farmer());
            int x = villageX - 40 + spot * 4;
            int z = USE_Z - 40;
            int y = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            this.pos = new BlockPos(x, y, z);
            emptyPack();
            clearSpot();
        }

        void emptyPack() {
            InventoryManager inv = this.tv.farmer().getInventory();
            for (int i = 0; i < inv.getSlots().length; i++) inv.setSlot(i, ItemStack.EMPTY);
        }

        void clearSpot() {
            for (int dy = 3; dy >= 0; dy--) this.level.setBlock(this.pos.above(dy), Blocks.AIR.defaultBlockState(), 3);
            discardItems();
        }

        /** Item entities within two blocks of the crop. */
        List<ItemEntity> ground() {
            return this.level.getEntitiesOfClass(ItemEntity.class, new AABB(this.pos).inflate(2));
        }

        void discardItems() {
            for (ItemEntity e : this.level.getEntitiesOfClass(ItemEntity.class, new AABB(this.pos).inflate(3))) e.discard();
        }

        void farmland(BlockState crop) {
            this.level.setBlock(this.pos.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            this.level.setBlock(this.pos, crop, 3);
        }

        Subscription rule(HarvestRule rule) {
            Subscription s = TownKitFarming.harvest(HARVEST_OWNER, rule);
            this.subs.add(s);
            return s;
        }

        FarmerManager.Job harvestJob(Item plantingItem) {
            return new FarmerManager.Job(UUID.randomUUID(), FarmerManager.JobType.HARVEST, this.pos, this.pos, this.pos, plantingItem, 0, true,
                this.level.getGameTime() + 1200);
        }

        boolean harvestCrop(FarmerManager.Job job) throws ReflectiveOperationException {
            return (boolean) invoke(this.activity, "harvestCrop", new Class<?>[] {Activity.Context.class, FarmerManager.Job.class}, this.ctx, job);
        }

        boolean pickBerries() throws ReflectiveOperationException {
            return (boolean) invoke(this.activity, "pickBerries", new Class<?>[] {Activity.Context.class, BlockPos.class}, this.ctx, this.pos);
        }

        boolean harvestBlock(BlockPos at) throws ReflectiveOperationException {
            return (boolean) invoke(this.activity, "harvestBlock", new Class<?>[] {Activity.Context.class, BlockPos.class, ItemStack.class},
                this.ctx, at, ItemStack.EMPTY);
        }

        int carried(Item item) {
            return FarmingTests.carried(this.tv.farmer(), item);
        }

        /** Whether every stack of {@code item} the farmer carries is owned by her village. */
        boolean allBound(Item item) {
            for (ItemStack s : this.tv.farmer().getInventory().getSlots()) {
                if (s.is(item) && !VillageTokenBinding.isBound(s)) return false;
            }
            return true;
        }

        void end() {
            this.subs.forEach(Subscription::remove);
            this.hooks.forEach(HookRegistration::remove);
            emptyPack();
            clearSpot();
            this.level.setBlock(this.pos.below(), Blocks.DIRT.defaultBlockState(), 3);
            BehaviourTests.finish(this.helper, this.c);
        }
    }

    private static Object invoke(Object self, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = FarmerActivity.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(self, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    @FunctionalInterface
    private interface SceneBody {
        void run(UseScene s) throws ReflectiveOperationException;
    }

    /** Runs {@code body} in the shared village at {@code spot}, then undoes its rules and hooks and clears the spot (also on failure). */
    private static void useScene(GameTestHelper helper, String name, int spot, SceneBody body) throws ReflectiveOperationException {
        useScene(helper, name, USE_X, spot, body);
    }

    private static void useScene(GameTestHelper helper, String name, int villageX, int spot, SceneBody body)
            throws ReflectiveOperationException {
        Checks c = new Checks(name);
        TestVillage village = USE_VILLAGES.get(villageX);
        if (village == null) {
            village = BehaviourTests.village(helper, villageX, USE_Z).orElse(null);
            if (village == null) return;
            USE_VILLAGES.put(villageX, village);
        }
        UseScene s = new UseScene(helper, c, village, villageX, spot);
        boolean ran = false;
        try {
            body.run(s);
            ran = true;
        } finally {
            if (!ran || !s.deferEnd) s.end();
        }
    }

    private static ResourceLocation ruleId(String path) {
        return ResourceLocation.fromNamespaceAndPath(TownKit.MODID, path);
    }

    private static HarvestRule useRule(String id, Block block, Holding holding) {
        return HarvestRule.builder(ruleId(id)).block(block).action(new HarvestAction.Use(holding)).build();
    }

    private static BlockState ripePick() {
        CropBlock crop = (CropBlock) TestBlocks.TEST_PICK_CROP.get();
        return crop.getStateForAge(crop.getMaxAge());
    }

    private static boolean useHooksAttached() {
        List<String> keys = FarmHooks.attachedKeys();
        return keys.contains(FarmHooks.HARVEST_CROP) || keys.contains(FarmHooks.PICK_BERRIES) || keys.contains(FarmHooks.PICK_GLOW_BERRIES)
            || keys.contains(FarmHooks.HARVEST_POD);
    }

    /**
     * A ripe field crop under a use rule (empty hand) is picked, not broken: the plant stays at its picked age, its two
     * produce land in the pack owned by the village, nothing is on the ground, the soil stays farmland every time (no
     * farmland-to-dirt roll), and Towntopia's "harvest done" leaves the tile as it is (no replant).
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void fieldCropPickedStaysPlanted(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "fieldCropPickedStaysPlanted", 0, s -> {
            s.rule(useRule("test_pick_field", TestBlocks.TEST_PICK_CROP.get(), Holding.EMPTY_HAND));
            Item produce = TestBlocks.TEST_PRODUCE.get();
            if (HooksOff.off()) {
                s.farmland(ripePick());
                s.c.check("hooks off: the use hooks are not attached", !useHooksAttached(), FarmHooks.attachedKeys());
                s.c.check("hooks off: Towntopia breaks the crop", s.harvestCrop(s.harvestJob(TestBlocks.TEST_PICK_SEED.get()))
                    && !s.level.getBlockState(s.pos).is(TestBlocks.TEST_PICK_CROP.get()), s.level.getBlockState(s.pos));
                return;
            }
            s.c.check("the use hooks are attached", FarmHooks.attachedKeys().containsAll(List.of(FarmHooks.HARVEST_CROP, FarmHooks.PICK_BERRIES,
                FarmHooks.PICK_GLOW_BERRIES, FarmHooks.HARVEST_POD)), FarmHooks.attachedKeys());
            s.c.check("no break rule: the break capture is not attached", !FarmHooks.attachedKeys().contains(FarmHooks.HARVEST_BLOCK),
                FarmHooks.attachedKeys());
            int picks = 10;
            boolean allPicked = true;
            boolean allStayed = true;
            boolean soilKept = true;
            for (int i = 0; i < picks; i++) {
                s.farmland(ripePick());
                FarmerManager.Job job = s.harvestJob(TestBlocks.TEST_PICK_SEED.get());
                allPicked &= s.harvestCrop(job);
                BlockState after = s.level.getBlockState(s.pos);
                allStayed &= after.is(TestBlocks.TEST_PICK_CROP.get())
                    && ((CropBlock) TestBlocks.TEST_PICK_CROP.get()).getAge(after) == TestBlocks.PickCropBlock.PICKED_AGE;
                soilKept &= s.level.getBlockState(s.pos.below()).is(Blocks.FARMLAND);
                if (i == 0) {
                    s.tv.mgr().farmers().reportSuccess(s.level, s.tv.mgr(), s.tv.farmer().getNpcId(), job);
                    BlockState reported = s.level.getBlockState(s.pos);
                    s.c.check("after Towntopia's harvest-done the tile is not replanted", reported.equals(after), reported);
                }
            }
            s.c.check("every harvest answered true", allPicked, "");
            s.c.check("the plant stayed at its picked age every time", allStayed, s.level.getBlockState(s.pos));
            s.c.check("the soil stayed farmland every time", soilKept, s.level.getBlockState(s.pos.below()));
            s.c.check("two produce per pick (and any bonus) in the pack", s.carried(produce) >= 2 * picks, s.carried(produce));
            s.c.check("the produce is owned by the village", s.allBound(produce), "");
            s.c.check("nothing on the ground", s.ground().isEmpty(), s.ground());
            s.c.check("no capture left running", !DropCapture.active(), "");
            s.c.check("the rule is still on", !UseHarvest.switchedOff(ruleId("test_pick_field")), "");
        });
    }

    /**
     * A berry bush under a use rule holding shears: with no shears the pick fails (never counted) and the bush is
     * untouched; with shears it is picked by use (the bush stays, berries in the pack) and the shears wear like
     * Towntopia's own pick.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void bushPickedWithShearsDamagesShears(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "bushPickedWithShearsDamagesShears", 1, s -> {
            ResourceLocation id = ruleId("test_bush_shears");
            s.rule(useRule("test_bush_shears", TestBlocks.TEST_BUSH.get(), Holding.SHEARS));
            s.level.setBlock(s.pos.below(), Blocks.DIRT.defaultBlockState(), 3);
            s.level.setBlock(s.pos, bush(TestBlocks.TEST_BUSH.get(), 3), 3);
            if (HooksOff.off()) {
                s.c.check("hooks off: Towntopia's pick refuses the test bush (sweet berry bushes only)", !s.pickBerries(), "");
                return;
            }
            boolean allFailed = true;
            for (int i = 0; i < UseHarvest.SWITCH_OFF_AFTER + 2; i++) allFailed &= !s.pickBerries();
            s.c.check("no shears: the pick fails", allFailed, "");
            s.c.check("no shears: the bush is untouched", s.level.getBlockState(s.pos).equals(bush(TestBlocks.TEST_BUSH.get(), 3)),
                s.level.getBlockState(s.pos));
            s.c.check("no shears: never counted against the rule", !UseHarvest.switchedOff(id), "");
            s.tv.farmer().getInventory().addItem(bound(Items.SHEARS, 1, s.tv.mgr()));
            s.c.check("with shears the pick works", s.pickBerries(), "");
            BlockState after = s.level.getBlockState(s.pos);
            s.c.check("the bush stays, picked", after.is(TestBlocks.TEST_BUSH.get()) && after.getValue(SweetBerryBushBlock.AGE) == 1, after);
            s.c.check("berries in the pack", s.carried(Items.SWEET_BERRIES) >= 2, s.carried(Items.SWEET_BERRIES));
            int damage = -1;
            for (ItemStack st : s.tv.farmer().getInventory().getSlots()) if (st.is(Items.SHEARS)) damage = st.getDamageValue();
            s.c.check("the shears lost durability", damage == 1, damage);
            s.c.check("nothing on the ground", s.ground().isEmpty(), s.ground());
        });
    }

    /**
     * A crop whose click passes: the harvest fails and the plant is left alone each time; the tenth failure in a row
     * switches the rule off (worldwide), after which Towntopia breaks the crop again; a reload switches it on.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void passingBlockCountsFailure(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "passingBlockCountsFailure", 2, s -> {
            ResourceLocation id = ruleId("test_pass_rule");
            s.rule(useRule("test_pass_rule", TestBlocks.TEST_PASS_CROP.get(), Holding.EMPTY_HAND));
            CropBlock crop = (CropBlock) TestBlocks.TEST_PASS_CROP.get();
            BlockState ripe = crop.getStateForAge(crop.getMaxAge());
            s.farmland(ripe);
            if (HooksOff.off()) {
                s.c.check("hooks off: Towntopia breaks the crop", s.harvestCrop(s.harvestJob(TestBlocks.TEST_PASS_SEED.get()))
                    && s.level.getBlockState(s.pos).isAir(), s.level.getBlockState(s.pos));
                return;
            }
            try {
                for (int i = 1; i <= UseHarvest.SWITCH_OFF_AFTER; i++) {
                    boolean ok = s.harvestCrop(s.harvestJob(TestBlocks.TEST_PASS_SEED.get()));
                    s.c.check("failure " + i + ": the harvest fails and the plant stays", !ok && s.level.getBlockState(s.pos).equals(ripe),
                        s.level.getBlockState(s.pos));
                    s.c.check("failure " + i + ": " + (i < UseHarvest.SWITCH_OFF_AFTER ? "still on" : "switched off"),
                        UseHarvest.switchedOff(id) == (i >= UseHarvest.SWITCH_OFF_AFTER), "");
                }
                s.c.check("nothing gained", s.carried(TestBlocks.TEST_PRODUCE.get()) == 0, s.carried(TestBlocks.TEST_PRODUCE.get()));
                s.c.check("switched off: Towntopia breaks the crop", s.harvestCrop(s.harvestJob(TestBlocks.TEST_PASS_SEED.get()))
                    && s.level.getBlockState(s.pos).isAir(), s.level.getBlockState(s.pos));
                FarmHooks.reloaded();
                s.c.check("after a reload the rule is on again", !UseHarvest.switchedOff(id), "");
            } finally {
                FarmHooks.reloaded();
            }
        });
    }

    /**
     * The crop is broken (or replaced) between planning and the pick: no exception, no item made up, nothing in the pack
     * or on the ground.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void blockBrokenBeforePickNoItems(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "blockBrokenBeforePickNoItems", 3, s -> {
            HarvestRule rule = useRule("test_pick_gone", TestBlocks.TEST_PICK_CROP.get(), Holding.EMPTY_HAND);
            s.rule(rule);
            s.farmland(ripePick());
            FarmerManager.Job job = s.harvestJob(TestBlocks.TEST_PICK_SEED.get());
            s.level.setBlock(s.pos, Blocks.AIR.defaultBlockState(), 3);
            s.c.check("broken: the harvest fails", !s.harvestCrop(job), "");
            s.level.setBlock(s.pos, Blocks.STONE.defaultBlockState(), 3);
            s.c.check("replaced: the harvest fails", !s.harvestCrop(job), "");
            if (!HooksOff.off()) {
                try {
                    s.level.setBlock(s.pos, Blocks.AIR.defaultBlockState(), 3);
                    s.c.check("broken: use-harvest says the block is gone",
                        UseHarvest.run(s.level, s.tv.farmer(), s.tv.mgr(), s.pos, rule) == UseHarvest.Result.BLOCK_GONE, "");
                    s.level.setBlock(s.pos, Blocks.STONE.defaultBlockState(), 3);
                    s.c.check("replaced: use-harvest says the block is gone",
                        UseHarvest.run(s.level, s.tv.farmer(), s.tv.mgr(), s.pos, rule) == UseHarvest.Result.BLOCK_GONE, "");
                    s.c.check("the stone is untouched", s.level.getBlockState(s.pos).is(Blocks.STONE), s.level.getBlockState(s.pos));
                } finally {
                    FarmHooks.reloaded();
                }
            }
            boolean empty = true;
            for (ItemStack st : s.tv.farmer().getInventory().getSlots()) empty &= st.isEmpty();
            s.c.check("nothing in the pack", empty, "");
            s.c.check("nothing on the ground", s.ground().isEmpty(), s.ground());
        });
    }

    /** The pack is full: the picked produce is popped at the crop the way Towntopia's own harvest does it. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void fullPackPopsToLossLog(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "fullPackPopsToLossLog", 4, s -> {
            s.rule(useRule("test_pick_full", TestBlocks.TEST_PICK_CROP.get(), Holding.EMPTY_HAND));
            s.farmland(ripePick());
            InventoryManager inv = s.tv.farmer().getInventory();
            for (int i = 0; i < inv.getSlots().length; i++) inv.setSlot(i, bound(Items.COBBLESTONE, 64, s.tv.mgr()));
            boolean ok = s.harvestCrop(s.harvestJob(TestBlocks.TEST_PICK_SEED.get()));
            if (HooksOff.off()) {
                s.c.check("hooks off: Towntopia breaks the crop", ok, "");
                return;
            }
            s.c.check("the pick worked", ok && s.level.getBlockState(s.pos).is(TestBlocks.TEST_PICK_CROP.get()), s.level.getBlockState(s.pos));
            int popped = 0;
            for (ItemEntity e : s.ground()) if (e.getItem().is(TestBlocks.TEST_PRODUCE.get())) popped += e.getItem().getCount();
            s.c.check("the produce was popped at the crop (not lost, not caught again)", popped >= 2, s.ground());
            s.c.check("the pack is unchanged", s.carried(Items.COBBLESTONE) == 64 * inv.getSlots().length, s.carried(Items.COBBLESTONE));
            s.c.check("no capture left running", !DropCapture.active(), "");
        });
    }

    /**
     * A two-block crop whose upper half breaks with drops when the lower half goes: without a rule Towntopia's harvest of
     * the lower half leaves the upper half's drop on the ground; with a break rule nothing is left within two blocks and
     * both drops are in the pack.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void twoBlockBreakRuleNoGroundItems(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "twoBlockBreakRuleNoGroundItems", 5, s -> {
            Block tall = TestBlocks.TEST_TALL_CROP.get();
            BlockState lower = tall.defaultBlockState();
            BlockState upper = lower.setValue(TestBlocks.TallCropBlock.UPPER, true);
            Item produce = TestBlocks.TEST_PRODUCE.get();
            s.level.setBlock(s.pos.below(), Blocks.DIRT.defaultBlockState(), 3);
            s.level.setBlock(s.pos, lower, 3);
            s.level.setBlock(s.pos.above(), upper, 3);
            s.c.check("no rule: harvested", s.harvestBlock(s.pos), "");
            s.c.check("no rule: both halves are gone", s.level.getBlockState(s.pos).isAir() && s.level.getBlockState(s.pos.above()).isAir(), "");
            s.c.check("no rule: the upper half's drop is on the ground (Towntopia as is)", !s.ground().isEmpty(), s.ground());
            s.discardItems();
            s.emptyPack();
            s.rule(HarvestRule.builder(ruleId("test_tall_break")).block(tall).build());
            if (HooksOff.off()) {
                s.c.check("hooks off: the break capture is not attached", !FarmHooks.attachedKeys().contains(FarmHooks.HARVEST_BLOCK), "");
                return;
            }
            s.c.check("the break capture is attached", FarmHooks.attachedKeys().contains(FarmHooks.HARVEST_BLOCK), FarmHooks.attachedKeys());
            s.c.check("a break rule alone attaches no use hook", !useHooksAttached(), FarmHooks.attachedKeys());
            s.level.setBlock(s.pos, lower, 3);
            s.level.setBlock(s.pos.above(), upper, 3);
            s.c.check("with the rule: harvested", s.harvestBlock(s.pos), "");
            s.c.check("both halves are gone", s.level.getBlockState(s.pos).isAir() && s.level.getBlockState(s.pos.above()).isAir(), "");
            s.c.check("nothing on the ground within two blocks", s.ground().isEmpty(), s.ground());
            s.c.check("both drops are in the pack", s.carried(produce) == 2, s.carried(produce));
            s.c.check("owned by the village", s.allBound(produce), "");
            s.c.check("no capture left running", !DropCapture.active(), "");
        });
    }

    /**
     * Use-harvest rolls Towntopia's skill bonus on the first caught drop its list or the W5 tag accepts: with the chance
     * at 1 every pick gives one more produce, at 0 none.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void useRuleGetsBonusRoll(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "useRuleGetsBonusRoll", 6, s -> {
            if (HooksOff.off()) {
                s.c.check("hooks off: nothing to check (no use-harvest)", true, "");
                return;
            }
            s.rule(useRule("test_pick_bonus", TestBlocks.TEST_PICK_CROP.get(), Holding.EMPTY_HAND));
            double[] chance = {1.0};
            s.hooks.add(TownKitHooks.after(HARVEST_OWNER, K_EXTRA_FOOD, call -> call.setReturnValue(chance[0])));
            Item produce = TestBlocks.TEST_PRODUCE.get();
            for (int i = 0; i < 3; i++) {
                s.farmland(ripePick());
                s.harvestCrop(s.harvestJob(TestBlocks.TEST_PICK_SEED.get()));
            }
            s.c.check("chance 1: two produce and one bonus per pick", s.carried(produce) == 9, s.carried(produce));
            s.emptyPack();
            chance[0] = 0.0;
            for (int i = 0; i < 3; i++) {
                s.farmland(ripePick());
                s.harvestCrop(s.harvestJob(TestBlocks.TEST_PICK_SEED.get()));
            }
            s.c.check("chance 0: two produce per pick, no bonus", s.carried(produce) == 6, s.carried(produce));
        });
    }

    /**
     * Towntopia's own harvest stays whenever TownKit has nothing to do: with hooks off a use rule changes nothing (the
     * crop is broken); with hooks on, the rule removed leaves no use hook attached and the crop is broken.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void hooksOffTowntopiaBehaviour(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "hooksOffTowntopiaBehaviour", 7, s -> {
            Subscription sub = s.rule(useRule("test_pick_off", TestBlocks.TEST_PICK_CROP.get(), Holding.EMPTY_HAND));
            if (HooksOff.off()) {
                s.farmland(ripePick());
                s.c.check("hooks off: no use hook attached", !useHooksAttached(), FarmHooks.attachedKeys());
                s.c.check("hooks off: Towntopia breaks the crop", s.harvestCrop(s.harvestJob(TestBlocks.TEST_PICK_SEED.get()))
                    && !s.level.getBlockState(s.pos).is(TestBlocks.TEST_PICK_CROP.get()), s.level.getBlockState(s.pos));
                s.c.check("hooks off: the broken crop's drop is in the pack", s.carried(TestBlocks.TEST_PRODUCE.get()) >= 1, "");
                return;
            }
            s.c.check("a use rule attaches the use hooks", useHooksAttached(), FarmHooks.attachedKeys());
            sub.remove();
            s.c.check("removed: no use hook attached", !useHooksAttached(), FarmHooks.attachedKeys());
            s.c.check("removed: no break capture attached", !FarmHooks.attachedKeys().contains(FarmHooks.HARVEST_BLOCK), FarmHooks.attachedKeys());
            s.farmland(ripePick());
            s.c.check("no rule: Towntopia breaks the crop", s.harvestCrop(s.harvestJob(TestBlocks.TEST_PICK_SEED.get()))
                && !s.level.getBlockState(s.pos).is(TestBlocks.TEST_PICK_CROP.get()), s.level.getBlockState(s.pos));
            s.c.check("no rule: the broken crop's drop is in the pack", s.carried(TestBlocks.TEST_PRODUCE.get()) >= 1, "");
        });
    }

    /** A fresh two-block test crop at the scene's spot (lower half on dirt). */
    private static void tallCrop(UseScene s) {
        BlockState lower = TestBlocks.TEST_TALL_CROP.get().defaultBlockState();
        s.level.setBlock(s.pos.below(), Blocks.DIRT.defaultBlockState(), 3);
        s.level.setBlock(s.pos, lower, 3);
        s.level.setBlock(s.pos.above(), lower.setValue(TestBlocks.TallCropBlock.UPPER, true), 3);
    }

    private static int producedOnGround(UseScene s) {
        int n = 0;
        for (ItemEntity e : s.ground()) if (e.getItem().is(TestBlocks.TEST_PRODUCE.get())) n += e.getItem().getCount();
        return n;
    }

    /**
     * Other listeners on {@code harvestBlock} (one ordered ahead of TownKit's, one after) run exactly once per harvest of a
     * break rule's block, before and after: the capture doesn't run Towntopia's method a second time.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void breakCaptureListenersRunOnce(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "breakCaptureListenersRunOnce", 8, s -> {
            int[] counts = new int[4];
            BlockPos at = s.pos;
            s.hooks.add(TownKitHooks.before(HARVEST_OWNER, FarmHooks.HARVEST_BLOCK, call -> {
                if (at.equals(call.args()[1])) counts[0]++;
            }));
            s.hooks.add(TownKitHooks.after(HARVEST_OWNER, FarmHooks.HARVEST_BLOCK, call -> {
                if (at.equals(call.args()[1])) counts[1]++;
            }));
            s.rule(HarvestRule.builder(ruleId("test_tall_once")).block(TestBlocks.TEST_TALL_CROP.get()).build());
            s.hooks.add(TownKitHooks.before(HARVEST_OWNER, FarmHooks.HARVEST_BLOCK, call -> {
                if (at.equals(call.args()[1])) counts[2]++;
            }));
            s.hooks.add(TownKitHooks.after(HARVEST_OWNER, FarmHooks.HARVEST_BLOCK, call -> {
                if (at.equals(call.args()[1])) counts[3]++;
            }));
            tallCrop(s);
            boolean ok = s.harvestBlock(s.pos);
            s.c.check("harvested", ok, "");
            if (HooksOff.off()) {
                s.c.check("hooks off: no listener runs", counts[0] + counts[1] + counts[2] + counts[3] == 0, java.util.Arrays.toString(counts));
                return;
            }
            s.c.check("a before-listener ahead of TownKit's ran once", counts[0] == 1, counts[0]);
            s.c.check("an after-listener registered first ran once", counts[1] == 1, counts[1]);
            s.c.check("a before-listener after TownKit's ran once", counts[2] == 1, counts[2]);
            s.c.check("an after-listener registered last ran once", counts[3] == 1, counts[3]);
            s.c.check("nothing on the ground", s.ground().isEmpty(), s.ground());
            s.c.check("both drops in the pack", s.carried(TestBlocks.TEST_PRODUCE.get()) == 2, s.carried(TestBlocks.TEST_PRODUCE.get()));
            s.c.check("no capture left open", DropCapture.openCount() == 0, DropCapture.openCount());
        });
    }

    /**
     * Break rule with a full pack: Towntopia pops the lower half's drop itself (not caught again), TownKit pops the caught
     * upper half's drop; each item is popped, and logged, exactly once.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH)
    public static void breakFullPackPopsOnce(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "breakFullPackPopsOnce", 9, s -> {
            int[] pops = new int[1];
            s.hooks.add(TownKitHooks.after(HARVEST_OWNER, FarmHooks.LOSS_POP, call -> {
                if (s.pos.equals(call.args()[1])) pops[0] += ((ItemStack) call.args()[2]).getCount();
            }));
            s.rule(HarvestRule.builder(ruleId("test_tall_full")).block(TestBlocks.TEST_TALL_CROP.get()).build());
            InventoryManager inv = s.tv.farmer().getInventory();
            for (int i = 0; i < inv.getSlots().length; i++) inv.setSlot(i, bound(Items.COBBLESTONE, 64, s.tv.mgr()));
            tallCrop(s);
            s.c.check("harvested", s.harvestBlock(s.pos), "");
            if (HooksOff.off()) {
                s.c.check("hooks off: one drop popped by Towntopia, one fell on its own", producedOnGround(s) == 2, s.ground());
                return;
            }
            s.c.check("two items popped (logged) in all, one each", pops[0] == 2, pops[0]);
            s.c.check("exactly the two drops are on the ground", producedOnGround(s) == 2, s.ground());
            s.c.check("the pack is unchanged", s.carried(Items.COBBLESTONE) == 64 * inv.getSlots().length, s.carried(Items.COBBLESTONE));
            s.c.check("no capture left open", DropCapture.openCount() == 0, DropCapture.openCount());
        });
    }

    /**
     * A later before-listener cancels {@code harvestBlock}, so TownKit's after-hook never comes: the capture left open is
     * released at the end of the tick (it never outlives it), and a capture left open is released by the next harvest.
     * Its own batch: the other use tests check that no capture is open, and this one leaves one open until the tick ends.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = USE_BATCH + "_stale")
    public static void staleBreakCaptureReleased(GameTestHelper helper) throws ReflectiveOperationException {
        useScene(helper, "staleBreakCaptureReleased", USE_X + 400, 0, s -> {
            if (HooksOff.off()) {
                s.c.check("hooks off: nothing to check (no capture)", true, "");
                return;
            }
            s.rule(HarvestRule.builder(ruleId("test_tall_stale")).block(TestBlocks.TEST_TALL_CROP.get()).build());
            boolean[] cancel = {true};
            s.hooks.add(TownKitHooks.before(HARVEST_OWNER, FarmHooks.HARVEST_BLOCK, call -> {
                if (cancel[0] && s.pos.equals(call.args()[1])) call.cancelWith(false);
            }));
            tallCrop(s);
            s.c.check("cancelled: no harvest", !s.harvestBlock(s.pos), "");
            s.c.check("cancelled: the capture is left open for now", DropCapture.openCount() == 1, DropCapture.openCount());
            cancel[0] = false;
            s.c.check("the next harvest works", s.harvestBlock(s.pos), "");
            s.c.check("the next harvest released the stale capture and closed its own", DropCapture.openCount() == 0, DropCapture.openCount());
            s.c.check("nothing on the ground", s.ground().isEmpty(), s.ground());
            cancel[0] = true;
            tallCrop(s);
            s.c.check("cancelled again: no harvest", !s.harvestBlock(s.pos), "");
            s.deferEnd = true;
            helper.runAfterDelay(1, () -> {
                try {
                    s.c.check("a tick later the capture is released", DropCapture.openCount() == 0, DropCapture.openCount());
                    s.c.check("the crop is untouched", s.level.getBlockState(s.pos).is(TestBlocks.TEST_TALL_CROP.get()), "");
                } finally {
                    s.end();
                }
            });
        });
    }

    // --- W1: harvest rules in the away catch-up ---------------------------------------------------------------------------

    private static final String AWAY_BATCH = "townkit_farm_away";
    private static final int AWAY_X = 21200;
    /** The away tests' shared village (one farmer, one farm): made by the first test of the batch that runs. */
    private static TestVillage awayVillage;

    @FunctionalInterface
    private interface AwayBody {
        void run(Checks c, TestVillage tv, BlockPos tile, List<Subscription> subs) throws ReflectiveOperationException;
    }

    /** One crop tile of the village's farm (where the away farmer works), or null while the farm isn't known yet. */
    @Nullable
    private static BlockPos awayTile(TestVillage tv) {
        for (BlockPos p : tv.mgr().farmers().awayField().tiles()) return p;
        return null;
    }

    /** Runs {@code body} on a crop tile of the shared village's farm, cleaning the tile and the rules up after it. */
    private static void awayScene(GameTestHelper helper, String name, AwayBody body) {
        Checks c = new Checks(name);
        if (awayVillage == null) awayVillage = seedVillage(helper, AWAY_X, 9600);
        TestVillage tv = awayVillage;
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        BuildingTests.waitFor(helper, 10, 600, () -> awayTile(tv) != null, () -> {
            BlockPos tile = awayTile(tv);
            List<Subscription> subs = new ArrayList<>();
            if (tile == null) {
                c.check("the farm has a crop tile", false, "");
                BehaviourTests.finish(helper, c);
                return;
            }
            InventoryManager inv = tv.farmer().getInventory();
            for (int i = 0; i < inv.getSlots().length; i++) inv.setSlot(i, ItemStack.EMPTY);
            level.setBlock(tile.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(tile.above(), Blocks.AIR.defaultBlockState(), 3);
            try {
                body.run(c, tv, tile, subs);
            } catch (ReflectiveOperationException | RuntimeException e) {
                TownKit.LOG.error("[TownKit test] {} threw", name, e);
                c.check("no exception", false, e);
            } finally {
                subs.forEach(Subscription::remove);
                level.setBlock(tile.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(tile, Blocks.AIR.defaultBlockState(), 3);
            }
            BehaviourTests.finish(helper, c);
        });
    }

    /** One away day, previewed ({@code AwayRun.previewAll} is package-private; it changes nothing in the world). */
    private static Object awayRun(ServerLevel level, VillageManager mgr) throws ReflectiveOperationException {
        Class<?> run = Class.forName("com.alex.test.village.economy.away.AwayRun");
        Method preview = run.getDeclaredMethod("previewAll", ServerLevel.class, VillageManager.class, int.class);
        preview.setAccessible(true);
        return preview.invoke(null, level, mgr, 1);
    }

    @SuppressWarnings("unchecked")
    private static int awayMade(Object run, Item item) throws ReflectiveOperationException {
        return ((java.util.Map<Item, Integer>) run.getClass().getMethod("made").invoke(run)).getOrDefault(item, 0);
    }

    /** The away farm's plot state at {@code pos} after the day, or null when the farm holds no plot there. */
    @Nullable
    private static BlockState awayPlot(Object run, BlockPos pos) throws ReflectiveOperationException {
        java.lang.reflect.Field f = run.getClass().getDeclaredField("farm");
        f.setAccessible(true);
        com.alex.test.village.economy.away.AwayFarm farm = (com.alex.test.village.economy.away.AwayFarm) f.get(run);
        for (com.alex.test.village.economy.away.AwayFarm.Plot p : farm.plots()) if (p.pos.equals(pos)) return p.state();
        return null;
    }

    private static HarvestRule awayRule(String id, @Nullable AwayYield yield) {
        HarvestRule.Builder b = HarvestRule.builder(ruleId(id)).block(TestBlocks.TEST_PICK_CROP.get())
            .action(new HarvestAction.Use(Holding.EMPTY_HAND));
        if (yield != null) b.away(yield);
        return b.build();
    }

    private static boolean awayHookAttached() {
        return FarmHooks.attachedKeys().contains(FarmHooks.AWAY_HARVEST);
    }

    /**
     * A ripe crop under a use rule with an away yield, in a village away for a day: the yield is in the away result
     * (the rule's 3, not Towntopia's own drop), the plant stays, at the rule's picked state, and the soil is untouched.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = AWAY_BATCH)
    public static void awayUseRuleWithYield(GameTestHelper helper) {
        awayScene(helper, "awayUseRuleWithYield", (c, tv, tile, subs) -> {
            ServerLevel level = helper.getLevel();
            subs.add(TownKitFarming.harvest(HARVEST_OWNER, awayRule("test_away_yield",
                new AwayYield(TestBlocks.TEST_PRODUCE.get(), 3, 3, java.util.Map.of("age", "4")))));
            level.setBlock(tile, ripePick(), 3);
            if (HooksOff.off()) {
                c.check("hooks off: the away hook is not attached", !awayHookAttached(), FarmHooks.attachedKeys());
                return;
            }
            c.check("the away hook is attached", awayHookAttached(), FarmHooks.attachedKeys());
            Object run = awayRun(level, tv.mgr());
            c.check("the yield is in the away result", awayMade(run, TestBlocks.TEST_PRODUCE.get()) == 3,
                awayMade(run, TestBlocks.TEST_PRODUCE.get()));
            BlockState plot = awayPlot(run, tile);
            c.check("the plot is at the picked state", plot != null && plot.is(TestBlocks.TEST_PICK_CROP.get())
                && ((CropBlock) TestBlocks.TEST_PICK_CROP.get()).getAge(plot) == TestBlocks.PickCropBlock.PICKED_AGE, plot);
            c.check("the world's crop is untouched by a preview", level.getBlockState(tile).equals(ripePick()), level.getBlockState(tile));
        });
    }

    /** A use rule with no away yield: nothing is rolled and the plot stays ripe (not broken, not picked). */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = AWAY_BATCH)
    public static void awayUseRuleWithoutYieldStaysRipe(GameTestHelper helper) {
        awayScene(helper, "awayUseRuleWithoutYieldStaysRipe", (c, tv, tile, subs) -> {
            ServerLevel level = helper.getLevel();
            subs.add(TownKitFarming.harvest(HARVEST_OWNER, awayRule("test_away_noyield", null)));
            level.setBlock(tile, ripePick(), 3);
            if (HooksOff.off()) return;
            Object run = awayRun(level, tv.mgr());
            c.check("no yield", awayMade(run, TestBlocks.TEST_PRODUCE.get()) == 0, awayMade(run, TestBlocks.TEST_PRODUCE.get()));
            c.check("the plot is still ripe", ripePick().equals(awayPlot(run, tile)), awayPlot(run, tile));
        });
    }

    /**
     * A rule block with a block of the same crop directly above it is skipped by the away day (a two-block crop's
     * halves can't be kept in step): no yield, the plot stays as it was, even with an away yield on the rule.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = AWAY_BATCH)
    public static void awayTwoBlockSkipped(GameTestHelper helper) {
        awayScene(helper, "awayTwoBlockSkipped", (c, tv, tile, subs) -> {
            ServerLevel level = helper.getLevel();
            subs.add(TownKitFarming.harvest(HARVEST_OWNER, awayRule("test_away_tall",
                new AwayYield(TestBlocks.TEST_PRODUCE.get(), 3, 3, java.util.Map.of("age", "4")))));
            level.setBlock(tile, ripePick(), 3);
            level.setBlock(tile.above(), ripePick(), 3);
            if (HooksOff.off()) return;
            Object run = awayRun(level, tv.mgr());
            c.check("no yield", awayMade(run, TestBlocks.TEST_PRODUCE.get()) == 0, awayMade(run, TestBlocks.TEST_PRODUCE.get()));
            c.check("the plot is untouched", ripePick().equals(awayPlot(run, tile)), awayPlot(run, tile));
        });
    }

    /** A rule whose picked state doesn't fit its block: the plot is left alone and nothing is yielded. */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = AWAY_BATCH)
    public static void awayBadPickedStateSkipped(GameTestHelper helper) {
        awayScene(helper, "awayBadPickedStateSkipped", (c, tv, tile, subs) -> {
            ServerLevel level = helper.getLevel();
            subs.add(TownKitFarming.harvest(HARVEST_OWNER, awayRule("test_away_bad",
                new AwayYield(TestBlocks.TEST_PRODUCE.get(), 3, 3, java.util.Map.of("age", "99")))));
            level.setBlock(tile, ripePick(), 3);
            if (HooksOff.off()) return;
            Object run = awayRun(level, tv.mgr());
            c.check("no yield", awayMade(run, TestBlocks.TEST_PRODUCE.get()) == 0, awayMade(run, TestBlocks.TEST_PRODUCE.get()));
            c.check("the plot is untouched", ripePick().equals(awayPlot(run, tile)), awayPlot(run, tile));
        });
    }

    /** A crop the farmer's settings switch off is not worked away, rule or not: no yield, no state change, the plot stays ripe. */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = AWAY_BATCH)
    public static void awayDisabledCropSkipped(GameTestHelper helper) {
        awayScene(helper, "awayDisabledCropSkipped", (c, tv, tile, subs) -> {
            ServerLevel level = helper.getLevel();
            FarmerSettings settings = FarmerSettings.of(tv.farmer());
            Item seed = TestBlocks.TEST_PICK_SEED.get();
            int before = settings.plantMode(seed);
            try {
                subs.add(TownKitFarming.harvest(HARVEST_OWNER, awayRule("test_away_off",
                    new AwayYield(TestBlocks.TEST_PRODUCE.get(), 3, 3, java.util.Map.of("age", "4")))));
                settings.setPlantMode(seed, FarmerSettings.PLANT_LEAVE);
                level.setBlock(tile, ripePick(), 3);
                c.check("the crop is switched off in the farmer's settings", !settings.isPlantEnabled(seed), settings.plantMode(seed));
                if (HooksOff.off()) return;
                Object run = awayRun(level, tv.mgr());
                c.check("no yield", awayMade(run, TestBlocks.TEST_PRODUCE.get()) == 0, awayMade(run, TestBlocks.TEST_PRODUCE.get()));
                c.check("the plot is untouched", ripePick().equals(awayPlot(run, tile)), awayPlot(run, tile));
            } finally {
                settings.setPlantMode(seed, before);
            }
        });
    }

    /** No rule: Towntopia's own away harvest runs (the crop is cut, the plot is empty) and the away hook is not attached. */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = AWAY_BATCH)
    public static void awayNoRuleUnchanged(GameTestHelper helper) {
        awayScene(helper, "awayNoRuleUnchanged", (c, tv, tile, subs) -> {
            ServerLevel level = helper.getLevel();
            level.setBlock(tile, ripePick(), 3);
            c.check("no rule: the away hook is not attached", !awayHookAttached(), FarmHooks.attachedKeys());
            Object run = awayRun(level, tv.mgr());
            BlockState plot = awayPlot(run, tile);
            c.check("Towntopia's own harvest cut the crop", plot != null && plot.isAir(), plot);
        });
    }
}
