package townkit.test;

import com.alex.test.ModItems;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.building.CauldronEvents;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.villagers.farmer.crops.BlockMatcher;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import townkit.core.hook.Census;
import townkit.core.hook.HookInjector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.hooks.TownKitHooks;
import townkit.api.event.TownKitEvents;
import townkit.api.event.TypedEvent;
import townkit.api.Subscription;
import townkit.api.hooks.HookRegistration;
import townkit.core.event.EventBindings;
import townkit.core.BuildInfo;
import townkit.core.TownKit;
import townkit.core.hook.HookStatus;
import townkit.core.hook.Hooks;

/**
 * TownKit's own tests (run with ./gradlew runGameTestServer). Each check logs PASS/FAIL; any FAIL fails the test.
 * Only active when the "townkit" GameTest namespace is enabled, so players never run them.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class TownKitTests {
    private static final String TOKEN_TYPE = "village.tokens.ItemFrameTokenRegistry#tokenType(ItemStack)";
    private static final String BRAIN_TICK = "village.brain.VillagerBrain#tick(VillageManager,ServerLevel)";
    private static final String VILLAGE_TICK = "village.VillageManager#tick(ServerLevel)";

    private TownKitTests() {
    }

    /** Hook mechanics that need no world: manifest, injection, return override, cancel, type checks, error isolation. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_basics")
    @SuppressWarnings("deprecation") // BUILDING_CHANGE is deprecated but still checked
    public static void hookBasics(GameTestHelper helper) {
        Checks c = new Checks("hookBasics");
        c.check("manifest has hooks", TownKitHooks.count() > 0, TownKitHooks.count());
        c.check("tokenType hook exists", TownKitHooks.find(TOKEN_TYPE).isPresent(), TOKEN_TYPE);

        HookStatus.Verification v = HookStatus.verify();
        c.checkHooksOn("every generated hook is injected", v.missing() == 0 && v.classFailed() == 0,
            v.live() + "/" + v.checked() + " live, missing " + v.missingKeys().stream().limit(3).toList() + ", class failures " + v.failedClasses().stream().limit(2).toList());
        // after verify every hooked class has loaded, so each typed event's hook must be present and live on this Towntopia
        List<String> deadEvents = new ArrayList<>();
        for (TypedEvent<?> e : List.of(TownKitEvents.ACTIVITY_CHANGE, TownKitEvents.PROFESSION_CHANGE, TownKitEvents.BUILDING_CHANGE,
            TownKitEvents.DAY_ROLLOVER, TownKitEvents.VILLAGE_FOUNDED)) {
            if (!e.available() || !TownKitHooks.isLive(EventBindings.hookKey(e))) deadEvents.add(e.name());
        }
        c.checkHooksOn("typed events available on this Towntopia", deadEvents.isEmpty(), "not live: " + deadEvents);
        // code A* calls from its background search must be marked, so addon listeners know to be thread-safe
        List<String> unmarked = new ArrayList<>();
        for (String key : List.of("village.AStarPathfinder$Route#waypoints()", "village.AStarPathfinder$SearchResult#route()",
            "village.AStarPathfinder$SearchStats#done(AStarPathfinder.Route,int,long,boolean,boolean)", "village.RouteRules#mayEnter(NavNode)")) {
            if (!"worker".equals(TownKitHooks.find(key).map(h -> h.thread()).orElse(null))) unmarked.add(key);
        }
        c.check("A* background-search hooks are marked as worker thread", unmarked.isEmpty(), "unmarked: " + unmarked);
        // rules that had a wildcard typo since 0.1.0 now do what their comments say (user decision, Oct 26 2026)
        c.check("save-file scan hooks are marked as worker thread",
            "worker".equals(TownKitHooks.find("village.SaveFileBodyScan$Result#failed(String)").map(h -> h.thread()).orElse(null)),
            TownKitHooks.find("village.SaveFileBodyScan$Result#failed(String)").map(h -> h.thread()));
        c.check("route-map helper records and tick stagger are not hooked",
            TownKitHooks.find("village.NavGraph$StandingCandidate#walkable()").isEmpty()
                && TownKitHooks.find("village.brain.TickStagger#isSlot(long,int,int)").isEmpty(),
            TownKitHooks.find("village.NavGraph$StandingCandidate#walkable()").isPresent() + " / "
                + TownKitHooks.find("village.brain.TickStagger#isSlot(long,int,int)").isPresent());
        // TownKit's version and its Towntopia dependency come from gradle.properties alone: the Java constants match what NeoForge loaded
        var self = net.neoforged.fml.ModList.get().getModContainerById(TownKit.MODID).orElseThrow().getModInfo();
        var dep = self.getDependencies().stream().filter(d -> d.getModId().equals(BuildInfo.TOWNTOPIA_MODID)).findFirst();
        c.check("one source for versions and Towntopia's mod ID", self.getVersion().toString().equals(BuildInfo.VERSION)
                && TownKit.VERSION.equals(BuildInfo.VERSION) && dep.isPresent()
                && dep.get().getVersionRange().toString().equals("[" + BuildInfo.TOWNTOPIA_VERSION + ",)")
                && net.neoforged.fml.ModList.get().isLoaded(BuildInfo.TOWNTOPIA_MODID),
            "mods.toml " + self.getVersion() + " / constant " + BuildInfo.VERSION + ", dependency " + dep.map(d -> d.getModId() + " " + d.getVersionRange()));
        // A*'s queue record runs ~68k times a tick inside the search loop (census, Oct 26 2026): excluded on purpose
        c.check("A* queue record is not hooked", TownKitHooks.find("village.AStarPathfinder$Entry#f()").isEmpty()
            && TownKitHooks.find("village.AStarPathfinder$Entry#<init>(NavNode,int,int)").isEmpty(), TownKitHooks.find("village.AStarPathfinder$Entry#f()"));

        ItemStack hall = new ItemStack(ModItems.TOWN_HALL.get());
        ItemStack stick = new ItemStack(Items.STICK);
        c.check("baseline: town hall token type", ItemFrameTokenRegistry.tokenType(hall) == ItemFrameTokenRegistry.TokenType.TOWN_HALL,
            ItemFrameTokenRegistry.tokenType(hall));

        // after-listener replaces the return value; isToken (which calls tokenType) follows it
        HookRegistration r1 = TownKitHooks.after("townkit-test", TOKEN_TYPE, call -> call.setReturnValue(null));
        c.checkHooksOn("after-listener overrides return value", ItemFrameTokenRegistry.tokenType(hall) == null, ItemFrameTokenRegistry.tokenType(hall));
        c.checkHooksOn("callers see the override", !ItemFrameTokenRegistry.isToken(hall), ItemFrameTokenRegistry.isToken(hall));
        r1.remove();
        c.check("removing the listener restores behaviour", ItemFrameTokenRegistry.tokenType(hall) == ItemFrameTokenRegistry.TokenType.TOWN_HALL,
            ItemFrameTokenRegistry.tokenType(hall));

        // before-listener skips the method and returns its own value; arguments arrive intact
        AtomicReference<Object> seenArg = new AtomicReference<>();
        HookRegistration r2 = TownKitHooks.before("townkit-test", TOKEN_TYPE, call -> {
            seenArg.set(call.args()[0]);
            call.cancelWith(ItemFrameTokenRegistry.TokenType.KITCHEN);
        });
        c.checkHooksOn("before-listener cancelWith returns its value", ItemFrameTokenRegistry.tokenType(stick) == ItemFrameTokenRegistry.TokenType.KITCHEN,
            ItemFrameTokenRegistry.tokenType(stick));
        c.checkHooksOn("listener sees the real argument object", seenArg.get() == stick, seenArg.get());
        r2.remove();

        // a wrong-type return value is rejected inside the listener; the method still returns normally
        HookRegistration r3 = TownKitHooks.after("townkit-test", TOKEN_TYPE, call -> call.setReturnValue("not a token type"));
        Object result = ItemFrameTokenRegistry.tokenType(hall);
        c.checkHooksOn("wrong return type is rejected, call unharmed", result == ItemFrameTokenRegistry.TokenType.TOWN_HALL && r3.failures() == 1,
            result + ", failures " + r3.failures());
        r3.remove();

        // a listener that always throws is isolated, then switched off after 10 failures in a row
        HookRegistration r4 = TownKitHooks.after("townkit-test", TOKEN_TYPE, call -> {
            throw new RuntimeException("deliberate test failure");
        });
        for (int i = 0; i < 12; i++) {
            ItemFrameTokenRegistry.tokenType(hall);
        }
        c.checkHooksOn("throwing listener is switched off after 10 failures", r4.isDisabled(), "disabled=" + r4.isDisabled());
        c.check("Towntopia unaffected by the throwing listener", ItemFrameTokenRegistry.tokenType(hall) == ItemFrameTokenRegistry.TokenType.TOWN_HALL, "ok");
        r4.remove();

        c.check("no test listeners left behind", testListeners() == 0, testListeners());
        c.finish(helper);
    }

    /**
     * The other hook kinds and the method split: constructor after-hooks, small getters, interface default methods,
     * annotations staying on the wrapper (so event handlers register once), and the call census.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_kinds")
    public static void hookKinds(GameTestHelper helper) {
        Checks c = new Checks("hookKinds");

        // constructor: after-only, sees the finished object and its arguments
        String ctor = "village.villagers.farmer.crops.BlockMatcher$Single#<init>(Block)";
        AtomicReference<Object> built = new AtomicReference<>();
        AtomicReference<Object> builtArg = new AtomicReference<>();
        HookRegistration rc = TownKitHooks.after("townkit-test", ctor, call -> {
            built.set(call.self());
            builtArg.set(call.args()[0]);
        });
        BlockMatcher.Single single = new BlockMatcher.Single(Blocks.STONE);
        c.checkHooksOn("constructor after-hook sees the new object", built.get() == single, built.get());
        c.checkHooksOn("constructor after-hook sees its argument", builtArg.get() == Blocks.STONE, builtArg.get());
        rc.remove();
        boolean refused = false;
        try {
            TownKitHooks.before("townkit-test", ctor, call -> { });
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        c.check("before-listener on a constructor is refused", refused, "refused=" + refused);

        // a record accessor (a few bytes of code) can be overridden
        HookRegistration rg = TownKitHooks.after("townkit-test", "village.villagers.farmer.crops.BlockMatcher$Single#block()",
            call -> call.setReturnValue(Blocks.DIRT));
        c.checkHooksOn("getter override", single.block() == Blocks.DIRT, single.block());
        rg.remove();
        c.check("getter back to normal", single.block() == Blocks.STONE, single.block());

        // interface default method, reached through an implementation that doesn't override it
        AwayJob job = new AwayJob() {
            @Override
            public String cannotWork(AwayContext ctx) {
                return null;
            }

            @Override
            public void workDay(AwayContext ctx) {
            }
        };
        HookRegistration ri = TownKitHooks.after("townkit-test", "village.economy.away.AwayJob#seesEveryCopy()", call -> call.setReturnValue(true));
        c.checkHooksOn("interface default method hook", job.seesEveryCopy(), job.seesEveryCopy());
        ri.remove();
        c.check("interface default back to normal", !job.seesEveryCopy(), job.seesEveryCopy());

        // the split keeps annotations on the wrapper only: an @SubscribeEvent handler is still registered exactly once
        int annotated = 0;
        int bodies = 0;
        boolean bodyClean = true;
        for (Method m : CauldronEvents.class.getDeclaredMethods()) {
            if (m.getName().equals("onLevelTick") && m.isAnnotationPresent(SubscribeEvent.class)) annotated++;
            if (m.getName().startsWith(HookInjector.BODY_PREFIX)) {
                bodies++;
                bodyClean &= m.isSynthetic() && Modifier.isPrivate(m.getModifiers()) && m.getAnnotations().length == 0;
            }
        }
        c.check("event handler keeps @SubscribeEvent on its wrapper only", annotated == 1, annotated);
        c.checkHooksOn("renamed bodies are private, synthetic and unannotated", bodies > 0 && bodyClean, bodies + " bodies, clean=" + bodyClean);

        // census counts calls, including constructors, and writes its report
        String tokenType = TOKEN_TYPE;
        ItemStack hall = new ItemStack(ModItems.TOWN_HALL.get());
        Census.start(helper.getLevel().getServer());
        for (int i = 0; i < 100; i++) {
            ItemFrameTokenRegistry.tokenType(hall);
            new BlockMatcher.Single(Blocks.STONE);
        }
        try {
            Census.Result r = Census.stop(helper.getLevel().getServer());
            long tt = countIn(r.file(), tokenType);
            long cc = countIn(r.file(), ctor);
            c.checkHooksOn("census counts method calls", tt >= 100, tt);
            c.checkHooksOn("census counts constructor calls", cc >= 100, cc);
            c.check("census leaves no census bits behind", ItemFrameTokenRegistry.tokenType(hall) == ItemFrameTokenRegistry.TokenType.TOWN_HALL
                && !Census.running(), "running=" + Census.running());
        } catch (Exception e) {
            c.check("census report written", false, e);
        }

        c.check("no test listeners left behind", testListeners() == 0, testListeners());
        c.finish(helper);
    }

    /** Listeners this test class added (TownKit's own internal hooks, owner "townkit", may be attached by other tests). */
    private static long testListeners() {
        return Hooks.allEntries().stream().filter(e -> "townkit-test".equals(e.owner)).count();
    }

    private static long countIn(java.nio.file.Path census, String key) throws java.io.IOException {
        for (String line : java.nio.file.Files.readAllLines(census)) {
            String[] f = line.split("\t");
            if (f.length > 2 && f[2].equals(key)) return Long.parseLong(f[0]);
        }
        return -1;
    }

    /** Hooks firing inside a live village: before/after on ticks, cancelling a brain tick, typed events, perf accounting. */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "townkit_village")
    public static void hooksInVillage(GameTestHelper helper) {
        Checks c = new Checks("hooksInVillage");
        ServerLevel level = helper.getLevel();
        TestKit.setHeadless(true);
        int x = 6000;
        int z = 6000;
        // Villagers only tick in loaded, ticking chunks: force-load the test village's area (ScenarioBuilder doesn't)
        for (int cx = (x >> 4) - 4; cx <= (x >> 4) + 4; cx++) {
            for (int cz = (z >> 4) - 4; cz <= (z >> 4) + 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        level.getChunk(x >> 4, z >> 4); // forcing is asynchronous; load the centre now so the ground height is real
        int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        ScenarioBuilder.VillageResult village = ScenarioBuilder.buildVillage(level, new BlockPos(x, feetY, z), feetY, 2);
        VillageManager mgr = village.manager();
        if (mgr == null) {
            helper.fail("test village was not created");
            return;
        }
        TestKit.setSimulatedPlayer(village.townHallDoor(), level.dimension());
        for (int i = 0; i < 4; i++) {
            ScenarioBuilder.spawnVillager(level, mgr, village.townHallDoor().north(3).east(i - 2), i % 2 == 0 ? Profession.FARMER : Profession.NONE);
        }

        AtomicInteger brainBefore = new AtomicInteger();
        AtomicInteger brainAfter = new AtomicInteger();
        AtomicInteger villageTicks = new AtomicInteger();
        AtomicInteger activityChanges = new AtomicInteger();
        AtomicReference<Object> villageSelf = new AtomicReference<>();
        HookRegistration b = TownKitHooks.before("townkit-test", BRAIN_TICK, call -> brainBefore.incrementAndGet());
        HookRegistration a = TownKitHooks.after("townkit-test", BRAIN_TICK, call -> brainAfter.incrementAndGet());
        HookRegistration vt = TownKitHooks.after("townkit-test", VILLAGE_TICK, call -> {
            villageTicks.incrementAndGet();
            villageSelf.set(call.self());
        });
        Subscription ev = TownKitEvents.ACTIVITY_CHANGE.listen("townkit-test", e -> activityChanges.incrementAndGet());
        int[] tick = {0};
        int[] afterAtCancelStart = {0};
        HookRegistration[] cancel = {null};
        helper.onEachTick(() -> {
            tick[0]++;
            if (tick[0] == 200) {
                c.checkHooksOn("village tick hook fires", villageTicks.get() > 100, villageTicks.get());
                c.checkHooksOn("hook sees the right village object", villageSelf.get() == mgr, villageSelf.get());
                c.checkHooksOn("brain before-hook fires", brainBefore.get() > 100, brainBefore.get());
                c.check("brain after-hook fires about as often", Math.abs(brainBefore.get() - brainAfter.get()) <= 8, brainBefore.get() + " vs " + brainAfter.get());
                c.checkHooksOn("typed ACTIVITY_CHANGE event fires", activityChanges.get() > 0, activityChanges.get());
                // now skip every brain tick: after-hooks must stop, because the method body no longer runs
                afterAtCancelStart[0] = brainAfter.get();
                cancel[0] = TownKitHooks.before("townkit-test", BRAIN_TICK, call -> call.cancel());
            }
            if (tick[0] == 260) {
                c.check("cancelling skips the method (no after-hooks)", brainAfter.get() == afterAtCancelStart[0],
                    afterAtCancelStart[0] + " -> " + brainAfter.get());
                cancel[0].remove();
            }
            if (tick[0] == 320) {
                c.checkHooksOn("removing the cancel resumes brains", brainAfter.get() > afterAtCancelStart[0], brainAfter.get());
                boolean perf = Hooks.allEntries().stream().anyMatch(en -> en.calls > 0 && en.nanos > 0);
                c.checkHooksOn("listener time is accounted", perf, "entries " + Hooks.allEntries().size());
                b.remove();
                a.remove();
                vt.remove();
                ev.remove();
                TestKit.clearSimulatedPlayer();
                c.finish(helper);
            }
        });
    }
}
