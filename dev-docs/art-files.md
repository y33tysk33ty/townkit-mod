# Art files

Art comes from files in your addon's resources (or a resource pack: looks need no code at all). Behaviour comes from code. TownKit ships no art.

All paths are under `assets/<ns>/`. A file `townkit/<kind>/<name>.json` declares the id `<ns>:<name>`.

| File | Format | Notes |
|---|---|---|
| `townkit/bubbles/<name>.json` | `{"texture": "<ns>:textures/townkit/bubbles/<name>.png", "valence": "positive\|neutral\|negative"}` | Both fields required. The icon still needs `TownKitBubbles.icon` on the server. |
| `townkit/looks/<name>.json` | `{"name": "<translation key or text>", "folder": "<ns>:textures/townkit/looks/<name>/", "geo_folder": "<ns>:geo/townkit/looks/<name>/"}` | Both folders optional (defaults shown). `geo_folder` must be under `geo/`. The `towntopia` namespace is refused. |
| look skins | `<folder>/dynamic_villagers_<skin>.png` (Towntopia's file names: `dynamic_villagers_cook.png`, `..._guard_2.png`, `..._farmer_female_1.png`), an optional `..._dirty.png` twin for farmers, an optional body model `<geo_folder>/<skin>.json` | A skin without its own model is drawn on Towntopia's base villager model. Farmer dirt: a clean file (stages 0-2) and a dirty one (3-4). |
| `townkit/skins/<name>.json` | `{"texture": "<ns>:textures/townkit/skins/<name>.png", "geo": "<ns>:geo/townkit/skins/<name>.json"}` | `geo` optional (default: Towntopia's base villager model), must be under `geo/`. |
| `townkit/moves/<name>.json` | `{"animation_file": "<ns>:animations/townkit/<file>.animation.json", "animation": "<animation name in that file>", "props": ["<ns>:<prop>"]}` | `props` optional. The animation file is a GeckoLib animation for Towntopia's `base_villager` rig. |
| `townkit/props/<name>.json` | `{"geo": "<ns>:geo/<file>.geo.json", "texture": "<ns>:textures/entity/<file>.png", "bone": "<villager bone>"}` | Every cube of the model is drawn at the bone's pivot and moves with it. Use `bone2` or `bone`. |

## Rules

- **Missing art never crashes.** A client missing a file (unknown id, missing texture, a model GeckoLib couldn't load, a missing animation, a model without the prop's bone) draws Towntopia's normal look: no bubble for an addon icon, Towntopia's skin and animation, the villager without the prop. It logs once per missing id. A look whose files are gone (a removed pack) is ignored, and Towntopia's own choice is drawn.
- **Looks, skins, moves and props are client art: they need TownKit on the client.** A client without TownKit ignores the files. A look from a plain resource pack still needs TownKit installed on that client. Addons that add art are installed on both sides, like Towntopia.
- **Addon textures hide Towntopia's accessory pieces** (hats, packs). Draw your own into your model if you want one.
- **Theme page limits:** addon looks get a row of names under Towntopia's five cards, without 3D previews and without scrolling (a few short names fit). The "Your look" card's previews show Towntopia's look while an addon look is chosen.

## Data files for work recipes

The cook, smith and miner use **data** files (`data/<ns>/townkit/...`), not assets. See [Work recipes](work-recipes.md#data-files).
