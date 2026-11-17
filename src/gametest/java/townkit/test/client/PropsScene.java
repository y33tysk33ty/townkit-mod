package townkit.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.villagers.core.Profession;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import software.bernie.geckolib.animation.AnimationController;
import townkit.api.Subscription;
import townkit.api.anim.Move;
import townkit.api.anim.Prop;
import townkit.api.anim.TownKitAnimations;
import townkit.core.TownKit;
import townkit.core.client.ClientLooks;
import townkit.core.client.anim.MoveHooks;
import townkit.core.client.anim.Moves;
import townkit.core.client.anim.PropLayer;
import townkit.core.client.anim.Props;
import townkit.core.village.VillagerHandle;

/**
 * Scene {@code props}: two guards walk back and forth between two points on the square. The first has its WALK
 * replaced (per villager, on the server) by the placeholder march {@code townkit:check_march}, whose move file carries
 * the placeholder banner prop {@code townkit:check_banner} on the right forearm ({@code bone2}); the second walks as
 * Towntopia does, and on the client {@code withProps} gives Towntopia's WALK the prop {@code townkit:check_nobone},
 * whose bone no rig has (missing bone: not drawn, logged once). Checks: no prop layer and no move hooks before; while
 * they walk, the first's walk controller plays the march (the walk swap ran), the second's plays Towntopia's walk, the
 * layer is in the renderer and drew banners, the missing bone was logged (screenshots, a few ticks apart). When the swap
 * and the registration are removed the first walks as Towntopia does again, and the layer and hooks go.
 */
final class PropsScene {
    private static final int FEET_Y = -60;
    private static final String OWNER = "townkit-client-check";
    private static final Move MARCH = Move.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_march"));
    private static final ResourceLocation BANNER = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_banner");
    private static final ResourceLocation NO_BONE = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_nobone");
    /** Ticks a guard takes per leg before it is sent the other way. */
    private static final int LEG_TICKS = 100;

    @Nullable
    private static Subscription walkProps;
    @Nullable
    private static Subscription swap;
    @Nullable
    private static Consumer<ServerTickEvent.Post> walker;
    @Nullable
    private static Subscription throwing;
    @Nullable
    private static Subscription listener;
    private static int drawnBefore;
    private static int warningsBefore;

    private PropsScene() {
    }

    static List<CheckRun.Step> steps() {
        return List.of(
            CheckRun.act("nothing before any prop is needed", r -> {
                r.check("the prop files were read", Props.CLIENT.get(BANNER) != null && Props.CLIENT.get(NO_BONE) != null, BANNER);
                r.check("no prop layer in the renderer", !PropLayer.inRenderer() && PropLayer.layeredRenderers() == 0, PropLayer.layeredRenderers());
                r.check("no move hooks", MoveHooks.attachedKeys().isEmpty(), MoveHooks.attachedKeys());
                drawnBefore = PropLayer.drawn();
                warningsBefore = Props.CLIENT.warnings();
            }),
            CheckRun.act("build village on the server", r -> r.onServer("village", PropsScene::buildVillage)),
            CheckRun.act("hide the hud", r -> r.mc().options.hideGui = true),
            CheckRun.await("the guards are on the client", 400, r -> entity(r, 0) != null && entity(r, 1) != null),
            CheckRun.act("give Towntopia's walk a prop whose bone no rig has (client)", r ->
                walkProps = TownKitAnimations.withProps(OWNER, Move.towntopia("WALK"), List.of(Prop.of(NO_BONE)))),
            CheckRun.await("the swap reaches the client", 100, r -> !ClientLooks.get(entity(r, 0)).moveSwaps().isEmpty()),
            CheckRun.await("the first guard marches with a banner", 300, r -> "townkit.check_march".equals(walkClip(r, 0))
                && PropLayer.drawn() > drawnBefore),
            CheckRun.pause("let them walk", 10),
            CheckRun.act("checks: walking", r -> {
                r.check("the walk swap ran: the first guard's walk controller plays the march", "townkit.check_march".equals(walkClip(r, 0)),
                    walkClip(r, 0));
                r.check("walk swaps chosen", MoveHooks.walkSwaps() > 0, MoveHooks.walkSwaps());
                r.check("the prop layer is in the renderer", PropLayer.inRenderer(), "");
                r.check("... and in the Mayor's, visitors' and nomads' renderers too", PropLayer.layeredRenderers() == 4, PropLayer.layeredRenderers());
                r.check("the layer drew banners", PropLayer.drawn() > drawnBefore, PropLayer.drawn() - drawnBefore);
                r.check("move hooks attached (walk swap included)", MoveHooks.attachedKeys().contains("after entity.NpcEntity#hurryClip()"),
                    MoveHooks.attachedKeys());
                r.shot();
            }),
            CheckRun.await("the second guard walks too", 300, r -> walkClip(r, 1) != null && walkClip(r, 1).contains("walk")),
            CheckRun.act("checks: the second guard", r -> {
                r.check("the second guard walks as Towntopia does", walkClip(r, 1) != null && !walkClip(r, 1).startsWith("townkit"), walkClip(r, 1));
                r.check("the missing bone was logged (and nothing crashed)", Props.CLIENT.warnings() > warningsBefore,
                    Props.CLIENT.warnings() - warningsBefore);
            }),
            CheckRun.act("client move listeners: one that throws, one that swaps the second guard's walk for the march", r -> {
                Entity second = entity(r, 1);
                throwing = TownKitAnimations.onClientMove(OWNER + "-throws", (e, m) -> {
                    throw new IllegalStateException("test listener throws on purpose");
                });
                listener = TownKitAnimations.onClientMove(OWNER, (e, m) -> e == second && m.equals(Move.towntopia("WALK")) ? MARCH : null);
            }),
            CheckRun.await("the second guard marches (onClientMove)", 300, r -> "townkit.check_march".equals(walkClip(r, 1))),
            CheckRun.act("checks: client listener", r -> {
                r.check("onClientMove swapped the second guard's walk", "townkit.check_march".equals(walkClip(r, 1)), walkClip(r, 1));
                r.check("the throwing listener was skipped and logged once", Moves.CLIENT.listenerWarnings() == 1, Moves.CLIENT.listenerWarnings());
                if (listener != null) listener.remove();
                if (throwing != null) throwing.remove();
                listener = null;
                throwing = null;
            }),
            CheckRun.await("listener removed: the second guard walks as Towntopia does again", 300, r -> walkClip(r, 1) != null
                && walkClip(r, 1).contains("walk") && !walkClip(r, 1).startsWith("townkit")),
            CheckRun.pause("walk on", 15),
            // between legs the guard stands for a moment (Towntopia's idle): wait for the next leg
            CheckRun.await("marching again", 200, r -> "townkit.check_march".equals(walkClip(r, 0))),
            CheckRun.pause("a few steps", 8),
            CheckRun.act("second screenshot", r -> {
                r.check("still marching with the banner", "townkit.check_march".equals(walkClip(r, 0)) && PropLayer.inRenderer(), walkClip(r, 0));
                r.shot();
            }),
            CheckRun.act("remove the prop registration and the swap", r -> {
                if (walkProps != null) walkProps.remove();
                walkProps = null;
                r.onServer("unswapped", s -> {
                    if (swap != null) swap.remove();
                    swap = null;
                    return true;
                });
            }),
            CheckRun.await("the swap is gone on the client", 100, r -> r.ready("unswapped") && ClientLooks.get(entity(r, 0)).moveSwaps().isEmpty()
                && !ClientLooks.anySwap()),
            CheckRun.await("the first guard walks as Towntopia does again", 200, r -> walkClip(r, 0) != null && !walkClip(r, 0).startsWith("townkit")),
            CheckRun.act("checks: removed", r -> {
                r.check("the prop layer left every renderer", !PropLayer.inRenderer() && PropLayer.layeredRenderers() == 0, PropLayer.layeredRenderers());
                r.check("move hooks detached", MoveHooks.attachedKeys().isEmpty(), MoveHooks.attachedKeys());
                r.onServer("stopped", s -> {
                    if (walker != null) NeoForge.EVENT_BUS.unregister(walker);
                    walker = null;
                    return true;
                });
            }),
            CheckRun.await("walking stopped", 40, r -> r.ready("stopped")));
    }

    /** Server thread: the village, two guards walking east and west, the player north of them looking south. */
    private static Object buildVillage(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(0, FEET_Y, 0), FEET_Y, 0);
        VillageManager mgr = v.manager();
        if (mgr == null) throw new IllegalStateException("test village was not created");
        BlockPos a = v.townHallDoor().north(4).west(3);
        BlockPos b = a.north(2);
        NpcEntity soldier = spawn(level, mgr, a, Profession.GUARD);
        NpcEntity plain = spawn(level, mgr, b, Profession.GUARD);
        swap = TownKitAnimations.replace(OWNER, VillagerHandle.of(soldier), Move.towntopia("WALK"), MARCH);
        VillagerModel ms = mgr.getModel(soldier.getNpcId());
        VillagerModel mp = mgr.getModel(plain.getNpcId());
        int[] tick = {0};
        walker = e -> {
            if (tick[0]++ % LEG_TICKS != 0) return;
            boolean east = (tick[0] / LEG_TICKS) % 2 == 0;
            if (ms != null) ms.requestMoveTo(mgr.getGraph(), east ? a.east(7) : a, 0.6F);
            if (mp != null) mp.requestMoveTo(mgr.getGraph(), east ? b.east(7) : b, 0.6F);
        };
        NeoForge.EVENT_BUS.addListener(walker);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.connection.teleport(a.getX() + 3.5, FEET_Y + 1, a.getZ() - 6.5, 0f, 20f);
        return new int[] {soldier.getId(), plain.getId()};
    }

    private static NpcEntity spawn(ServerLevel level, VillageManager mgr, BlockPos at, Profession job) {
        NpcEntity npc = ScenarioBuilder.spawnVillager(level, mgr, at, job);
        if (npc == null) throw new IllegalStateException(job + " was not spawned");
        VillagerModel model = mgr.getModel(npc.getNpcId());
        if (model != null) model.setBrainSuspended(true);
        return npc;
    }

    @Nullable
    private static Entity entity(CheckRun r, int index) {
        if (!r.ready("village")) return null;
        int id = r.<int[]>result("village")[index];
        return r.mc().level == null ? null : r.mc().level.getEntity(id);
    }

    /** The clip the villager's walk controller plays now, or null. */
    @Nullable
    private static String walkClip(CheckRun r, int index) {
        if (!(entity(r, index) instanceof NpcEntity npc)) return null;
        AnimationController<?> c = npc.getAnimatableInstanceCache().getManagerForId(npc.getId()).getAnimationControllers().get("walk_controller");
        return c == null || c.getCurrentAnimation() == null ? null : c.getCurrentAnimation().animation().name();
    }
}
