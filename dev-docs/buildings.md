# Buildings

`townkit.api.building.TownKitBuildings` lets modded blocks count toward Towntopia's buildings, and lets you read and watch buildings.

```java
// a modded table counts wherever Towntopia checks for a crafting table (kitchen, storage, fletcher, toolmaker)
Subscription rule = TownKitBuildings.countsAs("myaddon", MyBlocks.OAK_TABLE.get(), Blocks.CRAFTING_TABLE);
if (rule.isDisabled()) LOG.warn("counts nowhere, see the log line TownKit wrote");
TownKitBuildings.standIn(MyBlocks.OAK_TABLE.get()).ifPresent(r -> LOG.info("counts at {}", r.countsAt()));

TownKitEvents.BUILDING_ADDED.listen("myaddon", e -> LOG.info("{} added ({})", e.building().type(), e.how()));
TownKitBuildings.at(level, pos).ifPresent(b ->
        LOG.info("valid {}, problem {}, working blocks {}", b.valid(), b.problem(), b.workingBlocks()));
```

## Stand-ins: modded blocks that count

### `countsAs(owner, modded, vanilla)`

The modded block passes the checks Towntopia makes for the vanilla block, at each audited check site where that is safe.

- For a check on a class (`instanceof AbstractFurnaceBlock`, `CampfireBlock`, `AnvilBlock`...), pass the vanilla block that class stands for (`Blocks.FURNACE`, `Blocks.CAMPFIRE`, `Blocks.ANVIL`).
- Per check site, TownKit's audit says:
  - **SAFE**: always counts.
  - **NEEDS**: counts only if the block has what Towntopia needs there (a real inventory, a real `BedBlock`).
  - **UNSAFE**: never counts, because Towntopia would write the block's state or cast it to the vanilla class there.
- A rule that counts nowhere returns a disabled `Subscription` and logs why.
- Where a block counts is logged when you register, and readable with `standIn(block)`, which returns a `StandInReport` with `countsAt` and `skippedAt` (site ids, with the reason for each skip).
- Blocks built on the vanilla classes (a furnace subclass, a bed extending `BedBlock`) need no rule.

### `useCommonTags(owner)`

Off by default. While at least one such subscription is live, non-vanilla members of the shared `c:` tags that clearly mean one thing (chests, barrels, bookshelves, crafting tables, furnaces) count as the matching vanilla block, with the same per-site safety. An explicit `countsAs` rule wins over a tag. Tag reloads are followed.

### Where a stand-in really counts

Reports (`standIn`, `/townkit building`) count a site only if its check was actually rewritten:

- A site whose class hasn't loaded yet counts. It is rewritten when it loads.
- A site in a class switched off in `townkit-hooks.properties`, or whose check no longer matches the audit after a Towntopia update, is listed as skipped ("not rewritten ...").
- With every hook switched off, `countsAs` logs once that no stand-in can count.
- If another mod has already replaced a check (a `@Redirect`), TownKit leaves that spot as the other mod made it, and the stand-in doesn't apply there.

### Validity ignores working blocks

Towntopia's building validity is about the room's shape and access, so a kitchen without a crafting table is still valid. A stand-in therefore shows up in `workingBlocks()` and `BUILDING_BLOCKS_CHANGED`, not in `valid()`.

## Reading buildings

Server thread only, read-only, and never makes Towntopia scan anything:

| Method | Gives |
|---|---|
| `TownKitBuildings.at(level, pos)` | The building a position is in |
| `TownKitBuildings.ofType(village, type)` | A village's buildings of one type (`towntopia:kitchen`) |
| `TownKitBuildings.details(village, frameId)` | One building by its frame id |

Each gives `BuildingDetails`:

| Field | Means |
|---|---|
| `info()` | `BuildingInfo`: frame id, type id, door |
| `valid()`, `problem()` | Whether Towntopia counts it, and its reason as a lowercase id like `room_unreachable` |
| `roomTiles()` | The room's floor tiles |
| `workingBlocks()` | Working block positions, grouped by the vanilla block id each counts as (a modded table sits under `minecraft:crafting_table`) |

## Events

`BUILDING_ADDED`, `BUILDING_REMOVED`, `BUILDING_VALIDITY_CHANGED`, `BUILDING_BLOCKS_CHANGED`. See [Events](events.md#building-events). Don't add or remove Towntopia buildings from a handler; defer with `server.execute(...)`.

## The command

`/townkit building` (operators): stand in a building's room to see its type, whether it is valid and why not, which blocks counted for which role (and whether through a rule or a tag), and nearby blocks that didn't count, with the reason.

## How it works, and what it costs

Whenever hooks are on, Towntopia's audited block checks (`state.is(Blocks.X)` and `instanceof SomeBlock`) are rewritten as their classes load, rule or no rule. The audited sites and their verdicts are in `src/main/resources/townkit/building-sites.tsv`. TownKit only **adds** a call after Towntopia's own check (`StandIns.afterIs` / `afterInstanceOf`); it never removes Towntopia's instruction, so other mods aiming at the same spot keep working. With no rule, a rewritten check costs its original test plus, when that fails, one static boolean read. The building events' hooks attach only while something listens.

## Not included

New building types, and "dirty" sources such as explosions, pistons and fluids changing a building.

## Tests to read

`BuildingTests` (in game), and the unit tests in `src/test/java/townkit/core/building/` (audit, rewriter, stand-in table).
