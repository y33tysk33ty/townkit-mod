package townkit.core.client;

import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.core.TownKit;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.net.WorkListsAppliedPayload;
import townkit.core.net.WorkListsPayload;
import townkit.core.net.WorkWindowPayload;
import townkit.core.work.WorkScreens;
import townkit.core.work.cook.CookScreenRows;
import townkit.core.work.mine.MinerScreenRows;

/**
 * Client side of the players' settings screens. The server sends the cook's dish rows and the miner's ore rows
 * ({@link WorkListsPayload}); this builds each page from Towntopia's own schema and those rows ({@link CookScreenRows},
 * {@link MinerScreenRows}, the same way the server does), checks that its settings hook really answers them, and tells
 * the server whether it does
 * ({@link WorkListsAppliedPayload}). It can't when the payload is unusable, the page can't be built, or the hook is
 * missing or not woven (hooks switched off on this install): then it answers {@code false} and keeps Towntopia's lists,
 * and the server writes this player's windows with Towntopia's schema.
 *
 * <p>Right before a settings window opens the server says which page it wrote the window with
 * ({@link WorkWindowPayload}); {@code SettingsSchemaRegistry#forProfession} after answers that page on the client's main
 * thread until the window's menu has been read ({@code GenericSettingsMenu}'s client constructor, after), else
 * Towntopia's. On any other thread (an integrated server) it leaves the answer alone. Client main thread only.
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class WorkScreensHooks {
    static final String FOR_PROFESSION = "village.villagers.settings.SettingsSchemaRegistry#forProfession(Profession)";
    static final String CLIENT_MENU = "ui.GenericSettingsMenu#<init>(int,Inventory,RegistryFriendlyByteBuf)";

    /** The cook's and the miner's pages built from the last lists, and their version; null: Towntopia's. */
    @Nullable
    private static volatile SettingsSchema latest;
    @Nullable
    private static volatile SettingsSchema latestMiner;
    private static volatile int latestVersion;
    /** The pages the window being opened was written with; null: Towntopia's. */
    @Nullable
    private static volatile SettingsSchema window;
    @Nullable
    private static volatile SettingsSchema windowMiner;
    private static final List<Hooks.Entry> ATTACHED = new ArrayList<>();
    private static boolean warned;

    private WorkScreensHooks() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // the client's main thread: settings read there are a player's screens
        event.enqueueWork(() -> WorkScreens.clientThread(Thread.currentThread()));
    }

    /** Takes the lists the server sent (replacing the last ones) and answers whether the screens now show them. */
    public static void accept(WorkListsPayload payload) {
        WorkScreens.clientThread(Thread.currentThread());
        window = null;
        windowMiner = null;
        SettingsSchema page = null;
        SettingsSchema minerPage = null;
        boolean built = payload.valid();
        if (!built) {
            warn("the server sent work lists a screen can't use");
        } else {
            try {
                if (payload.dishes().isPresent()) page = CookScreenRows.schema(CookSettingsSchema.SCHEMA, payload.dishes().get());
            } catch (RuntimeException e) {
                warn("can't build the cook's settings page from the server's rows: " + e);
                built = false;
            }
            try {
                if (payload.ores().isPresent()) minerPage = MinerScreenRows.schema(MinerSettingsSchema.SCHEMA, payload.ores().get());
            } catch (RuntimeException e) {
                warn("can't build the miner's settings page from the server's rows: " + e);
                built = false;
            }
        }
        // nothing to show (Towntopia's lists): applied; rows that couldn't be built or answered: not
        boolean applied = built && (page == null && minerPage == null ? payload.dishes().isEmpty() && payload.ores().isEmpty()
            : attach() && answers(page, minerPage));
        if ((page != null || minerPage != null) && !applied) {
            if (built) warn("the settings hook doesn't answer here (hooks switched off on this install?)");
            page = null;
            minerPage = null;
        }
        if (page == null && minerPage == null) detach();
        latest = page;
        latestMiner = minerPage;
        latestVersion = payload.version();
        answer(new WorkListsAppliedPayload(payload.version(), applied));
    }

    /** The server is about to open a settings window written with the page of lists {@code version} (0: Towntopia's). */
    public static void window(WorkWindowPayload payload) {
        boolean current = payload.version() != 0 && payload.version() == latestVersion;
        window = current ? latest : null;
        windowMiner = current ? latestMiner : null;
    }

    /** Whether the settings registry, asked on this thread, really answers these pages (the hook is woven and live). */
    private static boolean answers(@Nullable SettingsSchema page, @Nullable SettingsSchema minerPage) {
        window = page;
        windowMiner = minerPage;
        try {
            if (page != null && SettingsSchemaRegistry.forProfession(Profession.COOK).orElse(null) != page) return false;
            return minerPage == null || SettingsSchemaRegistry.forProfession(Profession.MINER).orElse(null) == minerPage;
        } catch (RuntimeException e) {
            return false;
        } finally {
            window = null;
            windowMiner = null;
        }
    }

    private static void answer(WorkListsAppliedPayload payload) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        // optional: a server without this payload never asked (it sent no lists either)
        if (connection != null && connection.hasChannel(WorkListsAppliedPayload.TYPE)) PacketDistributor.sendToServer(payload);
    }

    /** The cook's page this client shows (null: Towntopia's own). */
    @Nullable
    public static SettingsSchema cookPage() {
        return latest;
    }

    /** The miner's page this client shows (null: Towntopia's own). */
    @Nullable
    public static SettingsSchema minerPage() {
        return latestMiner;
    }

    /** The miner's "wanted ores" entry ids this client shows. */
    public static List<String> minerEntryIds() {
        SettingsSchema page = latestMiner;
        return (page != null ? page : MinerSettingsSchema.SCHEMA).findRow(MinerSettingsSchema.WANTED_ORES)
            .map(SettingsSchema.Row::entryIds).orElse(List.of());
    }

    /** The cook's "allowed recipes" entry ids this client shows. */
    public static List<String> cookEntryIds() {
        SettingsSchema page = latest;
        return (page != null ? page : CookSettingsSchema.SCHEMA).findRow(CookSettingsSchema.ALLOWED_RECIPES)
            .map(SettingsSchema.Row::entryIds).orElse(List.of());
    }

    public static boolean attached() {
        return !ATTACHED.isEmpty();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        latest = null;
        latestMiner = null;
        latestVersion = 0;
        window = null;
        windowMiner = null;
        detach();
    }

    private static void warn(String why) {
        if (warned) return;
        warned = true;
        TownKit.LOG.warn("[TownKit] {}; the settings screens show Towntopia's lists", why);
    }

    /** Attaches both hooks (if not yet); false when one can't be found. */
    private static boolean attach() {
        if (!ATTACHED.isEmpty()) return true;
        if (!add(FOR_PROFESSION, true, WorkScreensHooks::afterForProfession) || !add(CLIENT_MENU, true, call -> {
            window = null;
            windowMiner = null;
        })) {
            detach();
            return false;
        }
        return true;
    }

    private static boolean add(String key, boolean after, HookListener listener) {
        HookInfo h = HookManifest.get().byKey.get(key);
        if (h == null) {
            warn("can't find " + key);
            return false;
        }
        ATTACHED.add(Hooks.add(h, TownKit.MODID, listener, after));
        return true;
    }

    private static void detach() {
        ATTACHED.forEach(Hooks::remove);
        ATTACHED.clear();
    }

    private static void afterForProfession(HookCall call) {
        if (!WorkScreens.onClientThread()) return;
        if (call.returnValue() instanceof Optional<?> o && o.isPresent() && o.get() instanceof SettingsSchema s) {
            SettingsSchema page = s.profession() == Profession.COOK ? window : s.profession() == Profession.MINER ? windowMiner : null;
            if (page != null) call.setReturnValue(Optional.of(page));
        }
    }
}
