# Villages and villagers

`townkit.api.TownKitVillages` gives you handles (see [Concepts](concepts.md#handles)):

```java
for (Village v : TownKitVillages.all(server)) {
    LOG.info("{} owned by {}: {} villagers, state {}", v.id(), v.ownerName().orElse("nobody"),
            v.villagers().size(), v.state());
}
TownKitVillages.at(level, pos).ifPresent(v -> LOG.info("inside {}", v.id()));
TownKitVillages.of(entity).ifPresent(villager -> LOG.info("{} is a {}", villager.id(), villager.profession()));
```

| Method | Gives |
|---|---|
| `TownKitVillages.all(server)` | Every village |
| `TownKitVillages.byId(server, id)` | One village by its UUID |
| `TownKitVillages.at(level, pos)` | The village a position is in |
| `TownKitVillages.of(entity)` | The `Villager` for a Towntopia villager's entity, empty for anything else |

## `Village`

| Method | Gives |
|---|---|
| `id()`, `dimension()`, `exists()` | Identity, and whether it still exists |
| `contains(pos)` | Whether a position is inside it |
| `villagers()`, `villager(id)` | Its villagers |
| `buildings()` | `BuildingInfo` per building: frame id, type id like `towntopia:kitchen`, door |
| `owner()`, `ownerName()` | The owning player's UUID and name |
| `state()` | `VillageState`: ACTIVE, DORMANT, RECONCILING, INACTIVE |
| `bellPos()` | The bell's position |
| `raidStage()` | `RaidStage`: IDLE, WARNING, CAMP, ASSAULT, RETREAT |
| `raidInProgress()` | True during fighting or a Bad Omen countdown; false during a normal raid's WARNING and CAMP. For "any raid at all" use `raidStage() != RaidStage.IDLE`. |
| `border()` | An `AABB` copy at full world height; empty until Towntopia has a border for the village |
| `storage()` | A `VillageStorage` snapshot (below) |

All read live.

## `VillageStorage`

`Village.storage()` is a read-only snapshot of what the village's storage holds right now:

| Method | Gives |
|---|---|
| `items()`, `items(category)` | Merged stacks (copies), all or per `StorageCategory` |
| `count(item)` | How many of one item |
| `ownEmeralds()`, `otherVillageEmeralds()`, `otherVillageEmeraldsByVillage()`, `plainEmeralds()` | Emeralds bound to this village, to other villages, and unbound |
| `storageBuildings()`, `unreadBuildings()` | How many storage buildings there are, and how many were partly skipped |

- It reads only chunks that are already loaded, never loads one and never takes anything. Storage in unloaded chunks is skipped and counted in `unreadBuildings()`.
- A container two storage buildings share counts once.
- A gone village gives an empty snapshot.
- `StorageCategory` follows Towntopia's own sorting: FOOD, CROP, SEEDS, RAW_MEAT, WOOD, STONE, ORES, TOOLS, ARMOR, ENCHANTS, MISC.

## `Villager`

| Method | Gives |
|---|---|
| `id()` | Towntopia's villager id (UUID) |
| `exists()`, `loaded()` | Whether it still exists, and whether its body is loaded |
| `profession()` | An id like `towntopia:farmer`; `towntopia:none` while unloaded (Towntopia keeps the job on the entity) |
| `activity()` | The current activity's name, `"none"` when idle |
| `hunger()`, `wellbeing()`, `sad()` | Needs and mood |
| `moving()`, `position()` | Whether it walks, and where it is |
| `village()`, `entity()` | Its village and its entity |
| `addWellbeing(delta)`, `addHunger(delta)` | Change needs (100 = full). Loaded villagers only: they return false otherwise. |
| `data(key)`, `setData(key, value)`, `removeData(key)` | Your own saved data, see [Villager behaviour](villagers-and-brain.md#villager-data) |

## Towntopia's own objects

Need Towntopia's object behind a handle? `townkit.api.hooks.Unwrap` (experimental) hands back the `VillageManager`, `VillagerModel` or `NpcEntity`. Code using it can break on a Towntopia update. See [Experimental hooks](experimental-hooks.md).
