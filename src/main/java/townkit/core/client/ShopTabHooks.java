package townkit.core.client;

import com.alex.test.Tr;
import com.alex.test.network.MayorTradePayloads;
import com.alex.test.town.TradeOfferEntry;
import com.alex.test.ui.MayorStatsMenu;
import com.alex.test.village.tokens.VillageTokenBinding;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.core.TownKit;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.net.ShopExtrasPayload;

/**
 * Client side of the Mayor shop's addon rows. Towntopia's offer entries carry no currency, availability, reason or
 * description, so the server sends a {@link ShopExtrasPayload} for the rows TownKit sells, and these hooks (attached
 * while the last payload had rows, removed otherwise) use it on Towntopia's shop tab:
 * <ul>
 *   <li>{@code ShopTab#render} after: each such card's currency item and price (coloured by what the player can pay),
 *   an addon row's name, the Buy button, and a dark veil over a greyed card.</li>
 *   <li>{@code ShopTab#mouseClicked} before: a click on a greyed card's Buy does nothing; a row paid in anything but
 *   village emeralds is bought here (Towntopia's own click only checks emeralds).</li>
 *   <li>{@code ShopTokenDescriptions#forItem} after: an addon row's description (Towntopia's rows keep theirs), plus a
 *   greyed row's reason.</li>
 *   <li>{@code MayorLook#icon} before: an addon row's icon (its entry id is not an item id).</li>
 * </ul>
 * The card layout mirrors Towntopia 0.1.2's {@code ShopTab}; its private parts are read through method handles, and if
 * any is missing TownKit draws and blocks nothing (logged once), leaving Towntopia's look. Client main thread only.
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class ShopTabHooks {
    static final String RENDER = "ui.screen.mayor.ShopTab#render(GuiGraphics,int,int)";
    static final String CLICK = "ui.screen.mayor.ShopTab#mouseClicked(double,double)";
    static final String DESCRIPTION = "town.ShopTokenDescriptions#forItem(String)";
    static final String ICON = "ui.screen.mayor.MayorLook#icon(String)";

    // Towntopia 0.1.2 ShopTab layout
    private static final int SHOP_X = 14;
    private static final int LIST_Y = 88;
    private static final int CARD_H = 28;
    private static final int ROW_STEP = 30;
    private static final int BUY_W = 44;
    private static final int BUY_H = 16;
    private static final int CARD_BG = -14079703;
    private static final int CARD_BG_HOVER = -13092808;

    /** One card as last drawn (for the client check). */
    public record DrawnCard(String offerId, int x, int y, int width, ResourceLocation currency, boolean available) {
    }

    private static Map<String, ShopExtrasPayload.Extra> extras = Map.of();
    private static final List<Hooks.Entry> ATTACHED = new ArrayList<>();
    private static List<DrawnCard> lastDrawn = List.of();
    @Nullable
    private static String lastDescription;
    @Nullable
    private static Layout layout;
    private static boolean layoutFailed;

    private ShopTabHooks() {
    }

    /** Takes the rows the server sent with the shop list it last sent. */
    public static void accept(ShopExtrasPayload payload) {
        Map<String, ShopExtrasPayload.Extra> m = new HashMap<>();
        for (ShopExtrasPayload.Extra e : payload.rows()) m.put(e.offerId(), e);
        extras = Map.copyOf(m);
        if (m.isEmpty()) detach();
        else attach();
    }

    /** The extras for an offer id, or null. */
    @Nullable
    public static ShopExtrasPayload.Extra extra(String offerId) {
        return extras.get(offerId);
    }

    /** The cards with extras in the last drawn frame of the shop tab. */
    public static List<DrawnCard> lastDrawn() {
        return lastDrawn;
    }

    /** The last description TownKit handed Towntopia's shop tooltip (the hovered card's). */
    @Nullable
    public static String lastDescription() {
        return lastDescription;
    }

    public static boolean attached() {
        return !ATTACHED.isEmpty();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        extras = Map.of();
        detach();
    }

    private static void attach() {
        if (!ATTACHED.isEmpty()) return;
        add(RENDER, true, ShopTabHooks::afterRender);
        add(CLICK, false, ShopTabHooks::beforeClick);
        add(DESCRIPTION, true, ShopTabHooks::afterDescription);
        add(ICON, false, ShopTabHooks::beforeIcon);
    }

    private static void detach() {
        ATTACHED.forEach(Hooks::remove);
        ATTACHED.clear();
        lastDrawn = List.of();
    }

    private static void add(String key, boolean after, HookListener listener) {
        HookInfo h = HookManifest.get().byKey.get(key);
        if (h == null) {
            TownKit.LOG.warn("[TownKit] can't find {}: the Mayor's shop tab loses that part of TownKit's rows", key);
            return;
        }
        ATTACHED.add(Hooks.add(h, TownKit.MODID, listener, after));
    }

    // --- hooks -----------------------------------------------------------------------------------------------------

    private static void afterDescription(HookCall call) {
        if (!(call.args()[0] instanceof String id)) return;
        ShopExtrasPayload.Extra e = extras.get(id);
        if (e == null) return;
        String text = e.description().map(Component::getString).orElse((String) call.returnValue());
        if (!e.available() && e.reason().isPresent()) text = (text == null || text.isEmpty() ? "" : text + "\n") + e.reason().get().getString();
        lastDescription = text;
        call.setReturnValue(text);
    }

    private static void beforeIcon(HookCall call) {
        if (!(call.args()[0] instanceof String id)) return;
        ShopExtrasPayload.Extra e = extras.get(id);
        if (e != null && e.name().isPresent()) call.cancelWith(new ItemStack(BuiltInRegistries.ITEM.get(e.item())));
    }

    private static void afterRender(HookCall call) {
        Layout l = layout(call.self());
        Context ctx = Context.current();
        if (l == null || ctx == null) return;
        GuiGraphics g = (GuiGraphics) call.args()[0];
        int mouseX = (Integer) call.args()[1];
        int mouseY = (Integer) call.args()[2];
        Font font = Minecraft.getInstance().font;
        List<Card> cards = l.cards(call.self());
        if (cards == null) return;
        List<DrawnCard> drawn = new ArrayList<>();
        int listX = l.left(call.self()) + SHOP_X;
        int listY = l.top(call.self()) + LIST_Y;
        int width = l.listW(call.self());
        int height = l.listH(call.self());
        // the reflective reads above may throw (a Towntopia layout change): never with the scissor on
        g.enableScissor(listX + 1, listY + 1, listX + width - 1, listY + height - 1);
        try {
            for (Card c : cards) {
                ShopExtrasPayload.Extra e = extras.get(c.offer.itemId());
                if (e == null) continue;
                Item currency = BuiltInRegistries.ITEM.get(e.currency());
                int held = ctx.count(currency, e.boundCurrency());
                boolean affordable = e.available() && held >= c.offer.price();
                boolean hovered = inside(mouseX, mouseY, c.x, c.y, c.width, CARD_H);
                int bg = hovered ? CARD_BG_HOVER : CARD_BG;
                int buttonX = c.buttonX();
                g.pose().pushPose();
                try {
                    g.pose().translate(0, 0, 200);
                    // cover Towntopia's emerald and price (and an addon row's name), then draw TownKit's
                    g.fill(c.x + 22, c.y + 9, buttonX - 2, c.y + CARD_H - 1, bg);
                    if (e.name().isPresent()) g.fill(c.x + 24, c.y + 2, buttonX - 2, c.y + 13, bg);
                    g.pose().translate(0, 0, 10);
                    g.renderFakeItem(new ItemStack(currency), c.x + 24, c.y + 10);
                    g.drawString(font, Integer.toString(c.offer.price()), c.x + 41, c.y + 15, affordable ? -1649510 : -5592406, false);
                    e.name().ifPresent(n -> g.drawString(font, font.substrByWidth(n, buttonX - c.x - 30).getString(), c.x + 25, c.y + 4, -789517, false));
                    boolean buttonHover = affordable && inside(mouseX, mouseY, buttonX, c.y + 6, BUY_W, BUY_H);
                    g.fill(buttonX, c.y + 6, buttonX + BUY_W, c.y + 6 + BUY_H, !affordable ? -14407644 : (buttonHover ? -11636909 : -12890562));
                    String buy = Tr.s("screen.dynamicvillages.mayor.buy", "Buy");
                    g.pose().translate(0, 0, 10);
                    g.drawString(font, buy, buttonX + (BUY_W - font.width(buy)) / 2, c.y + 10, affordable ? -789517 : -5592406, false);
                    if (!e.available()) {
                        // above the drawn items, below Towntopia's tooltip (z 400)
                        g.pose().translate(0, 0, 160);
                        g.fill(c.x, c.y, c.x + c.width, c.y + CARD_H, 0x90101010);
                    }
                } finally {
                    g.pose().popPose();
                }
                drawn.add(new DrawnCard(c.offer.itemId(), c.x, c.y, c.width, e.currency(), e.available()));
            }
        } finally {
            g.disableScissor();
        }
        lastDrawn = List.copyOf(drawn);
    }

    private static void beforeClick(HookCall call) {
        Layout l = layout(call.self());
        Context ctx = Context.current();
        if (l == null || ctx == null) return;
        double mouseX = (Double) call.args()[0];
        double mouseY = (Double) call.args()[1];
        List<Card> cards = l.cards(call.self());
        if (cards == null) return;
        for (Card c : cards) {
            if (!inside(mouseX, mouseY, c.buttonX(), c.y + 6, BUY_W, BUY_H)) continue;
            ShopExtrasPayload.Extra e = extras.get(c.offer.itemId());
            if (e == null) return; // Towntopia's own row: Towntopia's click
            Item currency = BuiltInRegistries.ITEM.get(e.currency());
            if (e.available() && (currency != Items.EMERALD || !e.boundCurrency())) {
                if (ctx.count(currency, e.boundCurrency()) >= c.offer.price()) {
                    PacketDistributor.sendToServer(new MayorTradePayloads.Buy(ctx.menu.mayorId(), c.offer.itemId()));
                }
                call.cancelWith(true);
            } else if (!e.available()) {
                call.cancelWith(true);
            }
            return;
        }
    }

    // --- layout ----------------------------------------------------------------------------------------------------

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** The open Mayor menu and the player's inventory. */
    private record Context(MayorStatsMenu menu, Inventory inventory) {
        @Nullable
        static Context current() {
            Player p = Minecraft.getInstance().player;
            return p != null && p.containerMenu instanceof MayorStatsMenu m ? new Context(m, p.getInventory()) : null;
        }

        int count(Item currency, boolean bound) {
            UUID village = this.menu.villageId();
            int total = 0;
            for (int slot = 0; slot < this.inventory.getContainerSize(); slot++) {
                ItemStack s = this.inventory.getItem(slot);
                if (s.isEmpty() || !s.is(currency)) continue;
                if (bound) {
                    VillageTokenBinding b = VillageTokenBinding.read(s);
                    if (b == null || village == null || !b.townHallId().equals(village)) continue;
                }
                total += s.getCount();
            }
            return total;
        }
    }

    private record Card(TradeOfferEntry offer, int x, int y, int width) {
        int buttonX() {
            return this.x + this.width - BUY_W - 5;
        }
    }

    @Nullable
    private static Layout layout(Object shopTab) {
        if (layout != null || layoutFailed) return layout;
        try {
            layout = new Layout(shopTab.getClass());
        } catch (ReflectiveOperationException | RuntimeException e) {
            layoutFailed = true;
            TownKit.LOG.warn("[TownKit] can't read Towntopia's shop tab layout ({}): addon shop rows are drawn as Towntopia draws them", e.toString());
        }
        return layout;
    }

    /** Method handles on Towntopia's ShopTab (and its MayorPart base). */
    private static final class Layout {
        private final MethodHandle offers;
        private final MethodHandle cardW;
        private final MethodHandle visibleRows;
        private final MethodHandle listScroll;
        private final MethodHandle left;
        private final MethodHandle top;
        private final MethodHandle listW;

        Layout(Class<?> shopTab) throws ReflectiveOperationException {
            MethodHandles.Lookup tab = MethodHandles.privateLookupIn(shopTab, MethodHandles.lookup());
            Class<?> part = shopTab.getSuperclass();
            MethodHandles.Lookup base = MethodHandles.privateLookupIn(part, MethodHandles.lookup());
            this.offers = tab.findVirtual(shopTab, "offers", MethodType.methodType(List.class));
            this.cardW = tab.findVirtual(shopTab, "cardW", MethodType.methodType(int.class));
            this.visibleRows = tab.findVirtual(shopTab, "visibleRows", MethodType.methodType(int.class));
            this.listScroll = tab.findGetter(shopTab, "listScroll", int.class);
            this.left = base.findVirtual(part, "left", MethodType.methodType(int.class));
            this.top = base.findVirtual(part, "top", MethodType.methodType(int.class));
            this.listW = base.findVirtual(part, "listW", MethodType.methodType(int.class, boolean.class));
        }

        int left(Object tab) {
            return call(this.left, tab);
        }

        int top(Object tab) {
            return call(this.top, tab);
        }

        int listW(Object tab) {
            try {
                return (int) this.listW.invoke(tab, true);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        int listH(Object tab) {
            return ROW_STEP * call(this.visibleRows, tab);
        }

        /** The cards on screen, as Towntopia lays them out; null if Towntopia's parts can't be called. */
        @Nullable
        @SuppressWarnings("unchecked")
        List<Card> cards(Object tab) {
            try {
                List<TradeOfferEntry> entries = (List<TradeOfferEntry>) this.offers.invoke(tab);
                int x = call(this.left, tab) + SHOP_X;
                int listY = call(this.top, tab) + LIST_Y;
                int cardW = call(this.cardW, tab);
                int rows = call(this.visibleRows, tab);
                int scroll = call(this.listScroll, tab);
                List<Card> out = new ArrayList<>();
                for (int row = 0; row < rows; row++) {
                    for (int column = 0; column < 2; column++) {
                        int index = (scroll + row) * 2 + column;
                        if (index >= entries.size()) break;
                        out.add(new Card(entries.get(index), x + 1 + column * (cardW + 2), listY + 1 + row * ROW_STEP, cardW));
                    }
                }
                return out;
            } catch (Throwable t) {
                return null;
            }
        }

        private static int call(MethodHandle h, Object tab) {
            try {
                return (int) h.invoke(tab);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }
}
