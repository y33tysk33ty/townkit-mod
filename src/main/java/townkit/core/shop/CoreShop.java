package townkit.core.shop;

import com.alex.test.town.MayorShop;
import com.alex.test.town.TradeOfferEntry;
import com.alex.test.village.VillageManager;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import townkit.api.Subscription;
import townkit.api.building.BuildingDetails;
import townkit.api.shop.ShopAdjust;
import townkit.api.shop.ShopEntry;
import townkit.api.shop.ShopOffer;
import townkit.api.spi.TownKitShopView;
import townkit.api.village.Village;
import townkit.core.TownKit;
import townkit.api.building.TownKitBuildings;
import townkit.core.building.RewrittenSites;
import townkit.core.village.VillageHandle;

/**
 * The Mayor's shop for addons ({@link townkit.api.shop.TownKitShop}): owns the one {@link ShopTable} and turns
 * Towntopia's own offers plus its rules into the rows a buyer sees. {@link ShopHooks} attaches to Towntopia's shop
 * while anything is registered.
 */
public final class CoreShop implements TownKitShopView {
    static final ShopTable<Item> TABLE = new ShopTable<>(CoreShop::logFailure, ShopHooks::refresh);

    /** Set while TownKit itself asks Towntopia for its own list, so the offers hook leaves that list alone. */
    private static final ThreadLocal<Boolean> RAW = ThreadLocal.withInitial(() -> false);
    @Nullable
    private static volatile Map<Item, Integer> catalogue;
    private static volatile boolean catalogueRead;
    private static volatile boolean warnedHooksOff;

    @Override
    public Subscription add(String owner, ShopEntry entry) {
        checkOwner(owner);
        Objects.requireNonNull(entry, "entry");
        warnIfHooksOff(owner);
        Supplier<Component> name = entry.name().isPresent() ? entry.name()::get : () -> new ItemStack(entry.item().get()).getHoverName();
        return TABLE.add(owner, new ShopTable.AddonRow<>(entry.id(), entry.item(), name, entry.description().orElse(null), entry.basePrice(),
            entry.priceGrowth(), entry.price().orElse(null), entry.currency(), entry.boundCurrency(), entry.bindSold(),
            entry.requires().orElse(null), entry.condition().orElse(null)));
    }

    @Override
    public Subscription adjust(String owner, Item builtIn, ShopAdjust adjust) {
        checkOwner(owner);
        Objects.requireNonNull(builtIn, "builtIn");
        Objects.requireNonNull(adjust, "adjust");
        Map<Item, Integer> c = catalogue();
        if (c != null && !c.containsKey(builtIn)) {
            throw new IllegalArgumentException("Towntopia's shop has no row for " + BuiltInRegistries.ITEM.getKey(builtIn));
        }
        warnIfHooksOff(owner);
        return TABLE.adjust(owner, builtIn, adjust);
    }

    @Override
    public Subscription builtInCurrency(String owner, Supplier<Item> coin) {
        checkOwner(owner);
        Objects.requireNonNull(coin, "coin");
        warnIfHooksOff(owner);
        String before = TABLE.currencyOwner();
        if (before != null) {
            TownKit.LOG.warn("[TownKit] shop: '{}' switches the currency of Towntopia's shop rows, which '{}' already switched; the last "
                + "one registered ('{}') wins", owner, before, owner);
        }
        return TABLE.currency(owner, coin);
    }

    @Override
    public List<ShopOffer> offers(Village village, @Nullable ServerPlayer buyer) {
        Objects.requireNonNull(village, "village");
        if (!(village instanceof VillageHandle h)) throw new IllegalArgumentException("Not a TownKit village handle: " + village);
        VillageManager mgr = h.manager();
        if (mgr == null || mgr.getLevel() == null) return List.of();
        if (!mgr.getLevel().getServer().isSameThread()) throw new IllegalStateException("TownKitShop.offers is server thread only");
        List<ShopOffer> out = new ArrayList<>();
        for (ShopTable.Row<Item> r : rows(mgr, buyer, raw(mgr, buyer))) out.add(offer(r));
        return List.copyOf(out);
    }

    // --- rows ------------------------------------------------------------------------------------------------------

    /** Towntopia's own list for this village and buyer, untouched by TownKit's offers hook. */
    static List<TradeOfferEntry> raw(VillageManager mgr, @Nullable Player buyer) {
        RAW.set(true);
        try {
            return MayorShop.offers(mgr, MayorShop.villageSize(mgr), buyer);
        } finally {
            RAW.set(false);
        }
    }

    static boolean computingRaw() {
        return RAW.get();
    }

    /** Towntopia's rows plus the registered rules: what {@code buyer} sees in {@code mgr}'s shop. */
    static List<ShopTable.Row<Item>> rows(VillageManager mgr, @Nullable Player buyer, List<TradeOfferEntry> towntopia) {
        Map<Item, Integer> c = catalogue();
        List<ShopTable.BuiltInRow<Item>> builtIn = new ArrayList<>(towntopia.size());
        for (TradeOfferEntry e : towntopia) {
            ResourceLocation id = ResourceLocation.tryParse(e.itemId());
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            Integer base = c == null ? null : c.get(item);
            builtIn.add(new ShopTable.BuiltInRow<>(id, item, Component.literal(e.displayName()), base == null ? e.price() : base, e.price()));
        }
        VillageHandle village = VillageHandle.of(mgr);
        int size = MayorShop.villageSize(mgr);
        ServerPlayer sp = buyer instanceof ServerPlayer p ? p : null;
        ShopCounts counts = ShopCounts.of(mgr.getLevel().getServer());
        return TABLE.compute(builtIn, new ShopTable.Env<>() {
            @Override
            public Village village() {
                return village;
            }

            @Nullable
            @Override
            public ServerPlayer buyer() {
                return sp;
            }

            @Override
            public Item emerald() {
                return MayorShop.CURRENCY;
            }

            @Override
            public int grown(ResourceLocation addonId, int basePrice) {
                return MayorShop.priceAt(basePrice, size, counts.count(mgr.getVillageId(), addonId));
            }

            @Override
            public boolean hasBuilding(ResourceLocation type) {
                for (BuildingDetails d : TownKitBuildings.ofType(village, type)) {
                    if (d.valid()) return true;
                }
                return false;
            }

            @Override
            public Item broken() {
                return Items.BARRIER;
            }
        });
    }

    /** The list Towntopia's screen gets: Towntopia's entries (with TownKit's price) and one entry per addon row. */
    static List<TradeOfferEntry> entries(List<ShopTable.Row<Item>> rows, List<TradeOfferEntry> towntopia) {
        Map<String, TradeOfferEntry> own = new java.util.HashMap<>();
        for (TradeOfferEntry e : towntopia) own.put(e.itemId(), e);
        List<TradeOfferEntry> out = new ArrayList<>(rows.size());
        for (ShopTable.Row<Item> r : rows) {
            TradeOfferEntry e = r.builtIn() ? own.get(r.id().toString()) : null;
            if (e != null) {
                out.add(e.price() == r.price() ? e : new TradeOfferEntry(e.itemId(), e.displayName(), r.price(), e.profession(), e.hint()));
            } else {
                // the entry id is the row id (not the item's): the Buy packet names it, and TownKit's client draws its item
                out.add(new TradeOfferEntry(r.id().toString(), r.name().getString(), r.price(), false, ""));
            }
        }
        return List.copyOf(out);
    }

    static ShopOffer offer(ShopTable.Row<Item> r) {
        return new ShopOffer(r.id(), r.item(), r.name(), r.price(), r.currency(), r.available(), r.reason());
    }

    /** Towntopia's catalogue: base price by item; null (logged once) if it can't be read. */
    @Nullable
    static Map<Item, Integer> catalogue() {
        if (catalogueRead) return catalogue;
        synchronized (CoreShop.class) {
            if (!catalogueRead) {
                try {
                    Field f = MayorShop.class.getDeclaredField("CATALOGUE");
                    f.setAccessible(true);
                    Map<Item, Integer> m = new IdentityHashMap<>();
                    for (Object o : (List<?>) f.get(null)) {
                        MayorShop.CatalogueEntry e = (MayorShop.CatalogueEntry) o;
                        m.put(e.item().get(), e.basePrice());
                    }
                    catalogue = m;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    TownKit.LOG.warn("[TownKit] shop: can't read Towntopia's catalogue ({}): adjust() can't check its item, and price "
                        + "functions on Towntopia's rows see Towntopia's price as the base price", e.toString());
                }
                catalogueRead = true;
            }
            return catalogue;
        }
    }

    // --- misc ------------------------------------------------------------------------------------------------------

    private static void checkOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) throw new IllegalArgumentException("owner is blank");
    }

    private static void warnIfHooksOff(String owner) {
        if (!warnedHooksOff && RewrittenSites.GLOBAL.hooksOff()) {
            warnedHooksOff = true;
            TownKit.LOG.warn("[TownKit] shop: {} registered a shop change, but every Towntopia hook is switched off in "
                + "townkit-hooks.properties, so the Mayor's shop stays Towntopia's own", owner);
        }
    }

    private static void logFailure(ResourceLocation id, Throwable t) {
        TownKit.LOG.error("[TownKit] shop row {}: its price, condition, item, name or currency failed, so it shows greyed out "
            + "(logged once per row)", id, t);
    }
}
