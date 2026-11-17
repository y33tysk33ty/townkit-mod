package townkit.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.villagers.core.Profession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import townkit.api.Subscription;
import townkit.api.anim.Move;
import townkit.api.anim.PlayMode;
import townkit.api.anim.Prop;
import townkit.api.anim.TownKitAnimations;
import townkit.core.TownKit;
import townkit.core.client.anim.PropLayer;

/**
 * Scene {@code perf}: the client render cost of 0.7.0's prop layer. 40 villagers stand still (brains
 * suspended) in a 10 x 4 grid in view of the player; the frame-rate cap and vsync are off. Each phase waits
 * {@value #WARMUP} frames and then times {@value #FRAMES} frames (wall time between the ends of two frames):
 * <ul>
 *   <li>A: nothing registered (no prop layer, no move hooks);</li>
 *   <li>B: one {@code withProps} registration (on Towntopia's WALK, which nobody here plays), so the prop layer is in
 *   every Towntopia people renderer and the move hooks are attached, but no prop is drawn: the always-on cost;</li>
 *   <li>A and B again, to show the drift between phases;</li>
 *   <li>C: B plus the placeholder march {@code townkit:check_march} looping on all 40 (a move whose file carries the
 *   banner prop), so each villager runs the move controller and gets a prop drawn every frame.</li>
 * </ul>
 * The means, medians and p95 of each phase go to the notes ({@code client-check.json}). The only checks are that each
 * phase is what it says (layer in or out, banners drawn in C); the timings are reported, not judged. Frame time depends
 * on the graphics card and on what else the machine does, so only the differences between phases of one run mean
 * anything.
 */
final class PerfScene {
    private static final int FEET_Y = -60;
    private static final String OWNER = "townkit-client-check";
    private static final Move MARCH = Move.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_march"));
    private static final ResourceLocation BANNER = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_banner");
    private static final int VILLAGERS = 40;
    private static final int WARMUP = 120;
    private static final int FRAMES = 600;
    /** Ticks a phase may take (600 frames at 10 fps would be 60 s). */
    private static final int PHASE_TIMEOUT = 20 * 120;

    /** Frame times in ns of the phase being timed; null while no phase is. */
    @Nullable
    private static long[] times;
    private static int frame;
    private static long last;
    @Nullable
    private static Subscription walkProps;
    private static int capBefore;
    private static boolean vsyncBefore;
    private static final List<String> SUMMARY = new ArrayList<>();
    private static int drawnBefore;
    private static final java.util.function.Consumer<RenderFrameEvent.Post> TIMER = PerfScene::onFrame;

    private PerfScene() {
    }

    static List<CheckRun.Step> steps() {
        List<CheckRun.Step> s = new ArrayList<>();
        s.add(CheckRun.act("uncap the frame rate, hook the frame timer", r -> {
            capBefore = r.mc().options.framerateLimit().get();
            vsyncBefore = r.mc().options.enableVsync().get();
            r.mc().options.framerateLimit().set(260);
            r.mc().options.enableVsync().set(false);
            NeoForge.EVENT_BUS.addListener(TIMER);
            r.check("no prop layer before", !PropLayer.inRenderer() && PropLayer.layeredRenderers() == 0, PropLayer.layeredRenderers());
        }));
        s.add(CheckRun.act("build village on the server", r -> r.onServer("village", PerfScene::buildVillage)));
        s.add(CheckRun.act("hide the hud", r -> r.mc().options.hideGui = true));
        s.add(CheckRun.await("the villagers are on the client", 600, r -> onClient(r) == VILLAGERS));
        s.add(CheckRun.pause("settle", 100));
        s.add(CheckRun.act("screenshot: the 40 in view", CheckRun::shot));
        phase(s, "A1 nothing registered", r -> !PropLayer.inRenderer());
        s.add(CheckRun.act("register withProps (Towntopia's WALK, banner)", r ->
            walkProps = TownKitAnimations.withProps(OWNER, Move.towntopia("WALK"), List.of(Prop.of(BANNER)))));
        s.add(CheckRun.await("the layer is in the renderers", 100, r -> PropLayer.inRenderer() && PropLayer.layeredRenderers() == 4));
        phase(s, "B1 withProps registered, nothing drawn", r -> PropLayer.inRenderer());
        s.add(CheckRun.act("remove withProps", r -> {
            if (walkProps != null) walkProps.remove();
            walkProps = null;
        }));
        s.add(CheckRun.await("the layer left the renderers", 100, r -> !PropLayer.inRenderer() && PropLayer.layeredRenderers() == 0));
        phase(s, "A2 nothing registered", r -> !PropLayer.inRenderer());
        s.add(CheckRun.act("register withProps again", r ->
            walkProps = TownKitAnimations.withProps(OWNER, Move.towntopia("WALK"), List.of(Prop.of(BANNER)))));
        s.add(CheckRun.await("the layer is in the renderers again", 100, r -> PropLayer.inRenderer()));
        phase(s, "B2 withProps registered, nothing drawn", r -> PropLayer.inRenderer());
        s.add(CheckRun.act("loop the march (banner prop) on all 40", r -> r.onServer("march", srv -> {
            for (int id : r.<int[]>result("village")) {
                Entity e = srv.overworld().getEntity(id);
                if (e != null) TownKitAnimations.play(OWNER, e, MARCH, PlayMode.loop());
            }
            return true;
        })));
        s.add(CheckRun.await("banners are drawn", 200, r -> r.ready("march") && PropLayer.drawn() > drawnBefore + VILLAGERS));
        s.add(CheckRun.act("count banners from here, screenshot", r -> {
            drawnBefore = PropLayer.drawn();
            r.shot();
        }));
        phase(s, "C withProps + march with banner on all 40", r -> PropLayer.inRenderer());
        s.add(CheckRun.act("checks and clean up", r -> {
            int perFrame = (PropLayer.drawn() - drawnBefore) / (WARMUP + FRAMES);
            r.check("C drew about one banner per villager per frame", perFrame >= VILLAGERS / 2, perFrame);
            SUMMARY.forEach(r::note);
            r.onServer("stopped", srv -> {
                for (int id : r.<int[]>result("village")) {
                    Entity e = srv.overworld().getEntity(id);
                    if (e != null) TownKitAnimations.stop(e);
                }
                return true;
            });
            if (walkProps != null) walkProps.remove();
            walkProps = null;
            NeoForge.EVENT_BUS.unregister(TIMER);
            r.mc().options.framerateLimit().set(capBefore);
            r.mc().options.enableVsync().set(vsyncBefore);
        }));
        s.add(CheckRun.await("moves stopped", 100, r -> r.ready("stopped")));
        return s;
    }

    /** Steps that time one phase: warm-up, then {@link #FRAMES} frames; {@code state} must hold before and after. */
    private static void phase(List<CheckRun.Step> s, String name, java.util.function.Predicate<CheckRun> state) {
        s.add(CheckRun.act(name + ": start", r -> {
            r.check(name + ": set up as described", state.test(r), "layer in " + PropLayer.layeredRenderers() + " renderers");
            times = new long[WARMUP + FRAMES];
            frame = 0;
            last = 0;
        }));
        s.add(CheckRun.await(name + ": timing", PHASE_TIMEOUT, r -> times != null && frame >= times.length));
        s.add(CheckRun.act(name + ": result", r -> {
            long[] t = Arrays.copyOfRange(times, WARMUP, WARMUP + FRAMES);
            times = null;
            r.check(name + ": still set up as described", state.test(r), "");
            r.check(name + ": all 40 villagers still loaded", onClient(r) == VILLAGERS, onClient(r));
            long[] sorted = t.clone();
            Arrays.sort(sorted);
            double mean = Arrays.stream(t).average().orElse(0) / 1e6;
            String line = String.format(Locale.ROOT, "frame ms %s: mean %.3f, p50 %.3f, p95 %.3f, max %.2f over %d frames",
                name, mean, sorted[FRAMES / 2] / 1e6, sorted[(int) (FRAMES * 0.95)] / 1e6, sorted[FRAMES - 1] / 1e6, FRAMES);
            SUMMARY.add(line);
            r.note(line);
        }));
    }

    /** Each frame's wall time, from the end of the one before to the end of this one. */
    private static void onFrame(RenderFrameEvent.Post event) {
        long now = System.nanoTime();
        long[] t = times;
        if (t != null && last != 0 && frame < t.length) t[frame++] = now - last;
        last = now;
    }

    private static int onClient(CheckRun r) {
        if (!r.ready("village") || r.mc().level == null) return 0;
        int n = 0;
        for (int id : r.<int[]>result("village")) {
            if (r.mc().level.getEntity(id) instanceof NpcEntity) n++;
        }
        return n;
    }

    /** Server thread: the village, 40 villagers standing in a 10 x 4 grid, the player north of them looking south. */
    private static Object buildVillage(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(0, FEET_Y, 0), FEET_Y, 0);
        VillageManager mgr = v.manager();
        if (mgr == null) throw new IllegalStateException("test village was not created");
        BlockPos door = v.townHallDoor();
        Profession[] jobs = {Profession.FARMER, Profession.NONE, Profession.LUMBERJACK, Profession.MINER};
        int[] ids = new int[VILLAGERS];
        for (int i = 0; i < VILLAGERS; i++) {
            BlockPos at = door.north(4 + 2 * (i / 10)).east(2 * (i % 10) - 9);
            NpcEntity npc = ScenarioBuilder.spawnVillager(level, mgr, at, jobs[i % jobs.length]);
            if (npc == null) throw new IllegalStateException("villager " + i + " was not spawned");
            VillagerModel model = mgr.getModel(npc.getNpcId());
            if (model != null) model.setBrainSuspended(true);
            ids[i] = npc.getId();
        }
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        // north of the grid, looking south (+z) and a little down at it
        p.connection.teleport(door.getX() + 0.5, FEET_Y + 4, door.getZ() - 22.5, 0f, 12f);
        return ids;
    }
}
