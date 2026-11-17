# Events

`townkit.api.event.TownKitEvents` holds every typed event. Each is a `TypedEvent<T>`:

```java
Subscription s = TownKitEvents.ACTIVITY_CHANGE.listen("myaddon",
        e -> LOG.info("{}: {} -> {}", e.villager().id(), e.from(), e.to()));

if (!TownKitEvents.VILLAGE_FOUNDED.available()) {
    LOG.warn("hooks are off: no founding events");
}
```

- `listen(owner, handler)` returns a `Subscription`. `remove()` detaches the handler.
- `available()` says whether TownKit can deliver the event right now. It is false when Towntopia changed the code the event watches, or when hooks are off and the event is built on hooks. Then `listen` logs a warning and returns a subscription that is already disabled.
- A handler that throws 10 times in a row is switched off (`isDisabled()`).
- Events carry TownKit's types (`Villager`, `Village`, `BuildingInfo`, ids like `towntopia:farmer`), never Towntopia's classes.
- Hooks behind an event are attached only while something listens.

## Villager events

| Event | Record | Fires when |
|---|---|---|
| `ACTIVITY_CHANGE` | `ActivityChange(villager, from, to)` | A villager's activity changes. Activity names as strings, `"none"` when idle. |
| `PROFESSION_CHANGE` | `ProfessionChange(villager, from, to)` | A villager's job changes. Ids like `towntopia:farmer`. Server thread only. |
| `DAY_ROLLOVER` | `DayRollover(villager, village)` | Towntopia rolls a villager over to a new day. |
| `VILLAGER_REMOVED` | `VillagerRemoved(villager, village)` | Towntopia forgets a villager (it died or left for good). |
| `ADDON_ACTIVITY_STARTED` | `AddonActivityStarted(villager, id)` | An addon activity starts. See [Villager behaviour](villagers-and-brain.md). |
| `ADDON_ACTIVITY_STOPPED` | `AddonActivityStopped(villager, id, reason)` | It stops, with a `StopReason`. |

`PROFESSION_CHANGE` details: Towntopia sets a villager's profession again while it loads from disk, on a loading thread. That isn't a change, so TownKit skips it. A villager that already had a job when your addon was installed never reports it: read `Villager.profession()` instead. A villager that loads on the server thread (a change of dimension, `/summon` with NBT) does report, from `towntopia:none` to its job. Treat `none` to a job as "has this job", not as a promotion.

## Village events

| Event | Record | Fires when |
|---|---|---|
| `VILLAGE_FOUNDED` | `VillageFounded(village, door)` | A player founded a village, or Towntopia took over a vanilla village. After Towntopia finished founding it. Not for villages restored from a save. Needs hooks on. |
| `VILLAGE_STATE_CHANGED` | `VillageStateChanged(village, from, to)` | The village's `VillageState` changed, for example ACTIVE to DORMANT when no player is near. |
| `RAID_STAGE_CHANGED` | `RaidStageChanged(village, from, to)` | The `RaidStage` changed. Stages can be skipped. |
| `RAID_STARTED` | `RaidStarted(village, stage)` | The stage left IDLE. `stage` is the first one seen. |
| `RAID_ENDED` | `RaidEnded(village)` | The stage went back to IDLE. |

- The last four are **polled once a second**, so they arrive up to a second late. A village's first reading is silent. A raid that starts and ends between two checks fires nothing, and a listener attached during a raid can see `RAID_ENDED` with no `RAID_STARTED`.
- They keep working with hooks off.
- A quick wake from dormant is two events (DORMANT to RECONCILING, then RECONCILING to ACTIVE), which can arrive together. Use `to()` rather than a fresh `village.state()` in the handler.
- `RAID_ENDED` carries no result: Towntopia has no structured read of how a raid ended.
- A vanilla raid Towntopia adopted reads as ASSAULT for the whole fight.
- There is no village-removed event: Towntopia never removes villages. A fallen one goes INACTIVE, which `VILLAGE_STATE_CHANGED` reports.

## Building events

| Event | Record | Fires when |
|---|---|---|
| `BUILDING_ADDED` | `BuildingAdded(village, building, how)` | A building is added. `AddedHow`: `PLACED`, `SCANNED`, `RESTORED` (also for buildings restored from a save), `UNKNOWN`. |
| `BUILDING_REMOVED` | `BuildingRemoved(village, building)` | A building is removed. |
| `BUILDING_VALIDITY_CHANGED` | `BuildingValidityChanged(village, building, valid, problem)` | Valid turned invalid or back, with Towntopia's reason. |
| `BUILDING_BLOCKS_CHANGED` | `BuildingBlocksChanged(village, building, added, removed)` | Working block positions changed. |
| `BUILDING_CHANGE` | `BuildingChange(village, building, change)` | **Deprecated** since 0.5.0, still delivered. Use `BUILDING_ADDED` and `BUILDING_REMOVED`. |

No event fires for a re-check that changes nothing. Handlers can run inside Towntopia's own loops over buildings: don't add or remove Towntopia buildings (or their frames or tokens) from a handler. Defer that to the next tick with `server.execute(...)`. See [Buildings](buildings.md).

## Shop, bubble and move events

These can be cancelled or changed. Each needs hooks on.

| Event | Type | What you can do |
|---|---|---|
| `SHOP_BUYING` | `ShopBuying` (`village()`, `buyer()`, `offer()`) | `cancel(reason)` stops the purchase, nothing taken. Fires before every purchase, Towntopia's rows too. See [Mayor shop](shop.md). |
| `SHOP_BOUGHT` | `ShopBought(village, buyer, offer, given)` | Read only. Fires once paid out. |
| `BUBBLE_SHOWING` | `BubbleShowing` (`villager()`, `icon()`, `kind()`, `ticks()`) | `cancel()` or `replace(icon)`. Before every bubble. See [Mood icons](mood-icons.md). |
| `MOVE_STARTING` | `MoveStarting` (`villager()`, `move()`) | `cancel()` or `replace(move)`. Before a move Towntopia sets, and before each `play`. See [Animations and props](animations-and-props.md). |

Later handlers see what earlier handlers replaced. For `SHOP_BUYING` the first cancel wins.
