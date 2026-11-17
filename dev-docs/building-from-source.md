# Building from source

## You need

- JDK 21 (Gradle can fetch one for you)
- Towntopia's jar, which isn't in this repository because it isn't TownKit's to share

## Steps

1. Put `towntopia-0.1.3.jar` (the version in `gradle.properties` as `towntopia_version`) in `libs/`, with exactly that name. Get it from Towntopia's [official repository](https://github.com/town-topia-team/town-topia) (`jar/` folder) or its download page.
2. Build:

   ```bash
   ./gradlew build
   ```

   On Windows use `gradlew.bat build`.

3. The mod jar is `build/libs/townkit-<version>.jar`, with `-sources` and `-javadoc` jars next to it.

To use your build from an addon, run `./gradlew publishToMavenLocal` and see [Getting started](getting-started.md).

## What the build does

- **`generateHooks`** runs the generator in `hookgen/` over Towntopia's jar. It writes the hook mixins, the hook manifest, and the hook list (`build/hooks.md`; this repository keeps a copy at [dev-docs/hooks.md](hooks.md)). It checks every rewritten method against the JVM's size limits, and fails if a hook TownKit's own code or tests need no longer exists.
- **`generateBuildInfo`** writes `townkit.core.BuildInfo` from `gradle.properties`, which also fills `META-INF/neoforge.mods.toml`.
- **`test`** runs the unit tests and the hook generator's tests (see [Running the tests](running-the-tests.md)). Five audit tests also read `libs/towntopia-0.1.2.jar`, because TownKit's building and nav audits were made on that version: `CheckSiteScannerTest`, `SiteVerdictsTest`, `CheckSiteRewriterTest`, `NavSiteScannerTest` and `NavSiteRewriterTest`. Without that jar, the parts of them that read it are **skipped** (the reason is printed) and the build still passes.
- **`javadoc`** covers the addon-facing API (`townkit/api`) only.

## Useful tasks

| Task | Does |
|---|---|
| `./gradlew runClient` | A dev client with TownKit and Towntopia. `-Ptownkit.verifyClientAndQuit=true` checks every hook at the title screen, then quits. |
| `./gradlew runGameTestServer` | The in-game tests (see [Running the tests](running-the-tests.md)) |
| `./gradlew runServerCheck` | A dev dedicated server, to check no client class is touched |
| `./gradlew publishToMavenLocal` | Publish `townkit:townkit:<version>` with sources and javadoc to your local Maven repository |

## Leaving hooks out

`hookgen/hook-rules.txt` lists what isn't hooked and why (hot inner loops), plus `keep` rules that bring back trimmed hooks. Edit it and rebuild. See [Experimental hooks](experimental-hooks.md#what-isnt-hooked-and-why).

## After a Towntopia update

1. Put the new jar in `libs/` and set `towntopia_version` in `gradle.properties`.
2. `./gradlew build`, then compare the new `build/hooks.md` with the old hook list.
3. Audit the rewrite sites. The audit tests compare Towntopia's code with the `.tsv` files in `src/main/resources/townkit/` and fail on any new, moved or re-counted check. **They read a fixed jar name, not `towntopia_version`**, so point them at the new jar first (the `Path.of("libs", "towntopia-<version>.jar")` line near the top of each test):
   - buildings: `CheckSiteScannerTest`, `SiteVerdictsTest`, `CheckSiteRewriterTest` (made on 0.1.2)
   - pathfinding: `NavSiteScannerTest`, `NavSiteRewriterTest` (made on 0.1.2)
   - work recipes: `WorkSitesAuditTest` (made on 0.1.3), and `ForeignInjectorTest` (every rewrite only adds code)

   Re-audit each changed row before updating the file. Until then the game is safe: at run time TownKit rewrites a site only when it still matches its row, so a site that doesn't match stays as Towntopia ships it.
4. Run `./gradlew runGameTestServer` and the client check scenes.
