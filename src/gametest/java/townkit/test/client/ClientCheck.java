package townkit.test.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import townkit.core.TownKit;

/**
 * Client check: runs one scene in a real client with nobody at the keyboard, then quits. Only in the {@code clientCheck}
 * run ({@code ./gradlew runClientCheck -Ptownkit.clientCheck=<name>}),
 * which passes {@code -Dtownkit.clientCheck=<scene>}; without that property this class does nothing.
 *
 * <p>At the title screen it creates a fresh creative superflat world ({@code check-<scene>-<millis>} in the run's
 * {@code saves/}, so no old village gets in the way), then runs the scene's steps one after the other, each keyed on a
 * condition with a timeout. Screenshots go to {@code screenshots/<scene>-<n>.png} and the result to
 * {@code client-check.json} ({@code {scene, passed, notes[], screenshots[]}}), both in the run folder.
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class ClientCheck {
    /** Scenes by name. Later tasks add theirs here. */
    private static final Map<String, Supplier<List<CheckRun.Step>>> SCENES = Map.of(
        "smoke", SmokeScene::steps,
        "shop", ShopScene::steps,
        "bubbles", BubblesScene::steps,
        "looks", LooksScene::steps,
        "skins", SkinsScene::steps,
        "moves", MovesScene::steps,
        "props", PropsScene::steps,
        "perf", PerfScene::steps,
        "work", WorkScene::steps);

    /** Ticks to wait for the world to load and the player to appear. */
    private static final int WORLD_TIMEOUT = 20 * 180;
    /** Ticks to wait for screenshot files to be written before quitting. */
    private static final int SHOT_TIMEOUT = 200;

    private static final String SCENE = System.getProperty("townkit.clientCheck");
    private static final long STARTED = System.currentTimeMillis();

    private enum Phase { TITLE, WORLD, STEPS, SHOTS, DONE }

    private static Phase phase = Phase.TITLE;
    private static CheckRun run;
    private static List<CheckRun.Step> steps;
    private static int stepIndex;
    private static int stepTicks;
    private static int waitTicks;

    private ClientCheck() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (SCENE == null || phase == Phase.DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PauseScreen) {
            // a focus change must not stall the run
            mc.setScreen(null);
        }
        try {
            tick(mc);
        } catch (Throwable t) {
            TownKit.LOG.error("[TownKit client check] run failed", t);
            fail("error: " + t);
            finish(mc);
        }
    }

    private static void tick(Minecraft mc) {
        switch (phase) {
            case TITLE -> {
                if (!(mc.screen instanceof TitleScreen) && !(mc.screen instanceof AccessibilityOnboardingScreen)) return;
                run = new CheckRun(SCENE);
                mc.options.pauseOnLostFocus = false;
                Supplier<List<CheckRun.Step>> scene = SCENES.get(SCENE);
                if (scene == null) {
                    fail("unknown scene '" + SCENE + "'; known: " + SCENES.keySet());
                    finish(mc);
                    return;
                }
                steps = scene.get();
                phase = Phase.WORLD;
                createWorld(mc, mc.screen);
            }
            case WORLD -> {
                if (mc.player != null && mc.level != null && mc.getSingleplayerServer() != null && mc.screen == null
                    && mc.level.hasChunk(mc.player.chunkPosition().x, mc.player.chunkPosition().z)) {
                    run.note("world loaded");
                    phase = Phase.STEPS;
                } else if (++waitTicks > WORLD_TIMEOUT) {
                    fail("world did not load in " + WORLD_TIMEOUT + " ticks");
                    finish(mc);
                }
            }
            case STEPS -> {
                if (stepIndex >= steps.size()) {
                    phase = Phase.SHOTS;
                    waitTicks = 0;
                    return;
                }
                CheckRun.Step step = steps.get(stepIndex);
                if (step.run(run)) {
                    stepIndex++;
                    stepTicks = 0;
                } else if (++stepTicks >= step.timeoutTicks()) {
                    fail("step '" + step.name() + "' timed out after " + step.timeoutTicks() + " ticks");
                }
                // a failed check ends the run at once (screenshots taken so far may still be half written)
                if (run.failed) finish(mc);
            }
            case SHOTS -> {
                boolean written = run.screenshots.stream().allMatch(s -> {
                    File f = new File(mc.gameDirectory, s);
                    return f.isFile() && f.lastModified() >= STARTED;
                });
                if (written) {
                    finish(mc);
                } else if (++waitTicks > SHOT_TIMEOUT) {
                    fail("screenshots not written in " + SHOT_TIMEOUT + " ticks");
                    finish(mc);
                }
            }
            case DONE -> {
            }
        }
    }

    private static void createWorld(Minecraft mc, Screen last) {
        String name = "check-" + SCENE + "-" + System.currentTimeMillis();
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        run.note("creating world " + name);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(1234L, false, false),
            reg -> reg.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), last);
    }

    private static void fail(String why) {
        if (run == null) run = new CheckRun(SCENE);
        run.check(why, false, "run");
    }

    /** Writes client-check.json and quits the game. */
    private static void finish(Minecraft mc) {
        if (phase == Phase.DONE) return;
        phase = Phase.DONE;
        boolean passed = !run.failed && steps != null && stepIndex >= steps.size();
        JsonObject json = new JsonObject();
        json.addProperty("scene", SCENE);
        json.addProperty("passed", passed);
        JsonArray notes = new JsonArray();
        run.notes.forEach(notes::add);
        json.add("notes", notes);
        JsonArray shots = new JsonArray();
        run.screenshots.forEach(shots::add);
        json.add("screenshots", shots);
        File out = new File(mc.gameDirectory, "client-check.json");
        try {
            Files.writeString(out.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TownKit.LOG.error("[TownKit client check] could not write {}", out, e);
        }
        TownKit.LOG.info("[TownKit client check] scene {} {}", SCENE, passed ? "PASSED" : "FAILED");
        mc.stop();
    }
}
