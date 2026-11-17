# Animations and props

`townkit.api.anim.TownKitAnimations` lets you add your own moves (GeckoLib animations made for Towntopia's villager rig), play any move on a villager, replace Towntopia's moves, and draw props while a move plays. Moves you set are seen by every player with TownKit.

```java
Move march = Move.of(ResourceLocation.parse("myaddon:march"));             // from a move file
TownKitAnimations.play("myaddon", villager, Move.of(ResourceLocation.parse("myaddon:wave")), PlayMode.loop()); // saved loop
TownKitAnimations.play("myaddon", entity, Move.towntopia("SALUTE"), PlayMode.once().holdStill());
TownKitAnimations.replace("myaddon", guard, Move.towntopia("WALK"), march); // this villager marches instead of walking
TownKitAnimations.stop("myaddon", villager);                                // only your play and saved loop
```

## Moves

- `Move.towntopia("SALUTE")` names one of Towntopia's moves by its `VillagerAnimationState` name (id `towntopia:salute`). It plays the clip Towntopia uses for that state; `WAVE` plays its cheer clip, as Towntopia does. Seated variants aren't followed.
- `Move.of(id)` is your move, from a move file (see [Art files](art-files.md)).
- Your move only moves the bones it keys. Every other bone keeps what Towntopia plays.
- Animation names are looked up across every animation file the villager model knows, Towntopia's first, so give yours a name of their own (`<ns>.<move>`).

## Playing

`play(owner, entity, move, mode)` or `play(owner, villager, move, mode)` returns a `PlayResult` (`playing`, `held`).

| `PlayMode` | Plays |
|---|---|
| `PlayMode.once()` | Once, then back to Towntopia's animation |
| `PlayMode.loop()` | Until stopped |
| `PlayMode.loopFor(ticks)` | For a set time |
| `.holdStill()` | Also ask the villager to stand still |

- Playing is visual by default: the villager keeps doing what it was doing.
- `holdStill()` goes through TownKit's activity rules. It's refused while the villager works, eats, sleeps, talks, runs an evening step, flees or shelters; the move then plays without holding, and `held()` says so.
- One move at a time per villager: the latest play replaces the one before, whoever started it.
- For a `once` play the server takes the clip's length from the move's files in the mod jars (Towntopia's from Towntopia's jar). A move only a resource pack defines counts as 5 seconds on the server (logged once).

### Saved loops

- A loop played on a `Villager` is saved with it and plays again whenever it loads (after an unload, a dimension change or a restart) until stopped. A `loopFor` loop is saved only until its time is up (game time).
- One saved loop per villager: a later saved loop replaces it, whoever saved it.
- A loop saved with `holdStill()` doesn't hold after a load.
- A `once` play, or any play on an `Entity`, is never saved. When it ends, the saved loop (if any) plays again. A `once` play on an unloaded villager does nothing.

### Stopping

- `stop(owner, entity)` and `stop(owner, villager)` stop only that owner's play and forget only that owner's saved loop. The villager form works while it is unloaded. Another owner's play and saved loop are left alone; if the stopped play sat over another owner's saved loop, that loop plays again.
- `stop(entity)` stops every move on the entity and forgets its saved loop, whoever started them.

## Replacing Towntopia's moves

`replace(owner, builtIn, replacement)` for every villager, or `replace(owner, villager, builtIn, replacement)` for one (which beats the global swaps). Among either kind, the last registered wins, and removing it brings back the one before.

- A swap **to a Towntopia move** changes the move Towntopia sets on the server. Every client sees it, with or without TownKit.
- A swap **to an addon move** keeps Towntopia's move on the server; clients with TownKit play the addon move over it (the addon move wins on the bones it keys).
- **Walking** and its gaits (`WALK`, `RUN`, `JOG`, `SWORD_RUN`, `INJURED_RUN`; a `WALK` swap also covers the sad walk) are worked out by each client from the villager's motion, so those swaps happen on the client, keyed by the swaps the server sends.
- A villager's own swaps are saved with it (one per owner, villager and move: replacing the same move again replaces your earlier swap, which is how to switch one off after a restart). Global swaps aren't saved: register them at each start.
- Swaps don't change `play`.

## Events

- `MOVE_STARTING` (server) fires before a villager starts a move Towntopia sets (after the swaps, only when the move changes) and before each `play`. `cancel()` or `replace(move)`. Not fired when a saved loop plays again after a load. Needs hooks on.
- For walking and its gaits, `TownKitAnimations.onClientMove(owner, listener)` is called on the client (render thread) when a villager starts walking or changes gait. Return another move, or null to keep it.

## Props

A prop file names a GeckoLib model, a texture and the villager bone it rides on (see [Art files](art-files.md)). A prop is drawn while a move that carries it plays:

- the move file's `"props"` list, or
- `withProps(owner, move, props)` with a list of `Prop.of(id)`, for any move, Towntopia's included.

Use bone `bone2` (right forearm) or `bone` (left): they are in every one of Towntopia's rigs. The base rig's `ITEM` bone is missing from the London and Rustic rigs. Props are drawn on Towntopia's villagers, the Mayor, visitors and nomads, up to 48 blocks from the camera.

While a prop could be drawn, TownKit's prop layer is in Towntopia's villager renderers, and Towntopia's hidden-bone skip is off for every villager drawn. Once props are in use this costs about 0.15 ms per frame with 40 villagers in view.

## Sides and threads

- `withProps` and `onClientMove` register on the side they are called on and are never sent from the server. Call them where client code runs (mod setup runs on both sides; a dedicated server keeps them unused).
- `play` and `stop` are server thread only. `replace` (global) and `withProps` may be called from any thread.
- With every hook off, `play` logs once that clients play Towntopia's animations.

## Tests to read

`AnimationTests` and `AnimationSwapTests` (in game), and the `moves` and `props` client check scenes.
