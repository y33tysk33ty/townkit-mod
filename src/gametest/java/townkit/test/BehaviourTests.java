package townkit.test;

import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.VillageManager;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.LeisureActivity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.serialization.Codec;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.brain.ActivityContext;
import townkit.api.brain.ActivityStatus;
import townkit.api.brain.ChoiceContext;
import townkit.api.brain.ChoicePhase;
import townkit.api.brain.ChoiceProvider;
import townkit.api.brain.InterruptProvider;
import townkit.api.brain.MoveStatus;
import townkit.api.brain.StopReason;
import townkit.api.brain.TownKitBrain;
import townkit.api.brain.VillagerActivity;
import townkit.api.TownKitVillages;
import townkit.api.event.TownKitEvents;
import townkit.api.village.Villager;
import townkit.api.villager.VillagerDataKey;
import townkit.core.TownKit;
import townkit.core.brain.ActivityRegistry;
import townkit.core.brain.CoreBrain;
import townkit.core.hook.Hooks;

/**
 * Contract tests for the stable villager-behaviour API (activities, choices, interrupts, moving, needs, villager data).
 * Run with ./gradlew runGameTestServer. Each test builds its own village, 400 blocks from the others.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class BehaviourTests {
    private BehaviourTests() {
    }

    record TestVillage(VillageManager mgr, BlockPos door, NpcEntity farmer) {
    }

    /** A test village with one farmer, chunks force-loaded, the simulated player at its door. */
    static Optional<TestVillage> village(GameTestHelper helper, int x, int z) {
        ServerLevel level = helper.getLevel();
        TestKit.setHeadless(true);
        forceChunks(level, x, z);
        int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(x, feetY, z), feetY, 2);
        if (v.manager() == null) {
            helper.fail("test village was not created");
            return Optional.empty();
        }
        TestKit.setSimulatedPlayer(v.townHallDoor(), level.dimension());
        NpcEntity farmer = ScenarioBuilder.spawnVillager(level, v.manager(), v.townHallDoor().north(3), Profession.FARMER);
        return Optional.of(new TestVillage(v.manager(), v.townHallDoor(), farmer));
    }

    /**
     * Force-loads the chunks of a test village at ({@code x}, {@code z}). The GameTest runner un-forces every forced
     * chunk after each batch, so a village kept across batches needs this again.
     */
    static void forceChunks(ServerLevel level, int x, int z) {
        for (int cx = (x >> 4) - 4; cx <= (x >> 4) + 4; cx++) {
            for (int cz = (z >> 4) - 4; cz <= (z >> 4) + 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        level.getChunk(x >> 4, z >> 4);
    }

    /**
     * Pins the world's day time for the rest of a test, and puts it back when the test ends (passed or failed). Tests
     * that queue an addon activity need Towntopia's daytime routine: from the villager's end of work (its own, a little
     * before 12000) Towntopia's evening plan replaces a queued activity in the same tick, before its first tick. The
     * GameTest server runs ticks back to back, so without a pin the day time a test starts at depends on how many ticks
     * the tests before it used, and drifts across that line from run to run.
     */
    static void pinDayTime(GameTestHelper helper, long time) {
        ServerLevel level = helper.getLevel();
        long before = level.getDayTime();
        level.setDayTime(time);
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                level.setDayTime(before);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                level.setDayTime(before);
            }

            @Override
            public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {
            }
        });
    }

    static void finish(GameTestHelper helper, Checks c) {
        TestKit.clearSimulatedPlayer();
        c.finish(helper);
    }

    /** Let Towntopia forget a test villager, so it doesn't go looking for its missing body during later tests. */
    static void forget(TestVillage tv) {
        tv.mgr().removeVillager(tv.farmer().getNpcId());
        if (!tv.farmer().isRemoved()) tv.farmer().discard();
    }

    /** The way Towntopia lets go of a villager whose chunk unloads. */
    static void unload(TestVillage tv) {
        tv.mgr().unbindVillager(tv.farmer());
        tv.farmer().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
    }

    /** What the test activities saw, per activity id. */
    static final class Record {
        int starts;
        int ticks;
        final List<StopReason> stops = new ArrayList<>();
    }

    static final Map<ResourceLocation, Record> RECORDS = new ConcurrentHashMap<>();

    static Record record(ResourceLocation id) {
        return RECORDS.computeIfAbsent(id, k -> new Record());
    }

    /** A test activity: ticks RUNNING {@code runFor} times then DONE; throws from tick when {@code boom}. */
    record TestActivity(ResourceLocation id, int runFor, boolean boom, boolean routine) implements VillagerActivity {
        @Override
        public void start(ActivityContext ctx) {
            record(this.id).starts++;
        }

        @Override
        public ActivityStatus tick(ActivityContext ctx) {
            if (this.boom) throw new RuntimeException("deliberate test failure");
            return ++record(this.id).ticks % this.runFor == 0 ? ActivityStatus.DONE : ActivityStatus.RUNNING;
        }

        @Override
        public void stop(ActivityContext ctx, StopReason reason) {
            record(this.id).stops.add(reason);
        }

        @Override
        public boolean allowsRoutineInterruption() {
            return this.routine;
        }
    }

    /** A test activity whose start throws (counted first); ticks DONE. */
    record StartBoomActivity(ResourceLocation id) implements VillagerActivity {
        @Override
        public void start(ActivityContext ctx) {
            record(this.id).starts++;
            throw new RuntimeException("deliberate test failure");
        }

        @Override
        public ActivityStatus tick(ActivityContext ctx) {
            record(this.id).ticks++;
            return ActivityStatus.DONE;
        }

        @Override
        public void stop(ActivityContext ctx, StopReason reason) {
            record(this.id).stops.add(reason);
        }
    }

    /** Test activities, registered once on first use. */
    static final class Acts {
        static final ResourceLocation STAND = ResourceLocation.parse("townkit_test:stand");
        static final ResourceLocation LOCKED = ResourceLocation.parse("townkit_test:locked");
        static final ResourceLocation BOOM = ResourceLocation.parse("townkit_test:boom");
        static final ResourceLocation WALK = ResourceLocation.parse("townkit_test:walk");
        static final ResourceLocation LONG = ResourceLocation.parse("townkit_test:long");
        static final ResourceLocation LOCKED_LONG = ResourceLocation.parse("townkit_test:locked_long");
        static final ResourceLocation START_BOOM = ResourceLocation.parse("townkit_test:start_boom");
        static final ResourceLocation FOLLOW = ResourceLocation.parse("townkit_test:follow");

        static {
            TownKitBrain.registerActivity(FOLLOW, v -> new FollowActivity());
            TownKitBrain.registerActivity(START_BOOM, v -> new StartBoomActivity(START_BOOM));
            TownKitBrain.registerActivity(STAND, v -> new TestActivity(STAND, 40, false, true));
            TownKitBrain.registerActivity(LOCKED, v -> new TestActivity(LOCKED, 40, false, false));
            TownKitBrain.registerActivity(BOOM, v -> new TestActivity(BOOM, 40, true, true));
            TownKitBrain.registerActivity(WALK, v -> new WalkActivity());
            TownKitBrain.registerActivity(LONG, v -> new TestActivity(LONG, 1_000_000, false, true));
            TownKitBrain.registerActivity(LOCKED_LONG, v -> new TestActivity(LOCKED_LONG, 1_000_000, false, false));
        }

        static void init() {
        }
    }

    /** Where the walk test activity goes next (set by the test before starting it), and what it saw. */
    static final class Walk {
        static volatile BlockPos goal;
        static volatile Entity target;
        static volatile MoveStatus result;
        static volatile String problem;
        static volatile int firstCallTick = -1;
        static volatile int calls;

        static void reset(BlockPos goal, Entity target) {
            Walk.goal = goal;
            Walk.target = target;
            result = null;
            problem = null;
            calls = 0;
        }
    }

    /** Moves to Walk.goal or Walk.target until the move is no longer MOVING, then records the answer. */
    static final class WalkActivity implements VillagerActivity {
        @Override
        public ResourceLocation id() {
            return Acts.WALK;
        }

        @Override
        public ActivityStatus tick(ActivityContext ctx) {
            Walk.calls++;
            MoveStatus s = Walk.target != null ? ctx.moveTo(Walk.target, 0.6F) : ctx.moveTo(Walk.goal, 0.6F);
            if (s == MoveStatus.MOVING) return ActivityStatus.RUNNING;
            Walk.result = s;
            Walk.problem = ctx.lastMoveProblem().orElse(null);
            return s == MoveStatus.ARRIVED ? ActivityStatus.DONE : ActivityStatus.FAILED;
        }
    }

    /** What the follow test activity saw: the status of each moveTo(Follow.target) call, in order. */
    static final class Follow {
        static volatile Entity target;
        static final List<MoveStatus> statuses = new ArrayList<>();
    }

    /** Follows Follow.target for as long as it runs, recording every answer. */
    static final class FollowActivity implements VillagerActivity {
        @Override
        public ResourceLocation id() {
            return Acts.FOLLOW;
        }

        @Override
        public ActivityStatus tick(ActivityContext ctx) {
            MoveStatus s = ctx.moveTo(Follow.target, 0.6F);
            Follow.statuses.add(s);
            return s == MoveStatus.FAILED || s == MoveStatus.STUCK ? ActivityStatus.FAILED : ActivityStatus.RUNNING;
        }
    }

    /** A route-map tile about {@code dx} blocks east of {@code from}. */
    static BlockPos routeTileNear(TestVillage tv, BlockPos from, int dx) {
        NavNode n = tv.mgr().getGraph().nearest(from.east(dx), 4);
        return n == null ? null : n.feetPos();
    }

    /** Moving: reach a route tile, fail at once off the map, follow a moving pig, fail when the target disappears. */
    @GameTest(template = "empty", timeoutTicks = 1600, batch = "townkit_behaviour_moves")
    public static void movesInVillage(GameTestHelper helper) {
        Checks c = new Checks("movesInVillage");
        Acts.init();
        TestVillage tv = village(helper, 10400, 6400).orElse(null);
        if (tv == null) return;
        pinDayTime(helper, 1000L); // daytime: Towntopia's evening plan would replace the queued activity
        ServerLevel level = helper.getLevel();
        Villager v = TownKitVillages.of(tv.farmer()).orElseThrow();
        int[] tick = {0};
        int[] stage = {0};
        Pig[] pig = {null};
        helper.onEachTick(() -> {
            tick[0]++;
            switch (stage[0]) {
                case 0 -> {
                    if (tick[0] < 40 || tv.mgr().getGraph() == null) return;
                    BlockPos goal = routeTileNear(tv, tv.farmer().blockPosition(), 10);
                    c.check("a route tile 10 blocks away exists", goal != null, goal);
                    Walk.reset(goal, null);
                    CoreBrain.start(v, Acts.WALK);
                    stage[0] = 1;
                    tick[0] = 0;
                }
                case 1 -> {
                    if (Walk.result == null && tick[0] < 400) return;
                    c.checkHooksOn("moveTo a route tile arrives", Walk.result == MoveStatus.ARRIVED, Walk.result + " " + Walk.problem);
                    Walk.reset(tv.farmer().blockPosition().east(500), null);
                    CoreBrain.start(v, Acts.WALK);
                    stage[0] = 2;
                    tick[0] = 0;
                }
                case 2 -> {
                    if (Walk.result == null && tick[0] < 100) return;
                    c.check("a goal off the route map fails on the first call, with a reason",
                        Walk.result == MoveStatus.FAILED && Walk.calls == 1 && Walk.problem != null, Walk.result + " after " + Walk.calls + " calls: " + Walk.problem);
                    BlockPos spot = routeTileNear(tv, tv.farmer().blockPosition(), 5);
                    pig[0] = EntityType.PIG.create(level);
                    pig[0].moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0F, 0F);
                    pig[0].setNoAi(true);
                    level.addFreshEntity(pig[0]);
                    Walk.reset(null, pig[0]);
                    CoreBrain.start(v, Acts.WALK);
                    stage[0] = 3;
                    tick[0] = 0;
                }
                case 3 -> {
                    if (tick[0] == 10) {
                        // the pig moves away along the route map before the villager reaches it
                        BlockPos further = routeTileNear(tv, pig[0].blockPosition(), 8);
                        if (further != null) pig[0].teleportTo(further.getX() + 0.5, further.getY(), further.getZ() + 0.5);
                    }
                    if (Walk.result == null && tick[0] < 500) return;
                    c.check("following a pig that moved arrives next to it", Walk.result == MoveStatus.ARRIVED
                        && tv.farmer().position().distanceTo(pig[0].position()) <= 2.5, Walk.result + " " + Walk.problem + " at " + tv.farmer().position().distanceTo(pig[0].position()));
                    Walk.reset(null, pig[0]);
                    BlockPos away = routeTileNear(tv, tv.farmer().blockPosition(), 12);
                    if (away != null) pig[0].teleportTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
                    CoreBrain.start(v, Acts.WALK);
                    stage[0] = 4;
                    tick[0] = 0;
                }
                case 4 -> {
                    if (tick[0] == 15) pig[0].discard();
                    if (Walk.result == null && tick[0] < 200) return;
                    c.check("a target that disappears mid-move fails with 'target gone'",
                        Walk.result == MoveStatus.FAILED && "target gone".equals(Walk.problem), Walk.result + " " + Walk.problem);
                    stage[0] = 5;
                    forget(tv);
                    finish(helper, c);
                }
                default -> {
                }
            }
        });
    }

    /** Following: once arrived, a target that walks off more than 4 blocks makes the follow MOVING again, then ARRIVED again. */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_behaviour_follow")
    public static void followResumesAfterTargetMoves(GameTestHelper helper) {
        Checks c = new Checks("followResumesAfterTargetMoves");
        Acts.init();
        TestVillage tv = village(helper, 12800, 6400).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        long dayTimeBefore = level.getDayTime();
        level.setDayTime(1000L);
        VillagerModel model = tv.mgr().getModel(tv.farmer().getNpcId());
        Villager v = TownKitVillages.of(tv.farmer()).orElseThrow();
        int[] tick = {0};
        int[] stage = {0};
        int[] mark = {0};
        Pig[] pig = {null};
        helper.onEachTick(() -> {
            tick[0]++;
            switch (stage[0]) {
                case 0 -> {
                    if (tick[0] < 40 || tv.mgr().getGraph() == null) return;
                    BlockPos spot = routeTileNear(tv, tv.farmer().blockPosition(), 5);
                    c.check("a route tile 5 blocks away exists", spot != null, spot);
                    pig[0] = EntityType.PIG.create(level);
                    pig[0].moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0F, 0F);
                    pig[0].setNoAi(true);
                    level.addFreshEntity(pig[0]);
                    synchronized (Follow.statuses) {
                        Follow.statuses.clear();
                    }
                    Follow.target = pig[0];
                    CoreBrain.start(v, Acts.FOLLOW);
                    stage[0] = 1;
                    tick[0] = 0;
                }
                case 1 -> {
                    boolean arrived = !Follow.statuses.isEmpty() && Follow.statuses.get(Follow.statuses.size() - 1) == MoveStatus.ARRIVED;
                    if (!arrived && tick[0] < 400) return;
                    c.check("the follow arrives next to the pig", arrived, Follow.statuses.isEmpty() ? "none" : Follow.statuses.get(Follow.statuses.size() - 1));
                    BlockPos away = routeTileNear(tv, pig[0].blockPosition(), 10);
                    c.check("a route tile 10 blocks past the pig exists", away != null, away);
                    pig[0].teleportTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
                    mark[0] = Follow.statuses.size();
                    stage[0] = 2;
                    tick[0] = 0;
                }
                case 2 -> {
                    if (tick[0] < 5) return;
                    List<MoveStatus> after = List.copyOf(Follow.statuses.subList(mark[0], Follow.statuses.size()));
                    c.check("the follow is MOVING again after the pig walked off", after.contains(MoveStatus.MOVING), after);
                    stage[0] = 3;
                    tick[0] = 0;
                }
                case 3 -> {
                    boolean arrived = Follow.statuses.get(Follow.statuses.size() - 1) == MoveStatus.ARRIVED;
                    if (!arrived && tick[0] < 500) return;
                    c.check("and arrives next to it again", arrived && tv.farmer().position().distanceTo(pig[0].position()) <= 2.5,
                        Follow.statuses.get(Follow.statuses.size() - 1) + " at " + tv.farmer().position().distanceTo(pig[0].position()));
                    c.check("still the same run", runs(model, Acts.FOLLOW), current(model));
                    pig[0].discard();
                    level.setDayTime(dayTimeBefore);
                    stage[0] = 4;
                    forget(tv);
                    finish(helper, c);
                }
                default -> {
                }
            }
        });
    }

    static boolean runs(VillagerModel model, ResourceLocation id) {
        Activity a = model.brain().currentActivity();
        return a != null && id.toString().equals(a.name());
    }

    static String current(VillagerModel model) {
        Activity a = model.brain().currentActivity();
        return a == null ? "none" : a.name();
    }

    /** Evening: an addon choice replaces a leisure step and then keeps running (no restart every tick); daytime choices stay out of the evening. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_behaviour_evening")
    public static void eveningChoices(GameTestHelper helper) {
        Checks c = new Checks("eveningChoices");
        Acts.init();
        TestVillage tv = village(helper, 10800, 6400).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        long dayTimeBefore = level.getDayTime();
        // evening for every villager, and no bedtime yet for any, until the check 250 ticks on: Towntopia ends each
        // villager's work between 11834 and 12500 and sends its early sleeper (plan EARLY) to bed between 13000 and
        // 14000. At 13000 a test farmer that was the village's early sleeper went to bed inside the test window.
        level.setDayTime(12550L);
        VillagerModel model = tv.mgr().getModel(tv.farmer().getNpcId());
        UUID farmerId = tv.farmer().getNpcId();
        int[] eveningAsks = {0};
        int[] dayAsks = {0};
        Subscription s = TownKitBrain.addChoice("townkit-test", new ChoiceProvider() {
            @Override
            public float score(ChoiceContext ctx) {
                if (ctx.phase() == ChoicePhase.EVENING) eveningAsks[0]++;
                if (ctx.phase() == ChoicePhase.DAY) dayAsks[0]++;
                // only this test's farmer: the provider is asked for every villager in every awake test village, and
                // a never-ending addon run started elsewhere would outlive the test (restored by id after a reload)
                return ctx.villager().id().equals(farmerId) ? 1F : 0F;
            }

            @Override
            public ResourceLocation activity() {
                return Acts.LONG;
            }
        });
        // count only this test's farmer: the provider applies to every villager in the village
        UUID npcId = tv.farmer().getNpcId();
        int[] farmerStarts = {0};
        int[] farmerStops = {0};
        Subscription started = TownKitEvents.ADDON_ACTIVITY_STARTED.listen("townkit-test", e -> {
            if (e.villager().id().equals(npcId) && e.id().equals(Acts.LONG)) farmerStarts[0]++;
        });
        Subscription stopped = TownKitEvents.ADDON_ACTIVITY_STOPPED.listen("townkit-test", e -> {
            if (e.villager().id().equals(npcId) && e.id().equals(Acts.LONG)) farmerStops[0]++;
        });
        helper.runAfterDelay(250, () -> {
            started.remove();
            stopped.remove();
            c.checkHooksOn("the evening plan offered a leisure step to addons", eveningAsks[0] > 0, eveningAsks[0]);
            c.checkHooksOn("the addon activity runs", runs(model, Acts.LONG), current(model));
            c.checkHooksOn("and keeps running: started once, not restarted every tick", farmerStarts[0] == 1 && farmerStops[0] == 0,
                "starts " + farmerStarts[0] + ", stops " + farmerStops[0]);
            c.check("no daytime choice in the evening", dayAsks[0] == 0, dayAsks[0]);
            s.remove();
            level.setDayTime(dayTimeBefore);
            forget(tv);
            finish(helper, c);
        });
    }

    /** An activity that opted out of routine interruptions refuses Towntopia's queued activities but still yields to bedtime. */
    @GameTest(template = "empty", timeoutTicks = 800, batch = "townkit_behaviour_locked")
    public static void lockedYieldsToSafety(GameTestHelper helper) {
        Checks c = new Checks("lockedYieldsToSafety");
        Acts.init();
        TestVillage tv = village(helper, 11200, 6400).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        long dayTimeBefore = level.getDayTime();
        level.setDayTime(1000L);
        VillagerModel model = tv.mgr().getModel(tv.farmer().getNpcId());
        Villager v = TownKitVillages.of(tv.farmer()).orElseThrow();
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (tick[0] == 20) CoreBrain.start(v, Acts.LOCKED_LONG);
            if (tick[0] == 60) {
                c.check("the locked activity is running", runs(model, Acts.LOCKED_LONG), current(model));
                model.brain().forceActivity(new LeisureActivity()); // a routine activity Towntopia queues
            }
            if (tick[0] == 80) {
                c.checkHooksOn("a queued routine activity doesn't break in", runs(model, Acts.LOCKED_LONG), current(model));
                level.setDayTime(18000L); // bedtime
            }
            if (tick[0] == 480) {
                c.check("bedtime still ends it", !runs(model, Acts.LOCKED_LONG), current(model));
                level.setDayTime(dayTimeBefore);
                forget(tv);
                finish(helper, c);
            }
        });
    }

    /** An interrupted addon run stops the walk it asked for; a run Towntopia never started gets no stop. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_behaviour_stop")
    public static void stopCleansUp(GameTestHelper helper) {
        Checks c = new Checks("stopCleansUp");
        Acts.init();
        TestVillage tv = village(helper, 11600, 6400).orElse(null);
        if (tv == null) return;
        pinDayTime(helper, 1000L); // daytime: Towntopia's evening plan would replace the queued activity
        VillagerModel model = tv.mgr().getModel(tv.farmer().getNpcId());
        Villager v = TownKitVillages.of(tv.farmer()).orElseThrow();
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (tick[0] == 30) {
                RECORDS.remove(Acts.STAND);
                List<String> stopped = new ArrayList<>();
                Subscription sub = TownKitEvents.ADDON_ACTIVITY_STOPPED.listen("townkit-test", e -> stopped.add(e.id().toString()));
                Activity a = ActivityRegistry.create(Acts.STAND, model);
                a.stop(new Activity.Context(model.brain(), model, tv.mgr(), (ServerLevel) tv.farmer().level(), tv.farmer()));
                sub.remove();
                c.check("a run that never started gets no stop call", record(Acts.STAND).stops.isEmpty(), record(Acts.STAND).stops);
                c.check("and no STOPPED event", stopped.isEmpty(), stopped);
                Walk.reset(routeTileNear(tv, tv.farmer().blockPosition(), 25), null);
                CoreBrain.start(v, Acts.WALK);
            }
            if (tick[0] == 60) {
                c.check("the walk is under way", model.isMoving() || model.isAwaitingPath(), Walk.result);
                CoreBrain.start(v, Acts.STAND); // interrupts the walk
            }
            if (tick[0] == 80) {
                c.checkHooksOn("the interrupted run's walk was stopped", runs(model, Acts.STAND) && !model.isMoving(), current(model) + ", moving " + model.isMoving());
                forget(tv);
                finish(helper, c);
            }
        });
    }

    /** One addon's provider that fails outside its score/wants call must not switch off everyone's interrupts. */
    @GameTest(template = "empty", timeoutTicks = 500, batch = "townkit_behaviour_badprovider")
    public static void badProviderIsolated(GameTestHelper helper) {
        Checks c = new Checks("badProviderIsolated");
        Acts.init();
        TestVillage tv = village(helper, 12000, 6400).orElse(null);
        if (tv == null) return;
        pinDayTime(helper, 1000L); // daytime: Towntopia's evening plan would replace the queued activity
        ServerLevel level = helper.getLevel();
        NpcEntity idler = ScenarioBuilder.spawnVillager(level, tv.mgr(), tv.door().north(3).east(2), Profession.NONE);
        Subscription bad = TownKitBrain.addInterrupt("townkit-test-bad", new InterruptProvider() {
            @Override
            public boolean wants(ChoiceContext ctx) {
                return true;
            }

            @Override
            public ResourceLocation activity() {
                throw new IllegalStateException("deliberate test failure");
            }
        }, 10);
        helper.runAfterDelay(300, () -> {
            boolean hookOff = Hooks.allEntries().stream().anyMatch(e -> "townkit".equals(e.owner)
                && e.hook.key().equals("village.brain.VillagerBrain#checkOverrides(Activity.Context)") && e.disabled);
            c.check("TownKit's interrupt hook is still on", !hookOff, hookOff);
            c.checkHooksOn("the bad provider was switched off instead", bad.isDisabled(), bad.isDisabled());
            bad.remove();
            tv.mgr().removeVillager(idler.getNpcId());
            idler.discard();
            forget(tv);
            finish(helper, c);
        });
    }

    /** Calls Towntopia's private restoreActivity (what it does when a villager comes back with a saved activity name). */
    static Activity restore(TestVillage tv, String name) throws ReflectiveOperationException {
        VillagerModel model = tv.mgr().getModel(tv.farmer().getNpcId());
        Method m = VillagerBrain.class.getDeclaredMethod("restoreActivity", String.class, Activity.Context.class);
        m.setAccessible(true);
        return (Activity) m.invoke(model.brain(), name, new Activity.Context(model.brain(), model, tv.mgr(), (ServerLevel) tv.farmer().level(), tv.farmer()));
    }

    /** An addon activity runs through Towntopia's brain: start, ticks, stop once with the right reason, and events. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_behaviour_lifecycle")
    public static void activityLifecycle(GameTestHelper helper) {
        Checks c = new Checks("activityLifecycle");
        Acts.init();
        TestVillage tv = village(helper, 8400, 6400).orElse(null);
        if (tv == null) return;
        pinDayTime(helper, 1000L); // daytime: Towntopia's evening plan would replace the queued activity
        UUID npcId = tv.farmer().getNpcId();
        VillagerModel model = tv.mgr().getModel(npcId);
        Activity locked = ActivityRegistry.create(Acts.LOCKED, model);
        c.check("an opted-out activity is a plain activity (not work) that TownKit marks as locked",
            !(locked instanceof WorkActivity) && ActivityRegistry.addonId(locked) != null && !ActivityRegistry.allowsRoutineInterruption(locked), locked);
        RECORDS.remove(Acts.STAND);
        List<String> events = new ArrayList<>();
        Subscription started = TownKitEvents.ADDON_ACTIVITY_STARTED.listen("townkit-test", e -> {
            if (e.villager().id().equals(npcId)) events.add("started " + e.id());
        });
        Subscription stopped = TownKitEvents.ADDON_ACTIVITY_STOPPED.listen("townkit-test", e -> {
            if (e.villager().id().equals(npcId)) events.add("stopped " + e.id() + " " + e.reason());
        });
        Villager v = TownKitVillages.of(tv.farmer()).orElseThrow();
        String[] seenRunning = {null};
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (tick[0] == 20) c.check("queued like Towntopia's own activities", CoreBrain.start(v, Acts.STAND), v);
            if (tick[0] > 20 && tick[0] < 60 && seenRunning[0] == null && model.brain().currentActivity() != null
                && Acts.STAND.toString().equals(model.brain().currentActivity().name())) {
                seenRunning[0] = model.brain().currentActivity().name();
            }
            if (tick[0] == 150) {
                Record r = record(Acts.STAND);
                c.check("Towntopia shows the activity under its id", Acts.STAND.toString().equals(seenRunning[0]), seenRunning[0]);
                c.check("start ran once", r.starts == 1, r.starts);
                c.check("stop ran once, DONE", r.stops.equals(List.of(StopReason.DONE)), r.stops);
                c.check("started and stopped events", events.equals(List.of("started " + Acts.STAND, "stopped " + Acts.STAND + " DONE")), events);
                c.check("second run queued", CoreBrain.start(v, Acts.STAND), v);
            }
            if (tick[0] == 170) forget(tv); // the villager dies mid-run
            if (tick[0] == 190) {
                Record r = record(Acts.STAND);
                c.check("the second run stopped once when the villager was forgotten",
                    r.stops.size() == 2 && (r.stops.get(1) == StopReason.VILLAGER_GONE || r.stops.get(1) == StopReason.INTERRUPTED), r.stops);
                started.remove();
                stopped.remove();
                finish(helper, c);
            }
        });
    }

    /** A run whose start threw still gets its stop (FAILED) to clean up, but fires neither STARTED nor STOPPED. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_behaviour_startboom")
    public static void failedStartFiresNoEvents(GameTestHelper helper) {
        Checks c = new Checks("failedStartFiresNoEvents");
        Acts.init();
        TestVillage tv = village(helper, 12400, 6400).orElse(null);
        if (tv == null) return;
        pinDayTime(helper, 1000L); // daytime: Towntopia's evening plan would replace the queued activity
        UUID npcId = tv.farmer().getNpcId();
        RECORDS.remove(Acts.START_BOOM);
        List<String> events = new ArrayList<>();
        Subscription started = TownKitEvents.ADDON_ACTIVITY_STARTED.listen("townkit-test", e -> {
            if (e.villager().id().equals(npcId) && e.id().equals(Acts.START_BOOM)) events.add("started");
        });
        Subscription stopped = TownKitEvents.ADDON_ACTIVITY_STOPPED.listen("townkit-test", e -> {
            if (e.villager().id().equals(npcId) && e.id().equals(Acts.START_BOOM)) events.add("stopped " + e.reason());
        });
        Villager v = TownKitVillages.of(tv.farmer()).orElseThrow();
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (tick[0] == 20) c.check("queued", CoreBrain.start(v, Acts.START_BOOM), v);
            if (tick[0] == 100) {
                Record r = record(Acts.START_BOOM);
                c.check("start ran once and threw", r.starts == 1, r.starts);
                c.check("the addon still got stop(FAILED) to clean up", r.stops.equals(List.of(StopReason.FAILED)), r.stops);
                c.check("no STARTED or STOPPED event for a run whose start threw", events.isEmpty(), events);
                started.remove();
                stopped.remove();
                forget(tv);
                finish(helper, c);
            }
        });
    }

    /** Coming back with a saved addon activity name restores it; a name nobody registers falls back to Towntopia's own choice. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_behaviour_restore")
    public static void restoreById(GameTestHelper helper) {
        Checks c = new Checks("restoreById");
        Acts.init();
        TestVillage tv = village(helper, 8800, 6400).orElse(null);
        if (tv == null) return;
        helper.runAfterDelay(20, () -> {
            try {
                Activity known = restore(tv, Acts.STAND.toString());
                c.checkHooksOn("a registered id is restored", known != null && Acts.STAND.toString().equals(known.name()) && ActivityRegistry.addonId(known) != null, known);
                Activity unknown = restore(tv, "townkit_test:missing");
                c.check("an unknown id falls back to Towntopia", ActivityRegistry.addonId(unknown) == null, unknown == null ? "null" : unknown.name());
            } catch (ReflectiveOperationException e) {
                c.check("restoreActivity reachable", false, e);
            }
            forget(tv);
            finish(helper, c);
        });
    }

    /** An activity that keeps throwing is switched off after 10 failures; the village carries on. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_behaviour_boom")
    public static void throwingActivityIsSwitchedOff(GameTestHelper helper) {
        Checks c = new Checks("throwingActivityIsSwitchedOff");
        Acts.init();
        TestVillage tv = village(helper, 9200, 6400).orElse(null);
        if (tv == null) return;
        helper.runAfterDelay(20, () -> {
            VillagerModel model = tv.mgr().getModel(tv.farmer().getNpcId());
            Activity.Context ctx = new Activity.Context(model.brain(), model, tv.mgr(), (ServerLevel) tv.farmer().level(), tv.farmer());
            List<Activity.Status> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Activity a = ActivityRegistry.create(Acts.BOOM, model);
                if (a == null) break;
                results.add(a.tickOuter(ctx));
                a.stop(ctx);
            }
            c.check("each throwing run ended FAILED, no exception escaped", results.size() == 10 && results.stream().allMatch(s -> s == Activity.Status.FAILED), results);
            c.check("switched off after 10 failures", ActivityRegistry.create(Acts.BOOM, model) == null && ActivityRegistry.isSwitchedOff(Acts.BOOM), "");
            c.check("the village keeps its villager", tv.mgr().getModel(tv.farmer().getNpcId()) != null, "");
            forget(tv);
            finish(helper, c);
        });
    }

    /** The brain hooks TownKit adds for choices, by key (for the "back to zero cost" check). */
    static long choiceHookEntries() {
        Set<String> keys = Set.of("village.brain.VillagerBrain#decideNext(Activity.Context,float,float,float,float)",
            "village.brain.VillagerBrain#eveningOverride(Activity.Context)", "village.brain.EveningPlanner#decide(EveningPlanner.Facts)");
        return Hooks.allEntries().stream().filter(e -> "townkit".equals(e.owner) && keys.contains(e.hook.key())).count();
    }

    /** Daytime choices: a higher score wins, a tie goes to Towntopia, nothing at night; removing the last provider unhooks. */
    @GameTest(template = "empty", timeoutTicks = 1400, batch = "townkit_behaviour_choices")
    public static void choicesInVillage(GameTestHelper helper) {
        Checks c = new Checks("choicesInVillage");
        Acts.init();
        TestVillage tv = village(helper, 9600, 6400).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        long dayTimeBefore = level.getDayTime();
        level.setDayTime(1000L);
        VillagerModel model = tv.mgr().getModel(tv.farmer().getNpcId());
        List<ChoiceContext> seen = new ArrayList<>();
        int[] nightAsks = {0};
        Subscription[] subs = new Subscription[1];
        subs[0] = TownKitBrain.addChoice("townkit-test", new ChoiceProvider() {
            @Override
            public float score(ChoiceContext ctx) {
                if (ctx.villager().id().equals(tv.farmer().getNpcId()) && seen.size() < 50) seen.add(ctx);
                return 1000F;
            }

            @Override
            public ResourceLocation activity() {
                return Acts.STAND;
            }
        });
        c.check("adding a provider hooks the brain", choiceHookEntries() > 0, choiceHookEntries());
        int[] tick = {0};
        int[] stage = {0};
        int[] startsAtStage = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            Activity current = model.brain().currentActivity();
            boolean running = current != null && Acts.STAND.toString().equals(current.name());
            if (stage[0] == 0 && (running || tick[0] == 500)) {
                c.checkHooksOn("a higher daytime score runs the addon activity", running, current == null ? "none" : current.name());
                c.checkHooksOn("the daytime context has Towntopia's scores and no evening step", !seen.isEmpty()
                    && seen.stream().allMatch(x -> x.phase() == ChoicePhase.DAY && x.eveningStep().isEmpty() && x.leisureScore() >= 0F && x.workScore() >= 0F), seen.size());
                subs[0].remove();
                // exactly Towntopia's best score: a tie, which Towntopia keeps
                subs[0] = TownKitBrain.addChoice("townkit-test", new ChoiceProvider() {
                    @Override
                    public float score(ChoiceContext ctx) {
                        return Math.max(Math.max(ctx.eatScore(), ctx.fetchScore()), Math.max(ctx.workScore(), ctx.leisureScore()));
                    }

                    @Override
                    public ResourceLocation activity() {
                        return Acts.STAND;
                    }
                });
                stage[0] = 1;
                tick[0] = 0;
            } else if (stage[0] == 1 && !running) {
                // the run chosen before the tie provider arrived has finished (Towntopia starts a run a tick after
                // switching to it, so count only from here)
                startsAtStage[0] = record(Acts.STAND).starts;
                stage[0] = 2;
                tick[0] = 0;
            } else if (stage[0] == 2 && tick[0] == 300) {
                c.check("a tie goes to Towntopia", record(Acts.STAND).starts == startsAtStage[0], startsAtStage[0] + " -> " + record(Acts.STAND).starts);
                subs[0].remove();
                level.setDayTime(18000L); // night
                subs[0] = TownKitBrain.addChoice("townkit-test", new ChoiceProvider() {
                    @Override
                    public float score(ChoiceContext ctx) {
                        if (ctx.phase() == ChoicePhase.DAY) nightAsks[0]++;
                        return 1000F;
                    }

                    @Override
                    public ResourceLocation activity() {
                        return Acts.STAND;
                    }
                });
                startsAtStage[0] = record(Acts.STAND).starts;
                stage[0] = 3;
                tick[0] = 0;
            } else if (stage[0] == 3 && tick[0] == 300) {
                c.check("nothing is chosen at night", record(Acts.STAND).starts == startsAtStage[0] && nightAsks[0] == 0,
                    "starts " + startsAtStage[0] + " -> " + record(Acts.STAND).starts + ", day asks " + nightAsks[0]);
                subs[0].remove();
                c.check("removing the last provider unhooks the brain", choiceHookEntries() == 0, choiceHookEntries());
                level.setDayTime(dayTimeBefore);
                forget(tv);
                finish(helper, c);
            }
        });
    }

    /** A Towntopia job that keeps running (this test's farmer has no farm to work). */
    static final class HeldJob extends WorkActivity {
        @Override
        protected Activity.Status doTick(Activity.Context ctx) {
            return Activity.Status.RUNNING;
        }

        @Override
        public String name() {
            return "townkit_test_job";
        }
    }

    /**
     * Interrupts: asked only on the villager's slot, replace an idle or leisurely villager, never a Towntopia job, and
     * never get in before Towntopia's own flee.
     */
    @GameTest(template = "empty", timeoutTicks = 800, batch = "townkit_behaviour_interrupts")
    public static void interruptsInVillage(GameTestHelper helper) {
        Checks c = new Checks("interruptsInVillage");
        Acts.init();
        TestVillage tv = village(helper, 10000, 6400).orElse(null);
        if (tv == null) return;
        ServerLevel level = helper.getLevel();
        long dayTimeBefore = level.getDayTime();
        level.setDayTime(1000L);
        NpcEntity idler = ScenarioBuilder.spawnVillager(level, tv.mgr(), tv.door().north(3).east(2), Profession.NONE);
        VillagerModel idlerModel = tv.mgr().getModel(idler.getNpcId());
        VillagerModel farmerModel = tv.mgr().getModel(tv.farmer().getNpcId());
        Map<UUID, Integer> asks = new ConcurrentHashMap<>();
        Subscription s = TownKitBrain.addInterrupt("townkit-test", new InterruptProvider() {
            @Override
            public boolean wants(ChoiceContext ctx) {
                asks.merge(ctx.villager().id(), 1, Integer::sum);
                return ctx.phase() == ChoicePhase.INTERRUPT;
            }

            @Override
            public ResourceLocation activity() {
                return Acts.STAND;
            }
        }, 20);
        boolean[] idlerRan = {false};
        boolean[] brokeWork = {false};
        boolean[] sawWork = {false};
        boolean[] brokeFlee = {false};
        boolean[] fled = {false};
        String[] farmerPrev = {null};
        boolean[] farmerPrevWork = {false};
        String[] idlerPrev = {null};
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            Activity f = farmerModel.brain().currentActivity();
            Activity i = idlerModel.brain().currentActivity();
            String fName = f == null ? "none" : f.name();
            String iName = i == null ? "none" : i.name();
            // a Towntopia job (work) or flee/shelter going straight to the addon activity means an interrupt broke it
            boolean fWork = f instanceof HeldJob;
            if (Acts.STAND.toString().equals(fName) && farmerPrevWork[0]) brokeWork[0] = true;
            if (Acts.STAND.toString().equals(iName) && ("flee".equals(idlerPrev[0]) || "shelter".equals(idlerPrev[0]))) brokeFlee[0] = true;
            if (Acts.STAND.toString().equals(iName)) idlerRan[0] = true;
            if (fWork) sawWork[0] = true;
            if ("flee".equals(iName) || "shelter".equals(iName)) fled[0] = true;
            farmerPrev[0] = fName;
            farmerPrevWork[0] = fWork;
            idlerPrev[0] = iName;
            if (tick[0] == 5) farmerModel.brain().forceActivity(new HeldJob());
            if (tick[0] == 300) {
                c.checkHooksOn("an idle or leisurely villager is interrupted", idlerRan[0], iName);
                int idlerAsks = asks.getOrDefault(idler.getNpcId(), 0);
                c.check("asked only on the villager's slot (every 20 ticks)", idlerAsks <= 300 / 20 + 1, idlerAsks);
                c.check("a Towntopia job is never interrupted", sawWork[0] && !brokeWork[0] && f instanceof HeldJob, "saw job " + sawWork[0] + ", broken " + brokeWork[0] + ", now " + fName);
                Zombie zombie = EntityType.ZOMBIE.create(level);
                zombie.moveTo(idler.getX() + 3, idler.getY(), idler.getZ(), 0F, 0F);
                zombie.setNoAi(true);
                level.addFreshEntity(zombie);
                idler.setLastHurtByMob(zombie);
            }
            if (tick[0] == 500) {
                c.check("Towntopia's flee wins over addon interrupts", fled[0] && !brokeFlee[0], "fled " + fled[0] + ", broken " + brokeFlee[0]);
                s.remove();
                level.setDayTime(dayTimeBefore);
                tv.mgr().removeVillager(idler.getNpcId());
                idler.discard();
                forget(tv);
                finish(helper, c);
            }
        });
    }

    /** Registered on first use (registering also attaches TownKit's drop-on-removal hook). */
    private static final class Keys {
        static final VillagerDataKey<String> NOTE = VillagerDataKey.register(ResourceLocation.parse("townkit_test:note"), Codec.STRING);
    }

    /** Villager data is saved per villager, readable while unloaded, and dropped (after VILLAGER_REMOVED) when Towntopia forgets the villager. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_behaviour_data")
    public static void villagerData(GameTestHelper helper) {
        Checks c = new Checks("villagerData");
        TestVillage tv = village(helper, 8000, 6400).orElse(null);
        if (tv == null) return;
        helper.runAfterDelay(20, () -> {
            Villager v = TownKitVillages.of(tv.farmer()).orElseThrow();
            UUID npcId = tv.farmer().getNpcId();
            c.check("setData on the farmer", v.setData(Keys.NOTE, "hello"), v);
            c.check("data reads it back", Optional.of("hello").equals(v.data(Keys.NOTE)), v.data(Keys.NOTE));
            unload(tv);
            c.check("still readable while unloaded", Optional.of("hello").equals(v.data(Keys.NOTE)), v.data(Keys.NOTE));
            List<UUID> removed = new ArrayList<>();
            List<Optional<String>> dataAtEvent = new ArrayList<>();
            Subscription s = TownKitEvents.VILLAGER_REMOVED.listen("townkit-test", e -> {
                removed.add(e.villager().id());
                dataAtEvent.add(e.villager().data(Keys.NOTE));
            });
            c.check("VILLAGER_REMOVED is available exactly when hooks are on", TownKitEvents.VILLAGER_REMOVED.available() == !HooksOff.off(), HooksOff.off());
            tv.mgr().removeVillager(npcId);
            s.remove();
            c.checkHooksOn("VILLAGER_REMOVED fired once for the farmer", removed.size() == 1 && removed.get(0).equals(npcId), removed);
            c.checkHooksOn("the event still saw the data", dataAtEvent.size() == 1 && Optional.of("hello").equals(dataAtEvent.get(0)), dataAtEvent);
            c.checkHooksOn("data dropped after removal", v.data(Keys.NOTE).isEmpty(), v.data(Keys.NOTE));
            // hooks off nothing tells TownKit the villager is gone, so its data is never dropped (a documented hooks-off limit)
            if (HooksOff.off()) c.check("hooks off: the data is kept after removal", Optional.of("hello").equals(v.data(Keys.NOTE)), v.data(Keys.NOTE));
            finish(helper, c);
        });
    }

    /** Needs writes change Towntopia's own values, clamp hunger, and do nothing while the villager is unloaded. */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_behaviour_needs")
    public static void needsWrites(GameTestHelper helper) {
        Checks c = new Checks("needsWrites");
        TestVillage tv = village(helper, 7600, 6400).orElse(null);
        if (tv == null) return;
        helper.runAfterDelay(40, () -> {
            Villager v = TownKitVillages.of(tv.farmer()).orElseThrow();
            VillagerStats needs = tv.mgr().getModel(tv.farmer().getNpcId()).brain().needs();
            needs.wellbeing = 50F;
            needs.hunger = 50F;
            c.check("addWellbeing on a loaded villager", v.addWellbeing(5F) && Math.abs(needs.wellbeing - 55F) < 0.001F, needs.wellbeing);
            c.check("addHunger on a loaded villager", v.addHunger(-10F) && Math.abs(needs.hunger - 40F) < 0.001F, needs.hunger);
            c.check("hunger is clamped at 100", v.addHunger(1000F) && needs.hunger == 100F, needs.hunger);
            unload(tv);
            float w = needs.wellbeing;
            float h = needs.hunger;
            c.check("unloaded: addWellbeing is refused", !v.addWellbeing(5F) && needs.wellbeing == w, needs.wellbeing);
            c.check("unloaded: addHunger is refused", !v.addHunger(-5F) && needs.hunger == h, needs.hunger);
            forget(tv);
            finish(helper, c);
        });
    }
}
