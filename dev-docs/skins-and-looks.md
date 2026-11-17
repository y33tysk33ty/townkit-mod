# Skins and looks

`townkit.api.skin` has two parts:

- **Looks**: a whole set of villager skins a player picks for themselves. They come from data files, so a plain resource pack can add one.
- **Skins for single villagers** (an envoy, a group of soldiers): set from your code and seen by every player.

```java
// the envoy wears Towntopia's merchant skin (it follows each player's look), saved with the villager
Subscription envoy = TownKitSkins.set("myaddon", villager, SkinRef.towntopia("merchant"));
// a soldier wears your own skin while the entity exists (not saved)
TownKitSkins.setTemporary("myaddon", soldierEntity, SkinRef.of(ResourceLocation.parse("myaddon:soldier")));
```

## Looks

A look is a `townkit/looks/<name>.json` file plus skin textures (see [Art files](art-files.md)). No code needed.

- Each player picks a look on the **Theme page** of the Mayor screen, where TownKit adds a row of addon looks under Towntopia's cards.
- The choice is saved in TownKit's client config `townkit-client.toml` (`addonLook`), never in Towntopia's, so Towntopia's config stays valid without TownKit.
- A skin the chosen look has no file for is drawn as in Towntopia's **Modern** look.
- Single skins can be swapped on the page of one look's skins: a "Wear <look>'s version" button for each addon look with a file for that skin. These swaps are saved as `addonLookSwaps` lines: `"<look in use>: <skin> = <look its picture comes from>"`. Looks are ids (`myaddon:medieval`, or `towntopia:classic`, `london`, `modern`, `rustic`); skins are named as in Towntopia's skin changes (`cook`, `guard_2`, `farmer_female_1`).
- `TownKitSkins.looks()` and `currentLook()` (each a `Look`: id and name) are client only, main thread. A dedicated server has no looks.

## Skins for single villagers

| Method | Lasts |
|---|---|
| `set(owner, villager, skin)` | Saved with the villager: drawn every time it loads (after an unload, a change of dimension or a restart) until the subscription is removed. Works while the villager is unloaded. |
| `setTemporary(owner, entity, skin)` | While the entity exists: not saved, gone when it unloads, changes dimension or the server stops. Covers villagers, the Mayor, visitors and nomads. May be called from an `EntityJoinLevelEvent` listener. |
| `of(entity)` | Reads the winning skin. Server: what was set. Client: what the server sent. |

A `SkinRef` is either:

- `SkinRef.towntopia(key)`: one of Towntopia's skins by key. It follows each player's look and swaps, with that look's own body for the skin where it has one.
- `SkinRef.of(id)`: your own skin from a skin file. It looks the same whatever look a player picked.

The skin wins over the player's look.

### Per owner

- One saved skin and one temporary skin per owner per villager. Setting again with the same owner replaces your earlier skin of the same kind.
- Subscriptions don't survive a restart. To switch a saved skin off after one, call `set` again with the same owner (it replaces your saved skin), then remove the new subscription.
- Across owners, the skin set last wins, and removing it brings back the one set before.

### Notes

- A Towntopia farmer skin given this way (`towntopia:farmer_*`) is always drawn clean, with no dirt stages.
- Towntopia's accessory pieces (hats, packs) on a Towntopia skin follow the villager's own profession, not the skin.
- Addon textures hide Towntopia's accessory pieces: Towntopia decides them from the texture path and reads any addon texture as its Classic look, so TownKit hides all of them on addon-look skins and addon skins. If you want a hat, draw it into your own model.
- `set`, `setTemporary` and their subscriptions' `remove` are server thread only.
- With every hook off, `set` logs once that clients draw villagers as Towntopia does.

## Who sees what

Looks and skins are client art: they need TownKit on the client. A client without TownKit ignores the files and draws Towntopia's skins. Missing files never crash: Towntopia's normal look is drawn and one line is logged per missing id.

## Tests to read

`SkinTests` and `LookSyncTests` (in game), and the `looks` and `skins` client check scenes.
