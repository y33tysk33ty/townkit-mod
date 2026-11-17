# Running the tests

TownKit has three kinds of tests. All need Towntopia's jar in `libs/` (see [Building from source](building-from-source.md)).

## Unit tests (`src/test/`, `hookgen/src/test/`)

```bash
./gradlew test
```

No game needed. They check:

- **API rules**: the stable tier never uses a Towntopia class, the API never reaches into TownKit's internals, every stable type has `@since`.
- **Contracts** of each module's core logic: events, the brain's choices and interrupts, stand-in tables, path rules, link tables and rides, shop pricing, bubbles, skins, moves, props, and every work-recipe method (`WorkApiContractTest`).
- **Audits** against Towntopia's jar: the building, nav and work rewrite sites still match the `.tsv` files (five building and nav audits read `libs/towntopia-0.1.2.jar` too, and are skipped without it), and every rewrite only adds code (`ForeignInjectorTest`).
- **The release jar** holds no test classes (`JarContentsTest`).
- **The hook generator** (`HookGenTest`).

## In-game tests (`src/gametest/`)

```bash
./gradlew runGameTestServer
```

A headless server with Towntopia and TownKit builds real villages and checks each API against them. Each run uses a fresh world in `runs/gametest/<time>/`. The server needs a lot of memory (it runs with up to 8 GB), because every test village stays loaded until the server stops.

- `-Ptest.namespaces=townkitwork` runs only the work-recipe tests.
- `-Ptownkit.hooks.enabled=false` runs the suite with every hook off, to check each module's hooks-off behaviour.

These tests are the best working examples of each API:

| Test class | Shows |
|---|---|
| `TownKitTests` | Raw hooks: before and after, cancelling, return overrides, constructors, listener isolation, the census, typed events in a live village |
| `StableApiTests` | Handles and events: handles match Towntopia's objects, a handle that outlives its villager answers empty |
| `VillageApiTests` | Village reads, storage, polled state and raid events, founding |
| `BehaviourTests` | Activities, choices by day and evening, interrupts, moving and following, needs, villager data |
| `BuildingTests` | Stand-ins, building reads, building events |
| `PathTests` | Roads, never tiles, raiders, speed, transport links, rides and fallbacks, the bubble elevator |
| `ShopTests` | Addon rows, coins, conditions, required buildings, currency switch, purchase events |
| `BubbleTests`, `BubbleStateTests` | Pop-ups, ongoing states, hides, swaps, idle thoughts, `BUBBLE_SHOWING` |
| `SkinTests`, `LookSyncTests` | Saved and temporary skins, what reaches tracking and late-joining players |
| `AnimationTests`, `AnimationSwapTests` | Plays, saved loops, per-owner stop, swaps, `MOVE_STARTING` |
| `CookingTests`, `ForgeTests`, `MiningTests` | Dishes and stations, tiers, recipes and smelting, ores, settings rows, reloads |

Test-only blocks and helpers live in the same source set and are never in the release jar.

## Client checks (`src/gametest/java/townkit/test/client/`)

```bash
./gradlew runClientCheck -Ptownkit.clientCheck=<scene>
```

A real client runs one scene in a fresh flat world with nobody at the keyboard, saves screenshots to `runs/client-check/screenshots/`, writes PASS or FAIL to `runs/client-check/client-check.json`, and quits. Run one scene at a time.

| Scene | Checks |
|---|---|
| `smoke` | The client starts with TownKit and draws villagers |
| `shop` | The shop screen: currencies, greyed rows, descriptions |
| `bubbles` | Addon bubble icons |
| `looks` | The Theme page row and addon looks |
| `skins` | Addon and Towntopia skins on single villagers |
| `moves` | Addon moves and walking swaps |
| `props` | Props on bones |
| `work` | The cook's and miner's settings rows |
| `perf` | Frame time with 40 villagers in view |

## Tips

- Run one game or test server at a time.
- Tests that count events filter on their own villager: providers and listeners see every villager in every test village. Do the same in your own tests.
