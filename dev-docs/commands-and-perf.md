# Commands and performance

## Commands

All need operator level 2. None of them change the world.

| Command | Shows |
|---|---|
| `/townkit status` | TownKit's version, the Towntopia version and whether it matches the one the hooks were generated from, hooks, hooked classes loaded so far, listeners attached (and how many were switched off after failures), startup cost |
| `/townkit hooks search <words>` | Matching hook keys and return types (and their thread) |
| `/townkit hooks verify` | Loads every hooked class and confirms each hook is injected. A dedicated server checks the non-client hooks; a client checks them all. |
| `/townkit hooks census start` / `stop` | Counts how often every hook's method runs. `stop` prints the top 10 and writes `townkit/census-<time>.tsv` in the game or server folder. Hooked calls cost a few nanoseconds more while it runs: use it for minutes, not permanently. |
| `/townkit building` | The building you stand in: type, valid or not and why, blocks that counted (rule, tag or plain), blocks that didn't and why |
| `/townkit perf` | Tick mean, p95, p99 and max over the last minute, est. TPS, lag spikes and how many TownKit listeners caused, cost per addon and per hook |
| `/townkit perf reset` | Clears the counters |

### Reading `/townkit perf`

- Lines are keyed by **owner**, so give TownKit your mod id.
- An addon's choice and interrupt providers show up twice: on the addon's own line, and inside the `townkit` line, because TownKit's brain hooks are what call them. Don't add the two; the `townkit` line minus the providers is TownKit's own cost.
- A lag spike is a tick of 50 ms or more. TownKit names the hook when its listeners caused it, and logs a warning.

## How a hook costs almost nothing

The generator in `hookgen/` reads the Towntopia jar at build time. As each class loads in the game, TownKit rewrites every hooked method `foo` into three methods:

```
foo(args)                 wrapper with foo's name, access and annotations (about 20 bytes):
                          if (STATE[id] == 0) return townkit$foo(args); else return townkit$hooked$foo(args);
townkit$hooked$foo(args)  runs the listeners around the body; only reached while something listens
townkit$foo(args)         Towntopia's original body
```

- With nobody listening, a call costs one byte read and a branch. Java's JIT inlines the wrapper and then the original body.
- At build time the generator runs the same injector over the jar and checks every method against the JVM's size limits: no hooked method crosses the 325-byte hot-inlining limit or the 8,000-byte compile limit. The numbers are at the top of the [hook list](hooks.md).
- Constructors can't be split, so they get a small check before each `return` instead.
- In stack traces you'll see `foo` followed by `townkit$foo`. That second frame is Towntopia's own code.
- Listener calls go through one shared `Bridges` class, one method per argument shape.

### Rewritten checks

Some modules also rewrite single instructions, as their classes load:

- **Buildings**: Towntopia's audited block checks (`building-sites.tsv`).
- **Pathfinding**: its route steps, costs, pace, flood fill, line of sight and reachability (`nav-sites.tsv`).
- **Work recipes**: the cook's, smith's and miner's audited sites (`work-sites.tsv`).

They only **add** a call after Towntopia's own instruction and never remove or replace it, so another mod's mixin on the same instruction still applies (a unit test checks this). With nothing registered, each costs its original instruction plus one static boolean read.

### Measured

At 40 villagers, with nothing registered, hooks on vs off showed no measurable tick cost (differences of a few hundredths of a millisecond, inside run-to-run noise). Startup: about 20 ms to read the hook list, about 30 ms to inject the classes loaded at startup, and about 10 ms for the version check. Once an addon draws props, client frames take about 0.15 ms longer with 40 villagers in view.

## Version check

At startup TownKit compares the installed Towntopia jar with the one its hooks were generated from. A different jar isn't fatal: hooks whose methods still exist keep working, missing ones stay inert, and TownKit says so in the log and in `/townkit status`.
