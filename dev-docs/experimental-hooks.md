# Experimental hooks

`townkit.api.hooks` reaches almost any Towntopia method by its own name: a **before** and an **after** side on methods, an **after** side on constructors. It follows Towntopia's names, so it can break on any Towntopia update. Use it when the stable API has no answer yet, and ask for a stable one.

```java
// After VillagerBrain picks what to do next: swap leisure for your own activity.
TownKitHooks.after("myaddon", "village.brain.VillagerBrain#decideNext(Activity.Context,float,float,float,float)", call -> {
    if (call.returnValue() instanceof LeisureActivity) {
        call.setReturnValue(new MyActivity());
    }
});

// Before a building is validated: read it, or skip the method with your own answer.
TownKitHooks.before("myaddon", "village.building.KitchenBuilding#scanFunctionalBlocks(ServerLevel)", call -> {
    KitchenBuilding kitchen = call.self(KitchenBuilding.class);
    ServerLevel level = call.arg(0, ServerLevel.class);
    // call.cancelWith(true) would skip Towntopia's scan and report "valid"
});

// Right after an object is built (constructors have an after side only).
TownKitHooks.after("myaddon", "village.villagers.farmer.crops.BlockMatcher$Single#<init>(Block)", call -> { /* call.self() is the new object */ });
```

Compiling against Towntopia's classes needs Towntopia's jar on your compile classpath (`compileOnly`, see [Getting started](getting-started.md)).

## Keys

Keys look like `<class relative to com.alex.test>#<method>(<simple parameter types>)`. Constructors are `#<init>(...)`. Overloads that would share a key get a `~n` suffix.

Find keys:

- in the [hook list](hooks.md),
- in game with `/townkit hooks search <words>`,
- in code with `TownKitHooks.search(text, limit)` and `TownKitHooks.find(key)` (a `HookPoint`: key, return type, static, constructor, client, thread).

`TownKitHooks.isLive(key)` says whether a hook is injected right now; `count()` gives the number of hooks.

## `HookCall`

| Method | Use |
|---|---|
| `self()`, `self(type)` | The object the method runs on |
| `args()`, `arg(index, type)` | The arguments, as passed |
| `isAfter()` | Which side you're on |
| `cancel()` | Before side, void method: skip Towntopia's body |
| `cancelWith(value)` | Before side, non-void method: skip the body and return `value` |
| `returnValue()`, `setReturnValue(value)` | After side: read or replace the result |
| `hook()`, `key()` | Which hook this is |

- After-listeners don't run if the method was cancelled or threw. Arguments are as passed, even if the method changed its parameters inside.
- Constructors only have an after side. `before(...)` on a constructor key is refused.
- **Return types are checked.** A wrong type is rejected and counted as a listener failure, and the game carries on.

## Registrations

`before(owner, key, listener)` and `after(owner, key, listener)` return a `HookRegistration`: a `Subscription` plus `calls()` and `failures()`.

- **Errors are isolated.** A throwing listener is logged; after 10 failures in a row it's switched off. Towntopia is never interrupted.
- `remove()` detaches the listener. The hook then costs nothing again.
- Registering a key that doesn't exist (or was trimmed, below) throws. Check with `find` first.

## Threads

Pathfinding hooks run on worker threads, marked "runs on worker thread" in the hook list. Client hooks run on the render thread and are marked "client only". Their listeners must be thread-safe.

## Getting Towntopia's objects

`townkit.api.hooks.Unwrap` gives Towntopia's object behind a stable handle:

| Method | Gives |
|---|---|
| `Unwrap.village(village)` | `VillageManager` |
| `Unwrap.villager(villager)` | `VillagerModel` |
| `Unwrap.npc(villager)` | `NpcEntity`, if loaded |
| `Unwrap.context(activityContext)` | Towntopia's `Activity.Context` |

## What isn't hooked, and why

- **Hot inner loops** listed in `hookgen/hook-rules.txt`: route-map node lookups, A*'s own search methods and its queue record, flood-fill steps, small geometry checks. They run hundreds of thousands of times per tick. The build warns about any rule that matches nothing in the current Towntopia.
- **Trimmed categories** that nothing in TownKit needs: private methods, plain getters and setters (a method that only returns or stores one field), data-class constructors (that only copy their arguments into fields), and packet plumbing in `network` classes. Hook keys TownKit's own code and tests use are always kept.
- **Compiler-generated code** (lambdas, `access$` bridges, enum `values`/`valueOf`, record `equals`/`hashCode`/`toString`) and **anonymous classes** (`Foo$1`): their names change with every Towntopia build.
- **Static initializers**, and Towntopia's own test kit, data generators and mixins.

To bring a trimmed hook back, add `keep Class#regex` to `hookgen/hook-rules.txt` (for example `keep village.FollowWalk#tickLine`) and rebuild, or ask for it. `keep` never overrides an exclusion in that file, and never brings back abstract, synthetic or oversized methods.

### Hooks kept on purpose

Six private methods are kept for war-style addons:

- `village.villagers.cleric.behavior.ClericCare#candidates(Activity.Context,ProfessionSettings)`: an after-listener can replace the returned list.
- `client.VillagerGeoModel#rigSkin(PathfinderMob)` (client only): a before-listener can `cancelWith` a skin name.
- `entity.mayor.MayorSpawnManager#ensureMayor(ServerLevel,TownHallBuilding)`: a before-listener can `cancel`.
- `village.villagers.guard.behavior.GuardThreatEvents#resolveManager(NpcEntity,ServerLevel)` and `#resolveManager(ServerPlayer,ServerLevel)`: a before-listener can `cancelWith` a different `VillageManager`.
- `village.VillageCrowding#isInside(NpcEntity,Set)`: an after-listener returning `false` drops that NPC from crowding.

## Config

A server owner can switch hooks off (`enabled=false`) or leave single classes unhooked (`disabledClasses=`) in `config/townkit-hooks.properties`. Your hooks then never fire. See [Concepts](concepts.md#hooks-on-and-off).
