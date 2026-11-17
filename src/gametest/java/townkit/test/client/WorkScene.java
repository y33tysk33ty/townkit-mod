package townkit.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import java.lang.reflect.Method;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import townkit.api.work.Dish;
import townkit.api.work.OreEntry;
import townkit.api.work.TownKitCooking;
import townkit.api.work.TownKitMining;
import townkit.core.client.WorkScreensHooks;

/**
 * Scene {@code work}: a test village with a cook and a miner, a check addon's dish (2 apples + 1 sugar -> 1 melon slice)
 * and a check addon's ore (the vanilla amethyst block standing in for a modded ore block). The client gets the work
 * lists (its player has TownKit); the cook's settings window opens (as Towntopia's settings button opens it) and its
 * Recipes tab shows the check dish: the window's own rows hold its id, read from the menu the client built from the
 * server's opening data. Then the miner's window opens and its Ores tab ("wanted ores") shows the check ore.
 * Screenshots of the Recipes tab and the Ores tab.
 */
final class WorkScene {
    private static final int FEET_Y = -60;
    private static final String OWNER = "townkit-client-check";
    private static final String DISH = BuiltInRegistries.ITEM.getKey(Items.MELON_SLICE).toString();
    private static final ResourceLocation ORE = ResourceLocation.fromNamespaceAndPath("townkit-check", "amethyst_ore");
    /** The Ores tab: Towntopia's miner settings sections are Behaviour, Equipment, Ores. */
    private static final int ORES_TAB = 2;
    /** The Recipes tab: Towntopia's cook settings sections are Behaviour, Recipes, Stock. */
    private static final int RECIPES_TAB = 1;

    private WorkScene() {
    }

    static List<CheckRun.Step> steps() {
        return List.of(
            CheckRun.act("build village, cook, miner, check dish and ore", r -> r.onServer("cook", WorkScene::build)),
            CheckRun.await("village built", 200, r -> r.ready("cook")),
            CheckRun.await("the work lists arrived with the check dish and ore", 200, r -> WorkScreensHooks.cookEntryIds().contains(DISH)
                && WorkScreensHooks.minerEntryIds().contains(ORE.toString())),
            CheckRun.act("open the cook's settings", r -> r.onServer("open", s -> open(s, r.<int[]>result("cook")[0], "Cook Settings"))),
            CheckRun.await("cook settings window open", 200, r -> menu(r.mc().screen) != null),
            CheckRun.act("recipes tab", r -> showTab(r.mc().screen, RECIPES_TAB)),
            CheckRun.act("hide the hud", r -> r.mc().options.hideGui = true),
            CheckRun.pause("settle", 10),
            CheckRun.act("checks: the check dish's row", r -> {
                GenericSettingsMenu menu = menu(r.mc().screen);
                SettingsSchema.Row allowed = menu.schema().findRow(CookSettingsSchema.ALLOWED_RECIPES).orElse(null);
                List<String> ids = allowed == null ? List.of() : allowed.entryIds();
                r.check("the window's Recipes list has the check dish", ids.contains(DISH), ids);
                boolean line = menu.linesForSection(RECIPES_TAB).stream().anyMatch(l -> DISH.equals(l.entryId()));
                r.check("the Recipes tab has a line for it", line, menu.linesForSection(RECIPES_TAB).size() + " lines");
                r.check("Towntopia's own dishes are still there", ids.contains("minecraft:bread") && ids.contains("minecraft:cake"), ids.size());
                r.check("the stock section has its row", menu.schema().findRow(CookSettingsSchema.stockId(DISH)).isPresent(), "");
                r.shot();
            }),
            CheckRun.act("close the window", r -> r.mc().setScreen(null)),
            CheckRun.pause("closed", 10),
            CheckRun.act("open the miner's settings", r -> r.onServer("openMiner", s -> open(s, r.<int[]>result("cook")[1], "Miner Settings"))),
            CheckRun.await("miner settings window open", 200, r -> menu(r.mc().screen) != null
                && menu(r.mc().screen).schema().findRow(MinerSettingsSchema.WANTED_ORES).isPresent()),
            CheckRun.act("ores tab", r -> showTab(r.mc().screen, ORES_TAB)),
            CheckRun.pause("settle", 10),
            CheckRun.act("checks: the check ore's row", r -> {
                GenericSettingsMenu menu = menu(r.mc().screen);
                SettingsSchema.Row wanted = menu.schema().findRow(MinerSettingsSchema.WANTED_ORES).orElse(null);
                List<String> ids = wanted == null ? List.of() : wanted.entryIds();
                r.check("the window's wanted ores list has the check ore", ids.contains(ORE.toString()), ids);
                boolean line = menu.linesForSection(ORES_TAB).stream().anyMatch(l -> ORE.toString().equals(l.entryId()));
                r.check("the Ores tab has a line for it", line, menu.linesForSection(ORES_TAB).size() + " lines");
                r.check("Towntopia's own seven ores are still there", ids.contains("coal_ore") && ids.contains("diamond_ore") && ids.size() == 8, ids);
                r.shot();
            }));
    }

    /** Server thread: the village, its cook's and miner's entity ids, the check dish and the check ore; the player owns nothing (creative). */
    private static Object build(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(0, FEET_Y, 0), FEET_Y, 0);
        VillageManager mgr = v.manager();
        if (mgr == null) throw new IllegalStateException("test village was not created");
        NpcEntity cook = ScenarioBuilder.spawnVillager(level, mgr, v.townHallDoor().north(3), Profession.COOK);
        NpcEntity miner = ScenarioBuilder.spawnVillager(level, mgr, v.townHallDoor().north(4), Profession.MINER);
        TownKitCooking.addDish(OWNER, Dish.builder(Items.MELON_SLICE).ingredient(Items.APPLE, 2).ingredient(Items.SUGAR, 1)
            .nutrition(8).build());
        TownKitMining.ore(OWNER, OreEntry.builder(ORE, net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK).depths(0, 64)
            .label(Component.literal("Check ore")).build());
        return new int[] {cook.getId(), miner.getId()};
    }

    /** Server thread: opens a villager's settings window for the player the way Towntopia's settings button does. */
    @Nullable
    private static Object open(MinecraftServer server, int villagerId, String title) {
        Entity e = server.overworld().getEntity(villagerId);
        if (!(e instanceof NpcEntity villager)) throw new IllegalStateException("the test villager is gone");
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.connection.teleport(villager.getX() + 1.5, villager.getY(), villager.getZ(), 90f, 0f);
        p.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new GenericSettingsMenu(id, inventory, villager),
            Component.literal(title)), buf -> GenericSettingsMenu.writeOpeningData(buf, villager));
        return villagerId;
    }

    @Nullable
    private static GenericSettingsMenu menu(@Nullable Screen s) {
        return s instanceof AbstractContainerScreen<?> c && c.getMenu() instanceof GenericSettingsMenu m ? m : null;
    }

    private static void showTab(Screen s, int tab) {
        try {
            Method m = s.getClass().getDeclaredMethod("showTab", int.class);
            m.setAccessible(true);
            m.invoke(s, tab);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't switch the settings window's tab", e);
        }
    }
}
