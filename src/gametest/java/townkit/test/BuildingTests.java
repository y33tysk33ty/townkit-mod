package townkit.test;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.core.Profession;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import townkit.api.Subscription;
import townkit.api.building.BuildingDetails;
import townkit.api.building.StandInReport;
import townkit.api.building.TownKitBuildings;
import townkit.api.event.TownKitEvents;
import townkit.api.event.TypedEvent;
import townkit.core.TownKit;
import townkit.core.building.BuildingEventHooks;
import townkit.core.building.CommonTagRecognition;
import townkit.core.building.CoreBuildings;
import townkit.core.building.StandIns;
import townkit.core.hook.Hooks;
import townkit.test.BehaviourTests.TestVillage;

/**
 * Contract tests for stand-in rules ({@link TownKitBuildings}) in real Towntopia villages. Each test has its own batch
 * and its own village, 400 blocks from every other test village. Rooms are built with Towntopia's test kit
 * ({@code ScenarioBuilder.buildRoom}) and registered the way {@code ScenarioBuilder.buildVillage} does it: a token bound
 * to the village in the room's frame, then {@code rebuildNow}, whose existing-buildings scan registers the room.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class BuildingTests {
    static final String OWNER = "townkit-test";

    private BuildingTests() {
    }

    /** A room this test built and registered, with the spots for its working blocks (back row, west and east end). */
    record Room(Building building, BlockPos west, BlockPos middle, BlockPos east) {
    }

    /**
     * Builds an empty 5x5 room with its interior's north-west corner at (ox+dx, oz+dz), lets {@code furnish} place
     * blocks on its back row, puts {@code token} (bound to the village) in its frame and registers it.
     */
    static Optional<Room> room(GameTestHelper helper, TestVillage tv, int dx, int dz, Supplier<Item> token,
            java.util.function.Consumer<Room> furnish) {
        ServerLevel level = helper.getLevel();
        int ox = tv.door().getX();
        int oz = tv.door().getZ() + 1;
        int feetY = tv.door().getY();
        int minX = ox + dx;
        int minZ = oz + dz;
        ScenarioBuilder.Room r = ScenarioBuilder.buildRoom(level, minX, feetY, minZ, ScenarioBuilder.RoomFurniture.NONE);
        int back = minZ + ScenarioBuilder.DEFAULT_INTERIOR - 1;
        Room spots = new Room(null, new BlockPos(minX, feetY, back), new BlockPos(minX + 2, feetY, back),
                new BlockPos(minX + 4, feetY, back));
        furnish.accept(spots);
        ItemStack stack = new ItemStack(token.get());
        VillageTokenBinding.bind(stack, tv.mgr());
        r.frame().setItem(stack, false);
        tv.mgr().rebuildNow(level);
        for (Building b : tv.mgr().getBuildings()) {
            if (b.getDoorPos().equals(r.doorPos())) {
                return Optional.of(new Room(b, spots.west(), spots.middle(), spots.east()));
            }
        }
        helper.fail("room at " + r.doorPos() + " was not registered");
        return Optional.empty();
    }

    /**
     * Towntopia's own answer to "does this room have what it needs": its {@code scanFunctionalBlocks} result (Kitchen:
     * a heat source and a crafting table; Forge: an anvil and a furnace; Storage: 2 containers and a crafting table).
     * Towntopia's {@code isValid()} only covers the room's shape, so this is what the tests call valid. Runs the
     * (rewritten) checks again, as Towntopia's re-check does.
     */
    static boolean meets(Building b, ServerLevel level) {
        try {
            Method m = b.getClass().getDeclaredMethod("scanFunctionalBlocks", ServerLevel.class);
            m.setAccessible(true);
            return (boolean) m.invoke(b, level);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't call scanFunctionalBlocks on " + b.getClass().getSimpleName(), e);
        }
    }

    /** A Towntopia re-check of the building (what it does every 100 ticks), then whether it meets its needs. */
    static boolean rescan(TestVillage tv, Room room, ServerLevel level) {
        tv.mgr().recheckBuilding(level, room.building());
        return meets(room.building(), level);
    }

    static void put(ServerLevel level, BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), 3);
    }

    static BlockState chest() {
        return Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE);
    }

    static boolean anyKey(List<String> keys, String owner, String target) {
        return keys.stream().anyMatch(k -> k.startsWith(owner + "#") && k.endsWith("#" + target));
    }

    static boolean anyKey(Set<String> keys, String owner, String target) {
        return anyKey(new ArrayList<>(keys), owner, target);
    }

    static void removeAll(List<Subscription> subs) {
        for (Subscription s : subs) {
            s.remove();
        }
        subs.clear();
    }

    /** Runs a test step; a throw fails the test after the rules are removed, so later tests start clean. */
    static void step(GameTestHelper helper, Checks c, List<Subscription> subs, Runnable body) {
        try {
            body.run();
        } catch (net.minecraft.gametest.framework.GameTestAssertException e) {
            throw e; // the test's own verdict (Checks.finish), not a crash
        } catch (Throwable t) {
            removeAll(subs);
            TestKitAccess.clear();
            TownKit.LOG.error("[TownKit test] {} threw", c.test, t);
            c.check("no exception", false, t);
            c.finish(helper);
        }
    }

    /** The simulated player is global; tests without a village leave it alone. */
    static final class TestKitAccess {
        static void clear() {
            com.alex.test.testkit.TestKit.clearSimulatedPlayer();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A kitchen whose only "crafting table" is a modded block counts only while the stand-in rule exists. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_table")
    public static void standInTableMakesKitchenValid(GameTestHelper helper) {
        Checks c = new Checks("standInTableMakesKitchenValid");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 7600, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Block table = TestBlocks.TEST_TABLE.get();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), table);
        }).orElse(null);
        if (kitchen == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            c.check("the room is a kitchen", kitchen.building() instanceof KitchenBuilding, kitchen.building());
            KitchenBuilding k = (KitchenBuilding) kitchen.building();
            c.check("without a rule the modded table doesn't count", !rescan(tv, kitchen, level),
                    k.getCraftingTablePositions());
            Subscription rule = TownKitBuildings.countsAs(OWNER, table, Blocks.CRAFTING_TABLE);
            subs.add(rule);
            c.checkHooksOn("the rule is accepted", !rule.isDisabled(), rule.isDisabled());
            c.checkHooksOn("with the rule the kitchen is valid", rescan(tv, kitchen, level), k.getCraftingTablePositions());
            c.checkHooksOn("and the modded table is its crafting table", k.getCraftingTablePositions().contains(kitchen.east()),
                    k.getCraftingTablePositions());
            rule.remove();
            subs.clear();
            c.check("after remove() it's invalid again", !rescan(tv, kitchen, level), k.getCraftingTablePositions());
            c.check("and nothing stands in any more", !StandIns.active(), StandIns.active());
            // control: the same spot with a vanilla crafting table
            put(level, kitchen.east(), Blocks.CRAFTING_TABLE);
            c.check("control: a vanilla crafting table there makes it valid", rescan(tv, kitchen, level),
                    k.getCraftingTablePositions());
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /**
     * A modded oven as FURNACE: per the audit, the Kitchen and Forge scans are SAFE (the cook and smith only stand
     * there), so it counts in both; the Storage room's furnace, the cook's and the smith's {@code lightFurnace} are
     * UNSAFE (they write {@code LIT}), so it is skipped there and a storage room never takes it as its furnace.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_oven")
    public static void ovenCountsInKitchenAndForgeNotStorage(GameTestHelper helper) {
        Checks c = new Checks("ovenCountsInKitchenAndForgeNotStorage");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 8000, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Block oven = TestBlocks.TEST_OVEN.get();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), oven);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        Room forge = room(helper, tv, 12, -14, ModItems.FORGE::get, r -> {
            put(level, r.west(), oven);
            put(level, r.east(), Blocks.ANVIL);
        }).orElse(null);
        Room storage = room(helper, tv, -16, -14, ModItems.STORAGE_ROOM::get, r -> {
            level.setBlock(r.west(), chest(), 3);
            level.setBlock(r.west().east(2), chest(), 3);
            put(level, r.middle().east(), oven);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null || forge == null || storage == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            KitchenBuilding k = (KitchenBuilding) kitchen.building();
            ForgeBuilding f = (ForgeBuilding) forge.building();
            StorageBuilding s = (StorageBuilding) storage.building();
            BlockPos storageOven = storage.middle().east();
            c.check("without a rule the kitchen has no heat source", !rescan(tv, kitchen, level),
                    k.getHeatPositions(KitchenBuilding.HeatSource.FURNACE));
            c.check("without a rule the forge has no furnace", !rescan(tv, forge, level), f.getFurnacePos());

            Subscription rule = TownKitBuildings.countsAs(OWNER, oven, Blocks.FURNACE);
            subs.add(rule);
            c.checkHooksOn("the rule is accepted", !rule.isDisabled(), rule.isDisabled());
            StandInReport r = TownKitBuildings.standIn(oven).orElse(null);
            c.checkHooksOn("there is a report", r != null, r);
            if (r != null) {
                c.check("report: modded/vanilla/explicit", r.modded() == oven && r.vanilla() == Blocks.FURNACE
                        && !r.fromCommonTag(), r);
                String furnaceClass = "net/minecraft/world/level/block/AbstractFurnaceBlock";
                c.check("counts at the kitchen heat-source site",
                        anyKey(r.countsAt(), "village/building/KitchenBuilding", furnaceClass), r.countsAt());
                c.check("counts at the forge furnace site (SAFE in the audit)",
                        anyKey(r.countsAt(), "village/building/ForgeBuilding", furnaceClass), r.countsAt());
                c.check("skipped at the storage room's furnace (UNSAFE)",
                        anyKey(r.skippedAt().keySet(), "village/building/StorageBuilding", "FURNACE"), r.skippedAt());
                c.check("skipped at the smith's lightFurnace (UNSAFE)", anyKey(r.skippedAt().keySet(),
                        "village/villagers/blacksmith/behavior/BlacksmithActivity", furnaceClass), r.skippedAt());
                c.check("skipped at the cook's lightFurnace (UNSAFE)", anyKey(r.skippedAt().keySet(),
                        "village/villagers/cook/behavior/CookActivity", furnaceClass), r.skippedAt());
                boolean offTopic = r.countsAt().stream().anyMatch(key -> key.endsWith("#CRAFTING_TABLE")
                        || key.endsWith("SmokerBlock") || key.endsWith("BlastFurnaceBlock") || key.endsWith("BedBlock"));
                c.check("lists only sites about furnaces", !offTopic, r.countsAt());
            }
            c.checkHooksOn("the kitchen is valid with the oven", rescan(tv, kitchen, level),
                    k.getHeatPositions(KitchenBuilding.HeatSource.FURNACE));
            c.checkHooksOn("as its FURNACE heat source",
                    k.getHeatPositions(KitchenBuilding.HeatSource.FURNACE).contains(kitchen.west()),
                    k.getHeatPositions(KitchenBuilding.HeatSource.FURNACE));
            c.checkHooksOn("the forge is valid with the oven", rescan(tv, forge, level), f.getFurnacePos());
            rescan(tv, storage, level);
            c.check("the storage room doesn't take it as its furnace", !s.getFurnacePositions().contains(storageOven),
                    s.getFurnacePositions());
            // control: a vanilla furnace on the same storage spot is taken
            put(level, storageOven, Blocks.FURNACE);
            rescan(tv, storage, level);
            c.check("control: a vanilla furnace there is the storage furnace",
                    s.getFurnacePositions().contains(storageOven), s.getFurnacePositions());
            removeAll(subs);
            c.check("after remove() the kitchen is invalid again", !rescan(tv, kitchen, level),
                    k.getHeatPositions(KitchenBuilding.HeatSource.FURNACE));
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /** A plain block can't be a bed: every bed site needs a real BedBlock, so the rule counts nowhere. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_building_hammock")
    public static void hammockRefused(GameTestHelper helper) {
        Checks c = new Checks("hammockRefused");
        List<Subscription> subs = new ArrayList<>();
        step(helper, c, subs, () -> {
            Block hammock = TestBlocks.TEST_HAMMOCK.get();
            Subscription s = TownKitBuildings.countsAs(OWNER, hammock, Blocks.RED_BED);
            subs.add(s);
            c.check("the rule is refused (disabled subscription)", s.isDisabled(), s.isDisabled());
            c.check("no report", TownKitBuildings.standIn(hammock).isEmpty(), TownKitBuildings.standIn(hammock));
            c.check("nothing stands in", !StandIns.active(), StandIns.active());
            c.check("a bed block passes the bed check", CoreBuildings.CHECKS.passes("bed_block", Blocks.RED_BED), "");
            c.check("the hammock doesn't", !CoreBuildings.CHECKS.passes("bed_block", hammock), "");
            // control: a rule that can count somewhere is accepted
            Subscription control = TownKitBuildings.countsAs(OWNER, TestBlocks.TEST_TABLE.get(), Blocks.CRAFTING_TABLE);
            subs.add(control);
            c.checkHooksOn("control: a table rule is accepted", !control.isDisabled(), control.isDisabled());
            removeAll(subs);
            c.finish(helper);
        });
    }

    /**
     * A crate without a block entity isn't storage: the only chest site needs a real inventory, so the rule counts
     * nowhere and a storage room never takes it as a container.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_crate")
    public static void crateWithoutInventoryNotStorage(GameTestHelper helper) {
        Checks c = new Checks("crateWithoutInventoryNotStorage");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 8400, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Block crate = TestBlocks.TEST_CRATE.get();
        Room storage = room(helper, tv, 12, 14, ModItems.STORAGE_ROOM::get, r -> {
            level.setBlock(r.west(), chest(), 3);
            put(level, r.middle(), crate);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (storage == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            Subscription s = TownKitBuildings.countsAs(OWNER, crate, Blocks.CHEST);
            subs.add(s);
            c.check("the crate fails the inventory check", !CoreBuildings.CHECKS.passes("inventory", crate), "");
            c.check("a chest passes it", CoreBuildings.CHECKS.passes("inventory", Blocks.CHEST), "");
            c.check("a chest passes the container check", CoreBuildings.CHECKS.passes("container", Blocks.CHEST), "");
            c.check("the crate rule counts nowhere (disabled)", s.isDisabled(), s.isDisabled());
            c.check("no report for the crate", TownKitBuildings.standIn(crate).isEmpty(), TownKitBuildings.standIn(crate));
            boolean ok = rescan(tv, storage, level);
            c.check("the storage room doesn't count the crate", !storage.building().getFunctionalBlocks()
                    .contains(storage.middle()), storage.building().getFunctionalBlocks());
            c.check("so one chest is not enough", !ok, storage.building().getFunctionalBlocks());
            // control: a block with a real inventory as a chest counts at the NEEDS:inventory site
            Subscription control = TownKitBuildings.countsAs(OWNER, Blocks.BARREL, Blocks.CHEST);
            subs.add(control);
            c.checkHooksOn("control: a barrel as a chest is accepted", !control.isDisabled(), control.isDisabled());
            StandInReport r = TownKitBuildings.standIn(Blocks.BARREL).orElse(null);
            c.checkHooksOn("and counts at the storage check", r != null && anyKey(r.countsAt(),
                    "village/building/StorageContainers", "net/minecraft/world/level/block/ChestBlock"), r);
            removeAll(subs);
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /**
     * A block that holds items only through NeoForge's item-handler capability, registered off the server thread
     * (like an addon at mod setup): the capability can't be probed there, so the rule is kept pending (a live
     * subscription, not active yet), then decided on the server thread, where it counts as storage.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_capability")
    public static void capabilityStorageCountsAfterServerThreadRecheck(GameTestHelper helper) {
        Checks c = new Checks("capabilityStorageCountsAfterServerThreadRecheck");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 9200, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Block crate = TestBlocks.TEST_HANDLER_CRATE.get();
        Room storage = room(helper, tv, 12, 14, ModItems.STORAGE_ROOM::get, r -> {
            level.setBlock(r.west(), chest(), 3);
            put(level, r.middle(), crate);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (storage == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            c.check("without a rule the storage room doesn't count it", !rescan(tv, storage, level)
                    && !storage.building().getFunctionalBlocks().contains(storage.middle()),
                    storage.building().getFunctionalBlocks());
            Subscription[] rule = new Subscription[1];
            boolean[] offThread = new boolean[2];
            Thread t = new Thread(() -> {
                rule[0] = TownKitBuildings.countsAs(OWNER, crate, Blocks.CHEST);
                offThread[0] = CoreBuildings.CHECKS.passes("inventory", crate);
                offThread[1] = CoreBuildings.CHECKS.decided("inventory", crate);
            }, "townkit-test-addon-thread");
            t.start();
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            subs.add(rule[0]);
            c.check("off the server thread: not probed, undecided", !offThread[0] && !offThread[1],
                    offThread[0] + "/" + offThread[1]);
            c.check("the rule is kept (live subscription)", rule[0] != null && !rule[0].isDisabled(), rule[0]);
            c.check("but pending: no report yet", TownKitBuildings.standIn(crate).isEmpty(),
                    TownKitBuildings.standIn(crate));
            c.check("on the server thread the capability is probed", CoreBuildings.CHECKS.passes("inventory", crate), "");
            c.check("the crate's block entity isn't a Container", !CoreBuildings.CHECKS.passes("container", crate), "");
            helper.runAfterDelay(5, () -> step(helper, c, subs, () -> {
                StandInReport r = TownKitBuildings.standIn(crate).orElse(null);
                c.checkHooksOn("decided on the server thread: counts at the storage check", r != null && anyKey(r.countsAt(),
                        "village/building/StorageContainers", "net/minecraft/world/level/block/ChestBlock"), r);
                boolean ok = rescan(tv, storage, level);
                c.checkHooksOn("the storage room counts it as a container", storage.building().getFunctionalBlocks()
                        .contains(storage.middle()), storage.building().getFunctionalBlocks());
                c.checkHooksOn("so chest + crate + table make it valid", ok, storage.building().getFunctionalBlocks());
                removeAll(subs);
                c.check("after remove() it doesn't count", !rescan(tv, storage, level), "");
                BehaviourTests.forget(tv);
                BehaviourTests.finish(helper, c);
            }));
        }));
    }

    /** Recognition of shared c: tags stays off until an addon asks, and switches off when the last one lets go. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_building_tags")
    public static void commonTagsOffByDefault(GameTestHelper helper) {
        Checks c = new Checks("commonTagsOffByDefault");
        List<Subscription> subs = new ArrayList<>();
        step(helper, c, subs, () -> {
            Block crate = TestBlocks.TEST_CRATE.get();
            c.check("off by default", !CoreBuildings.commonTagsOn(), CoreBuildings.commonTagsOn());
            c.check("a chest stands in for nothing", TownKitBuildings.standIn(Blocks.CHEST).isEmpty(), "");
            c.check("a modded block stands in for nothing", TownKitBuildings.standIn(crate).isEmpty(), "");
            c.check("no rules: inactive", !StandIns.active(), StandIns.active());
            int nonVanilla = 0;
            for (String tag : CommonTagRecognition.TAGS.keySet()) {
                TagKey<Block> key = TagKey.create(Registries.BLOCK, ResourceLocation.parse(tag));
                for (var holder : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
                    if (!"minecraft".equals(BuiltInRegistries.BLOCK.getKey(holder.value()).getNamespace())) {
                        nonVanilla++;
                    }
                }
            }
            Subscription a = TownKitBuildings.useCommonTags(OWNER);
            Subscription b = TownKitBuildings.useCommonTags(OWNER + "-2");
            subs.add(a);
            subs.add(b);
            c.check("the subscriptions are live", !a.isDisabled() && !b.isDisabled(), a.isDisabled() + "/" + b.isDisabled());
            c.check("on after useCommonTags", CoreBuildings.commonTagsOn(), CoreBuildings.commonTagsOn());
            c.check("the table has entries iff the pack has non-vanilla tag members",
                    StandIns.table().isEmpty() == (nonVanilla == 0), "non-vanilla members " + nonVanilla);
            c.check("vanilla tag members never stand in", TownKitBuildings.standIn(Blocks.CHEST).isEmpty(), "");
            a.remove();
            c.check("still on while one subscription is live", CoreBuildings.commonTagsOn(), CoreBuildings.commonTagsOn());
            b.remove();
            subs.clear();
            c.check("off after the last remove()", !CoreBuildings.commonTagsOn(), CoreBuildings.commonTagsOn());
            c.check("and inactive again", !StandIns.active(), StandIns.active());
            c.finish(helper);
        });
    }

    /** With no rule and no tag subscription, the bridge's fast path is a single "inactive" read. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_building_nocost")
    public static void noRulesNoCost(GameTestHelper helper) {
        Checks c = new Checks("noRulesNoCost");
        c.check("no rules: inactive", !StandIns.active(), StandIns.active());
        c.check("recognition off", !CoreBuildings.commonTagsOn(), CoreBuildings.commonTagsOn());
        c.check("the real checks are installed", CoreBuildings.installed(), CoreBuildings.installed());
        c.finish(helper);
    }

    /**
     * A kitchen and a forge running on stand-ins (table and oven) with a cook and a smith: 400 ticks without an
     * exception. Counted with a Log4j appender on the root logger: every ERROR-or-worse event, and any event carrying
     * a throwable, from any logger, while the test runs (batches run one at a time).
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_building_ticks")
    public static void standInsDontBreakTicks(GameTestHelper helper) {
        Checks c = new Checks("standInsDontBreakTicks");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 8800, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Block table = TestBlocks.TEST_TABLE.get();
        Block oven = TestBlocks.TEST_OVEN.get();
        subs.add(TownKitBuildings.countsAs(OWNER, table, Blocks.CRAFTING_TABLE));
        subs.add(TownKitBuildings.countsAs(OWNER, oven, Blocks.FURNACE));
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), oven);
            put(level, r.east(), table);
        }).orElse(null);
        Room forge = room(helper, tv, 12, -14, ModItems.FORGE::get, r -> {
            put(level, r.west(), oven);
            put(level, r.east(), Blocks.ANVIL);
        }).orElse(null);
        if (kitchen == null || forge == null) {
            removeAll(subs);
            return;
        }
        ErrorCounter errors = ErrorCounter.attach();
        // positive control: the appender must see an ERROR logged through log4j (it is then left out of the count)
        LogManager.getLogger("townkit-test").error(ErrorCounter.PROBE);
        NpcEntity cook = ScenarioBuilder.spawnVillager(level, tv.mgr(), tv.door().north(3).east(2), Profession.COOK);
        NpcEntity smith = ScenarioBuilder.spawnVillager(level, tv.mgr(), tv.door().north(3).west(2), Profession.BLACKSMITH);
        helper.runAfterDelay(400, () -> {
            errors.detach();
            step(helper, c, subs, () -> {
                c.checkHooksOn("both rules accepted", subs.stream().noneMatch(Subscription::isDisabled), subs.size());
                c.checkHooksOn("the kitchen is valid on stand-ins", rescan(tv, kitchen, level), kitchen.building());
                c.checkHooksOn("the forge is valid on a stand-in", rescan(tv, forge, level), forge.building());
                c.check("the log capture works (probe ERROR seen)", errors.probeSeen.get(), errors.probeSeen.get());
                c.check("no error or exception logged in 400 ticks", errors.count.get() == 0,
                        errors.count.get() + " " + errors.first);
                // the mixin plugin's record of rewritten sites is the one reports read (same class loader)
                c.checkHooksOn("the kitchen's checks were recorded as rewritten",
                        townkit.core.building.RewrittenSites.GLOBAL.loaded("village/building/KitchenBuilding"),
                        townkit.core.building.RewrittenSites.GLOBAL.loaded("village/building/KitchenBuilding"));
                Optional<StandInReport> tr = TownKitBuildings.standIn(table);
                c.checkHooksOn("no counted site is reported as not rewritten", tr.isPresent()
                        && tr.get().skippedAt().values().stream().noneMatch(v -> v.startsWith("not rewritten")),
                        tr.map(StandInReport::skippedAt).orElse(null));
                removeAll(subs);
                for (NpcEntity n : List.of(cook, smith)) {
                    tv.mgr().removeVillager(n.getNpcId());
                    if (!n.isRemoved()) n.discard();
                }
                BehaviourTests.forget(tv);
                BehaviourTests.finish(helper, c);
            });
        });
    }

    // --- read API --------------------------------------------------------------------------------------------------

    static final ResourceLocation KITCHEN = ResourceLocation.parse("towntopia:kitchen");
    static final ResourceLocation FORGE = ResourceLocation.parse("towntopia:forge");
    static final ResourceLocation CRAFTING_TABLE = ResourceLocation.parse("minecraft:crafting_table");
    static final ResourceLocation FURNACE = ResourceLocation.parse("minecraft:furnace");

    /** A floor tile in the middle of the room (villagers stand there). */
    static BlockPos floor(Room r) {
        return r.middle().north(2);
    }

    static townkit.api.village.Village handle(TestVillage tv) {
        return townkit.core.village.VillageHandle.of(tv.mgr());
    }

    static List<BlockPos> listed(BuildingDetails d, ResourceLocation id) {
        return d.workingBlocks().getOrDefault(id, List.of());
    }

    /** Runs {@code then} once {@code done} holds, checking every {@code every} ticks for at most {@code left} ticks. */
    static void waitFor(GameTestHelper helper, int every, int left, java.util.function.BooleanSupplier done,
            Runnable then) {
        if (left <= 0 || done.getAsBoolean()) {
            then.run();
            return;
        }
        helper.runAfterDelay(every, () -> waitFor(helper, every, left - every, done, then));
    }

    /** A kitchen read back: its type, Towntopia's verdict, its room and its working blocks; reading changes nothing. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_details")
    public static void detailsOfAKitchen(GameTestHelper helper) {
        Checks c = new Checks("detailsOfAKitchen");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 9600, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            Building b = kitchen.building();
            BuildingDetails d = TownKitBuildings.details(handle(tv), b.getItemFrameId()).orElse(null);
            c.check("details found", d != null, d);
            if (d != null) {
                c.check("info as VillageHandle.info", d.info().equals(townkit.core.village.VillageHandle.info(b)), d.info());
                c.check("type towntopia:kitchen", KITCHEN.equals(d.info().type()), d.info().type());
                c.check("valid", d.valid(), d);
                c.check("valid is Towntopia's verdict", d.valid() == b.isValid(), b.isValid());
                c.check("no problem", d.problem().isEmpty(), d.problem());
                c.check("crafting table listed", listed(d, CRAFTING_TABLE).equals(List.of(kitchen.east())), d.workingBlocks());
                c.check("furnace listed", listed(d, FURNACE).equals(List.of(kitchen.west())), d.workingBlocks());
                c.check("room tiles non-empty", !d.roomTiles().isEmpty(), d.roomTiles().size());
                c.check("room tiles hold the floor", d.roomTiles().contains(floor(kitchen)), d.roomTiles());
                c.check("room tiles are Towntopia's", d.roomTiles().equals(b.getNavMeshBlocks()), d.roomTiles().size());
            }
            // reading never rescans: a table placed after Towntopia's last scan isn't listed, its lists don't change
            Set<BlockPos> before = Set.copyOf(b.getFunctionalBlocks());
            put(level, kitchen.middle(), Blocks.CRAFTING_TABLE);
            BuildingDetails again = TownKitBuildings.details(handle(tv), b.getItemFrameId()).orElse(null);
            c.check("a block placed since the scan isn't listed", again != null
                    && !listed(again, CRAFTING_TABLE).contains(kitchen.middle()), again);
            c.check("reading left Towntopia's lists alone", before.equals(b.getFunctionalBlocks()), b.getFunctionalBlocks());
            c.check("unknown frame: empty", TownKitBuildings.details(handle(tv), java.util.UUID.randomUUID()).isEmpty(), "");
            // server thread only
            Throwable[] off = new Throwable[1];
            Thread t = new Thread(() -> {
                try {
                    TownKitBuildings.details(handle(tv), b.getItemFrameId());
                } catch (Throwable e) {
                    off[0] = e;
                }
            }, "townkit-test-reader");
            t.start();
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            c.check("off the server thread it throws IllegalStateException", off[0] instanceof IllegalStateException, off[0]);
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /** {@code at} finds the building from a floor tile, the space above it and a working block. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_at")
    public static void atFindsTheRoom(GameTestHelper helper) {
        Checks c = new Checks("atFindsTheRoom");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 10000, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            java.util.UUID id = kitchen.building().getItemFrameId();
            for (BlockPos p : List.of(floor(kitchen), floor(kitchen).above(), kitchen.east())) {
                Optional<BuildingDetails> d = TownKitBuildings.at(level, p);
                c.check("found at " + p, d.isPresent() && d.get().info().frameId().equals(id), d);
            }
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /** {@code at} outside every room (in front of the door, in the wall, in the roof) is empty. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_at_outside")
    public static void atOutsideIsEmpty(GameTestHelper helper) {
        Checks c = new Checks("atOutsideIsEmpty");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 10400, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            BlockPos door = kitchen.building().getDoorPos();
            c.check("control: inside is found", TownKitBuildings.at(level, floor(kitchen)).isPresent(), "");
            for (BlockPos p : List.of(door.north(4), kitchen.west().west(), floor(kitchen).above(3),
                    floor(kitchen).below())) {
                Optional<BuildingDetails> d = TownKitBuildings.at(level, p);
                c.check("nothing at " + p, d.isEmpty(), d);
            }
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /** {@code ofType} lists the village's kitchens and only them. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_oftype")
    public static void ofTypeListsKitchens(GameTestHelper helper) {
        Checks c = new Checks("ofTypeListsKitchens");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 10800, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room k1 = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        Room k2 = room(helper, tv, -16, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        Room forge = room(helper, tv, 12, -14, ModItems.FORGE::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.ANVIL);
        }).orElse(null);
        if (k1 == null || k2 == null || forge == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            Set<java.util.UUID> kitchens = new java.util.HashSet<>();
            for (BuildingDetails d : TownKitBuildings.ofType(handle(tv), KITCHEN)) kitchens.add(d.info().frameId());
            c.check("both kitchens, nothing else", kitchens.equals(Set.of(k1.building().getItemFrameId(),
                    k2.building().getItemFrameId())), kitchens);
            List<BuildingDetails> forges = TownKitBuildings.ofType(handle(tv), FORGE);
            c.check("one forge", forges.size() == 1
                    && forges.get(0).info().frameId().equals(forge.building().getItemFrameId()), forges);
            c.check("unknown type: empty", TownKitBuildings.ofType(handle(tv),
                    ResourceLocation.parse("towntopia:no_such_building")).isEmpty(), "");
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /** A stand-in table is listed under {@code minecraft:crafting_table}, not under its own id. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_standin_read")
    public static void standInShowsUnderVanillaId(GameTestHelper helper) {
        Checks c = new Checks("standInShowsUnderVanillaId");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 11200, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Block table = TestBlocks.TEST_TABLE.get();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), table);
        }).orElse(null);
        if (kitchen == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            subs.add(TownKitBuildings.countsAs(OWNER, table, Blocks.CRAFTING_TABLE));
            rescan(tv, kitchen, level);
            BuildingDetails d = TownKitBuildings.details(handle(tv), kitchen.building().getItemFrameId()).orElse(null);
            c.check("details found", d != null, d);
            if (d != null) {
                c.checkHooksOn("the test table under minecraft:crafting_table",
                        listed(d, CRAFTING_TABLE).equals(List.of(kitchen.east())), d.workingBlocks());
                c.check("not under its own id", !d.workingBlocks().containsKey(BuiltInRegistries.BLOCK.getKey(table)),
                        d.workingBlocks());
                c.check("furnace still listed", listed(d, FURNACE).equals(List.of(kitchen.west())), d.workingBlocks());
            }
            removeAll(subs);
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /** {@code /townkit building} run from inside a kitchen: names the type, the stand-in table and the block with no rule. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_command")
    public static void commandRunsInKitchen(GameTestHelper helper) {
        Checks c = new Checks("commandRunsInKitchen");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 12400, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Block table = TestBlocks.TEST_TABLE.get();
        Block oven = TestBlocks.TEST_OVEN.get();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), table);
            put(level, r.middle(), oven);
        }).orElse(null);
        if (kitchen == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            subs.add(TownKitBuildings.countsAs(OWNER, table, Blocks.CRAFTING_TABLE));
            rescan(tv, kitchen, level);
            List<String> out = new ArrayList<>();
            net.minecraft.commands.CommandSource sink = new net.minecraft.commands.CommandSource() {
                @Override
                public void sendSystemMessage(net.minecraft.network.chat.Component m) {
                    out.add(m.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            BlockPos inside = kitchen.middle().north(2);
            net.minecraft.commands.CommandSourceStack src = new net.minecraft.commands.CommandSourceStack(sink,
                    net.minecraft.world.phys.Vec3.atBottomCenterOf(inside), net.minecraft.world.phys.Vec2.ZERO, level, 2,
                    "test", net.minecraft.network.chat.Component.literal("test"), level.getServer(), null);
            int result;
            try {
                result = level.getServer().getCommands().getDispatcher().execute("townkit building", src);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                result = -1;
            }
            TownKit.LOG.info("[TownKit test] /townkit building output:" + System.lineSeparator() + "  {}", String.join(System.lineSeparator() + "  ", out));
            c.check("command succeeded", result > 0, result);
            c.check("first line names the kitchen", !out.isEmpty() && out.get(0).startsWith("towntopia:kitchen"), out);
            c.check("furnace counted", out.contains("  minecraft:furnace x1"), out);
            c.checkHooksOn("table counted via the rule and its owner",
                    out.stream().anyMatch(l -> l.startsWith("  minecraft:crafting_table x1: 1 via townkit:test_table (rule by "
                            + OWNER + ")")), out);
            c.check("oven listed with no rule", out.contains("  townkit:test_oven: no rule"), out);
            c.check("within the line cap", out.size() <= 32, out.size());
            removeAll(subs);
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /**
     * Towntopia 0.1.2 doesn't make validity depend on working blocks: a kitchen without a crafting table is valid
     * (its token lights, it counts as operational). The read API reports that faithfully.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_building_tableless")
    public static void tablelessKitchenValidLikeTowntopia(GameTestHelper helper) {
        Checks c = new Checks("tablelessKitchenValidLikeTowntopia");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 12000, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> put(level, r.west(), Blocks.FURNACE))
                .orElse(null);
        if (kitchen == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            Building b = kitchen.building();
            BuildingDetails d = TownKitBuildings.details(handle(tv), b.getItemFrameId()).orElse(null);
            c.check("details found", d != null, d);
            if (d != null) {
                c.check("Towntopia calls it valid", b.isValid(), b.isValid());
                c.check("so valid is true", d.valid(), d);
                c.check("no crafting table listed", !d.workingBlocks().containsKey(CRAFTING_TABLE), d.workingBlocks());
                c.check("the furnace is listed", listed(d, FURNACE).equals(List.of(kitchen.west())), d.workingBlocks());
            }
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /**
     * A kitchen villagers can no longer reach (stone in front of its door): after Towntopia's 400-tick grace it turns
     * the building off, and the read API says invalid with Towntopia's reason {@code room_unreachable}.
     */
    @GameTest(template = "empty", timeoutTicks = 900, batch = "townkit_building_invalid")
    public static void invalidHasProblem(GameTestHelper helper) {
        Checks c = new Checks("invalidHasProblem");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 11600, 7200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null) return;
        Building b = kitchen.building();
        BlockPos outside = b.getDoorPos().north();
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            c.check("valid before", TownKitBuildings.details(handle(tv), b.getItemFrameId()).map(BuildingDetails::valid)
                    .orElse(false), b.isValid());
            put(level, outside, Blocks.STONE);
            put(level, outside.above(), Blocks.STONE);
            // a plain setBlock doesn't reach Towntopia; tell it as its own place/break handlers do
            tv.mgr().noteBlockChanged(outside);
            tv.mgr().noteBlockChanged(outside.above());
            tv.mgr().rebuildNow(level);
            waitFor(helper, 20, 760, () -> !b.isValid(), () -> step(helper, c, subs, () -> {
                c.check("Towntopia turned it off", !b.isValid(), "shape " + b.isShapeValid() + " access "
                        + b.isAccessValid());
                BuildingDetails d = TownKitBuildings.details(handle(tv), b.getItemFrameId()).orElse(null);
                c.check("details found", d != null, d);
                if (d != null) {
                    c.check("not valid", !d.valid(), d);
                    c.check("problem room_unreachable", d.problem().equals(Optional.of("room_unreachable")), d.problem());
                }
                put(level, outside, Blocks.AIR);
                put(level, outside.above(), Blocks.AIR);
                tv.mgr().noteBlockChanged(outside);
                tv.mgr().noteBlockChanged(outside.above());
                BehaviourTests.forget(tv);
                BehaviourTests.finish(helper, c);
            }));
        }));
    }

    // --- building events -------------------------------------------------------------------------------------------

    /** What one test saw of the four building events, for its own village only. */
    static final class Seen {
        final List<TownKitEvents.BuildingAdded> added = new ArrayList<>();
        final List<TownKitEvents.BuildingRemoved> removed = new ArrayList<>();
        final List<TownKitEvents.BuildingValidityChanged> validity = new ArrayList<>();
        final List<TownKitEvents.BuildingBlocksChanged> blocks = new ArrayList<>();

        static Seen listen(List<Subscription> subs, TestVillage tv) {
            Seen s = new Seen();
            UUID village = tv.mgr().getVillageId();
            subs.add(TownKitEvents.BUILDING_ADDED.listen(OWNER, e -> {
                if (e.village().id().equals(village)) s.added.add(e);
            }));
            subs.add(TownKitEvents.BUILDING_REMOVED.listen(OWNER, e -> {
                if (e.village().id().equals(village)) s.removed.add(e);
            }));
            subs.add(TownKitEvents.BUILDING_VALIDITY_CHANGED.listen(OWNER, e -> {
                if (e.village().id().equals(village)) s.validity.add(e);
            }));
            subs.add(TownKitEvents.BUILDING_BLOCKS_CHANGED.listen(OWNER, e -> {
                if (e.village().id().equals(village)) s.blocks.add(e);
            }));
            return s;
        }

        List<TownKitEvents.BuildingAdded> added(UUID frame) {
            return this.added.stream().filter(e -> e.building().frameId().equals(frame)).toList();
        }

        List<TownKitEvents.BuildingRemoved> removed(UUID frame) {
            return this.removed.stream().filter(e -> e.building().frameId().equals(frame)).toList();
        }

        List<TownKitEvents.BuildingValidityChanged> validity(UUID frame) {
            return this.validity.stream().filter(e -> e.building().frameId().equals(frame)).toList();
        }

        List<TownKitEvents.BuildingBlocksChanged> blocks(UUID frame) {
            return this.blocks.stream().filter(e -> e.building().frameId().equals(frame)).toList();
        }

        int total() {
            return this.added.size() + this.removed.size() + this.validity.size() + this.blocks.size();
        }

        @Override
        public String toString() {
            return "added " + this.added + ", removed " + this.removed + ", validity " + this.validity + ", blocks "
                    + this.blocks;
        }
    }

    /**
     * A furnished room whose frame is still empty: the map is rebuilt, so Towntopia knows the room, but no building is
     * registered there. Same spots as {@link #room}.
     */
    static ScenarioBuilder.Room bareRoom(GameTestHelper helper, TestVillage tv, int dx, int dz,
            java.util.function.Consumer<Room> furnish) {
        ServerLevel level = helper.getLevel();
        int minX = tv.door().getX() + dx;
        int minZ = tv.door().getZ() + 1 + dz;
        int feetY = tv.door().getY();
        ScenarioBuilder.Room r = ScenarioBuilder.buildRoom(level, minX, feetY, minZ, ScenarioBuilder.RoomFurniture.NONE);
        int back = minZ + ScenarioBuilder.DEFAULT_INTERIOR - 1;
        furnish.accept(new Room(null, new BlockPos(minX, feetY, back), new BlockPos(minX + 2, feetY, back),
                new BlockPos(minX + 4, feetY, back)));
        tv.mgr().rebuildNow(level);
        return r;
    }

    /**
     * Puts a village-bound token in the room's frame and tells Towntopia the way its frame-interact handler does
     * ({@code VillageManager.routeItemFrameInteract}, as Towntopia's own playthrough test kit does): the next village
     * tick checks the frame and registers the building.
     */
    static void placeToken(TestVillage tv, ScenarioBuilder.Room r, Supplier<Item> token) {
        ItemStack stack = new ItemStack(token.get());
        VillageTokenBinding.bind(stack, tv.mgr());
        r.frame().setItem(stack, false);
        tv.mgr().routeItemFrameInteract(r.frame().blockPosition());
    }

    @javax.annotation.Nullable
    static Building registeredAt(TestVillage tv, BlockPos door) {
        for (Building b : tv.mgr().getBuildings()) {
            if (b.getDoorPos().equals(door)) return b;
        }
        return null;
    }

    @javax.annotation.Nullable
    static ItemFrame frameOf(ServerLevel level, Building b) {
        return level.getEntitiesOfClass(ItemFrame.class, new AABB(b.getItemFramePos()).inflate(1),
                f -> f.getUUID().equals(b.getItemFrameId())).stream().findFirst().orElse(null);
    }

    /** The building-event hooks TownKit has attached right now. */
    static Set<String> attachedEventHooks() {
        Set<String> all = new java.util.HashSet<>();
        for (TypedEvent<?> e : BUILDING_EVENTS) all.addAll(BuildingEventHooks.keys(e));
        Set<String> out = new java.util.TreeSet<>();
        for (Hooks.Entry en : Hooks.allEntries()) {
            if (TownKit.MODID.equals(en.owner) && all.contains(en.hook.key())) out.add(en.hook.key());
        }
        return out;
    }

    static final List<TypedEvent<?>> BUILDING_EVENTS = List.of(TownKitEvents.BUILDING_ADDED,
            TownKitEvents.BUILDING_REMOVED, TownKitEvents.BUILDING_VALIDITY_CHANGED, TownKitEvents.BUILDING_BLOCKS_CHANGED);

    /** Placing a token in a frame registers a kitchen: one BUILDING_ADDED, PLACED; nothing else for the new building. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_bevents_placed")
    public static void placedFiresAdded(GameTestHelper helper) {
        Checks c = new Checks("placedFiresAdded");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 7600, 7600).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        ScenarioBuilder.Room r = bareRoom(helper, tv, 12, 14, s -> {
            put(level, s.west(), Blocks.FURNACE);
            put(level, s.east(), Blocks.CRAFTING_TABLE);
        });
        c.check("nothing registered before the token", registeredAt(tv, r.doorPos()) == null, "");
        Seen seen = Seen.listen(subs, tv);
        placeToken(tv, r, ModItems.KITCHEN::get);
        waitFor(helper, 5, 200, () -> registeredAt(tv, r.doorPos()) != null, () -> step(helper, c, subs, () -> {
            Building b = registeredAt(tv, r.doorPos());
            c.check("Towntopia registered the kitchen", b instanceof KitchenBuilding, b);
            if (b != null) {
                UUID id = b.getItemFrameId();
                List<TownKitEvents.BuildingAdded> mine = seen.added(id);
                c.checkHooksOn("one BUILDING_ADDED", mine.size() == 1, seen);
                c.checkHooksOn("how PLACED", mine.size() == 1 && mine.get(0).how() == TownKitEvents.AddedHow.PLACED, mine);
                c.checkHooksOn("its info", mine.size() == 1
                        && mine.get(0).building().equals(townkit.core.village.VillageHandle.info(b)), mine);
                c.check("no validity or blocks event for the new building", seen.validity(id).isEmpty()
                        && seen.blocks(id).isEmpty(), seen);
                c.check("nothing removed", seen.removed.isEmpty(), seen);
            }
            removeAll(subs);
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /**
     * A kitchen dropped and restored through Towntopia's own restore path: its saved marker remembered again with
     * {@code VillageManager.rememberSavedBuildings} (what the registry does when a village loads), which restores it
     * ({@code restoreSavedBuildings} then {@code installRestoredBuilding}): one BUILDING_ADDED, RESTORED.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_bevents_restored")
    public static void restoredFiresAdded(GameTestHelper helper) {
        Checks c = new Checks("restoredFiresAdded");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 8000, 7600).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            Building b = kitchen.building();
            UUID id = b.getItemFrameId();
            List<VillageManager.SavedBuilding> saved = tv.mgr().getSavedBuildings().stream()
                    .filter(s -> id.equals(s.itemFrameId())).toList();
            c.check("the kitchen is in the village's saved buildings", saved.size() == 1, saved);
            ItemFrame frame = frameOf(level, b);
            c.check("its frame is there", frame != null, b.getItemFramePos());
            Seen seen = Seen.listen(subs, tv);
            if (frame != null) {
                // Towntopia's handler for a frame leaving the level: drops the building and its saved marker
                tv.mgr().routeEntityLeave(frame);
            }
            c.check("dropped", tv.mgr().getBuilding(id) == null, tv.mgr().getBuilding(id));
            c.checkHooksOn("one BUILDING_REMOVED for the drop", seen.removed(id).size() == 1, seen);
            tv.mgr().rememberSavedBuildings(saved);
            Building back = tv.mgr().getBuilding(id);
            c.check("Towntopia restored it", back instanceof KitchenBuilding && back != b, back);
            List<TownKitEvents.BuildingAdded> mine = seen.added(id);
            c.checkHooksOn("one BUILDING_ADDED", mine.size() == 1, seen);
            c.checkHooksOn("how RESTORED", mine.size() == 1 && mine.get(0).how() == TownKitEvents.AddedHow.RESTORED, mine);
            c.check("no validity or blocks event for the restored building", seen.validity(id).isEmpty()
                    && seen.blocks(id).isEmpty(), seen);
            removeAll(subs);
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /** Breaking a kitchen's frame removes the building: one BUILDING_REMOVED with what it was. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_bevents_removed")
    public static void removedFires(GameTestHelper helper) {
        Checks c = new Checks("removedFires");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 8400, 7600).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null) return;
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            Building b = kitchen.building();
            UUID id = b.getItemFrameId();
            townkit.api.village.BuildingInfo info = townkit.core.village.VillageHandle.info(b);
            ItemFrame frame = frameOf(level, b);
            c.check("its frame is there", frame != null, b.getItemFramePos());
            Seen seen = Seen.listen(subs, tv);
            if (frame != null) frame.discard(); // Towntopia's entity-leave handler removes the building
            helper.runAfterDelay(5, () -> step(helper, c, subs, () -> {
                c.check("Towntopia removed it", tv.mgr().getBuilding(id) == null, tv.mgr().getBuilding(id));
                List<TownKitEvents.BuildingRemoved> mine = seen.removed(id);
                c.checkHooksOn("one BUILDING_REMOVED", mine.size() == 1, seen);
                c.checkHooksOn("with what it was", mine.size() == 1 && mine.get(0).building().equals(info), mine);
                c.check("nothing added", seen.added.isEmpty(), seen);
                removeAll(subs);
                BehaviourTests.forget(tv);
                BehaviourTests.finish(helper, c);
            }));
        }));
    }

    /**
     * Breaking the kitchen's only crafting table, then Towntopia's own rescan (the change noted as its block handlers
     * do): BUILDING_BLOCKS_CHANGED with the table in {@code removed}. Towntopia 0.1.2 keeps the kitchen valid without
     * a table ({@code isValid()} is shape and access only), so no BUILDING_VALIDITY_CHANGED: see
     * {@link #blockedDoorFiresInvalid} for the invalid side.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_bevents_table")
    public static void breakingTableFiresBlocksChanged(GameTestHelper helper) {
        Checks c = new Checks("breakingTableFiresBlocksChanged");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 8800, 7600).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null) return;
        Building b = kitchen.building();
        UUID id = b.getItemFrameId();
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            Seen seen = Seen.listen(subs, tv);
            put(level, kitchen.east(), Blocks.AIR);
            tv.mgr().noteBlockChanged(kitchen.east());
            waitFor(helper, 5, 200, () -> !seen.blocks(id).isEmpty(), () -> helper.runAfterDelay(40,
                    () -> step(helper, c, subs, () -> {
                List<TownKitEvents.BuildingBlocksChanged> mine = seen.blocks(id);
                c.checkHooksOn("one BUILDING_BLOCKS_CHANGED", mine.size() == 1, seen);
                c.checkHooksOn("the table removed, nothing added", mine.size() == 1
                        && mine.get(0).removed().equals(Set.of(kitchen.east())) && mine.get(0).added().isEmpty(), mine);
                c.check("Towntopia keeps the kitchen valid", b.isValid(), b.isValid());
                c.check("so no validity event", seen.validity(id).isEmpty(), seen);
                removeAll(subs);
                BehaviourTests.forget(tv);
                BehaviourTests.finish(helper, c);
            })));
        }));
    }

    /**
     * Stone in front of the kitchen's door: after Towntopia's 400-tick access grace it turns the kitchen off (one
     * BUILDING_VALIDITY_CHANGED, invalid, {@code room_unreachable}); clearing the door turns it back on (valid, no
     * problem).
     */
    @GameTest(template = "empty", timeoutTicks = 1500, batch = "townkit_bevents_invalid")
    public static void blockedDoorFiresInvalid(GameTestHelper helper) {
        Checks c = new Checks("blockedDoorFiresInvalid");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 9200, 7600).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null) return;
        Building b = kitchen.building();
        UUID id = b.getItemFrameId();
        BlockPos outside = b.getDoorPos().north();
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            c.check("valid before", b.isValid(), b.isValid());
            Seen seen = Seen.listen(subs, tv);
            put(level, outside, Blocks.STONE);
            put(level, outside.above(), Blocks.STONE);
            tv.mgr().noteBlockChanged(outside);
            tv.mgr().noteBlockChanged(outside.above());
            tv.mgr().rebuildNow(level);
            waitFor(helper, 20, 900, () -> !b.isValid(), () -> step(helper, c, subs, () -> {
                c.check("Towntopia turned it off", !b.isValid(), "shape " + b.isShapeValid() + " access "
                        + b.isAccessValid());
                List<TownKitEvents.BuildingValidityChanged> off = seen.validity(id);
                c.checkHooksOn("one BUILDING_VALIDITY_CHANGED", off.size() == 1, seen);
                c.checkHooksOn("invalid, room_unreachable", off.size() == 1 && !off.get(0).valid()
                        && off.get(0).problem().equals(Optional.of("room_unreachable")), off);
                put(level, outside, Blocks.AIR);
                put(level, outside.above(), Blocks.AIR);
                tv.mgr().noteBlockChanged(outside);
                tv.mgr().noteBlockChanged(outside.above());
                tv.mgr().rebuildNow(level);
                waitFor(helper, 10, 400, b::isValid, () -> step(helper, c, subs, () -> {
                    List<TownKitEvents.BuildingValidityChanged> all = seen.validity(id);
                    c.check("valid again", b.isValid(), b.isValid());
                    c.checkHooksOn("then one more event: valid, no problem", all.size() == 2 && all.get(1).valid()
                            && all.get(1).problem().isEmpty(), all);
                    removeAll(subs);
                    BehaviourTests.forget(tv);
                    BehaviourTests.finish(helper, c);
                }));
            }));
        }));
    }

    /**
     * Towntopia's re-checks of an unchanged kitchen fire nothing; a crafting table added before the next re-check
     * fires one BUILDING_BLOCKS_CHANGED (the control: the hooks were live all along).
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_bevents_quiet")
    public static void noEventWhenNothingChanged(GameTestHelper helper) {
        Checks c = new Checks("noEventWhenNothingChanged");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 9600, 7600).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        Room kitchen = room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
            put(level, r.west(), Blocks.FURNACE);
            put(level, r.east(), Blocks.CRAFTING_TABLE);
        }).orElse(null);
        if (kitchen == null) return;
        Building b = kitchen.building();
        UUID id = b.getItemFrameId();
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            Seen seen = Seen.listen(subs, tv);
            c.checkHooksOn("the validate hook is attached", attachedEventHooks().stream()
                    .anyMatch(k -> k.endsWith("village.building.Building#validate(ServerLevel)")), attachedEventHooks());
            for (int i = 0; i < 3; i++) tv.mgr().recheckBuilding(level, b);
            helper.runAfterDelay(40, () -> step(helper, c, subs, () -> {
                c.check("no event from re-checks that change nothing", seen.total() == 0, seen);
                put(level, kitchen.middle(), Blocks.CRAFTING_TABLE);
                tv.mgr().recheckBuilding(level, b);
                List<TownKitEvents.BuildingBlocksChanged> mine = seen.blocks(id);
                c.checkHooksOn("control: a new table fires one BUILDING_BLOCKS_CHANGED", mine.size() == 1
                        && mine.get(0).added().equals(Set.of(kitchen.middle())) && mine.get(0).removed().isEmpty(), seen);
                c.check("and no validity event", seen.validity(id).isEmpty(), seen);
                removeAll(subs);
                put(level, kitchen.middle(), Blocks.AIR);
                BehaviourTests.forget(tv);
                BehaviourTests.finish(helper, c);
            }));
        }));
    }

    /**
     * No listener, no hooks: each building event attaches its hooks with its first listener and drops them with its
     * last; a second listener doesn't attach them twice.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_bevents_nohooks")
    public static void noListenerNoHooks(GameTestHelper helper) {
        Checks c = new Checks("noListenerNoHooks");
        c.check("no building-event hook attached with no listener", attachedEventHooks().isEmpty(), attachedEventHooks());
        for (TypedEvent<?> e : BUILDING_EVENTS) {
            Set<String> keys = BuildingEventHooks.keys(e);
            c.check(e.name() + " is available exactly when hooks are on", e.available() == !HooksOff.off(), e.name());
            c.check(e.name() + " has hooks", !keys.isEmpty(), keys);
            Subscription one = e.listen(OWNER, x -> {
            });
            Subscription two = e.listen(OWNER, x -> {
            });
            c.checkHooksOn(e.name() + ": its first listener attaches exactly its hooks", attachedEventHooks().equals(new java.util.TreeSet<>(keys)),
                    attachedEventHooks());
            long entries = Hooks.allEntries().stream().filter(en -> TownKit.MODID.equals(en.owner)
                    && keys.contains(en.hook.key())).count();
            c.checkHooksOn(e.name() + ": one before and one after per hook, however many listeners", entries == 2L * keys.size(),
                    entries + " for " + keys.size());
            one.remove();
            c.checkHooksOn(e.name() + ": still attached while a listener is left", attachedEventHooks().equals(new java.util.TreeSet<>(keys)),
                    attachedEventHooks());
            two.remove();
            c.check(e.name() + ": detached with its last listener", attachedEventHooks().isEmpty(), attachedEventHooks());
        }
        c.finish(helper);
    }

    /** One town-hall BUILDING_ADDED as seen inside the handler: did its village exist, could the read API find it. */
    record Delivery(TownKitEvents.BuildingAdded event, boolean villageExists, boolean readable) {
    }

    /**
     * Founding a village (Towntopia's {@code createFromTownHall}, via the test village helper) registers its town hall
     * inside {@code initialize}, before the registry knows the village: the ADDED is held until the village is
     * registered, so inside the handler the village exists and the read API resolves the building.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_bevents_founding")
    public static void foundingTownHallAddedOnceVillageExists(GameTestHelper helper) {
        Checks c = new Checks("foundingTownHallAddedOnceVillageExists");
        List<Subscription> subs = new ArrayList<>();
        List<Delivery> seen = new ArrayList<>();
        subs.add(TownKitEvents.BUILDING_ADDED.listen(OWNER, e -> {
            if (e.building().type().getPath().equals("town_hall")) {
                seen.add(new Delivery(e, e.village().exists(),
                        TownKitBuildings.details(e.village(), e.building().frameId()).isPresent()));
            }
        }));
        TestVillage tv = BehaviourTests.village(helper, 10400, 7600).orElse(null);
        if (tv == null) {
            removeAll(subs);
            return;
        }
        UUID village = tv.mgr().getVillageId();
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            List<Delivery> mine = seen.stream().filter(d -> d.event().village().id().equals(village)).toList();
            c.checkHooksOn("one BUILDING_ADDED for the new village's town hall", mine.size() == 1, seen);
            if (mine.size() == 1) {
                UUID frame = mine.get(0).event().building().frameId();
                c.check("it is the village's valid town hall", tv.mgr().hasValidTownHall(frame), frame);
                c.check("delivered once the village exists", mine.get(0).villageExists(), mine);
                c.check("the read API resolves it inside the handler", mine.get(0).readable(), mine);
            }
            removeAll(subs);
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /**
     * The town hall token moved to another room: the token is taken from its frame (Towntopia notices it missing),
     * then hung in a new room's frame ({@code createFromTownHall}, as hanging it does). Towntopia's
     * {@code restoreOrphan} clears the old town hall ({@code clearTownHallsForRecovery}) and registers the new one:
     * one BUILDING_REMOVED for the old, one BUILDING_ADDED for the new.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_bevents_orphan")
    public static void movedTownHallRemovesTheOldOne(GameTestHelper helper) {
        Checks c = new Checks("movedTownHallRemovesTheOldOne");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 10800, 7600).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        UUID village = tv.mgr().getVillageId();
        Building oldHall = tv.mgr().getBuildings().stream()
                .filter(b -> b instanceof com.alex.test.village.building.TownHallBuilding).findFirst().orElse(null);
        if (oldHall == null) {
            helper.fail("the test village has no town hall");
            return;
        }
        UUID oldId = oldHall.getItemFrameId();
        ItemFrame oldFrame = frameOf(level, oldHall);
        ScenarioBuilder.Room newRoom = bareRoom(helper, tv, 12, -14, s -> {
        });
        com.alex.test.village.VillageManagerRegistry reg = com.alex.test.village.VillageManagerRegistry.get(level.getServer());
        helper.runAfterDelay(20, () -> step(helper, c, subs, () -> {
            c.check("the old town hall's frame is there", oldFrame != null, oldHall.getItemFramePos());
            if (oldFrame == null) {
                c.finish(helper);
                return;
            }
            Seen seen = Seen.listen(subs, tv);
            ItemStack token = oldFrame.getItem().copy();
            oldFrame.setItem(ItemStack.EMPTY, false); // taken out quietly: no frame check, the building stays
            // Towntopia notices the token gone: it waits for it, or orphans the village (both allow the move)
            BooleanSupplier noticed = () -> reg.isAwaitingTownHallToken(village) || reg.isVillageInactive(village);
            waitFor(helper, 5, 300, noticed, () -> step(helper, c, subs, () -> {
                c.check("Towntopia noticed the token missing", noticed.getAsBoolean(), "");
                c.check("the old town hall is still registered", tv.mgr().getBuilding(oldId) == oldHall, "");
                newRoom.frame().setItem(token, false);
                com.alex.test.village.VillageManager back = reg.createFromTownHall(level, newRoom.frame());
                UUID newId = newRoom.frame().getUUID();
                c.check("Towntopia moved the town hall", back == tv.mgr() && tv.mgr().hasValidTownHall(newId), back);
                c.check("the old one is gone", tv.mgr().getBuilding(oldId) == null, tv.mgr().getBuilding(oldId));
                c.checkHooksOn("one BUILDING_REMOVED for the old town hall", seen.removed(oldId).size() == 1, seen);
                c.checkHooksOn("one BUILDING_ADDED for the new town hall", seen.added(newId).size() == 1, seen);
                removeAll(subs);
                BehaviourTests.forget(tv);
                BehaviourTests.finish(helper, c);
            }));
        }));
    }

    /** The deprecated BUILDING_CHANGE still fires "registered" for a placed building. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_bevents_legacy")
    @SuppressWarnings("deprecation")
    public static void buildingChangeStillWorks(GameTestHelper helper) {
        Checks c = new Checks("buildingChangeStillWorks");
        List<Subscription> subs = new ArrayList<>();
        TestVillage tv = BehaviourTests.village(helper, 10000, 7600).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        ScenarioBuilder.Room r = bareRoom(helper, tv, 12, 14, s -> {
            put(level, s.west(), Blocks.FURNACE);
            put(level, s.east(), Blocks.CRAFTING_TABLE);
        });
        UUID village = tv.mgr().getVillageId();
        List<TownKitEvents.BuildingChange> changes = new ArrayList<>();
        Subscription legacy = TownKitEvents.BUILDING_CHANGE.listen(OWNER, e -> {
            if (e.village().id().equals(village)) changes.add(e);
        });
        subs.add(legacy);
        c.checkHooksOn("still available", TownKitEvents.BUILDING_CHANGE.available() && !legacy.isDisabled(), legacy.isDisabled());
        placeToken(tv, r, ModItems.KITCHEN::get);
        waitFor(helper, 5, 200, () -> registeredAt(tv, r.doorPos()) != null, () -> step(helper, c, subs, () -> {
            Building b = registeredAt(tv, r.doorPos());
            c.check("registered", b != null, r.doorPos());
            if (b != null) {
                List<TownKitEvents.BuildingChange> mine = changes.stream()
                        .filter(e -> e.building().frameId().equals(b.getItemFrameId())).toList();
                c.checkHooksOn("one BUILDING_CHANGE \"registered\"", mine.size() == 1
                        && "registered".equals(mine.get(0).change()), changes);
            }
            removeAll(subs);
            BehaviourTests.forget(tv);
            BehaviourTests.finish(helper, c);
        }));
    }

    /** Counts ERROR+ events and events with a throwable on the root logger while attached. */
    static final class ErrorCounter extends AbstractAppender {
        static final String PROBE = "TownKit test: log capture probe (expected, not an error)";
        final AtomicInteger count = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean probeSeen = new java.util.concurrent.atomic.AtomicBoolean();
        final List<String> first = java.util.Collections.synchronizedList(new ArrayList<>());

        private ErrorCounter() {
            super("townkit-test-errors", null, null, true, Property.EMPTY_ARRAY);
        }

        static ErrorCounter attach() {
            ErrorCounter a = new ErrorCounter();
            a.start();
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            config.getRootLogger().addAppender(a, Level.ALL, null);
            ctx.updateLoggers();
            return a;
        }

        void detach() {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().removeAppender(getName());
            ctx.updateLoggers();
            stop();
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.ERROR) || event.getThrown() != null) {
                if (PROBE.equals(event.getMessage().getFormattedMessage())) {
                    probeSeen.set(true);
                    return;
                }
                count.incrementAndGet();
                if (first.size() < 5) {
                    first.add(event.getLoggerName() + ": " + event.getMessage().getFormattedMessage()
                            + (event.getThrown() == null ? "" : " / " + event.getThrown()));
                }
            }
        }
    }
}
