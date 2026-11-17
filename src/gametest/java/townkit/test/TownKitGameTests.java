package townkit.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import townkit.api.nav.Ride;
import townkit.api.nav.RideContext;
import townkit.api.nav.RideStatus;
import townkit.api.nav.TownKitTransport;
import townkit.api.nav.TransportKind;
import townkit.api.nav.TransportLink;
import townkit.api.nav.TransportStop;
import townkit.core.TownKit;

/**
 * Test-only wiring on TownKit's mod bus. This class lives in the {@code gametest} source set, which joins the
 * {@code townkit} mod only in the {@code gameTestServer} run: the client run and the release jar never see it, so
 * TownKit's own code never names the test package.
 *
 * <p>FML posts {@link FMLConstructModEvent} right after TownKit's constructor, before any registry event, so the
 * deferred registers added here are in time. On {@link FMLCommonSetupEvent} the test kind {@link SetupKind} is
 * registered and enabled the way the developer docs tell addons to (at mod setup), for {@code PathTests.kindEnabledAtSetupWorks}.
 */
// no bus = ...: FML puts each listener on the mod bus or the game bus by its event type (IModBusEvent -> mod bus)
@EventBusSubscriber(modid = TownKit.MODID)
public final class TownKitGameTests {
    private TownKitGameTests() {
    }

    @SubscribeEvent
    public static void onConstruct(FMLConstructModEvent event) {
        IEventBus modBus = ModList.get().getModContainerById(TownKit.MODID).orElseThrow().getEventBus();
        // test blocks and the stop-marker items: only with townkit.testblocks=true (the gameTestServer run sets it)
        TestBlocks.registerIfEnabled(modBus);
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        // perf runs (-Dtownkit.perf=true) are the same gameTestServer run: an enabled kind attaches the transport and
        // ride hooks for the whole run, which the perf A/B must not measure (a player without a transport addon never
        // pays it); kindEnabledAtSetupWorks skips itself in that mode
        if (!TestBlocks.enabled() || perfRun()) {
            return;
        }
        // common setup runs off the main thread, in parallel with other mods: register on the main thread, as an addon
        // following the developer docs would
        event.enqueueWork(() -> {
            TownKitTransport.registerKind("townkit-test-setup", new SetupKind());
            TownKitTransport.enable("townkit-test-setup", SetupKind.ID, TestBlocks.TEST_SETUP_MARKER.get());
            SetupKind.enabledAtSetup = true;
            TownKit.LOG.info("[TownKit] test kind {} registered and enabled at mod setup", SetupKind.ID);
        });
    }

    /** Whether this is a perf run ({@code -Dtownkit.perf=true}, see TownKitPerfTest). */
    static boolean perfRun() {
        return Boolean.getBoolean("townkit.perf");
    }

    /**
     * {@code townkit:test_setup_kind}: two of its stops 3 apart along x or z, on one level, link both ways at cost 3; a
     * ride puts the rider on the far tile on its first tick. Its marker is {@link TestBlocks#TEST_SETUP_MARKER}, which
     * only {@code kindEnabledAtSetupWorks} places, so no other test village gets a stop of this kind.
     */
    static final class SetupKind implements TransportKind {
        static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_setup_kind");
        /** Rides this kind has made (every test village; only one test uses its marker). */
        static final AtomicInteger RIDES = new AtomicInteger();
        static volatile boolean enabledAtSetup;

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public List<TransportLink> links(ServerLevel level, List<TransportStop> stops) {
            List<TransportLink> out = new ArrayList<>();
            for (int i = 0; i < stops.size(); i++) {
                for (int j = i + 1; j < stops.size(); j++) {
                    BlockPos a = stops.get(i).tile();
                    BlockPos b = stops.get(j).tile();
                    int dx = Math.abs(a.getX() - b.getX());
                    int dz = Math.abs(a.getZ() - b.getZ());
                    if (a.getY() == b.getY() && (dx == 3 && dz == 0 || dz == 3 && dx == 0)) out.add(new TransportLink(ID, a, b, 3, true));
                }
            }
            return out;
        }

        @Override
        public boolean available(ServerLevel level, TransportLink link) {
            return true;
        }

        @Override
        public Ride ride(RideContext ctx) {
            RIDES.incrementAndGet();
            return c -> {
                c.placeAt(c.link().to());
                return RideStatus.DONE;
            };
        }
    }
}
