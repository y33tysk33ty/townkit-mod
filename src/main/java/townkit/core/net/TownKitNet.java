package townkit.core.net;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import townkit.core.TownKit;
import townkit.core.client.ClientLooks;
import townkit.core.client.ShopTabHooks;
import townkit.core.client.WorkScreensHooks;
import townkit.core.work.WorkScreens;

/**
 * TownKit's network payloads. Every payload is optional: a client without TownKit can join a server with it (it
 * simply never gets TownKit's payloads, see {@link townkit.core.look.LookSync}), and a TownKit client can join a
 * server without it.
 */
// no bus = ...: FML puts each listener on the mod bus or the game bus by its event type (IModBusEvent -> mod bus)
@EventBusSubscriber(modid = TownKit.MODID)
public final class TownKitNet {
    /** Protocol version of TownKit's payloads: a client and server with different versions don't exchange them. */
    public static final String PROTOCOL = "1";

    private TownKitNet() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL).optional();
        // the handler runs on the client's main thread (the default); only a client ever receives this payload, so the
        // client-only ClientLooks is loaded there and never on a dedicated server (the lambda body is what names it)
        registrar.playToClient(VillagerLookPayload.TYPE, VillagerLookPayload.STREAM_CODEC, (payload, context) -> ClientLooks.accept(payload));
        // likewise: ShopTabHooks is client-only and loaded only by this handler's body
        registrar.playToClient(ShopExtrasPayload.TYPE, ShopExtrasPayload.STREAM_CODEC, (payload, context) -> ShopTabHooks.accept(payload));
        // likewise: WorkScreensHooks is client-only and loaded only by this handler's body
        registrar.playToClient(WorkListsPayload.TYPE, WorkListsPayload.STREAM_CODEC, (payload, context) -> WorkScreensHooks.accept(payload));
        registrar.playToClient(WorkWindowPayload.TYPE, WorkWindowPayload.STREAM_CODEC, (payload, context) -> WorkScreensHooks.window(payload));
        // the client's answer to the work lists: until a true answer for the lists sent last, the player's settings
        // windows are written with Towntopia's own rows (WorkScreens)
        registrar.playToServer(WorkListsAppliedPayload.TYPE, WorkListsAppliedPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) WorkScreens.onApplied(player, payload);
        });
    }
}
