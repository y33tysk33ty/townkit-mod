package townkit.test;

import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.core.Profession;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import townkit.core.TownKit;
import townkit.core.work.WorkScreens;

/**
 * Dedicated-server check for a client without TownKit ({@code runServerCheckTests -Ptownkit.openCookCheck=true}, then
 * {@code runClientNoTownKit}): when the first player joins, builds a village with a cook next to them and opens the
 * cook's settings window for them the way Towntopia's settings button does. The server must have an addon dish (a data
 * file). The log says whether the window is still open and the player still connected 10 s later (a client that misread
 * the window would have dropped it, or been disconnected). Does nothing without {@code -Dtownkit.openCookCheck=true}.
 */
@EventBusSubscriber(modid = TownKit.MODID)
public final class NoTownKitOpenCheck {
    private static final boolean ON = Boolean.getBoolean("townkit.openCookCheck");
    @Nullable
    private static ServerPlayer player;
    @Nullable
    private static NpcEntity cook;
    private static int ticks = -1;

    private NoTownKitOpenCheck() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (ON && player == null && event.getEntity() instanceof ServerPlayer p) {
            player = p;
            ticks = 0;
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!ON || player == null || ticks < 0) return;
        ticks++;
        ServerPlayer p = player;
        if (ticks == 100) {
            ServerLevel level = p.serverLevel();
            int x = p.getBlockX() + 40;
            int z = p.getBlockZ();
            int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(x, feetY, z), feetY, 0);
            VillageManager mgr = v.manager();
            if (mgr == null) {
                log("FAIL: the check village was not created");
                ticks = -1;
                return;
            }
            cook = ScenarioBuilder.spawnVillager(level, mgr, v.townHallDoor().north(3), Profession.COOK);
            log("village and cook ready");
        } else if (ticks == 160 && cook != null) {
            NpcEntity c = cook;
            p.connection.teleport(c.getX() + 1.5, c.getY(), c.getZ(), 90f, 0f);
            p.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new GenericSettingsMenu(id, inventory, c),
                Component.literal("Cook Settings")), buf -> GenericSettingsMenu.writeOpeningData(buf, c));
            log("opened the cook's settings for " + p.getGameProfile().getName() + " (window page version "
                + WorkScreens.windowVersion(p) + ", addon rows in the server's page: "
                + townkit.core.work.cook.CookOverlay.GLOBAL.touched() + ", addon rows in this player's page: "
                + !WorkScreens.cookPage(p).equals(com.alex.test.village.villagers.cook.settings.CookSettingsSchema.SCHEMA) + ")");
        } else if (ticks == 360) {
            boolean connected = p.server.getPlayerList().getPlayer(p.getUUID()) == p;
            boolean open = p.containerMenu instanceof GenericSettingsMenu;
            log((connected && open ? "PASS" : "FAIL") + ": 10 s later the player is " + (connected ? "connected" : "gone")
                + " and the cook's window is " + (open ? "still open" : "closed"));
            ticks = -1;
        }
    }

    private static void log(String line) {
        TownKit.LOG.info("[TownKit open-cook check] {}", line);
    }
}
