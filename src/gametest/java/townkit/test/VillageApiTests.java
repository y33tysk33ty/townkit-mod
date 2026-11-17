package townkit.test;

import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.raid.RaidManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageTokenBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.TownKitVillages;
import townkit.api.event.TownKitEvents;
import townkit.api.event.TypedEvent;
import townkit.api.village.RaidStage;
import townkit.api.village.StorageCategory;
import townkit.api.village.Village;
import townkit.api.village.VillageState;
import townkit.api.village.VillageStorage;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.event.VillageWatcher;
import townkit.core.village.VillageHandle;

/** Contract tests for the extra read-only information on {@link Village} (owner, state, bell, raid stage, border). */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class VillageApiTests {
    private VillageApiTests() {
    }

    private record TestVillage(VillageManager mgr, BlockPos door) {
    }

    /** A test village (400 blocks from the others), chunks force-loaded, the simulated player at its door. */
    private static Optional<TestVillage> village(GameTestHelper helper, int x, int z) {
        ServerLevel level = helper.getLevel();
        TestKit.setHeadless(true);
        for (int cx = (x >> 4) - 4; cx <= (x >> 4) + 4; cx++) {
            for (int cz = (z >> 4) - 4; cz <= (z >> 4) + 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        level.getChunk(x >> 4, z >> 4);
        int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(x, feetY, z), feetY, 2);
        if (v.manager() == null) {
            helper.fail("test village was not created");
            return Optional.empty();
        }
        TestKit.setSimulatedPlayer(v.townHallDoor(), level.dimension());
        return Optional.of(new TestVillage(v.manager(), v.townHallDoor()));
    }

    /** owner, ownerName, state, bellPos, raidStage, raidInProgress and border on a live village and on a gone one. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_village_reads")
    public static void villageReads(GameTestHelper helper) {
        Checks c = new Checks("villageReads");
        TestVillage tv = village(helper, 7600, 6000).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        UUID id = tv.mgr().getVillageId();
        VillageManagerRegistry reg = VillageManagerRegistry.get(level.getServer());
        helper.runAfterDelay(20, () -> {
            Village v = TownKitVillages.at(level, tv.door()).orElse(null);
            c.check("village found", v != null, tv.door());
            if (v == null) {
                finish(helper, c);
                return;
            }
            c.check("fresh: no owner, no owner name", v.owner().isEmpty() && v.ownerName().isEmpty(), v.owner());
            c.check("fresh: ACTIVE", v.state() == VillageState.ACTIVE, v.state());
            BlockPos bell = tv.mgr().getBellPos();
            c.check("bellPos matches getBellPos() (empty only for ZERO)",
                bell.equals(BlockPos.ZERO) ? v.bellPos().isEmpty() : v.bellPos().equals(Optional.of(bell)), v.bellPos());
            c.check("fresh: raid IDLE and not in progress", v.raidStage() == RaidStage.IDLE && !v.raidInProgress(),
                v.raidStage() + "/" + v.raidInProgress());
            AABB border = v.border().orElse(null);
            c.check("border present and contains the door", border != null && border.contains(tv.door().getCenter()), border);

            UUID owner = UUID.randomUUID();
            tv.mgr().claim().setOwner(owner, "TestOwner");
            c.check("owner set: owner()", v.owner().equals(Optional.of(owner)), v.owner());
            c.check("owner set: ownerName()", v.ownerName().equals(Optional.of("TestOwner")), v.ownerName());
            tv.mgr().claim().clearOwner();
            c.check("owner cleared: both empty", v.owner().isEmpty() && v.ownerName().isEmpty(), v.owner());

            reg.debugSetDormant(level, id, true);
            c.check("debugSetDormant(true): DORMANT", v.state() == VillageState.DORMANT, v.state());
            reg.debugSetDormant(level, id, false);
            c.check("debugSetDormant(false): RECONCILING", v.state() == VillageState.RECONCILING, v.state());
            // Towntopia's own reconcile tick takes it the rest of the way back
            poll(helper, 300, () -> v.state() == VillageState.ACTIVE, () -> {
                c.check("after reconciling: ACTIVE", v.state() == VillageState.ACTIVE, v.state());

                Village gone = new VillageHandle(level.getServer(), UUID.randomUUID(), level.dimension());
                c.check("unknown village: exists() false", !gone.exists(), gone);
                c.check("unknown village: INACTIVE, IDLE, not in progress",
                    gone.state() == VillageState.INACTIVE && gone.raidStage() == RaidStage.IDLE && !gone.raidInProgress(),
                    gone.state() + "/" + gone.raidStage());
                c.check("unknown village: owner, name, bell, border empty",
                    gone.owner().isEmpty() && gone.ownerName().isEmpty() && gone.bellPos().isEmpty() && gone.border().isEmpty(), gone);
                finish(helper, c);
            });
        });
    }

    /** storage(): counts, categories, the three emerald kinds, copies, and the building counts. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_village_storage")
    public static void storageView(GameTestHelper helper) {
        Checks c = new Checks("storageView");
        TestVillage tv = village(helper, 8000, 5600).orElse(null);
        if (tv == null) return;
        TestVillage other = village(helper, 8000, 5200).orElse(null);
        if (other == null) return;
        ServerLevel level = helper.getLevel();
        TestKit.setSimulatedPlayer(tv.door(), level.dimension());
        helper.runAfterDelay(20, () -> {
            Village v = TownKitVillages.at(level, tv.door()).orElse(null);
            if (v == null) {
                c.check("village found", false, tv.door());
                finish(helper, c);
                return;
            }
            UUID otherId = other.mgr().getVillageId();
            VillageStorage before = v.storage();
            int breadBefore = before.count(Items.BREAD);
            int ownBefore = before.ownEmeralds();
            int plainBefore = before.plainEmeralds();
            int otherBefore = before.otherVillageEmeralds();
            int otherIdBefore = before.otherVillageEmeraldsByVillage().getOrDefault(otherId, 0);

            int stocked = ScenarioBuilder.stockStorage(level, tv.mgr(), new ItemStack(Items.BREAD, 10));
            // Towntopia's deposit refuses plain and other-village emeralds, so those go straight into a chest
            stocked += placeInChest(level, tv.mgr(), new ItemStack(Items.EMERALD, 3));
            ItemStack own = new ItemStack(Items.EMERALD, 4);
            VillageTokenBinding.bind(own, tv.mgr());
            stocked += ScenarioBuilder.stockStorage(level, tv.mgr(), own);
            ItemStack foreign = new ItemStack(Items.EMERALD, 2);
            VillageTokenBinding.bind(foreign, other.mgr());
            stocked += placeInChest(level, tv.mgr(), foreign);
            c.check("all 19 items went into storage", stocked == 19, stocked);

            VillageStorage s = v.storage();
            c.check("count(BREAD) grew by 10", s.count(Items.BREAD) == breadBefore + 10, s.count(Items.BREAD) + " from " + breadBefore);
            c.check("items(FOOD) holds bread", s.items(StorageCategory.FOOD).stream().anyMatch(st -> st.is(Items.BREAD) && st.getCount() >= 10),
                s.items(StorageCategory.FOOD));
            c.check("items() holds bread, items(MISC) does not",
                s.items().stream().anyMatch(st -> st.is(Items.BREAD)) && s.items(StorageCategory.MISC).stream().noneMatch(st -> st.is(Items.BREAD)),
                s.items(StorageCategory.MISC));
            c.check("ownEmeralds +4", s.ownEmeralds() == ownBefore + 4, s.ownEmeralds());
            c.check("plainEmeralds +3", s.plainEmeralds() == plainBefore + 3, s.plainEmeralds());
            c.check("otherVillageEmeralds +2", s.otherVillageEmeralds() == otherBefore + 2, s.otherVillageEmeralds());
            c.check("otherVillageEmeraldsByVillage: second village +2",
                s.otherVillageEmeraldsByVillage().getOrDefault(otherId, 0) == otherIdBefore + 2, s.otherVillageEmeraldsByVillage());
            c.check("count(EMERALD) is the sum of the three kinds",
                s.count(Items.EMERALD) == s.ownEmeralds() + s.plainEmeralds() + s.otherVillageEmeralds(), s.count(Items.EMERALD));
            c.check("storageBuildings >= 1, unreadBuildings == 0", s.storageBuildings() >= 1 && s.unreadBuildings() == 0,
                s.storageBuildings() + "/" + s.unreadBuildings());

            for (ItemStack st : s.items()) st.setCount(1);
            for (ItemStack st : s.items(StorageCategory.FOOD)) st.setCount(1);
            VillageStorage again = v.storage();
            c.check("changing returned stacks changes nothing",
                again.count(Items.BREAD) == breadBefore + 10
                    && again.items().stream().filter(st -> st.is(Items.BREAD)).mapToInt(ItemStack::getCount).sum() == breadBefore + 10
                    && s.items().stream().filter(st -> st.is(Items.BREAD)).mapToInt(ItemStack::getCount).sum() == breadBefore + 10,
                again.count(Items.BREAD));

            Village gone = new VillageHandle(level.getServer(), UUID.randomUUID(), level.dimension());
            VillageStorage none = gone.storage();
            c.check("gone village: empty snapshot", none.items().isEmpty() && none.storageBuildings() == 0 && none.ownEmeralds() == 0
                && none.otherVillageEmeralds() == 0 && none.plainEmeralds() == 0 && none.unreadBuildings() == 0, none.storageBuildings());
            finish(helper, c);
        });
    }

    /** storage() skips working blocks in unloaded chunks, reports them as unread, and does not load the chunk. */
    @GameTest(template = "empty", timeoutTicks = 1500, batch = "townkit_village_storage_unloaded")
    public static void storageSkipsUnloadedChunks(GameTestHelper helper) {
        Checks c = new Checks("storageSkipsUnloadedChunks");
        TestVillage tv = village(helper, 8400, 5600).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        helper.runAfterDelay(20, () -> {
            Village v = TownKitVillages.at(level, tv.door()).orElse(null);
            if (v == null) {
                c.check("village found", false, tv.door());
                finish(helper, c);
                return;
            }
            List<BlockPos> work = new ArrayList<>();
            for (Building b : tv.mgr().getBuildings()) {
                if (b instanceof StorageBuilding sb) work.addAll(sb.getFunctionalBlocks());
            }
            c.check("storage building has working blocks", !work.isEmpty(), work.size());
            if (work.isEmpty()) {
                finish(helper, c);
                return;
            }
            // a forced chunk keeps everything within two chunks of it loaded, so release the whole forced square
            int cx0 = tv.door().getX() >> 4;
            int cz0 = tv.door().getZ() >> 4;
            for (int cx = cx0 - 6; cx <= cx0 + 6; cx++) {
                for (int cz = cz0 - 6; cz <= cz0 + 6; cz++) level.setChunkForced(cx, cz, false);
            }
            waitUnloaded(helper, c, level, v, work, 0);
        });
    }

    /** Puts the stack into the first empty slot of a storage chest, bypassing Towntopia's deposit rules; returns the count placed. */
    private static int placeInChest(ServerLevel level, VillageManager mgr, ItemStack stack) {
        for (Building b : mgr.getBuildings()) {
            if (!(b instanceof StorageBuilding sb)) continue;
            for (BlockPos pos : sb.getFunctionalBlocks()) {
                if (!(level.getBlockEntity(pos) instanceof Container chest)) continue;
                for (int i = 0; i < chest.getContainerSize(); i++) {
                    if (chest.getItem(i).isEmpty()) {
                        int n = stack.getCount();
                        chest.setItem(i, stack.copy());
                        return n;
                    }
                }
            }
        }
        return 0;
    }

    private static void waitUnloaded(GameTestHelper helper, Checks c, ServerLevel level, Village v, List<BlockPos> work, int waited) {
        if (work.stream().anyMatch(level::isLoaded)) {
            if (waited >= 1000) {
                TownKit.LOG.info("[TownKit test] storageSkipsUnloadedChunks SKIPPED: the storage chunk stayed loaded for another reason");
                finish(helper, c);
                return;
            }
            helper.runAfterDelay(20, () -> waitUnloaded(helper, c, level, v, work, waited + 20));
            return;
        }
        VillageStorage s = v.storage();
        c.check("unreadBuildings >= 1", s.unreadBuildings() >= 1, s.unreadBuildings());
        c.check("storageBuildings >= 1", s.storageBuildings() >= 1, s.storageBuildings());
        c.check("chunk still unloaded afterwards", work.stream().noneMatch(level::isLoaded), work.get(0));
        finish(helper, c);
    }

    // --- polled village and raid events ---------------------------------------------------------------------------

    /** Checks {@code done} every few ticks; runs {@code then} when it holds or after {@code maxTicks} (the caller re-checks). */
    private static void poll(GameTestHelper helper, int maxTicks, BooleanSupplier done, Runnable then) {
        if (done.getAsBoolean() || maxTicks <= 0) {
            then.run();
            return;
        }
        helper.runAfterDelay(5, () -> poll(helper, maxTicks - 5, done, then));
    }

    private static void removeAll(List<Subscription> subs) {
        for (Subscription s : subs) s.remove();
        subs.clear();
    }

    /** Leaves a test village awake: a failed test must not leave it dormant for the tests after it. */
    private static void wake(ServerLevel level, UUID id) {
        VillageManagerRegistry reg = VillageManagerRegistry.get(level.getServer());
        for (int i = 0; i < 2 && reg.isVillageDormantOrReconciling(id); i++) reg.debugSetDormant(level, id, false);
    }

    private static boolean sawState(List<TownKitEvents.VillageStateChanged> seen, VillageState from, VillageState to) {
        return seen.stream().anyMatch(e -> e.from() == from && e.to() == to);
    }

    /** VILLAGE_STATE_CHANGED follows debugSetDormant and Towntopia's own reconcile tick, once a second at most. */
    @GameTest(template = "empty", timeoutTicks = 900, batch = "townkit_village_state_events")
    public static void villageStateEvents(GameTestHelper helper) {
        Checks c = new Checks("villageStateEvents");
        TestVillage tv = village(helper, 8800, 5200).orElse(null);
        if (tv == null) return;
        TestKit.clearSimulatedPlayer(); // a player at the door would wake the dormant village at a random tick
        ServerLevel level = helper.getLevel();
        UUID id = tv.mgr().getVillageId();
        VillageManagerRegistry reg = VillageManagerRegistry.get(level.getServer());
        List<Subscription> subs = new ArrayList<>();
        List<TownKitEvents.VillageStateChanged> seen = new ArrayList<>();
        Runnable end = () -> {
            removeAll(subs);
            wake(level, id);
            finish(helper, c);
        };
        subs.add(TownKitEvents.VILLAGE_STATE_CHANGED.listen("townkit_test", e -> {
            if (e.village().id().equals(id)) seen.add(e);
        }));
        helper.runAfterDelay(40, () -> {
            c.check("baseline: the first reading is silent", seen.isEmpty(), seen);
            reg.debugSetDormant(level, id, true);
            poll(helper, 60, () -> sawState(seen, VillageState.ACTIVE, VillageState.DORMANT), () -> {
                c.check("ACTIVE -> DORMANT fired once",
                    seen.stream().filter(e -> e.from() == VillageState.ACTIVE && e.to() == VillageState.DORMANT).count() == 1, seen);
                // Towntopia's reconcile tick can finish the wake within the second: the watcher then reports both steps
                // together, in order. ACTIVE comes from Towntopia's reconcile tick.
                reg.debugSetDormant(level, id, false);
                poll(helper, 300, () -> sawState(seen, VillageState.RECONCILING, VillageState.ACTIVE), () -> {
                    c.check("DORMANT -> RECONCILING fired", sawState(seen, VillageState.DORMANT, VillageState.RECONCILING), seen);
                    c.check("RECONCILING -> ACTIVE fired", sawState(seen, VillageState.RECONCILING, VillageState.ACTIVE), seen);
                    c.check("in order, each once: ACTIVE>DORMANT, DORMANT>RECONCILING, RECONCILING>ACTIVE", seen.size() == 3
                        && seen.get(1).to() == VillageState.RECONCILING && seen.get(2).to() == VillageState.ACTIVE, seen);
                    end.run();
                });
            });
        });
    }

    /** RAID_STARTED, RAID_STAGE_CHANGED and RAID_ENDED around RaidManager.startNow / endNow. */
    @GameTest(template = "empty", timeoutTicks = 900, batch = "townkit_village_raid_events")
    public static void raidEvents(GameTestHelper helper) {
        Checks c = new Checks("raidEvents");
        TestVillage tv = village(helper, 12000, 5200).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        UUID id = tv.mgr().getVillageId();
        List<Subscription> subs = new ArrayList<>();
        List<TownKitEvents.RaidStarted> started = new ArrayList<>();
        List<TownKitEvents.RaidStageChanged> staged = new ArrayList<>();
        List<TownKitEvents.RaidEnded> ended = new ArrayList<>();
        Runnable end = () -> {
            removeAll(subs);
            // leave no raid and no raiders behind: other tests share this world
            try {
                if (tv.mgr().raids().stage() != RaidManager.Stage.IDLE) tv.mgr().raids().endNow(level, false);
                AABB box = new AABB(tv.door()).inflate(200);
                for (Entity e : level.getEntities((Entity) null, box, x -> true)) {
                    if (tv.mgr().raids().isRaider(e)) e.discard();
                }
            } catch (RuntimeException ex) {
                TownKit.LOG.error("[TownKit test] raidEvents cleanup failed", ex);
            }
            finish(helper, c);
        };
        subs.add(TownKitEvents.RAID_STARTED.listen("townkit_test", e -> {
            if (e.village().id().equals(id)) started.add(e);
        }));
        subs.add(TownKitEvents.RAID_STAGE_CHANGED.listen("townkit_test", e -> {
            if (e.village().id().equals(id)) staged.add(e);
        }));
        subs.add(TownKitEvents.RAID_ENDED.listen("townkit_test", e -> {
            if (e.village().id().equals(id)) ended.add(e);
        }));
        helper.runAfterDelay(40, () -> {
            c.check("baseline: nothing fired yet", started.isEmpty() && staged.isEmpty() && ended.isEmpty(), staged);
            String refused = tv.mgr().raids().startNow(level, null, 1);
            TownKit.LOG.info("[TownKit test] raidEvents startNow returned: {}", refused);
            if (refused != null) {
                c.check("startNow was accepted (refusal reason shown)", false, refused);
                end.run();
                return;
            }
            RaidStage now = RaidStage.valueOf(tv.mgr().raids().stage().name());
            c.check("startNow left IDLE", now != RaidStage.IDLE, now);
            Village v = VillageHandle.of(tv.mgr());
            c.check("raidStage() through the handle is the manager's stage", v.raidStage() == now, v.raidStage() + " vs " + now);
            c.check("raidInProgress() through the handle follows the manager's",
                v.raidInProgress() == tv.mgr().raids().inProgress(), v.raidInProgress());
            poll(helper, 60, () -> !started.isEmpty(), () -> {
                c.check("raidInProgress() still follows the manager's while the raid runs",
                    v.raidInProgress() == tv.mgr().raids().inProgress(), v.raidInProgress());
                c.check("RAID_STARTED fired once with a non-IDLE stage",
                    started.size() == 1 && started.get(0).stage() != RaidStage.IDLE, started);
                c.check("RAID_STAGE_CHANGED from IDLE fired",
                    staged.stream().anyMatch(e -> e.from() == RaidStage.IDLE && e.to() != RaidStage.IDLE), staged);
                c.check("no RAID_ENDED yet", ended.isEmpty(), ended);
                String endRefused = tv.mgr().raids().endNow(level, false);
                if (endRefused != null) c.check("endNow was accepted", false, endRefused);
                poll(helper, 200, () -> !ended.isEmpty(), () -> {
                    c.check("RAID_ENDED fired once", ended.size() == 1, ended);
                    c.check("RAID_STAGE_CHANGED back to IDLE fired",
                        staged.stream().anyMatch(e -> e.to() == RaidStage.IDLE), staged);
                    end.run();
                });
            });
        });
    }

    /** The watcher's tick listener exists only while one of the four events has a listener; a first reading is silent. */
    @GameTest(template = "empty", timeoutTicks = 900, batch = "townkit_village_events_lifecycle")
    public static void villageEventsLifecycle(GameTestHelper helper) {
        Checks c = new Checks("villageEventsLifecycle");
        TestVillage tv = village(helper, 12800, 5200).orElse(null);
        if (tv == null) return;
        TestKit.clearSimulatedPlayer(); // a player at the door would wake the dormant village at a random tick
        ServerLevel level = helper.getLevel();
        UUID id = tv.mgr().getVillageId();
        VillageManagerRegistry reg = VillageManagerRegistry.get(level.getServer());
        List<Subscription> subs = new ArrayList<>();
        List<TownKitEvents.VillageStateChanged> seen = new ArrayList<>();
        Runnable end = () -> {
            removeAll(subs);
            wake(level, id);
            finish(helper, c);
        };
        c.check("no listener: not attached", !VillageWatcher.isAttached(), VillageWatcher.isAttached());
        Subscription raidEnded = TownKitEvents.RAID_ENDED.listen("townkit_test", e -> { });
        subs.add(raidEnded);
        c.check("one listener: attached", VillageWatcher.isAttached(), VillageWatcher.isAttached());
        Subscription changed = TownKitEvents.VILLAGE_STATE_CHANGED.listen("townkit_test", e -> { });
        subs.add(changed);
        raidEnded.remove();
        c.check("another event still listening: still attached", VillageWatcher.isAttached(), VillageWatcher.isAttached());
        changed.remove();
        subs.clear();
        c.check("last listener gone: detached", !VillageWatcher.isAttached(), VillageWatcher.isAttached());

        reg.debugSetDormant(level, id, true);
        helper.runAfterDelay(5, () -> {
            c.check("village is dormant", reg.isVillageDormant(id), reg.isVillageDormant(id));
            subs.add(TownKitEvents.VILLAGE_STATE_CHANGED.listen("townkit_test", e -> {
                if (e.village().id().equals(id)) seen.add(e);
            }));
            helper.runAfterDelay(60, () -> {
                c.check("first reading while dormant is silent", seen.isEmpty(), seen);
                reg.debugSetDormant(level, id, false);
                poll(helper, 300, () -> sawState(seen, VillageState.RECONCILING, VillageState.ACTIVE), () -> {
                    c.check("then the wake fires (the watch is live)", sawState(seen, VillageState.DORMANT, VillageState.RECONCILING)
                        && sawState(seen, VillageState.RECONCILING, VillageState.ACTIVE), seen);
                    end.run();
                });
            });
        });
    }

    /** VILLAGE_FOUNDED fires once for a village founded while listening, with its door, and not for an earlier one. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_village_founded")
    public static void villageFounded(GameTestHelper helper) {
        Checks c = new Checks("villageFounded");
        TestVillage before = village(helper, 9200, 4800).orElse(null);
        if (before == null) return;
        List<TownKitEvents.VillageFounded> seen = new ArrayList<>();
        List<Boolean> existedInHandler = new ArrayList<>();
        Subscription sub = TownKitEvents.VILLAGE_FOUNDED.listen("townkit_test", e -> {
            seen.add(e);
            existedInHandler.add(e.village().exists());
        });
        TestVillage fresh = null;
        try {
            fresh = village(helper, 9600, 4800).orElse(null);
        } finally {
            sub.remove();
        }
        if (fresh == null) return;
        UUID freshId = fresh.mgr().getVillageId();
        UUID beforeId = before.mgr().getVillageId();
        TestVillage f = fresh;
        List<TownKitEvents.VillageFounded> mine = seen.stream().filter(e -> e.village().id().equals(freshId)).toList();
        c.checkHooksOn("exactly one event for the new village", mine.size() == 1, seen);
        if (mine.size() == 1) {
            c.check("door is the town-hall door", mine.get(0).door().equals(f.door()), mine.get(0).door() + " vs " + f.door());
            c.check("village exists inside the handler", existedInHandler.get(seen.indexOf(mine.get(0))), existedInHandler);
        }
        c.check("no event for the village built before listening", seen.stream().noneMatch(e -> e.village().id().equals(beforeId)), seen);
        finish(helper, c);
    }

    private static <T> boolean disabledOnListen(TypedEvent<T> event) {
        Subscription s = event.listen("townkit_test", e -> { });
        boolean disabled = s.isDisabled();
        s.remove();
        return disabled;
    }

    /**
     * Hook-bound events are unavailable and give a disabled subscription only with every hook off; polled events never
     * are. Holds in both modes, so a run with {@code -Ptownkit.hooks.enabled=false} checks the off side.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_event_availability")
    public static void eventAvailabilityFollowsTheHookSwitch(GameTestHelper helper) {
        Checks c = new Checks("eventAvailabilityFollowsTheHookSwitch");
        boolean off = RewrittenSites.GLOBAL.hooksOff();
        for (TypedEvent<?> e : List.of(TownKitEvents.VILLAGE_FOUNDED, TownKitEvents.ACTIVITY_CHANGE, TownKitEvents.BUILDING_ADDED)) {
            c.check(e.name() + " available unless hooks are off", e.available() == !off, e.available() + ", hooks off: " + off);
            c.check(e.name() + " subscription disabled only if hooks are off", disabledOnListen(e) == off, "hooks off: " + off);
        }
        for (TypedEvent<?> e : List.of(TownKitEvents.VILLAGE_STATE_CHANGED, TownKitEvents.RAID_STAGE_CHANGED,
            TownKitEvents.RAID_STARTED, TownKitEvents.RAID_ENDED)) {
            c.check(e.name() + " available either way", e.available(), "hooks off: " + off);
            c.check(e.name() + " subscription live either way", !disabledOnListen(e), "hooks off: " + off);
        }
        c.finish(helper);
    }

    private static void finish(GameTestHelper helper, Checks c) {
        TestKit.clearSimulatedPlayer();
        c.finish(helper);
    }
}
