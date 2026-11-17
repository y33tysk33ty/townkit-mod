package townkit.test.client;

import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageManager;
import com.alex.test.village.claims.VillageClaims;
import java.lang.reflect.Field;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import townkit.api.shop.ShopCheck;
import townkit.api.shop.ShopEntry;
import townkit.api.shop.TownKitShop;
import townkit.core.TownKit;
import townkit.core.client.ShopTabHooks;

/**
 * Scene {@code shop}: a test village whose Mayor sells two addon rows, one paid in gold nuggets and one greyed out
 * ("Only while at war."). Opens the Mayor's shop tab and checks what TownKit drew: the nugget as the coin row's
 * currency, the greyed row marked unavailable, the greyed row's tooltip (mouse moved onto it, screenshot), a click on
 * the greyed row's Buy that buys nothing, and a click on the coin row's Buy that buys the bell for 2 nuggets.
 */
final class ShopScene {
    private static final int FEET_Y = -60;
    private static final String COIN_ROW = TownKit.MODID + ":check_coin_row";
    private static final String GREYED_ROW = TownKit.MODID + ":check_greyed_row";
    private static final String WHY = "Only while at war.";

    private ShopScene() {
    }

    static List<CheckRun.Step> steps() {
        return List.of(
            CheckRun.act("build village, register rows", r -> r.onServer("village", ShopScene::buildVillage)),
            CheckRun.await("village built", 200, r -> r.ready("village")),
            CheckRun.act("find the Mayor", r -> r.onServer("mayor", s -> mayor(s, r.<BlockPos>result("village")))),
            CheckRun.await("Mayor found", 400, r -> {
                if (!r.ready("mayor")) return false;
                if (r.result("mayor") != null) return true;
                // not spawned yet: look again
                r.onServer("mayor", s -> mayor(s, r.<BlockPos>result("village")));
                return false;
            }),
            CheckRun.await("Mayor screen open", 200, r -> isMayorScreen(r.mc().screen)),
            CheckRun.act("shop tab", r -> setTab(r.mc().screen, "SHOP")),
            CheckRun.act("hide the hud", r -> r.mc().options.hideGui = true),
            CheckRun.await("TownKit drew both rows", 200, r -> ShopTabHooks.attached() && card(COIN_ROW) != null && card(GREYED_ROW) != null),
            CheckRun.act("checks: drawn rows", r -> {
                ShopTabHooks.DrawnCard coin = card(COIN_ROW);
                ShopTabHooks.DrawnCard greyed = card(GREYED_ROW);
                r.check("coin row drawn with the gold nugget as its currency",
                    ResourceLocation.parse("minecraft:gold_nugget").equals(coin.currency()), coin);
                r.check("coin row available", coin.available(), coin);
                r.check("greyed row drawn unavailable", !greyed.available(), greyed);
                r.check("Towntopia's rows untouched (no extras)", ShopTabHooks.extra("towntopia:farmer_token") == null, "farmer_token");
                moveMouse(r.mc(), greyed.x() + 10, greyed.y() + 10);
            }),
            CheckRun.await("greyed row's tooltip drawn", 100, r -> {
                String d = ShopTabHooks.lastDescription();
                return d != null && d.contains(WHY);
            }),
            CheckRun.pause("settle", 5),
            CheckRun.act("checks: tooltip", r -> {
                r.check("greyed row's tooltip has its description and reason",
                    ShopTabHooks.lastDescription().startsWith("A lantern for the client check.") && ShopTabHooks.lastDescription().endsWith(WHY),
                    ShopTabHooks.lastDescription());
                r.shot();
                click(r, card(GREYED_ROW));
            }),
            CheckRun.pause("greyed click settles", 40),
            CheckRun.act("checks: greyed click", r -> {
                r.check("greyed Buy bought nothing", count(r, Items.LANTERN) == 0 && count(r, Items.GOLD_NUGGET) == 10,
                    count(r, Items.LANTERN) + " lanterns, " + count(r, Items.GOLD_NUGGET) + " nuggets");
                click(r, card(COIN_ROW));
            }),
            CheckRun.await("coin row bought", 100, r -> count(r, Items.BELL) == 1),
            CheckRun.act("checks: coin click", r -> r.check("bell bought for 2 nuggets", count(r, Items.GOLD_NUGGET) == 8,
                count(r, Items.GOLD_NUGGET) + " nuggets")));
    }

    /** Server thread: the village, the two rows, ten (unbound) gold nuggets, the player owning the village. */
    private static Object buildVillage(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(0, FEET_Y, 0), FEET_Y, 0);
        VillageManager mgr = v.manager();
        if (mgr == null) throw new IllegalStateException("test village was not created");
        TownKitShop.add("townkit-client-check", ShopEntry.builder(ResourceLocation.parse(COIN_ROW), () -> Items.BELL, 2)
            .name(Component.literal("Check Bell")).description(Component.literal("A bell for the client check."))
            .priceGrowth(false).currency(() -> Items.GOLD_NUGGET).boundCurrency(false).build());
        TownKitShop.add("townkit-client-check", ShopEntry.builder(ResourceLocation.parse(GREYED_ROW), () -> Items.LANTERN, 1)
            .name(Component.literal("Check Lantern")).description(Component.literal("A lantern for the client check."))
            .priceGrowth(false).condition(ctx -> ShopCheck.greyed(Component.literal(WHY))).build());
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.getInventory().clearContent();
        p.getInventory().add(new ItemStack(Items.GOLD_NUGGET, 10));
        VillageClaims.claim(p, mgr);
        return v.townHallDoor();
    }

    /** Server thread: the Mayor near the door, with the player next to it and its menu open; null while it isn't there. */
    @Nullable
    static Object mayor(MinecraftServer server, BlockPos door) {
        List<Entity> mayors = server.overworld().getEntities((Entity) null, new AABB(door).inflate(40), e -> e instanceof MayorEntity);
        if (mayors.isEmpty()) return null;
        MayorEntity m = (MayorEntity) mayors.get(0);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.connection.teleport(m.getX() + 1.5, m.getY(), m.getZ(), 90f, 0f);
        p.openMenu(m);
        return m.getId();
    }

    static boolean isMayorScreen(@Nullable Screen s) {
        return s != null && s.getClass().getSimpleName().equals("MayorStatsScreen");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void setTab(Screen s, String tab) {
        try {
            Field f = s.getClass().getDeclaredField("tab");
            f.setAccessible(true);
            f.set(s, Enum.valueOf((Class) f.getType(), tab));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't switch the Mayor screen's tab", e);
        }
    }

    @Nullable
    private static ShopTabHooks.DrawnCard card(String offerId) {
        return ShopTabHooks.lastDrawn().stream().filter(c -> c.offerId().equals(offerId)).findFirst().orElse(null);
    }

    /** Puts the cursor at GUI coordinates (x, y): the screen reads the mouse handler's position each frame. */
    private static void moveMouse(Minecraft mc, int x, int y) {
        double scale = mc.getWindow().getGuiScale();
        try {
            Field fx = MouseHandler.class.getDeclaredField("xpos");
            Field fy = MouseHandler.class.getDeclaredField("ypos");
            fx.setAccessible(true);
            fy.setAccessible(true);
            fx.setDouble(mc.mouseHandler, x * scale);
            fy.setDouble(mc.mouseHandler, y * scale);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't move the mouse", e);
        }
    }

    /** Clicks the card's Buy button, as the screen gets a left click there. */
    private static void click(CheckRun r, ShopTabHooks.DrawnCard c) {
        double x = c.x() + c.width() - 5 - 22;
        double y = c.y() + 6 + 8;
        r.mc().screen.mouseClicked(x, y, 0);
        r.note("clicked Buy of " + c.offerId() + " at " + x + "," + y);
    }

    private static int count(CheckRun r, net.minecraft.world.item.Item item) {
        return r.mc().player == null ? -1 : r.mc().player.getInventory().countItem(item);
    }
}
