# FAQ and troubleshooting

### Do I need TownKit if no mod asks for it?
No. TownKit does nothing visible on its own. Install it when an addon you want needs it.

### Does TownKit slow my game down?
Not measurably. Hooks no addon uses cost one extra check per call. With addons, `/townkit perf` shows what each one costs per tick, so you can see for yourself.

### The game won't start: "requires towntopia … or above"
Your Towntopia is older than the one this TownKit was made for. Update Towntopia (see [Compatibility](compatibility.md)).

### Villagers look plain, but the addon says they should have new skins, moves or icons
- Is TownKit installed on **your** game, not just the server? Art is drawn by TownKit on each player's game.
- Is the addon (or resource pack) with the art installed on your game too?
- Check the log for a line starting with `[TownKit]` naming a missing file. Missing art falls back to Towntopia's normal look instead of crashing.

### An addon's feature stopped working after a Towntopia update
Run `/townkit hooks verify`. Missing hooks mean that part of Towntopia changed. Wait for a TownKit update, or tell the addon's author.

### A Mayor shop row is greyed out
Hover over it: the tooltip says why. The village might need a certain building, or the addon decided it isn't for sale right now. If it says the row is unavailable, the addon's code failed and TownKit greyed it out instead of selling it wrong. That's worth reporting to the addon's author.

### The bubble elevator doesn't work
It only works if an addon switches it on. Then it needs:
- a full bubble column, 2 to 64 blocks high, on soul sand (going up) or magma (going down),
- the addon's marker item in item frames at a stop beside the bottom and beside the top, inside the village,
- something like a sign at the bottom opening, so the water doesn't flow out. Without it the stop isn't dry and no link forms.

### My cook or miner settings screen doesn't show an addon's dishes or ores
Those rows only show for players who have TownKit installed.

### How do I tell if TownKit itself is causing a problem?
Set `enabled=false` in `config/townkit-hooks.properties` and restart. If the problem goes away, report it with your log. Turn it back on afterwards: most addon features need it.

### Where do I report bugs?
On TownKit's GitHub issues page. Include the log, your Towntopia and TownKit versions (`/townkit status` shows both), and the list of addons you use.
