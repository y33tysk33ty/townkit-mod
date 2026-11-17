package townkit.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import townkit.core.TownKit;
import townkit.core.hook.HookStatus;

/**
 * Dev check for client hooks, which a dedicated server can't load: with -Dtownkit.verifyClientAndQuit=true the client
 * verifies every hook (client ones included) when the title screen first opens, logs the result and quits.
 * Run with ./gradlew runClient -Ptownkit.verifyClientAndQuit=true.
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class ClientVerify {
    private static boolean done;

    private ClientVerify() {
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening event) {
        if (done || !(event.getNewScreen() instanceof TitleScreen) || !Boolean.getBoolean("townkit.verifyClientAndQuit")) {
            return;
        }
        done = true;
        long t0 = System.nanoTime();
        HookStatus.Verification v = HookStatus.verify();
        TownKit.LOG.info("[TownKit client verify] {} hooks checked in {} ms: {} live, {} missing, {} in classes that failed to load",
            v.checked(), String.format("%.0f", (System.nanoTime() - t0) / 1e6), v.live(), v.missing(), v.classFailed());
        v.missingKeys().stream().limit(10).forEach(k -> TownKit.LOG.info("[TownKit client verify]   missing: {}", k));
        v.failedClasses().stream().limit(10).forEach(k -> TownKit.LOG.info("[TownKit client verify]   class failed: {}", k));
        Minecraft.getInstance().stop();
    }
}
