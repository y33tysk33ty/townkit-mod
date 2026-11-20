# Compatibility and limits

## Towntopia versions

| TownKit | Built for Towntopia |
|---|---|
| 0.7.1, 0.7.2, 0.8.0 | 0.1.3 (mod id `towntopia`) |
| 0.3.0 to 0.7.0 | 0.1.2 |

- **Older Towntopia** than TownKit was built for: NeoForge refuses to load ("Mod townkit requires towntopia <version> or above"). None of TownKit's code runs.
- **A different, newer build**: the game loads, and TownKit logs that the installed Towntopia differs. Hooks whose methods still exist keep working; the rest stay inert. `/townkit hooks verify` shows which. Audited rewrite sites that no longer match stay as Towntopia ships them, so the game stays safe.
- The stable API is what protects your addon here: when Towntopia changes, TownKit is updated and your code keeps compiling and running.

## Clients without TownKit

A client without TownKit can join a server that has it:

- It draws Towntopia's own skins, animations and bubbles; addon art isn't drawn.
- The Mayor's shop: it still buys under the server's rules, but draws emeralds for every row, can't buy coin rows from its screen, and shows no proper icon for addon rows.
- Cook and miner settings: it sees Towntopia's own rows and can't wipe an addon's switches.
- Everything the server decides works the same.

If your addon adds art, ask players to install TownKit and your addon on their game too (`side="BOTH"`).

## Other mods that change Towntopia

TownKit holds every one of its changes to Towntopia's code, so addons don't need mixins of their own. Other mods' mixins on Towntopia still apply:

- TownKit's rewrites only add code around Towntopia's instructions and never remove or replace one (since 0.7.1).
- A plain `@Redirect` from another mod is applied before TownKit; at that spot TownKit leaves the code as the other mod made it, and its own check (a stand-in, say) doesn't apply there.
- TownKit 0.7.0 and older could crash with such mods (for example TownTopia Integrations: a `VerifyError` in `BlacksmithActivity`).

## Known limits

**Events**
- The polled village events arrive up to a second late, and a raid that starts and ends between two checks fires nothing.
- There is no village-removed event (Towntopia never removes villages) and no raid result.
- With hooks off, `VILLAGER_REMOVED` is unavailable, so villager data is never dropped.

**Behaviour**
- Addon activities never win at night, and in the evening only replace leisure steps.
- Without a choice provider, an addon activity started in the evening is replaced by Towntopia's evening plan.

**Buildings**
- No new building types; no dirty sources (explosions, pistons, fluids).
- Validity ignores working blocks, so a stand-in shows in `workingBlocks()`, not in `valid()`.

**Pathfinding**
- No minecarts or boats yet, no automatic transport discovery, no move or ride events.
- Raiders never ride; never rules apply to every walker on the route map.

**Shop**
- Towntopia has no Farm building: farms are farm sites, so `requires` can't name one.

**Skins, looks, props**
- The Theme page row shows a few short names, no previews, no scrolling.
- Addon textures hide Towntopia's accessory pieces.
- While props could be drawn, Towntopia's hidden-bone skip is off for every villager drawn.

**Work**
- No recycling for addon gear; removing raw iron or gold smelting isn't supported; Towntopia's own ores, materials and tiers can't be removed.
- Addon ores have no label in the mine room or town statistics.
- A changed Towntopia dish keeps Towntopia's "needs" text on players' screens.
- A tag dish is expanded to at most 8 variants, and the Mayor's stock orders see only its first variant (0.8.0).
- Towntopia's own cake uses up three milk buckets and gives none back unless an addon opts it into `keepContainers` (0.8.0).

**Farming** (details in [Farming](farming.md#limits))
- One modded crop per bush, hanging and log kind: a Towntopia bug, TownKit can't fix it.
- A harvest rule on a field crop needs `break_and_replant` in the crop file; `Use(SHEARS)` and `Use(AXE)` need the crop file's `harvest_tool`.
- Two-block crops with a rule are skipped in the away catch-up.
- A rule that gets ten bad answers in a row is off until `/reload` or a restart.
