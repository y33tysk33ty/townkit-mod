package townkit.test;

import com.alex.test.ModItems;
import com.alex.test.testkit.TestKit;
import com.alex.test.town.MayorShop;
import com.alex.test.town.TradeOfferEntry;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.testkit.ScenarioBuilder;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.event.TownKitEvents;
import townkit.api.shop.ShopAdjust;
import townkit.api.shop.ShopCheck;
import townkit.api.shop.ShopEntry;
import townkit.api.shop.ShopOffer;
import townkit.api.shop.TownKitShop;
import townkit.core.TownKit;
import townkit.core.hook.Hooks;
import townkit.core.net.ShopExtrasPayload;
import townkit.core.shop.ShopCounts;
import townkit.core.shop.ShopHooks;
import townkit.core.village.VillageHandle;
import townkit.test.BehaviourTests.TestVillage;

/**
 * Contract tests for {@link TownKitShop} and the shop events in a real Towntopia village. Shop registrations are global,
 * so every test has its own batch (batches run one after another) and removes what it registered; the tests share one
 * village. Purchases go through {@code MayorShop.buy}, which Towntopia's Buy packet handler calls.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class ShopTests {
    private static final String OWNER = "townkit-test-shop";
    private static final ResourceLocation BELL = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_bell");
    private static final ResourceLocation FARMER = ResourceLocation.parse("towntopia:farmer_token");
    private static final ResourceLocation GUARD_POST = ResourceLocation.parse("towntopia:guard_post");

    @Nullable
    private static TestVillage shared;

    private ShopTests() {
    }

    /** One test's body: what it registered is removed after it, pass or fail. */
    private interface Body {
        void run(Checks c, TestVillage tv, List<Subscription> subs, List<ServerPlayer> players);
    }

    private static void run(GameTestHelper helper, String name, Body body) {
        Checks c = new Checks(name);
        TestVillage tv = village(helper).orElse(null);
        if (tv == null) return;
        helper.runAfterDelay(20, () -> {
            List<Subscription> subs = new ArrayList<>();
            List<ServerPlayer> players = new ArrayList<>();
            try {
                body.run(c, tv, subs, players);
            } catch (RuntimeException e) {
                TownKit.LOG.error("[TownKit test] {} threw", name, e);
                c.check("no exception", false, e);
            } finally {
                subs.forEach(Subscription::remove);
                players.forEach(p -> p.getInventory().clearContent());
            }
            c.check("nothing of TownKit's shop left attached", ShopHooks.attachedKeys().isEmpty(), ShopHooks.attachedKeys());
            BehaviourTests.finish(helper, c);
        });
    }

    /** The shared test village (made by the first shop test that runs). */
    private static Optional<TestVillage> village(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (shared != null && VillageManagerRegistry.get(level.getServer()).get(shared.mgr().getVillageId()) == shared.mgr()) {
            TestKit.setHeadless(true);
            TestKit.setSimulatedPlayer(shared.door(), level.dimension());
            return Optional.of(shared);
        }
        Optional<TestVillage> v = BehaviourTests.village(helper, 14000, 8000);
        v.ifPresent(t -> shared = t);
        return v;
    }

    private static VillageHandle handle(TestVillage tv) {
        return VillageHandle.of(tv.mgr());
    }

    /** A player at the town hall door with an empty inventory holding {@code stacks}. */
    private static ServerPlayer buyer(GameTestHelper helper, TestVillage tv, List<ServerPlayer> players, ItemStack... stacks) {
        // a fake player: not in the player list, so nothing to clean up after the test
        ServerPlayer p = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "townkit-shop-test"));
        players.add(p);
        p.setPos(tv.door().getX() + 0.5, tv.door().getY(), tv.door().getZ() + 0.5);
        p.getInventory().clearContent();
        for (ItemStack s : stacks) p.getInventory().add(s);
        return p;
    }

    private static ItemStack bound(Item item, int count, VillageManager mgr) {
        ItemStack s = new ItemStack(item, count);
        VillageTokenBinding.bind(s, mgr);
        return s;
    }

    /** How many {@code item} the player holds; bound to {@code mgr} only, unless it is null. */
    private static int count(Player p, Item item, @Nullable VillageManager mgr) {
        return MayorShop.countMatching(p.getInventory(), s -> s.is(item) && (mgr == null || VillageTokenBinding.matches(s, mgr)));
    }

    @Nullable
    private static ShopOffer offer(List<ShopOffer> offers, ResourceLocation id) {
        return offers.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }

    private static Subscription addBell(List<Subscription> subs, ShopEntry entry) {
        Subscription s = TownKitShop.add(OWNER, entry);
        subs.add(s);
        return s;
    }

    private static ShopEntry.Builder bell(int price) {
        return ShopEntry.builder(BELL, () -> Items.BELL, price).name(Component.literal("Test Bell"));
    }

    // --- tests -----------------------------------------------------------------------------------------------------

    /** An addon entry is listed after Towntopia's rows, at Towntopia's grown price; its price grows with the village. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_listed")
    public static void addonEntryListed(GameTestHelper helper) {
        run(helper, "addonEntryListed", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            addBell(subs, bell(100).build());
            List<ShopOffer> offers = TownKitShop.offers(handle(tv), null);
            // with the shop hook attached, Towntopia's own list already ends with the addon row
            List<String> own = MayorShop.offers(mgr, MayorShop.villageSize(mgr), (Player) null).stream().map(TradeOfferEntry::itemId).toList();
            List<String> ids = offers.stream().map(o -> o.id().toString()).toList();
            c.checkHooksOn("Towntopia's rows first, then the addon row", ids.equals(own) && ids.get(ids.size() - 1).equals(BELL.toString())
                && ids.stream().limit(ids.size() - 1).allMatch(i -> i.startsWith("towntopia:")), ids);
            ShopOffer b = offer(offers, BELL);
            int size = MayorShop.villageSize(mgr);
            // the shared village may already own bells bought by other shop tests: that is the "owned" growth
            int owned = ShopCounts.of(helper.getLevel().getServer()).count(mgr.getVillageId(), BELL);
            c.check("grown price: Towntopia's priceAt(100, size, owned)", b != null && b.price() == MayorShop.priceAt(100, size, owned),
                b == null ? null : b.price() + " vs " + MayorShop.priceAt(100, size, owned) + " at size " + size + ", owned " + owned);
            c.check("emerald, available, item, name", b != null && b.currency() == Items.EMERALD && b.available() && b.reason() == null
                && b.item() == Items.BELL && b.name().getString().equals("Test Bell"), b);
            c.checkHooksOn("Towntopia's screen list has the row (by its id)",
                MayorShop.offers(mgr, (Player) null).stream().anyMatch(e -> e.itemId().equals(BELL.toString())), BELL);
            ScenarioBuilder.spawnVillager(helper.getLevel(), mgr, tv.door().north(3).east(2), Profession.NONE);
            int bigger = MayorShop.villageSize(mgr);
            ShopOffer grown = offer(TownKitShop.offers(handle(tv), null), BELL);
            c.check("a bigger village asks more", bigger > size && grown != null && grown.price() == MayorShop.priceAt(100, bigger, owned)
                && grown.price() > b.price(), grown == null ? null : grown.price() + " at size " + bigger);
            subs.remove(0).remove();
            addBell(subs, bell(100).priceGrowth(false).build());
            ShopOffer fixed = offer(TownKitShop.offers(handle(tv), null), BELL);
            c.check("growth off: the base price", fixed != null && fixed.price() == 100, fixed);
            c.check("a towntopia: id is refused", threw(() -> ShopEntry.builder(FARMER, () -> Items.BELL, 1)), FARMER);
            c.check("the same id twice is refused", threw(() -> TownKitShop.add(OWNER, bell(1).build())), BELL);
        });
    }

    /** A player with 5 bound coins buys a 3-coin entry: 2 left, the item given and bound, SHOP_BOUGHT once. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_buy_coin")
    public static void buyWithCoin(GameTestHelper helper) {
        run(helper, "buyWithCoin", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            Item coin = TestBlocks.TEST_COIN.get();
            addBell(subs, bell(3).priceGrowth(false).currency(TestBlocks.TEST_COIN).build());
            List<TownKitEvents.ShopBought> bought = new ArrayList<>();
            subs.add(TownKitEvents.SHOP_BOUGHT.listen(OWNER, e -> {
                if (e.offer().id().equals(BELL)) bought.add(e);
            }));
            ServerPlayer p = buyer(helper, tv, players, bound(coin, 5, mgr));
            int before = ShopCounts.of(p.getServer()).count(mgr.getVillageId(), BELL);
            ShopOffer listed = offer(TownKitShop.offers(handle(tv), p), BELL);
            c.check("listed in coins", listed != null && listed.currency() == coin && listed.price() == 3, listed);
            MayorShop.Purchase result = MayorShop.buy(p, mgr, BELL.toString());
            c.checkHooksOn("bought", result.success(), result.message());
            c.checkHooksOn("2 coins left", count(p, coin, null) == 2, count(p, coin, null));
            c.checkHooksOn("the bell given, bound to the village", count(p, Items.BELL, mgr) == 1, count(p, Items.BELL, null));
            c.checkHooksOn("SHOP_BOUGHT once", bought.size() == 1, bought.size());
            c.checkHooksOn("SHOP_BOUGHT: buyer, village, given",
                bought.size() == 1 && bought.get(0).buyer() == p && bought.get(0).village().id().equals(mgr.getVillageId())
                    && bought.get(0).given().is(Items.BELL) && VillageTokenBinding.matches(bought.get(0).given(), mgr), bought);
            c.checkHooksOn("the village's count went up", ShopCounts.of(p.getServer()).count(mgr.getVillageId(), BELL) == before + 1, before);
            MayorShop.Purchase again = MayorShop.buy(p, mgr, BELL.toString());
            c.checkHooksOn("2 coins aren't enough for a second", !again.success() && count(p, coin, null) == 2, again.message());
        });
    }

    /** Coins not bound to the village don't count; nothing is taken. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_unbound")
    public static void unboundCoinRefused(GameTestHelper helper) {
        run(helper, "unboundCoinRefused", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            Item coin = TestBlocks.TEST_COIN.get();
            addBell(subs, bell(3).priceGrowth(false).currency(TestBlocks.TEST_COIN).build());
            ServerPlayer p = buyer(helper, tv, players, new ItemStack(coin, 5));
            MayorShop.Purchase result = MayorShop.buy(p, mgr, BELL.toString());
            c.check("refused", !result.success(), result.message());
            c.checkHooksOn("says not enough", result.message().startsWith("Not enough"), result.message());
            c.check("5 coins still held, no bell", count(p, coin, null) == 5 && count(p, Items.BELL, null) == 0, count(p, coin, null));
            subs.remove(0).remove();
            addBell(subs, bell(3).priceGrowth(false).currency(TestBlocks.TEST_COIN).boundCurrency(false).bindSold(false).build());
            MayorShop.Purchase loose = MayorShop.buy(p, mgr, BELL.toString());
            c.checkHooksOn("boundCurrency(false): unbound coins pay", loose.success() && count(p, coin, null) == 2, loose.message());
            c.checkHooksOn("bindSold(false): the bell is not bound", count(p, Items.BELL, null) == 1 && count(p, Items.BELL, mgr) == 0,
                count(p, Items.BELL, mgr));
        });
    }

    /** A SHOP_BUYING listener cancels: nothing taken or given, the reason is the answer; the first cancel wins. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_cancel")
    public static void buyingCancelled(GameTestHelper helper) {
        run(helper, "buyingCancelled", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            Item coin = TestBlocks.TEST_COIN.get();
            addBell(subs, bell(3).priceGrowth(false).currency(TestBlocks.TEST_COIN).build());
            AtomicInteger bought = new AtomicInteger();
            subs.add(TownKitEvents.SHOP_BOUGHT.listen(OWNER, e -> bought.incrementAndGet()));
            List<Boolean> secondSawCancelled = new ArrayList<>();
            Subscription first = TownKitEvents.SHOP_BUYING.listen(OWNER, e -> e.cancel(Component.literal("No trading today.")));
            Subscription second = TownKitEvents.SHOP_BUYING.listen(OWNER, e -> {
                secondSawCancelled.add(e.cancelled());
                e.cancel(Component.literal("Second reason."));
            });
            subs.add(first);
            subs.add(second);
            ServerPlayer p = buyer(helper, tv, players, bound(coin, 5, mgr), bound(Items.EMERALD, 200, mgr));
            MayorShop.Purchase result = MayorShop.buy(p, mgr, BELL.toString());
            c.checkHooksOn("refused with the first reason", !result.success() && result.message().equals("No trading today."), result.message());
            c.checkHooksOn("later handlers still run and see it cancelled", secondSawCancelled.equals(List.of(true)), secondSawCancelled);
            c.check("nothing taken or given", count(p, coin, null) == 5 && count(p, Items.BELL, null) == 0, count(p, coin, null));
            MayorShop.Purchase own = MayorShop.buy(p, mgr, GUARD_POST.toString());
            c.checkHooksOn("Towntopia's own row: cancelled too", !own.success() && own.message().equals("No trading today."), own.message());
            c.checkHooksOn("no emeralds taken, no guard post", count(p, Items.EMERALD, null) == 200 && count(p, ModItems.GUARD_POST.get(), null) == 0,
                count(p, Items.EMERALD, null));
            c.check("no SHOP_BOUGHT", bought.get() == 0, bought.get());
            first.remove();
            second.remove();
            List<TownKitEvents.ShopBuying> seen = new ArrayList<>();
            subs.add(TownKitEvents.SHOP_BUYING.listen(OWNER, seen::add));
            MayorShop.Purchase ok = MayorShop.buy(p, mgr, GUARD_POST.toString());
            c.check("without the cancel Towntopia sells its row", ok.success(), ok.message());
            c.checkHooksOn("SHOP_BUYING for Towntopia's row, once", seen.size() == 1 && seen.get(0).offer().id().equals(GUARD_POST), seen.size());
            c.checkHooksOn("SHOP_BOUGHT for Towntopia's row, once", bought.get() == 1, bought.get());
        });
    }

    /** A SHOP_BUYING handler that spends the buyer's coins: the money is counted again, the sale refused, nothing taken or given. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_payment_window")
    public static void buyingHandlerSpendsCoins(GameTestHelper helper) {
        run(helper, "buyingHandlerSpendsCoins", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            Item coin = TestBlocks.TEST_COIN.get();
            addBell(subs, bell(3).priceGrowth(false).currency(TestBlocks.TEST_COIN).build());
            AtomicInteger bought = new AtomicInteger();
            subs.add(TownKitEvents.SHOP_BOUGHT.listen(OWNER, e -> bought.incrementAndGet()));
            // leaves 2 of the 5 coins: a payment that takes "whatever is there" would take them and give the bell
            subs.add(TownKitEvents.SHOP_BUYING.listen(OWNER, e -> {
                if (e.offer().id().equals(BELL)) {
                    e.buyer().getInventory().clearContent();
                    e.buyer().getInventory().add(bound(coin, 2, mgr));
                }
            }));
            ServerPlayer p = buyer(helper, tv, players, bound(coin, 5, mgr));
            MayorShop.Purchase result = MayorShop.buy(p, mgr, BELL.toString());
            c.check("refused", !result.success(), result.message());
            c.checkHooksOn("says not enough", result.message().startsWith("Not enough"), result.message());
            c.checkHooksOn("the 2 coins left are kept, no bell", count(p, coin, null) == 2 && count(p, Items.BELL, null) == 0,
                count(p, coin, null) + " coins, " + count(p, Items.BELL, null) + " bells");
            c.check("no SHOP_BOUGHT", bought.get() == 0, bought.get());
        });
    }

    /** An addon supplier that throws never lets Towntopia sell a row TownKit hid or changed: the shop fails closed. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_fail_closed")
    public static void throwingSupplierFailsClosed(GameTestHelper helper) {
        run(helper, "throwingSupplierFailsClosed", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            subs.add(TownKitShop.adjust(OWNER, ModItems.FARMER_TOKEN.get(), ShopAdjust.builder().hidden(true).build()));
            addBell(subs, bell(1).currency(() -> {
                throw new IllegalStateException("deliberate test failure: coin not registered");
            }).build());
            ServerPlayer p = buyer(helper, tv, players, bound(Items.EMERALD, 200, mgr));
            for (int i = 0; i < 12; i++) {
                MayorShop.Purchase own = MayorShop.buy(p, mgr, FARMER.toString());
                MayorShop.Purchase addon = MayorShop.buy(p, mgr, BELL.toString());
                if (i == 0 || i == 11) {
                    c.checkHooksOn("hidden Towntopia row refused (try " + i + ")", !own.success(), own.message());
                    c.check("the broken addon row refused (try " + i + ")", !addon.success(), addon.message());
                }
            }
            c.checkHooksOn("nothing taken or given", count(p, Items.EMERALD, null) == 200 && count(p, ModItems.FARMER_TOKEN.get(), null) == 0
                && count(p, Items.BELL, null) == 0, count(p, Items.EMERALD, null));
            ShopOffer b = offer(TownKitShop.offers(handle(tv), p), BELL);
            c.check("the broken row is listed greyed", b != null && !b.available(), b);
        });
    }

    /** Handing the bought item over throws after the coins were taken: the coins all come back, nothing is counted or fired. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_handover")
    public static void handOverFailureRefunds(GameTestHelper helper) {
        run(helper, "handOverFailureRefunds", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            Item coin = TestBlocks.TEST_COIN.get();
            addBell(subs, bell(3).priceGrowth(false).currency(TestBlocks.TEST_COIN).build());
            AtomicInteger bought = new AtomicInteger();
            subs.add(TownKitEvents.SHOP_BOUGHT.listen(OWNER, e -> bought.incrementAndGet()));
            ServerPlayer p = new BellRefusingPlayer(helper.getLevel());
            players.add(p);
            p.setPos(tv.door().getX() + 0.5, tv.door().getY(), tv.door().getZ() + 0.5);
            p.getInventory().add(bound(coin, 5, mgr));
            int before = ShopCounts.of(p.getServer()).count(mgr.getVillageId(), BELL);
            MayorShop.Purchase result = MayorShop.buy(p, mgr, BELL.toString());
            c.check("refused", !result.success(), result.message());
            c.check("all 5 coins back, still bound", count(p, coin, mgr) == 5, count(p, coin, null));
            c.check("no bell", count(p, Items.BELL, null) == 0, count(p, Items.BELL, null));
            c.check("the village's count unchanged", ShopCounts.of(p.getServer()).count(mgr.getVillageId(), BELL) == before, before);
            c.check("no SHOP_BOUGHT", bought.get() == 0, bought.get());
        });
    }

    /** A fake buyer whose inventory throws when a bell is added: stands in for any failure while the item is handed over. */
    private static final class BellRefusingPlayer extends FakePlayer {
        private final Inventory inventory = new Inventory(this) {
            @Override
            public boolean add(ItemStack stack) {
                if (stack.is(Items.BELL)) throw new IllegalStateException("deliberate test failure: handing over the bell");
                return super.add(stack);
            }
        };

        BellRefusingPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "townkit-shop-test"));
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }
    }

    /** A condition answering hidden: not listed, not buyable (also for a Towntopia row an adjust hides). */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_hidden")
    public static void conditionHidden(GameTestHelper helper) {
        run(helper, "conditionHidden", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            addBell(subs, bell(1).condition(ctx -> ShopCheck.hidden()).build());
            subs.add(TownKitShop.adjust(OWNER, ModItems.FARMER_TOKEN.get(), ShopAdjust.builder().hidden(true).build()));
            ServerPlayer p = buyer(helper, tv, players, bound(Items.EMERALD, 200, mgr));
            List<ShopOffer> offers = TownKitShop.offers(handle(tv), p);
            c.check("hidden addon row not listed", offer(offers, BELL) == null, offers.size());
            c.check("hidden Towntopia row not listed", offer(offers, FARMER) == null, offers.size());
            c.checkHooksOn("not in Towntopia's screen list either", MayorShop.offers(mgr, p).stream()
                .noneMatch(e -> e.itemId().equals(BELL.toString()) || e.itemId().equals(FARMER.toString())), FARMER);
            MayorShop.Purchase addon = MayorShop.buy(p, mgr, BELL.toString());
            MayorShop.Purchase own = MayorShop.buy(p, mgr, FARMER.toString());
            c.check("addon row refused", !addon.success() && addon.message().equals("The Mayor does not sell that."), addon.message());
            c.checkHooksOn("Towntopia row refused", !own.success() && own.message().equals("The Mayor does not sell that."), own.message());
            c.checkHooksOn("nothing taken", count(p, Items.EMERALD, null) == 200 && count(p, ModItems.FARMER_TOKEN.get(), null) == 0,
                count(p, Items.EMERALD, null));
            c.check("adjusting an item Towntopia doesn't sell throws",
                threw(() -> TownKitShop.adjust(OWNER, Items.DIRT, ShopAdjust.builder().hidden(true).build())), Items.DIRT);
        });
    }

    /** A condition answering greyed: listed with the reason, not buyable, nothing taken. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_greyed")
    public static void conditionGreyedNotBuyable(GameTestHelper helper) {
        run(helper, "conditionGreyedNotBuyable", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            Component why = Component.literal("Only while at war.");
            addBell(subs, bell(1).condition(ctx -> ShopCheck.greyed(why)).build());
            subs.add(TownKitShop.adjust(OWNER, ModItems.FARMER_TOKEN.get(), ShopAdjust.builder().condition(ctx -> ShopCheck.greyed(why)).build()));
            ServerPlayer p = buyer(helper, tv, players, bound(Items.EMERALD, 200, mgr));
            List<ShopOffer> offers = TownKitShop.offers(handle(tv), p);
            ShopOffer b = offer(offers, BELL);
            ShopOffer f = offer(offers, FARMER);
            c.check("addon row listed greyed with the reason", b != null && !b.available() && why.equals(b.reason()), b);
            c.check("Towntopia row listed greyed with the reason", f != null && !f.available() && why.equals(f.reason()), f);
            MayorShop.Purchase addon = MayorShop.buy(p, mgr, BELL.toString());
            MayorShop.Purchase own = MayorShop.buy(p, mgr, FARMER.toString());
            c.checkHooksOn("addon row refused with the reason", !addon.success() && addon.message().equals(why.getString()), addon.message());
            c.checkHooksOn("Towntopia row refused with the reason", !own.success() && own.message().equals(why.getString()), own.message());
            c.checkHooksOn("nothing taken or given", count(p, Items.EMERALD, null) == 200 && count(p, Items.BELL, null) == 0
                && count(p, ModItems.FARMER_TOKEN.get(), null) == 0, count(p, Items.EMERALD, null));
            subs.add(TownKitShop.add(OWNER, ShopEntry.builder(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_broken"),
                () -> Items.STICK, 1).condition(ctx -> {
                    throw new IllegalStateException("addon bug (expected in this test)");
                }).build()));
            ShopOffer broken = offer(TownKitShop.offers(handle(tv), p), ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_broken"));
            c.check("a throwing condition greys the row", broken != null && !broken.available()
                && Component.translatable("townkit.shop.unavailable").equals(broken.reason()), broken);
        });
    }

    /** {@code requires(towntopia:kitchen)}: greyed until the village has a valid kitchen. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_requires")
    public static void requiresBuilding(GameTestHelper helper) {
        run(helper, "requiresBuilding", (c, tv, subs, players) -> {
            ResourceLocation kitchen = ResourceLocation.parse("towntopia:kitchen");
            addBell(subs, bell(1).requires(kitchen).build());
            ShopOffer before = offer(TownKitShop.offers(handle(tv), null), BELL);
            c.check("greyed without a kitchen", before != null && !before.available()
                && Component.translatable("townkit.shop.needs_building", kitchen.toString()).equals(before.reason()), before);
            ServerLevel level = helper.getLevel();
            BuildingTests.Room room = BuildingTests.room(helper, tv, 12, 14, ModItems.KITCHEN::get, r -> {
                BuildingTests.put(level, r.west(), Blocks.FURNACE);
                BuildingTests.put(level, r.east(), Blocks.CRAFTING_TABLE);
            }).orElse(null);
            c.check("kitchen registered and valid", room != null && room.building().isValid(), room);
            ShopOffer after = offer(TownKitShop.offers(handle(tv), null), BELL);
            c.check("available with a valid kitchen", after != null && after.available() && after.reason() == null, after);
        });
    }

    /** Towntopia's Farmer token costs coins after the switch; the replacement Town Hall stays on emeralds. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_currency")
    public static void builtInCurrencySwitch(GameTestHelper helper) {
        run(helper, "builtInCurrencySwitch", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            Item coin = TestBlocks.TEST_COIN.get();
            subs.add(TownKitShop.builtInCurrency(OWNER, TestBlocks.TEST_COIN));
            ServerPlayer p = buyer(helper, tv, players, bound(Items.EMERALD, 10, mgr));
            ShopOffer farmer = offer(TownKitShop.offers(handle(tv), p), FARMER);
            c.check("Farmer token listed in coins", farmer != null && farmer.currency() == coin, farmer);
            if (farmer == null) return;
            List<TradeOfferEntry> own = MayorShop.offers(mgr, MayorShop.villageSize(mgr), (Player) p);
            c.check("same price as Towntopia's", own.stream().anyMatch(e -> e.itemId().equals(FARMER.toString()) && e.price() == farmer.price()),
                farmer.price());
            p.getInventory().add(bound(coin, farmer.price() + 2, mgr));
            MayorShop.Purchase result = MayorShop.buy(p, mgr, FARMER.toString());
            c.check("bought with coins", result.success(), result.message());
            c.checkHooksOn("coins paid, emeralds kept", count(p, coin, null) == 2 && count(p, Items.EMERALD, null) == 10,
                count(p, coin, null) + " coins, " + count(p, Items.EMERALD, null) + " emeralds");
            c.check("Farmer token given, bound", count(p, ModItems.FARMER_TOKEN.get(), mgr) == 1, count(p, ModItems.FARMER_TOKEN.get(), null));
            c.checkHooksOn("replacement Town Hall counts emeralds, not coins", MayorShop.replacementEmeralds(p.getInventory(), mgr.getVillageId()) == 10,
                MayorShop.replacementEmeralds(p.getInventory(), mgr.getVillageId()));
            c.check("TownKit has no hook on the replacement Town Hall", Hooks.allEntries().stream()
                .noneMatch(e -> e.hook.key().startsWith("town.MayorShop#buyReplacementTownHall") || e.hook.key().startsWith("town.MayorShop#replacement")),
                ShopHooks.attachedKeys());
            Subscription gold = TownKitShop.builtInCurrency(OWNER + "-2", () -> Items.GOLD_NUGGET);
            subs.add(gold);
            ShopOffer second = offer(TownKitShop.offers(handle(tv), p), FARMER);
            c.check("the last switch wins", second != null && second.currency() == Items.GOLD_NUGGET, second);
            gold.remove();
            ShopOffer back = offer(TownKitShop.offers(handle(tv), p), FARMER);
            c.check("removing it brings the first back", back != null && back.currency() == coin, back);
        });
    }

    /** No registrations: no shop hook attached and offers is Towntopia's own list exactly. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_unchanged")
    public static void noAddonUnchanged(GameTestHelper helper) {
        run(helper, "noAddonUnchanged", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            c.check("no shop hook attached", ShopHooks.attachedKeys().isEmpty(), ShopHooks.attachedKeys());
            c.check("no TownKit listener on Towntopia's shop", Hooks.allEntries().stream()
                .noneMatch(e -> TownKit.MODID.equals(e.owner) && e.hook.key().startsWith("town.MayorShop#")), ShopHooks.attachedKeys());
            ServerPlayer p = buyer(helper, tv, players, bound(Items.EMERALD, 5, mgr));
            for (Player who : java.util.Arrays.asList(null, p)) {
                List<TradeOfferEntry> own = MayorShop.offers(mgr, MayorShop.villageSize(mgr), who);
                List<ShopOffer> offers = TownKitShop.offers(handle(tv), (ServerPlayer) who);
                boolean same = own.size() == offers.size();
                for (int i = 0; same && i < own.size(); i++) {
                    TradeOfferEntry e = own.get(i);
                    ShopOffer o = offers.get(i);
                    same = o.id().toString().equals(e.itemId()) && o.name().getString().equals(e.displayName()) && o.price() == e.price()
                        && o.currency() == Items.EMERALD && o.available() && o.reason() == null;
                }
                c.check("offers equals Towntopia's list (" + (who == null ? "no buyer" : "buyer") + ")", same, offers.size() + " vs " + own.size());
            }
        });
    }

    /**
     * Price functions decide what is charged: an addon row priced by a {@link townkit.api.shop.ShopPrice}, and Towntopia's
     * Farmer token at an adjusted price ({@code grownPrice} is Towntopia's own price).
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_prices")
    public static void customPricesCharged(GameTestHelper helper) {
        run(helper, "customPricesCharged", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            Item coin = TestBlocks.TEST_COIN.get();
            List<Integer> seenBase = new ArrayList<>();
            addBell(subs, bell(4).currency(TestBlocks.TEST_COIN).price(ctx -> {
                seenBase.add(ctx.basePrice());
                return ctx.basePrice() + 7;
            }).build());
            ServerPlayer p = buyer(helper, tv, players, bound(coin, 20, mgr), bound(Items.EMERALD, 50, mgr));
            int towntopiaFarmer = MayorShop.offers(mgr, MayorShop.villageSize(mgr), (Player) p).stream()
                .filter(e -> e.itemId().equals(FARMER.toString())).findFirst().map(TradeOfferEntry::price).orElse(-1);
            subs.add(TownKitShop.adjust(OWNER, ModItems.FARMER_TOKEN.get(), ShopAdjust.builder().price(ctx -> ctx.grownPrice() + 1).build()));
            List<ShopOffer> offers = TownKitShop.offers(handle(tv), p);
            ShopOffer b = offer(offers, BELL);
            ShopOffer f = offer(offers, FARMER);
            c.check("addon row listed at base + 7", b != null && b.price() == 11, b);
            c.check("price function sees the base price", seenBase.contains(4), seenBase);
            c.check("Farmer token listed at Towntopia's price + 1", towntopiaFarmer > 0 && f != null && f.price() == towntopiaFarmer + 1,
                f + " vs Towntopia " + towntopiaFarmer);
            MayorShop.Purchase bell = MayorShop.buy(p, mgr, BELL.toString());
            c.checkHooksOn("bell bought", bell.success(), bell.message());
            c.checkHooksOn("charged 11 coins", count(p, coin, null) == 9, count(p, coin, null));
            MayorShop.Purchase farmer = MayorShop.buy(p, mgr, FARMER.toString());
            c.check("Farmer token bought", farmer.success(), farmer.message());
            c.checkHooksOn("charged Towntopia's price + 1 emeralds", count(p, Items.EMERALD, null) == 50 - (towntopiaFarmer + 1),
                count(p, Items.EMERALD, null) + " left of 50, Towntopia's price " + towntopiaFarmer);
            c.check("Farmer token given, bound", count(p, ModItems.FARMER_TOKEN.get(), mgr) == 1, count(p, ModItems.FARMER_TOKEN.get(), null));
        });
    }

    /** A player whose client got shop extras gets an empty payload once the last rule is removed (hooks kept or not). */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_shop_extras_cleared")
    public static void extrasClearedAfterLastRule(GameTestHelper helper) {
        run(helper, "extrasClearedAfterLastRule", (c, tv, subs, players) -> {
            VillageManager mgr = tv.mgr();
            List<String> sent = new ArrayList<>();
            ShopHooks.Channel capture = new ShopHooks.Channel() {
                @Override
                public boolean reaches(ServerPlayer player) {
                    return true;
                }

                @Override
                public void send(ServerPlayer to, ShopExtrasPayload payload) {
                    sent.add(to.getGameProfile().getName() + ":" + payload.rows().stream().map(ShopExtrasPayload.Extra::offerId).toList());
                }
            };
            ShopHooks.Channel old = ShopHooks.swapChannelForTests(capture);
            try {
                ServerPlayer p = buyer(helper, tv, players);
                String who = p.getGameProfile().getName();
                // 1: hooks detach with the last rule
                Subscription bellSub = TownKitShop.add(OWNER, bell(1).build());
                MayorShop.offers(mgr, p);
                c.checkHooksOn("extras sent with the list", sent.equals(List.of(who + ":[" + BELL + "]")), sent);
                sent.clear();
                bellSub.remove();
                c.checkHooksOn("last rule removed: empty extras sent", sent.equals(List.of(who + ":[]")), sent);
                c.check("hooks detached", ShopHooks.attachedKeys().isEmpty(), ShopHooks.attachedKeys());
                // 2: a currency switch removed while a shop listener keeps the hooks attached
                sent.clear();
                subs.add(TownKitEvents.SHOP_BOUGHT.listen(OWNER, e -> { }));
                Subscription currency = TownKitShop.builtInCurrency(OWNER, TestBlocks.TEST_COIN);
                MayorShop.offers(mgr, p);
                c.checkHooksOn("switched rows sent", sent.size() == 1 && sent.get(0).contains(FARMER.toString()), sent);
                sent.clear();
                currency.remove();
                c.checkHooksOn("switch removed: empty extras sent though hooks stay", sent.equals(List.of(who + ":[]"))
                    && !ShopHooks.attachedKeys().isEmpty(), sent + " " + ShopHooks.attachedKeys());
                sent.clear();
                MayorShop.offers(mgr, p);
                c.check("no rule: the next list sends nothing more", sent.isEmpty(), sent);
            } finally {
                ShopHooks.swapChannelForTests(old);
            }
        });
    }

    /** The extras payload through Minecraft's own component codec, with the game's registries. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_shop_extras")
    public static void extrasRoundTrip(GameTestHelper helper) {
        Checks c = new Checks("extrasRoundTrip");
        ShopExtrasPayload sent = new ShopExtrasPayload(List.of(
            new ShopExtrasPayload.Extra(BELL.toString(), ResourceLocation.parse("minecraft:bell"), ResourceLocation.parse("townkit:test_coin"),
                true, false, Optional.of(Component.translatable("townkit.shop.needs_building", "towntopia:kitchen").withStyle(ChatFormatting.RED)),
                Optional.of(Component.literal("Rings the alarm")), Optional.of(Component.literal("Test Bell"))),
            new ShopExtrasPayload.Extra(FARMER.toString(), FARMER, ResourceLocation.parse("minecraft:emerald"), true, true,
                Optional.empty(), Optional.empty(), Optional.empty())));
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        ShopExtrasPayload.STREAM_CODEC.encode(buf, sent);
        ShopExtrasPayload read = ShopExtrasPayload.STREAM_CODEC.decode(buf);
        c.check("round trip equal", sent.equals(read), read);
        c.check("nothing left over", !buf.isReadable(), buf.readableBytes());
        c.finish(helper);
    }

    private static boolean threw(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
