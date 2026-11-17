# Concepts

## API tiers

| Tier | Packages | Promise |
|---|---|---|
| **Stable** | `townkit.api` and its sub-packages, except the two below | In TownKit's own types, never Towntopia's. Semantic versioning from 1.0.0. Before 1.0.0 a minor release (0.x) may break it; every break is listed in the [changelog](../CHANGELOG.md), and anything removed is `@Deprecated` for at least one minor release first. Every type says since which version it exists (`@since`). |
| **Experimental** | `townkit.api.hooks` | Follows Towntopia's own names, so it can break on any Towntopia update. Use it when the stable API has no answer yet. See [Experimental hooks](experimental-hooks.md). |
| **Internal** | `townkit.api.spi`, `townkit.core`, `townkit.mixin` | Not for addons. May change at any time. |

Unit tests check the tiers on every build: the stable tier never uses a Towntopia class, and the API never reaches into TownKit's internals.

## Handles

`Village` and `Villager` are **handles**: they hold ids and read Towntopia live. They are safe to keep in fields and maps.

- A removed village or a dead villager reads as gone: `exists()` is false and reads give empty answers, never an exception.
- A `Villager` handle follows its villager: it looks where the villager was last seen, then in whichever village holds it now.
- Handles hold the server weakly and read as gone once that server stops (for example after leaving a single-player world).

## Subscriptions and owners

Almost every registration looks like `register(owner, ...)` and returns a `Subscription`:

```java
Subscription s = TownKitEvents.ACTIVITY_CHANGE.listen("myaddon", e -> { /* ... */ });
s.isDisabled(); // true if TownKit switched it off (see below) or couldn't install it
s.remove();     // undo the registration
```

- `owner` is your mod id. It names your addon in logs, in `/townkit perf`, and in clash messages. A blank owner throws `IllegalArgumentException`.
- In modules with "per owner" rules (skins, animations), setting something again with the same owner replaces your earlier one.
- Subscriptions don't survive a restart. Register again at every start (mod setup is the usual place). Things saved with a villager (data keys, saved skins, saved loops, per-villager move swaps) are the exception: they come back with the villager.

## Isolation

Your code can't take Towntopia down:

- A listener, provider or handler that throws is logged. After **10 failures in a row** it is switched off (`isDisabled()` turns true) and the game carries on.
- A Mayor shop price or condition that throws greys its row out instead.
- Missing art falls back to Towntopia's normal look.

## Threads

- Most calls are **server thread** only: reads like `TownKitBuildings.at`, `TownKitShop.offers`, and actions like `TownKitBubbles.show`, `TownKitSkins.set` and `TownKitAnimations.play`. Each page says which.
- Registrations of rules (stand-ins, path rules, shop rows, bubble states, work recipes and so on) may be made at mod setup or later, from any thread.
- Towntopia's route searches run on **pathfinding worker threads**. Path rules are plain values, so this needs nothing from you; raw hooks on those methods are marked "runs on worker thread" in the [hook list](hooks.md), and their listeners must be thread-safe.
- Client hooks and `onClientMove` run on the **render thread**.

## Hooks on and off

TownKit works by adding hooks to Towntopia's classes as they load. A server owner can switch them off in `config/townkit-hooks.properties` (`enabled=false`), or switch off single classes (`disabledClasses=`).

With every hook off:

- Events built on hooks report `available()` false. `listen` logs a warning and returns a subscription that is already disabled. The polled village events (`VILLAGE_STATE_CHANGED`, `RAID_*`) and all reads keep working.
- Each module logs once that Towntopia's own behaviour stays: shop rows, stand-ins, path rules, transport, skins, animations, work recipes and bubble states do nothing. Each page lists the exceptions.

A class switched off with `disabledClasses` isn't detected: an event built on it still reads as available and never fires.

## Nothing registered costs (almost) nothing

Each module attaches its hooks only when something registers or listens, and takes them off again when the last subscription goes. Hooks nobody listens to cost one byte read and a branch per call. See [Commands and performance](commands-and-perf.md#how-a-hook-costs-almost-nothing).
