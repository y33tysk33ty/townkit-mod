package townkit.core.shop;

import com.alex.test.ModItems;
import com.alex.test.town.MayorShop;
import com.alex.test.town.TradeOfferEntry;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.guard.CaptainAssignmentHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import townkit.api.event.TownKitEvents;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.api.shop.ShopOffer;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.event.DirectEvents;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.net.ShopExtrasPayload;
import townkit.core.village.VillageHandle;

/**
 * Hooks on Towntopia's shop, attached while an addon registered a shop change or listens to a shop event, removed when
 * none does (so a world without shop addons runs Towntopia's shop untouched, at no cost):
 * <ul>
 *   <li>{@link #OFFERS} after: Towntopia's list becomes TownKit's rows (only with shop changes registered); the buyer's
 *   client gets the {@link ShopExtrasPayload} for it.</li>
 *   <li>{@link #BUY} before: TownKit sells addon rows and the Towntopia rows an addon changed (price, condition,
 *   currency, hidden); Towntopia's untouched rows go on to Towntopia's own purchase, with {@code SHOP_BUYING} fired
 *   first and {@code SHOP_BOUGHT} (from {@link #BUY} after) once Towntopia sold.</li>
 * </ul>
 * Server thread.
 */
public final class ShopHooks {
    static final String OFFERS = "town.MayorShop#offers(VillageManager,int,Player)";
    static final String BUY = "town.MayorShop#buy(Player,VillageManager,String)";

    private static final List<Hooks.Entry> ATTACHED = new ArrayList<>();
    /** The refusal when a purchase fails inside TownKit (fail closed). */
    static final String CANT_SELL = "The Mayor can't sell that right now.";
    private static final java.util.concurrent.atomic.AtomicLong BUY_FAILURES = new java.util.concurrent.atomic.AtomicLong();

    /** A purchase of an untouched Towntopia row in progress (between BUY before and after). */
    private record Pending(ServerPlayer buyer, VillageHandle village, ShopOffer offer) {
    }

    @Nullable
    private static Pending pending;

    /** The shop events attach the hooks too: Towntopia's own purchases fire them. */
    public static final DirectEvents.Lifecycle EVENTS = new DirectEvents.Lifecycle() {
        @Override
        public boolean available() {
            return !RewrittenSites.GLOBAL.hooksOff() && HookManifest.get().byKey.containsKey(OFFERS) && HookManifest.get().byKey.containsKey(BUY);
        }

        @Override
        public void attach() {
            refresh();
        }

        @Override
        public void detach() {
            refresh();
        }
    };

    private ShopHooks() {
    }

    /** Attaches the hooks when anything needs them, detaches them when nothing does. */
    static synchronized void refresh() {
        boolean wanted = !CoreShop.TABLE.isEmpty() || DirectEvents.has(TownKitEvents.SHOP_BUYING) || DirectEvents.has(TownKitEvents.SHOP_BOUGHT);
        if (wanted && ATTACHED.isEmpty()) {
            add(OFFERS, true, ShopHooks::afterOffers);
            add(BUY, false, ShopHooks::beforeBuy);
            add(BUY, true, ShopHooks::afterBuy);
        } else if (!wanted && !ATTACHED.isEmpty()) {
            ATTACHED.forEach(Hooks::remove);
            ATTACHED.clear();
            pending = null;
        }
        // no rule left: clients still holding extras would draw (and block) rows that changed back to Towntopia's
        if (CoreShop.TABLE.isEmpty()) clearHolders();
    }

    // --- extras ----------------------------------------------------------------------------------------------------

    /** Who can get the extras payload and how it gets there. Swappable for tests only. */
    public interface Channel {
        boolean reaches(ServerPlayer player);

        void send(ServerPlayer to, ShopExtrasPayload payload);
    }

    private static final Channel NETWORK = new Channel() {
        @Override
        public boolean reaches(ServerPlayer player) {
            if (player instanceof FakePlayer) return false;
            try {
                return player.connection.hasChannel(ShopExtrasPayload.TYPE);
            } catch (RuntimeException e) { // a test player's stand-in connection
                return false;
            }
        }

        @Override
        public void send(ServerPlayer to, ShopExtrasPayload payload) {
            PacketDistributor.sendToPlayer(to, payload);
        }
    };

    private static volatile Channel channel = NETWORK;
    /** Players whose client got a non-empty extras payload last, by id (they get an empty one once no rule is left). */
    private static final Map<UUID, ServerPlayer> HOLDERS = new ConcurrentHashMap<>();

    /** Tests only: swaps the channel and returns the old one. */
    public static Channel swapChannelForTests(Channel replacement) {
        Channel old = channel;
        channel = replacement;
        return old;
    }

    /** Sends an empty extras payload to every player still holding extras, on the server thread. */
    private static void clearHolders() {
        if (HOLDERS.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            HOLDERS.clear();
            return;
        }
        if (!server.isSameThread()) {
            server.execute(ShopHooks::clearHolders);
            return;
        }
        if (!CoreShop.TABLE.isEmpty()) return; // a rule came back meanwhile: its next list replaces the extras
        for (ServerPlayer p : List.copyOf(HOLDERS.values())) {
            HOLDERS.remove(p.getUUID());
            if (!p.hasDisconnected()) channel.send(p, new ShopExtrasPayload(List.of()));
        }
    }

    /** The hook keys TownKit's shop has attached right now (for tests). */
    public static synchronized List<String> attachedKeys() {
        return ATTACHED.stream().map(e -> e.hook.key()).toList();
    }

    private static void add(String key, boolean after, HookListener listener) {
        HookInfo h = HookManifest.get().byKey.get(key);
        if (h == null) {
            TownKit.LOG.warn("[TownKit] can't find {}: the Mayor's shop stays Towntopia's own", key);
            return;
        }
        ATTACHED.add(Hooks.add(h, TownKit.MODID, listener, after));
    }

    // --- offers ----------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void afterOffers(HookCall call) {
        if (CoreShop.computingRaw()) return;
        Player buyer = (Player) call.args()[2];
        if (CoreShop.TABLE.isEmpty()) {
            // hooks kept for the shop events only: a client still holding extras from an earlier rule drops them
            if (buyer instanceof ServerPlayer sp && HOLDERS.remove(sp.getUUID()) != null) channel.send(sp, new ShopExtrasPayload(List.of()));
            return;
        }
        if (!(call.args()[0] instanceof VillageManager mgr) || mgr.getLevel() == null) return;
        List<TradeOfferEntry> towntopia = (List<TradeOfferEntry>) call.returnValue();
        if (towntopia == null) return;
        List<ShopTable.Row<Item>> rows = CoreShop.rows(mgr, buyer, towntopia);
        call.setReturnValue(CoreShop.entries(rows, towntopia));
        if (buyer instanceof ServerPlayer sp) sendExtras(sp, rows);
    }

    private static void sendExtras(ServerPlayer to, List<ShopTable.Row<Item>> rows) {
        if (!channel.reaches(to)) return;
        List<ShopExtrasPayload.Extra> extras = new ArrayList<>();
        for (ShopTable.Row<Item> r : rows) {
            if (!r.changed()) continue;
            Optional<Component> description = r.builtIn() ? Optional.empty() : Optional.of(r.description() != null ? r.description() : r.name());
            extras.add(new ShopExtrasPayload.Extra(r.id().toString(), BuiltInRegistries.ITEM.getKey(r.item()),
                BuiltInRegistries.ITEM.getKey(r.currency()), r.boundCurrency(), r.available(), Optional.ofNullable(r.reason()), description,
                r.builtIn() ? Optional.empty() : Optional.of(r.name())));
        }
        if (extras.isEmpty()) {
            if (HOLDERS.remove(to.getUUID()) == null) return; // nothing to replace on that client
        } else {
            HOLDERS.put(to.getUUID(), to);
        }
        channel.send(to, new ShopExtrasPayload(extras));
    }

    // --- buying ----------------------------------------------------------------------------------------------------

    /**
     * Fails closed: while any shop rule is registered, a TownKit failure here refuses the sale instead of letting
     * Towntopia's own {@code buy} run (which would sell a row an addon hid, greyed, repriced or switched to a coin at
     * Towntopia's emerald price), and never counts against the hook (which would drop every addon's rules).
     */
    private static void beforeBuy(HookCall call) {
        pending = null;
        try {
            decideBuy(call);
        } catch (Throwable t) {
            pending = null;
            long n = BUY_FAILURES.incrementAndGet();
            if (n <= 3 || n % 1000 == 0) TownKit.LOG.error("[TownKit] shop: a purchase failed inside TownKit ({} times so far)", n, t);
            if (!CoreShop.TABLE.isEmpty()) call.cancelWith(refuse(CANT_SELL, 0, 0));
        }
    }

    private static void decideBuy(HookCall call) {
        if (!(call.args()[0] instanceof ServerPlayer buyer) || !(call.args()[1] instanceof VillageManager mgr)
            || !(call.args()[2] instanceof String itemId)) {
            return;
        }
        // Towntopia's own first refusal ("no active Town Hall") stays Towntopia's, for every row
        if (mgr.getLevel() != buyer.level() || !hasActiveTownHall(mgr)) return;
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        VillageHandle village = VillageHandle.of(mgr);
        if (id == null || village == null) return;
        List<ShopTable.Row<Item>> rows = CoreShop.rows(mgr, buyer, CoreShop.raw(mgr, buyer));
        ShopTable.Row<Item> row = null;
        for (ShopTable.Row<Item> r : rows) {
            if (r.id().equals(id)) row = r;
        }
        boolean addon = CoreShop.TABLE.addon(id) != null;
        Item builtIn = addon ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        boolean townkitSells = addon || (builtIn != null && isCatalogued(builtIn) && CoreShop.TABLE.changes(builtIn));
        if (townkitSells) {
            call.cancelWith(sell(buyer, mgr, village, row));
            return;
        }
        // Towntopia sells it; TownKit only fires the events around it
        if (row == null || !row.builtIn()) return;
        ShopOffer offer = CoreShop.offer(row);
        if (DirectEvents.has(TownKitEvents.SHOP_BUYING) && MayorShop.villageEmeralds(buyer.getInventory(), mgr) >= row.price()) {
            TownKitEvents.ShopBuying event = new TownKitEvents.ShopBuying(village, buyer, offer);
            DirectEvents.fire(TownKitEvents.SHOP_BUYING, event);
            if (event.cancelled()) {
                call.cancelWith(new MayorShop.Purchase(false, event.reason().orElseThrow().getString(), 0,
                    MayorShop.villageEmeralds(buyer.getInventory(), mgr)));
                return;
            }
        }
        if (DirectEvents.has(TownKitEvents.SHOP_BOUGHT)) pending = new Pending(buyer, village, offer);
    }

    private static void afterBuy(HookCall call) {
        Pending p = pending;
        pending = null;
        if (p == null || !(call.returnValue() instanceof MayorShop.Purchase purchase) || !purchase.success()) return;
        ItemStack given = new ItemStack(p.offer().item());
        VillageManager mgr = p.village().manager();
        if (mgr != null) VillageTokenBinding.bind(given, mgr);
        DirectEvents.fire(TownKitEvents.SHOP_BOUGHT, new TownKitEvents.ShopBought(p.village(), p.buyer(), p.offer(), given));
    }

    /** TownKit's own purchase of a row it sells; mirrors Towntopia's {@code MayorShop.buy} with the row's price and currency. */
    private static MayorShop.Purchase sell(ServerPlayer buyer, VillageManager mgr, VillageHandle village, @Nullable ShopTable.Row<Item> row) {
        Inventory inv = buyer.getInventory();
        if (row == null) return refuse("The Mayor does not sell that.", 0, MayorShop.villageEmeralds(inv, mgr));
        Predicate<ItemStack> payable = stack -> stack.is(row.currency()) && (!row.boundCurrency() || VillageTokenBinding.matches(stack, mgr));
        int held = MayorShop.countMatching(inv, payable);
        if (!row.available()) {
            return refuse(row.reason() == null ? ShopTable.UNAVAILABLE.getString() : row.reason().getString(), row.price(), held);
        }
        boolean captain = row.builtIn() && row.item() == ModItems.CAPTAIN_TOKEN.get();
        if (captain && !CaptainAssignmentHandler.hasOpenBarracks(mgr, null)) {
            return refuse(CaptainAssignmentHandler.barracksCount(mgr) == 0
                ? "A captain needs a Barracks. Buying a Barracks comes with its captain." : "Every Barracks already has its captain.", 0, held);
        }
        if (captain && MayorShop.countMatching(inv, s -> s.is(ModItems.CAPTAIN_TOKEN.get()) && VillageTokenBinding.matches(s, mgr))
            >= CaptainAssignmentHandler.openCaptaincies(mgr)) {
            return refuse("You already carry a Captain token for every Barracks without a captain.", 0, held);
        }
        String coin = new ItemStack(row.currency()).getHoverName().getString();
        String name = row.name().getString();
        if (held < row.price()) return notEnough(inv, row, payable, held);
        ShopOffer offer = CoreShop.offer(row);
        if (DirectEvents.has(TownKitEvents.SHOP_BUYING)) {
            TownKitEvents.ShopBuying event = new TownKitEvents.ShopBuying(village, buyer, offer);
            DirectEvents.fire(TownKitEvents.SHOP_BUYING, event);
            if (event.cancelled()) return refuse(event.reason().orElseThrow().getString(), row.price(), held);
            // a handler may have moved or spent the buyer's money: count it again
            held = MayorShop.countMatching(inv, payable);
            if (held < row.price()) return notEnough(inv, row, payable, held);
        }
        // everything handed over is made before any money moves, so a failure here takes nothing
        ItemStack bought = new ItemStack(row.item());
        if (row.bindSold()) VillageTokenBinding.bind(bought, mgr);
        ItemStack given = bought.copy();
        boolean withCaptain = row.builtIn() && row.item() == ModItems.BARRACKS.get();
        ItemStack token = null;
        if (withCaptain) {
            token = new ItemStack(ModItems.CAPTAIN_TOKEN.get());
            VillageTokenBinding.bind(token, mgr);
        }
        List<ItemStack> paid = take(inv, payable, row.price());
        int taken = 0;
        for (ItemStack s : paid) taken += s.getCount();
        if (taken < row.price()) {
            // never an underpaid sale: what was taken goes back
            for (ItemStack s : paid) give(buyer, s);
            return notEnough(inv, row, payable, MayorShop.countMatching(inv, payable));
        }
        try {
            give(buyer, bought);
            if (token != null) give(buyer, token);
            if (!row.builtIn()) ShopCounts.of(buyer.getServer()).increment(mgr.getVillageId(), row.id());
        } catch (RuntimeException | Error e) {
            // never paid-but-no-item: the whole payment goes back, then the sale fails closed (beforeBuy refuses it)
            for (ItemStack s : paid) give(buyer, s);
            throw e;
        }
        if (DirectEvents.has(TownKitEvents.SHOP_BOUGHT)) {
            DirectEvents.fire(TownKitEvents.SHOP_BOUGHT, new TownKitEvents.ShopBought(village, buyer, offer, given));
        }
        return new MayorShop.Purchase(true, "Bought " + name + " for " + row.price() + " " + coin
            + (withCaptain ? ", with a Captain token to lead it." : "."), row.price(), held - row.price());
    }

    private static MayorShop.Purchase notEnough(Inventory inv, ShopTable.Row<Item> row, Predicate<ItemStack> payable, int held) {
        String coin = new ItemStack(row.currency()).getHoverName().getString();
        boolean other = row.boundCurrency() && MayorShop.countMatching(inv, s -> s.is(row.currency())) > held;
        return refuse("Not enough " + coin + (row.boundCurrency() ? " from this village: " : ": ") + row.name().getString() + " costs "
            + row.price() + ", you have " + held + "." + (other ? " The Mayor only takes " + coin + " with this village's name." : ""),
            row.price(), held);
    }

    private static MayorShop.Purchase refuse(String message, int price, int held) {
        return new MayorShop.Purchase(false, message, price, held);
    }

    private static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** Takes up to {@code amount} matching items; returns what it took (fewer than {@code amount} if the buyer is short). */
    private static List<ItemStack> take(Inventory inv, Predicate<ItemStack> test, int amount) {
        List<ItemStack> taken = new ArrayList<>();
        int remaining = amount;
        for (int slot = 0; slot < inv.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && test.test(stack)) {
                int n = Math.min(remaining, stack.getCount());
                taken.add(stack.split(n));
                if (stack.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
                remaining -= n;
            }
        }
        return taken;
    }

    private static boolean hasActiveTownHall(VillageManager mgr) {
        for (Building b : mgr.getBuildings()) {
            if (b instanceof TownHallBuilding && b.isValid()) return true;
        }
        return false;
    }

    private static boolean isCatalogued(Item item) {
        java.util.Map<Item, Integer> c = CoreShop.catalogue();
        return c == null || c.containsKey(item);
    }
}
