package townkit.test;

import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.core.Profession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.TownKitVillages;
import townkit.api.event.TownKitEvents;
import townkit.api.event.TypedEvent;
import townkit.api.nav.PathRule;
import townkit.api.nav.TownKitPaths;
import townkit.api.nav.Ride;
import townkit.api.nav.RideContext;
import townkit.api.nav.RideStatus;
import townkit.api.nav.TownKitTransport;
import townkit.api.nav.TransportKind;
import townkit.api.nav.TransportLink;
import townkit.api.nav.TransportStop;
import townkit.api.village.Village;
import townkit.api.village.Villager;
import townkit.core.TownKit;
import townkit.core.village.VillageHandle;
import townkit.core.village.VillagerHandle;

/**
 * Contract tests for the stable API (run with ./gradlew runGameTestServer). After a Towntopia update, these passing
 * means addons that use only the stable tier keep working.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class StableApiTests {
    private static final ResourceLocation FARMER = ResourceLocation.parse("towntopia:farmer");
    private static final ResourceLocation LUMBERJACK = ResourceLocation.parse("towntopia:lumberjack");

    private StableApiTests() {
    }

    private record TestVillage(VillageManager mgr, BlockPos door, NpcEntity farmer) {
    }

    /** A test village (400 blocks from the others), one farmer, chunks force-loaded, the simulated player at its door. */
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
        NpcEntity farmer = ScenarioBuilder.spawnVillager(level, v.manager(), v.townHallDoor().north(3), Profession.FARMER);
        return Optional.of(new TestVillage(v.manager(), v.townHallDoor(), farmer));
    }

    private static void finish(GameTestHelper helper, Checks c) {
        TestKit.clearSimulatedPlayer();
        c.finish(helper);
    }

    /** Read API: villages and villagers come back as TownKit handles that match Towntopia's own objects. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_stable_handles")
    public static void villageHandles(GameTestHelper helper) {
        Checks c = new Checks("villageHandles");
        TestVillage tv = village(helper, 6400, 6000).orElse(null);
        if (tv == null) return;
        UUID villageId = tv.mgr().getVillageId();
        UUID npcId = tv.farmer().getNpcId();
        helper.runAfterDelay(20, () -> {
            Optional<Village> at = TownKitVillages.at(helper.getLevel(), tv.door());
            c.check("at(door) finds the village", at.isPresent(), tv.door());
            c.check("village id matches", at.map(v -> v.id().equals(villageId)).orElse(false), at.map(Village::id));
            c.check("village exists", at.map(Village::exists).orElse(false), at);
            c.check("village dimension", at.map(v -> v.dimension() == helper.getLevel().dimension()).orElse(false), at.map(Village::dimension));
            c.check("village contains its door", at.map(v -> v.contains(tv.door())).orElse(false), tv.door());
            c.check("villagers() has the farmer", at.map(v -> v.villagers().stream().anyMatch(vi -> vi.id().equals(npcId))).orElse(false), npcId);
            c.check("villager(id) finds the farmer", at.flatMap(v -> v.villager(npcId)).isPresent(), npcId);
            c.check("byId finds it", TownKitVillages.byId(helper.getLevel().getServer(), villageId).isPresent(), villageId);
            c.check("all() has it", TownKitVillages.all(helper.getLevel().getServer()).stream().anyMatch(v -> v.id().equals(villageId)), villageId);
            Optional<Villager> of = TownKitVillages.of(tv.farmer());
            c.check("of(npc) is present", of.isPresent(), tv.farmer());
            c.check("of(npc) id is the npcId", of.map(v -> v.id().equals(npcId)).orElse(false), of.map(Villager::id));
            c.check("of(npc) profession is towntopia:farmer", of.map(Villager::profession).map(FARMER::equals).orElse(false), of.map(Villager::profession));
            c.check("of(npc) village id", of.flatMap(Villager::village).map(v -> v.id().equals(villageId)).orElse(false), of.flatMap(Villager::village));
            c.check("of(npc) is loaded, with a position and its entity",
                of.map(v -> v.exists() && v.loaded() && v.position().isPresent() && v.entity().orElse(null) == tv.farmer()).orElse(false), of);
            c.check("of(a pig) is empty", TownKitVillages.of(EntityType.PIG.create(helper.getLevel())).isEmpty(), "pig");
            // a handle that points at the wrong village (the villager moved) finds the villager's real one
            Villager moved = new VillagerHandle(helper.getLevel().getServer(), UUID.randomUUID(), npcId, null);
            c.check("a villager handle follows its villager to its village", moved.exists()
                && moved.village().map(v -> v.id().equals(villageId)).orElse(false), moved.village());
            // the client copy: no server, no village, answers from the entity
            Villager client = new VillagerHandle(null, null, npcId, tv.farmer());
            c.check("client form: village() empty, answers from the entity", client.village().isEmpty() && client.exists() && client.loaded()
                && FARMER.equals(client.profession()) && "none".equals(client.activity()), client);
            c.check("buildings() lists the town hall", at.map(v -> v.buildings().stream().anyMatch(b -> b.type().getPath().equals("town_hall"))).orElse(false),
                at.map(v -> v.buildings().stream().map(b -> b.type().toString()).toList()));
            finish(helper, c);
        });
    }

    /** Typed events carry TownKit handles; a handler that keeps throwing is switched off like a raw hook listener. */
    @GameTest(template = "empty", timeoutTicks = 800, batch = "townkit_stable_events")
    @SuppressWarnings("deprecation") // BUILDING_CHANGE is deprecated but still checked
    public static void stableEvents(GameTestHelper helper) {
        Checks c = new Checks("stableEvents");
        for (TypedEvent<?> e : List.of(TownKitEvents.ACTIVITY_CHANGE, TownKitEvents.PROFESSION_CHANGE, TownKitEvents.BUILDING_CHANGE,
            TownKitEvents.DAY_ROLLOVER)) {
            c.check("event " + e.name() + " is available exactly when hooks are on", e.available() == !HooksOff.off(), e.name());
        }
        TestVillage tv = village(helper, 6800, 6000).orElse(null);
        if (tv == null) return;
        UUID npcId = tv.farmer().getNpcId();
        AtomicReference<TownKitEvents.ActivityChange> activity = new AtomicReference<>();
        List<TownKitEvents.ProfessionChange> professions = new ArrayList<>();
        AtomicInteger thrown = new AtomicInteger();
        Subscription a = TownKitEvents.ACTIVITY_CHANGE.listen("townkit-test", e -> {
            if (e.villager().id().equals(npcId)) activity.set(e);
        });
        // only this test's farmer: other tests' villages may still have villagers whose professions change
        Subscription p = TownKitEvents.PROFESSION_CHANGE.listen("townkit-test", e -> {
            if (e.villager().id().equals(npcId)) professions.add(e);
        });
        Subscription bad = TownKitEvents.PROFESSION_CHANGE.listen("townkit-test", e -> {
            if (!e.villager().id().equals(npcId)) return;
            thrown.incrementAndGet();
            throw new RuntimeException("deliberate test failure");
        });
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (tick[0] == 20) {
                for (int i = 0; i < 12; i++) tv.farmer().setProfession(i % 2 == 0 ? Profession.LUMBERJACK : Profession.FARMER);
                TownKitEvents.ProfessionChange first = professions.isEmpty() ? null : professions.get(0);
                c.checkHooksOn("PROFESSION_CHANGE reports from/to ids", first != null && first.from().equals(FARMER) && first.to().equals(LUMBERJACK), first);
                c.checkHooksOn("PROFESSION_CHANGE villager is the farmer", first != null && first.villager().id().equals(npcId), first);
                c.checkHooksOn("a handler that keeps throwing is switched off", bad.isDisabled() && thrown.get() == 10, "thrown=" + thrown.get() + " disabled=" + bad.isDisabled());
                c.checkHooksOn("other handlers keep running", professions.size() == 12, professions.size());
                // a villager loading from disk: Towntopia's readAdditionalSaveData sets the profession (none -> farmer) on
                // the entity-deserializer thread. That isn't a change, and the event never fires off the server thread
                List<String> offThread = new ArrayList<>();
                Subscription loads = TownKitEvents.PROFESSION_CHANGE.listen("townkit-test", e -> {
                    if (e.villager().id().equals(npcId) && !helper.getLevel().getServer().isSameThread()) {
                        offThread.add(Thread.currentThread().getName() + ": " + e.from() + " -> " + e.to());
                    }
                });
                net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
                tv.farmer().save(saved);
                AtomicReference<Object> loaded = new AtomicReference<>();
                Thread loader = new Thread(() -> {
                    try {
                        loaded.set(EntityType.create(saved, helper.getLevel()).orElse(null));
                    } catch (RuntimeException e) {
                        loaded.set(e);
                    }
                }, "townkit-test-deserializer");
                loader.start();
                try {
                    loader.join(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                loads.remove();
                c.check("the copy loaded off the server thread", loaded.get() instanceof NpcEntity, loaded.get());
                c.check("PROFESSION_CHANGE doesn't fire off the server thread while a villager loads", offThread.isEmpty(), offThread);
            }
            if (tick[0] == 600) {
                TownKitEvents.ActivityChange e = activity.get();
                c.checkHooksOn("ACTIVITY_CHANGE fires with the farmer's id", e != null, e);
                c.checkHooksOn("ACTIVITY_CHANGE names are set", e != null && !e.to().isEmpty() && !e.from().isEmpty(), e);
                c.checkHooksOn("ACTIVITY_CHANGE villager has its village", e != null && e.villager().village().map(v -> v.id().equals(tv.mgr().getVillageId())).orElse(false), e);
                // last, since it cuts the farmer's entity off from its village; Towntopia's village still holds the villager
                tv.farmer().setVillageLink(null);
                Optional<Villager> unlinked = TownKitVillages.of(tv.farmer());
                c.check("a villager whose entity lost its link still finds the village that holds it",
                    unlinked.flatMap(Villager::village).map(v -> v.id().equals(tv.mgr().getVillageId())).orElse(false), unlinked);
                a.remove();
                p.remove();
                bad.remove();
                finish(helper, c);
            }
        });
    }

    /** A handle that outlives its villager answers empty or false, never throws. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_stable_stale")
    public static void staleHandles(GameTestHelper helper) {
        Checks c = new Checks("staleHandles");
        TestVillage tv = village(helper, 7200, 6000).orElse(null);
        if (tv == null) return;
        helper.runAfterDelay(20, () -> {
            Villager v = TownKitVillages.of(tv.farmer()).orElse(null);
            c.check("handle made", v != null, tv.farmer());
            if (v == null) {
                finish(helper, c);
                return;
            }
            c.check("exists before", v.exists(), v);
            // unloaded but alive: the way Towntopia lets go of a villager whose chunk unloads
            tv.mgr().unbindVillager(tv.farmer());
            tv.farmer().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            try {
                c.check("unloaded: still exists, in its village", v.exists() && v.village().isPresent(), v);
                c.check("unloaded: not loaded, no entity or position", !v.loaded() && v.entity().isEmpty() && v.position().isEmpty(), v);
                // Towntopia keeps the profession on the entity only (VillagerBrain.profession() says NONE while unloaded)
                c.check("unloaded: profession reads towntopia:none, as documented", "towntopia:none".equals(v.profession().toString()), v.profession());
            } catch (RuntimeException e) {
                c.check("no exception from an unloaded villager's handle", false, e);
            }
            // a village Towntopia doesn't have (it never deletes villages, only marks them inactive)
            Village gone = new VillageHandle(helper.getLevel().getServer(), UUID.randomUUID(), helper.getLevel().dimension());
            try {
                c.check("unknown village: exists() false, empty answers", !gone.exists() && gone.villagers().isEmpty() && gone.buildings().isEmpty()
                    && gone.villager(tv.farmer().getNpcId()).isEmpty() && !gone.contains(tv.door()), gone);
            } catch (RuntimeException e) {
                c.check("no exception from an unknown village's handle", false, e);
            }
            // the way Towntopia forgets a dead villager
            tv.mgr().removeVillager(tv.farmer().getNpcId());
            tv.farmer().discard();
            try {
                c.check("exists() is false", !v.exists(), v);
                c.check("activity() is none", "none".equals(v.activity()), v.activity());
                c.check("position() is empty", v.position().isEmpty(), v.position());
                c.check("village() is empty", v.village().isEmpty(), v.village());
                c.check("entity() is empty", v.entity().isEmpty(), v.entity());
                c.check("loaded() is false", !v.loaded(), v.loaded());
            } catch (RuntimeException e) {
                c.check("no exception from a stale handle", false, e);
            }
            finish(helper, c);
        });
    }

    /** TownKitPaths: rule, active and effective, on a block no test village uses; every rule is removed at the end. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_stable_paths")
    public static void pathsContract(GameTestHelper helper) {
        Checks c = new Checks("pathsContract");
        Block block = Blocks.LIME_CONCRETE;
        List<Subscription> rules = new ArrayList<>();
        try {
            c.check("nothing registered: active() false, effective() empty", !TownKitPaths.active() && TownKitPaths.effective(block).isEmpty(),
                TownKitPaths.effective(block));
            c.check("rule(blank owner) throws IllegalArgumentException", throwsA(IllegalArgumentException.class,
                () -> rules.add(TownKitPaths.rule(" ", block, PathRule.never()))), "");
            c.check("rule(null block) throws NullPointerException", throwsA(NullPointerException.class,
                () -> rules.add(TownKitPaths.rule("townkit-test", null, PathRule.never()))), "");
            c.check("rule(null owner) throws NullPointerException", throwsA(NullPointerException.class,
                () -> rules.add(TownKitPaths.rule(null, block, PathRule.never()))), "");
            c.check("rule(null rule) throws NullPointerException", throwsA(NullPointerException.class,
                () -> rules.add(TownKitPaths.rule("townkit-test", block, null))), "");
            c.check("effective(null) throws NullPointerException", throwsA(NullPointerException.class,
                () -> TownKitPaths.effective(null)), "");
            // PathRule: factories, combinators and accessors
            PathRule fast = PathRule.speed(1.5);
            c.check("speed(1.5): speed 1.5, no cost, not never, villagers only", fast.speed().orElse(0) == 1.5
                && fast.cost().isEmpty() && !fast.isNever() && !fast.appliesToRaiders(), fast);
            PathRule dear = PathRule.cost(2);
            c.check("cost(2): cost 2, no speed, not never, villagers only", dear.cost().orElse(0) == 2 && dear.speed().isEmpty()
                && !dear.isNever() && !dear.appliesToRaiders(), dear);
            PathRule costAndSpeed = dear.withSpeed(1.5);
            c.check("withSpeed adds a speed and keeps the cost; the original is unchanged", costAndSpeed.cost().orElse(0) == 2
                && costAndSpeed.speed().orElse(0) == 1.5 && dear.speed().isEmpty(), costAndSpeed + " / " + dear);
            PathRule cheapFast = fast.withCost(0.5);
            c.check("withCost adds a cost and keeps the speed", cheapFast.cost().orElse(0) == 0.5 && cheapFast.speed().orElse(0) == 1.5,
                cheapFast);
            PathRule raiders = costAndSpeed.includeRaiders();
            c.check("includeRaiders: appliesToRaiders, cost and speed kept; the original is unchanged", raiders.appliesToRaiders()
                && raiders.cost().orElse(0) == 2 && raiders.speed().orElse(0) == 1.5 && !costAndSpeed.appliesToRaiders(), raiders);
            PathRule neverAll = PathRule.never().includeRaiders();
            c.check("never(): isNever, no cost or speed; includeRaiders keeps never", PathRule.never().isNever()
                && PathRule.never().cost().isEmpty() && PathRule.never().speed().isEmpty() && neverAll.isNever()
                && neverAll.appliesToRaiders(), neverAll);
            c.check("speed(0), speed(-1), speed(NaN), speed(Infinity) throw IllegalArgumentException",
                throwsA(IllegalArgumentException.class, () -> PathRule.speed(0))
                    && throwsA(IllegalArgumentException.class, () -> PathRule.speed(-1))
                    && throwsA(IllegalArgumentException.class, () -> PathRule.speed(Double.NaN))
                    && throwsA(IllegalArgumentException.class, () -> PathRule.speed(Double.POSITIVE_INFINITY)), "");
            c.check("withSpeed(0) and withCost(0) throw IllegalArgumentException",
                throwsA(IllegalArgumentException.class, () -> dear.withSpeed(0))
                    && throwsA(IllegalArgumentException.class, () -> fast.withCost(0))
                    && throwsA(IllegalArgumentException.class, () -> PathRule.cost(-2)), "");
            Subscription slow = TownKitPaths.rule("townkit-test", block, PathRule.speed(1.25));
            rules.add(slow);
            Subscription quick = TownKitPaths.rule("townkit-test", block, PathRule.speed(2).includeRaiders());
            rules.add(quick);
            Optional<PathRule> speeds = TownKitPaths.effective(block);
            c.check("effective() keeps the highest speed", speeds.map(r -> r.speed().orElse(0) == 2).orElse(false), speeds);
            slow.remove();
            quick.remove();
            Subscription cheap = TownKitPaths.rule("townkit-test", block, PathRule.cost(0.5));
            rules.add(cheap);
            Subscription never = TownKitPaths.rule("townkit-test", block, PathRule.never());
            rules.add(never);
            c.check("a rule: active() true, its subscription not disabled", TownKitPaths.active() && !cheap.isDisabled(), cheap.isDisabled());
            Optional<PathRule> both = TownKitPaths.effective(block);
            c.check("effective() combines the rules (never wins, cost kept)", both.map(r -> r.isNever() && r.cost().orElse(0) == 0.5).orElse(false), both);
            never.remove();
            never.remove();
            Optional<PathRule> one = TownKitPaths.effective(block);
            c.check("remove() (twice is harmless) drops only that rule", one.map(r -> !r.isNever() && r.cost().orElse(0) == 0.5).orElse(false), one);
            cheap.remove();
            c.check("all removed: active() false, effective() empty", !TownKitPaths.active() && TownKitPaths.effective(block).isEmpty(),
                TownKitPaths.effective(block));
        } catch (RuntimeException e) {
            c.check("no exception", false, e);
        } finally {
            rules.forEach(Subscription::remove);
        }
        c.finish(helper);
    }

    /** A transport kind that makes no links (contract test only). */
    private record NoLinks(ResourceLocation id) implements TransportKind {
        @Override
        public List<TransportLink> links(ServerLevel level, List<TransportStop> stops) {
            return List.of();
        }

        @Override
        public boolean available(ServerLevel level, TransportLink link) {
            return true;
        }

        @Override
        public Ride ride(RideContext ctx) {
            return c -> RideStatus.FAILED;
        }
    }

    /**
     * TownKitTransport: registerKind, enable, links and BUBBLE_ELEVATOR; TransportLink's checks. The kinds make no
     * links, so nothing changes in any village; everything is removed at the end.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_stable_transport")
    public static void transportContract(GameTestHelper helper) {
        Checks c = new Checks("transportContract");
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("townkit_test", "contract");
        TransportKind kind = new NoLinks(id);
        List<Subscription> subs = new ArrayList<>();
        try {
            c.check("BUBBLE_ELEVATOR is townkit:bubble_elevator", TownKitTransport.BUBBLE_ELEVATOR.toString().equals("townkit:bubble_elevator"),
                TownKitTransport.BUBBLE_ELEVATOR);
            c.check("registerKind(blank owner) throws IllegalArgumentException", throwsA(IllegalArgumentException.class,
                () -> subs.add(TownKitTransport.registerKind(" ", kind))), "");
            c.check("registerKind(null owner) throws NullPointerException", throwsA(NullPointerException.class,
                () -> subs.add(TownKitTransport.registerKind(null, kind))), "");
            c.check("registerKind(null kind) throws NullPointerException", throwsA(NullPointerException.class,
                () -> subs.add(TownKitTransport.registerKind("townkit-test", null))), "");
            c.check("registerKind(a kind with a null id) throws NullPointerException", throwsA(NullPointerException.class,
                () -> subs.add(TownKitTransport.registerKind("townkit-test", new NoLinks(null)))), "");
            c.check("enable(null kind id) throws NullPointerException", throwsA(NullPointerException.class,
                () -> subs.add(TownKitTransport.enable("townkit-test", null, Items.STICK))), "");
            c.check("enable(null marker) throws NullPointerException", throwsA(NullPointerException.class,
                () -> subs.add(TownKitTransport.enable("townkit-test", id, null))), "");
            c.check("enable(blank owner) throws IllegalArgumentException", throwsA(IllegalArgumentException.class,
                () -> subs.add(TownKitTransport.enable("", id, Items.STICK))), "");
            // enabling before the kind is registered is fine: it applies once the kind arrives
            Subscription en = TownKitTransport.enable("townkit-test", id, Items.STICK);
            subs.add(en);
            Subscription reg = TownKitTransport.registerKind("townkit-test", kind);
            subs.add(reg);
            c.check("subscriptions are not disabled", !en.isDisabled() && !reg.isDisabled(), "");
            c.check("registerKind(an id already registered) throws IllegalArgumentException", throwsA(IllegalArgumentException.class,
                () -> subs.add(TownKitTransport.registerKind("townkit-test", new NoLinks(id)))), "");
            c.check("an enabled kind with no stops: active() stays false", !TownKitPaths.active(), "");
            ServerLevel level = helper.getLevel();
            Village nowhere = new VillageHandle(level.getServer(), UUID.randomUUID(), level.dimension());
            c.check("links(a village with none) is empty", TownKitTransport.links(nowhere).isEmpty(), TownKitTransport.links(nowhere));
            c.check("links(null) throws NullPointerException", throwsA(NullPointerException.class, () -> TownKitTransport.links(null)), "");
            reg.remove();
            reg.remove();
            c.check("remove() (twice is harmless) frees the id", throwsA(IllegalStateException.class, () -> {
                Subscription again = TownKitTransport.registerKind("townkit-test", kind);
                subs.add(again);
                again.remove();
                throw new IllegalStateException("registered again");
            }), "");
            BlockPos p = BlockPos.ZERO;
            c.check("TransportLink(cost 0) throws IllegalArgumentException", throwsA(IllegalArgumentException.class,
                () -> new TransportLink(id, p, p.east(3), 0, true)), "");
            c.check("TransportLink(from == to) throws IllegalArgumentException", throwsA(IllegalArgumentException.class,
                () -> new TransportLink(id, p, p, 1, true)), "");
            c.check("TransportLink(null kind) throws NullPointerException", throwsA(NullPointerException.class,
                () -> new TransportLink(null, p, p.east(3), 1, true)), "");
        } catch (RuntimeException e) {
            c.check("no exception", false, e);
        } finally {
            subs.forEach(Subscription::remove);
        }
        c.check("all removed: active() false", !TownKitPaths.active(), "");
        c.finish(helper);
    }

    private static boolean throwsA(Class<? extends Throwable> type, Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return type.isInstance(e);
        }
    }
}
