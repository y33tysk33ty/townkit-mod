# Getting started

## 1. Add TownKit to your build

TownKit isn't on a public Maven repository yet. Use one of these:

**A. The release jar in a `libs/` folder** (simplest)

Put `townkit-0.7.2.jar` (from CurseForge or this repo's releases) and Towntopia's jar in your addon's `libs/` folder:

```gradle
repositories {
    flatDir { dirs 'libs' }
    maven { url = 'https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/' } // Towntopia needs GeckoLib
}
dependencies {
    implementation "blank:townkit:0.7.2"          // libs/townkit-0.7.2.jar
    // only needed to compile against Towntopia's own classes (raw hooks, Unwrap); the stable API doesn't need it
    compileOnly "blank:towntopia:0.1.3"           // libs/towntopia-0.1.3.jar
}
```

**B. Your local Maven repository** (with sources and javadoc in your IDE)

[Build TownKit from source](building-from-source.md) and run `./gradlew publishToMavenLocal`. Then:

```gradle
repositories {
    mavenLocal()
    flatDir { dirs 'libs' } // towntopia-0.1.3.jar here: TownKit's published POM names it blank:towntopia:0.1.3
    maven { url = 'https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/' }
}
dependencies {
    implementation "townkit:townkit:0.7.2"
    compileOnly "blank:towntopia:0.1.3"
}
```

> Curse Maven: once TownKit's CurseForge project id is public, `curse.maven:townkit-<project id>:<file id>` will work too.

## 2. Declare the dependency

In your `META-INF/neoforge.mods.toml`:

```toml
[[dependencies.myaddon]]
    modId="townkit"
    type="required"
    versionRange="[0.7.2,)"
    ordering="AFTER"
    side="BOTH"
```

`side="BOTH"` matters if your addon adds art (looks, skins, moves, props, bubble icons): TownKit draws those on the client.

## 3. Make your first call

Call TownKit from `FMLCommonSetupEvent` or later. Before TownKit has started, the API throws `IllegalStateException("TownKit is not initialised yet: ...")`.

```java
@Mod("myaddon")
public final class MyAddon {
    public MyAddon(IEventBus modBus) {
        modBus.addListener(this::setup);
    }

    private void setup(FMLCommonSetupEvent event) {
        // log every job change
        TownKitEvents.PROFESSION_CHANGE.listen("myaddon", e ->
            LOG.info("{} is now a {}", e.villager().id(), e.to()));

        // villagers prefer dirt paths and walk 1.5x as fast on them
        TownKitPaths.rule("myaddon", Blocks.DIRT_PATH, PathRule.cost(0.75).withSpeed(1.5));
    }
}
```

Every registration takes an **owner** (your mod id) and returns a `Subscription`. Keep it if you want to undo the registration later with `remove()`. See [Concepts](concepts.md).

## 4. Check it in game

- `/townkit status` shows how many listeners are attached.
- `/townkit perf` lists your addon by its owner name with its cost per tick.

## Where to go next

- [Concepts](concepts.md), then the page for the area you need (see the [index](README.md)).
- The in-game tests in `src/gametest/java/townkit/test/` use each API against real villages. [Running the tests](running-the-tests.md) lists which test covers what.
