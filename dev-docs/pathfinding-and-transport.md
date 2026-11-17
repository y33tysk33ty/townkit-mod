# Pathfinding and transport

`townkit.api.nav` lets you change how Towntopia's villagers travel: prefer or avoid blocks, walk faster on them, and use transport (lifts, teleporters, anything you make) through stops that players mark.

TownKit itself changes nothing here: no built-in rule, no default road blocks, and its ready-made bubble elevator stays off until a mod enables it.

```java
// villagers prefer dirt paths and walk 1.5x as fast on them
Subscription road = TownKitPaths.rule("myaddon", Blocks.DIRT_PATH, PathRule.cost(0.75).withSpeed(1.5));
// villagers never route over magma
TownKitPaths.rule("myaddon", Blocks.MAGMA_BLOCK, PathRule.never());
// bubble columns become elevators wherever a player hangs your marker in item frames at the bottom and top
TownKitTransport.enable("myaddon", TownKitTransport.BUBBLE_ELEVATOR, MyItems.LIFT_MARKER.get());
```

## Path rules

`TownKitPaths.rule(owner, block, PathRule)`. The block is the one a villager stands on for that tile.

| Rule | Effect |
|---|---|
| `PathRule.cost(m)` | Below 1 preferred, above 1 avoided |
| `PathRule.speed(m)` | Walk faster (or slower) on it. Also makes the tile cheaper to route through: cost divided by speed. |
| `PathRule.never()` | Never route over it |
| `.withCost(m)`, `.withSpeed(m)` | Combine |
| `.includeRaiders()` | Also apply **cost and speed** to Towntopia's raiders |

- Several rules on one block combine: any never wins, then the lowest cost and the highest speed.
- The effective cost multiplier (cost / speed) is clamped to 0.5 to 64, so a search never explores much more of the map.
- Towntopia's own costs (ladders, trapdoors, cauldrons) are unchanged.
- `TownKitPaths.active()` says whether anything is registered; `effective(block)` shows a block's combined rule.

### Who a rule applies to

- Villagers by default. `includeRaiders()` widens a rule's cost and speed to raiders, nothing else.
- **Never** rules and transport links apply to every walker that uses the route map, raiders included, because Towntopia's reachability code doesn't know who walks.
- **Raiders never ride**: a link step in a raider search is skipped.

### Never tiles and reachability

- Building access and reachability count a goal on a never tile, or a place only reachable over never tiles, as unreachable. A building like that is invalid.
- A move can still **end** on a never tile: the route search exempts its goal. A villager standing on one can always walk off.

### When rules take effect

A new or removed rule is seen by the next route search at once. Cached reachability and building access change only when the surrounding route map next updates or rebuilds.

### Speed

Speed applies to the walk along route lines (not ladders, not rides). It is **nonlinear** around Towntopia's walk-easing threshold: the villager's move control stops easing above speed 0.575, so a multiplier that crosses it speeds villagers up more than its value. Speed 1.5 on a 0.5 walk gave about 0.42x the walk time, not 0.67x.

## Transport

A transport kind connects stops into links that villagers ride.

1. Implement `TransportKind`:

   | Method | Does |
   |---|---|
   | `id()` | The kind's id |
   | `links(level, stops)` | Pair the `TransportStop`s into `TransportLink`s (from, to, cost, both ways or not) |
   | `available(level, link)` | Whether a link works right now. Refreshed every 40 ticks. |
   | `ride(ctx)` | Make a `Ride` for one trip |

2. Register it: `TownKitTransport.registerKind(owner, kind)`.
3. Enable it with a marker item: `TownKitTransport.enable(owner, kindId, markerItem)`. Do this at mod setup.

A **stop** is that marker in an item frame inside a village's bounds, over a routable tile. TownKit binds it and makes it glow like Towntopia's tokens, and re-checks it every 40 ticks. Frames that already held a marker when the kind was enabled are picked up when they next load or a player touches them.

**Links** go into Towntopia's route map and are re-added after every Towntopia rebuild, so every route that benefits uses them, Towntopia's own jobs included. `TownKitTransport.links(village)` lists a village's links.

### Rides

When a walking villager reaches a link's start, TownKit pauses Towntopia's walk, ticks your `Ride`, and lets Towntopia plan on from the far stop. This applies to any Towntopia NPC whose walk is being ticked: a villager's own walk, a guard's chase walk, a traveler no village holds.

| `Ride` method | Does |
|---|---|
| `tick(ctx)` | Return `RideStatus.RUNNING`, `DONE` or `FAILED` |
| `end(ctx, completed)` | Called once at the end (optional) |
| `timeoutTicks()` | Default 400 |

`RideContext` gives `villager()`, `body()`, `level()`, `link()`, `ticks()`, plus `moveTowards(target, speed)` and `placeAt(feet)`.

- Towntopia's water rescue and stuck checks are suppressed for the rider only.
- `FAILED`, a throw, a timeout, or the link going away mid-ride (frame broken or emptied, not available at a refresh) puts the villager on the far stop if it is dry, else back on the boarding stop. The villager then avoids the link for about 10 minutes.
- An interrupted activity, an unload, a removed villager or a server stop end the ride safely, without marking the link.

### When transport switches off

Each logged once:

- With every hook off, path rules have no effect and transport is off.
- Transport also switches off when a route check it needs isn't rewritten (its class is in `disabledClasses`, the rewrite failed, or a newer Towntopia changed the method), or when a Towntopia call it makes fails. Links already installed stay refused and their edges are taken out of the route maps.

## The bubble elevator

`TownKitTransport.BUBBLE_ELEVATOR` (`townkit:bubble_elevator`) is a ready-made kind. It's off until a mod enables it with its own marker item.

- A whole column of 2 to 64 bubble blocks on **soul sand** (one-way up) or **magma** (one-way down).
- Bottom stop: the tile beside the column's bottom block. Top stop: a tile beside the top, feet one above it. Each bottom stop links to each top stop.
- **The bottom opening needs a sign** (or something like it: walk-through and holding water). Otherwise the water flows out, the tile isn't dry, and no link forms.
- A drained or broken column is noticed at the next availability refresh (within 40 ticks); a ride in it then fails and falls back.
- 8 blocks took 19 ticks going up and 32 going down.

## Costs

With nothing registered, Towntopia's rewritten nav checks cost their original call plus one static boolean read, and no nav hook is attached. A road rule plus an enabled bubble elevator measured under 0.1 ms per tick at 40 villagers. The audited nav sites are in `src/main/resources/townkit/nav-sites.tsv`.

## Not included

Minecarts and boats (the kind API doesn't rule them out), automatic transport discovery, move or ride events, an A* node cap.

## Tests to read

`PathTests` (in game) covers roads, never tiles, raiders, smoothing, speed, links, rides and every fallback, and the bubble elevator.
