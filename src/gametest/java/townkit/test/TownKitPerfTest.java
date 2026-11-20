package townkit.test;

import com.alex.test.ModItems;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.farmer.crops.BlockMatcher;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.core.TownKit;
import townkit.core.hook.Census;
import townkit.core.hook.HookStatus;
import townkit.core.hook.Hooks;
import townkit.core.perf.PerfMonitor;

/**
 * A/B cost of TownKit's hooks with nobody listening. Only does anything with -Dtownkit.perf=true; compare a run with
 * -Dtownkit.hooks.enabled=false (Towntopia untouched) against a normal one. Measures:
 * <ul>
 *   <li>a tight loop over a hooked static method and a hooked record getter (ns per call, best of 5 rounds)</li>
 *   <li>server tick time with a village of N villagers (default 40), 600 ticks warm-up then 1200 measured</li>
 * </ul>
 * With -Dtownkit.perf.census=true it also runs a call census over the measured ticks.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class TownKitPerfTest {
    private static final int WARMUP = 600;
    private static final int MEASURE = 1200;

    private TownKitPerfTest() {
    }

    /** The activity the no-op choice provider names (never run: the provider always scores 0). */
    private static final class NoopActivity {
        static final net.minecraft.resources.ResourceLocation ID = net.minecraft.resources.ResourceLocation.parse("townkit_perf:noop");
        private static boolean registered;

        static synchronized void register() {
            if (registered) return;
            registered = true;
            townkit.api.brain.TownKitBrain.registerActivity(ID, v -> new townkit.api.brain.VillagerActivity() {
                @Override
                public net.minecraft.resources.ResourceLocation id() {
                    return ID;
                }

                @Override
                public townkit.api.brain.ActivityStatus tick(townkit.api.brain.ActivityContext ctx) {
                    return townkit.api.brain.ActivityStatus.DONE;
                }
            });
        }
    }

    private static final RouteSearchWindow SEARCH = new RouteSearchWindow();

    /**
     * Towntopia's own A* search stats ({@code AStarPathfinder$SearchStats}, villagers), read by reflection over the
     * measured ticks: {@link #start} flushes them (Towntopia logs the warm-up as a "[RouteSearch]" line) and restarts
     * Towntopia's 60 s window ({@code FollowWalk$Stats.windowStart}) so it doesn't flush again before {@link #report}.
     * Fail soft: a missing class or field only says so in the report.
     */
    static final class RouteSearchWindow {
        private java.lang.reflect.Field villagers;
        private java.lang.reflect.Field windowStart;
        private long startedAt;
        private String problem;

        void start(ServerLevel level) {
            try {
                Class<?> stats = Class.forName("com.alex.test.village.AStarPathfinder$SearchStats");
                villagers = stats.getDeclaredField("VILLAGERS");
                villagers.setAccessible(true);
                java.lang.reflect.Method flush = stats.getDeclaredMethod("flush");
                flush.setAccessible(true);
                flush.invoke(null);
                windowStart = Class.forName("com.alex.test.village.FollowWalk$Stats").getDeclaredField("windowStart");
                windowStart.setAccessible(true);
                startedAt = level.getGameTime();
                windowStart.setLong(null, startedAt);
            } catch (ReflectiveOperationException | RuntimeException e) {
                problem = e.toString();
            }
        }

        String report() {
            if (problem != null) return "stats unavailable: " + problem;
            try {
                Object s = villagers.get(null);
                long routes = adder(s, "routes");
                long found = adder(s, "foundCount");
                long more = adder(s, "morePasses");
                long tiles = adder(s, "tiles");
                long nanos = adder(s, "nanos");
                long mostTiles = ((java.util.concurrent.atomic.AtomicLong) field(s, "mostTiles")).get();
                long mostNanos = ((java.util.concurrent.atomic.AtomicLong) field(s, "mostNanos")).get();
                boolean clean = windowStart.getLong(null) == startedAt;
                return String.format(Locale.ROOT,
                    "villagers: %d routes (%d found, %d second search), tiles looked at mean %.1f most %d, time mean %.3f ms most %.2f ms, total %.1f ms%s",
                    routes, found, more, routes == 0 ? 0D : tiles / (double) routes, mostTiles, routes == 0 ? 0D : nanos / 1e6 / routes,
                    mostNanos / 1e6, nanos / 1e6, clean ? "" : " (Towntopia flushed inside the window: partial)");
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "stats unavailable: " + e;
            }
        }

        private static Object field(Object o, String name) throws ReflectiveOperationException {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        }

        private static long adder(Object o, String name) throws ReflectiveOperationException {
            return ((java.util.concurrent.atomic.LongAdder) field(o, name)).sum();
        }
    }

    /**
     * The {@code -Ptownkit.perf.paths=true} setup: a 3-wide dirt-path road cross through the perf village and an
     * 8-high downward bubble elevator (magma) beside a platform with a ramp, laid out like {@code PathTests.lift(down)},
     * 12 north of the town hall door. {@link #register} adds one road rule (dirt path: speed 1.5, cost 0.75) and enables
     * the bubble elevator with the test marker, then hangs the 2 stop frames. Perf only: TownKit itself never changes
     * road speed and has no default road blocks.
     */
    static final class PathsSetup {
        private final ServerLevel level;
        private final VillageManager mgr;
        private final BlockPos a;
        private final BlockPos t;
        private final BlockPos door;
        private final java.util.List<townkit.api.Subscription> subs = new java.util.ArrayList<>();
        private final java.util.List<net.minecraft.world.entity.decoration.ItemFrame> frames = new java.util.ArrayList<>();

        private PathsSetup(ServerLevel level, VillageManager mgr, BlockPos a, BlockPos t, BlockPos door) {
            this.level = level;
            this.mgr = mgr;
            this.a = a;
            this.t = t;
            this.door = door;
        }

        static PathsSetup build(ServerLevel level, VillageManager mgr, BlockPos door) {
            int g = door.getY();
            net.minecraft.world.level.block.state.BlockState road = Blocks.DIRT_PATH.defaultBlockState();
            // east-west road 3-5 north of the door (under the villagers' spawn rows), north-south road 7-9 east of it
            for (int x = door.getX() - 28; x <= door.getX() + 28; x++) {
                for (int z = door.getZ() - 5; z <= door.getZ() - 3; z++) set(level, mgr, new BlockPos(x, g - 1, z), road, 3);
            }
            for (int x = door.getX() + 7; x <= door.getX() + 9; x++) {
                for (int z = door.getZ() - 7; z <= door.getZ() + 21; z++) set(level, mgr, new BlockPos(x, g - 1, z), road, 3);
            }
            // the elevator (PathTests.lift, down variant): column col, bottom stop a west of it, top stop t on the platform
            BlockPos o = door.north(12);
            int h = 8;
            int cx = o.getX() - 3;
            int cz = o.getZ() - 4;
            BlockPos col = new BlockPos(cx, g, cz);
            net.minecraft.world.level.block.state.BlockState stone = Blocks.STONE.defaultBlockState();
            net.minecraft.world.level.block.state.BlockState glass = Blocks.GLASS.defaultBlockState();
            for (int x = cx + 1; x <= cx + 9; x++) {
                for (int z = cz - 2; z <= cz + 8; z++) {
                    for (int y = g; y < g + h; y++) set(level, mgr, new BlockPos(x, y, z), stone, 3);
                }
            }
            for (int dy = 0; dy < h; dy++) {
                set(level, mgr, new BlockPos(cx, g + dy, cz - 1), glass, 3);
                set(level, mgr, new BlockPos(cx, g + dy, cz + 1), glass, 3);
                if (dy >= 3) set(level, mgr, new BlockPos(cx - 1, g + dy, cz), glass, 3);
            }
            BlockPos a = new BlockPos(cx - 1, g, cz);
            set(level, mgr, a.above(2), stone, 3);
            // the bottom opening needs a sign (or similar) holding the water, or no link forms
            set(level, mgr, a, Blocks.OAK_SIGN.defaultBlockState(), 3);
            set(level, mgr, a.above(), Blocks.OAK_HANGING_SIGN.defaultBlockState(), 3);
            set(level, mgr, col.below(), Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
            for (int dy = 0; dy < h; dy++) {
                set(level, mgr, col.above(dy), Blocks.BUBBLE_COLUMN.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.BubbleColumnBlock.DRAG_DOWN, true), 3);
            }
            for (int i = 0; i <= h - 2; i++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    for (int y = g; y <= g + h - 2 - i; y++) set(level, mgr, new BlockPos(cx + 10 + i, y, z), stone, 3);
                }
            }
            mgr.rebuildNow(level);
            return new PathsSetup(level, mgr, a, new BlockPos(cx + 1, g + h, cz), door);
        }

        /** Sets a block and tells Towntopia's route map (a plain setBlock isn't seen by its dirty tracker). */
        private static void set(ServerLevel level, VillageManager mgr, BlockPos pos,
                net.minecraft.world.level.block.state.BlockState state, int flags) {
            level.setBlock(pos, state, flags);
            mgr.markNavigationDirtyAt(pos);
        }

        /** Route-map tiles standing on dirt path, over the road cross (shows the road rule has tiles to act on). */
        String roadTiles() {
            com.alex.test.village.NavGraph graph = mgr.getGraph();
            if (graph == null) return "no route map";
            int onPath = 0;
            int all = 0;
            for (int x = door.getX() - 28; x <= door.getX() + 28; x++) {
                for (int z = door.getZ() - 7; z <= door.getZ() + 21; z++) {
                    boolean ew = z >= door.getZ() - 5 && z <= door.getZ() - 3;
                    boolean ns = x >= door.getX() + 7 && x <= door.getX() + 9;
                    if (!ew && !ns) continue;
                    all++;
                    com.alex.test.village.NavNode n = graph.nodeAt(x, door.getY(), z);
                    if (n != null && n.surfaceBlock == Blocks.DIRT_PATH) onPath++;
                }
            }
            return onPath + " of " + all + " road tiles in the route map stand on dirt path";
        }

        void register() {
            subs.add(townkit.api.nav.TownKitPaths.rule("townkit-perf", Blocks.DIRT_PATH,
                townkit.api.nav.PathRule.speed(1.5).withCost(0.75)));
            subs.add(townkit.api.nav.TownKitTransport.enable("townkit-perf", townkit.api.nav.TownKitTransport.BUBBLE_ELEVATOR,
                TestBlocks.TEST_STOP_MARKER.get()));
            for (BlockPos feet : new BlockPos[] {a, t}) {
                net.minecraft.world.entity.decoration.ItemFrame f =
                    new net.minecraft.world.entity.decoration.ItemFrame(level, feet, net.minecraft.core.Direction.UP);
                f.setItem(new ItemStack(TestBlocks.TEST_STOP_MARKER.get()));
                level.addFreshEntity(f);
                frames.add(f);
            }
        }

        int links() {
            return townkit.api.nav.TownKitTransport.links(townkit.core.village.VillageHandle.of(mgr)).size();
        }

        void remove() {
            subs.forEach(townkit.api.Subscription::remove);
            frames.forEach(net.minecraft.world.entity.Entity::discard);
        }
    }

    /**
     * The {@code -Ptownkit.perf.modules07=true} setup (hooks-on runs only): one of each 0.7.0 module registered, the way
     * an addon would: a shop entry (a bell for 5), a bubble state (a coin icon, active for the first villager, checked by
     * TownKit every 40 ticks for every villager), a per-villager skin on the second villager (Towntopia's merchant look)
     * and a looping move on the third (the placeholder {@code townkit:check_raise}). {@link #report} says what is in
     * force at the end, so a run shows they were live.
     */
    static final class Modules07Setup {
        private static final String OWNER = "townkit-perf";
        private final java.util.List<townkit.api.Subscription> subs = new java.util.ArrayList<>();
        private final com.alex.test.entity.NpcEntity skinned;
        private final com.alex.test.entity.NpcEntity moving;

        private Modules07Setup(com.alex.test.entity.NpcEntity skinned, com.alex.test.entity.NpcEntity moving) {
            this.skinned = skinned;
            this.moving = moving;
        }

        static Modules07Setup register(java.util.List<com.alex.test.entity.NpcEntity> npcs) {
            Modules07Setup m = new Modules07Setup(npcs.get(1), npcs.get(2));
            m.subs.add(townkit.api.shop.TownKitShop.add(OWNER, townkit.api.shop.ShopEntry.builder(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "perf_bell"),
                () -> net.minecraft.world.item.Items.BELL, 5).build()));
            java.util.UUID first = townkit.core.village.VillagerHandle.of(npcs.get(0)).id();
            m.subs.add(townkit.api.bubble.TownKitBubbles.state(OWNER, townkit.api.bubble.BubbleState.builder(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "perf_state"),
                townkit.api.bubble.TownKitBubbles.icon(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "perf_coin"),
                    townkit.api.bubble.Valence.POSITIVE),
                v -> v.id().equals(first)).build()));
            m.subs.add(townkit.api.skin.TownKitSkins.set(OWNER, townkit.core.village.VillagerHandle.of(m.skinned),
                townkit.api.skin.SkinRef.towntopia("merchant")));
            townkit.api.anim.TownKitAnimations.play(OWNER, townkit.core.village.VillagerHandle.of(m.moving),
                townkit.api.anim.Move.of(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_raise")),
                townkit.api.anim.PlayMode.loop());
            return m;
        }

        String report() {
            return "skin " + townkit.api.skin.TownKitSkins.of(skinned).map(s -> s.id().toString()).orElse("none")
                + ", move " + townkit.core.look.LookSync.look(moving).move();
        }

        void remove() {
            townkit.api.anim.TownKitAnimations.stop(OWNER, townkit.core.village.VillagerHandle.of(moving));
            subs.forEach(townkit.api.Subscription::remove);
        }
    }

    /**
     * The {@code -Ptownkit.perf.work071=true} setup (hooks-on runs only): what an addon registers with the 0.7.1 work
     * recipes: one dish made at a station (a cook station on lodestone), one smith material with one gear tier (steel,
     * between iron and diamond) and a pickaxe recipe, and one ore with a share of the miner's lucky rolls. Server side
     * only. {@link #report} says what is in force at the end, and the size of Towntopia's smith material enum (8 with
     * the hooks off, 24 with them on: TownKit adds 16 spare constants).
     */
    static final class Work071Setup {
        private static final String OWNER = "townkit-perf";
        private final java.util.List<townkit.api.Subscription> subs = new java.util.ArrayList<>();

        static Work071Setup register() {
            Work071Setup w = new Work071Setup();
            net.minecraft.resources.ResourceLocation pot = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "perf_pot");
            net.minecraft.resources.ResourceLocation steel = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_steel");
            net.minecraft.resources.ResourceLocation iron = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("towntopia", "iron");
            net.minecraft.resources.ResourceLocation wood = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("towntopia", "wood");
            w.subs.add(townkit.api.work.TownKitCooking.station(OWNER, pot,
                townkit.api.work.CookStation.builder().block(Blocks.LODESTONE).build()));
            w.subs.add(townkit.api.work.TownKitCooking.addDish(OWNER,
                townkit.api.work.Dish.builder(net.minecraft.world.item.Items.MELON_SLICE)
                    .ingredient(net.minecraft.world.item.Items.APPLE, 2).ingredient(net.minecraft.world.item.Items.SUGAR, 1)
                    .nutrition(8).station(pot).build()));
            w.subs.add(townkit.api.work.TownKitForge.material(OWNER, steel,
                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, steel)));
            w.subs.add(townkit.api.work.TownKitForge.tier(OWNER, steel, townkit.api.work.TierPlace.after(iron).skill(10)));
            w.subs.add(townkit.api.work.TownKitForge.addRecipe(OWNER,
                townkit.api.work.ForgeRecipe.builder(TestBlocks.TEST_STEEL_PICKAXE.get()).cost(steel, 3).cost(wood, 1)
                    .gear(steel, townkit.api.work.GearSlot.PICKAXE).build()));
            double weight = townkit.core.work.mine.MiningOverlay.towntopiaTotal(40)
                * (townkit.api.work.OreEntry.MAX_Y - townkit.api.work.OreEntry.MIN_Y + 1);
            w.subs.add(townkit.api.work.TownKitMining.ore(OWNER, townkit.api.work.OreEntry.builder(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_tin"), TestBlocks.TEST_TIN_ORE.get())
                .luckyRolls(weight, townkit.api.work.OreEntry.MIN_Y, townkit.api.work.OreEntry.MAX_Y).build()));
            return w;
        }

        /** Size of Towntopia's smith material enum and what one pass over it costs (the blacksmith walks it for every material question). */
        static String materials() {
            try {
                Class<?> mat = Class.forName("com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity$Mat");
                int n = mat.getEnumConstants().length;
                long sink = 0;
                double best = Double.MAX_VALUE;
                for (int round = 0; round < 5; round++) {
                    int reps = 2_000_000;
                    long t0 = System.nanoTime();
                    for (int i = 0; i < reps; i++) {
                        for (Object c : mat.getEnumConstants()) sink += ((Enum<?>) c).ordinal();
                    }
                    best = Math.min(best, (System.nanoTime() - t0) / (double) reps);
                }
                return "smith materials " + n + ", one pass over them " + String.format(Locale.ROOT, "%.1f", best) + " ns (sink " + (sink & 1) + ")";
            } catch (ReflectiveOperationException e) {
                return "smith materials unreadable (" + e + ")";
            }
        }

        String report() {
            return townkit.api.work.TownKitCooking.dishes().size() + " dishes, "
                + townkit.api.work.TownKitMining.ores().size() + " ores, steel tier "
                + townkit.api.work.TownKitForge.tierOf(TestBlocks.TEST_STEEL_PICKAXE.get()).map(Object::toString).orElse("none");
        }

        void remove() {
            subs.forEach(townkit.api.Subscription::remove);
        }
    }

    /**
     * The {@code -Ptownkit.perf.modules08=true} setup (hooks-on runs only): what an addon registers with the 0.8.0 work
     * modules: one harvest rule on wheat (ripe at age 7, Towntopia's own Break harvest, so the farm hooks attach on every
     * field scan), one tag dish (small flowers), one dish that keeps its containers (milk bucket) and one seed source
     * (beetroot seeds from beetroot). Server side only. {@link #report} says what is in force at the end.
     */
    static final class Modules08Setup {
        private static final String OWNER = "townkit-perf";
        private static final net.minecraft.resources.ResourceLocation RULE_ID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "perf_wheat");
        private final java.util.List<townkit.api.Subscription> subs = new java.util.ArrayList<>();

        static Modules08Setup register() {
            Modules08Setup w = new Modules08Setup();
            w.subs.add(townkit.api.farm.TownKitFarming.harvest(OWNER, townkit.api.farm.HarvestRule.builder(RULE_ID)
                .block(Blocks.WHEAT).ripe(new townkit.api.farm.Ripe.AtLeast("age", 7)).build()));
            w.subs.add(townkit.api.work.TownKitCooking.addDish(OWNER,
                townkit.api.work.Dish.builder(net.minecraft.world.item.Items.MUSHROOM_STEW)
                    .ingredient(net.minecraft.tags.ItemTags.SMALL_FLOWERS, 2).nutrition(0).build()));
            w.subs.add(townkit.api.work.TownKitCooking.addDish(OWNER,
                townkit.api.work.Dish.builder(net.minecraft.world.item.Items.CAKE)
                    .ingredient(net.minecraft.world.item.Items.MILK_BUCKET, 1).ingredient(net.minecraft.world.item.Items.SUGAR, 1)
                    .keepContainers(true).build()));
            w.subs.add(townkit.api.farm.TownKitFarming.seedFrom(OWNER, net.minecraft.world.item.Items.BEETROOT_SEEDS,
                net.minecraft.world.item.Items.BEETROOT, 2));
            return w;
        }

        String report() {
            boolean rule = townkit.api.farm.TownKitFarming.harvests().stream().anyMatch(r -> r.id().equals(RULE_ID));
            long tagDishes = townkit.api.work.TownKitCooking.dishes().stream().filter(d -> !d.tagIngredients().isEmpty()).count();
            long keepDishes = townkit.api.work.TownKitCooking.dishes().stream().filter(townkit.api.work.Dish::keepContainers).count();
            boolean seed = townkit.api.farm.TownKitFarming.seedSources().containsKey(net.minecraft.world.item.Items.BEETROOT_SEEDS);
            return "harvest rule " + rule + ", tag dishes " + tagDishes + ", keep-container dishes " + keepDishes + ", seed source " + seed;
        }

        void remove() {
            subs.forEach(townkit.api.Subscription::remove);
        }
    }

    @GameTest(template = "empty", timeoutTicks = WARMUP + MEASURE + 400, batch = "townkit_perf")
    public static void hookCost(GameTestHelper helper) {
        if (!Boolean.getBoolean("townkit.perf")) {
            helper.succeed();
            return;
        }
        boolean hooks = HookStatus.hookedClasses() > 0;
        int villagers = Integer.getInteger("townkit.perf.villagers", 40);
        boolean census = Boolean.getBoolean("townkit.perf.census");
        // -Dtownkit.perf.noopChoice=true: one choice provider that never wants anything, asked on every decision
        boolean noopChoice = Boolean.getBoolean("townkit.perf.noopChoice");
        townkit.api.Subscription[] noop = {null};
        if (noopChoice) {
            NoopActivity.register();
            noop[0] = townkit.api.brain.TownKitBrain.addChoice("townkit-perf", new townkit.api.brain.ChoiceProvider() {
                @Override
                public float score(townkit.api.brain.ChoiceContext ctx) {
                    return 0F;
                }

                @Override
                public net.minecraft.resources.ResourceLocation activity() {
                    return NoopActivity.ID;
                }
            });
        }

        // -Dtownkit.perf.standIns=true: one stand-in rule (needs -Dtownkit.testblocks=true) and a listener on each building event
        java.util.List<townkit.api.Subscription> standIns = new java.util.ArrayList<>();
        long[] eventCount = {0};
        // -Dtownkit.perf.villageEvents=true: one listener on VILLAGE_STATE_CHANGED (attaches Towntopia-side village watching)
        long[] villageEventCount = {0};
        boolean villageEvents = Boolean.getBoolean("townkit.perf.villageEvents");
        if (villageEvents) {
            standIns.add(townkit.api.event.TownKitEvents.VILLAGE_STATE_CHANGED.listen("townkit-perf", e -> villageEventCount[0]++));
        }
        if (Boolean.getBoolean("townkit.perf.standIns")) {
            standIns.add(townkit.api.building.TownKitBuildings.countsAs("townkit-perf", TestBlocks.TEST_TABLE.get(), Blocks.CRAFTING_TABLE));
            standIns.add(townkit.api.event.TownKitEvents.BUILDING_ADDED.listen("townkit-perf", e -> eventCount[0]++));
            standIns.add(townkit.api.event.TownKitEvents.BUILDING_REMOVED.listen("townkit-perf", e -> eventCount[0]++));
            standIns.add(townkit.api.event.TownKitEvents.BUILDING_VALIDITY_CHANGED.listen("townkit-perf", e -> eventCount[0]++));
            standIns.add(townkit.api.event.TownKitEvents.BUILDING_BLOCKS_CHANGED.listen("townkit-perf", e -> eventCount[0]++));
        }

        // micro: the cost an unlistened hook adds to a call (JIT-compiled, results kept so the loop can't vanish)
        ItemStack hall = new ItemStack(ModItems.TOWN_HALL.get());
        BlockMatcher.Single single = new BlockMatcher.Single(Blocks.STONE);
        double staticNs = Double.MAX_VALUE;
        double getterNs = Double.MAX_VALUE;
        long sink = 0;
        for (int round = 0; round < 5; round++) {
            int n = 20_000_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                if (ItemFrameTokenRegistry.isToken(hall)) sink++;
            }
            staticNs = Math.min(staticNs, (System.nanoTime() - t0) / (double) n);
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sink += System.identityHashCode(single.block()) & 1;
            }
            getterNs = Math.min(getterNs, (System.nanoTime() - t0) / (double) n);
        }

        ServerLevel level = helper.getLevel();
        TestKit.setHeadless(true);
        int x = 12000;
        int z = 12000;
        for (int cx = (x >> 4) - 6; cx <= (x >> 4) + 6; cx++) {
            for (int cz = (z >> 4) - 6; cz <= (z >> 4) + 6; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        level.getChunk(x >> 4, z >> 4);
        int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        ScenarioBuilder.VillageResult village = ScenarioBuilder.buildVillage(level, new BlockPos(x, feetY, z), feetY, 4);
        VillageManager mgr = village.manager();
        if (mgr == null) {
            helper.fail("perf village was not created");
            return;
        }
        TestKit.setSimulatedPlayer(village.townHallDoor(), level.dimension());
        // every run measures the same part of the day (morning work hours): the time the earlier tests leave behind
        // differs between runs (hooks-off runs fail tests at other speeds), and at night the villagers sleep, plan no
        // routes and the tick costs half as much
        level.setDayTime(1000);
        // -Dtownkit.perf.paths=true: a dirt-path road cross and a bubble elevator are built (in hooks-off runs too, so the
        // worlds match), a road rule is registered and the elevator enabled with its 2 stops (perf only: TownKit itself
        // never changes road speed)
        boolean paths = Boolean.getBoolean("townkit.perf.paths");
        PathsSetup pathSetup = paths ? PathsSetup.build(level, mgr, village.townHallDoor()) : null;
        Profession[] jobs = {Profession.FARMER, Profession.NONE, Profession.LUMBERJACK, Profession.MINER};
        java.util.List<com.alex.test.entity.NpcEntity> npcs = new java.util.ArrayList<>();
        for (int i = 0; i < villagers; i++) {
            com.alex.test.entity.NpcEntity npc = ScenarioBuilder.spawnVillager(level, mgr,
                village.townHallDoor().north(3 + (i / 10)).east((i % 10) - 5), jobs[i % jobs.length]);
            if (npc != null) npcs.add(npc);
        }
        if (pathSetup != null) pathSetup.register();
        // -Dtownkit.perf.modules07=true: one shop entry, bubble state, per-villager skin and looping move (0.7.0 modules)
        boolean modules07 = Boolean.getBoolean("townkit.perf.modules07");
        Modules07Setup modSetup = modules07 && npcs.size() >= 3 ? Modules07Setup.register(npcs) : null;
        // -Dtownkit.perf.work071=true: one station dish, one smith tier with a recipe, one ore with lucky rolls (0.7.1 work recipes)
        boolean work071 = Boolean.getBoolean("townkit.perf.work071");
        Work071Setup workSetup = work071 ? Work071Setup.register() : null;
        // -Dtownkit.perf.modules08=true: one wheat harvest rule, one tag dish, one keep-containers dish, one seed source (0.8.0 modules)
        boolean modules08 = Boolean.getBoolean("townkit.perf.modules08");
        Modules08Setup mod08Setup = modules08 ? Modules08Setup.register() : null;
        // the smith's material enum (8 hooks off, 24 hooks on) is read in every run, so the plain pairs show its cost too
        String materialsInfo = Work071Setup.materials();
        long finalSink = sink;
        double sNs = staticNs;
        double gNs = getterNs;
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (tick[0] == WARMUP) {
                PerfMonitor.reset();
                SEARCH.start(level);
                if (pathSetup != null) {
                    TownKit.LOG.info("[TownKit perf] paths at warm-up end: {} link(s), paths active {}, {}", pathSetup.links(), townkit.api.nav.TownKitPaths.active(), pathSetup.roadTiles());
                    Hooks.allEntries().forEach(e -> {
                        if ("townkit".equals(e.owner)) {
                            e.calls = 0;
                            e.nanos = 0;
                        }
                    });
                }
                if (census) Census.start(level.getServer());
            }
            if (tick[0] == WARMUP + MEASURE - 2) {
                // Towntopia's route search stats over the measured window (read before its own 60 s flush would fire)
                TownKit.LOG.info("[TownKit perf] route search " + SEARCH.report() + " | paths=" + paths + " | day time " + level.getDayTime() % 24000);
            }
            if (tick[0] == WARMUP + MEASURE) {
                PerfMonitor.TickStats s = PerfMonitor.stats();
                TownKit.LOG.info(String.format(Locale.ROOT,
                    "[TownKit perf] hooks=%s census=%s paths=" + paths + " modules07=" + (modSetup == null ? "false" : "true (" + modSetup.report() + ")") + " work071=" + (workSetup == null ? "false" : "true (" + workSetup.report() + ")") + " modules08=" + (mod08Setup == null ? "false" : "true (" + mod08Setup.report() + ")") + " " + materialsInfo + " noopChoice=" + noopChoice + " standIns=" + Boolean.getBoolean("townkit.perf.standIns") + " villageEvents=" + villageEvents + " villageEventCount=" + villageEventCount[0] + " buildingEvents=" + eventCount[0] + " villagers=%d (%d in village) | server tick mean %.3f ms, p50 %.3f, p95 %.3f, p99 %.3f, max %.1f over %d ticks"
                        + " | hooked static call %.2f ns, hooked getter %.2f ns (sink %d)",
                    hooks ? "on" : "off", census, villagers, mgr.getVillagers().size(), s.meanMs(), s.p50Ms(), s.p95Ms(), s.p99Ms(), s.maxMs(),
                    s.ticks(), sNs, gNs, finalSink & 1));
                if (census) {
                    try {
                        Census.Result r = Census.stop(level.getServer());
                        TownKit.LOG.info("[TownKit perf] census: {} calls/tick, {} hooks never called, file {}",
                            String.format(Locale.ROOT, "%,.0f", r.totalCalls() / (double) r.ticks()), r.neverCalled(), r.file());
                        r.top().forEach(l -> TownKit.LOG.info("[TownKit perf]   {}", l));
                    } catch (Exception e) {
                        TownKit.LOG.error("[TownKit perf] census report failed", e);
                    }
                }
                if (noop[0] != null) {
                    // where TownKit's own brain hooks spend their time (listener bodies only, over warm-up + measured ticks)
                    for (Hooks.Entry e : Hooks.allEntries()) {
                        if (!"townkit".equals(e.owner)) continue;
                        TownKit.LOG.info(String.format(Locale.ROOT, "[TownKit perf] townkit hook %s (%s): %d calls, %.3f ms total, %.0f ns per call",
                            e.hook.key(), e.after ? "after" : "before", e.calls, e.nanos / 1e6, e.calls == 0 ? 0D : (double) e.nanos / e.calls));
                    }
                    noop[0].remove();
                }
                if (pathSetup != null) {
                    TownKit.LOG.info("[TownKit perf] paths at the end: {} link(s), paths active {}", pathSetup.links(), townkit.api.nav.TownKitPaths.active());
                    // TownKit's own nav hooks over the measured ticks (the link availability refresh runs inside the
                    // tickNavRebuild after-hook, every 40 ticks per village)
                    for (Hooks.Entry e : Hooks.allEntries()) {
                        if (!"townkit".equals(e.owner) || e.calls == 0) continue;
                        TownKit.LOG.info(String.format(Locale.ROOT, "[TownKit perf] townkit hook %s (%s): %d calls, %.3f ms total, %.0f ns per call, %.4f ms per tick",
                            e.hook.key(), e.after ? "after" : "before", e.calls, e.nanos / 1e6, (double) e.nanos / e.calls, e.nanos / 1e6 / MEASURE));
                    }
                    pathSetup.remove();
                }
                standIns.forEach(townkit.api.Subscription::remove);
                if (modSetup != null) modSetup.remove();
                if (workSetup != null) workSetup.remove();
                if (mod08Setup != null) mod08Setup.remove();
                TestKit.clearSimulatedPlayer();
                helper.succeed();
            }
        });
    }
}
