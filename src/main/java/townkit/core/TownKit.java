package townkit.core;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import townkit.api.spi.TownKitBackend;
import townkit.core.hook.HookManifest;
import townkit.core.hook.HookStatus;
import townkit.core.perf.PerfMonitor;

/**
 * TownKit: the library mod that holds every mixin into Towntopia and gives addons a stable API instead.
 * Foundation: generated hooks on (nearly) every Towntopia method, version check, performance tracking, commands.
 */
@Mod(TownKit.MODID)
public final class TownKit {
    public static final String MODID = "townkit";
    public static final String VERSION = BuildInfo.VERSION;
    public static final Logger LOG = LogUtils.getLogger();

    public TownKit(IEventBus modBus, ModContainer container) {
        TownKitBackend.install(new CoreBackend());
        if (FMLEnvironment.dist.isClient()) {
            // the addon look a player picked (client only; Towntopia's own look choice stays in Towntopia's config)
            container.registerConfig(ModConfig.Type.CLIENT, townkit.core.client.skin.LookChoice.SPEC, "townkit-client.toml");
        }
        // the real block checks for stand-in rules, before any addon can register one
        townkit.core.building.CoreBuildings.install();
        modBus.addListener(this::onSetup);
        NeoForge.EVENT_BUS.register(PerfMonitor.class);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> TownKitCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> LOG.info(
            "[TownKit] startup cost: hook list {} ms, injecting into {} loaded classes {} ms, version check {} ms",
            String.format("%.1f", HookManifest.LOAD_NANOS / 1e6), HookStatus.hookedClasses(),
            String.format("%.1f", HookStatus.injectNanos() / 1e6), String.format("%.1f", VersionGuard.checkNanos() / 1e6)));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> townkit.core.villager.VillagerDataHooks.onServerStarted(e.getServer()));
        LOG.info("[TownKit] {} hooks into Towntopia available", HookManifest.get().hooks.length);
    }

    private void onSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(VersionGuard::check);
        // Towntopia's dishes into the cook table before any data file is read (a change of one isn't reported missing)
        event.enqueueWork(townkit.core.work.cook.CookOverlay.GLOBAL::loadTowntopia);
    }
}
