# What changes in your game

## With TownKit alone

Almost nothing. TownKit adds no blocks, items, villagers or rules of its own. With no addon using it:

- Villagers live, work and walk exactly as in plain Towntopia.
- The Mayor's shop, the cook, the blacksmith and the miner are Towntopia's own.
- TownKit's bubble elevator is **off**. It only switches on when an addon enables it.
- You get the [`/townkit` commands](commands.md) for operators, including a performance report.

TownKit adds "hooks" to Towntopia's code as the game loads. A hook nobody uses costs about one extra check per call, which was too small to measure in tests with 40 villagers.

## What addons built on TownKit can change

Each addon decides what it does. These are the things TownKit lets them do:

| Area | What you might see |
|---|---|
| **Villager behaviour** | Villagers doing new things in their free time or in the evening (an addon activity), new needs and moods, villagers remembering addon data |
| **Buildings** | Modded blocks counting for Towntopia's buildings, like a modded table counting as a crafting table in a kitchen |
| **Paths and travel** | Villagers preferring roads, walking faster on them, avoiding some blocks; lifts and teleporters villagers ride, such as the bubble elevator: a soul-sand or magma bubble column with marker items in item frames at the bottom and top |
| **Mayor's shop** | New rows in the shop, prices in an addon's own coin, rows greyed out until the village has a certain building, changed prices on Towntopia's own rows |
| **Mood icons** | New thought and speech bubbles over villagers' heads, Towntopia's bubbles swapped for others |
| **Skins and looks** | New looks to pick on the Mayor screen's **Theme page**, special skins for single villagers (an envoy, a group of soldiers) |
| **Animations and props** | Villagers waving, marching or playing new moves, holding props while they do |
| **Work** | New dishes for the cook (some made at a special cooking station), new materials, tiers and gear for the blacksmith, new ores for the miner. These show up in the cook's and miner's settings screens like Towntopia's own |
| **Village events** | Addons reacting when a village is founded, goes quiet, or is raided |

## Your choices

- **Looks:** if an addon or resource pack adds looks, pick one on the Theme page of the Mayor screen. TownKit adds a row of addon looks under Towntopia's cards. Your choice only changes what *you* see. See [Settings](settings.md).
- **Cook and miner settings:** addon dishes and ores get rows in those villagers' settings screens, so you can switch them on or off like Towntopia's.

## If something goes wrong

TownKit is built so that a broken addon or missing art doesn't crash the game:

- Missing art (a texture, model or animation) draws Towntopia's normal look instead, and writes one line to the log.
- An addon's code that keeps failing is switched off after 10 failures in a row, and the game carries on.
- A Mayor shop row whose addon code fails is greyed out. It's never sold at the wrong price.

See the [FAQ](faq.md) for what to check.
