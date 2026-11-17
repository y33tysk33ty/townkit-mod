# Commands

All TownKit commands start with `/townkit` and need operator level 2 (cheats on in single player, or an operator on a server). None of them change your world: they only show information.

| Command | What it shows |
|---|---|
| `/townkit status` | TownKit's version, the Towntopia version installed, and whether it matches the one TownKit was made for. Also how many hooks exist, how many hooked classes are loaded, and how many addon listeners are attached. |
| `/townkit building` | Stand inside a Towntopia building's room. Shows its type, whether it's valid and why not, which blocks counted for which job (and whether an addon rule made them count), and nearby blocks that didn't count, with the reason. |
| `/townkit perf` | Server tick times over the last minute (average and worst), est. TPS, lag spikes and how many TownKit listeners caused, and what each addon and each hook costs per tick. |
| `/townkit perf reset` | Clears the performance counters. |
| `/townkit hooks verify` | Checks every hook is really in place. Useful after a Towntopia update. |
| `/townkit hooks search <words>` | Finds hooks by name. For addon makers. |
| `/townkit hooks census start` / `stop` | Counts how often every hook runs. Play normally for a few minutes, then `stop`: it prints the top 10 and writes the full list to `townkit/census-<time>.tsv` in the game or server folder. Hooked calls cost a little more while it runs, so don't leave it on. |

## Reading `/townkit perf`

- An addon's activity choices show up twice: on the addon's own line and inside the `townkit` line, because TownKit is what asks the addon. Don't add the two together.
- "Lag spikes caused by TownKit listeners" counts ticks of 50 ms or more where addon listeners took a big share. It names the hook, so you can tell which addon to report.

## Reading `/townkit hooks verify`

It prints how many hooks are live, missing, or in classes that couldn't load. Missing hooks usually mean your Towntopia is a different version from the one TownKit was made for. See [Compatibility](compatibility.md).
