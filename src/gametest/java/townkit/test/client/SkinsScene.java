package townkit.test.client;

import com.alex.test.ClientConfig;
import com.alex.test.client.VillagerTheme;
import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.villagers.core.Profession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.renderer.GeoRenderer;
import townkit.api.Subscription;
import townkit.api.skin.SkinRef;
import townkit.api.skin.TownKitSkins;
import townkit.core.TownKit;
import townkit.core.client.ClientLooks;
import townkit.core.client.skin.LookChoice;
import townkit.core.client.skin.LookHooks;
import townkit.core.client.skin.Skins;
import townkit.core.client.skin.VillagerSkins;
import townkit.core.village.VillagerHandle;

/**
 * Scene {@code skins}: per-villager skins. Three villagers on the square with Towntopia's Rustic look in use; the server
 * gives the first {@code towntopia:merchant}, the second the placeholder addon skin {@code townkit:check_skin} (texture
 * and a big-headed body model, gametest resources) and the third {@code townkit:check_broken}, whose texture is
 * missing so this client refuses it. Checks: the first wears Rustic's merchant skin on Rustic's merchant rig, the second
 * the addon texture and model, the third Towntopia's own skin (screenshot). Then the addon look {@code
 * townkit:client_check} (which has no merchant file) is chosen: the merchant falls back to Modern's (Towntopia's
 * Classic merchant file, as Modern has none) on the base rig, the addon skin stays (screenshot). Then the server
 * removes the skins: the villagers are drawn as usual and the skin hooks go.
 */
final class SkinsScene {
    private static final int FEET_Y = -60;
    private static final String OWNER = "townkit-client-check";
    private static final ResourceLocation LOOK = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "client_check");
    private static final SkinRef MERCHANT = SkinRef.towntopia("merchant");
    private static final SkinRef CHECK = SkinRef.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_skin"));
    private static final SkinRef BROKEN = SkinRef.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_broken"));
    private static final ResourceLocation CHECK_PNG = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "textures/townkit/skins/check_skin.png");
    private static final ResourceLocation CHECK_GEO = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "geo/townkit/skins/check_skin.json");
    private static final ResourceLocation RUSTIC_MERCHANT = ResourceLocation.fromNamespaceAndPath("towntopia", "textures/entity/rustic/dynamic_villagers_merchant.png");
    private static final ResourceLocation RUSTIC_MERCHANT_RIG = ResourceLocation.fromNamespaceAndPath("towntopia", "geo/rustic/merchant.json");
    private static final ResourceLocation CLASSIC_MERCHANT = ResourceLocation.fromNamespaceAndPath("towntopia", "textures/entity/dynamic_villagers_merchant.png");
    private static final ResourceLocation BASE_RIG = ResourceLocation.fromNamespaceAndPath("towntopia", "geo/base_villager.json");
    private static final String RIG_HOOK = "after client.VillagerGeoModel#rigSkin(PathfinderMob)";
    /** The server's subscriptions (server thread). */
    private static final List<Subscription> SUBS = new ArrayList<>();

    private SkinsScene() {
    }

    static List<CheckRun.Step> steps() {
        return List.of(
            CheckRun.act("Towntopia's Rustic look, no addon look", r -> {
                LookChoice.choose(null);
                ClientConfig.setVillagerTheme(VillagerTheme.RUSTIC);
                r.check("the client has the placeholder skin", Skins.CLIENT.get(CHECK.id()) != null, CHECK.id());
                r.check("the skin with a missing texture was refused", Skins.CLIENT.get(BROKEN.id()) == null, BROKEN.id());
                r.check("no skin hooks while no villager has a skin", !ClientLooks.anySkin() && !LookHooks.attachedKeys().contains(RIG_HOOK),
                    LookHooks.attachedKeys());
            }),
            CheckRun.act("build village, set skins on the server", r -> r.onServer("village", SkinsScene::buildVillage)),
            CheckRun.await("villagers and their skins on the client", 400, r -> r.ready("village") && entity(r, 0) != null && entity(r, 1) != null
                && entity(r, 2) != null && TownKitSkins.of(entity(r, 0)).isPresent() && TownKitSkins.of(entity(r, 2)).isPresent()),
            CheckRun.act("hide the hud", r -> r.mc().options.hideGui = true),
            CheckRun.await("villagers' section drawn", 400, r -> r.mc().levelRenderer.isSectionCompiled(entity(r, 0).blockPosition())),
            CheckRun.pause("settle", 40),
            CheckRun.act("checks: skins under Rustic", r -> {
                r.check("the client knows each villager's skin", Optional.of(MERCHANT).equals(TownKitSkins.of(entity(r, 0)))
                    && Optional.of(CHECK).equals(TownKitSkins.of(entity(r, 1))) && Optional.of(BROKEN).equals(TownKitSkins.of(entity(r, 2))),
                    TownKitSkins.of(entity(r, 0)) + " " + TownKitSkins.of(entity(r, 1)) + " " + TownKitSkins.of(entity(r, 2)));
                r.check("skin hooks attached (rig key too)", LookHooks.attachedKeys().contains(RIG_HOOK) && LookHooks.attachedKeys().size() == 8,
                    LookHooks.attachedKeys());
                r.check("towntopia:merchant follows the look: Rustic's merchant skin", RUSTIC_MERCHANT.equals(texture(r, 0)), texture(r, 0));
                r.check("... on Rustic's merchant rig (rig key merchant)", RUSTIC_MERCHANT_RIG.equals(model(r, 0)), model(r, 0));
                r.check("addon skin: its texture", CHECK_PNG.equals(texture(r, 1)), texture(r, 1));
                r.check("addon skin: its model", CHECK_GEO.equals(model(r, 1)), model(r, 1));
                r.check("missing art: drawn as usual (Towntopia's Rustic skin)", texture(r, 2).getNamespace().equals("towntopia")
                    && texture(r, 2).getPath().startsWith("textures/entity/rustic/"), texture(r, 2));
                r.check("missing art resolves to nothing", VillagerSkins.resolveForCheck(entity(r, 2)) == null, VillagerSkins.resolveForCheck(entity(r, 2)));
                r.shot();
            }),
            CheckRun.act("choose the addon look", r -> {
                LookChoice.choose(LOOK);
                r.check("the addon look is chosen", LookHooks.active().isPresent(), LookChoice.chosen());
            }),
            CheckRun.pause("settle with the look", 20),
            CheckRun.act("checks: skins under the addon look", r -> {
                r.check("merchant under an addon look without a merchant file: Modern's version (Towntopia's Classic file)",
                    CLASSIC_MERCHANT.equals(texture(r, 0)), texture(r, 0));
                r.check("... on the base rig", BASE_RIG.equals(model(r, 0)), model(r, 0));
                r.check("the addon skin wins over the look", CHECK_PNG.equals(texture(r, 1)) && CHECK_GEO.equals(model(r, 1)), texture(r, 1));
                r.shot();
                LookChoice.choose(null);
            }),
            CheckRun.act("remove the skins on the server", r -> r.onServer("removed", s -> {
                SUBS.forEach(Subscription::remove);
                SUBS.clear();
                return true;
            })),
            CheckRun.await("the client hears the skins are gone", 200, r -> r.ready("removed") && !ClientLooks.anySkin()),
            CheckRun.pause("settle without skins", 10),
            CheckRun.act("checks: drawn as usual again", r -> {
                r.check("merchant villager drawn as usual (Towntopia's farmer)", texture(r, 0).getNamespace().equals("towntopia")
                    && texture(r, 0).getPath().contains("farmer"), texture(r, 0));
                r.check("addon skin villager drawn as usual", texture(r, 1).getNamespace().equals("towntopia"), texture(r, 1));
                r.check("skin hooks detached", !LookHooks.attachedKeys().contains(RIG_HOOK) && LookHooks.attachedKeys().size() == 4,
                    LookHooks.attachedKeys());
                ClientConfig.setVillagerTheme(VillagerTheme.MODERN);
            }));
    }

    /** Server thread: the village, three standing villagers facing north with skins, the player north of them looking at them. */
    private static Object buildVillage(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(0, FEET_Y, 0), FEET_Y, 0);
        VillageManager mgr = v.manager();
        if (mgr == null) throw new IllegalStateException("test village was not created");
        BlockPos at = v.townHallDoor().north(4);
        NpcEntity merchant = stand(level, mgr, at, Profession.FARMER);
        NpcEntity check = stand(level, mgr, at.east(2), Profession.GUARD);
        NpcEntity broken = stand(level, mgr, at.east(4), Profession.GUARD);
        SUBS.add(TownKitSkins.set(OWNER, VillagerHandle.of(merchant), MERCHANT));
        SUBS.add(TownKitSkins.set(OWNER, VillagerHandle.of(check), CHECK));
        SUBS.add(TownKitSkins.setTemporary(OWNER, broken, BROKEN));
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.connection.teleport(at.getX() + 2.5, FEET_Y, at.getZ() - 5.5, 0f, 15f);
        return new int[] {merchant.getId(), check.getId(), broken.getId()};
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

    private static ResourceLocation texture(CheckRun r, int index) {
        Entity e = entity(r, index);
        return r.mc().getEntityRenderDispatcher().getRenderer(e).getTextureLocation(e);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResourceLocation model(CheckRun r, int index) {
        Entity e = entity(r, index);
        GeoRenderer renderer = (GeoRenderer) r.mc().getEntityRenderDispatcher().getRenderer(e);
        return renderer.getGeoModel().getModelResource((software.bernie.geckolib.animatable.GeoAnimatable) e);
    }
}
