# Mood icons

`townkit.api.bubble.TownKitBubbles` lets you add your own bubble icons, pop them up, give villagers ongoing states, and control Towntopia's own bubbles.

```java
BubbleIcon restless = TownKitBubbles.icon(ResourceLocation.parse("myaddon:restless"), Valence.NEGATIVE);
TownKitBubbles.show(villagerEntity, BubbleIcon.towntopia("emote_love"), BubbleKind.SPEECH, 60); // server thread
TownKitBubbles.state("myaddon", BubbleState.builder(ResourceLocation.parse("myaddon:restless"), restless, v -> isNight(v))
        .resolved(BubbleIcon.towntopia("emote_relief")).priority(1).build());
TownKitBubbles.swap("myaddon", BubbleIcon.towntopia("status_hungry"), restless); // Towntopia's hungry shows yours
TownKitBubbles.addIdleThought("myaddon", restless, 2);
```

## Icons

- `BubbleIcon.towntopia(id)` names one of Towntopia's 67 icons (`status_hungry`, `emote_love`...). `TownKitBubbles.builtIns()` lists them.
- `TownKitBubbles.icon(id, valence)` registers your icon on the server. Each client draws it from your icon file (see [Art files](art-files.md)).
- `Valence` (POSITIVE, NEUTRAL, NEGATIVE) pitches the villager's "huh" sound like Towntopia's own icons.

## Pop-ups

`show(entity, icon, kind, ticks)` or `show(entity, icon, kind, ticks, silent)` shows an icon for a set time as a `BubbleKind.THOUGHT` or `SPEECH` bubble, with the "huh" unless `silent`.

- One bubble per villager: a pop-up replaces the bubble showing, and a bubble Towntopia shows later replaces yours.
- `clear(entity)` removes the bubble showing.
- Returns false for an entity that isn't a Towntopia NPC or isn't loaded. Server thread only.

## Ongoing states

`state(owner, BubbleState)`. `BubbleState.builder(id, icon, active)` with an `active` test per villager, then optionally `resolved(icon)` and `priority(n)`.

- Shown the way Towntopia shows its own states: the icon pops up when the state starts, again every 500 to 1200 ticks while it lasts, and the resolved icon (if any) once when it ends. Only while a player is within 20 blocks.
- `active` is checked on the server thread every 40 ticks per villager near a player. A test that throws counts as false (logged once).
- The highest priority active state shows; the later registered wins a tie.
- Towntopia's states are at priority 0. Above 0 outranks them (Towntopia's state bubble and idle thoughts stay away). 0 or below shows only while Towntopia's state is "fine".
- A state only shows an icon. Its effect on happiness is yours to apply (`Villager.addWellbeing`).

## Towntopia's bubbles

| Method | Does |
|---|---|
| `hide(owner, builtIn)` | The bubble and its "huh" don't show (the bubble already showing stays) |
| `swap(owner, builtIn, replacement)` | Show yours instead, same kind and time, the "huh" pitched by the replacement |
| `hide(owner, villager, builtIn)`, `swap(owner, villager, builtIn, replacement)` | The same for one villager; its own rules beat the global ones |
| `addIdleThought(owner, icon, weight)` | Grow the pool of thoughts a content villager has now and then. Towntopia's six idle thoughts weigh 1 each. |

- Swaps stack: the last registered wins, and removing it brings back the one before. A hide beats every swap.
- Hides and swaps never touch `show`.
- Rules for one villager aren't saved with it: register them again at each start.

## Event

`BUBBLE_SHOWING` fires before every bubble, Towntopia's (after the hides and swaps, so `icon()` is what would show) or an addon's (`show`, states). `cancel()` shows nothing; `replace(icon)` shows another (later handlers see it; an unregistered addon icon is ignored and logged once). Fires for Towntopia's villagers, the Mayor and travellers, on the server thread. Needs hooks on.

## Who sees what

Towntopia's icons go through Towntopia's own bubble, so every client sees them. Addon icons are drawn by TownKit, only on clients that have TownKit and the icon's file. Others see no bubble.

## Threads and hooks off

- `icon`, `state`, `hide`, `swap` and `addIdleThought` may be called at mod setup or later, from any thread.
- With nothing shown or registered, TownKit attaches nothing and ticks nothing.
- With every hook off, `show` and `clear` of Towntopia's icons still work. Addon-icon pop-ups, states, hides, swaps and idle thoughts do nothing (logged once), and `BUBBLE_SHOWING` is unavailable.

## Tests to read

`BubbleTests` and `BubbleStateTests` (in game), and the `bubbles` client check scene.
