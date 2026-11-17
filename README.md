# TownKit

**TownKit is the library mod for Towntopia addons** (Minecraft 1.21.1, NeoForge, mod id `townkit`).

Towntopia makes villages come alive. TownKit lets other mods change those villages, including what villagers do, what counts as a building, how villagers travel, the Mayor's shop, mood bubbles, skins, animations, and what the cook, blacksmith and miner make. Addons get this without touching Towntopia's code themselves. TownKit holds every change to Towntopia's code in one place, so when Towntopia updates, it gets fixed once, in TownKit, instead of in every addon.

On its own TownKit changes almost nothing in your game.

## For players

- **You need:** Minecraft 1.21.1, NeoForge 21.1.238 or newer, Towntopia 0.1.3 or newer (TownKit 0.7.2), and GeckoLib.
- **Install:** put the jar in your `mods` folder, on the server and on each player's game.
- **Read more:** the [player guide](docs/README.md) covers installing, what changes in your game, commands, settings, compatibility and an FAQ.

## For addon developers

TownKit gives you a **stable API** in its own types, so your addon never touches Towntopia's classes:

| Area | You can |
|---|---|
| [Events](dev-docs/events.md) | Listen for activity and profession changes, new days, villages founded, village state changes, raids, buildings, shop purchases, bubbles and moves |
| [Villages and villagers](dev-docs/villages.md) | Read villages, villagers, owners, borders, raid stages and storage contents through handles that are safe to keep |
| [Villager behaviour](dev-docs/villagers-and-brain.md) | Add activities, win daytime and evening choices, interrupt idle villagers, move villagers, change needs, save your own data per villager |
| [Buildings](dev-docs/buildings.md) | Make modded blocks count for Towntopia's buildings, read buildings, watch them change |
| [Pathfinding and transport](dev-docs/pathfinding-and-transport.md) | Path rules (prefer, avoid, never, road speed), transport links villagers ride, a ready-made bubble elevator |
| [Mayor shop](dev-docs/shop.md) | Sell your items, use your own coin, change Towntopia's rows, watch and cancel purchases |
| [Mood icons](dev-docs/mood-icons.md) | Your own bubble icons, pop-ups, ongoing states, hide or swap Towntopia's |
| [Skins and looks](dev-docs/skins-and-looks.md) | Whole looks from data files, skins for single villagers |
| [Animations and props](dev-docs/animations-and-props.md) | Play and loop moves, replace Towntopia's moves, draw props on bones |
| [Work recipes](dev-docs/work-recipes.md) | Dishes and cook stations, smith materials, tiers, recipes and smelting, miner ores, from Java or data files |

Plus an **experimental** tier of raw hooks on almost 8,000 Towntopia methods ([experimental hooks](dev-docs/experimental-hooks.md)), performance tracking per addon and per hook, and safety nets: a version check, per-class kill switches, and isolated listener errors.

Start with the [developer guide](dev-docs/README.md): [getting started](dev-docs/getting-started.md), then [concepts](dev-docs/concepts.md).

## This repository

| Folder | What's in it |
|---|---|
| `src/main/` | The mod. `townkit/api` is the API addons use; `townkit/core` and `townkit/mixin` are internal. |
| `src/test/` | Unit tests (no game needed) |
| `src/gametest/` | In-game tests that run in a real headless server with real Towntopia villages, and client check scenes. Working examples of every API area. |
| `hookgen/` | The build-time tool that reads Towntopia's jar and generates TownKit's hooks |
| `docs/` | Player guide |
| `dev-docs/` | Developer guide, plus the full [hook list](dev-docs/hooks.md) |
| `libs/` | Where Towntopia's jar goes to build (not included, see [building from source](dev-docs/building-from-source.md)) |

Changes per version are in the [changelog](CHANGELOG.md).

## License

TownKit is licensed under the **GNU Lesser General Public License v3.0 only** (`LGPL-3.0-only`). See [LICENSE](LICENSE) and [COPYING](COPYING).

In short: you can use TownKit in your own addons under any license. If you change TownKit itself and share that changed version, your changes to TownKit must stay under the LGPL with their source available.

Towntopia is a separate mod by its own author and is not covered by this license.
