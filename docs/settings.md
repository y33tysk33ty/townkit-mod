# Settings

TownKit has two config files, both in your game's (or server's) `config` folder. Most players never need to touch either.

## `config/townkit-hooks.properties` (server and single player)

Created the first time the game starts. Changes apply on the **next** start.

```properties
# TownKit hooks. Changes apply on the next game start.
enabled=true
disabledClasses=
```

| Setting | What it does |
|---|---|
| `enabled` | `false` switches every TownKit hook off. Most addon features then stop working: they log a line and Towntopia behaves as it does without them. Use this only to check whether TownKit is behind a problem. |
| `disabledClasses` | A comma-separated list of Towntopia classes to leave alone, like `village.MoveToCommand,village.raid.*` (`*` is a wildcard). An escape hatch in case a Towntopia update makes one part misbehave. |

## `config/townkit-client.toml` (your game only)

Holds your own look choices. It never changes Towntopia's config, so your Towntopia settings stay valid if you remove TownKit.

| Setting | What it does |
|---|---|
| `addonLook` | The addon look you picked on the Theme page, as its id (like `"myaddon:medieval"`). Empty means one of Towntopia's own looks is in use. |
| `addonLookSwaps` | Your own swaps of single skins, one line each: `"<look in use>: <skin> = <look its picture comes from>"`. For example `"myaddon:medieval: cook = towntopia:rustic"` draws Rustic's cook while Medieval is in use. |

You don't have to edit this file by hand. Both settings are set from the game.

## Picking a look on the Theme page

1. Open the Mayor screen and go to the **Theme** page.
2. Under Towntopia's look cards, TownKit adds a row with the names of the addon looks you have.
3. Click one to use it. Click one of Towntopia's cards to go back.

- A villager skin the chosen look doesn't include is drawn as in Towntopia's **Modern** look.
- To swap a single skin, open a look's page of skins from its card. Each skin an addon look has a version of gets a "Wear <look>'s version" button.
- While an addon look is in use, Towntopia's own *Leave out / Put back / Stop wearing* buttons on that page are covered. Pick a Towntopia look again to reach them.
- The addon row shows names only, no 3D previews, and doesn't scroll, so only a few short names fit.

Looks only change what *you* see. Skins an addon gives to single villagers (an envoy, a soldier) win over your look, and every player sees them.
