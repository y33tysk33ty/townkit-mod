package townkit.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.villagers.core.Profession;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import townkit.core.TownKit;
import townkit.core.client.ClientLooks;
import townkit.core.look.LookSync;
import townkit.core.look.VillagerLook;

/**
 * Scene {@code smoke}: one test village, one villager standing still on the square, no addon. Screenshot of the
 * villager as Towntopia draws it; then the look sync over the real connection: the client sees no look, then the look
 * the server sets, then none again after the server clears it.
 */
final class SmokeScene {
    private static final int FEET_Y = -60;
    private static final ResourceLocation CHECK_BUBBLE = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "client_check");

    private SmokeScene() {
    }

    static List<CheckRun.Step> steps() {
        return List.of(
            CheckRun.act("build village", r -> r.onServer("villager", SmokeScene::buildVillage)),
            CheckRun.await("villager on the client", 400, r -> r.ready("villager") && clientVillager(r) != null),
            CheckRun.act("hide the hud", r -> r.mc().options.hideGui = true),
            CheckRun.await("villager's section drawn", 400, r -> r.mc().levelRenderer.isSectionCompiled(clientVillager(r).blockPosition())),
            // let the rest of the view and the villager's model settle before the screenshot
            CheckRun.pause("settle", 40),
            CheckRun.act("checks before", r -> {
                Entity v = clientVillager(r);
                r.check("villager is a Towntopia NpcEntity on the client", v instanceof NpcEntity, v.getClass().getName());
                r.check("no-addon world: client holds no look", ClientLooks.get(v).isEmpty(), ClientLooks.get(v));
                r.shot();
            }),
            CheckRun.act("set a look on the server", r -> r.onServer("set", serverWork(r, e ->
                LookSync.update(e, l -> l.withBubble(CHECK_BUBBLE, true, 100))))),
            CheckRun.await("client gets the look", 100, r -> r.ready("set") && CHECK_BUBBLE.equals(ClientLooks.get(clientVillager(r)).bubble())),
            CheckRun.act("check the look", r -> r.check("client look equals the server's",
                VillagerLook.EMPTY.withBubble(CHECK_BUBBLE, true, 100).equals(ClientLooks.get(clientVillager(r))), ClientLooks.get(clientVillager(r)))),
            CheckRun.act("clear the look on the server", r -> r.onServer("clear", serverWork(r, e -> LookSync.update(e, l -> VillagerLook.EMPTY)))),
            CheckRun.await("client look cleared", 100, r -> r.ready("clear") && ClientLooks.get(clientVillager(r)).isEmpty()),
            CheckRun.act("done", r -> r.check("look sync round trip", true, "set, seen, cleared")));
    }

    /** Server thread: builds the village, puts one standing cook on the square facing north, and the player a few blocks north of it looking at it. */
    static Object buildVillage(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(0, FEET_Y, 0), FEET_Y, 0);
        VillageManager mgr = v.manager();
        if (mgr == null) throw new IllegalStateException("test village was not created");
        BlockPos door = v.townHallDoor();
        BlockPos at = door.north(4);
        NpcEntity npc = ScenarioBuilder.spawnVillager(level, mgr, at, Profession.COOK);
        if (npc == null) throw new IllegalStateException("villager was not spawned");
        VillagerModel model = mgr.getModel(npc.getNpcId());
        if (model != null) model.setBrainSuspended(true);
        npc.setYRot(180f);
        npc.setYHeadRot(180f);
        npc.setYBodyRot(180f);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.connection.teleport(at.getX() + 0.5, FEET_Y, at.getZ() - 4.5, 0f, 15f);
        return npc.getId();
    }

    static Entity clientVillager(CheckRun r) {
        int id = r.<Integer>result("villager");
        return r.mc().level == null ? null : r.mc().level.getEntity(id);
    }

    /** Server work on the scene's villager; its id is read here, on the client thread, before the work is handed over. */
    static Function<MinecraftServer, Object> serverWork(CheckRun r, Consumer<Entity> work) {
        int id = r.<Integer>result("villager");
        return s -> {
            Entity e = s.overworld().getEntity(id);
            if (e == null) throw new IllegalStateException("villager gone on the server");
            work.accept(e);
            return true;
        };
    }
}
