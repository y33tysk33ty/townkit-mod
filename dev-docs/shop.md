# Mayor shop

`townkit.api.shop.TownKitShop` lets you sell your own items in the Mayor's shop and change Towntopia's own rows.

```java
// a cake for 6 of your coins, fixed price, greyed out until the village has a kitchen
Subscription cake = TownKitShop.add("myaddon",
        ShopEntry.builder(ResourceLocation.parse("myaddon:village_cake"), () -> Items.CAKE, 6)
                .name(Component.translatable("myaddon.shop.cake"))
                .description(Component.translatable("myaddon.shop.cake.desc"))
                .currency(MyItems.COIN)
                .priceGrowth(false)
                .requires(ResourceLocation.parse("towntopia:kitchen"))
                .condition(ctx -> atWar(ctx.village()) ? ShopCheck.greyed(Component.literal("Not during a war")) : ShopCheck.ok())
                .build());

// Towntopia's farmer token costs twice as much
TownKitShop.adjust("myaddon", farmerToken, ShopAdjust.builder().price(ctx -> ctx.grownPrice() * 2).build());

// refuse some buyers
TownKitEvents.SHOP_BUYING.listen("myaddon", e -> {
    if (banned(e.buyer())) e.cancel(Component.literal("The Mayor won't sell to you"));
});
```

## Your own rows

`TownKitShop.add(owner, ShopEntry)`. `ShopEntry.builder(id, item, basePrice)` then:

| Builder method | Does |
|---|---|
| `name(component)`, `description(component)` | Name and hover text, resolved on the client. Default: the item's name. |
| `priceGrowth(on)` | On by default: Towntopia's growth with village size and with how many this village bought. Towntopia's caps apply: the size factor stops at x3.5 and the bought factor at x1.5, so at most x5.25 the base price. `false` for a fixed price. |
| `price(ShopPrice)` | Your own price per village and buyer. `ShopContext` gives `village()`, `buyer()`, `basePrice()`, `grownPrice()`. A negative result counts as 0. |
| `currency(item)` | Pay in your own coin. Emeralds by default. |
| `boundCurrency(on)`, `bindSold(on)` | By default only coins bound to the buyer's village count, and the sold item is bound to the village, as Towntopia does. Switch either off. |
| `requires(buildingType)` | Greyed until the village has a valid building of that type (`towntopia:kitchen`) |
| `condition(ShopCondition)` | Checked after `requires`. Answer `ShopCheck.ok()`, `hidden()` (not listed, not buyable) or `greyed(reason)` (listed greyed, the reason as tooltip and refusal text). |

- Ids may not use the `towntopia` namespace, and two live rows can't share an id.
- Addon rows come after Towntopia's rows, in the order added.
- Prices and conditions run on the server thread whenever the list is built or a purchase is checked. Keep them cheap.

## Towntopia's rows

`TownKitShop.adjust(owner, item, ShopAdjust)` changes a Towntopia row, keyed by the row's item (for example a profession token):

| `ShopAdjust.builder()` method | Does |
|---|---|
| `price(ShopPrice)` | A new price. `ctx.grownPrice()` is what Towntopia would ask. |
| `condition(ShopCondition)` | Grey it out or hide it |
| `hidden(true)` | Hide it |

Several adjustments of one row combine: any hide hides it, every condition must pass, the last registered price wins.

`TownKitShop.builtInCurrency(owner, coin)` makes Towntopia's own rows cost your coin (bound, like Towntopia's emeralds). The replacement Town Hall token stays on emeralds. If two addons do this, the last registered wins (a warning is logged), and removing it brings back the one before.

## Reading the shop

`TownKitShop.offers(village, buyer)` gives the rows a buyer would see, greyed rows included, as `ShopOffer` (id, item, name, price, currency, available, reason). Towntopia's rows have ids `towntopia:<item path>`. Server thread only.

## Events

- `SHOP_BUYING` fires before every purchase (Towntopia's rows and addon rows), once the row is available and the buyer holds enough. Nothing is taken yet. `cancel(reason)` stops it: nothing taken or given, and the buyer is told the reason. The first cancel wins.
- The money is counted again after the event. A handler that moves or spends the buyer's coins makes a TownKit-sold row refuse as "not enough", with nothing taken.
- Towntopia may still refuse one of its untouched rows afterwards for its own reasons (a Captain token with no open Barracks). Then no `SHOP_BOUGHT` follows.
- `SHOP_BOUGHT` fires once paid out, with a copy of the item given.
- Both need hooks on.

## Safety

- A price or condition that throws (or a condition returning null), or an item, name or currency supplier that throws (an unregistered `DeferredItem`, say), greys the row with the reason `townkit.shop.unavailable`. It's logged once per row, and the shop still opens.
- A `builtInCurrency` supplier that throws greys every Towntopia row it switched.
- **The shop fails closed.** While anything is registered, a purchase that fails inside TownKit is refused ("The Mayor can't sell that right now."). It's never handed to Towntopia's own sale at its emerald price.

## Clients

With TownKit, the shop screen shows each row's currency, greys unavailable rows with the reason as tooltip, and shows an addon row's description. A client without TownKit still buys under the server's rules, but draws emeralds for every row, can't buy coin rows from its screen, and shows no proper icon for addon rows.

## Nothing registered

With nothing registered and no shop event listened to, the shop is Towntopia's own and TownKit attaches nothing. With every hook off, registering logs once that the shop stays Towntopia's.

## Tests to read

`ShopTests` (in game).
