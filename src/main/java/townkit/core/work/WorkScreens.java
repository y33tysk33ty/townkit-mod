package townkit.core.work;

import com.alex.test.entity.NpcEntity;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.VisibleForTesting;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.core.FieldReader;
import townkit.core.TownKit;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.net.WorkListsAppliedPayload;
import townkit.core.net.WorkListsPayload;
import townkit.core.net.WorkWindowPayload;
import townkit.core.work.cook.CookOverlay;
import townkit.core.work.cook.CookScreenRows;
import townkit.core.work.mine.MinerScreenRows;
import townkit.core.work.mine.MiningOverlay;

/**
 * Server side of the players' settings screens for the work TownKit changed (the cook's dishes, the miner's ores).
 *
 * <p>Towntopia's settings window is positional: the server writes one value per row of its schema
 * ({@code GenericSettingsMenu#writeOpeningData}) and the client reads them back by the schema its own game has. So each
 * player's window is written with the page that player's client builds:
 * <ul>
 *   <li>a client with TownKit gets a {@link WorkListsPayload} (on login, after a {@code /reload}, when an addon's
 *   registrations change), builds the page from Towntopia's schema and those rows ({@link CookScreenRows}) and answers
 *   whether its screens now show it ({@link WorkListsAppliedPayload}). Only after a {@code true} answer for the lists
 *   sent last is its window written with that page; until then (and after a {@code false} answer, or one for older
 *   lists) with Towntopia's schema. Right before each window it gets a {@link WorkWindowPayload} naming the page the
 *   window was written with, and reads the window with exactly that page. The lists carry the cook's dish rows and the
 *   miner's ore rows ({@link MinerScreenRows}); each profession's window gets its own page;</li>
 *   <li>a client without TownKit (or a player not sent a payload) builds Towntopia's own page; its window is written
 *   with Towntopia's schema, so it reads exactly what it expects and never sees an addon's row.</li>
 * </ul>
 * Saving: Towntopia saves one switch at a time ({@code ProfessionSettings#setAllowed}). A change to a switch that no
 * player with this villager's window open could see is refused ({@link SettingsMerge}): a player can't change what
 * their screen doesn't show.
 *
 * <p>The window hooks are attached while the cook's or the miner's list is changed (the only time a page differs from
 * Towntopia's).
 * Server thread only, except {@link #clientThread}/{@link #onClientThread}.
 */
@EventBusSubscriber(modid = TownKit.MODID)
public final class WorkScreens {
    static final String MENU_INIT = "ui.GenericSettingsMenu#<init>(int,Inventory,NpcEntity)";
    static final String WRITE_OPENING = "ui.GenericSettingsMenu#writeOpeningData(RegistryFriendlyByteBuf,NpcEntity)";
    static final String SET_ALLOWED = "village.villagers.settings.ProfessionSettings#setAllowed(String,String,boolean)";

    /** Who can get the payload and how it gets there. Swappable for tests only. */
    public interface Channel {
        boolean reaches(ServerPlayer player);

        void send(ServerPlayer to, CustomPacketPayload payload);
    }

    private static final Channel NETWORK = new Channel() {
        @Override
        public boolean reaches(ServerPlayer player) {
            if (player instanceof FakePlayer) return false;
            try {
                // the payload is optional: a client without TownKit never negotiated it
                return player.connection.hasChannel(WorkListsPayload.TYPE);
            } catch (RuntimeException e) { // a test player's stand-in connection
                return false;
            }
        }

        @Override
        public void send(ServerPlayer to, CustomPacketPayload payload) {
            PacketDistributor.sendToPlayer(to, payload);
        }
    };

    private static volatile Channel channel = NETWORK;

    /**
     * Lists as sent: their payload and the pages they build ({@code page}: the cook's, {@code minerPage}: the miner's;
     * null: Towntopia's own).
     */
    public record Prepared(WorkListsPayload payload, @Nullable SettingsSchema page, @Nullable SettingsSchema minerPage) {
    }

    /** One player's lists: the version sent and the pages it builds (null: Towntopia's own). */
    private record Sent(int version, @Nullable SettingsSchema page, @Nullable SettingsSchema minerPage) {
    }

    private static final AtomicInteger VERSION = new AtomicInteger();
    /** Lists sent with dish or ore rows and not answered yet, by player. */
    private static final Map<UUID, Sent> PENDING = new ConcurrentHashMap<>();
    /** Lists the player's client answered it shows (the last ones sent): their windows are written with that page. */
    private static final Map<UUID, Sent> APPLIED = new ConcurrentHashMap<>();

    /** The settings window a player last had written: whose, and with which page. */
    private record Viewing(ServerPlayer player, int villagerId, SettingsSchema page) {
    }

    private static final Map<UUID, Viewing> VIEWING = new ConcurrentHashMap<>();

    /** A window being opened on this thread: Minecraft makes the menu, then writes its opening data. */
    private record Opening(NpcEntity villager, ServerPlayer player) {
    }

    private static final ThreadLocal<Opening> OPENING = new ThreadLocal<>();
    /** The page for the opening data being written on this thread; read once (by the settings registry's hook). */
    private static final ThreadLocal<SettingsSchema> WRITING = new ThreadLocal<>();
    private static final Map<String, Hooks.Entry> ATTACHED = new LinkedHashMap<>();
    @Nullable
    private static volatile Thread clientThread;
    @Nullable
    private static FieldReader<NpcEntity> villagerField;

    private WorkScreens() {
    }

    // --- the client's thread ------------------------------------------------------------------------------------------

    /** Set by the client (its main thread): settings read there are the client's screens, answered from the payload. */
    public static void clientThread(Thread thread) {
        clientThread = thread;
    }

    /** Whether this is the client's main thread (a player's screen); never on a dedicated server. */
    public static boolean onClientThread() {
        Thread t = clientThread;
        return t != null && Thread.currentThread() == t;
    }

    // --- sending ------------------------------------------------------------------------------------------------------

    /**
     * What players' screens show now: the cook's and the miner's pages as the settings registry answers them on the
     * server (Towntopia's own objects when nothing changed them: no rows), as rows, with a new version. A page too big for
     * the payload's caps, or one that can't be built, is not sent (logged once): screens keep Towntopia's list for it.
     */
    public static Prepared prepare() {
        int version = VERSION.incrementAndGet();
        Optional<List<WorkListsPayload.Row>> dishes = Optional.empty();
        Optional<List<WorkListsPayload.Row>> ores = Optional.empty();
        SettingsSchema cookPage = null;
        SettingsSchema minerPage = null;
        try {
            SettingsSchema page = SettingsSchemaRegistry.forProfession(Profession.COOK).orElse(CookSettingsSchema.SCHEMA);
            if (page != CookSettingsSchema.SCHEMA) {
                List<WorkListsPayload.Row> rows = CookScreenRows.rows(page);
                if (new WorkListsPayload(version, Optional.of(rows), Optional.empty()).valid()) {
                    dishes = Optional.of(rows);
                    cookPage = CookScreenRows.schema(CookSettingsSchema.SCHEMA, rows);
                } else {
                    WorkLog.GLOBAL.once("work-lists-too-big", "[" + CookOverlay.KIND + "] the cook's dish list is too long for players' "
                        + "screens (at most " + WorkListsPayload.MAX_ROWS + " rows, texts up to " + WorkListsPayload.MAX_TEXT
                        + " characters): their settings windows keep Towntopia's list (villagers still use the merged one)");
                }
            }
        } catch (RuntimeException e) {
            WorkLog.GLOBAL.once("work-lists-failed", "[" + CookOverlay.KIND + "] can't build the players' screens' lists (" + e
                + "): their settings windows keep Towntopia's list");
        }
        try {
            SettingsSchema page = SettingsSchemaRegistry.forProfession(Profession.MINER).orElse(MinerSettingsSchema.SCHEMA);
            if (page != MinerSettingsSchema.SCHEMA) {
                List<WorkListsPayload.Row> rows = MinerScreenRows.rows(page);
                if (new WorkListsPayload(version, Optional.empty(), Optional.of(rows)).valid()) {
                    ores = Optional.of(rows);
                    minerPage = MinerScreenRows.schema(MinerSettingsSchema.SCHEMA, rows);
                } else {
                    WorkLog.GLOBAL.once("work-ores-too-big", "[" + MiningOverlay.KIND + "] the miner's ore list is too long for players' "
                        + "screens (at most " + WorkListsPayload.MAX_ROWS + " rows, texts up to " + WorkListsPayload.MAX_TEXT
                        + " characters): their settings windows keep Towntopia's list (villagers still use the merged one)");
                }
            }
        } catch (RuntimeException e) {
            WorkLog.GLOBAL.once("work-ores-failed", "[" + MiningOverlay.KIND + "] can't build the players' screens' ore list (" + e
                + "): their settings windows keep Towntopia's list");
        }
        return new Prepared(new WorkListsPayload(version, dishes, ores), cookPage, minerPage);
    }

    /**
     * Sends {@code lists} to {@code player} if their client has TownKit. Until the client answers that it shows them,
     * the player's windows are written with Towntopia's schema.
     */
    public static void send(ServerPlayer player, Prepared lists) {
        UUID id = player.getUUID();
        APPLIED.remove(id);
        PENDING.remove(id);
        if (!channel.reaches(player)) return;
        if (lists.page() != null || lists.minerPage() != null) {
            PENDING.put(id, new Sent(lists.payload().version(), lists.page(), lists.minerPage()));
        }
        channel.send(player, lists.payload());
    }

    /** The client's answer: its screens show the lists of that version, or Towntopia's. Answers for other lists are ignored. */
    public static void onApplied(ServerPlayer player, WorkListsAppliedPayload answer) {
        UUID id = player.getUUID();
        Sent sent = PENDING.get(id);
        if (sent == null || sent.version() != answer.version()) return; // not the lists sent last: stale or unknown
        PENDING.remove(id);
        if (answer.applied()) {
            APPLIED.put(id, sent);
        } else {
            WorkLog.GLOBAL.once("not-applied|" + id, "[" + CookOverlay.KIND + "] " + player.getGameProfile().getName()
                + "'s client can't show TownKit's work lists (hooks off on that install?): their settings windows keep Towntopia's lists");
        }
    }

    /** Sends the current lists to every player on {@code server}. */
    public static void sendAll(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        Prepared p = prepare();
        for (ServerPlayer player : players) send(player, p);
    }

    /** After an addon's registration changed (any thread): the server sends the new lists on its thread. */
    public static void resend() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isRunning()) server.execute(() -> sendAll(server));
    }

    /** The cook page {@code player}'s windows are written with: the one their client answered it shows, else Towntopia's. */
    public static SettingsSchema cookPage(ServerPlayer player) {
        Sent s = APPLIED.get(player.getUUID());
        return s != null && s.page() != null ? s.page() : CookSettingsSchema.SCHEMA;
    }

    /** The miner page {@code player}'s windows are written with: the one their client answered it shows, else Towntopia's. */
    public static SettingsSchema minerPage(ServerPlayer player) {
        Sent s = APPLIED.get(player.getUUID());
        return s != null && s.minerPage() != null ? s.minerPage() : MinerSettingsSchema.SCHEMA;
    }

    /** The version of the lists {@code player}'s windows are written with (0: Towntopia's). */
    public static int windowVersion(ServerPlayer player) {
        Sent s = APPLIED.get(player.getUUID());
        return s != null ? s.version() : 0;
    }

    // login and /reload: after the work tables took the new files (CoreWork refreshes the cook's hooks at normal priority)
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Prepared p = prepare();
        event.getRelevantPlayers().forEach(player -> send(player, p));
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        APPLIED.clear();
        VIEWING.clear();
    }

    /** Tests only: swaps the channel and returns the old one. */
    @VisibleForTesting
    public static Channel swapChannelForTests(Channel replacement) {
        Channel old = channel;
        channel = Objects.requireNonNull(replacement, "replacement");
        return old;
    }

    /** Tests only: forgets a (fake) player's page and window. */
    @VisibleForTesting
    public static void forgetForTests(UUID player) {
        forget(player);
    }

    private static void forget(UUID player) {
        PENDING.remove(player);
        APPLIED.remove(player);
        VIEWING.remove(player);
    }

    // --- the settings window --------------------------------------------------------------------------------------------

    /** Attaches the window hooks while the cook's list is changed, detaches them otherwise. Called by the cook's refresh. */
    public static synchronized void refresh() {
        boolean wanted = CookOverlay.GLOBAL.touched() || MiningOverlay.GLOBAL.touched();
        set(MENU_INIT, true, wanted, WorkScreens::afterMenuInit);
        set(WRITE_OPENING, false, wanted, WorkScreens::beforeWriteOpening);
        set(WRITE_OPENING, true, wanted, call -> WRITING.remove());
        set(SET_ALLOWED, false, wanted, WorkScreens::beforeSetAllowed);
    }

    /** The hook keys attached right now (for tests). */
    public static synchronized List<String> attachedKeys() {
        return ATTACHED.values().stream().map(e -> e.hook.key()).toList();
    }

    private static void set(String key, boolean after, boolean wanted, HookListener listener) {
        String slot = key + "|" + after;
        Hooks.Entry e = ATTACHED.get(slot);
        if (wanted && e == null) {
            HookInfo h = HookManifest.get().byKey.get(key);
            if (h == null) {
                WorkLog.GLOBAL.once("screen-hook|" + key, "[" + CookOverlay.KIND + "] can't find the hook " + key
                    + "; players' settings windows may not match their screens");
                return;
            }
            ATTACHED.put(slot, Hooks.add(h, TownKit.MODID, listener, after));
        } else if (!wanted && e != null) {
            Hooks.remove(e);
            ATTACHED.remove(slot);
        }
    }

    /**
     * The page to answer, once, for the opening data being written on this thread (the settings registry's hook asks;
     * {@code writeOpeningData} asks the registry first thing), or null.
     */
    @Nullable
    public static SettingsSchema writing() {
        SettingsSchema s = WRITING.get();
        if (s != null) WRITING.remove();
        return s;
    }

    /** The server made a villager's settings menu for a player: its opening data is written next. */
    private static void afterMenuInit(HookCall call) {
        WRITING.remove();
        if (call.args()[1] instanceof Inventory inv && inv.player instanceof ServerPlayer p && call.args()[2] instanceof NpcEntity v) {
            OPENING.set(new Opening(v, p));
        } else {
            OPENING.remove();
        }
    }

    /** The opening data for the menu just made: written with the page that player's client builds. */
    private static void beforeWriteOpening(HookCall call) {
        Opening o = OPENING.get();
        OPENING.remove();
        if (o == null || o.villager() != call.args()[1]) return;
        Profession profession = o.villager().getProfession();
        if (!hasPage(profession)) return;
        SettingsSchema page = profession == Profession.COOK ? cookPage(o.player()) : minerPage(o.player());
        // ahead of the window on the same connection: the client reads the window with exactly this page
        if (channel.reaches(o.player())) channel.send(o.player(), new WorkWindowPayload(windowVersion(o.player())));
        WRITING.set(page);
        pruneViewing();
        VIEWING.put(o.player().getUUID(), new Viewing(o.player(), o.villager().getId(), page));
    }

    /**
     * Drops the windows that are closed (the player's menu is no longer that villager's settings) or whose player is
     * gone, so no player (a fake player never logs out) is held here after its window closed.
     */
    private static void pruneViewing() {
        VIEWING.values().removeIf(w -> w.player().isRemoved() || !(w.player().containerMenu instanceof GenericSettingsMenu m)
            || m.villagerId() != w.villagerId());
    }

    /** Tests only: whether a settings window of {@code player} is remembered. */
    @VisibleForTesting
    public static boolean viewingForTests(UUID player) {
        return VIEWING.containsKey(player);
    }

    /**
     * A switch is set on a villager whose settings window some player has open: refused when none of those players'
     * pages has that entry (it can only come from a client that doesn't show it). Not from a window (no player has
     * this villager's window open): Towntopia's own call, left alone.
     */
    private static void beforeSetAllowed(HookCall call) {
        if (VIEWING.isEmpty() || !(call.self() instanceof ProfessionSettings ps) || !hasPage(ps.schema().profession())
                || !(call.args()[0] instanceof String setting) || !(call.args()[1] instanceof String entry)
                || !(call.args()[2] instanceof Boolean allowed)) {
            return;
        }
        NpcEntity villager = villager(ps);
        if (villager == null) return;
        pruneViewing();
        Set<String> known = new HashSet<>();
        boolean viewed = false;
        for (Viewing w : VIEWING.values()) {
            if (w.villagerId() != villager.getId() || !(w.player().containerMenu instanceof GenericSettingsMenu m)
                    || m.villagerId() != villager.getId()) {
                continue;
            }
            viewed = true;
            w.page().findRow(setting).ifPresent(r -> known.addAll(r.entryIds()));
        }
        if (!viewed) return;
        Map<String, Boolean> out = SettingsMerge.merge(Map.of(entry, ps.isAllowed(setting, entry)), Map.of(entry, allowed), known);
        if (!allowed.equals(out.get(entry))) {
            WorkLog.GLOBAL.once("unseen-switch|" + entry, "[" + CookOverlay.KIND + "] a settings change for '" + entry
                + "' came from a window that doesn't show it (a player without TownKit?): ignored");
            call.cancel();
        }
    }

    /** Whether players' pages can differ from Towntopia's for this profession's settings. */
    private static boolean hasPage(Profession profession) {
        return profession == Profession.COOK || profession == Profession.MINER;
    }

    @Nullable
    private static NpcEntity villager(ProfessionSettings ps) {
        FieldReader<NpcEntity> f = villagerField;
        if (f == null) {
            f = FieldReader.of(ProfessionSettings.class, "villager", NpcEntity.class);
            villagerField = f;
        }
        return f.available() ? f.read(ps) : null;
    }
}
