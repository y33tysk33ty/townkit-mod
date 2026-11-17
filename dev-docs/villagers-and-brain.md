# Villager behaviour

`townkit.api.brain` lets you give villagers new things to do, and `townkit.api.villager` lets you save your own data per villager.

```java
static final ResourceLocation PET = ResourceLocation.parse("myaddon:pet_animal");
static final VillagerDataKey<String> MY_PET =
        VillagerDataKey.register(ResourceLocation.parse("myaddon:my_pet"), Codec.STRING);

TownKitBrain.registerActivity(PET, villager -> new VillagerActivity() {
    public ResourceLocation id() { return PET; }
    public ActivityStatus tick(ActivityContext ctx) {
        Entity pet = findPet(ctx);                              // your code
        if (pet == null) return ActivityStatus.FAILED;
        return switch (ctx.moveTo(pet, 0.6F)) {
            case MOVING -> ActivityStatus.RUNNING;
            case ARRIVED -> { ctx.villager().addWellbeing(5F); yield ActivityStatus.DONE; }
            default -> ActivityStatus.FAILED;                   // see ctx.lastMoveProblem()
        };
    }
});

TownKitBrain.addChoice("myaddon", new ChoiceProvider() {
    public float score(ChoiceContext ctx) {
        return ctx.villager().data(MY_PET).isPresent() ? ctx.leisureScore() + 1F : 0F;
    }
    public ResourceLocation activity() { return PET; }
});
```

## Activities

`TownKitBrain.registerActivity(id, factory)` registers a `VillagerActivity` by id. Towntopia's brain runs it like one of its own.

| Method | When it runs |
|---|---|
| `id()` | The activity's id |
| `start(ctx)` | Once, before the first tick (optional) |
| `tick(ctx)` | Every tick. Return `ActivityStatus.RUNNING`, `DONE` or `FAILED`. |
| `stop(ctx, reason)` | Once, with a `StopReason`: `DONE`, `FAILED`, `INTERRUPTED`, `UNLOADED`, `VILLAGER_GONE` |
| `allowsRoutineInterruption()` | `false` keeps routine things out (below) |

- The factory makes a fresh instance per run. After an unload, the villager comes back to a fresh run of the same id.
- Exceptions are isolated; 10 in a row switch the activity off.
- A run whose `start` threw fires no started or stopped event, but still gets `stop(FAILED)` to clean up.
- `allowsRoutineInterruption() == false`: TownKit refuses the activities Towntopia queues while yours runs (conversations, tavern dances, the Mayor's and druid's chats, other addons' interrupts). Towntopia's own interrupts still apply: fleeing, shelter, critical hunger, guard call-outs, water rescue, bedtime.
- An activity that starts during a villager's evening is replaced by Towntopia's evening plan before it starts, unless a choice provider put it there (below). Towntopia's schedule stays in charge of the evening.
- Events: `ADDON_ACTIVITY_STARTED`, `ADDON_ACTIVITY_STOPPED`.

## `ActivityContext`

| Method | Gives |
|---|---|
| `villager()`, `village()`, `level()`, `gameTime()` | Where you are. `village()` is `@Nullable`. |
| `moveTo(pos, speed)`, `moveTo(entity, speed)` | Walk there, see below |
| `lastMoveProblem()` | Why the last move failed, if it did |

### Moving

`moveTo` uses Towntopia's own pathfinding on the village's route map and returns a `MoveStatus`:

| Status | Means |
|---|---|
| `MOVING` | On the way |
| `ARRIVED` | Within 2 blocks |
| `FAILED` | Off the route map, no route, or the target is gone |
| `STUCK` | No progress for 200 ticks |

- A goal that drifts by 2 blocks or less keeps the current route. A new route is asked for at most every 20 ticks.
- Following an entity: an `ARRIVED` follow goes back to `MOVING` when the target gets more than 4 blocks away.
- When the activity stops, the villager stops walking to its goal.

## Choices

`TownKitBrain.addChoice(owner, ChoiceProvider)` lets your activity win the villager's next pick. The provider gives a `score(ChoiceContext)` and the `activity()` id it wants.

- **Daytime** (Towntopia's work hours): your activity wins only with a score **strictly higher** than Towntopia's best of eat, fetch, work and leisure (`ctx.eatScore()`, `fetchScore()`, `workScore()`, `leisureScore()`). Ties go to Towntopia.
- **Evening** (`ctx.phase() == ChoicePhase.EVENING`): it may replace only a leisure step (`EveningStep`: TAVERN, VISIT, LIBRARY, HOME), never depositing, chores, supper, washing, a bard's set or bedtime. Once running it keeps its leisure slot until a non-leisure step or bedtime comes.
- **Never at night.**
- Once any choice provider exists, TownKit looks at the evening plan on every tick for every villager. This is a cheap check, and providers are only asked when a leisure step comes up.
- Providers see every villager in every village. Score 0 for the ones you don't care about.

## Interrupts

`TownKitBrain.addInterrupt(owner, InterruptProvider, intervalTicks)` asks `wants(ctx)` every `intervalTicks` on each villager's own slot, staggered across villagers. `intervalTicks` below 10 throws `IllegalArgumentException`. The context's `phase()` is `ChoicePhase.INTERRUPT`.

- Only when Towntopia has no interrupt of its own, and only for an idle or leisurely villager or an interruptible addon activity.
- Never over a Towntopia job, meal, conversation, evening step, sleep or safety behaviour.

## Needs

`Villager.addWellbeing(delta)` and `addHunger(delta)` (100 = full) change needs. Loaded villagers only: they return false otherwise.

## Villager data

`VillagerDataKey.register(id, codec)` makes a key for your own data on a villager:

```java
static final VillagerDataKey<Integer> TRUST =
        VillagerDataKey.register(ResourceLocation.parse("myaddon:trust"), Codec.INT);

villager.setData(TRUST, 3);
int trust = villager.data(TRUST).orElse(0);
villager.removeData(TRUST);
```

- Saved with the world in TownKit's own file, readable while the villager is unloaded.
- Dropped when Towntopia forgets the villager (after `VILLAGER_REMOVED`). With every hook off that event is unavailable, so nothing tells TownKit a villager is gone and its data is kept.

## Costs

Nothing is hooked into Towntopia's brain until the first registration. Removing the last provider unhooks it again. Your providers' time shows on your line in `/townkit perf`, and again inside the `townkit` line (don't add the two).

## Tests to read

`BehaviourTests` in `src/gametest/` covers needs, data, the activity lifecycle, restore after unload, choices by day and evening, interrupts, moving and following.
