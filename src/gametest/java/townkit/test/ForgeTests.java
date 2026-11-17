package townkit.test;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.blacksmith.ForgeOrderBook;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardArmorUtils;
import com.alex.test.village.villagers.guard.GuardSwordUtils;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.lumberjack.AxeUtils;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import com.alex.test.village.villagers.miner.PickaxeUtils;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.miner.behavior.MinerActivity;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.alex.test.village.villagers.tools.ToolSpeed;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.hooks.HookRegistration;
import townkit.api.hooks.TownKitHooks;
import townkit.api.work.ForgeRecipe;
import townkit.api.work.GearSlot;
import townkit.api.work.TierPlace;
import townkit.api.work.TownKitForge;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.work.RewrittenWorkSites;
import townkit.core.work.WorkSites;
import townkit.core.work.forge.ForgeBridges;
import townkit.core.work.forge.ForgeHooks;
import townkit.core.work.forge.SmeltBridges;

/**
 * The blacksmith overlay in a real village: a storage room, a forge (furnace and anvil), a smith and a miner.
 * Most tests ask Towntopia's own planner what the smith would make next ({@code BlacksmithActivity.plan}, the method its
 * work activity calls) with a known stock in storage and in the villagers' packs, so the answer doesn't depend on
 * walking; {@link #stockItemForged} lets the smith really forge. The smith's level is set with an after-hook on
 * {@code VillagerBrain#blacksmithSkillLevel} (raising the real skill doesn't reach the planner at once).
 *
 * <p>The addon here: material {@code townkit:test_steel} (tag {@code townkit:test_steel} = amethyst shards), tier
 * {@code townkit:test_steel} between iron and diamond, the test steel pickaxe and chestplate (test items). With TownKit's
 * hooks switched off, each test checks that the API still answers and Towntopia's smith keeps its own lists.
 */
@GameTestHolder(CookingTests.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class ForgeTests {
    private static final String OWNER = "townkit-test-forge";
    private static final int VILLAGE_X = 17200;
    private static final int VILLAGE_Z = 9600;
    private static final String K_SKILL = "village.brain.VillagerBrain#blacksmithSkillLevel()";
    private static final ResourceLocation STEEL = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_steel");
    private static final ResourceLocation IRON = ResourceLocation.fromNamespaceAndPath("towntopia", "iron");
    private static final ResourceLocation WOOD = ResourceLocation.fromNamespaceAndPath("towntopia", "wood");
    private static final TagKey<Item> STEEL_ITEMS = TagKey.create(Registries.ITEM, STEEL);

    private record ForgeVillage(VillageManager mgr, BlockPos door, UUID smithId, UUID minerId) {
    }

    @Nullable
    private static ForgeVillage shared;

    private ForgeTests() {
    }

    // --- the shared village ------------------------------------------------------------------------------------------

    private static Optional<ForgeVillage> village(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageManagerRegistry reg = VillageManagerRegistry.get(level.getServer());
        ForgeVillage v = shared;
        if (v != null && reg.get(v.mgr().getVillageId()) == v.mgr()) {
            BehaviourTests.forceChunks(level, VILLAGE_X, VILLAGE_Z);
            TestKit.setHeadless(true);
            TestKit.setSimulatedPlayer(v.door(), level.dimension());
            UUID id = v.mgr().getVillageId();
            for (int i = 0; i < 2 && reg.isVillageDormantOrReconciling(id); i++) reg.debugSetDormant(level, id, false);
            return Optional.of(v);
        }
        TestKit.setHeadless(true);
        BehaviourTests.forceChunks(level, VILLAGE_X, VILLAGE_Z);
        int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, VILLAGE_X, VILLAGE_Z);
        ScenarioBuilder.VillageResult r = ScenarioBuilder.buildVillage(level, new BlockPos(VILLAGE_X, feetY, VILLAGE_Z), feetY, 0);
        VillageManager mgr = r.manager();
        if (mgr == null) {
            helper.fail("forge test village was not created");
            return Optional.empty();
        }
        TestKit.setSimulatedPlayer(r.townHallDoor(), level.dimension());
        // a forge east of the storage room: Towntopia's own forge furniture (a furnace and an anvil)
        ScenarioBuilder.Room forge = ScenarioBuilder.buildRoom(level, VILLAGE_X + 12, feetY, VILLAGE_Z + 14, ScenarioBuilder.RoomFurniture.FORGE);
        ItemStack token = new ItemStack(ModItems.FORGE.get());
        VillageTokenBinding.bind(token, mgr);
        forge.frame().setItem(token, false);
        mgr.rebuildNow(level);
        NpcEntity smith = ScenarioBuilder.spawnVillager(level, mgr, r.townHallDoor().north(3), Profession.BLACKSMITH);
        NpcEntity miner = ScenarioBuilder.spawnVillager(level, mgr, r.townHallDoor().north(4), Profession.MINER);
        ForgeVillage made = new ForgeVillage(mgr, r.townHallDoor(), smith.getNpcId(), miner.getNpcId());
        shared = made;
        return Optional.of(made);
    }

    @Nullable
    private static NpcEntity body(GameTestHelper helper, VillageManager mgr, UUID id) {
        VillagerModel model = mgr.getModel(id);
        NpcEntity b = model == null ? null : model.puppet();
        return b != null && !b.isRemoved() && helper.getLevel().getEntity(b.getId()) == b ? b : null;
    }

    @Nullable
    private static ForgeBuilding forge(ForgeVillage v) {
        for (Building b : v.mgr().getBuildings()) {
            if (b instanceof ForgeBuilding f && f.isValid()) return f;
        }
        return null;
    }

    /** Runs {@code then} once the smith's and miner's bodies are in the level and the village is awake with its rooms. */
    private static void whenLoaded(GameTestHelper helper, Checks c, ForgeVillage v, int ticks, Consumer<Scene> then) {
        NpcEntity smith = body(helper, v.mgr(), v.smithId());
        NpcEntity miner = body(helper, v.mgr(), v.minerId());
        // the storage room's owner comes from its frame entity, back in Towntopia's frame registry a little after the
        // chunks reload: until then its chests' items don't count for the village
        boolean storage = v.mgr().getBuildings().stream()
            .anyMatch(b -> b instanceof StorageBuilding s && s.isValid() && s.owner(helper.getLevel()) != null);
        ForgeBuilding forge = forge(v);
        boolean awake = !VillageManagerRegistry.get(helper.getLevel().getServer()).isVillageDormantOrReconciling(v.mgr().getVillageId());
        if (smith != null && miner != null && storage && forge != null && awake) {
            then.accept(new Scene(helper, c, v, smith, miner));
            return;
        }
        if (ticks <= 0) {
            c.check("smith and miner in the level, the village awake, storage (with its owner) and forge valid", false,
                "smith " + smith + ", miner " + miner + ", storage " + storage + ", forge " + forge + ", awake " + awake);
            BehaviourTests.finish(helper, c);
            return;
        }
        LoadWait.notYet();
        helper.runAfterDelay(1, () -> whenLoaded(helper, c, v, ticks - 1, then));
    }

    /**
     * The forge village's miner, once it is in the level and the village is awake: {@code MiningTests} reuse this village
     * (no village of their own). The caller undoes whatever it changes on the miner.
     */
    static void withMiner(GameTestHelper helper, Checks c, Consumer<NpcEntity> then) {
        ForgeVillage v = village(helper).orElse(null);
        if (v == null) return;
        whenLoaded(helper, c, v, 200, s -> then.accept(s.miner));
    }

    // --- one test's scene --------------------------------------------------------------------------------------------

    /** What one test registered or changed; {@link #cleanUp} undoes it (also when the test fails or times out). */
    private static final class Scene {
        final GameTestHelper helper;
        final Checks c;
        final ForgeVillage v;
        final NpcEntity smith;
        final NpcEntity miner;
        final ServerLevel level;
        final List<Subscription> subs = new ArrayList<>();
        final List<HookRegistration> hooks = new ArrayList<>();
        final List<Runnable> undo = new ArrayList<>();
        final long dayBefore;
        /** The smith level the planner sees (an after-hook answers it). */
        int smithLevel = 20;
        boolean ended;

        Scene(GameTestHelper helper, Checks c, ForgeVillage v, NpcEntity smith, NpcEntity miner) {
            this.helper = helper;
            this.c = c;
            this.v = v;
            this.smith = smith;
            this.miner = miner;
            this.level = helper.getLevel();
            this.dayBefore = this.level.getDayTime();
        }

        Activity.Context ctx(NpcEntity npc) {
            VillagerModel m = this.v.mgr().getModel(npc.getNpcId());
            return new Activity.Context(m.brain(), m, this.v.mgr(), this.level, npc);
        }

        void feed() {
            for (UUID id : List.of(this.v.smithId(), this.v.minerId())) {
                VillagerModel m = this.v.mgr().getModel(id);
                if (m != null) m.brain().needs().hunger = 100F;
            }
        }

        void cleanUp() {
            if (this.ended) return;
            this.ended = true;
            this.subs.forEach(Subscription::remove);
            this.hooks.forEach(HookRegistration::remove);
            this.undo.forEach(Runnable::run);
            this.level.setDayTime(this.dayBefore);
            empty(this.smith);
            empty(this.miner);
            clearStorage(this.level, this.v.mgr());
        }

        void end() {
            cleanUp();
            BehaviourTests.finish(this.helper, this.c);
        }
    }

    private static void scene(GameTestHelper helper, String name, Consumer<Scene> body) {
        Checks c = new Checks(name);
        ForgeVillage v = village(helper).orElse(null);
        if (v == null) return;
        whenLoaded(helper, c, v, 200, s -> {
            helper.testInfo.addListener(new GameTestListener() {
                @Override
                public void testStructureLoaded(GameTestInfo info) {
                }

                @Override
                public void testPassed(GameTestInfo info, GameTestRunner runner) {
                    s.cleanUp();
                }

                @Override
                public void testFailed(GameTestInfo info, GameTestRunner runner) {
                    s.cleanUp();
                }

                @Override
                public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner runner) {
                }
            });
            empty(s.smith);
            empty(s.miner);
            clearStorage(s.level, v.mgr());
            s.feed();
            UUID smithBrain = v.smithId();
            s.hooks.add(TownKitHooks.after(OWNER, K_SKILL, call -> {
                VillagerModel m = v.mgr().getModel(smithBrain);
                if (m != null && call.self() == m.brain()) call.setReturnValue(s.smithLevel);
            }));
            try {
                body.accept(s);
            } catch (GameTestAssertException e) {
                throw e;
            } catch (RuntimeException e) {
                TownKit.LOG.error("[TownKit test] {} threw", name, e);
                c.check("no exception", false, e);
                s.end();
            }
        });
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static boolean hooksOff() {
        return RewrittenSites.GLOBAL.hooksOff();
    }

    private static void empty(NpcEntity npc) {
        InventoryManager inv = npc.getInventory();
        if (inv != null) {
            for (int i = 0; i < InventoryManager.SIZE; i++) inv.setSlot(i, ItemStack.EMPTY);
        }
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND)) {
            npc.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void clearStorage(ServerLevel level, VillageManager mgr) {
        for (Building b : mgr.getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid()) continue;
            for (StorageInventory inv : s.inventories(level)) {
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack in = inv.peek(i);
                    if (!in.isEmpty()) inv.take(i, in.getCount());
                }
            }
            s.invalidateContentIndex();
        }
    }

    private static ItemStack bound(Scene s, Item item, int count) {
        ItemStack st = new ItemStack(item, count);
        VillageTokenBinding.bind(st, s.v.mgr());
        return st;
    }

    /** {@code count} of {@code item}, bound to the village, into the storage room's chests. */
    private static void stock(Scene s, Item item, int count) {
        int before = count(s, s.smith, item);
        int wanted = count;
        List<String> where = new ArrayList<>();
        while (count > 0) {
            int n = Math.min(count, item.getDefaultMaxStackSize());
            ItemStack st = bound(s, item, n);
            boolean in = false;
            for (Building b : s.v.mgr().getBuildings()) {
                if (in || !(b instanceof StorageBuilding sb) || !sb.isValid()) continue;
                for (StorageInventory inv : sb.inventories(s.level)) {
                    if (inv.size() >= 9 && inv.insert(st, true).isEmpty()) {
                        ItemStack left = inv.insert(st, false);
                        sb.invalidateContentIndex();
                        where.add(inv.key() + " left " + left.getCount());
                        in = true;
                        break;
                    }
                }
            }
            if (!in) {
                int put = ScenarioBuilder.stockStorage(s.level, s.v.mgr(), st);
                where.add("stockStorage " + put);
                if (put <= 0) {
                    s.c.check("stocked " + item, false, count + " left");
                    return;
                }
            }
            count -= n;
        }
        int after = count(s, s.smith, item);
        if (after != before + wanted) s.c.check("stocked " + item + " countable", false, before + " -> " + after + " via " + where);
    }

    private static int count(Scene s, NpcEntity npc, Item item) {
        int n = 0;
        InventoryManager inv = npc.getInventory();
        if (inv != null) {
            for (int i = 0; i < InventoryManager.SIZE; i++) {
                if (inv.getSlot(i).is(item)) n += inv.getSlot(i).getCount();
            }
        }
        for (Building b : s.v.mgr().getBuildings()) {
            if (b instanceof StorageBuilding st && st.isValid()) n += st.countItem(s.level, item);
        }
        return n;
    }

    /** One job of the smith's plan: what it makes and why. */
    private record Job(@Nullable Item item, String reason) {
    }

    /** What Towntopia's planner queues for the smith right now ({@code BlacksmithActivity.plan}, package-private). */
    private static List<Job> plan(Scene s) {
        try {
            Method plan = BlacksmithActivity.class.getDeclaredMethod("plan", Activity.Context.class, float.class, ForgeBuilding.class);
            plan.setAccessible(true);
            List<?> jobs = (List<?>) plan.invoke(null, s.ctx(s.smith), 0F, forge(s.v));
            List<Job> out = new ArrayList<>();
            for (Object job : jobs) {
                Method recipe = job.getClass().getDeclaredMethod("recipe");
                recipe.setAccessible(true);
                Method reason = job.getClass().getDeclaredMethod("reason");
                reason.setAccessible(true);
                Object r = recipe.invoke(job);
                Item item = null;
                if (r != null) {
                    Method result = r.getClass().getDeclaredMethod("result");
                    result.setAccessible(true);
                    item = (Item) result.invoke(r);
                }
                out.add(new Job(item, (String) reason.invoke(job)));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't run the smith's planner", e);
        }
    }

    private static boolean plans(List<Job> jobs, Item item) {
        return jobs.stream().anyMatch(j -> j.item() == item);
    }

    /** Towntopia's forge rescans its room's working blocks (anvil, furnaces, smithing tables). */
    private static void rescan(Scene s) {
        ForgeBuilding f = forge(s.v);
        if (f == null) return;
        try {
            Method scan = ForgeBuilding.class.getDeclaredMethod("scanFunctionalBlocks", ServerLevel.class);
            scan.setAccessible(true);
            scan.invoke(f, s.level);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't rescan the forge", e);
        }
    }

    /** Whether the plan upgrades a pickaxe (Towntopia's reason "upgrading a pickaxe"). */
    private static boolean upgrades(List<Job> jobs) {
        return jobs.stream().anyMatch(j -> j.reason().equals("upgrading a pickaxe"));
    }

    private static Item steelPick() {
        return TestBlocks.TEST_STEEL_PICKAXE.get();
    }

    private static Item steelSword() {
        return TestBlocks.TEST_STEEL_SWORD.get();
    }

    private static Item steelAxe() {
        return TestBlocks.TEST_STEEL_AXE.get();
    }

    /** A villager of {@code profession} for one test (forgotten again at its end). */
    private static NpcEntity spawn(Scene s, Profession profession, int north) {
        NpcEntity npc = ScenarioBuilder.spawnVillager(s.level, s.v.mgr(), s.v.door().north(north), profession);
        s.undo.add(() -> {
            s.v.mgr().removeVillager(npc.getNpcId());
            if (!npc.isRemoved()) npc.discard();
        });
        return npc;
    }

    private static boolean between(long low, long mid, long high) {
        return low < mid && mid < high;
    }

    private static Item steelChest() {
        return TestBlocks.TEST_STEEL_CHESTPLATE.get();
    }

    /** The test material and tier (steel between iron and diamond, smith level 10). */
    private static void steel(Scene s) {
        s.subs.add(TownKitForge.material(OWNER, STEEL, STEEL_ITEMS));
        s.subs.add(TownKitForge.tier(OWNER, STEEL, TierPlace.after(IRON).skill(10)));
    }

    private static ForgeRecipe steelPickRecipe(boolean smithing) {
        return ForgeRecipe.builder(steelPick()).cost(STEEL, 3).cost(WOOD, 1).gear(STEEL, GearSlot.PICKAXE).smithing(smithing).build();
    }

    /** Hooks off: the API answers, Towntopia's smith keeps its own lists; the test checks that and ends. */
    private static boolean endIfHooksOff(Scene s, Item gear) {
        return endIfHooksOff(s, gear, STEEL);
    }

    private static boolean endIfHooksOff(Scene s, Item gear, ResourceLocation tier) {
        if (!hooksOff()) return false;
        s.c.check("hooks off: tierOf answers the registered tier", TownKitForge.tierOf(gear).equals(Optional.of(tier)),
            TownKitForge.tierOf(gear));
        s.c.check("hooks off: the overlay is off for Towntopia", !ForgeHooks.on() && ForgeHooks.attachedKeys().isEmpty(), ForgeHooks.attachedKeys());
        s.c.check("hooks off: Towntopia rates the addon gear as unknown", ForgeOrderBook.tierOf(gear) == 0, ForgeOrderBook.tierOf(gear));
        s.end();
        return true;
    }

    // --- tests -------------------------------------------------------------------------------------------------------

    /**
     * Goal 3: a miner with an iron pickaxe gets the addon pickaxe (between iron and diamond) next, and the diamond one
     * only after that; the addon pickaxe is rated between them and the miner may use it.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_forge_upgrade")
    public static void minerUpgradesThroughModdedTier(GameTestHelper helper) {
        scene(helper, "minerUpgradesThroughModdedTier", s -> {
            steel(s);
            s.subs.add(TownKitForge.addRecipe(OWNER, steelPickRecipe(false)));
            if (endIfHooksOff(s, steelPick())) return;
            s.c.check("the overlay is on", ForgeHooks.on(), ForgeHooks.attachedKeys());
            s.c.check("the steel pickaxe is rated at its tier, between iron and diamond",
                ForgeOrderBook.tierOf(Items.IRON_PICKAXE) < ForgeOrderBook.tierOf(steelPick())
                    && ForgeOrderBook.tierOf(steelPick()) < ForgeOrderBook.tierOf(Items.DIAMOND_PICKAXE),
                ForgeOrderBook.tierOf(Items.IRON_PICKAXE) + " < " + ForgeOrderBook.tierOf(steelPick()) + " < " + ForgeOrderBook.tierOf(Items.DIAMOND_PICKAXE));
            s.c.check("the miner's quality puts it between iron and diamond",
                PickaxeUtils.quality(new ItemStack(Items.IRON_PICKAXE)) < PickaxeUtils.quality(new ItemStack(steelPick()))
                    && PickaxeUtils.quality(new ItemStack(steelPick())) < PickaxeUtils.quality(new ItemStack(Items.DIAMOND_PICKAXE)),
                PickaxeUtils.quality(new ItemStack(steelPick())));
            s.c.check("the miner may use it (diamond picks allowed)", MinerActivity.allowedPickaxes(s.ctx(s.miner)).contains(steelPick()),
                MinerActivity.allowedPickaxes(s.ctx(s.miner)));
            s.miner.getInventory().setSlot(0, bound(s, Items.IRON_PICKAXE, 1));
            stock(s, Items.AMETHYST_SHARD, 12);
            stock(s, Items.OAK_LOG, 16);
            stock(s, Items.DIAMOND, 16);
            // level 20: the steel tier (level 10) but not diamond (25)
            List<Job> first = plan(s);
            s.c.check("level 20, iron pickaxe: the smith makes the steel pickaxe", plans(first, steelPick()), first);
            s.c.check("level 20: no diamond pickaxe", !plans(first, Items.DIAMOND_PICKAXE), first);
            // the miner now holds the steel pickaxe: at level 20 it is the best the smith makes
            s.miner.getInventory().setSlot(0, bound(s, steelPick(), 1));
            List<Job> held = plan(s);
            s.c.check("steel pickaxe held: no pickaxe upgrade at level 20", !upgrades(held) && !plans(held, Items.IRON_PICKAXE)
                && !plans(held, Items.STONE_PICKAXE), held);
            // later, level 30: diamond next
            s.smithLevel = 30;
            List<Job> later = plan(s);
            s.c.check("level 30, steel pickaxe: the smith makes the diamond pickaxe", plans(later, Items.DIAMOND_PICKAXE), later);
            s.c.check("level 30: no second steel pickaxe", !plans(later, steelPick()), later);
            s.end();
        });
    }

    /** A guard wears addon armour rated above iron, and the smith makes it as the guard's armour upgrade. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_forge_guard")
    public static void guardGetsModdedArmour(GameTestHelper helper) {
        scene(helper, "guardGetsModdedArmour", s -> {
            steel(s);
            s.subs.add(TownKitForge.addRecipe(OWNER, ForgeRecipe.builder(steelChest()).cost(STEEL, 8).gear(STEEL, GearSlot.CHESTPLATE).build()));
            if (endIfHooksOff(s, steelChest())) return;
            NpcEntity guard = ScenarioBuilder.spawnVillager(s.level, s.v.mgr(), s.v.door().north(5), Profession.GUARD);
            s.undo.add(() -> {
                s.v.mgr().removeVillager(guard.getNpcId());
                if (!guard.isRemoved()) guard.discard();
            });
            s.c.check("guards may wear it", GuardArmorUtils.allowedArmorFor(EquipmentSlot.CHEST).contains(steelChest()),
                GuardArmorUtils.allowedArmorFor(EquipmentSlot.CHEST));
            s.c.check("rated above iron, below diamond", GuardArmorUtils.quality(new ItemStack(Items.IRON_CHESTPLATE))
                < GuardArmorUtils.quality(new ItemStack(steelChest())) && GuardArmorUtils.quality(new ItemStack(steelChest()))
                < GuardArmorUtils.quality(new ItemStack(Items.DIAMOND_CHESTPLATE)), GuardArmorUtils.quality(new ItemStack(steelChest())));
            guard.setItemSlot(EquipmentSlot.CHEST, bound(s, Items.IRON_CHESTPLATE, 1));
            stock(s, Items.AMETHYST_SHARD, 16);
            List<Job> jobs = plan(s);
            s.c.check("the smith makes the steel chestplate for the guard", plans(jobs, steelChest()), jobs);
            guard.getInventory().setSlot(0, bound(s, steelChest(), 1));
            EquipmentSlot worn = GuardArmorUtils.wearFromPack(guard);
            s.c.check("the guard puts it on", worn == EquipmentSlot.CHEST && guard.getItemBySlot(EquipmentSlot.CHEST).is(steelChest()),
                worn + " " + guard.getItemBySlot(EquipmentSlot.CHEST));
            s.end();
        });
    }

    /** A smithing-table recipe is made only while the forge has a smithing table, and orders say so. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_forge_smithing")
    public static void smithingUpgrade(GameTestHelper helper) {
        scene(helper, "smithingUpgrade", s -> {
            steel(s);
            s.subs.add(TownKitForge.addRecipe(OWNER, steelPickRecipe(true)));
            if (endIfHooksOff(s, steelPick())) return;
            ForgeBuilding forge = forge(s.v);
            s.c.check("the test forge has no smithing table", forge.getSmithingTablePos() == null, forge.getSmithingTablePos());
            s.miner.getInventory().setSlot(0, bound(s, Items.IRON_PICKAXE, 1));
            stock(s, Items.AMETHYST_SHARD, 12);
            stock(s, Items.OAK_LOG, 16);
            List<Job> without = plan(s);
            s.c.check("no smithing table: the steel pickaxe isn't made", !plans(without, steelPick()), without);
            BlacksmithActivity.OrderInfo info = BlacksmithActivity.orderInfo(steelPick());
            s.c.check("orders: made at the smithing table, smith level 10", info != null && info.smithingTable() && info.level() == 10, info);
            // a smithing table in the forge room
            BlockPos anvil = forge.getAnvilPos();
            BlockPos table = anvil.west();
            s.level.setBlock(table, Blocks.SMITHING_TABLE.defaultBlockState(), 3);
            s.undo.add(() -> {
                s.level.setBlock(table, Blocks.AIR.defaultBlockState(), 3);
                rescan(s);
            });
            rescan(s);
            ForgeBuilding again = forge(s.v);
            s.c.check("the forge has the smithing table now", again != null && again.getSmithingTablePos() != null, again);
            List<Job> with = plan(s);
            s.c.check("with a smithing table: the steel pickaxe is made", plans(with, steelPick()), with);
            s.end();
        });
    }

    /** An addon stock item can be ordered by players (Mayor stock orders) and the smith forges it. */
    @GameTest(template = "empty", timeoutTicks = 8000, batch = "townkit_forge_stock")
    public static void stockItemForged(GameTestHelper helper) {
        scene(helper, "stockItemForged", s -> {
            StockReserves sr = StockReserves.get(s.level.getServer());
            UUID village = s.v.mgr().getVillageId();
            long now = s.level.getGameTime();
            s.c.check("before: an order for chains is refused", !sr.addRow(village, Items.CHAIN, true, false, 1, null, now), "");
            s.subs.add(TownKitForge.material(OWNER, STEEL, STEEL_ITEMS));
            s.subs.add(TownKitForge.addRecipe(OWNER, ForgeRecipe.builder(Items.CHAIN).count(2).cost(STEEL, 2).build()));
            if (hooksOff()) {
                s.c.check("hooks off: still refused", !sr.addRow(village, Items.CHAIN, true, false, 1, null, now), "");
                s.c.check("hooks off: not a forge item to Towntopia", !StockReserves.isForgeItem(Items.CHAIN), "");
                s.end();
                return;
            }
            s.c.check("the smith makes chains", StockReserves.producerOf(Items.CHAIN) == Profession.BLACKSMITH, StockReserves.producerOf(Items.CHAIN));
            s.c.check("chains are in the forge item list", StockReserves.forgeItems().contains(Items.CHAIN), "");
            s.c.check("an order for 2 chains is accepted", sr.addRow(village, Items.CHAIN, true, false, 2, null, now), "");
            s.undo.add(() -> {
                for (StockReserves.Row row : List.copyOf(sr.rows(village))) {
                    if (row.item == Items.CHAIN) sr.removeRow(village, row.id, s.level.getGameTime());
                }
            });
            stock(s, Items.AMETHYST_SHARD, 4);
            List<Job> jobs = plan(s);
            s.c.check("the order is planned", jobs.stream().anyMatch(j -> j.item() == Items.CHAIN && j.reason().contains("ordered")),
                jobs + "; wants " + sr.wants(s.v.mgr(), s.level, Profession.BLACKSMITH, s.smith.getNpcId()).stream()
                    .map(w -> w.item() + "x" + w.units()).toList() + "; amethyst " + count(s, s.smith, Items.AMETHYST_SHARD)
                    + "; order info " + BlacksmithActivity.orderInfo(Items.CHAIN) + "; smith activity "
                    + s.v.mgr().getModel(s.smith.getNpcId()).brain().currentActivity());
            int chainsBefore = count(s, s.smith, Items.CHAIN);
            s.level.setDayTime(1000L);
            BuildingTests.waitFor(helper, 20, 7000, () -> {
                s.feed();
                return count(s, s.smith, Items.CHAIN) > chainsBefore;
            }, () -> {
                s.c.check("the smith forged chains", count(s, s.smith, Items.CHAIN) >= chainsBefore + 2, count(s, s.smith, Items.CHAIN));
                s.c.check("from the amethyst (steel)", count(s, s.smith, Items.AMETHYST_SHARD) <= 2, count(s, s.smith, Items.AMETHYST_SHARD));
                s.end();
            });
        });
    }

    /**
     * The addon tier is removed while the miner holds its pickaxe. The pickaxe is rated as Towntopia rates
     * an unknown tool again (tier 0), the API no longer gives it a tier, the planner runs without a throw and wants a real
     * pickaxe for the miner.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_forge_removed")
    public static void removedTierFallsBack(GameTestHelper helper) {
        scene(helper, "removedTierFallsBack", s -> {
            s.subs.add(TownKitForge.material(OWNER, STEEL, STEEL_ITEMS));
            Subscription tier = TownKitForge.tier(OWNER, STEEL, TierPlace.after(IRON).skill(10));
            s.subs.add(tier);
            s.subs.add(TownKitForge.addRecipe(OWNER, steelPickRecipe(false)));
            if (endIfHooksOff(s, steelPick())) return;
            s.miner.getInventory().setSlot(0, bound(s, steelPick(), 1));
            stock(s, Items.COBBLESTONE, 16);
            stock(s, Items.OAK_LOG, 16);
            List<Job> before = plan(s);
            s.c.check("with the tier: the steel pickaxe beats stone, no pickaxe upgrade (a spare may be made)", !upgrades(before), before);
            tier.remove();
            s.c.check("the API no longer rates it", TownKitForge.tierOf(steelPick()).isEmpty(), TownKitForge.tierOf(steelPick()));
            s.c.check("Towntopia rates it as unknown (0)", ForgeOrderBook.tierOf(steelPick()) == 0, ForgeOrderBook.tierOf(steelPick()));
            List<Job> after = plan(s);
            s.c.check("without the tier: the smith upgrades the miner to stone", after.stream()
                .anyMatch(j -> j.item() == Items.STONE_PICKAXE && j.reason().startsWith("upgrading")), after);
            s.c.check("the steel recipe is not made (its tier is gone)", !plans(after, steelPick()), after);
            s.end();
        });
    }

    /**
     * Between-tier swords and axes are rated strictly between iron and diamond in the ratings
     * villagers choose tools by, so a guard and a lumberjack holding iron take the addon tool, and diamond over it.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_forge_tool_choice")
    public static void villagersPreferModdedTierTools(GameTestHelper helper) {
        scene(helper, "villagersPreferModdedTierTools", s -> {
            steel(s);
            s.subs.add(TownKitForge.addRecipe(OWNER, ForgeRecipe.builder(steelSword()).cost(STEEL, 2).cost(WOOD, 1).gear(STEEL, GearSlot.SWORD).build()));
            s.subs.add(TownKitForge.addRecipe(OWNER, ForgeRecipe.builder(steelAxe()).cost(STEEL, 3).cost(WOOD, 1).gear(STEEL, GearSlot.AXE).build()));
            if (endIfHooksOff(s, steelSword())) return;
            ItemStack ironSword = new ItemStack(Items.IRON_SWORD);
            ItemStack steelSword = new ItemStack(steelSword());
            ItemStack diamondSword = new ItemStack(Items.DIAMOND_SWORD);
            s.c.check("guards' sword rating: iron < steel < diamond", between(GuardSwordUtils.quality(ironSword), GuardSwordUtils.quality(steelSword),
                GuardSwordUtils.quality(diamondSword)), GuardSwordUtils.quality(ironSword) + " " + GuardSwordUtils.quality(steelSword) + " "
                + GuardSwordUtils.quality(diamondSword));
            s.c.check("general tool rating: iron < steel < diamond", between(ToolSelectionHelper.quality(ironSword),
                ToolSelectionHelper.quality(steelSword), ToolSelectionHelper.quality(diamondSword)), ToolSelectionHelper.quality(steelSword));
            ItemStack ironAxe = new ItemStack(Items.IRON_AXE);
            ItemStack steelAxe = new ItemStack(steelAxe());
            ItemStack diamondAxe = new ItemStack(Items.DIAMOND_AXE);
            s.c.check("lumberjacks' axe rating: iron < steel < diamond", between(AxeUtils.quality(ironAxe), AxeUtils.quality(steelAxe),
                AxeUtils.quality(diamondAxe)), AxeUtils.quality(ironAxe) + " " + AxeUtils.quality(steelAxe) + " " + AxeUtils.quality(diamondAxe));
            s.c.check("axe speed: iron < steel < diamond", ToolSpeed.of(ironAxe) < ToolSpeed.of(steelAxe)
                && ToolSpeed.of(steelAxe) < ToolSpeed.of(diamondAxe), ToolSpeed.of(steelAxe));

            NpcEntity guard = spawn(s, Profession.GUARD, 5);
            Set<Item> swords = GuardSettings.of(guard).getAllowedSwords();
            s.c.check("the guard may use the steel sword", swords.contains(steelSword()), swords);
            InventoryManager gi = guard.getInventory();
            gi.setSlot(0, bound(s, Items.IRON_SWORD, 1));
            gi.setSlot(1, bound(s, steelSword(), 1));
            int pick = GuardSwordUtils.findBestUsableSwordSlot(gi, swords);
            s.c.check("iron or steel: the guard takes steel", pick == 1, pick);
            gi.setSlot(0, bound(s, Items.DIAMOND_SWORD, 1));
            pick = GuardSwordUtils.findBestUsableSwordSlot(gi, swords);
            s.c.check("steel or diamond: the guard takes diamond", pick == 0, pick);

            NpcEntity lumberjack = spawn(s, Profession.LUMBERJACK, 6);
            Set<Item> axes = LumberjackSettings.of(lumberjack).getAllowedAxes();
            s.c.check("the lumberjack may use the steel axe", axes.contains(steelAxe()), axes);
            InventoryManager li = lumberjack.getInventory();
            li.setSlot(0, bound(s, Items.IRON_AXE, 1));
            li.setSlot(1, bound(s, steelAxe(), 1));
            pick = AxeUtils.findChoppingAxeSlot(li, axes);
            s.c.check("iron or steel: the lumberjack takes steel", pick == 1, pick);
            li.setSlot(0, bound(s, Items.DIAMOND_AXE, 1));
            pick = AxeUtils.findChoppingAxeSlot(li, axes);
            s.c.check("steel or diamond: the lumberjack takes diamond", pick == 0, pick);
            s.end();
        });
    }

    /**
     * A tier placed after stone sits between stone and iron in Towntopia's own planner, so the
     * stone and iron constants it compares are really ladder positions: stone -> the addon pickaxe -> iron.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_forge_after_stone")
    public static void tierAfterStoneUpgradesBetween(GameTestHelper helper) {
        scene(helper, "tierAfterStoneUpgradesBetween", s -> {
            ResourceLocation bronze = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_bronze");
            s.subs.add(TownKitForge.material(OWNER, STEEL, STEEL_ITEMS));
            s.subs.add(TownKitForge.tier(OWNER, bronze, TierPlace.after(ResourceLocation.fromNamespaceAndPath("towntopia", "stone"))));
            s.subs.add(TownKitForge.addRecipe(OWNER, ForgeRecipe.builder(steelPick()).cost(STEEL, 3).cost(WOOD, 1).gear(bronze, GearSlot.PICKAXE).build()));
            if (endIfHooksOff(s, steelPick(), bronze)) return;
            s.c.check("rated between stone and iron", ForgeOrderBook.tierOf(Items.STONE_PICKAXE) < ForgeOrderBook.tierOf(steelPick())
                && ForgeOrderBook.tierOf(steelPick()) < ForgeOrderBook.tierOf(Items.IRON_PICKAXE),
                ForgeOrderBook.tierOf(Items.STONE_PICKAXE) + " < " + ForgeOrderBook.tierOf(steelPick()) + " < " + ForgeOrderBook.tierOf(Items.IRON_PICKAXE));
            s.miner.getInventory().setSlot(0, bound(s, Items.STONE_PICKAXE, 1));
            stock(s, Items.AMETHYST_SHARD, 12);
            stock(s, Items.OAK_LOG, 16);
            List<Job> first = plan(s);
            s.c.check("stone pickaxe, no iron: the smith upgrades to the addon pickaxe", first.stream()
                .anyMatch(j -> j.item() == steelPick() && j.reason().equals("upgrading a pickaxe")), first);
            s.miner.getInventory().setSlot(0, bound(s, steelPick(), 1));
            stock(s, Items.COBBLESTONE, 16);
            List<Job> held = plan(s);
            s.c.check("addon pickaxe held: no upgrade to stone", !upgrades(held), held);
            stock(s, Items.IRON_INGOT, 32);
            List<Job> later = plan(s);
            s.c.check("with iron: the smith upgrades it to iron", later.stream()
                .anyMatch(j -> j.item() == Items.IRON_PICKAXE && j.reason().equals("upgrading a pickaxe")), later);
            s.end();
        });
    }

    /** Addon gear can be ordered through a Mayor stock order, and the smith forges it. */
    @GameTest(template = "empty", timeoutTicks = 8000, batch = "townkit_forge_gear_order")
    public static void moddedGearOrderedAndForged(GameTestHelper helper) {
        scene(helper, "moddedGearOrderedAndForged", s -> {
            StockReserves sr = StockReserves.get(s.level.getServer());
            UUID village = s.v.mgr().getVillageId();
            long now = s.level.getGameTime();
            steel(s);
            s.subs.add(TownKitForge.addRecipe(OWNER, steelPickRecipe(false)));
            if (hooksOff()) {
                s.c.check("hooks off: an order for the steel pickaxe is refused", !sr.addRow(village, steelPick(), true, false, 1, null, now), "");
                endIfHooksOff(s, steelPick());
                return;
            }
            s.c.check("addon gear is a forge item", StockReserves.isForgeItem(steelPick()) && StockReserves.forgeItems().contains(steelPick()), "");
            s.c.check("the smith makes it", StockReserves.producerOf(steelPick()) == Profession.BLACKSMITH, StockReserves.producerOf(steelPick()));
            s.c.check("an order for a steel pickaxe is accepted", sr.addRow(village, steelPick(), true, false, 1, null, now), "");
            s.undo.add(() -> {
                for (StockReserves.Row row : List.copyOf(sr.rows(village))) {
                    if (row.item == steelPick()) sr.removeRow(village, row.id, s.level.getGameTime());
                }
            });
            // the miner is set for life (diamond): the pickaxe is made only because it was ordered
            s.miner.getInventory().setSlot(0, bound(s, Items.DIAMOND_PICKAXE, 1));
            stock(s, Items.AMETHYST_SHARD, 6);
            stock(s, Items.OAK_LOG, 8);
            List<Job> jobs = plan(s);
            s.c.check("the order is planned", jobs.stream().anyMatch(j -> j.item() == steelPick() && j.reason().contains("ordered")), jobs);
            int before = count(s, s.smith, steelPick());
            s.level.setDayTime(1000L);
            BuildingTests.waitFor(helper, 20, 7000, () -> {
                s.feed();
                return count(s, s.smith, steelPick()) > before;
            }, () -> {
                s.c.check("the smith forged the steel pickaxe", count(s, s.smith, steelPick()) == before + 1, count(s, s.smith, steelPick()));
                s.c.check("from the amethyst (steel)", count(s, s.smith, Items.AMETHYST_SHARD) <= 3, count(s, s.smith, Items.AMETHYST_SHARD));
                s.end();
            });
        });
    }

    /** No registrations: nothing attached, every bridge hands back Towntopia's value, Towntopia's own plan. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_forge_none")
    public static void noAddonUnchanged(GameTestHelper helper) {
        scene(helper, "noAddonUnchanged", s -> {
            s.c.check("no forge hook attached", ForgeHooks.attachedKeys().isEmpty(), ForgeHooks.attachedKeys());
            s.c.check("the overlay is off", !ForgeHooks.on() && !ForgeHooks.smeltOn(), "");
            Object marker = new Object();
            s.c.check("the bridges hand back what they get", ForgeBridges.onTier(3) == 3 && ForgeBridges.onTop(4) == 4
                && ForgeBridges.onSize(5) == 5 && ForgeBridges.onNoTable(3) == 3 && ForgeBridges.onMaxTier(30, 3) == 3
                && ForgeBridges.onRecipeTier(2) == 2 && ForgeBridges.onTierOf(2, Items.IRON_PICKAXE) == 2
                && ForgeBridges.onShield(marker) == marker && ForgeBridges.onBucket(marker) == marker
                && SmeltBridges.onPick(Items.RAW_COPPER) == Items.RAW_COPPER && SmeltBridges.onCopperIngot(Items.COPPER_INGOT) == Items.COPPER_INGOT
                && SmeltBridges.onCarried(5, marker) == 5 && SmeltBridges.onStoredRaw(7, marker, Items.RAW_COPPER) == 7
                && SmeltBridges.onCount(marker, Items.RAW_COPPER, 3) == 3 && SmeltBridges.onConsume(true, marker, Items.RAW_COPPER, 1), "");
            s.c.check("Towntopia's tiers", ForgeOrderBook.tierOf(Items.IRON_PICKAXE) == 2 && ForgeOrderBook.tierOf(Items.NETHERITE_HELMET) == 4
                && ForgeOrderBook.tierOf(steelPick()) == 0, "");
            s.c.check("only Towntopia's forge items", !StockReserves.isForgeItem(Items.CHAIN) && StockReserves.isForgeItem(Items.BUCKET), "");
            s.miner.getInventory().setSlot(0, bound(s, Items.STONE_PICKAXE, 1));
            stock(s, Items.IRON_INGOT, 32);
            stock(s, Items.OAK_LOG, 16);
            List<Job> jobs = plan(s);
            s.c.check("Towntopia's plan: an iron pickaxe for the miner", plans(jobs, Items.IRON_PICKAXE), jobs);
            s.end();
        });
    }

    /** Hooks off (config): the API answers, Towntopia's smith keeps its lists. Hooks on: every smith site is rewritten. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_forge_hooks")
    public static void hooksOffUsesTowntopia(GameTestHelper helper) {
        Checks c = new Checks("hooksOffUsesTowntopia");
        List<Subscription> subs = new ArrayList<>();
        subs.add(TownKitForge.material(OWNER, STEEL, STEEL_ITEMS));
        subs.add(TownKitForge.tier(OWNER, STEEL, TierPlace.after(IRON)));
        subs.add(TownKitForge.addRecipe(OWNER, steelPickRecipe(false)));
        subs.add(TownKitForge.addRecipe(OWNER, ForgeRecipe.builder(Items.CHAIN).cost(STEEL, 2).build()));
        try {
            c.check("tierOf answers the registered tier", TownKitForge.tierOf(steelPick()).equals(Optional.of(STEEL)), TownKitForge.tierOf(steelPick()));
            c.check("tierOf answers Towntopia's tiers", TownKitForge.tierOf(Items.DIAMOND_SWORD).equals(Optional.of(ResourceLocation.fromNamespaceAndPath("towntopia", "diamond")))
                && TownKitForge.tierOf(Items.LEATHER_BOOTS).equals(Optional.of(WOOD)) && TownKitForge.tierOf(Items.APPLE).isEmpty(), "");
            String smith = "village/villagers/blacksmith/behavior/BlacksmithActivity";
            if (hooksOff()) {
                c.check("hooks off: the overlay is off", !ForgeHooks.on() && ForgeHooks.attachedKeys().isEmpty(), ForgeHooks.attachedKeys());
                c.check("hooks off: Towntopia rates the pickaxe as unknown", ForgeOrderBook.tierOf(steelPick()) == 0, "");
                c.check("hooks off: chains aren't forge items", !StockReserves.isForgeItem(Items.CHAIN), "");
                c.check("hooks off: nothing rewritten", !RewrittenWorkSites.GLOBAL.loaded(smith), "");
            } else {
                c.check("hooks on: the overlay is on", ForgeHooks.on(), "");
                c.check("hooks on: chains are forge items", StockReserves.isForgeItem(Items.CHAIN), "");
                WorkSites sites = WorkSites.load();
                List<String> missing = new ArrayList<>();
                for (String owner : List.of(smith, smith + "$Planner", smith + "$Offline", smith + "$Mat", "village/villagers/blacksmith/ForgeOrderBook",
                        "village/villagers/blacksmith/ForgeOrderBook$Stock")) {
                    missing.addAll(RewrittenWorkSites.GLOBAL.missing(sites, owner));
                }
                c.check("hooks on: every audited smith site is rewritten", missing.isEmpty(), missing);
            }
        } finally {
            subs.forEach(Subscription::remove);
        }
        c.check("all removed: the overlay is off again", !ForgeHooks.on() && ForgeHooks.attachedKeys().isEmpty(), ForgeHooks.attachedKeys());
        c.finish(helper);
    }

    // --- smelting -------------------------------------------------------------------------------------------

    /** The test raw and what it smelts into (items nothing else in these tests uses). */
    private static final Item RAW_TEST = Items.PRISMARINE_SHARD;
    private static final Item INGOT_TEST = Items.PRISMARINE_CRYSTALS;

    /** Towntopia's {@code BlacksmithActivity.hasSpareToSmelt}: would the smith go smelting spare ore now. */
    private static boolean hasSpareToSmelt(Scene s) {
        try {
            Method m = BlacksmithActivity.class.getDeclaredMethod("hasSpareToSmelt", Activity.Context.class, ForgeBuilding.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, s.ctx(s.smith), forge(s.v));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't ask the smith about spare ore", e);
        }
    }

    /** Sets the smith's "Smelting" setting ({@code smelt_spare_ore}) and puts it back at the scene's end. */
    private static void spareSetting(Scene s, boolean on) {
        try {
            Method settings = BlacksmithActivity.class.getDeclaredMethod("settings", Activity.Context.class);
            settings.setAccessible(true);
            ProfessionSettings ps = (ProfessionSettings) settings.invoke(null, s.ctx(s.smith));
            boolean before = ps.getBool("smelt_spare_ore");
            ps.setBool("smelt_spare_ore", on);
            s.undo.add(() -> ps.setBool("smelt_spare_ore", before));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't set the smith's smelting setting", e);
        }
    }

    /** Raw ore and coal in storage, a working hour, and the forge's furnace known. */
    private static void smeltingScene(Scene s, Item raw, int rawCount) {
        stock(s, raw, rawCount);
        stock(s, Items.COAL, 8);
        rescan(s);
        s.level.setDayTime(1000L);
    }

    /** Hooks off: Towntopia's smith still smelts {@code raw} into its own {@code ingot} (eight of them) and ignores the addon. */
    private static void towntopiaSmelts(Scene s, Item raw, Item ingot) {
        smeltingScene(s, raw, 8);
        BuildingTests.waitFor(s.helper, 20, 8500, () -> {
            s.feed();
            return count(s, s.smith, ingot) >= 8;
        }, () -> {
            s.c.check("hooks off: Towntopia smelts " + raw + " into " + ingot, count(s, s.smith, ingot) == 8, count(s, s.smith, ingot));
            s.c.check("hooks off: the addon ingot is never made", count(s, s.smith, INGOT_TEST) == 0, count(s, s.smith, INGOT_TEST));
            s.end();
        });
    }

    /**
     * An addon raw is smelted by the smith in the village like its spare raw copper: the same setting, batch and
     * fuel. Hooks off: the API answers and Towntopia's smith doesn't know the raw.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_forge_smelt_a")
    public static void smithSmeltsAddonRaw(GameTestHelper helper) {
        scene(helper, "smithSmeltsAddonRaw", s -> {
            s.subs.add(TownKitForge.smelt(OWNER, RAW_TEST, INGOT_TEST));
            smeltingScene(s, RAW_TEST, 10);
            if (hooksOff()) {
                s.c.check("hooks off: the smelting overlay is off", !ForgeHooks.smeltOn(), "");
                s.c.check("hooks off: Towntopia's smith has no spare ore to smelt", !hasSpareToSmelt(s), "");
                s.end();
                return;
            }
            s.c.check("the smelting overlay is on", ForgeHooks.smeltOn(), "");
            s.c.check("the smith has spare ore to smelt", hasSpareToSmelt(s), "");
            int coalBefore = count(s, s.smith, Items.COAL);
            BuildingTests.waitFor(helper, 20, 8500, () -> {
                s.feed();
                return count(s, s.smith, INGOT_TEST) >= 10;
            }, () -> {
                s.c.check("the smith smelted all ten raws", count(s, s.smith, INGOT_TEST) == 10, count(s, s.smith, INGOT_TEST));
                s.c.check("no raw left", count(s, s.smith, RAW_TEST) == 0, count(s, s.smith, RAW_TEST));
                s.c.check("it burned fuel", count(s, s.smith, Items.COAL) < coalBefore, count(s, s.smith, Items.COAL));
                s.end();
            });
        });
    }

    /** The "Smelting" setting off: the smith leaves addon raws alone, as it leaves its own. */
    @GameTest(template = "empty", timeoutTicks = 1500, batch = "townkit_forge_smelt_b")
    public static void spareSettingOffSkipsAddonRaw(GameTestHelper helper) {
        scene(helper, "spareSettingOffSkipsAddonRaw", s -> {
            s.subs.add(TownKitForge.smelt(OWNER, RAW_TEST, INGOT_TEST));
            smeltingScene(s, RAW_TEST, 10);
            spareSetting(s, true);
            boolean on = hasSpareToSmelt(s);
            s.c.check("setting on: the smith would smelt the addon raw (hooks off: it doesn't know it)", on == !hooksOff(), on);
            spareSetting(s, false);
            s.c.check("setting off: nothing to smelt", !hasSpareToSmelt(s), "");
            Object status;
            try {
                Method smeltSpare = BlacksmithActivity.class.getDeclaredMethod("smeltSpare", Activity.Context.class, ForgeBuilding.class);
                smeltSpare.setAccessible(true);
                status = smeltSpare.invoke(new BlacksmithActivity(), s.ctx(s.smith), forge(s.v));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("can't run the smith's spare-ore step", e);
            }
            s.c.check("setting off: the spare-ore step does nothing", status == null, status);
            helper.runAfterDelay(400, () -> {
                s.feed();
                s.c.check("setting off after 400 ticks: no ingot", count(s, s.smith, INGOT_TEST) == 0, count(s, s.smith, INGOT_TEST));
                s.c.check("setting off after 400 ticks: the raw is still there", count(s, s.smith, RAW_TEST) == 10, count(s, s.smith, RAW_TEST));
                s.end();
            });
        });
    }

    /**
     * Towntopia's raw copper still smelts to copper, and an addon raw in the same load to its own ingot: neither takes the
     * other's place.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_forge_smelt_c")
    public static void addonRawDoesNotBreakCopper(GameTestHelper helper) {
        scene(helper, "addonRawDoesNotBreakCopper", s -> {
            s.subs.add(TownKitForge.smelt(OWNER, RAW_TEST, INGOT_TEST));
            if (hooksOff()) {
                stock(s, RAW_TEST, 6);
                towntopiaSmelts(s, Items.RAW_COPPER, Items.COPPER_INGOT);
                return;
            }
            stock(s, Items.RAW_COPPER, 6);
            smeltingScene(s, RAW_TEST, 6);
            BuildingTests.waitFor(helper, 20, 8500, () -> {
                s.feed();
                return count(s, s.smith, INGOT_TEST) >= 6 && count(s, s.smith, Items.COPPER_INGOT) >= 6;
            }, () -> {
                s.c.check("six copper ingots", count(s, s.smith, Items.COPPER_INGOT) == 6, count(s, s.smith, Items.COPPER_INGOT));
                s.c.check("six addon ingots", count(s, s.smith, INGOT_TEST) == 6, count(s, s.smith, INGOT_TEST));
                s.c.check("no raw left", count(s, s.smith, Items.RAW_COPPER) == 0 && count(s, s.smith, RAW_TEST) == 0,
                    count(s, s.smith, Items.RAW_COPPER) + " copper, " + count(s, s.smith, RAW_TEST) + " addon");
                s.end();
            });
        });
    }

    /** Away catch-up ({@code BlacksmithActivity$Offline}) smelts the addon raw too; with hooks off it doesn't know it. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_forge_smelt_d")
    public static void awayCatchUpSmeltsAddonRaw(GameTestHelper helper) {
        scene(helper, "awayCatchUpSmeltsAddonRaw", s -> {
            s.subs.add(TownKitForge.smelt(OWNER, RAW_TEST, INGOT_TEST));
            smeltingScene(s, RAW_TEST, 10);
            BlacksmithActivity.OfflineWork work = BlacksmithActivity.workOffline(s.ctx(s.smith), 24000L,
                new BlacksmithActivity.AwayHooks(x -> { }, 0L, null));
            int ingots = count(s, s.smith, INGOT_TEST);
            if (hooksOff()) {
                s.c.check("hooks off: Towntopia's away catch-up leaves the raw", ingots == 0 && count(s, s.smith, RAW_TEST) == 10, work);
            } else {
                s.c.check("the away catch-up smelted the addon raw", ingots == 10 && work.smelted() >= 10, work + ", ingots " + ingots);
                s.c.check("no raw left", count(s, s.smith, RAW_TEST) == 0, count(s, s.smith, RAW_TEST));
            }
            s.end();
        });
    }

    /** A change of what raw copper (or raw iron) smelts into is honoured in the village. */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_forge_smelt_e")
    public static void changedRawResultsAreSmelted(GameTestHelper helper) {
        scene(helper, "changedRawResultsAreSmelted", s -> {
            s.subs.add(TownKitForge.smelt(OWNER, Items.RAW_COPPER, INGOT_TEST));
            if (hooksOff()) {
                towntopiaSmelts(s, Items.RAW_COPPER, Items.COPPER_INGOT);
                return;
            }
            smeltingScene(s, Items.RAW_COPPER, 8);
            BuildingTests.waitFor(helper, 20, 8500, () -> {
                s.feed();
                return count(s, s.smith, INGOT_TEST) >= 8;
            }, () -> {
                s.c.check("raw copper smelts into the changed ingot", count(s, s.smith, INGOT_TEST) == 8, count(s, s.smith, INGOT_TEST));
                s.c.check("no copper ingot", count(s, s.smith, Items.COPPER_INGOT) == 0, count(s, s.smith, Items.COPPER_INGOT));
                s.end();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_forge_smelt_f")
    public static void changedIronResultIsSmelted(GameTestHelper helper) {
        scene(helper, "changedIronResultIsSmelted", s -> {
            s.subs.add(TownKitForge.smelt(OWNER, Items.RAW_IRON, INGOT_TEST));
            if (hooksOff()) {
                towntopiaSmelts(s, Items.RAW_IRON, Items.IRON_INGOT);
                return;
            }
            smeltingScene(s, Items.RAW_IRON, 8);
            BuildingTests.waitFor(helper, 20, 8500, () -> {
                s.feed();
                return count(s, s.smith, INGOT_TEST) >= 8;
            }, () -> {
                s.c.check("raw iron smelts into the changed ingot", count(s, s.smith, INGOT_TEST) == 8, count(s, s.smith, INGOT_TEST));
                s.c.check("no iron ingot", count(s, s.smith, Items.IRON_INGOT) == 0, count(s, s.smith, Items.IRON_INGOT));
                s.end();
            });
        });
    }

    /** Raw copper removed: the smith leaves it (alone, it would smelt it), and the addon raw still smelts. */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_forge_smelt_g")
    public static void removedRawCopperIsLeft(GameTestHelper helper) {
        scene(helper, "removedRawCopperIsLeft", s -> {
            if (hooksOff()) {
                s.subs.add(TownKitForge.removeSmelt(OWNER, Items.RAW_COPPER));
                towntopiaSmelts(s, Items.RAW_COPPER, Items.COPPER_INGOT);
                return;
            }
            stock(s, Items.RAW_COPPER, 10);
            smeltingScene(s, Items.RAW_COPPER, 0);
            s.c.check("before: ten raw copper is smelted", hasSpareToSmelt(s), "");
            s.subs.add(TownKitForge.removeSmelt(OWNER, Items.RAW_COPPER));
            s.c.check("raw copper removed: nothing to smelt", !hasSpareToSmelt(s), "");
            s.subs.add(TownKitForge.smelt(OWNER, RAW_TEST, INGOT_TEST));
            stock(s, RAW_TEST, 8);
            s.c.check("an addon raw still counts", hasSpareToSmelt(s), "");
            BuildingTests.waitFor(helper, 20, 8500, () -> {
                s.feed();
                return count(s, s.smith, INGOT_TEST) >= 8;
            }, () -> {
                s.c.check("the addon raw is smelted", count(s, s.smith, INGOT_TEST) == 8, count(s, s.smith, INGOT_TEST));
                s.c.check("the raw copper is left", count(s, s.smith, Items.RAW_COPPER) == 10 && count(s, s.smith, Items.COPPER_INGOT) == 0,
                    count(s, s.smith, Items.RAW_COPPER) + " raw, " + count(s, s.smith, Items.COPPER_INGOT) + " ingots");
                s.end();
            });
        });
    }

    /** Raw copper removed while the smith already carries some: it stays raw copper, only the addon raw is smelted. */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_forge_smelt_h")
    public static void removedRawCopperInThePackIsLeft(GameTestHelper helper) {
        scene(helper, "removedRawCopperInThePackIsLeft", s -> {
            s.subs.add(TownKitForge.removeSmelt(OWNER, Items.RAW_COPPER));
            if (hooksOff()) {
                towntopiaSmelts(s, Items.RAW_COPPER, Items.COPPER_INGOT);
                return;
            }
            s.subs.add(TownKitForge.smelt(OWNER, RAW_TEST, INGOT_TEST));
            s.smith.getInventory().setSlot(0, bound(s, Items.RAW_COPPER, 8));
            smeltingScene(s, RAW_TEST, 8);
            BuildingTests.waitFor(helper, 20, 8500, () -> {
                s.feed();
                return count(s, s.smith, INGOT_TEST) >= 8;
            }, () -> {
                s.c.check("the addon raw is smelted", count(s, s.smith, INGOT_TEST) == 8, count(s, s.smith, INGOT_TEST));
                s.c.check("the carried raw copper is still raw copper", count(s, s.smith, Items.RAW_COPPER) == 8
                    && count(s, s.smith, Items.COPPER_INGOT) == 0, count(s, s.smith, Items.RAW_COPPER) + " raw, "
                    + count(s, s.smith, Items.COPPER_INGOT) + " ingots");
                s.end();
            });
        });
    }
}
