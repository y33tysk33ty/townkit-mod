# TownKit developer guide

TownKit is a library for **Towntopia addons**. It holds every change to Towntopia's code, so your addon doesn't need mixins of its own. It gives you a stable API in TownKit's own types, so a Towntopia update gets fixed once, inside TownKit, instead of breaking your addon.

This guide covers TownKit 0.8.0 (Towntopia 0.1.3, Minecraft 1.21.1, NeoForge).

## Start here

1. [Getting started](getting-started.md): add TownKit to your addon's build and make your first call.
2. [Concepts](concepts.md): the API tiers, handles, subscriptions and owners, threads, and what happens when hooks are off.

## The stable API

| Page | Package | What you can do |
|---|---|---|
| [Events](events.md) | `townkit.api.event` | Typed events for villagers, villages, raids, buildings, the shop, bubbles and moves |
| [Villages and villagers](villages.md) | `townkit.api`, `townkit.api.village` | Read villages, villagers, owners, borders, raids and storage |
| [Villager behaviour](villagers-and-brain.md) | `townkit.api.brain`, `townkit.api.villager` | Activities, choices, interrupts, moving, needs, per-villager data |
| [Buildings](buildings.md) | `townkit.api.building` | Modded blocks that count, building reads, building events |
| [Pathfinding and transport](pathfinding-and-transport.md) | `townkit.api.nav` | Path rules, road speed, transport links and rides, the bubble elevator |
| [Mayor shop](shop.md) | `townkit.api.shop` | Your own shop rows, coins, conditions, Towntopia's rows, purchase events |
| [Mood icons](mood-icons.md) | `townkit.api.bubble` | Bubble icons, pop-ups, ongoing states, hiding and swapping Towntopia's |
| [Skins and looks](skins-and-looks.md) | `townkit.api.skin` | Whole looks players pick, skins for single villagers |
| [Animations and props](animations-and-props.md) | `townkit.api.anim` | Playing, looping and replacing moves, props on bones |
| [Work recipes](work-recipes.md) | `townkit.api.work` | The cook's dishes and stations, the smith's materials, tiers, recipes and smelting, the miner's ores; tag ingredients, containers back and own food values for dishes |
| [Farming](farming.md) | `townkit.api.farm` | Harvest rules (when a crop is ripe, picking by right-click), seeds made from produce, the farmer's bonus tag |
| [Art files](art-files.md) | assets | The JSON files for bubbles, looks, skins, moves and props |

## More

| Page | What's in it |
|---|---|
| [Experimental hooks](experimental-hooks.md) | Raw before/after hooks on almost 8,000 Towntopia methods, for what the stable API doesn't cover yet |
| [Hook list](hooks.md) | Every hook key, generated from Towntopia's jar |
| [Commands and performance](commands-and-perf.md) | `/townkit` commands, the perf report, the hook census, and how a hook costs almost nothing |
| [Compatibility and limits](compatibility-and-limits.md) | Towntopia versions, clients without TownKit, other mods, known limits |
| [Building from source](building-from-source.md) | Building TownKit yourself |
| [Running the tests](running-the-tests.md) | Unit tests, in-game tests, client checks, and which test shows which API |

The javadoc of every class in `townkit.api` has the full contract of each method. The in-game tests in `src/gametest/` use every API area against real villages, so they double as examples.
