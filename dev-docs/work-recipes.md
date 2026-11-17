# Work recipes

`townkit.api.work` lets an addon, or a data pack, change what Towntopia's **cook**, **blacksmith** and **miner** make and mine:

| Class | Covers |
|---|---|
| `TownKitCooking` | Dishes and cook stations |
| `TownKitForge` | Smith materials, gear tiers, forge recipes, smelting |
| `TownKitMining` | Ores |

Every registration takes an `owner` (a blank one throws) and returns a `Subscription`; `remove()` undoes it. Register at mod setup or later, from any thread. With nothing registered, Towntopia's work runs on its own objects, untouched. With hooks off, the read methods still answer but Towntopia keeps its own recipes.

```java
// a dish: 2 wheat and a pumpkin make 3 pies; the cook keeps 24 in storage by default
TownKitCooking.addDish("mymod", Dish.builder(MyItems.PIE.get())
        .output(3).ingredient(Items.WHEAT, 2).ingredient(Items.PUMPKIN, 1).nutrition(8).stock(24).build());
// change or remove one of Towntopia's
TownKitCooking.changeDish("mymod", Items.BREAD, DishChange.builder().ingredient(Items.WHEAT, 2).build());
TownKitCooking.removeDish("mymod", Items.CAKE);

// a smith material, a gear tier between iron and diamond, a pickaxe of it, and smelting a raw
TownKitForge.material("mymod", id("steel"), MyTags.STEEL_INGOTS);
TownKitForge.tier("mymod", id("steel"), TierPlace.after(ResourceLocation.parse("towntopia:iron")));
TownKitForge.addRecipe("mymod", ForgeRecipe.builder(MyItems.STEEL_PICKAXE.get())
        .cost(id("steel"), 3).cost(ResourceLocation.parse("towntopia:wood"), 2).gear(id("steel"), GearSlot.PICKAXE).build());
TownKitForge.smelt("mymod", MyItems.RAW_TIN.get(), MyItems.TIN_INGOT.get());

// an ore, with a share of the miner's lucky rolls
TownKitMining.ore("mymod", OreEntry.builder(id("tin"), MyBlocks.TIN_ORE.get())
        .deepslate(MyBlocks.DEEPSLATE_TIN_ORE.get()).depths(0, 64).luckyRolls(6.0, 0, 64).build());
```

## How the lists merge

- Java registrations apply first, in the order they were made. Data files apply after them, in **file-id order** within a kind (a higher pack's file with the same id replaces the lower one). Data files are re-read on `/reload`.
- An `add` over an existing key is a full replacement. When two changes clash, the last one wins, and **a removal always wins**.
- Each clash logs one line naming both owners: an add over another owner's entry, a removal of an entry another owner added or changed, and two owners changing the same entry. Changing or removing one of Towntopia's own entries is no clash.
- A bad entry (unknown item, bad number, a name nobody registered) is skipped with one log line; the rest of the file still applies.
- **Java and data files have the same power.** Whatever a data file can add, change or remove, Java can too.

## Ingredients

TownKit only changes what the cook, smith and miner do with what the village has. It never makes anyone grow, mine or craft your dish's ingredients. The cook uses the village's storage and its own pack, as for Towntopia's dishes. A crop Towntopia's farmer doesn't grow needs Towntopia's own `farmer_crops` data.

## Cook

| Method | Does |
|---|---|
| `addDish(owner, Dish)` | Add a dish (or replace one whole) |
| `changeDish(owner, result, DishChange)` | Change fields of a dish |
| `removeDish(owner, result)` | Remove a dish |
| `station(owner, id, CookStation)`, `removeStation(owner, id)` | Cook stations (below) |
| `dishes()` | The merged list |

`Dish.builder(result)` then `output(n)` (per batch, default 1), `ingredient(item, count)` (at least one), `heated(bool)` (cooked on a heat source rather than at a crafting table, default false), `nutrition(0-20)` (0 keeps the item's own, default 5), `station(id)`, `stock(n)` (default stock target, at least 1, default 16). `DishChange.builder()` has the same methods; it sets only what you call.

- Towntopia has 18 dishes. You can change or remove them, or another addon's.
- Towntopia reads the merged list everywhere it reads its own: the cook's planning and batches, the Mayor's stock orders, the storage menu, the away catch-up, the farmer's compost and the builder's crafting.
- The cook's settings: an added dish gets an "allowed recipes" switch and a stock row; a removed dish loses both. Saved settings are keyed by item id, as Towntopia keys them, and are never rewritten, so a removed dish's saved switch comes back with the dish. Stock rows are keyed by the item's path (`mymod:bread` shares `stock_bread` with `minecraft:bread`).
- A batch already started on a dish that a change or removal makes stale ends without making anything (the ingredients stay with the cook).
- Changing bread doesn't change the baker's fixed 3-wheat bread.
- A changed Towntopia dish keeps Towntopia's own "needs" text on players' settings screens, even though the cook uses the new ingredients. Added dishes show their own "needs" line.

### Cook stations

Stations are "for show": TownKit never runs the machine.

- `CookStation.builder()` then `block(block)` or `blocks(tag)`, and optionally `handler(StationHandler)`.
- A dish names a station by id (`Dish.Builder.station`). The cook makes it only while one of the village's kitchens holds a block of the station, walks there, and works there with a crafting table's batch timing.
- Your `StationHandler` gets `started(ctx)` and `stopped(ctx)` callbacks (`stopped` exactly once per `started`) for visuals. `StationContext` gives the level, the station's position, the cook and the dish.
- A station dish ignores `heated`.

## Blacksmith

| Method | Does |
|---|---|
| `material(owner, id, itemTag)`, `removeMaterial(owner, id)` | Smith materials |
| `tier(owner, id, TierPlace)`, `removeTier(owner, id)` | Gear tiers |
| `addRecipe(owner, ForgeRecipe)`, `changeRecipe(owner, result, ForgeRecipeChange)`, `removeRecipe(owner, result)` | Forge recipes |
| `smelt(owner, raw, result)`, `removeSmelt(owner, raw)` | Smelting |
| `tierOf(item)` | The tier a registered gear item is |

**Materials** are item tags, used by id in recipes. Towntopia's are `towntopia:cobble`, `iron`, `leather`, `diamond`, `netherite`, `template`, `netherrack` and `wood`; they can't be replaced or removed. Up to 16 addon materials can be in use at once (more are skipped, logged). The smith counts, fetches and uses a material like Towntopia's own.

**Tiers**: Towntopia's are `towntopia:wood`, `stone`, `iron`, `diamond` and `netherite` (they can't be removed). Place yours with `TierPlace.after(tier)` (Towntopia's or an addon's), optionally `.skill(level)`. The smith upgrades villagers through it: iron, then your tier, then diamond. A tier follows the Towntopia tier just above it for the smith's "best material" setting, and needs the smith level its place gives. A tier after a tier nobody registered is skipped (logged).

**Recipes**: `ForgeRecipe.builder(result)` then `count(n)`, `cost(material, amount)`, `gear(tier, GearSlot)`, `smithing(bool)`.

- A gear recipe (`gear(tier, slot)`) is made for the villagers who use that slot: miners' pickaxes, guards' swords and armour, and so on. Villagers wear and use it as Towntopia's own of that tier.
- A recipe without gear is a stock item, forged when a player orders it. Gear can be ordered too (Mayor stock orders).
- `smithing(true)` follows Towntopia's netherite rule: the piece is made at the forge's smithing table, and the previous tier's item is **not** used up. List the template and the upgrade material in the cost.
- Towntopia's own recipes (its tools, armour, shield, shears, bucket and template copy) can be changed or removed.
- TownKit has no recycling for addon gear.

**Smelting**: your raw is smelted like the spare raw iron and copper: the same `smelt_spare_ore` setting, batch size and fuel rules, in the village and in the away catch-up. A change works for raw iron, gold and copper; removal works for raw copper and addon entries. Removing raw iron or gold isn't supported (skipped, one log line).

## Miner

| Method | Does |
|---|---|
| `ore(owner, OreEntry)` | Add an ore |
| `changeOre(owner, id, change)` | Change one (a function from the old entry to the new; `OreEntry.toBuilder()` helps) |
| `removeOre(owner, id)` | Remove one |
| `ores()` | The merged list |

`OreEntry.builder(id, stoneBlock)` then `deepslate(block)`, `label(component)`, `depths(low, high)`, `luckyRolls(weight, low, high)`, `druid(weight, low, high)`.

- Towntopia's seven ores (coal, copper, iron, lapis, redstone, gold, diamond) stay Towntopia's: an entry that names one is skipped (logged).
- Modded ore drops need nothing: the miner digs any ore block and gets the block's own loot. TownKit makes your ore's blocks count as ore for the miner's ore bonus, vein following and ore sense.
- The miner's "wanted ores" setting gets a row for your ore. A villager that switched it on goes to the mine whose depth is closest to your `depths`.
- **Lucky rolls are opt-in, and so is the druid**: an ore without `luckyRolls` or `druid` is never rolled.
- **Weights** are on the scale of Towntopia's own placements: a placement's veins times its vein size, spread over its heights. Towntopia's coal is 30 veins of 17, a weight of 510; its gold is 4 veins of 9, a weight of 36. An ore's chance at a height is `w / (T + w)`, with `w` your ore's weight spread there and `T` the sum of Towntopia's ore densities there. Only a roll that already produced an ore can become yours.
- The mine room's map marks your ore blocks like any ore (a marker). The mine room and the town statistics have no label for addon ores.

## Data files

`data/<ns>/townkit/<kind>/*.json`, each with `"action": "add"`, `"change"` or `"remove"`. A change sets only the fields it names (`null` clears an optional one).

| Kind | Example |
|---|---|
| `cook_dishes` | `{"action":"add","result":"mymod:stew","output":1,"ingredients":{"mymod:bean":2,"minecraft:bowl":1},"heated":true,"nutrition":8,"station":"mymod:pot","stock":16}` |
| | `{"action":"change","result":"minecraft:bread","ingredients":{"minecraft:wheat":2}}` |
| | `{"action":"remove","result":"minecraft:cake"}` |
| `cook_stations` | `{"action":"add","id":"mymod:pot","blocks":"#mymod:pots"}` (a block tag, or one block id without `#`), `{"action":"remove","id":"mymod:pot"}` |
| `forge_materials` | `{"action":"add","id":"mymod:steel","items":"#c:ingots/steel"}` (a tag, or one item id without `#`) |
| `forge_tiers` | `{"action":"add","id":"mymod:steel","after":"towntopia:iron","skill":30}` (`skill` optional) |
| `forge_recipes` | `{"action":"add","result":"mymod:steel_pickaxe","count":1,"cost":{"mymod:steel":3,"towntopia:wood":2},"tier":"mymod:steel","slot":"pickaxe","smithing":false}` |
| | `{"action":"remove","result":"minecraft:bucket"}` |
| `smelting` | `{"action":"add","raw":"mymod:raw_tin","result":"mymod:tin_ingot"}` |
| `ores` | `{"action":"add","id":"mymod:tin","stone":"mymod:tin_ore","deepslate":"mymod:deepslate_tin_ore","depths":[0,64],"lucky_rolls":{"weight":6.0,"low":0,"high":64}}` (`druid` has the same shape as `lucky_rolls`) |

- `cook_dishes`: an add needs `result` and `ingredients`; the rest default as in `Dish.builder`. Ingredients in a change replace the whole list; `"station":null` takes the station away.
- Materials, tiers, stations and ores are removed by `"id"`; dishes and recipes by `"result"`; smelting by `"raw"`.
- An unknown item, a count, output or stock below 1, a number that isn't whole, or a nutrition outside 0 to 20 skips the entry with one log line.

## Settings screens

Players who have TownKit see addon dishes and ores in the cook's and miner's settings (the server sends the rows). Players without TownKit can still join, see Towntopia's own rows, and can't wipe an addon's switches.

## Tests to read

`CookingTests`, `ForgeTests` and `MiningTests` (in game), and `WorkApiContractTest` (unit: every stable method and value type).
