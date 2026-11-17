# Changelog

Stable API (`townkit.api`): before 1.0.0, a minor release may break it, and every break is listed here. Anything removed is deprecated for at least one minor release first. The experimental hooks (`townkit.api.hooks`) follow Towntopia's code and may change with any Towntopia update.

## 0.7.2 (2026-11-17)

Open source release. **No API or gameplay changes**: worlds behave exactly as with 0.7.1.

- **License:** TownKit is now licensed under the GNU LGPL v3.0 only (`LGPL-3.0-only`; before: all rights reserved). `LICENSE` (LGPL-3.0) and `COPYING` (GPL-3.0) are in the repository and inside the jar (`META-INF/LICENSE`, `META-INF/COPYING`).
- **Source on GitHub**, with a player guide (`docs/`) and a developer guide (`dev-docs/`).
- **Tests:** the five audit unit tests that read the old Towntopia 0.1.2 jar are skipped when it isn't in `libs/`, so a fresh `./gradlew build` passes with only Towntopia 0.1.3.

## 0.7.1 (2026-11-17)

Work recipes: addons and data packs can change what Towntopia's cook, blacksmith and miner make and mine. Made for **Towntopia 0.1.3**. No breaking changes. With nothing registered, worlds behave as before.

- **Cook** (`TownKitCooking`): add dishes, change or remove Towntopia's 18 (or another addon's), per-dish stock target (default 16), and addon cook stations "for show" (the addon gets started and stopped callbacks).
- **Blacksmith** (`TownKitForge`): smith materials, gear tiers placed relative to other tiers, forge recipes for gear and stock items (`smithing(true)` follows Towntopia's netherite rule), `tierOf`. Addon gear can be ordered from the Mayor's stock orders.
- **Smelting** (`TownKitForge.smelt`, `removeSmelt`): addon raws are smelted like spare raw iron and copper.
- **Miner** (`TownKitMining`): addon ores with depths, an opt-in share of the miner's lucky rolls and of the druid, on the same weight scale as Towntopia's placements. They get a row in the miner's "wanted ores" settings.
- **Data files** `data/<ns>/townkit/<kind>/*.json` (`cook_dishes`, `cook_stations`, `forge_materials`, `forge_tiers`, `forge_recipes`, `smelting`, `ores`) with `add`, `change` or `remove`, applied after Java registrations. Java has the same power (`removeStation`, `removeMaterial`, `removeTier`, `changeOre`, `removeOre`).
- **Settings screens**: players with TownKit see addon dishes and ores in the cook's and miner's settings. Players without TownKit can join and can't wipe addon switches.
- The prop layer is now only in the renderers while a villager's move has props.
- A blank animation swap target is refused.
- **Fixed a crash with other mods that change the same spots in Towntopia** (for example TownTopia Integrations: opening a storage room's Stock page crashed). TownKit now only adds code around Towntopia's checks and never removes Towntopia's own instructions.

## 0.7.0 (2026-11-10)

Four new stable modules: the **Mayor shop**, **mood icons**, **skins and looks**, and **animations with props**. Art comes from data files; behaviour from code. TownKit ships no art. No breaking changes.

- **Mayor shop** (`townkit.api.shop`): sell any item with a base price (Towntopia's price growth, a fixed price, or a price per village and buyer), building requirements and conditions (ok, hidden, greyed with a reason), any currency item. Change Towntopia's own rows, make them cost a coin, read the offers. Events `SHOP_BUYING` (cancellable) and `SHOP_BOUGHT`. Fails closed: a purchase that fails inside TownKit is refused, never sold at the wrong price.
- **Mood icons** (`townkit.api.bubble`): addon icons, pop-ups, ongoing states with priorities, hiding or swapping Towntopia's icons, idle thoughts. Event `BUBBLE_SHOWING`.
- **Skins and looks** (`townkit.api.skin`): addon looks from data files (also plain resource packs), picked per player on the Theme page; skins for single villagers, saved or temporary.
- **Animations and props** (`townkit.api.anim`): play moves once or in a loop (saved loops survive unloads), replace Towntopia's moves globally or per villager, props drawn on bones. Events `MOVE_STARTING` and `onClientMove`.
- Missing art never crashes: Towntopia's normal look is drawn and one line is logged.
- **Behaviour change:** `PROFESSION_CHANGE` no longer fires off the server thread (Towntopia sets professions again while loading villagers from disk; that isn't a change).

## 0.6.2 (2026-11-08)

- **Village reads**: `Village.owner()`, `ownerName()`, `state()` (`VillageState`), `bellPos()`, `raidStage()` (`RaidStage`), `raidInProgress()`, `border()`.
- **Storage view**: `Village.storage()`, a read-only snapshot of the village's storage (per category, counts, emeralds).
- **Village events**: `VILLAGE_FOUNDED`, `VILLAGE_STATE_CHANGED`, `RAID_STAGE_CHANGED`, `RAID_STARTED`, `RAID_ENDED` (the last four polled once a second).
- **Behaviour change with hooks off:** every event built on hooks now reports `available()` false, and `listen` warns and gives a disabled subscription (before, it silently never fired).
- Six experimental hooks restored for a war-style addon (`ClericCare#candidates`, `VillagerGeoModel#rigSkin`, `MayorSpawnManager#ensureMayor`, both `GuardThreatEvents#resolveManager`, `VillageCrowding#isInside`).

## 0.6.1 (2026-11-04)

- **Hook trim (experimental tier, breaking):** 19,037 hooks became 7,924. Cut: private methods, plain getters and setters, data-class constructors, packet plumbing. Hooks TownKit itself uses are always kept. Registering a cut key now throws; check with `TownKitHooks.find` first. A cut hook can be brought back with a `keep` rule in `hookgen/hook-rules.txt`.
- Fixed: a transport switch-off in the middle of a link recompute could still add links afterwards.
- Fixed: a second rewrite pass of a Towntopia class could hide rewritten sites from reports.
- Fixed: a ride whose walk was dropped now ends within 40 ticks instead of waiting for its timeout.
- The release jar no longer contains test classes.

## 0.6.0 (2026-11-03)

Pathfinding in the stable tier (`townkit.api.nav`). TownKit itself changes nothing until an addon registers something.

- **Path rules**: `TownKitPaths.rule(owner, block, PathRule)` with cost, speed and never.
- **Transport**: transport kinds, stops marked by items in item frames, links in Towntopia's route map, rides with safe fallbacks.
- **Bubble elevator**: a ready-made transport kind, off until a mod enables it.

## 0.5.1 (2026-11-03)

- A rewritten building check with no stand-in rule costs only one extra boolean read.
- A method whose block checks changed after a Towntopia update stays as Towntopia ships it.
- A stand-in rule that can count nowhere is refused.

## 0.5.0 (2026-10-31)

Buildings in the stable tier (`townkit.api.building`).

- **Stand-in rules**: `TownKitBuildings.countsAs(owner, modded, vanilla)`, `useCommonTags(owner)`, `standIn(block)`.
- **Read API**: `TownKitBuildings.at`, `ofType`, `details` give `BuildingDetails`.
- **Events**: `BUILDING_ADDED`, `BUILDING_REMOVED`, `BUILDING_VALIDITY_CHANGED`, `BUILDING_BLOCKS_CHANGED`.
- **Command**: `/townkit building`.
- **Deprecated**: `BUILDING_CHANGE` (use `BUILDING_ADDED` and `BUILDING_REMOVED`).

## 0.4.1 (2026-10-31)

- An `ARRIVED` follow goes back to `MOVING` when the target gets more than 4 blocks away.
- A run whose `start` threw fires no started or stopped event (it still gets `stop(FAILED)`).
- `ActivityContext.village()` is `@Nullable`.

## 0.4.0 (2026-10-30)

Villager behaviour in the stable tier (`townkit.api.brain`, `townkit.api.villager`): addon activities, scored choices, interrupts, `ActivityContext.moveTo`, `Villager.addWellbeing` and `addHunger`, `VillagerDataKey`, events `VILLAGER_REMOVED`, `ADDON_ACTIVITY_STARTED`, `ADDON_ACTIVITY_STOPPED`. `/townkit perf` counts addon provider time.

## 0.3.0 (2026-10-26)

The API gets tiers: **stable** (`townkit.api`, TownKit's own types) and **experimental** raw hooks (`townkit.api.hooks`). Breaking changes:

- Raw hooks moved to `townkit.api.hooks` (`TownKitHooks`, `HookCall`, `HookListener`, `HookRegistration`, `HookPoint`, `Unwrap`).
- `TownKitVillages` returns `Village` and `Villager` handles instead of Towntopia's objects.
- Typed events carry TownKit's types and return `Subscription`; `listen` on an event that can't be delivered warns and returns a disabled subscription instead of throwing.
- Towntopia's private fields are read fail-soft: a renamed field makes the feature that needs it unavailable instead of crashing.
- Calling the API before TownKit has started throws `IllegalStateException`.
