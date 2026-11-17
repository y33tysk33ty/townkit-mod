package townkit.test;

import com.alex.test.entity.NpcEntity;
import com.alex.test.network.ModPayloads;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.village.villagers.druid.DruidBalance;
import com.alex.test.village.villagers.miner.MinerBalance;
import com.alex.test.village.villagers.miner.OreBlocks;
import com.alex.test.village.villagers.miner.OreHeights;
import com.alex.test.village.villagers.miner.behavior.MinerActivity;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.work.OreEntry;
import townkit.api.work.TownKitMining;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.work.RewrittenWorkSites;
import townkit.core.work.WorkScreens;
import townkit.core.work.WorkSites;
import townkit.core.work.mine.MineBridges;
import townkit.core.work.mine.MineHooks;
import townkit.core.work.mine.MiningOverlay;

/**
 * The miner's and druid's ores: addon ores with lucky rolls, druid placements and depths, against Towntopia's own
 * calls ({@code MinerActivity.yieldSource} for a miner's roll, {@code DruidBalance.regrownRock}, {@code yieldOf} for what a
 * dug block drops, {@code SettingsSchemaRegistry} for the wanted-ores row), seeded so a run is repeatable. Tests that need
 * a miner villager borrow the forge GameTests' village ({@link ForgeTests#withMiner}); the rest need no village. The addon:
 * {@code townkit:test_tin} (blocks {@code townkit:test_tin_ore} and {@code townkit:test_deepslate_tin_ore}, which drop
 * what copper ore drops) and {@code townkit:test_zinc}. With TownKit's hooks switched off each test checks that the API
 * still answers and Towntopia's miner and druid keep their seven ores.
 */
@GameTestHolder(CookingTests.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class MiningTests {
    private static final String OWNER = "townkit-test-mining";
    private static final ResourceLocation TIN = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_tin");
    private static final ResourceLocation ZINC = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_zinc");
    private static final int ROLLS = 40000;
    private static final int Y = 40;
    private static final int LEVEL = MinerBalance.MAX_LEVEL;

    private MiningTests() {
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static boolean hooksOff() {
        return RewrittenSites.GLOBAL.hooksOff();
    }

    private static Block tin() {
        return TestBlocks.TEST_TIN_ORE.get();
    }

    private static Block deepTin() {
        return TestBlocks.TEST_DEEPSLATE_TIN_ORE.get();
    }

    private static Block zinc() {
        return TestBlocks.TEST_ZINC_ORE.get();
    }

    private static OreEntry.Builder tinOre() {
        return OreEntry.builder(TIN, tin()).deepslate(deepTin()).label(Component.literal("Test Tin"));
    }

    /** A lucky-roll weight that takes half of the rolled ores at {@link #Y} (the addon's density equals Towntopia's total). */
    private static double halfWeight() {
        return MiningOverlay.towntopiaTotal(Y) * (OreEntry.MAX_Y - OreEntry.MIN_Y + 1);
    }

    /** What one run of rolls came to. */
    private record Rolls(int rolled, int addon, int towntopia) {
        double share() {
            return this.rolled == 0 ? 0 : (double) this.addon / this.rolled;
        }
    }

    /** {@code ROLLS} seeded calls of Towntopia's own {@code yieldSource} on plain stone at height {@code y}. */
    private static Rolls miner(long seed, int y, Block... addonBlocks) {
        RandomSource random = RandomSource.create(seed);
        BlockState stone = Blocks.STONE.defaultBlockState();
        int rolled = 0;
        int addon = 0;
        for (int i = 0; i < ROLLS; i++) {
            BlockState out = MinerActivity.yieldSource(stone, y, LEVEL, random);
            if (out == stone) continue;
            rolled++;
            for (Block b : addonBlocks) {
                if (out.is(b)) {
                    addon++;
                    break;
                }
            }
        }
        return new Rolls(rolled, addon, rolled - addon);
    }

    /** {@code ROLLS} seeded calls of Towntopia's own {@code regrownRock} on {@code rock} at height {@code y}. */
    private static Rolls druid(long seed, int y, BlockState rock, Block... addonBlocks) {
        RandomSource random = RandomSource.create(seed);
        int rolled = 0;
        int addon = 0;
        for (int i = 0; i < ROLLS; i++) {
            BlockState out = DruidBalance.regrownRock(LEVEL, y, rock, random);
            if (out == rock) continue;
            boolean mine = false;
            for (Block b : addonBlocks) mine |= out.is(b);
            boolean towntopias = false;
            for (OreHeights.Ore ore : OreHeights.Ore.values()) {
                towntopias |= out.is(OreBlocks.stone(ore)) || out.is(OreBlocks.deepslate(ore));
            }
            if (!mine && !towntopias) continue; // emerald, ancient debris
            rolled++;
            if (mine) addon++;
        }
        return new Rolls(rolled, addon, rolled - addon);
    }

    /** What a test registered; undone also when the test fails or times out. */
    private static final class Scene {
        final Checks c;
        final List<Subscription> subs = new ArrayList<>();
        final List<Runnable> undo = new ArrayList<>();
        boolean ended;

        Scene(GameTestHelper helper, String name) {
            this.c = new Checks(name);
            helper.testInfo.addListener(new GameTestListener() {
                @Override
                public void testStructureLoaded(GameTestInfo info) {
                }

                @Override
                public void testPassed(GameTestInfo info, GameTestRunner runner) {
                    cleanUp();
                }

                @Override
                public void testFailed(GameTestInfo info, GameTestRunner runner) {
                    cleanUp();
                }

                @Override
                public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner runner) {
                }
            });
        }

        Subscription ore(OreEntry ore) {
            Subscription s = TownKitMining.ore(OWNER, ore);
            this.subs.add(s);
            return s;
        }

        void cleanUp() {
            if (this.ended) return;
            this.ended = true;
            this.subs.forEach(Subscription::remove);
            this.undo.forEach(Runnable::run);
        }

        void end(GameTestHelper helper) {
            cleanUp();
            BehaviourTests.finish(helper, this.c);
        }
    }

    private static boolean nothingAttached() {
        return MineHooks.attachedKeys().isEmpty() && !MineHooks.checkOn();
    }

    // --- tests -------------------------------------------------------------------------------------------------------

    /**
     * What the miner digs from an addon ore block is the block's own loot, from Towntopia's own {@code yieldOf} (the call
     * its dig and its away shift make): true with an ore registered and with none, and with TownKit's hooks off. Only the
     * ore bonus (Towntopia's {@code isOre}) needs TownKit.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_mine_drops")
    public static void realModdedOreDrops(GameTestHelper helper) {
        Scene s = new Scene(helper, "realModdedOreDrops");
        ForgeTests.withMiner(helper, s.c, miner -> {
            ServerLevel level = helper.getLevel();
            BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
            level.setBlock(pos, tin().defaultBlockState(), 3);
            s.undo.add(() -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
            List<ItemStack> before = MinerActivity.yieldOf(level, pos, level.getBlockState(pos), miner, pick);
            s.c.check("with nothing registered: the miner's dig gets the block's loot (raw copper)", count(before, Items.RAW_COPPER) > 0, before);
            s.c.check("with nothing registered: not an ore to Towntopia", !MinerActivity.isOre(level.getBlockState(pos)), "");
            s.ore(tinOre().depths(0, 64).build());
            List<ItemStack> after = MinerActivity.yieldOf(level, pos, level.getBlockState(pos), miner, pick);
            s.c.check("registered: the same loot", count(after, Items.RAW_COPPER) > 0, after);
            level.setBlock(pos, deepTin().defaultBlockState(), 3);
            s.c.check("the deepslate block drops too", count(MinerActivity.yieldOf(level, pos, level.getBlockState(pos), miner, pick),
                Items.RAW_COPPER) > 0, "");
            if (hooksOff()) {
                s.c.check("hooks off: Towntopia's ore check doesn't know it", !MinerActivity.isOre(level.getBlockState(pos)), "");
            } else {
                s.c.check("hooks on: the ore check is in reach", MineHooks.checkOn(), "");
                s.c.check("hooks on: an addon ore's blocks are ore (ore bonus, veins, ore sense)",
                    MinerActivity.isOre(level.getBlockState(pos)) && MinerActivity.isOre(tin().defaultBlockState()), "");
                s.c.check("hooks on: stone and zinc (not registered) are not", !MinerActivity.isOre(Blocks.STONE.defaultBlockState())
                    && !MinerActivity.isOre(zinc().defaultBlockState()), "");
                s.c.check("Towntopia's own ores are still ore", MinerActivity.isOre(Blocks.IRON_ORE.defaultBlockState()), "");
            }
            s.end(helper);
        });
    }

    /** A rolled ore turns into the addon ore by its share ({@code w / (T + w)}); hooks off: Towntopia's seven only. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_mine_lucky")
    public static void luckyRollGivesAddonOre(GameTestHelper helper) {
        Scene s = new Scene(helper, "luckyRollGivesAddonOre");
        try {
            s.ore(tinOre().luckyRolls(halfWeight(), OreEntry.MIN_Y, OreEntry.MAX_Y).build());
            Rolls r = miner(42L, Y, tin(), deepTin());
            s.c.check("Towntopia's own roll still gives ores", r.rolled() > 1000, r);
            if (hooksOff()) {
                s.c.check("hooks off: no addon ore is rolled", r.addon() == 0, r);
                s.c.check("hooks off: nothing attached", nothingAttached(), MineHooks.attachedKeys());
            } else {
                s.c.check("the yield hook is attached", MineHooks.attachedKeys().stream().anyMatch(k -> k.contains("yieldSource")), MineHooks.attachedKeys());
                s.c.check("about half of the rolled ores are the addon's (share " + r.share() + ")", Math.abs(r.share() - 0.5) < 0.04, r);
                s.c.check("repeatable: the same seed rolls the same", miner(42L, Y, tin(), deepTin()).equals(r), "");
                RandomSource random = RandomSource.create(7L);
                BlockState stone = Blocks.STONE.defaultBlockState();
                boolean allDeep = true;
                int deep = 0;
                for (int i = 0; i < ROLLS; i++) {
                    BlockState out = MinerActivity.yieldSource(stone, -20, LEVEL, random);
                    if (out.is(tin())) allDeep = false;
                    if (out.is(deepTin())) deep++;
                }
                s.c.check("below Y 0 the addon ore rolls as its deepslate block", allDeep && deep > 0, deep);
            }
        } finally {
            s.end(helper);
        }
    }

    /** An addon ore without lucky rolls is never rolled, with or without TownKit's hooks. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_mine_nolucky")
    public static void noLuckyRollsWithoutOptIn(GameTestHelper helper) {
        Scene s = new Scene(helper, "noLuckyRollsWithoutOptIn");
        try {
            Rolls towntopias = miner(42L, Y); // before the ore is registered: Towntopia's own rolls
            s.ore(tinOre().depths(0, 64).druid(halfWeight(), OreEntry.MIN_Y, OreEntry.MAX_Y).build());
            Rolls r = miner(42L, Y, tin(), deepTin());
            s.c.check("Towntopia's own roll still gives ores", r.rolled() > 1000, r);
            s.c.check("no addon ore is rolled", r.addon() == 0, r);
            s.c.check("the yield hook isn't attached", MineHooks.attachedKeys().stream().noneMatch(k -> k.contains("yieldSource")),
                MineHooks.attachedKeys());
            s.c.check("the very same rolls as Towntopia's own (a run before registering)", towntopias.rolled() == r.rolled()
                && towntopias.rolled() > 1000, towntopias + " vs " + r);
        } finally {
            s.end(helper);
        }
    }

    /** The druid's regrown rock holds an addon ore only if the ore opts in with {@code druid(...)}. */
    @GameTest(template = "empty", timeoutTicks = 900, batch = "townkit_mine_druid")
    public static void druidIgnoresAddonOreWithoutOptIn(GameTestHelper helper) {
        Scene s = new Scene(helper, "druidIgnoresAddonOreWithoutOptIn");
        try {
            BlockState rock = Blocks.STONE.defaultBlockState();
            Subscription lucky = s.ore(tinOre().luckyRolls(halfWeight(), OreEntry.MIN_Y, OreEntry.MAX_Y).build());
            Rolls without = druid(42L, Y, rock, tin(), deepTin());
            s.c.check("the druid's own ores", without.rolled() > 1000, without);
            s.c.check("an ore with lucky rolls only: the druid ignores it", without.addon() == 0, without);
            s.c.check("the druid hook isn't attached", MineHooks.attachedKeys().stream().noneMatch(k -> k.contains("regrownRock")),
                MineHooks.attachedKeys());
            lucky.remove();
            s.ore(tinOre().druid(halfWeight(), OreEntry.MIN_Y, OreEntry.MAX_Y).build());
            Rolls with = druid(42L, Y, rock, tin(), deepTin());
            if (hooksOff()) {
                s.c.check("hooks off: the druid keeps its seven ores", with.addon() == 0, with);
            } else {
                s.c.check("opted in: about half of the druid's ores are the addon's (" + with.share() + ")", Math.abs(with.share() - 0.5) < 0.04, with);
                s.c.check("the miner's rolls don't get it (no lucky rolls)", miner(42L, Y, tin(), deepTin()).addon() == 0, "");
                Rolls deep = druid(9L, -20, Blocks.DEEPSLATE.defaultBlockState(), tin(), deepTin());
                RandomSource random = RandomSource.create(9L);
                boolean onlyDeep = true;
                for (int i = 0; i < ROLLS; i++) {
                    BlockState out = DruidBalance.regrownRock(LEVEL, -20, Blocks.DEEPSLATE.defaultBlockState(), random);
                    if (out.is(tin())) onlyDeep = false;
                }
                s.c.check("in deepslate it places the deepslate ore, never the stone one", deep.addon() > 0 && onlyDeep, deep);
            }
        } finally {
            s.end(helper);
        }
    }

    /**
     * The "wanted ores" row of the miner's settings lists the addon ore (off by default, with its label and depths), and
     * a villager that switched it on sorts mines by its depths: Towntopia's fit grows for a mine inside them, also for a
     * villager that wants no Towntopia ore.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_mine_wanted")
    public static void wantedOresListsAddonOre(GameTestHelper helper) {
        Scene s = new Scene(helper, "wantedOresListsAddonOre");
        ForgeTests.withMiner(helper, s.c, miner -> {
            String id = TIN.toString();
            // Towntopia's own fit of a coal wish, before any addon ore or wish exists
            double coal100 = OreHeights.fit(EnumSet.of(OreHeights.Ore.COAL), 100);
            double coal32 = OreHeights.fit(EnumSet.of(OreHeights.Ore.COAL), 32);
            s.ore(tinOre().depths(0, 64).build());
            s.ore(OreEntry.builder(ZINC, zinc()).luckyRolls(1.0, 0, 10).build());
            SettingsSchema page = SettingsSchemaRegistry.forProfession(Profession.MINER).orElseThrow();
            SettingsSchema.Row row = page.findRow(MinerSettingsSchema.WANTED_ORES).orElseThrow();
            if (hooksOff()) {
                s.c.check("hooks off: the registry keeps Towntopia's page", page == MinerSettingsSchema.SCHEMA, "");
                s.c.check("hooks off: no row for it", !row.entryIds().contains(id), row.entryIds());
                s.c.check("hooks off: nothing attached", nothingAttached(), MineHooks.attachedKeys());
                s.c.check("the API still answers", TownKitMining.ores().size() == 2, TownKitMining.ores());
                s.end(helper);
                return;
            }
            s.c.check("the row lists Towntopia's seven then the addon ores", row.entryIds().size() == 9 && row.entryIds().get(7).equals(id)
                && row.entryIds().get(8).equals(ZINC.toString()), row.entryIds());
            SettingsSchema.AllowListEntry entry = row.allowListEntries().get(7);
            s.c.check("with its label, off by default, and its depths", entry.label().equals("Test Tin") && !entry.defaultAllowed()
                && entry.description().contains("Y 0 to 64"), entry);
            s.c.check("an ore without depths says so", row.allowListEntries().get(8).description().contains("no best depth"), "");
            s.c.check("the attached hooks: settings, wanted, fit, yield", MineHooks.attachedKeys().size() == 4, MineHooks.attachedKeys());

            ProfessionSettings settings = ProfessionSettings.of(miner, page);
            for (String known : row.entryIds()) settings.setAllowed(MinerSettingsSchema.WANTED_ORES, known, false);
            s.undo.add(() -> {
                for (String known : row.entryIds()) settings.setAllowed(MinerSettingsSchema.WANTED_ORES, known, false);
            });
            double off = OreHeights.fit(MinerActivity.wantedOres(miner), 32);
            s.c.check("nothing wanted: the mine's fit is 0", off == 0.0 && MineBridges.onWantedEmpty(true), off);
            settings.setAllowed(MinerSettingsSchema.WANTED_ORES, id, true);
            Set<OreHeights.Ore> wanted = MinerActivity.wantedOres(miner);
            s.c.check("a villager that wants only the addon ore: Towntopia's set is empty", wanted.isEmpty(), wanted);
            s.c.check("but it still sorts the mines (mineOrder's isEmpty is answered false)", !MineBridges.onWantedEmpty(true), "");
            s.c.check("a mine inside its depths fits 1", OreHeights.fit(wanted, 32) == 1.0, OreHeights.fit(wanted, 32));
            s.c.check("a mine outside its depths fits 0", OreHeights.fit(wanted, 100) == 0.0, OreHeights.fit(wanted, 100));
            s.c.check("a set of another villager's isn't touched (Towntopia's fit before registering)",
                OreHeights.fit(EnumSet.of(OreHeights.Ore.COAL), 100) == coal100 && OreHeights.fit(EnumSet.of(OreHeights.Ore.COAL), 32) == coal32
                && OreHeights.fit(EnumSet.noneOf(OreHeights.Ore.class), 32) == 0.0, coal100 + ", " + coal32);
            settings.setAllowed(MinerSettingsSchema.WANTED_ORES, "coal_ore", true);
            Set<OreHeights.Ore> both = MinerActivity.wantedOres(miner);
            double coalOnly = OreHeights.fit(EnumSet.of(OreHeights.Ore.COAL), 32);
            s.c.check("with a Towntopia ore too: its fit plus the addon's", Math.abs(OreHeights.fit(both, 32) - (coalOnly + 1.0)) < 1e-9
                && !both.isEmpty(), OreHeights.fit(both, 32) + " vs " + coalOnly);
            settings.setAllowed(MinerSettingsSchema.WANTED_ORES, id, false);
            settings.setAllowed(MinerSettingsSchema.WANTED_ORES, "coal_ore", false);
            Set<OreHeights.Ore> none = MinerActivity.wantedOres(miner);
            s.c.check("switched off again: back to Towntopia's", OreHeights.fit(none, 32) == 0.0 && MineBridges.onWantedEmpty(true), "");
            s.end(helper);
        });
    }

    /**
     * The miner's side of the players' screens. A player without TownKit has the miner's settings
     * window open with Towntopia's own page (no addon ore row); a save from it naming the addon ore is refused, so the
     * ore's switch stays off, while a switch on that page (a Towntopia ore) still saves. Hooks off: nothing attached.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "townkit_mine_screens")
    public static void noTownKitSaveKeepsAddonOre(GameTestHelper helper) {
        Scene s = new Scene(helper, "noTownKitSaveKeepsAddonOre");
        ForgeTests.withMiner(helper, s.c, miner -> {
            String id = TIN.toString();
            s.ore(tinOre().depths(0, 64).build());
            ServerLevel level = helper.getLevel();
            FakePlayer plain = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("townkit-mine-plain".getBytes()),
                "townkit_mine_plain"));
            s.undo.add(() -> {
                plain.containerMenu = plain.inventoryMenu;
                WorkScreens.forgetForTests(plain.getUUID());
            });
            SettingsSchema page = SettingsSchemaRegistry.forProfession(Profession.MINER).orElseThrow();
            ProfessionSettings settings = ProfessionSettings.of(miner, page);
            settings.setAllowed(MinerSettingsSchema.WANTED_ORES, id, false);
            settings.setAllowed(MinerSettingsSchema.WANTED_ORES, "coal_ore", false);
            s.undo.add(() -> {
                ProfessionSettings.of(miner, page).setAllowed(MinerSettingsSchema.WANTED_ORES, id, false);
                ProfessionSettings.of(miner, page).setAllowed(MinerSettingsSchema.WANTED_ORES, "coal_ore", false);
            });
            if (hooksOff()) {
                s.c.check("hooks off: Towntopia's page, nothing attached", page == MinerSettingsSchema.SCHEMA && nothingAttached(), "");
                s.end(helper);
                return;
            }
            // the window as the server writes it for this player (Minecraft makes the menu, then writes its data)
            GenericSettingsMenu menu = new GenericSettingsMenu(1, plain.getInventory(), miner);
            GenericSettingsMenu.writeOpeningData(new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess()), miner);
            plain.containerMenu = menu;
            s.c.check("no TownKit: the window is remembered with Towntopia's page", WorkScreens.viewingForTests(plain.getUUID())
                && WorkScreens.minerPage(plain) == MinerSettingsSchema.SCHEMA, "");
            saveWanted(miner, id, true);
            s.c.check("no TownKit: a save naming the addon ore is refused", !ProfessionSettings.of(miner, page)
                .isAllowed(MinerSettingsSchema.WANTED_ORES, id), "");
            saveWanted(miner, "coal_ore", true);
            s.c.check("no TownKit: a Towntopia ore on its page still saves", ProfessionSettings.of(miner, page)
                .isAllowed(MinerSettingsSchema.WANTED_ORES, "coal_ore"), "");
            plain.containerMenu = plain.inventoryMenu;
            ProfessionSettings.of(miner, page).setAllowed(MinerSettingsSchema.WANTED_ORES, id, true);
            s.c.check("window closed: Towntopia's own call (no window) is left alone", ProfessionSettings.of(miner, page)
                .isAllowed(MinerSettingsSchema.WANTED_ORES, id), "");
            s.end(helper);
        });
    }

    /** A "wanted ores" switch saved the way Towntopia's settings packet saves it. */
    private static void saveWanted(NpcEntity miner, String entry, boolean on) {
        SettingsSchema schema = SettingsSchemaRegistry.forProfession(Profession.MINER).orElseThrow();
        try {
            Method m = ModPayloads.class.getDeclaredMethod("applyGenericSettingsUpdate", NpcEntity.class, SettingsSchema.class,
                SettingsSchema.Row.class, String.class);
            m.setAccessible(true);
            m.invoke(null, miner, schema, schema.findRow(MinerSettingsSchema.WANTED_ORES).orElseThrow(), entry + ":" + on);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't save like Towntopia's settings packet", e);
        }
    }

    /** Nothing registered: no hook, every bridge hands back what it gets, Towntopia's rolls and rows exactly. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_mine_none")
    public static void noAddonUnchanged(GameTestHelper helper) {
        Scene s = new Scene(helper, "noAddonUnchanged");
        try {
            s.c.check("no mining hook attached, the ore check off", nothingAttached(), MineHooks.attachedKeys());
            s.c.check("the bridges hand back what they get", !MineBridges.onIsOre(false, tin().defaultBlockState())
                && MineBridges.onIsOre(true, Blocks.STONE.defaultBlockState()) && MineBridges.onWantedEmpty(true)
                && !MineBridges.onWantedEmpty(false), "");
            s.c.check("the settings registry keeps Towntopia's own miner page",
                SettingsSchemaRegistry.forProfession(Profession.MINER).orElse(null) == MinerSettingsSchema.SCHEMA, "");
            s.c.check("no addon ores", TownKitMining.ores().isEmpty(), TownKitMining.ores());
            // Towntopia's own roll, replayed from its own pieces, matches the calls
            RandomSource a = RandomSource.create(5L);
            RandomSource b = RandomSource.create(5L);
            BlockState stone = Blocks.STONE.defaultBlockState();
            int mismatches = 0;
            int ores = 0;
            for (int i = 0; i < 5000; i++) {
                BlockState expected = stone;
                if (a.nextDouble() < MinerBalance.scrapOreChance(LEVEL)) {
                    OreHeights.Ore ore = OreHeights.pick(MinerBalance.scrapMix(Y), a.nextDouble());
                    if (ore != null) expected = OreBlocks.stone(ore).defaultBlockState();
                }
                BlockState got = MinerActivity.yieldSource(stone, Y, LEVEL, b);
                if (got != expected) mismatches++;
                if (got != stone) ores++;
            }
            s.c.check("Towntopia's rolls, call for call (" + ores + " ores)", mismatches == 0 && ores > 100, mismatches);
            s.c.check("Towntopia's ore check", MinerActivity.isOre(Blocks.DIAMOND_ORE.defaultBlockState()) && !MinerActivity.isOre(Blocks.STONE.defaultBlockState()), "");
            String miner = "village/villagers/miner/behavior/MinerActivity";
            if (hooksOff()) {
                s.c.check("hooks off: nothing rewritten in the miner", !RewrittenWorkSites.GLOBAL.loaded(miner), "");
            } else {
                List<String> missing = RewrittenWorkSites.GLOBAL.missing(WorkSites.load(), miner);
                s.c.check("hooks on: both audited miner sites (ore check, mine sort) are rewritten", missing.isEmpty()
                    && RewrittenWorkSites.GLOBAL.loaded(miner), missing);
            }
        } finally {
            s.end(helper);
        }
    }

    /** Subscriptions come and go: removing the last ore detaches every hook and restores Towntopia's page. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_mine_remove")
    public static void removalRestoresTowntopias(GameTestHelper helper) {
        Scene s = new Scene(helper, "removalRestoresTowntopias");
        try {
            Subscription a = s.ore(tinOre().depths(0, 64).luckyRolls(1.0, 0, 64).druid(1.0, 0, 64).build());
            s.c.check("registered: the API answers", TownKitMining.ores().size() == 1 && TownKitMining.ores().get(0).id().equals(TIN), TownKitMining.ores());
            if (!hooksOff()) {
                s.c.check("hooks attached", MineHooks.attachedKeys().size() == 5 && MineHooks.checkOn(), MineHooks.attachedKeys());
            }
            Optional<SettingsSchema> page = SettingsSchemaRegistry.forProfession(Profession.MINER);
            s.c.check("the page has the row (hooks on) or is Towntopia's (off)", hooksOff() == (page.orElseThrow() == MinerSettingsSchema.SCHEMA), "");
            a.remove();
            a.remove();
            s.c.check("removed: no ores, no hooks, Towntopia's page", TownKitMining.ores().isEmpty() && nothingAttached()
                && SettingsSchemaRegistry.forProfession(Profession.MINER).orElseThrow() == MinerSettingsSchema.SCHEMA, MineHooks.attachedKeys());
            // an ore naming one of Towntopia's seven is skipped
            Subscription own = TownKitMining.ore(OWNER, OreEntry.builder(ResourceLocation.fromNamespaceAndPath("towntopia", "iron"), tin()).build());
            s.subs.add(own);
            s.c.check("Towntopia's own ore ids are skipped", TownKitMining.ores().isEmpty() && nothingAttached(), TownKitMining.ores());
        } finally {
            s.end(helper);
        }
    }

    private static int count(List<ItemStack> stacks, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack st : stacks) {
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }
}
