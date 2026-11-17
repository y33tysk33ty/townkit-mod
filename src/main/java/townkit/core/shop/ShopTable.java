package townkit.core.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import townkit.api.Subscription;
import townkit.api.shop.ShopAdjust;
import townkit.api.shop.ShopCheck;
import townkit.api.shop.ShopCondition;
import townkit.api.shop.ShopContext;
import townkit.api.shop.ShopPrice;
import townkit.api.village.Village;

/**
 * The shop rules addons registered (addon rows, adjustments of Towntopia's rows, currency switches for Towntopia's
 * rows) and the one pure step that turns Towntopia's own rows plus those rules into the rows a buyer sees.
 *
 * <p>Writers lock and publish an immutable {@link State}; {@link #compute} reads the current state without locking.
 * Prices, conditions and the item, name and currency suppliers run inside {@link #compute}: one that throws (or a
 * price or condition that answers null) greys its row with {@code townkit.shop.unavailable}, and {@code onFailure}
 * hears about it once per row id. A built-in currency switch whose supplier throws greys every switched row.
 *
 * @param <I> item type (strings in unit tests)
 */
public final class ShopTable<I> {
    static final Component UNAVAILABLE = Component.translatable("townkit.shop.unavailable");

    /** One of Towntopia's rows as Towntopia lists it: its catalogue base price and the price it asks. */
    public record BuiltInRow<I>(ResourceLocation id, I item, Component name, int basePrice, int grownPrice) {
    }

    /** An addon row, as registered. {@code description} null means the name. */
    public record AddonRow<I>(ResourceLocation id, Supplier<I> item, Supplier<Component> name, @Nullable Component description, int basePrice,
            boolean priceGrowth, @Nullable ShopPrice price, Supplier<I> currency, boolean boundCurrency, boolean bindSold,
            @Nullable ResourceLocation requires, @Nullable ShopCondition condition) {
    }

    /**
     * A row as the buyer sees it. {@code builtIn}: one of Towntopia's rows. {@code changed}: TownKit sells it (every
     * addon row, and a Towntopia row an addon adjusted or whose currency an addon switched).
     */
    public record Row<I>(ResourceLocation id, I item, Component name, @Nullable Component description, int price, I currency,
            boolean boundCurrency, boolean bindSold, boolean available, @Nullable Component reason, boolean builtIn, boolean changed) {
    }

    /** What {@link #compute} asks of the world. */
    public interface Env<I> {
        Village village();

        @Nullable
        ServerPlayer buyer();

        /** Towntopia's currency. */
        I emerald();

        /** An addon row's price after Towntopia's growth (village size and how many this village bought). */
        int grown(ResourceLocation addonId, int basePrice);

        /** Whether the village has a valid building of this type. */
        boolean hasBuilding(ResourceLocation type);

        /** What a greyed addon row shows when its item supplier throws. */
        default I broken() {
            return emerald();
        }
    }

    private record Reg<T>(String owner, T value) {
    }

    private record AdjustReg<I>(String owner, I item, ShopAdjust adjust) {
    }

    private record State<I>(List<Reg<AddonRow<I>>> addons, List<AdjustReg<I>> adjusts, List<Reg<Supplier<I>>> currencies) {
        boolean isEmpty() {
            return addons.isEmpty() && adjusts.isEmpty() && currencies.isEmpty();
        }
    }

    private final BiConsumer<ResourceLocation, Throwable> onFailure;
    private final Runnable onChange;
    private final Object lock = new Object();
    private final Set<ResourceLocation> failed = ConcurrentHashMap.newKeySet();
    private volatile State<I> state = new State<>(List.of(), List.of(), List.of());

    /** {@code onChange} runs after each change (outside the lock), e.g. to attach or detach hooks. */
    public ShopTable(BiConsumer<ResourceLocation, Throwable> onFailure, Runnable onChange) {
        this.onFailure = onFailure;
        this.onChange = onChange;
    }

    public boolean isEmpty() {
        return this.state.isEmpty();
    }

    /** Throws IllegalArgumentException when a live addon row already has this id. */
    public Subscription add(String owner, AddonRow<I> row) {
        Reg<AddonRow<I>> reg = new Reg<>(owner, row);
        synchronized (this.lock) {
            State<I> s = this.state;
            for (Reg<AddonRow<I>> r : s.addons) {
                if (r.value.id().equals(row.id())) {
                    throw new IllegalArgumentException("shop entry " + row.id() + " is already added (by " + r.owner + ")");
                }
            }
            this.failed.remove(row.id());
            this.state = new State<>(plus(s.addons, reg), s.adjusts, s.currencies);
        }
        this.onChange.run();
        return subscription(() -> new State<>(minus(this.state.addons, reg), this.state.adjusts, this.state.currencies));
    }

    public Subscription adjust(String owner, I builtIn, ShopAdjust adjust) {
        AdjustReg<I> reg = new AdjustReg<>(owner, builtIn, adjust);
        synchronized (this.lock) {
            State<I> s = this.state;
            this.state = new State<>(s.addons, plus(s.adjusts, reg), s.currencies);
        }
        this.onChange.run();
        return subscription(() -> new State<>(this.state.addons, minus(this.state.adjusts, reg), this.state.currencies));
    }

    /** Towntopia's rows cost {@code coin}; the last registered switch wins. */
    public Subscription currency(String owner, Supplier<I> coin) {
        Reg<Supplier<I>> reg = new Reg<>(owner, coin);
        synchronized (this.lock) {
            State<I> s = this.state;
            this.state = new State<>(s.addons, s.adjusts, plus(s.currencies, reg));
        }
        this.onChange.run();
        return subscription(() -> new State<>(this.state.addons, this.state.adjusts, minus(this.state.currencies, reg)));
    }

    /** The owner of the currency switch in force; null when Towntopia's rows cost emeralds. */
    @Nullable
    public String currencyOwner() {
        List<Reg<Supplier<I>>> c = this.state.currencies;
        return c.isEmpty() ? null : c.get(c.size() - 1).owner;
    }

    /** Whether TownKit sells Towntopia's row for {@code builtIn} (an addon adjusted it or switched the currency). */
    public boolean changes(I builtIn) {
        State<I> s = this.state;
        if (!s.currencies.isEmpty()) return true;
        for (AdjustReg<I> a : s.adjusts) {
            if (a.item.equals(builtIn)) return true;
        }
        return false;
    }

    /** The live addon row with this id, or null. */
    @Nullable
    public AddonRow<I> addon(ResourceLocation id) {
        for (Reg<AddonRow<I>> r : this.state.addons) {
            if (r.value.id().equals(id)) return r.value;
        }
        return null;
    }

    /** The rows a buyer sees: Towntopia's (in its order, hidden ones left out), then the addon rows in registration order. */
    public List<Row<I>> compute(List<BuiltInRow<I>> builtIn, Env<I> env) {
        State<I> s = this.state;
        boolean switched = !s.currencies.isEmpty();
        I currency = env.emerald();
        Throwable currencyFailure = null;
        if (switched) {
            try {
                currency = Objects.requireNonNull(s.currencies.get(s.currencies.size() - 1).value.get(), "currency supplier returned null");
            } catch (Throwable t) { // fail closed: the switched rows grey, they are never sold for emeralds
                currencyFailure = t;
            }
        }
        List<Row<I>> out = new ArrayList<>(builtIn.size() + s.addons.size());
        for (BuiltInRow<I> b : builtIn) {
            boolean changed = switched;
            int price = b.grownPrice();
            ShopCheck check = ShopCheck.ok();
            if (currencyFailure != null) {
                fail(b.id(), currencyFailure);
                check = failedCheck();
            }
            boolean hidden = false;
            ShopContext ctx = new ShopContext(env.village(), env.buyer(), b.basePrice(), b.grownPrice());
            for (AdjustReg<I> a : s.adjusts) {
                if (!a.item.equals(b.item())) continue;
                changed = true;
                if (a.adjust.hidden()) hidden = true;
            }
            if (hidden) continue;
            for (AdjustReg<I> a : s.adjusts) {
                if (!a.item.equals(b.item())) continue;
                if (a.adjust.price().isPresent()) {
                    Integer p = price(b.id(), a.adjust.price().get(), ctx);
                    if (p == null) check = failedCheck();
                    else price = p;
                }
            }
            for (AdjustReg<I> a : s.adjusts) {
                if (!check.available()) break;
                if (a.item.equals(b.item()) && a.adjust.condition().isPresent()) {
                    check = check(b.id(), a.adjust.condition().get(), ctx);
                }
            }
            if (!check.shown()) continue;
            out.add(new Row<>(b.id(), b.item(), b.name(), null, price, currency, true, true, check.available(),
                check.reason().orElse(null), true, changed));
        }
        for (Reg<AddonRow<I>> r : s.addons) {
            AddonRow<I> a = r.value;
            int grown = a.priceGrowth() || a.price() != null ? env.grown(a.id(), a.basePrice()) : a.basePrice();
            ShopContext ctx = new ShopContext(env.village(), env.buyer(), a.basePrice(), grown);
            ShopCheck check = ShopCheck.ok();
            int price = a.priceGrowth() ? grown : a.basePrice();
            if (a.price() != null) {
                Integer p = price(a.id(), a.price(), ctx);
                if (p == null) check = failedCheck();
                else price = p;
            }
            if (check.available() && a.requires() != null && !env.hasBuilding(a.requires())) {
                check = ShopCheck.greyed(Component.translatable("townkit.shop.needs_building", a.requires().toString()));
            }
            if (check.available() && a.condition() != null) {
                check = check(a.id(), a.condition(), ctx);
            }
            if (!check.shown()) continue;
            I item = env.broken();
            Component name = Component.literal(a.id().toString());
            I coin = env.emerald();
            try {
                item = Objects.requireNonNull(a.item().get(), "item supplier returned null");
                name = Objects.requireNonNull(a.name().get(), "name supplier returned null");
                coin = Objects.requireNonNull(a.currency().get(), "currency supplier returned null");
            } catch (Throwable t) { // an unregistered DeferredItem, a missing optional dependency: grey the row
                fail(a.id(), t);
                check = failedCheck();
            }
            out.add(new Row<>(a.id(), item, name, a.description(), price, coin, a.boundCurrency(),
                a.bindSold(), check.available(), check.reason().orElse(null), false, true));
        }
        return List.copyOf(out);
    }

    private static ShopCheck failedCheck() {
        return ShopCheck.greyed(UNAVAILABLE);
    }

    @Nullable
    private Integer price(ResourceLocation id, ShopPrice price, ShopContext ctx) {
        try {
            return Math.max(0, price.price(ctx));
        } catch (Throwable t) { // an addon's Error (a missing optional dependency) must not break the shop
            fail(id, t);
            return null;
        }
    }

    private ShopCheck check(ResourceLocation id, ShopCondition condition, ShopContext ctx) {
        try {
            ShopCheck c = condition.check(ctx);
            if (c != null) return c;
            fail(id, new NullPointerException("condition returned null"));
        } catch (Throwable t) {
            fail(id, t);
        }
        return failedCheck();
    }

    private void fail(ResourceLocation id, Throwable t) {
        if (this.failed.add(id)) this.onFailure.accept(id, t);
    }

    private Subscription subscription(Supplier<State<I>> without) {
        return new Subscription() {
            private boolean removed;

            @Override
            public void remove() {
                synchronized (ShopTable.this.lock) {
                    if (this.removed) return;
                    this.removed = true;
                    ShopTable.this.state = without.get();
                }
                ShopTable.this.onChange.run();
            }

            @Override
            public boolean isDisabled() {
                return false;
            }
        };
    }

    private static <T> List<T> plus(List<T> list, T value) {
        List<T> out = new ArrayList<>(list);
        out.add(Objects.requireNonNull(value));
        return List.copyOf(out);
    }

    /** Removes {@code value} by identity (two registrations may be equal records). */
    private static <T> List<T> minus(List<T> list, T value) {
        List<T> out = new ArrayList<>(list.size());
        for (T t : list) {
            if (t != value) out.add(t);
        }
        return List.copyOf(out);
    }
}
