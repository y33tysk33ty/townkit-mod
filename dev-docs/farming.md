# Farming

`townkit.api.farm.TownKitFarming` lets an addon, or a data pack, tell Towntopia's **farmer** how to grow and pick other mods' crops. TownKit opens the door and you decide: Towntopia still finds, plans, plants and shows every crop from its own `farmer_crops` files. TownKit changes only what counts as ripe, how the farmer picks it, where seeds come from, and which drops can roll the skill bonus.

Every call takes an `owner` (a blank one throws) and returns a `Subscription`; `remove()` undoes it. Register at mod setup or later, from any thread. With nothing registered, Towntopia's farmer runs untouched. With hooks off, the read methods still answer but Towntopia keeps its own behaviour.

Data files are `data/<ns>/townkit/<kind>/*.json` with `"action": "add"`, `"change"` or `"remove"`, merged as in [Work recipes](work-recipes.md#how-the-lists-merge): Java first, then files in file-id order, a removal wins, each clash logged once, a bad entry skipped with one log line, everything re-read on `/reload`.

| Kind | Java |
|---|---|
| `farm_seeds` | `TownKitFarming.seedFrom`, `removeSeedFrom`, `seedSources` |
| `farm_harvest` | `TownKitFarming.harvest`, `changeHarvest`, `removeHarvest`, `harvests`, `harvestFor` |

## Bonus produce

The farmer's skill bonus is a chance of one extra item on a harvest, rolled only for seven vanilla items. The item tag `townkit:farmer_bonus_produce` (empty by default) adds more: the first drop of a harvest that is in the tag may roll the bonus. It is data only, a tag file, with no Java.

## Seeds from produce

```java
// 1 corn makes 2 kernels, like Towntopia's melon and pumpkin seeds
TownKitFarming.seedFrom("mymod", MyItems.CORN_KERNELS.get(), MyItems.CORN.get(), 2);
```

```json
{"action": "add", "seed": "mymod:corn_kernels", "produce": "mymod:corn", "seeds_per_produce": 2}
```

- When a field wants the seed and neither the farmer nor any storage has any, she fetches or uses the produce and makes up to 16 seeds. It is for crops whose harvest gives no seeds.
- The seed must be a planting item of a `farmer_crops` definition. One that isn't is kept but never planted (logged once after the server starts).
- Melon and pumpkin seeds stay Towntopia's: adding or removing them is skipped (logged). A count below 1 is skipped (logged). Adding a seed that has an entry replaces it whole (logged).
- A `change` file sets `produce` and `seeds_per_produce` (each one present replaces it); a `remove` file names the `seed`.
- `seedSources()` lists the addon entries only.
- The away catch-up has no seed crafting (Towntopia has none).

## Harvest rules

A rule says when a crop block counts as ripe and what the farmer does to harvest it.

```java
// a blueberry bush is ripe at age 3; the farmer right-clicks it with an empty hand and the bush stays
TownKitFarming.harvest("mymod", HarvestRule.builder(id("blueberry")).block(MyBlocks.BLUEBERRY_BUSH.get())
        .ripe(new Ripe.AtLeast("age", 3)).action(new HarvestAction.Use(Holding.EMPTY_HAND))
        .away(new AwayYield(MyItems.BLUEBERRIES.get(), 2, 3, Map.of("age", "1"))).build());
```

```json
{"action": "add", "id": "mymod:blueberry", "blocks": ["mymod:blueberry_bush"], "ripe": {"property": "age", "at_least": 3},
 "harvest": {"type": "use", "holding": "empty_hand"},
 "away": {"item": "mymod:blueberries", "count": [2, 3], "picked_state": {"age": "1"}}}
```

A `change` file sets each field it names and keeps the rest (`"away": null` removes the away yield); a `remove` file names the `id`. `ripe` and `away` are optional. `count` is a number or `[min, max]`.

| Part | Choices |
|---|---|
| `blocks` | `BlockRef.of(block)` or `BlockRef.ofTag(tag)`; data: an id, or `#tag`, one or a list |
| `ripe` | `Ripe.Towntopia` (Towntopia's own rule, the default; in data, leave `"ripe"` out); `Ripe.AtLeast(property, n)` (an integer property, data `"at_least"`); `Ripe.Is(property, value)` (any property by name, data `"is"`) |
| `action` | `HarvestAction.Break` (Towntopia's own harvest, the default); `HarvestAction.Use(Holding)` with `Holding.EMPTY_HAND`, `SHEARS` or `AXE` |
| `away` | `AwayYield(item, min, max, pickedState)`: what a picked plot gives while the village isn't loaded |

- A property the block doesn't have, or a value outside its range, leaves that block out of the rule (logged once). A rule with no block left is dropped. Two rules holding the same block: the first in merge order keeps it.
- **Use**: the farmer right-clicks the block like a player and the plant stays. TownKit uses a stand-in player (one per level) and calls `useItemOn`, then `useWithoutItem` when that passes, directly: no `PlayerInteractEvent` is fired. Her own tool of the `Holding` kind goes into the stand-in's hand; its wear is written back and a broken tool is removed. Every item the block drops within one block of it is caught while the call runs, and goes into her pack through Towntopia's item ownership. A full pack sends the rest to the item loss log.
- **Break** also catches the items the break spawns, so nothing is left on the ground, whatever the shape of the crop.
- **Skill bonus**: a picked or broken crop rolls Towntopia's bonus as before. For a picked (`Use`) crop the bonus item is the first caught drop that Towntopia's list or `townkit:farmer_bonus_produce` accepts, at the farmer's own chance. A broken (`Break`) crop is Towntopia's own roll, made on the drops of the main block.
- **Away catch-up** (the village isn't loaded): the ripe rule applies there too. A `Use` rule with `away` rolls `min` to `max` of the item (Towntopia's own bonus roll applies to its seven bonus items only; the tag does not reach the away catch-up) and sets the plot to `pickedState` (block property names to values, at least one; a name or value the block doesn't have skips that plot, logged once). A `Use` rule without `away` leaves the plot ripe until the village is loaded. A `Break` rule keeps Towntopia's own away harvest.
- `changeHarvest(owner, id, change)` takes a function from the old rule to the new one; the new rule must keep the id.

## Limits

- **One modded crop per bush, hanging and log kind.** Towntopia's `FarmerPerennials.definitionFor(kind)` takes the first `farmer_crops` definition of each kind (on soil, hanging, on log) by id, for every village. A second modded bush, vine or log crop is ignored, or replaces Towntopia's own. This is a Towntopia bug TownKit can't fix, so an addon can support one modded bush, one hanging and one log crop. Field crops (`break_and_replant`) have no such limit.
- **Field-crop rules need `break_and_replant`** in the crop's `farmer_crops` file, because Towntopia only makes field harvest jobs for that strategy. A rule on a field crop whose file says `pick_keep_plant` does nothing (logged once after start). Keep `break_and_replant` there and put the picking in the rule. Bush, hanging and log crops, which Towntopia's own files call `pick_keep_plant`, work normally.
- **`Use(SHEARS)` and `Use(AXE)` need the crop file's `harvest_tool`** to name that tool, because the farmer only fetches the tool the crop file names. A mismatch is logged once after start. A farmer with no such tool at harvest time fails the job as Towntopia's own tool jobs do, and that doesn't count against the rule.
- **Two-block crops are skipped in the away catch-up.** Towntopia tracks an upper half only for the pitcher plant, so TownKit can't keep a modded crop's halves in step. Plots of a rule's block with a same-crop block directly above get no away harvest and no yield: they stay ripe and are picked once the village is loaded. Two-block crops with no rule keep Towntopia's own away behaviour.
- **A rule is switched off after ten bad answers in a row**: the block refuses the click, or is gone afterwards, which includes a crop that turns into a different block when picked (a missing tool counts as neither). It stays off, in every village, until `/reload` or a restart (logged once), and Towntopia then harvests that crop its own way.
- **A rule on a modded `on_soil` bush needs a `Use` action.** Towntopia's own `pickBerries` only handles the sweet berry bush; a rule on such a bush without `Use` logs a warning once after start.
- A rule that no `farmer_crops` definition matches does nothing (logged once after start).
- TownKit doesn't make a crop grow, plant or show: that stays Towntopia's `farmer_crops` data.

## Tests to read

The GameTests `FarmingTests` (bonus tag, seeds from produce, ripe rules, use-harvest, break capture, away catch-up) and the unit tests for the codecs and tables.
