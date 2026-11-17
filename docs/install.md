# Installing TownKit

## What you need

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.238 or newer |
| Towntopia | 0.1.3 or newer (TownKit 0.7.0 and older were made for Towntopia 0.1.2) |
| GeckoLib | the version Towntopia asks for (Towntopia needs it, TownKit uses it too) |

## Where it goes

Put the TownKit jar in your `mods` folder, next to Towntopia and the addons that need it.

- **Single player:** your game's `mods` folder.
- **Server:** the server's `mods` folder. TownKit does its work on the server.
- **Players joining a server:** install TownKit on your game too. You can join without it, but you'll miss things addons add (see below).

## What a player without TownKit misses on a TownKit server

You can still join and play. Your game draws villages the way plain Towntopia does:

- Addon skins, looks, animations, props and addon mood icons aren't drawn. You see Towntopia's normal villagers.
- In the Mayor's shop every row shows emerald prices, and you can't buy rows that cost an addon's coin from the shop screen. Addon rows show no proper icon.
- The cook's and miner's settings screens show only Towntopia's own rows. You can't switch off an addon's dishes or ores, but you also can't wipe them by accident.

Everything the server decides (what villagers do, what the shop really sells, what the cook makes) works the same for everyone.

## Checking that it loaded

As an operator, type `/townkit status`. It shows TownKit's version, the Towntopia version it found, and whether that is the version TownKit was made for. See [Commands](commands.md).
