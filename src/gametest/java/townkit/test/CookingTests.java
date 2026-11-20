package townkit.test;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.network.ModPayloads;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.testkit.TestKit;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.MealLeftovers;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.cook.CookPlanner;
import com.alex.test.village.villagers.cook.VillageKitchens;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.cook.settings.CookSettings;
import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import com.alex.test.village.villagers.supplies.BucketNeeds;
import com.alex.test.village.villagers.tradesman.TradesmanLedger;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.hooks.HookRegistration;
import townkit.api.hooks.TownKitHooks;
import townkit.api.spi.TownKitBackend;
import townkit.api.work.CookStation;
import townkit.api.work.Dish;
import townkit.api.work.DishChange;
import townkit.api.work.StationContext;
import townkit.api.work.StationHandler;
import townkit.api.work.TownKitCooking;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.net.WorkListsAppliedPayload;
import townkit.core.net.WorkListsPayload;
import townkit.core.net.WorkWindowPayload;
import townkit.core.work.CoreWork;
import townkit.core.work.RewrittenWorkSites;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkSites;
import townkit.core.work.WorkScreens;
import townkit.core.work.cook.CookHooks;
import townkit.core.work.cook.CookOverlay;
import townkit.core.work.cook.CookScreenRows;
import townkit.core.work.cook.Stations;

/**
 * Contract tests for {@link TownKitCooking} with Towntopia's real cook. The dish list is global, so every test has its
 * own batch and undoes what it registered when it ends (pass, fail or time-out). The tests share one village (a town
 * hall, two houses, a storage room, a kitchen with a furnace and a crafting table, one cook), made by the first one
 * that runs. Their own GameTest namespace ({@value #NAMESPACE}) lets them run alone:
 * {@code ./gradlew runGameTestServer -Ptest.namespaces=townkitwork -Ptest.run=<fresh>}.
 *
 * <p>With TownKit's hooks switched off, the tests that need the cook to see addon dishes check instead that Towntopia
 * keeps its own list.
 */
@GameTestHolder(CookingTests.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class CookingTests {
    static final String NAMESPACE = "townkitwork";
    private static final String OWNER = "townkit-test-cook";
    private static final int VILLAGE_X = 16400;
    private static final int VILLAGE_Z = 9600;
    private static final String K_BATCH_DONE = "village.brain.VillagerBrain#onCookBatchCompleted(Activity.Context,Item)";
    private static final String K_COOK_TICK = "village.villagers.cook.behavior.CookActivity#doTick(Activity.Context)";
    private static final String ALLOWED = CookSettingsSchema.ALLOWED_RECIPES;
    /** The test cook station: a lodestone (a plain block no Towntopia building counts) in the kitchen. */
    private static final ResourceLocation POT = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "test_pot");

    private record CookVillage(VillageManager mgr, BlockPos door, UUID cookId) {
    }

    @Nullable
    private static CookVillage shared;

    private CookingTests() {
    }

    // --- the shared village ------------------------------------------------------------------------------------------

    /**
     * The shared village, its chunks forced again and the village awake (the runner un-forces every forced chunk after
     * each batch, so the village goes dormant and its cook's body unloads in between).
     */
    private static Optional<CookVillage> village(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageManagerRegistry reg = VillageManagerRegistry.get(level.getServer());
        CookVillage v = shared;
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
            helper.fail("cooking test village was not created");
            return Optional.empty();
        }
        TestKit.setSimulatedPlayer(r.townHallDoor(), level.dimension());
        // a kitchen east of the storage room: Towntopia's own kitchen furniture (a furnace and a crafting table)
        ScenarioBuilder.Room kitchen = ScenarioBuilder.buildRoom(level, VILLAGE_X + 12, feetY, VILLAGE_Z + 14,
            ScenarioBuilder.RoomFurniture.KITCHEN);
        ItemStack token = new ItemStack(ModItems.KITCHEN.get());
        VillageTokenBinding.bind(token, mgr);
        kitchen.frame().setItem(token, false);
        mgr.rebuildNow(level);
        NpcEntity cook = ScenarioBuilder.spawnVillager(level, mgr, r.townHallDoor().north(3), Profession.COOK);
        CookVillage made = new CookVillage(mgr, r.townHallDoor(), cook.getNpcId());
        shared = made;
        return Optional.of(made);
    }

    /**
     * Runs {@code then} with the cook's body once it is in the level and the village is awake with a valid storage room
     * (after a batch the village wakes from dormant and rescans its rooms). Checked each tick for up to {@code ticks}.
     */
    private static void whenCookLoaded(GameTestHelper helper, Checks c, CookVillage v, int ticks, Consumer<NpcEntity> then) {
        VillagerModel model = v.mgr().getModel(v.cookId());
        NpcEntity body = model == null ? null : model.puppet();
        boolean storage = v.mgr().getBuildings().stream().anyMatch(b -> b instanceof StorageBuilding s && s.isValid());
        boolean awake = !VillageManagerRegistry.get(helper.getLevel().getServer()).isVillageDormantOrReconciling(v.mgr().getVillageId());
        if (body != null && !body.isRemoved() && helper.getLevel().getEntity(body.getId()) == body && storage && awake) {
            then.accept(body);
            return;
        }
        if (ticks <= 0) {
            c.check("the test cook is in the level, the village awake, its storage valid", false,
                (model == null ? "no model" : "body " + body) + ", storage " + storage + ", awake " + awake);
            BehaviourTests.finish(helper, c);
            return;
        }
        LoadWait.notYet();
        helper.runAfterDelay(1, () -> whenCookLoaded(helper, c, v, ticks - 1, then));
    }

    // --- one test's scene --------------------------------------------------------------------------------------------

    /** What one test registered or changed; {@link #end} undoes it (also when the test fails or times out). */
    private static final class Scene {
        final GameTestHelper helper;
        final Checks c;
        final CookVillage v;
        final NpcEntity cook;
        final ServerLevel level;
        final List<Subscription> subs = new ArrayList<>();
        final List<HookRegistration> hooks = new ArrayList<>();
        /** Other things to undo at the end (swapped channels, fake players' windows). */
        final List<Runnable> undo = new ArrayList<>();
        /** Batches this cook completed, by dish. */
        final Map<Item, Integer> batches = new ConcurrentHashMap<>();
        final long dayBefore;
        /** Station blocks the test placed (taken away again at the end). */
        final List<BlockPos> placed = new ArrayList<>();
        boolean files;
        boolean ended;

        Scene(GameTestHelper helper, Checks c, CookVillage v, NpcEntity cook) {
            this.helper = helper;
            this.c = c;
            this.v = v;
            this.cook = cook;
            this.level = helper.getLevel();
            this.dayBefore = this.level.getDayTime();
        }

        int batches(Item dish) {
            return this.batches.getOrDefault(dish, 0);
        }

        /**
         * Keeps the cook full: a hungry villager with no food bakes bread from storage wheat or eats what it carries,
         * which would eat the test's ingredients. Called on every wait step.
         */
        void feed() {
            VillagerModel m = this.v.mgr().getModel(this.v.cookId());
            if (m != null) m.brain().needs().hunger = 100F;
        }

        /** {@code done}, with the cook fed first (for {@code BuildingTests.waitFor}). */
        java.util.function.BooleanSupplier fed(java.util.function.BooleanSupplier done) {
            return () -> {
                feed();
                return done.getAsBoolean();
            };
        }

        /** Undoes the test's changes; safe to call twice. */
        void cleanUp() {
            if (this.ended) return;
            this.ended = true;
            this.subs.forEach(Subscription::remove);
            this.hooks.forEach(HookRegistration::remove);
            this.undo.forEach(Runnable::run);
            if (this.files) {
                CookOverlay.GLOBAL.table().setFileChanges(List.of());
                Stations.GLOBAL.table().setFileChanges(List.of());
                CookHooks.refresh();
            }
            for (BlockPos p : this.placed) this.level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            this.level.setDayTime(this.dayBefore);
            resetCook(this.cook);
            clearStorage(this.level, this.v.mgr());
        }

        void end() {
            cleanUp();
            BehaviourTests.finish(this.helper, this.c);
        }
    }

    private static void scene(GameTestHelper helper, String name, Consumer<Scene> body) {
        Checks c = new Checks(name);
        CookVillage v = village(helper).orElse(null);
        if (v == null) return;
        whenCookLoaded(helper, c, v, 200, cook -> {
            Scene s = new Scene(helper, c, v, cook);
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
            resetCook(cook);
            clearStorage(s.level, v.mgr());
            s.feed();
            // a full lunch in its pack: else the cook fetches food for later and bakes the test's wheat into bread
            ItemStack lunch = new ItemStack(Items.SWEET_BERRIES, 16);
            VillageTokenBinding.bind(lunch, v.mgr());
            cook.getInventory().setSlot(InventoryManager.SIZE - 1, lunch);
            s.level.setDayTime(1000L); // the cook's working day
            s.hooks.add(TownKitHooks.after(OWNER, K_BATCH_DONE, call -> {
                if (call.args()[0] instanceof Activity.Context ctx && ctx.npc().getNpcId().equals(v.cookId())
                        && call.args()[1] instanceof Item dish) {
                    s.batches.merge(dish, 1, Integer::sum);
                }
            }));
            try {
                body.accept(s);
            } catch (GameTestAssertException e) {
                throw e; // a failed check, already reported and cleaned up by end()
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

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** The cook's saved settings back to defaults and an empty pack. */
    private static void resetCook(NpcEntity cook) {
        cook.getBrainData().remove(CookSettingsSchema.SCHEMA.tagName());
        InventoryManager inv = cook.getInventory();
        if (inv != null) {
            for (int i = 0; i < InventoryManager.SIZE; i++) inv.setSlot(i, ItemStack.EMPTY);
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

    /** Only {@code dish} on in the cook's "allowed recipes" (as a player would set it). */
    private static void allowOnly(NpcEntity cook, Item dish) {
        ProfessionSettings ps = ProfessionSettings.of(cook, CookOverlay.GLOBAL.schema());
        ps.setAllowed(ALLOWED, id(dish), true);
        for (CookActivity.Recipe r : CookActivity.recipes()) {
            if (r.result() != dish) ps.setAllowed(ALLOWED, id(r.result()), false);
        }
    }

    /**
     * Puts {@code count} of {@code item}, bound to the village, straight into the storage room's chests (Towntopia's own
     * deposit would put wheat into the room's composter, where it is gone), else through Towntopia's deposit.
     */
    private static void stock(Scene s, Item item, int count) {
        while (count > 0) {
            int n = Math.min(count, item.getDefaultMaxStackSize());
            ItemStack st = new ItemStack(item, n);
            VillageTokenBinding.bind(st, s.v.mgr());
            if (!insert(s, st.copy()) && ScenarioBuilder.stockStorage(s.level, s.v.mgr(), st) <= 0) {
                s.c.check("stocked " + item, false, count + " left");
                return;
            }
            count -= n;
        }
    }

    private static boolean insert(Scene s, ItemStack st) {
        for (Building b : s.v.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding sb) || !sb.isValid()) continue;
            for (StorageInventory inv : sb.inventories(s.level)) {
                if (inv.size() >= 9 && inv.insert(st, true).isEmpty()) {
                    inv.insert(st, false);
                    sb.invalidateContentIndex();
                    return true;
                }
            }
        }
        return false;
    }

    private static int stored(Scene s, Item item) {
        int n = 0;
        for (Building b : s.v.mgr().getBuildings()) {
            if (b instanceof StorageBuilding st && st.isValid()) n += st.countItem(s.level, item);
        }
        return n;
    }

    private static int carried(NpcEntity npc, Item item) {
        InventoryManager inv = npc.getInventory();
        int n = 0;
        if (inv != null) {
            for (int i = 0; i < InventoryManager.SIZE; i++) {
                ItemStack st = inv.getSlot(i);
                if (st.is(item)) n += st.getCount();
            }
        }
        return n;
    }

    private static int total(Scene s, Item item) {
        return stored(s, item) + carried(s.cook, item);
    }

    @Nullable
    private static CookActivity.Recipe recipe(Item result) {
        return CookActivity.recipes().stream().filter(r -> r.result() == result).findFirst().orElse(null);
    }

    /** 2 clay balls + 1 sugar -> 1 melon slice: nothing a villager eats, so every ingredient can be counted. */
    private static Dish clayMelon() {
        return Dish.builder(Items.MELON_SLICE).ingredient(Items.CLAY_BALL, 2).ingredient(Items.SUGAR, 1).nutrition(8).build();
    }

    /** 2 apples + 1 sugar -> 1 melon slice at the table, nutrition 8 (a vanilla food Towntopia's cook never makes). */
    private static Dish melon() {
        return Dish.builder(Items.MELON_SLICE).ingredient(Items.APPLE, 2).ingredient(Items.SUGAR, 1).nutrition(8).build();
    }

    /** Hooks off: the cook keeps Towntopia's own list; the test checks that and ends. */
    private static boolean endIfHooksOff(Scene s, Item addonDish) {
        if (!hooksOff()) return false;
        s.c.check("hooks off: the API lists the dish", TownKitCooking.dishes().stream().anyMatch(d -> d.result() == addonDish),
            TownKitCooking.dishes().size());
        s.c.check("hooks off: Towntopia's cook keeps its own list", recipe(addonDish) == null, CookActivity.recipes().size());
        s.end();
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<CookActivity.Recipe> towntopiaRecipes() {
        try {
            Field f = CookActivity.class.getDeclaredField("RECIPES");
            f.setAccessible(true);
            return (List<CookActivity.Recipe>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- stations ----------------------------------------------------------------------------------------------------

    /** {@link #clayMelon()} made at the test station. */
    private static Dish potMelon() {
        return Dish.builder(Items.MELON_SLICE).ingredient(Items.CLAY_BALL, 2).ingredient(Items.SUGAR, 1).nutrition(8)
            .station(POT).build();
    }

    @Nullable
    private static KitchenBuilding kitchen(Scene s) {
        for (Building b : s.v.mgr().getBuildings()) {
            if (b instanceof KitchenBuilding k && k.isValid()) return k;
        }
        return null;
    }

    /**
     * Puts the test station block in the kitchen (on the crafting table, else beside it) where Towntopia's rescan of
     * the room ({@code validate}) lets TownKit find it; null if no spot works. Needs a station dish registered (the
     * scan hook is attached only then).
     */
    @Nullable
    private static BlockPos placeStation(Scene s) {
        KitchenBuilding k = kitchen(s);
        BlockPos table = k == null ? null : k.getCraftingTablePos();
        if (table == null) return null;
        for (BlockPos p : List.of(table.above(), table.north(), table.south(), table.east(), table.west())) {
            if (!s.level.getBlockState(p).isAir()) continue;
            s.level.setBlock(p, Blocks.LODESTONE.defaultBlockState(), 3);
            // a player's block change marks the room dirty and Towntopia rescans it; setBlock fires no event, so ask
            s.v.mgr().rebuildNow(s.level);
            KitchenBuilding now = kitchen(s);
            if (now != null) now.validate(s.level);
            if (now != null && now.isValid() && Stations.GLOBAL.positions(List.of(now), POT).contains(p)) {
                s.placed.add(p);
                return p;
            }
            s.level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        s.v.mgr().rebuildNow(s.level);
        return null;
    }

    /** Counts the handler calls; checks they pair up. */
    private static final class Recorder implements StationHandler {
        final boolean throwing;
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger stopped = new AtomicInteger();
        final List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        @Nullable
        volatile StationContext first;
        /** The cook's distance from the block when the batch started (-1: not loaded). */
        volatile double startDistance = -1;

        Recorder(boolean throwing) {
            this.throwing = throwing;
        }

        @Override
        public void started(StationContext ctx) {
            if (this.started.get() > this.stopped.get()) this.problems.add("started twice without a stop");
            this.started.incrementAndGet();
            if (this.first == null) this.first = ctx;
            this.startDistance = ctx.cook().entity().map(e -> e.position().distanceTo(Vec3.atCenterOf(ctx.pos()))).orElse(-1.0);
            if (this.throwing) throw new IllegalStateException("test station handler throws (expected)");
        }

        @Override
        public void stopped(StationContext ctx) {
            this.stopped.incrementAndGet();
            if (this.stopped.get() > this.started.get()) this.problems.add("stopped without a start");
            if (this.throwing) throw new IllegalStateException("test station handler throws (expected)");
        }
    }

    /** The test cook's activity and, while it works a melon batch, where it works and how far it stands from the block. */
    private static final class Watch {
        final AtomicReference<CookActivity> activity = new AtomicReference<>();
        @Nullable
        volatile BlockPos workedAt;
        volatile double farthest = -1;
    }

    private static Watch watchCook(Scene s) {
        Watch w = new Watch();
        s.hooks.add(TownKitHooks.before(OWNER, K_COOK_TICK, call -> {
            if (call.args()[0] instanceof Activity.Context ctx && ctx.npc().getNpcId().equals(s.v.cookId())
                    && call.self() instanceof CookActivity a) {
                w.activity.set(a);
                if (onMelon(a)) {
                    BlockPos at = stationOf(a);
                    w.workedAt = at;
                    if (at != null) w.farthest = Math.max(w.farthest, ctx.npc().position().distanceTo(Vec3.atCenterOf(at)));
                }
            }
        }));
        return w;
    }

    @Nullable
    private static BlockPos stationOf(CookActivity a) {
        try {
            Field f = CookActivity.class.getDeclaredField("station");
            f.setAccessible(true);
            return (BlockPos) f.get(a);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Towntopia's own planner, asked whether the melon dish is a candidate (plan, stock order) for one batch of stock. */
    private static boolean[] melonCandidate(Scene s) {
        CookActivity.Recipe r = recipe(Items.MELON_SLICE);
        VillageKitchens vk = VillageKitchens.of(s.v.mgr());
        if (r == null || vk == null) return new boolean[] {false, false};
        CookPlanner.Pantry p = new CookPlanner.Pantry(new java.util.HashMap<>(Map.of(Items.CLAY_BALL, 2, Items.SUGAR, 1)))
            .forVillage(s.v.mgr());
        CookSettings cs = CookSettings.of(s.cook);
        return new boolean[] {CookPlanner.isCandidate(vk, p, cs, r, 100), CookPlanner.isOrderCandidate(vk, p, cs, r, 100)};
    }

    // --- tests -------------------------------------------------------------------------------------------------------

    /** An addon dish: the cook makes it from bound ingredients and puts it in storage, with the dish's food value. */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_added")
    public static void addonDishCooked(GameTestHelper helper) {
        scene(helper, "addonDishCooked", s -> {
            s.subs.add(TownKitCooking.addDish(OWNER, melon()));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            s.c.check("Towntopia's recipes() lists the dish", recipe(Items.MELON_SLICE) != null, CookActivity.recipes().size());
            s.c.check("isDish(melon slice)", CookActivity.isDish(new ItemStack(Items.MELON_SLICE)), "");
            allowOnly(s.cook, Items.MELON_SLICE);
            // enough for 20 batches: the cook keeps up to 16 food items as its own and puts the rest in storage
            stock(s, Items.APPLE, 40);
            stock(s, Items.SUGAR, 20);
            BuildingTests.waitFor(helper, 20, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0 && stored(s, Items.MELON_SLICE) > 0), () -> {
                s.c.check("the cook made the dish", s.batches(Items.MELON_SLICE) > 0, s.batches);
                s.c.check("the dish is in storage", stored(s, Items.MELON_SLICE) > 0,
                    stored(s, Items.MELON_SLICE) + " stored, " + carried(s.cook, Items.MELON_SLICE) + " carried");
                s.c.check("it used the dish's ingredients", total(s, Items.APPLE) <= 40 - 2 * s.batches(Items.MELON_SLICE),
                    total(s, Items.APPLE) + " apples left after " + s.batches(Items.MELON_SLICE) + " batches");
                int food = foodOfStored(s, Items.MELON_SLICE);
                s.c.check("the made dish has the dish's nutrition (8)", food == 8, food);
                s.end();
            });
        });
    }

    /**
     * A dish with nutrition 0 keeps the item's own food values (nutrition 4 and an effect): the cook plans with the item's
     * nutrition, so the village's food days count the stored meal, and the made stack still has its effect.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_keep_own")
    public static void keepOwnFoodKeepsEffect(GameTestHelper helper) {
        scene(helper, "keepOwnFoodKeepsEffect", s -> {
            Item meal = TestBlocks.TEST_MEAL.get();
            s.subs.add(TownKitCooking.addDish(OWNER, Dish.builder(meal).ingredient(Items.CLAY_BALL, 2).ingredient(Items.SUGAR, 1)
                .nutrition(0).build()));
            if (endIfHooksOff(s, meal)) return;
            CookActivity.Recipe planned = recipe(meal);
            s.c.check("the cook plans with the item's nutrition (4)", planned != null && planned.nutrition() == 4,
                planned == null ? "none" : planned.nutrition());
            allowOnly(s.cook, meal);
            stock(s, Items.CLAY_BALL, 40);
            stock(s, Items.SUGAR, 20);
            BuildingTests.waitFor(helper, 20, 8000, s.fed(() -> s.batches(meal) > 0 && stored(s, meal) > 0), () -> {
                s.c.check("the cook made the dish", s.batches(meal) > 0, s.batches);
                s.c.check("the dish is in storage", stored(s, meal) > 0, stored(s, meal) + " stored");
                FoodProperties food = foodStored(s, meal);
                s.c.check("the made dish keeps the item's own food values", food != null && food.nutrition() == 4
                    && !food.effects().isEmpty(), food == null ? "no food component" : food.nutrition() + ", " + food.effects().size() + " effects");
                // only the meal in the village's stores: its days of food come from the item's own nutrition
                clearStorage(s.level, s.v.mgr());
                stock(s, meal, 3);
                double days = new CookPlanner.Pantry(new java.util.HashMap<>(Map.of(meal, 3))).forVillage(s.v.mgr()).foodDays();
                s.c.check("the stored meal counts as food (foodDays above 0)", days > 0, days);
                s.end();
            });
        });
    }

    /** The food values of the first stored stack of {@code item}, or null. */
    @Nullable
    private static FoodProperties foodStored(Scene s, Item item) {
        for (Building b : s.v.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding st) || !st.isValid()) continue;
            for (StorageInventory inv : st.inventories(s.level)) {
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack in = inv.peek(i);
                    if (in.is(item)) return in.get(DataComponents.FOOD);
                }
            }
        }
        return null;
    }

    /** The nutrition of the first stored stack of {@code item}, or -1. */
    private static int foodOfStored(Scene s, Item item) {
        for (Building b : s.v.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding st) || !st.isValid()) continue;
            for (StorageInventory inv : st.inventories(s.level)) {
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack in = inv.peek(i);
                    if (in.is(item)) {
                        FoodProperties f = in.get(DataComponents.FOOD);
                        return f == null ? -1 : f.nutrition();
                    }
                }
            }
        }
        return -1;
    }

    /** Bread changed to 2 wheat: with only 2 wheat in the village (Towntopia needs 3), the cook bakes a loaf. */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "townkit_cook_changed")
    public static void changedBreadUsesTwoWheat(GameTestHelper helper) {
        scene(helper, "changedBreadUsesTwoWheat", s -> {
            s.subs.add(TownKitCooking.changeDish(OWNER, Items.BREAD, DishChange.builder().ingredient(Items.WHEAT, 2).build()));
            Dish bread = TownKitCooking.dishes().stream().filter(d -> d.result() == Items.BREAD).findFirst().orElseThrow();
            s.c.check("dishes(): bread needs 2 wheat", bread.ingredients().equals(Map.of(Items.WHEAT, 2)), bread.ingredients());
            if (hooksOff()) {
                s.c.check("hooks off: Towntopia's bread still needs 3", recipe(Items.BREAD).inputsPerBatch().get(Items.WHEAT) == 3, "");
                s.end();
                return;
            }
            CookActivity.Recipe r = recipe(Items.BREAD);
            s.c.check("Towntopia's recipes(): bread needs 2 wheat", r != null && r.inputsPerBatch().equals(Map.of(Items.WHEAT, 2)),
                r == null ? null : r.inputsPerBatch());
            allowOnly(s.cook, Items.BREAD);
            stock(s, Items.WHEAT, 2);
            BuildingTests.waitFor(helper, 20, 5000, s.fed(() -> s.batches(Items.BREAD) > 0), () -> {
                s.c.check("the cook baked bread from 2 wheat", s.batches(Items.BREAD) > 0, s.batches);
                s.c.check("both wheat used", total(s, Items.WHEAT) == 0, total(s, Items.WHEAT));
                s.end();
            });
        });
    }

    /**
     * Cake removed: not made though allowed and stocked. Control first: with the same stock and settings the cook makes
     * cake (so the setup can make it), then the removal goes in and the same stock again gives no cake.
     */
    @GameTest(template = "empty", timeoutTicks = 8000, batch = "townkit_cook_removed")
    public static void removedCakeNeverMade(GameTestHelper helper) {
        scene(helper, "removedCakeNeverMade", s -> {
            if (hooksOff()) {
                s.subs.add(TownKitCooking.removeDish(OWNER, Items.CAKE));
                s.c.check("hooks off: dishes() has no cake", TownKitCooking.dishes().stream().noneMatch(d -> d.result() == Items.CAKE), "");
                s.c.check("hooks off: Towntopia still lists cake", recipe(Items.CAKE) != null, "");
                s.end();
                return;
            }
            allowOnly(s.cook, Items.CAKE);
            stockCake(s);
            BuildingTests.waitFor(helper, 20, 4000, s.fed(() -> s.batches(Items.CAKE) > 0), () -> {
                int made = s.batches(Items.CAKE);
                s.c.check("control: the cook makes cake from this stock", made > 0, s.batches + " wheat " + total(s, Items.WHEAT)
                    + ", sugar " + total(s, Items.SUGAR) + ", egg " + total(s, Items.EGG) + ", milk " + total(s, Items.MILK_BUCKET));
                s.subs.add(TownKitCooking.removeDish(OWNER, Items.CAKE));
                s.c.check("dishes() has no cake", TownKitCooking.dishes().stream().noneMatch(d -> d.result() == Items.CAKE),
                    TownKitCooking.dishes().size());
                s.c.check("recipes() has no cake", recipe(Items.CAKE) == null, CookActivity.recipes().size());
                s.c.check("cake is no dish", !CookActivity.isDish(new ItemStack(Items.CAKE)), "");
                s.c.check("no stock order source for cake", StockReserves.recipeFor(Items.CAKE) == null, "");
                stockCake(s);
                helper.runAfterDelay(1500, () -> {
                    s.c.check("removed: no cake made in 1500 ticks", s.batches(Items.CAKE) == made, s.batches);
                    s.c.check("removed: its ingredients untouched", total(s, Items.EGG) >= 1 && total(s, Items.MILK_BUCKET) >= 3,
                        total(s, Items.EGG) + " eggs, " + total(s, Items.MILK_BUCKET) + " milk");
                    s.end();
                });
            });
        });
    }

    /** One cake's ingredients (3 wheat, 2 sugar, 1 egg, 3 milk buckets). */
    private static void stockCake(Scene s) {
        stock(s, Items.WHEAT, 3);
        stock(s, Items.SUGAR, 2);
        stock(s, Items.EGG, 1);
        stock(s, Items.MILK_BUCKET, 3);
    }

    /**
     * Settings rows: an added dish gets a switch and a stock row in Towntopia's settings registry and in every
     * {@code CookSettings}; a removed dish loses them. A saved value for a dish stays in the villager's
     * saved settings while the dish is gone and applies again when it comes back.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_cook_settings")
    public static void addonDishInSettingsRows(GameTestHelper helper) {
        scene(helper, "addonDishInSettingsRows", s -> {
            String melonId = id(Items.MELON_SLICE);
            String stockId = CookSettingsSchema.stockId(melonId);
            try {
                TownKitCooking.addDish(" ", melon());
                s.c.check("a blank owner is refused", false, "");
            } catch (IllegalArgumentException expected) {
                s.c.check("a blank owner is refused", true, expected.getMessage());
            }
            try {
                TownKitCooking.changeDish(OWNER, Items.BREAD, DishChange.builder().build());
                s.c.check("a change that sets nothing is refused", false, "");
            } catch (IllegalArgumentException expected) {
                s.c.check("a change that sets nothing is refused", true, expected.getMessage());
            }
            Subscription melon = TownKitCooking.addDish(OWNER, melon());
            s.subs.add(melon);
            s.subs.add(TownKitCooking.removeDish(OWNER, Items.CAKE));
            SettingsSchema reg = SettingsSchemaRegistry.forProfession(Profession.COOK).orElseThrow();
            if (hooksOff()) {
                s.c.check("hooks off: the registry keeps Towntopia's schema", reg == CookSettingsSchema.SCHEMA, "");
                s.c.check("hooks off: no stock target for the dish", CookSettings.of(s.cook).stockTarget(Items.MELON_SLICE) == 0, "");
                s.end();
                return;
            }
            List<String> ids = reg.findRow(ALLOWED).map(SettingsSchema.Row::entryIds).orElse(List.of());
            s.c.check("menus: the dish has a switch", ids.contains(melonId), ids.size());
            s.c.check("menus: cake's switch is gone", !ids.contains(id(Items.CAKE)), "");
            s.c.check("menus: the dish has a stock row", reg.findRow(stockId).isPresent(), stockId);
            s.c.check("menus: cake's stock row is gone", reg.findRow(CookSettingsSchema.stockId(id(Items.CAKE))).isEmpty(), "");
            String label = reg.findRow(ALLOWED).orElseThrow().allowListEntries().stream().filter(e -> e.id().equals(melonId))
                .map(SettingsSchema.AllowListEntry::label).findFirst().orElse("?");
            s.c.check("the switch is named after the item", label.equals(Items.MELON_SLICE.getDescription().getString()), label);
            CookSettings cs = CookSettings.of(s.cook);
            s.c.check("the cook's settings: the dish is allowed", cs.allowsRecipe(Items.MELON_SLICE), "");
            s.c.check("the cook's settings: stock target " + Dish.DEFAULT_STOCK,
                cs.stockTarget(Items.MELON_SLICE) == Dish.DEFAULT_STOCK, cs.stockTarget(Items.MELON_SLICE));

            // a saved value for a dish stays
            ProfessionSettings ps = ProfessionSettings.of(s.cook, CookOverlay.GLOBAL.schema());
            ps.setAllowed(ALLOWED, melonId, false);
            ps.setInt(stockId, 40);
            melon.remove();
            var saved = s.cook.getBrainData().getCompound(CookSettingsSchema.SCHEMA.tagName());
            s.c.check("dish gone: its switch is still saved", saved.getCompound(ALLOWED).contains(melonId)
                && !saved.getCompound(ALLOWED).getBoolean(melonId), saved);
            s.c.check("dish gone: its stock count is still saved", saved.getInt(stockId) == 40, saved);
            s.c.check("dish gone: no row in the registry", SettingsSchemaRegistry.forProfession(Profession.COOK).orElseThrow()
                .findRow(stockId).isEmpty(), "");
            s.subs.add(TownKitCooking.addDish(OWNER, melon()));
            CookSettings back = CookSettings.of(s.cook);
            s.c.check("dish back: the saved switch applies", !back.allowsRecipe(Items.MELON_SLICE), "");
            s.c.check("dish back: the saved stock count applies", back.stockTarget(Items.MELON_SLICE) == 40,
                back.stockTarget(Items.MELON_SLICE));
            s.end();
        });
    }

    /** A Mayor stock order for an added dish is accepted (Towntopia accepts only items its cook can make). */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_cook_orders")
    public static void addonDishInStockOrders(GameTestHelper helper) {
        scene(helper, "addonDishInStockOrders", s -> {
            StockReserves sr = StockReserves.get(s.level.getServer());
            UUID village = s.v.mgr().getVillageId();
            long now = s.level.getGameTime();
            s.c.check("before: nobody makes melon slices", StockReserves.producerOf(Items.MELON_SLICE) == null, "");
            s.c.check("before: an order is refused", !sr.addRow(village, Items.MELON_SLICE, true, false, 2, null, now), "");
            s.subs.add(TownKitCooking.addDish(OWNER, melon()));
            if (hooksOff()) {
                s.c.check("hooks off: still refused", !sr.addRow(village, Items.MELON_SLICE, true, false, 2, null, now), "");
                s.end();
                return;
            }
            s.c.check("the cook makes it", StockReserves.producerOf(Items.MELON_SLICE) == Profession.COOK,
                StockReserves.producerOf(Items.MELON_SLICE));
            s.c.check("its recipe is known to orders", StockReserves.recipeFor(Items.MELON_SLICE) != null, "");
            boolean accepted = sr.addRow(village, Items.MELON_SLICE, true, false, 2, null, now);
            s.c.check("an order for it is accepted", accepted, "");
            for (StockReserves.Row row : List.copyOf(sr.rows(village))) {
                if (row.item == Items.MELON_SLICE) sr.removeRow(village, row.id, now);
            }
            s.end();
        });
    }

    /**
     * A reload removes a dish while the cook is mid-batch on it. The batch ends without making it, the
     * ingredients stay with the cook (or in storage), nothing is logged as an error. The dish comes from the data-file
     * path ({@code setFileChanges}, what the reload listener calls) and goes the same way.
     */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "townkit_cook_reload")
    public static void reloadRemovesDishMidBatch(GameTestHelper helper) {
        scene(helper, "reloadRemovesDishMidBatch", s -> {
            CookOverlay.GLOBAL.table().setFileChanges(List.of(WorkChange.add("data file " + NAMESPACE + ":test_melon",
                Items.MELON_SLICE, clayMelon())));
            s.files = true;
            CookHooks.refresh(); // what the reload's datapack sync does
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            AtomicReference<CookActivity> activity = new AtomicReference<>();
            s.hooks.add(TownKitHooks.before(OWNER, K_COOK_TICK, call -> {
                if (call.args()[0] instanceof Activity.Context ctx && ctx.npc().getNpcId().equals(s.v.cookId())
                        && call.self() instanceof CookActivity a) {
                    activity.set(a);
                }
            }));
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.CLAY_BALL, 2);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 1, 5000, s.fed(() -> onMelon(activity.get())), () -> {
                if (!onMelon(activity.get())) {
                    s.c.check("the cook started a melon batch", false, s.batches);
                    s.end();
                    return;
                }
                BuildingTests.ErrorCounter errors = BuildingTests.ErrorCounter.attach();
                CookOverlay.GLOBAL.table().setFileChanges(List.of());
                CookHooks.refresh();
                helper.runAfterDelay(300, () -> {
                    errors.detach();
                    s.c.check("no melon slice made from the stale recipe", s.batches(Items.MELON_SLICE) == 0
                        && total(s, Items.MELON_SLICE) == 0, s.batches);
                    s.c.check("the clay is still there", total(s, Items.CLAY_BALL) == 2, total(s, Items.CLAY_BALL));
                    s.c.check("the sugar is still there", total(s, Items.SUGAR) == 1, total(s, Items.SUGAR));
                    CookActivity a = activity.get();
                    s.c.check("the batch ended", a == null || !onMelon(a), "");
                    s.c.check("nothing logged as an error", errors.count.get() == 0, errors.first);
                    s.end();
                });
            });
        });
    }

    /**
     * A reload that leaves the dish as it was (the same file read again, new objects with equal
     * values) and an unrelated addon registering and leaving mid-batch don't end the batch: it completes.
     */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "townkit_cook_reload_same")
    public static void reloadKeepsUnchangedDishMidBatch(GameTestHelper helper) {
        scene(helper, "reloadKeepsUnchangedDishMidBatch", s -> {
            String file = "data file " + NAMESPACE + ":test_melon";
            CookOverlay.GLOBAL.table().setFileChanges(List.of(WorkChange.add(file, Items.MELON_SLICE, clayMelon())));
            s.files = true;
            CookHooks.refresh();
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            AtomicReference<CookActivity> activity = new AtomicReference<>();
            s.hooks.add(TownKitHooks.before(OWNER, K_COOK_TICK, call -> {
                if (call.args()[0] instanceof Activity.Context ctx && ctx.npc().getNpcId().equals(s.v.cookId())
                        && call.self() instanceof CookActivity a) {
                    activity.set(a);
                }
            }));
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.CLAY_BALL, 2);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 1, 5000, s.fed(() -> onMelon(activity.get())), () -> {
                if (!onMelon(activity.get())) {
                    s.c.check("the cook started a melon batch", false, s.batches);
                    s.end();
                    return;
                }
                // the reload path with the same file, and an unrelated addon coming and going
                CookOverlay.GLOBAL.table().setFileChanges(List.of(WorkChange.add(file, Items.MELON_SLICE, clayMelon())));
                CookHooks.refresh();
                Subscription other = TownKitCooking.addDish(OWNER, Dish.builder(Items.PUMPKIN_PIE).ingredient(Items.PUMPKIN, 1).build());
                CookOverlay.GLOBAL.recipes();
                other.remove();
                BuildingTests.waitFor(helper, 5, 1000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0), () -> {
                    s.c.check("the batch completed", s.batches(Items.MELON_SLICE) == 1, s.batches);
                    s.c.check("its ingredients were used", total(s, Items.CLAY_BALL) == 0 && total(s, Items.SUGAR) == 0,
                        total(s, Items.CLAY_BALL) + " clay, " + total(s, Items.SUGAR) + " sugar");
                    s.end();
                });
            });
        });
    }

    /**
     * The away catch-up (Towntopia's {@code CookAway}, run through its own preview: {@code AwayRun.previewAll}, which
     * changes nothing) cooks an added dish, and doesn't cook it once it is removed.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_cook_away")
    public static void awayCatchUpUsesMergedList(GameTestHelper helper) {
        scene(helper, "awayCatchUpUsesMergedList", s -> {
            Subscription melon = TownKitCooking.addDish(OWNER, clayMelon());
            s.subs.add(melon);
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.CLAY_BALL, 8);
            stock(s, Items.SUGAR, 4);
            helper.runAfterDelay(20, () -> {
                Map<Item, Integer> with = awayPreview(s);
                s.c.check("away: the added dish is cooked", with.getOrDefault(Items.MELON_SLICE, 0) > 0, with);
                melon.remove();
                Map<Item, Integer> without = awayPreview(s);
                s.c.check("away: the removed dish is not cooked", without.getOrDefault(Items.MELON_SLICE, 0) == 0, without);
                s.end();
            });
        });
    }

    /** What one away day would make in the shared village ({@code AwayRun.previewAll}, package-private, changes nothing). */
    @SuppressWarnings("unchecked")
    private static Map<Item, Integer> awayPreview(Scene s) {
        try {
            Class<?> run = Class.forName("com.alex.test.village.economy.away.AwayRun");
            java.lang.reflect.Method preview = run.getDeclaredMethod("previewAll", ServerLevel.class, VillageManager.class, int.class);
            preview.setAccessible(true);
            Object r = preview.invoke(null, s.level, s.v.mgr(), 1);
            return (Map<Item, Integer>) run.getMethod("made").invoke(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean onMelon(@Nullable CookActivity a) {
        if (a == null || !a.isProcessingBatch()) return false;
        try {
            Field f = CookActivity.class.getDeclaredField("recipe");
            f.setAccessible(true);
            return f.get(a) instanceof CookActivity.Recipe r && r.result() == Items.MELON_SLICE;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- tag ingredients (W15) ----------------------------------------------------------------------------------------

    private static final net.minecraft.tags.TagKey<Item> TEST_VEG = net.minecraft.tags.TagKey.create(
        net.minecraft.core.registries.Registries.ITEM, ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_veg"));

    /** {@code veg} of any {@code #townkit:test_veg} (test data: veg a and b) + 1 sugar -> 1 melon slice. */
    private static Dish vegMelon(int veg) {
        return Dish.builder(Items.MELON_SLICE).ingredient(TEST_VEG, veg).ingredient(Items.SUGAR, 1).nutrition(8).build();
    }

    /** The item tags as bound now (to bind them again after a test changed one). */
    private static Map<net.minecraft.tags.TagKey<Item>, List<net.minecraft.core.Holder<Item>>> itemTags() {
        Map<net.minecraft.tags.TagKey<Item>, List<net.minecraft.core.Holder<Item>>> out = new java.util.HashMap<>();
        BuiltInRegistries.ITEM.getTags().forEach(p -> out.put(p.getFirst(), p.getSecond().stream().toList()));
        return out;
    }

    /** What the tag-reload listener does after the server's tags were bound again. */
    private static void tagsReloaded() {
        CookOverlay.GLOBAL.tagsChanged();
        CookHooks.refresh();
    }

    /** Storage holds only veg b: the cook makes the meal from b (live and in the away catch-up's preview). */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_tag_storage")
    public static void tagDishUsesWhatStorageHas(GameTestHelper helper) {
        scene(helper, "tagDishUsesWhatStorageHas", s -> {
            Item a = TestBlocks.TEST_VEG_A.get();
            Item b = TestBlocks.TEST_VEG_B.get();
            s.subs.add(TownKitCooking.addDish(OWNER, vegMelon(2)));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            CookActivity.Recipe r = recipe(Items.MELON_SLICE);
            s.c.check("one recipe for the tag dish, its inputs the first variant (veg a)", r != null
                && r.inputsPerBatch().equals(Map.of(a, 2, Items.SUGAR, 1)), r == null ? "none" : r.inputsPerBatch());
            s.c.check("both members are cook ingredients", CookOverlay.GLOBAL.ingredients().containsAll(List.of(a, b)), "");
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, b, 8);
            stock(s, Items.SUGAR, 4);
            helper.runAfterDelay(20, () -> {
                Map<Item, Integer> away = awayPreview(s);
                s.c.check("away: the meal is made from b", away.getOrDefault(Items.MELON_SLICE, 0) > 0, away);
                BuildingTests.waitFor(helper, 20, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0), () -> {
                    s.c.check("the cook made the meal", s.batches(Items.MELON_SLICE) > 0, s.batches);
                    s.c.check("from b", total(s, b) <= 8 - 2 * s.batches(Items.MELON_SLICE),
                        total(s, b) + " b left after " + s.batches(Items.MELON_SLICE) + " batches");
                    s.c.check("no a appeared or went", total(s, a) == 0, total(s, a));
                    s.end();
                });
            });
        });
    }

    /** a=2, b=10, both enough for a batch: the cook uses b, the one the village has most of. */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_tag_most")
    public static void tagDishPrefersMostStocked(GameTestHelper helper) {
        scene(helper, "tagDishPrefersMostStocked", s -> {
            Item a = TestBlocks.TEST_VEG_A.get();
            Item b = TestBlocks.TEST_VEG_B.get();
            s.subs.add(TownKitCooking.addDish(OWNER, vegMelon(2)));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, a, 2);
            stock(s, b, 10);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 20, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0), () -> {
                s.c.check("the cook made the meal", s.batches(Items.MELON_SLICE) == 1, s.batches);
                s.c.check("b was used", total(s, b) == 8, total(s, b));
                s.c.check("a was left alone", total(s, a) == 2, total(s, a));
                s.end();
            });
        });
    }

    /** A tag reload adds veg c to the tag: with only c in storage, the meal is made from c. */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_tag_reload")
    public static void tagReloadAddsVariant(GameTestHelper helper) {
        scene(helper, "tagReloadAddsVariant", s -> {
            Item c = TestBlocks.TEST_VEG_C.get();
            s.subs.add(TownKitCooking.addDish(OWNER, vegMelon(2)));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            Map<net.minecraft.tags.TagKey<Item>, List<net.minecraft.core.Holder<Item>>> before = itemTags();
            s.undo.add(() -> {
                BuiltInRegistries.ITEM.bindTags(before);
                tagsReloaded();
            });
            Map<net.minecraft.tags.TagKey<Item>, List<net.minecraft.core.Holder<Item>>> with = new java.util.HashMap<>(before);
            List<net.minecraft.core.Holder<Item>> veg = new ArrayList<>(with.getOrDefault(TEST_VEG, List.of()));
            veg.add(BuiltInRegistries.ITEM.wrapAsHolder(c));
            with.put(TEST_VEG, veg);
            CookActivity.Recipe old = recipe(Items.MELON_SLICE);
            BuiltInRegistries.ITEM.bindTags(with);
            tagsReloaded();
            s.c.check("the tag now holds c", c.builtInRegistryHolder().is(TEST_VEG), "");
            s.c.check("the dish's recipe was renewed (its variants changed)", recipe(Items.MELON_SLICE) != old, "");
            s.c.check("c is a cook ingredient", CookOverlay.GLOBAL.ingredients().contains(c), "");
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, c, 2);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 20, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0), () -> {
                s.c.check("the cook made the meal", s.batches(Items.MELON_SLICE) == 1, s.batches);
                s.c.check("from c", total(s, c) == 0, total(s, c));
                s.end();
            });
        });
    }

    /**
     * A reload changes the tag dish while the cook is mid-batch on it (with veg b). The batch ends
     * without making it, and the b and sugar she carries stay (nothing consumed for a wrong variant).
     */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "townkit_cook_tag_reload_mid")
    public static void reloadMidBatchNoWrongConsume(GameTestHelper helper) {
        scene(helper, "reloadMidBatchNoWrongConsume", s -> {
            Item b = TestBlocks.TEST_VEG_B.get();
            String file = "data file " + NAMESPACE + ":test_veg_melon";
            CookOverlay.GLOBAL.table().setFileChanges(List.of(WorkChange.add(file, Items.MELON_SLICE, vegMelon(2))));
            s.files = true;
            CookHooks.refresh();
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            AtomicReference<CookActivity> activity = new AtomicReference<>();
            s.hooks.add(TownKitHooks.before(OWNER, K_COOK_TICK, call -> {
                if (call.args()[0] instanceof Activity.Context ctx && ctx.npc().getNpcId().equals(s.v.cookId())
                        && call.self() instanceof CookActivity act) {
                    activity.set(act);
                }
            }));
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, b, 2);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 1, 5000, s.fed(() -> onMelon(activity.get())), () -> {
                if (!onMelon(activity.get())) {
                    s.c.check("the cook started a meal batch", false, s.batches);
                    s.end();
                    return;
                }
                BuildingTests.ErrorCounter errors = BuildingTests.ErrorCounter.attach();
                // the reload: the dish now needs 3 of the tag
                CookOverlay.GLOBAL.table().setFileChanges(List.of(WorkChange.add(file, Items.MELON_SLICE, vegMelon(3))));
                CookHooks.refresh();
                helper.runAfterDelay(300, () -> {
                    errors.detach();
                    s.c.check("no meal made from the stale recipe", s.batches(Items.MELON_SLICE) == 0
                        && total(s, Items.MELON_SLICE) == 0, s.batches);
                    s.c.check("the b is still there", total(s, b) == 2, total(s, b));
                    s.c.check("the sugar is still there", total(s, Items.SUGAR) == 1, total(s, Items.SUGAR));
                    CookActivity act = activity.get();
                    s.c.check("the batch ended", act == null || !onMelon(act), "");
                    s.c.check("nothing logged as an error", errors.count.get() == 0, errors.first);
                    s.end();
                });
            });
        });
    }

    /**
     * A dish with an addon station: the cook makes it at the station block (claims it, stands at it, Towntopia's
     * table batch time), and the station's handler hears {@code started} and {@code stopped} once for the one batch.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_station")
    public static void stationDishCookedAtStation(GameTestHelper helper) {
        scene(helper, "stationDishCookedAtStation", s -> {
            Recorder rec = new Recorder(false);
            s.subs.add(TownKitCooking.station(OWNER, POT, CookStation.builder().block(Blocks.LODESTONE).handler(rec).build()));
            s.subs.add(TownKitCooking.addDish(OWNER, potMelon()));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            BlockPos pot = placeStation(s);
            if (pot == null) {
                s.c.check("the station block is found in the kitchen", false, "no spot");
                s.end();
                return;
            }
            Watch w = watchCook(s);
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.CLAY_BALL, 2);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 5, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0 && rec.stopped.get() > 0), () -> {
                s.c.check("the cook made the dish", s.batches(Items.MELON_SLICE) == 1, s.batches);
                s.c.check("it worked at the station block", pot.equals(w.workedAt), w.workedAt + " vs " + pot);
                s.c.check("it stood at the block while working (within 4)", w.farthest >= 0 && w.farthest <= 4.0, w.farthest);
                s.c.check("started once", rec.started.get() == 1, rec.started.get());
                s.c.check("stopped once", rec.stopped.get() == 1, rec.stopped.get());
                s.c.check("handler calls pair up", rec.problems.isEmpty(), rec.problems);
                StationContext ctx = rec.first;
                s.c.check("the context names the block, the dish and the cook", ctx != null && pot.equals(ctx.pos())
                    && ctx.dish() == Items.MELON_SLICE && ctx.cook().id().equals(s.v.cookId()) && ctx.level() == s.level, ctx);
                s.c.check("the cook was at the block when the batch started", rec.startDistance >= 0 && rec.startDistance <= 4.0,
                    rec.startDistance);
                s.c.check("its ingredients were used", total(s, Items.CLAY_BALL) == 0 && total(s, Items.SUGAR) == 0,
                    total(s, Items.CLAY_BALL) + " clay, " + total(s, Items.SUGAR) + " sugar");
                s.end();
            });
        });
    }

    /**
     * No station block in the kitchen: Towntopia's planner refuses the dish (plan and stock order) and the cook never
     * makes it. Then the block goes in (found by the room's rescan) and the dish is a candidate; a dish naming a station
     * nobody registered is not.
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "townkit_cook_station_missing")
    public static void stationMissingNotOffered(GameTestHelper helper) {
        scene(helper, "stationMissingNotOffered", s -> {
            Recorder rec = new Recorder(false);
            s.subs.add(TownKitCooking.station(OWNER, POT, CookStation.builder().block(Blocks.LODESTONE).handler(rec).build()));
            s.subs.add(TownKitCooking.addDish(OWNER, potMelon()));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            KitchenBuilding k = kitchen(s);
            if (k != null) k.validate(s.level);
            boolean[] before = melonCandidate(s);
            s.c.check("no station block: not a candidate", !before[0], "");
            s.c.check("no station block: no order candidate", !before[1], "");
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.CLAY_BALL, 2);
            stock(s, Items.SUGAR, 1);
            helper.runAfterDelay(1200, () -> {
                s.feed();
                s.c.check("not made in 1200 ticks", s.batches(Items.MELON_SLICE) == 0, s.batches);
                s.c.check("its ingredients untouched", total(s, Items.CLAY_BALL) == 2 && total(s, Items.SUGAR) == 1,
                    total(s, Items.CLAY_BALL) + " clay, " + total(s, Items.SUGAR) + " sugar");
                s.c.check("no handler call", rec.started.get() == 0 && rec.stopped.get() == 0, rec.started + "/" + rec.stopped);
                BlockPos pot = placeStation(s);
                s.c.check("control: the block placed is found", pot != null, "");
                boolean[] with = melonCandidate(s);
                s.c.check("control: with the block it is a candidate", with[0], "");
                s.c.check("control: and an order candidate", with[1], "");
                s.subs.add(TownKitCooking.changeDish(OWNER, Items.MELON_SLICE, DishChange.builder()
                    .station(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "nobody_registered")).build()));
                boolean[] unknown = melonCandidate(s);
                s.c.check("a station nobody registered: not a candidate", !unknown[0] && !unknown[1], "");
                s.end();
            });
        });
    }

    /**
     * The station block is broken while the cook works a batch at it. The batch ends without making
     * anything (the ingredients stay), {@code stopped} is heard once, nothing is logged as an error.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_station_broken")
    public static void stationBrokenMidWork(GameTestHelper helper) {
        scene(helper, "stationBrokenMidWork", s -> {
            Recorder rec = new Recorder(false);
            s.subs.add(TownKitCooking.station(OWNER, POT, CookStation.builder().block(Blocks.LODESTONE).handler(rec).build()));
            s.subs.add(TownKitCooking.addDish(OWNER, potMelon()));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            BlockPos pot = placeStation(s);
            if (pot == null) {
                s.c.check("the station block is found in the kitchen", false, "no spot");
                s.end();
                return;
            }
            Watch w = watchCook(s);
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.CLAY_BALL, 2);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 1, 8000, s.fed(() -> onMelon(w.activity.get()) && rec.started.get() > 0), () -> {
                if (!onMelon(w.activity.get()) || rec.started.get() == 0) {
                    s.c.check("the cook started a batch at the station", false, s.batches + ", started " + rec.started);
                    s.end();
                    return;
                }
                BuildingTests.ErrorCounter errors = BuildingTests.ErrorCounter.attach();
                s.level.setBlock(pot, Blocks.AIR.defaultBlockState(), 3);
                helper.runAfterDelay(300, () -> {
                    errors.detach();
                    s.feed();
                    s.c.check("the batch ended", !onMelon(w.activity.get()), "");
                    s.c.check("nothing made", s.batches(Items.MELON_SLICE) == 0 && total(s, Items.MELON_SLICE) == 0, s.batches);
                    s.c.check("the ingredients are still there", total(s, Items.CLAY_BALL) == 2 && total(s, Items.SUGAR) == 1,
                        total(s, Items.CLAY_BALL) + " clay, " + total(s, Items.SUGAR) + " sugar");
                    s.c.check("started once", rec.started.get() == 1, rec.started.get());
                    s.c.check("stopped once", rec.stopped.get() == 1, rec.stopped.get());
                    s.c.check("handler calls pair up", rec.problems.isEmpty(), rec.problems);
                    s.c.check("nothing logged as an error", errors.count.get() == 0, errors.first);
                    s.end();
                });
            });
        });
    }

    /**
     * The cook's body leaves the level mid-batch (unloaded, as with its chunk) with no {@code doStop}:
     * {@code stopped} is heard once, at once. The body is then loaded back from its save (as a chunk reload) and the
     * test waits for Towntopia to take it as the cook's body again before it ends.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_station_unloaded")
    public static void stationCookUnloadedMidWork(GameTestHelper helper) {
        scene(helper, "stationCookUnloadedMidWork", s -> {
            Recorder rec = new Recorder(false);
            s.subs.add(TownKitCooking.station(OWNER, POT, CookStation.builder().block(Blocks.LODESTONE).handler(rec).build()));
            s.subs.add(TownKitCooking.addDish(OWNER, potMelon()));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            BlockPos pot = placeStation(s);
            if (pot == null) {
                s.c.check("the station block is found in the kitchen", false, "no spot");
                s.end();
                return;
            }
            Watch w = watchCook(s);
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.CLAY_BALL, 2);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 1, 8000, s.fed(() -> onMelon(w.activity.get()) && rec.started.get() > 0), () -> {
                if (rec.started.get() == 0) {
                    s.c.check("the cook started a batch at the station", false, s.batches + ", started " + rec.started);
                    s.end();
                    return;
                }
                // as a chunk unload and reload: the body saved, removed, then loaded back from its save
                net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
                s.cook.saveAsPassenger(saved);
                s.cook.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
                s.c.check("stopped heard when the body left", rec.stopped.get() == 1, rec.started + "/" + rec.stopped);
                net.minecraft.world.entity.Entity back = net.minecraft.world.entity.EntityType.loadEntityRecursive(saved, s.level, e -> e);
                s.c.check("the body loads back", back != null && s.level.addFreshEntity(back), back);
                BuildingTests.waitFor(helper, 5, 1200, () -> {
                    VillagerModel m = s.v.mgr().getModel(s.v.cookId());
                    NpcEntity body = m == null ? null : m.puppet();
                    return body != null && body != s.cook && !body.isRemoved();
                }, () -> {
                    s.c.check("calls pair up (no second stopped for that batch)", rec.problems.isEmpty()
                        && rec.stopped.get() <= rec.started.get(), rec.problems + " " + rec.started + "/" + rec.stopped);
                    s.end();
                });
            });
        });
    }

    /**
     * A station handler that throws in {@code started} and {@code stopped}. The cook still makes the
     * dish; the failure is logged once for the owner; two failures don't switch the handler off.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_station_throws")
    public static void throwingStationHandler(GameTestHelper helper) {
        scene(helper, "throwingStationHandler", s -> {
            Recorder rec = new Recorder(true);
            Subscription station = TownKitCooking.station(OWNER, POT, CookStation.builder().block(Blocks.LODESTONE).handler(rec).build());
            s.subs.add(station);
            s.subs.add(TownKitCooking.addDish(OWNER, potMelon()));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            BlockPos pot = placeStation(s);
            if (pot == null) {
                s.c.check("the station block is found in the kitchen", false, "no spot");
                s.end();
                return;
            }
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.CLAY_BALL, 2);
            stock(s, Items.SUGAR, 1);
            BuildingTests.ErrorCounter errors = BuildingTests.ErrorCounter.attach();
            BuildingTests.waitFor(helper, 5, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0 && rec.stopped.get() > 0), () -> {
                errors.detach();
                s.c.check("the cook still made the dish", s.batches(Items.MELON_SLICE) == 1, s.batches);
                s.c.check("started and stopped were both called (and threw)", rec.started.get() == 1 && rec.stopped.get() == 1,
                    rec.started + "/" + rec.stopped);
                s.c.check("logged once for the owner", errors.count.get() == 1
                    && errors.first.stream().anyMatch(l -> l.contains(OWNER)), errors.count.get() + " " + errors.first);
                s.c.check("two failures don't switch it off", !station.isDisabled(), "");
                s.end();
            });
        });
    }

    /**
     * A station and its dish from data files only, loaded the way the reload listener loads them, with no
     * player online (so no datapack sync): the station hooks are attached and the cook makes the dish at the station.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_station_files")
    public static void dataFileStationWithoutPlayer(GameTestHelper helper) {
        scene(helper, "dataFileStationWithoutPlayer", s -> {
            s.c.check("no player online", s.level.getServer().getPlayerCount() == 0, s.level.getServer().getPlayerCount());
            CoreWork work = (CoreWork) TownKitBackend.get().work();
            Map<String, Map<ResourceLocation, com.google.gson.JsonElement>> files = Map.of(
                Stations.KIND, Map.of(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "test_pot"), com.google.gson.JsonParser.parseString(
                    "{\"action\":\"add\",\"id\":\"" + POT + "\",\"blocks\":\"minecraft:lodestone\"}")),
                CookOverlay.KIND, Map.of(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "pot_melon"), com.google.gson.JsonParser.parseString(
                    "{\"action\":\"add\",\"result\":\"minecraft:melon_slice\",\"ingredients\":{\"minecraft:clay_ball\":2,"
                        + "\"minecraft:sugar\":1},\"nutrition\":8,\"station\":\"" + POT + "\"}")));
            s.files = true;
            work.loadFiles(files);
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            s.c.check("the files' station is registered", Stations.GLOBAL.get(POT) != null, "");
            s.c.check("the station hooks are attached without a sync", CookHooks.attachedKeys().stream()
                .anyMatch(k -> k.contains("getCraftingTablePositions")), CookHooks.attachedKeys());
            BlockPos pot = placeStation(s);
            if (pot == null) {
                s.c.check("the station block is found in the kitchen", false, "no spot");
                s.end();
                return;
            }
            Watch w = watchCook(s);
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.CLAY_BALL, 2);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 5, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0), () -> {
                s.c.check("the cook made the dish", s.batches(Items.MELON_SLICE) == 1, s.batches);
                s.c.check("it worked at the station block", pot.equals(w.workedAt), w.workedAt + " vs " + pot);
                s.end();
            });
        });
    }

    // --- players' screens --------------------------------------------------------------------------------------------

    /** A fake player (no client, never in the player list) for a settings window. */
    private static FakePlayer screenPlayer(Scene s, String name) {
        FakePlayer p = FakePlayerFactory.get(s.level, new GameProfile(UUID.nameUUIDFromBytes(("townkit-" + name).getBytes()), name));
        s.undo.add(() -> {
            p.containerMenu = p.inventoryMenu;
            WorkScreens.forgetForTests(p.getUUID());
        });
        return p;
    }

    /** The players whose client "has TownKit", and what TownKit sent them. */
    private static final class Capture implements WorkScreens.Channel {
        final java.util.Set<ServerPlayer> modded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final List<Object[]> sent = new ArrayList<>();

        @Override
        public boolean reaches(ServerPlayer player) {
            return this.modded.contains(player);
        }

        @Override
        public void send(ServerPlayer to, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
            this.sent.add(new Object[] {to, payload});
        }

        /** The last payload of {@code type} sent to {@code to}, or null. */
        @Nullable
        <T> T last(ServerPlayer to, Class<T> type) {
            for (int i = this.sent.size() - 1; i >= 0; i--) {
                if (this.sent.get(i)[0] == to && type.isInstance(this.sent.get(i)[1])) return type.cast(this.sent.get(i)[1]);
            }
            return null;
        }

        int count(ServerPlayer to, Class<?> type) {
            int n = 0;
            for (Object[] e : this.sent) if (e[0] == to && type.isInstance(e[1])) n++;
            return n;
        }
    }

    private static Capture capture(Scene s, ServerPlayer... modded) {
        Capture c = new Capture();
        java.util.Collections.addAll(c.modded, modded);
        WorkScreens.Channel old = WorkScreens.swapChannelForTests(c);
        s.undo.add(() -> WorkScreens.swapChannelForTests(old));
        return c;
    }

    /** The cook's settings window as the server writes it for {@code player} (Minecraft makes the menu, then writes it). */
    private static RegistryFriendlyByteBuf openWindow(Scene s, ServerPlayer player, int id) {
        GenericSettingsMenu menu = new GenericSettingsMenu(id, player.getInventory(), s.cook);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), s.level.registryAccess());
        GenericSettingsMenu.writeOpeningData(buf, s.cook);
        player.containerMenu = menu;
        return buf;
    }

    /**
     * Reads a window's opening data the way a client whose cook page is {@code page} does ({@code GenericSettingsMenu}'s
     * own readers); null (and a failed check) when it can't, or when bytes are left over.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static List<List<GenericSettingsMenu.RowLine>> readAsClient(Checks c, String who, SettingsSchema page,
            RegistryFriendlyByteBuf buf) {
        try {
            buf.readVarInt(); // villager id
            buf.readVarInt(); // profession
            Method lines = GenericSettingsMenu.class.getDeclaredMethod("readLines", SettingsSchema.class, FriendlyByteBuf.class);
            Method sale = GenericSettingsMenu.class.getDeclaredMethod("readSaleData", RegistryFriendlyByteBuf.class);
            lines.setAccessible(true);
            sale.setAccessible(true);
            List<List<GenericSettingsMenu.RowLine>> out = (List<List<GenericSettingsMenu.RowLine>>) lines.invoke(null, page, buf);
            sale.invoke(null, buf);
            TradesmanLedger.Snapshot.read(buf);
            c.check(who + ": the client reads the window exactly", buf.readableBytes() == 0, buf.readableBytes() + " bytes left over");
            return buf.readableBytes() == 0 ? out : null;
        } catch (InvocationTargetException e) {
            c.check(who + ": the client reads the window", false, e.getCause());
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            c.check(who + ": the client reads the window", false, e);
            return null;
        }
    }

    /** The value a window line shows for an "allowed recipes" entry, or null when the window has no such line. */
    @Nullable
    private static Boolean shown(List<List<GenericSettingsMenu.RowLine>> lines, String entry) {
        for (List<GenericSettingsMenu.RowLine> section : lines) {
            for (GenericSettingsMenu.RowLine l : section) {
                if (!l.isHeader() && ALLOWED.equals(l.row().settingId()) && entry.equals(l.entryId())) return l.boolValue();
            }
        }
        return null;
    }

    /** One switch saved the way Towntopia's settings packet handler saves it (its own private method). */
    private static void save(NpcEntity cook, String entry, boolean on) {
        SettingsSchema schema = SettingsSchemaRegistry.forProfession(Profession.COOK).orElseThrow();
        try {
            Method m = ModPayloads.class.getDeclaredMethod("applyGenericSettingsUpdate", NpcEntity.class, SettingsSchema.class,
                SettingsSchema.Row.class, String.class);
            m.setAccessible(true);
            m.invoke(null, cook, schema, schema.findRow(ALLOWED).orElseThrow(), entry + ":" + on);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't save like Towntopia's settings packet", e);
        }
    }

    /** The two addon dishes and the removed cake the screen tests use (two added: one removed and one added keep the length). */
    private static void screenDishes(Scene s) {
        s.subs.add(TownKitCooking.addDish(OWNER, melon()));
        s.subs.add(TownKitCooking.removeDish(OWNER, Items.CAKE));
        s.subs.add(TownKitCooking.addDish(OWNER, Dish.builder(Items.BEETROOT_SEEDS).ingredient(Items.BEETROOT, 1).build()));
    }

    /** The page the client builds from the lists sent to {@code to}, or null (and a failed check) when none carried dishes. */
    @Nullable
    private static SettingsSchema sentPage(Scene s, Capture cap, ServerPlayer to) {
        WorkListsPayload p = cap.last(to, WorkListsPayload.class);
        if (p == null || p.dishes().isEmpty()) {
            s.c.check("the lists sent carry the dishes", false, p);
            return null;
        }
        return CookScreenRows.schema(CookSettingsSchema.SCHEMA, p.dishes().get());
    }

    /** The window marker sent right before {@code to}'s last window: the version its window was written with. */
    private static int windowMarker(Capture cap, ServerPlayer to) {
        WorkWindowPayload w = cap.last(to, WorkWindowPayload.class);
        return w == null ? -1 : w.version();
    }

    /**
     * Players' screens. A player whose client lacks TownKit (never sent the payload) gets the cook's
     * settings window written with Towntopia's own rows (their client reads it exactly, no addon row), and saving from it
     * keeps the addon dish switched off, even for a save naming the addon dish. A player with TownKit gets the rows;
     * until their client answers that it shows them, their window is written with Towntopia's rows too; after a true
     * answer it is written with the page built from the rows (which is the server's page): read exactly, the addon dish
     * shows and can be switched.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_cook_screens")
    public static void noTownKitSaveKeepsAddonToggles(GameTestHelper helper) {
        scene(helper, "noTownKitSaveKeepsAddonToggles", s -> {
            String melonId = id(Items.MELON_SLICE);
            String cakeId = id(Items.CAKE);
            screenDishes(s);
            FakePlayer plain = screenPlayer(s, "townkit_screens_plain");
            FakePlayer modded = screenPlayer(s, "townkit_screens_modded");
            Capture cap = capture(s, modded);
            WorkScreens.Prepared lists = WorkScreens.prepare();
            WorkScreens.send(plain, lists);
            WorkScreens.send(modded, lists);
            s.c.check("only the player with TownKit is sent the lists", cap.count(plain, WorkListsPayload.class) == 0
                && cap.count(modded, WorkListsPayload.class) == 1, cap.sent.size());
            ProfessionSettings.of(s.cook, CookOverlay.GLOBAL.schema()).setAllowed(ALLOWED, melonId, false);

            // the player without TownKit
            List<List<GenericSettingsMenu.RowLine>> plainLines = readAsClient(s.c, "no TownKit", CookSettingsSchema.SCHEMA,
                openWindow(s, plain, 1));
            if (plainLines != null) {
                s.c.check("no TownKit: no row for the addon dish", shown(plainLines, melonId) == null, "");
                s.c.check("no TownKit: Towntopia's bread shows on", Boolean.TRUE.equals(shown(plainLines, id(Items.BREAD))), "");
            }
            s.c.check("no TownKit: no window marker", cap.count(plain, WorkWindowPayload.class) == 0, "");
            for (String id : CookSettingsSchema.SCHEMA.findRow(ALLOWED).orElseThrow().entryIds()) save(s.cook, id, true);
            save(s.cook, melonId, true); // a save naming a dish this player's screen doesn't show
            if (hooksOff()) {
                s.c.check("hooks off: no dish rows sent", lists.payload().dishes().isEmpty(), lists.payload());
                s.c.check("hooks off: Towntopia's settings registry", SettingsSchemaRegistry.forProfession(Profession.COOK)
                    .orElseThrow() == CookSettingsSchema.SCHEMA, "");
                readAsClient(s.c, "hooks off, TownKit", CookSettingsSchema.SCHEMA, openWindow(s, modded, 2));
                s.end();
                return;
            }
            CookSettings after = CookSettings.of(s.cook);
            s.c.check("no TownKit: saving keeps the addon dish switched off", !after.allowsRecipe(Items.MELON_SLICE), "");
            s.c.check("no TownKit: its own switches were saved", after.allowsRecipe(Items.BREAD), "");
            plain.containerMenu = plain.inventoryMenu;

            // the player with TownKit, before their client answered: Towntopia's rows
            readAsClient(s.c, "TownKit, no answer yet", CookSettingsSchema.SCHEMA, openWindow(s, modded, 2));
            s.c.check("a closed window isn't kept (the fake player's ref is dropped)", !WorkScreens.viewingForTests(plain.getUUID())
                && WorkScreens.viewingForTests(modded.getUUID()), "");
            s.c.check("TownKit, no answer yet: the window marker says Towntopia's", windowMarker(cap, modded) == 0, windowMarker(cap, modded));
            modded.containerMenu = modded.inventoryMenu;
            SettingsSchema page = sentPage(s, cap, modded);
            if (page == null) {
                s.end();
                return;
            }
            WorkScreens.onApplied(modded, new WorkListsAppliedPayload(lists.payload().version(), true));
            s.c.check("TownKit: the page built from the rows is the server's page", page.equals(CookOverlay.GLOBAL.schema()), "");
            List<List<GenericSettingsMenu.RowLine>> moddedLines = readAsClient(s.c, "TownKit", page, openWindow(s, modded, 3));
            s.c.check("TownKit: the window marker names the lists", windowMarker(cap, modded) == lists.payload().version(),
                windowMarker(cap, modded));
            if (moddedLines != null) {
                s.c.check("TownKit: the addon dish shows, switched off", Boolean.FALSE.equals(shown(moddedLines, melonId)),
                    shown(moddedLines, melonId));
                s.c.check("TownKit: no row for the removed cake", shown(moddedLines, cakeId) == null, "");
            }
            save(s.cook, melonId, true);
            s.c.check("TownKit: the player can switch the addon dish on", CookSettings.of(s.cook).allowsRecipe(Items.MELON_SLICE), "");
            s.end();
        });
    }

    /**
     * A client with TownKit's channel that did not apply the page (it answered false: hooks
     * off on that install, a page it couldn't build, ...) gets its window written with Towntopia's schema, and so does
     * one whose answer is for other lists or never came.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_cook_screens_not_applied")
    public static void channelButNotAppliedGetsTowntopias(GameTestHelper helper) {
        scene(helper, "channelButNotAppliedGetsTowntopias", s -> {
            screenDishes(s);
            FakePlayer modded = screenPlayer(s, "townkit_screens_not_applied");
            Capture cap = capture(s, modded);
            WorkScreens.Prepared lists = WorkScreens.prepare();
            WorkScreens.send(modded, lists);
            if (hooksOff()) {
                s.c.check("hooks off: no dish rows sent", lists.payload().dishes().isEmpty(), lists.payload());
                s.end();
                return;
            }
            s.c.check("the lists were sent", cap.count(modded, WorkListsPayload.class) == 1, "");
            WorkScreens.onApplied(modded, new WorkListsAppliedPayload(lists.payload().version() + 1000, true));
            readAsClient(s.c, "an answer for unknown lists", CookSettingsSchema.SCHEMA, openWindow(s, modded, 1));
            s.c.check("an answer for unknown lists: marker 0", windowMarker(cap, modded) == 0, windowMarker(cap, modded));
            WorkScreens.onApplied(modded, new WorkListsAppliedPayload(lists.payload().version(), false));
            readAsClient(s.c, "answered false", CookSettingsSchema.SCHEMA, openWindow(s, modded, 2));
            s.c.check("answered false: marker 0", windowMarker(cap, modded) == 0, windowMarker(cap, modded));
            s.c.check("answered false: Towntopia's page", WorkScreens.cookPage(modded) == CookSettingsSchema.SCHEMA, "");
            // a late true for the same lists after the false: the false was final for those lists
            WorkScreens.onApplied(modded, new WorkListsAppliedPayload(lists.payload().version(), true));
            s.c.check("a second answer for the same lists is ignored", WorkScreens.cookPage(modded) == CookSettingsSchema.SCHEMA, "");
            s.end();
        });
    }

    /**
     * Lists change (a /reload, a registration) after the client answered. Until it answers
     * the new lists, its windows are Towntopia's; a late answer for the old lists counts for nothing.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_cook_screens_stale")
    public static void staleAnswerAfterListChange(GameTestHelper helper) {
        scene(helper, "staleAnswerAfterListChange", s -> {
            screenDishes(s);
            FakePlayer modded = screenPlayer(s, "townkit_screens_stale");
            Capture cap = capture(s, modded);
            WorkScreens.Prepared v1 = WorkScreens.prepare();
            WorkScreens.send(modded, v1);
            if (hooksOff()) {
                s.c.check("hooks off: no dish rows sent", v1.payload().dishes().isEmpty(), v1.payload());
                s.end();
                return;
            }
            WorkScreens.onApplied(modded, new WorkListsAppliedPayload(v1.payload().version(), true));
            s.c.check("v1 answered: v1's page", WorkScreens.cookPage(modded) == v1.page(), "");
            // the list changes: a third added dish, new lists sent
            s.subs.add(TownKitCooking.addDish(OWNER, Dish.builder(Items.PUMPKIN_SEEDS).ingredient(Items.PUMPKIN, 1).build()));
            WorkScreens.Prepared v2 = WorkScreens.prepare();
            WorkScreens.send(modded, v2);
            s.c.check("new lists have a new version", v2.payload().version() != v1.payload().version(), v2.payload().version());
            readAsClient(s.c, "v2 sent, not answered", CookSettingsSchema.SCHEMA, openWindow(s, modded, 1));
            s.c.check("v2 not answered: marker 0", windowMarker(cap, modded) == 0, windowMarker(cap, modded));
            WorkScreens.onApplied(modded, new WorkListsAppliedPayload(v1.payload().version(), true)); // stale
            readAsClient(s.c, "a stale answer for v1", CookSettingsSchema.SCHEMA, openWindow(s, modded, 2));
            s.c.check("a stale answer: marker 0", windowMarker(cap, modded) == 0, windowMarker(cap, modded));
            WorkScreens.onApplied(modded, new WorkListsAppliedPayload(v2.payload().version(), true));
            SettingsSchema page = sentPage(s, cap, modded);
            if (page != null) {
                List<List<GenericSettingsMenu.RowLine>> lines = readAsClient(s.c, "v2 answered", page, openWindow(s, modded, 3));
                s.c.check("v2 answered: marker v2", windowMarker(cap, modded) == v2.payload().version(), windowMarker(cap, modded));
                if (lines != null) {
                    s.c.check("v2 answered: the third dish shows", shown(lines, id(Items.PUMPKIN_SEEDS)) != null, "");
                }
            }
            s.end();
        });
    }

    /**
     * The cook's settings window opened through Minecraft's real menu path, the way Towntopia's
     * open handler does it ({@code player.openMenu} with a {@code GenericSettingsMenu} provider and
     * {@code writeOpeningData} as the extra-data writer), for a player without TownKit and for one with TownKit that
     * answered the lists. The bytes Minecraft would send are read back with Towntopia's own readers: nothing left over.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_cook_screens_open")
    public static void realOpenPathReadsExactly(GameTestHelper helper) {
        scene(helper, "realOpenPathReadsExactly", s -> {
            screenDishes(s);
            ServerPlayer plain = openingPlayer(s, "townkit_open_plain");
            ServerPlayer modded = openingPlayer(s, "townkit_open_modded");
            Capture cap = capture(s, modded);
            WorkScreens.Prepared lists = WorkScreens.prepare();
            WorkScreens.send(plain, lists);
            WorkScreens.send(modded, lists);
            WorkScreens.onApplied(modded, new WorkListsAppliedPayload(lists.payload().version(), true));

            RegistryFriendlyByteBuf plainBytes = openForReal(s, plain);
            s.c.check("no TownKit: the window opened", plainBytes != null && plain.containerMenu instanceof GenericSettingsMenu, "");
            if (plainBytes != null) {
                List<List<GenericSettingsMenu.RowLine>> lines = readAsClient(s.c, "no TownKit, real open", CookSettingsSchema.SCHEMA, plainBytes);
                if (lines != null) s.c.check("no TownKit: no addon row", shown(lines, id(Items.MELON_SLICE)) == null, "");
            }
            RegistryFriendlyByteBuf moddedBytes = openForReal(s, modded);
            s.c.check("TownKit: the window opened", moddedBytes != null && modded.containerMenu instanceof GenericSettingsMenu, "");
            int marker = windowMarker(cap, modded);
            SettingsSchema page = hooksOff() || marker == 0 ? CookSettingsSchema.SCHEMA : sentPage(s, cap, modded);
            if (!hooksOff()) s.c.check("TownKit: the window marker names the lists", marker == lists.payload().version(), marker);
            if (moddedBytes != null && page != null) {
                List<List<GenericSettingsMenu.RowLine>> lines = readAsClient(s.c, "TownKit, real open", page, moddedBytes);
                if (lines != null && !hooksOff()) s.c.check("TownKit: the addon row shows", shown(lines, id(Items.MELON_SLICE)) != null, "");
            }
            s.end();
        });
    }

    /**
     * A real server player (not a {@link FakePlayer}, whose {@code openMenu} does nothing) that is never added to the
     * level or the player list; its connection is a fake player's, which sends nothing.
     */
    private static ServerPlayer openingPlayer(Scene s, String name) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("townkit-" + name).getBytes()), name);
        FakePlayer stand = FakePlayerFactory.get(s.level, new GameProfile(UUID.nameUUIDFromBytes(("townkit-conn-" + name).getBytes()),
            "c_" + name.substring(0, Math.min(name.length(), 12))));
        ServerPlayer p = new ServerPlayer(s.level.getServer(), s.level, profile, net.minecraft.server.level.ClientInformation.createDefault());
        p.connection = stand.connection;
        p.moveTo(s.cook.getX() + 1, s.cook.getY(), s.cook.getZ());
        s.undo.add(() -> {
            p.containerMenu = p.inventoryMenu;
            WorkScreens.forgetForTests(p.getUUID());
        });
        return p;
    }

    /** Opens the cook's settings for {@code player} as Towntopia's open handler does; the bytes written, or null. */
    @Nullable
    private static RegistryFriendlyByteBuf openForReal(Scene s, ServerPlayer player) {
        AtomicReference<byte[]> written = new AtomicReference<>();
        NpcEntity cook = s.cook;
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, ignored) -> new GenericSettingsMenu(id, inventory, cook),
            net.minecraft.network.chat.Component.literal("Cook Settings")), buf -> {
                GenericSettingsMenu.writeOpeningData(buf, cook);
                byte[] b = new byte[buf.writerIndex()];
                buf.getBytes(0, b);
                written.set(b);
            });
        byte[] b = written.get();
        return b == null ? null : new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(b), s.level.registryAccess());
    }

    /**
     * Nothing registered: every read hands back Towntopia's own objects, so the cook decides exactly as without TownKit
     * (the bridges return what Towntopia passed them; no listener of TownKit's cook runs except the stale-batch guard
     * once an earlier test changed the list).
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_cook_untouched")
    public static void noAddonUnchanged(GameTestHelper helper) {
        Checks c = new Checks("noAddonUnchanged");
        List<CookActivity.Recipe> own = towntopiaRecipes();
        c.check("nothing registered", !CookOverlay.GLOBAL.touched(), "");
        c.check("recipes() is Towntopia's own list", CookActivity.recipes() == own, "");
        c.check("Towntopia has 18 dishes", own.size() == 18, own.size());
        List<Dish> dishes = TownKitCooking.dishes();
        boolean same = dishes.size() == own.size();
        for (int i = 0; same && i < own.size(); i++) {
            CookActivity.Recipe r = own.get(i);
            Dish d = dishes.get(i);
            same = d.result() == r.result() && d.outputPerBatch() == r.outputPerBatch() && d.ingredients().equals(r.inputsPerBatch())
                && d.heated() == r.needsFurnace() && d.nutrition() == r.nutrition() && d.station() == null;
        }
        c.check("dishes() are Towntopia's 18, field for field", same, dishes.size());
        c.check("the settings registry has Towntopia's schema",
            SettingsSchemaRegistry.forProfession(Profession.COOK).orElse(null) == CookSettingsSchema.SCHEMA, "");
        // the batch guards (doTick, doStop) stay once an earlier test changed the list or had a station dish
        c.check("no schema, candidate or station hook attached (the test data tags a bowl meal: its container hook is on)", CookHooks.attachedKeys().stream()
            .allMatch(k -> k.contains("#doTick(") || k.contains("#doStop(") || k.equals(CookHooks.CONTAINER_OF)), CookHooks.attachedKeys());
        boolean dishesOk = own.stream().allMatch(r -> CookActivity.isDish(new ItemStack(r.result())))
            && !CookActivity.isDish(new ItemStack(Items.MELON_SLICE));
        c.check("isDish answers Towntopia's set", dishesOk, "");
        c.check("the bridges hand back what they get", CookOverlay.onRecipes(own) == own
            && CookOverlay.onIngredients(CookActivity.INGREDIENTS) == CookActivity.INGREDIENTS
            && CookOverlay.onSchema(CookSettingsSchema.SCHEMA) == CookSettingsSchema.SCHEMA, "");
        c.finish(helper);
    }

    /** Hooks off (config): dishes() still answers, Towntopia's cook keeps its list. Hooks on: the cook's reads are rewritten. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_cook_hooks")
    public static void hooksOffUsesTowntopia(GameTestHelper helper) {
        Checks c = new Checks("hooksOffUsesTowntopia");
        Subscription sub = TownKitCooking.addDish(OWNER, melon());
        try {
            c.check("dishes() lists the dish", TownKitCooking.dishes().stream().anyMatch(d -> d.result() == Items.MELON_SLICE), "");
            if (hooksOff()) {
                c.check("hooks off: Towntopia keeps its 18", CookActivity.recipes().size() == 18 && recipe(Items.MELON_SLICE) == null,
                    CookActivity.recipes().size());
                c.check("hooks off: not a dish to Towntopia", !CookActivity.isDish(new ItemStack(Items.MELON_SLICE)), "");
                c.check("hooks off: nothing rewritten", !RewrittenWorkSites.GLOBAL.loaded("village/villagers/cook/behavior/CookActivity"), "");
            } else {
                c.check("hooks on: Towntopia's recipes() has it", recipe(Items.MELON_SLICE) != null, CookActivity.recipes().size());
                WorkSites sites = WorkSites.load();
                List<String> missing = RewrittenWorkSites.GLOBAL.missing(sites, "village/villagers/cook/behavior/CookActivity");
                c.check("hooks on: every audited CookActivity read is rewritten", missing.isEmpty(), missing);
            }
        } finally {
            sub.remove();
        }
        c.finish(helper);
    }

    // --- containers back (W13) ---------------------------------------------------------------------------------------

    /** 1 milk bucket + 1 sugar -> 1 melon slice; {@code keep}: the empty bucket comes back. */
    private static Dish milkMelon(boolean keep) {
        return Dish.builder(Items.MELON_SLICE).ingredient(Items.MILK_BUCKET, 1).ingredient(Items.SUGAR, 1).nutrition(8)
            .keepContainers(keep).build();
    }

    /** A keep-containers dish: the cook's pack gains the empty bucket once the batch used the milk bucket. */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_keep")
    public static void keepContainersReturnsBucket(GameTestHelper helper) {
        scene(helper, "keepContainersReturnsBucket", s -> {
            s.subs.add(TownKitCooking.addDish(OWNER, milkMelon(true)));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.MILK_BUCKET, 1);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 1, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0), () -> {
                s.c.check("the cook made the dish", s.batches(Items.MELON_SLICE) == 1, s.batches);
                s.c.check("the milk bucket was used", total(s, Items.MILK_BUCKET) == 0, total(s, Items.MILK_BUCKET));
                s.c.check("the empty bucket is in the cook's pack", carried(s.cook, Items.BUCKET) == 1,
                    carried(s.cook, Items.BUCKET) + " in the pack, " + stored(s, Items.BUCKET) + " stored");
                s.end();
            });
        });
    }

    /** The same dish without the flag: Towntopia's cook uses the milk bucket up, no bucket appears. */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_keep_off")
    public static void noFlagNoBucket(GameTestHelper helper) {
        scene(helper, "noFlagNoBucket", s -> {
            s.subs.add(TownKitCooking.addDish(OWNER, milkMelon(false)));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.MILK_BUCKET, 1);
            stock(s, Items.SUGAR, 1);
            BuildingTests.waitFor(helper, 1, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0), () -> {
                s.c.check("the cook made the dish", s.batches(Items.MELON_SLICE) == 1, s.batches);
                s.c.check("the milk bucket was used", total(s, Items.MILK_BUCKET) == 0, total(s, Items.MILK_BUCKET));
                s.c.check("no bucket anywhere", total(s, Items.BUCKET) == 0, total(s, Items.BUCKET));
                s.end();
            });
        });
    }

    /**
     * A full pack. The cook carries the milk bucket in slot 0 and sugar in every other slot. The batch frees
     * slot 0 for the dish, so no room is left for the bucket: it pops at her feet (Towntopia's loss log), and the dish isn't lost.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "townkit_cook_keep_full")
    public static void fullPackPopsBucketToLossLog(GameTestHelper helper) {
        scene(helper, "fullPackPopsBucketToLossLog", s -> {
            s.subs.add(TownKitCooking.addDish(OWNER, milkMelon(true)));
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            allowOnly(s.cook, Items.MELON_SLICE);
            InventoryManager inv = s.cook.getInventory();
            ItemStack milk = new ItemStack(Items.MILK_BUCKET);
            VillageTokenBinding.bind(milk, s.v.mgr());
            inv.setSlot(0, milk);
            // sugar everywhere else (the dish's own ingredient, so the cook keeps it)
            for (int i = 1; i < 27; i++) {
                ItemStack sugar = new ItemStack(Items.SUGAR, 64);
                VillageTokenBinding.bind(sugar, s.v.mgr());
                inv.setSlot(i, sugar);
            }
            net.minecraft.world.phys.AABB around = s.cook.getBoundingBox().inflate(12);
            s.undo.add(() -> s.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, around,
                e -> e.getItem().is(Items.BUCKET)).forEach(Entity::discard));
            BuildingTests.waitFor(helper, 1, 8000, s.fed(() -> s.batches(Items.MELON_SLICE) > 0), () -> {
                s.c.check("the cook made the dish", s.batches(Items.MELON_SLICE) == 1, s.batches);
                s.c.check("the dish is in her pack", carried(s.cook, Items.MELON_SLICE) == 1, carried(s.cook, Items.MELON_SLICE));
                s.c.check("no bucket in the pack", carried(s.cook, Items.BUCKET) == 0, carried(s.cook, Items.BUCKET));
                int popped = s.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, s.cook.getBoundingBox().inflate(12),
                    e -> e.getItem().is(Items.BUCKET)).stream().mapToInt(e -> e.getItem().getCount()).sum();
                s.c.check("the bucket popped at her feet", popped == 1, popped);
                s.end();
            });
        });
    }

    /** The away day: a keep-containers dish's batch leaves the bucket in the away result; the same dish without it doesn't. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_cook_keep_away")
    public static void awayCookKeepsBucket(GameTestHelper helper) {
        scene(helper, "awayCookKeepsBucket", s -> {
            Subscription keep = TownKitCooking.addDish(OWNER, milkMelon(true));
            s.subs.add(keep);
            if (endIfHooksOff(s, Items.MELON_SLICE)) return;
            allowOnly(s.cook, Items.MELON_SLICE);
            stock(s, Items.MILK_BUCKET, 4);
            stock(s, Items.SUGAR, 4);
            helper.runAfterDelay(20, () -> {
                Map<Item, Integer> with = awayPreview(s);
                s.c.check("away: the dish is cooked", with.getOrDefault(Items.MELON_SLICE, 0) > 0, with);
                s.c.check("away: the buckets come back", with.getOrDefault(Items.BUCKET, 0) == with.getOrDefault(Items.MELON_SLICE, 0), with);
                keep.remove();
                s.subs.add(TownKitCooking.addDish(OWNER, milkMelon(false)));
                Map<Item, Integer> without = awayPreview(s);
                s.c.check("away: the dish is cooked without the flag", without.getOrDefault(Items.MELON_SLICE, 0) > 0, without);
                s.c.check("away: no bucket without the flag", without.getOrDefault(Items.BUCKET, 0) == 0, without);
                s.end();
            });
        });
    }

    // --- bowls back and milk on demand (W13) --------------------------------------------------------------------------

    /** Eats {@code meals} meals one after the other: how many bowls came back (1000 for anything else that came back). */
    private static int leftovers(Scene s, Item meal, int meals) {
        MealLeftovers.setCounts(s.cook, new net.minecraft.nbt.CompoundTag());
        int back = 0;
        for (int i = 0; i < meals; i++) {
            ItemStack left = MealLeftovers.leftover(s.cook, new ItemStack(meal), s.v.mgr());
            if (left.is(Items.BOWL)) back++;
            else if (!left.isEmpty()) back += 1000;
        }
        MealLeftovers.setCounts(s.cook, new net.minecraft.nbt.CompoundTag());
        return back;
    }

    /**
     * A meal in {@code #townkit:meal_returns_remainder} (test data) hands its crafting remainder back. Towntopia's rule keeps
     * every third container, so over 9 meals some bowls came back (6 by that rule; the test only needs some).
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_meal_bowl")
    public static void taggedBowlMealReturnsBowl(GameTestHelper helper) {
        scene(helper, "taggedBowlMealReturnsBowl", s -> {
            if (hooksOff()) {
                s.c.check("hooks off: the meal leaves nothing", leftovers(s, TestBlocks.TEST_BOWL_STEW.get(), 9) == 0, "");
                s.end();
                return;
            }
            s.c.check("the tag lists the test meal", new ItemStack(TestBlocks.TEST_BOWL_STEW.get()).is(CookHooks.MEAL_RETURNS_REMAINDER), "");
            s.c.check("the container hook is attached", CookHooks.attachedKeys().contains(CookHooks.CONTAINER_OF), CookHooks.attachedKeys());
            int back = leftovers(s, TestBlocks.TEST_BOWL_STEW.get(), 9);
            s.c.check("some bowls came back, only bowls", back > 0 && back < 1000, back);
            s.end();
        });
    }

    /** A meal not in the tag: Towntopia's answer (no container) stays. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_meal_bowl")
    public static void untaggedMealUnchanged(GameTestHelper helper) {
        scene(helper, "untaggedMealUnchanged", s -> {
            s.c.check("no bowl for an untagged meal", leftovers(s, TestBlocks.TEST_BOWL_PLAIN.get(), 9) == 0, "");
            s.c.check("Towntopia's own container (honey bottle) still answers",
                MealLeftovers.containerOf(new ItemStack(Items.HONEY_BOTTLE)).is(Items.GLASS_BOTTLE), "");
            s.end();
        });
    }

    private static final int MILK_X = 16400;
    private static final int MILK_Z = 9900;

    /**
     * A village of its own: a cook, a rancher and a ranch with a cow. The cook's only dish is an addon milk dish below its
     * stock, no cake wanted: the rancher's milk target (Towntopia's {@code BucketNeeds}) is above 0 only then.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_cook_milk")
    public static void rancherMilksForAddonDish(GameTestHelper helper) {
        Checks c = new Checks("rancherMilksForAddonDish");
        ServerLevel level = helper.getLevel();
        TestKit.setHeadless(true);
        BehaviourTests.forceChunks(level, MILK_X, MILK_Z);
        int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, MILK_X, MILK_Z);
        ScenarioBuilder.VillageResult r = ScenarioBuilder.buildVillage(level, new BlockPos(MILK_X, feetY, MILK_Z), feetY, 0);
        VillageManager mgr = r.manager();
        if (mgr == null) {
            helper.fail("milk test village was not created");
            return;
        }
        TestKit.setSimulatedPlayer(r.townHallDoor(), level.dimension());
        ScenarioBuilder.buildRanch(level, mgr, MILK_X - 14, feetY, MILK_Z + 14, net.minecraft.world.entity.EntityType.COW, 2);
        NpcEntity cook = ScenarioBuilder.spawnVillager(level, mgr, r.townHallDoor().north(3), Profession.COOK);
        ScenarioBuilder.spawnVillager(level, mgr, r.townHallDoor().north(5), Profession.RANCHER);
        mgr.rebuildNow(level);
        Subscription[] sub = new Subscription[1];
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                if (sub[0] != null) sub[0].remove();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                if (sub[0] != null) sub[0].remove();
            }

            @Override
            public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner runner) {
            }
        });
        BuildingTests.waitFor(helper, 1, 300, () -> {
            VillagerModel m = mgr.getModel(cook.getNpcId());
            return m != null && m.puppet() != null && mgr.getVillagers().stream().allMatch(v -> v.puppet() != null);
        }, () -> {
            sub[0] = TownKitCooking.addDish(OWNER, milkMelon(false));
            if (hooksOff()) {
                c.check("hooks off: the milk hook is not attached", !CookHooks.attachedKeys().contains(CookHooks.WANTS_CAKE), "");
                BehaviourTests.finish(helper, c);
                return;
            }
            // no cake wanted: only the addon dish (with milk) is allowed to this cook
            allowOnly(cook, Items.MELON_SLICE);
            BucketNeeds.forget(mgr);
            c.check("the milk hook is attached", CookHooks.attachedKeys().contains(CookHooks.WANTS_CAKE), CookHooks.attachedKeys());
            c.check("the rancher is asked to milk", BucketNeeds.milkWanted(mgr, level) > 0, BucketNeeds.milkWanted(mgr, level));
            // the dish is off in the cook's settings: no milk for it
            ProfessionSettings.of(cook, CookOverlay.GLOBAL.schema()).setAllowed(ALLOWED, id(Items.MELON_SLICE), false);
            BucketNeeds.forget(mgr);
            c.check("not allowed: no milk", BucketNeeds.milkWanted(mgr, level) == 0, BucketNeeds.milkWanted(mgr, level));
            BehaviourTests.finish(helper, c);
        });
    }
}
