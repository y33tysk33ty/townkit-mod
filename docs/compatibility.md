# Compatibility

## Towntopia versions

| Your Towntopia | What happens |
|---|---|
| Older than the version TownKit was made for | The game won't start. NeoForge shows "Mod townkit requires towntopia … or above". Update Towntopia, or use an older TownKit. |
| The version TownKit was made for | Everything works. |
| A newer Towntopia | The game loads and TownKit writes to the log that the Towntopia version differs. Hooks whose code still exists keep working; the rest do nothing. Run `/townkit hooks verify` to see how many are missing, and update TownKit when a new version is out. |

| TownKit | Made for Towntopia |
|---|---|
| 0.7.1, 0.7.2, 0.8.0, 0.8.1 | 0.1.3 |
| 0.3.0 to 0.7.0 | 0.1.2 |

## Players without TownKit

A player without TownKit can join a server that has it. They see villages as plain Towntopia draws them. See [Installing](install.md#what-a-player-without-townkit-misses-on-a-townkit-server) for the details.

## Other mods that change Towntopia

TownKit holds all of its changes to Towntopia's code itself, so addons built on it don't need their own. Other mods can change the same parts of Towntopia too:

- Since TownKit 0.7.1, TownKit only **adds** code around Towntopia's checks and never removes Towntopia's own instructions. Another mod aiming at the same spot keeps working.
- TownKit 0.7.0 and older could crash together with such mods, for example TownTopia Integrations (opening a storage room's Stock page crashed). Update to 0.7.1 or newer. The old workaround of listing classes in `disabledClasses` is no longer needed.
- Where another mod has already replaced a check, TownKit leaves that spot as the other mod made it. An addon's "this block counts as that one" rule may not apply at that one spot.

## Dedicated servers

TownKit runs on dedicated servers. Client-only parts (looks, the Theme page row, drawing addon skins and props) only load on a game with a screen.
