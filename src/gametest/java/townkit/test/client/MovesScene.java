package townkit.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.villagers.core.Profession;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import townkit.api.anim.Move;
import townkit.api.anim.PlayMode;
import townkit.api.anim.TownKitAnimations;
import townkit.api.hooks.HookRegistration;
import townkit.api.hooks.TownKitHooks;
import townkit.core.TownKit;
import townkit.core.client.ClientLooks;
import townkit.core.client.anim.MoveHooks;
import townkit.core.client.anim.Moves;

/**
 * Scene {@code moves}: addon moves on three villagers on the square. The server plays the placeholder move
 * {@code townkit:check_raise} (right arm straight up, held to the end of the 1.5 s clip; gametest resources) once on the
 * first and looping on the second, and {@code townkit:check_missing} (its animation file is missing) on the third.
 * Checks, reading each villager's right-arm bone as it is drawn: mid-move both raise their arm (screenshot), the move
 * controller ran, the third plays Towntopia's animation (missing art, no crash). When the once play is over its arm comes
 * back down although the clip ends raised (no held last frame), while the loop keeps it up
 * (screenshot). Then Towntopia's own SALUTE plays through the same controller; the loops are stopped (arm down) and,
 * with no move left, the move hooks go.
 */
final class MovesScene {
    private static final int FEET_Y = -60;
    private static final String OWNER = "townkit-client-check";
    private static final Move RAISE = Move.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_raise"));
    private static final Move MISSING = Move.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_missing"));
    private static final Move SALUTE = Move.towntopia("SALUTE");
    private static final String DRAW = "client.VillagerGeoModel#handleAnimations(PathfinderMob,long,AnimationState,float)";
    /** The placeholder raises the arm 170 degrees (about 2.97 rad) about z. */
    private static final float RAISED = 2.5F;
    private static final float DOWN = 1.2F;

    /** Right-arm z rotation of each villager as last drawn, by entity id. */
    private static final Map<Integer, Float> ARM = new ConcurrentHashMap<>();
    @Nullable
    private static HookRegistration recorder;
    private static int startedBefore;

    private MovesScene() {
    }

    static List<CheckRun.Step> steps() {
        return List.of(
            CheckRun.act("no move hooks before any move", r -> {
                r.check("the placeholder move file was read", Moves.CLIENT.get(RAISE.id()) != null, RAISE.id());
                r.check("no move hooks while no villager has a move", !ClientLooks.anyMove() && MoveHooks.attachedKeys().isEmpty(),
                    MoveHooks.attachedKeys());
                startedBefore = MoveHooks.started();
                recorder = TownKitHooks.after(OWNER, DRAW, call -> {
                    if (call.self() instanceof GeoModel<?> model && call.args()[0] instanceof Entity e) {
                        GeoBone arm = model.getAnimationProcessor().getBone("right_arm");
                        if (arm != null) ARM.put(e.getId(), arm.getRotZ());
                    }
                });
            }),
            CheckRun.act("build village on the server", r -> r.onServer("village", MovesScene::buildVillage)),
            CheckRun.act("hide the hud", r -> r.mc().options.hideGui = true),
            CheckRun.await("the villagers are drawn", 400, r -> arm(r, 0) != null && arm(r, 1) != null && arm(r, 2) != null),
            CheckRun.pause("settle", 20),
            CheckRun.act("play the moves on the server", r -> r.onServer("played", s -> {
                int[] ids = r.<int[]>result("village");
                TownKitAnimations.play(OWNER, s.overworld().getEntity(ids[0]), RAISE, PlayMode.once());
                TownKitAnimations.play(OWNER, s.overworld().getEntity(ids[1]), RAISE, PlayMode.loop());
                TownKitAnimations.play(OWNER, s.overworld().getEntity(ids[2]), MISSING, PlayMode.loop());
                return true;
            })),
            CheckRun.await("the moves reach the client", 100, r -> r.ready("played") && ClientLooks.get(entity(r, 0)).move() != null
                && ClientLooks.get(entity(r, 1)).move() != null && ClientLooks.get(entity(r, 2)).move() != null),
            CheckRun.await("mid-move: both arms raised", 60, r -> raised(r, 0) && raised(r, 1)),
            CheckRun.act("checks: mid-move", r -> {
                r.check("move hooks attached", MoveHooks.attachedKeys().size() == 2, MoveHooks.attachedKeys());
                r.check("the move controller is on each villager", MoveHooks.controllerOf(entity(r, 0)) != null
                    && MoveHooks.controllerOf(entity(r, 1)) != null && MoveHooks.controllerOf(entity(r, 2)) != null, "");
                r.check("the controller ran: two moves started", MoveHooks.started() - startedBefore == 2, MoveHooks.started() - startedBefore);
                r.check("once: right arm raised", raised(r, 0), arm(r, 0));
                r.check("loop: right arm raised", raised(r, 1), arm(r, 1));
                r.check("missing art: Towntopia's animation (arm down), logged", down(r, 2) && Moves.CLIENT.warnings() >= 1, arm(r, 2));
                r.shot();
            }),
            CheckRun.await("the once move played through", 120, r -> MoveHooks.controllerOf(entity(r, 0)) instanceof AnimationController<?> c
                && c.getAnimationState() == AnimationController.State.STOPPED),
            CheckRun.pause("settle after the once move", 20),
            CheckRun.act("checks: after the once move", r -> {
                r.check("once move over: the arm is back down (Towntopia's animation, no held last frame)", down(r, 0), arm(r, 0));
                r.check("loop: still raised", raised(r, 1), arm(r, 1));
                r.shot();
            }),
            CheckRun.act("play Towntopia's salute on the first", r -> r.onServer("salute", s -> {
                TownKitAnimations.play(OWNER, s.overworld().getEntity(r.<int[]>result("village")[0]), SALUTE, PlayMode.once());
                return true;
            })),
            CheckRun.await("the salute plays through the move controller", 100, r -> r.ready("salute")
                && MoveHooks.controllerOf(entity(r, 0)) instanceof AnimationController<?> c && c.getCurrentAnimation() != null
                && "salute".equals(c.getCurrentAnimation().animation().name())),
            CheckRun.act("stop the loops on the server", r -> r.onServer("stopped", s -> {
                TownKitAnimations.stop(s.overworld().getEntity(r.<int[]>result("village")[1]));
                TownKitAnimations.stop(s.overworld().getEntity(r.<int[]>result("village")[2]));
                return true;
            })),
            CheckRun.await("no move left on the client", 200, r -> r.ready("stopped") && !ClientLooks.anyMove()),
            CheckRun.pause("settle without moves", 20),
            CheckRun.act("checks: stopped", r -> {
                r.check("stopped loop: the arm is back down", down(r, 1), arm(r, 1));
                r.check("move hooks detached", MoveHooks.attachedKeys().isEmpty(), MoveHooks.attachedKeys());
                if (recorder != null) recorder.remove();
                recorder = null;
            }));
    }

    /** Server thread: the village, three standing villagers facing north, the player north of them. */
    private static Object buildVillage(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(0, FEET_Y, 0), FEET_Y, 0);
        VillageManager mgr = v.manager();
        if (mgr == null) throw new IllegalStateException("test village was not created");
        BlockPos at = v.townHallDoor().north(4);
        NpcEntity once = stand(level, mgr, at, Profession.FARMER);
        NpcEntity loop = stand(level, mgr, at.east(2), Profession.GUARD);
        NpcEntity missing = stand(level, mgr, at.east(4), Profession.GUARD);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.connection.teleport(at.getX() + 2.5, FEET_Y, at.getZ() - 5.5, 0f, 15f);
        return new int[] {once.getId(), loop.getId(), missing.getId()};
    }

    private static NpcEntity stand(ServerLevel level, VillageManager mgr, BlockPos at, Profession job) {
        NpcEntity npc = ScenarioBuilder.spawnVillager(level, mgr, at, job);
        if (npc == null) throw new IllegalStateException(job + " was not spawned");
        VillagerModel model = mgr.getModel(npc.getNpcId());
        if (model != null) model.setBrainSuspended(true);
        npc.setYRot(180f);
        npc.setYHeadRot(180f);
        npc.setYBodyRot(180f);
        return npc;
    }

    @Nullable
    private static Entity entity(CheckRun r, int index) {
        if (!r.ready("village")) return null;
        int id = r.<int[]>result("village")[index];
        return r.mc().level == null ? null : r.mc().level.getEntity(id);
    }

    @Nullable
    private static Float arm(CheckRun r, int index) {
        Entity e = entity(r, index);
        return e == null ? null : ARM.get(e.getId());
    }

    private static boolean raised(CheckRun r, int index) {
        Float z = arm(r, index);
        return z != null && Math.abs(z) > RAISED;
    }

    private static boolean down(CheckRun r, int index) {
        Float z = arm(r, index);
        return z != null && Math.abs(z) < DOWN;
    }
}
