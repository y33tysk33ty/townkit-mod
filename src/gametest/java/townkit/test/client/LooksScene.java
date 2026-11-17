package townkit.test.client;

import com.alex.test.ClientConfig;
import com.alex.test.client.VillagerTheme;
import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.claims.VillageClaims;
import com.alex.test.village.villagers.core.Profession;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.renderer.GeoRenderer;
import townkit.api.skin.Look;
import townkit.api.skin.TownKitSkins;
import townkit.core.TownKit;
import townkit.core.client.skin.LookChoice;
import townkit.core.client.skin.LookHooks;
import townkit.core.client.skin.LookPackHooks;
import townkit.core.client.skin.Looks;

/**
 * Scene {@code looks}: the placeholder look {@code townkit:client_check} (gametest resources: four farmer skins and
 * nothing else). With Towntopia's Rustic look picked, a farmer and a guard stand on the square; then the addon look
 * is chosen through {@link LookChoice}: the farmer wears the look's skin on Towntopia's base model, the guard (no file
 * in the look) Modern's skin, not Rustic's. Screenshot. Then the Mayor screen's Theme page: TownKit's row lists the
 * look as in use (screenshot); going back to Towntopia's look and clicking the look's button picks it again; picking
 * one of Towntopia's looks drops it. Then, with Towntopia's Modern in use, the farmer's skin page offers the look's
 * version; a click writes a TownKit swap line and the farmer wears the look's skin while Modern stays in use
 * (screenshots of the page and the villagers). Also checks Towntopia's own skinChanges lines round-trip through its look names.
 */
final class LooksScene {
    private static final int FEET_Y = -60;
    private static final ResourceLocation LOOK = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "client_check");
    private static final String LOOK_FOLDER = "textures/townkit/looks/client_check/dynamic_villagers_farmer_";
    private static final ResourceLocation BASE_RIG = ResourceLocation.fromNamespaceAndPath("towntopia", "geo/base_villager.json");
    private static final ResourceLocation MODERN = ResourceLocation.fromNamespaceAndPath("towntopia", "modern");
    /** The farmer's skin key ({@code farmer_<skin>}), read when its skin page opens. */
    private static String farmerSkin;

    private LooksScene() {
    }

    static List<CheckRun.Step> steps() {
        return List.of(
            CheckRun.act("Towntopia's Rustic look, no addon look", r -> {
                LookChoice.choose(null);
                ClientConfig.setVillagerTheme(VillagerTheme.RUSTIC);
                r.check("the client has the placeholder look", TownKitSkins.looks().stream().anyMatch(l -> l.id().equals(LOOK)), TownKitSkins.looks());
                r.check("no addon look chosen", TownKitSkins.currentLook().isEmpty(), TownKitSkins.currentLook());
                r.check("only the Theme page hooks while no look is chosen", LookHooks.attachedKeys().size() == 4, LookHooks.attachedKeys());
                // Towntopia's own skinChanges lines name its looks and are read back by name
                ClientConfig.setSkinChange(VillagerTheme.RUSTIC, "guard_1", VillagerTheme.CLASSIC);
                r.check("Towntopia's skinChanges line is read back (byName)", VillagerTheme.changeOf(VillagerTheme.RUSTIC, "guard_1") == VillagerTheme.CLASSIC,
                    ClientConfig.skinChanges());
                ClientConfig.setSkinChange(VillagerTheme.RUSTIC, "guard_1", null);
                r.check("and removed again", VillagerTheme.changeOf(VillagerTheme.RUSTIC, "guard_1") == null, ClientConfig.skinChanges());
            }),
            CheckRun.act("build village", r -> r.onServer("village", LooksScene::buildVillage)),
            CheckRun.await("villagers on the client", 400, r -> r.ready("village") && entity(r, 0) != null && entity(r, 1) != null),
            CheckRun.act("hide the hud", r -> r.mc().options.hideGui = true),
            CheckRun.await("villagers' section drawn", 400, r -> r.mc().levelRenderer.isSectionCompiled(entity(r, 0).blockPosition())),
            CheckRun.pause("settle", 40),
            CheckRun.act("checks before the look", r -> {
                r.note("before: farmer " + texture(r, 0) + " on " + model(r, 0) + ", guard " + texture(r, 1));
                r.check("guard drawn Rustic by Towntopia", texture(r, 1).getPath().startsWith("textures/entity/rustic/"), texture(r, 1));
            }),
            CheckRun.act("choose the look", r -> {
                LookChoice.choose(LOOK);
                r.check("current look is the placeholder", TownKitSkins.currentLook().map(Look::id).filter(LOOK::equals).isPresent(), TownKitSkins.currentLook());
                r.check("draw hooks attached", LookHooks.attachedKeys().size() == 10, LookHooks.attachedKeys());
            }),
            CheckRun.pause("settle with the look", 20),
            CheckRun.act("checks: farmer and guard", r -> {
                ResourceLocation farmer = texture(r, 0);
                ResourceLocation guard = texture(r, 1);
                r.check("farmer wears the look's skin", farmer.getNamespace().equals(TownKit.MODID) && farmer.getPath().startsWith(LOOK_FOLDER), farmer);
                r.check("farmer on Towntopia's base model (the look has no model)", BASE_RIG.equals(model(r, 0)), model(r, 0));
                r.check("guard falls back to Modern's skin", guard.getNamespace().equals("towntopia")
                    && guard.getPath().startsWith("textures/entity/modern/dynamic_villagers_guard_"), guard);
                r.check("Towntopia's accessory pieces hidden on the look's farmer", LookHooks.piecesHidden() > 0, LookHooks.piecesHidden());
                r.shot();
            }),
            CheckRun.act("open the Mayor's menu", r -> {
                BlockPos door = door(r);
                r.onServer("mayor", s -> ShopScene.mayor(s, door));
            }),
            CheckRun.await("Mayor found", 400, r -> {
                if (!r.ready("mayor")) return false;
                if (r.result("mayor") != null) return true;
                BlockPos door = door(r);
                r.onServer("mayor", s -> ShopScene.mayor(s, door));
                return false;
            }),
            CheckRun.await("Mayor screen open", 200, r -> ShopScene.isMayorScreen(r.mc().screen)),
            CheckRun.act("theme tab", r -> ShopScene.setTab(r.mc().screen, "THEME")),
            CheckRun.await("TownKit's row drawn with the look in use", 200, r -> chip(true) != null),
            CheckRun.pause("settle the page", 10),
            CheckRun.act("checks: Theme page", r -> {
                LookHooks.Chip c = chip(true);
                r.check("Theme page lists the look, in use", c != null, LookHooks.lastRow());
                r.shot();
                LookChoice.choose(null);
            }),
            CheckRun.await("row redrawn, look not in use", 100, r -> chip(false) != null),
            CheckRun.act("click the look's button", r -> {
                LookHooks.Chip c = chip(false);
                double x = c.x() + c.width() / 2.0;
                double y = c.y() + c.height() / 2.0;
                r.mc().screen.mouseClicked(x, y, 0);
                r.note("clicked the look's button at " + x + "," + y);
                r.check("click picks the look", LOOK.equals(LookChoice.chosen()), LookChoice.chosen());
                // what Towntopia's own "Use" buttons call
                ClientConfig.setVillagerTheme(VillagerTheme.MODERN);
                r.check("picking one of Towntopia's looks drops the addon look", LookChoice.chosen() == null && TownKitSkins.currentLook().isEmpty(),
                    LookChoice.chosen());
                r.check("draw hooks detached again", LookHooks.attachedKeys().size() == 4, LookHooks.attachedKeys());
            }),
            // the per-skin swap: on Towntopia's Modern page for the farmer's skin, wear the addon look's version
            CheckRun.act("open the farmer's skin page", r -> {
                Looks.SkinName skin = Looks.skinName(texture(r, 0));
                r.check("farmer drawn by Towntopia again", skin != null && texture(r, 0).getNamespace().equals("towntopia"), texture(r, 0));
                farmerSkin = skin.key();
                openPack(r.mc().screen, VillagerTheme.MODERN, entryIndex(farmerSkin));
            }),
            CheckRun.await("TownKit's button on the skin page", 200, r -> packButton(false) != null),
            CheckRun.pause("settle the skin page", 10),
            CheckRun.act("click wear the look's version", r -> {
                r.shot();
                LookPackHooks.PackButton b = packButton(false);
                double x = b.x() + b.width() / 2.0;
                double y = b.y() + b.height() / 2.0;
                r.mc().screen.mouseClicked(x, y, 0);
                r.note("clicked \"Wear\" for " + farmerSkin + " at " + x + "," + y);
                r.check("swap line written for Towntopia's Modern", LOOK.equals(LookChoice.swapFrom(MODERN, farmerSkin)),
                    LookChoice.swapFrom(MODERN, farmerSkin));
                r.check("Towntopia's skinChanges untouched", ClientConfig.skinChanges().isEmpty(), ClientConfig.skinChanges());
                r.check("draw hooks attached for the swap, no look chosen", LookChoice.chosen() == null && LookHooks.attachedKeys().size() == 7,
                    LookHooks.attachedKeys());
            }),
            CheckRun.await("button shows the swap worn", 100, r -> packButton(true) != null),
            CheckRun.act("close the screen, back to the villagers", r -> {
                r.mc().setScreen(null);
                int farmerId = r.<int[]>result("village")[0];
                r.onServer("back", s -> {
                    Entity farmer = s.overworld().getEntity(farmerId);
                    if (farmer == null) throw new IllegalStateException("farmer gone on the server");
                    s.getPlayerList().getPlayers().get(0).connection.teleport(farmer.getX() + 1.0, farmer.getY(), farmer.getZ() - 4.5, 0f, 15f);
                    return true;
                });
            }),
            CheckRun.await("player back", 100, r -> r.ready("back")),
            CheckRun.pause("settle with the swap", 40),
            CheckRun.act("checks: swapped farmer", r -> {
                ResourceLocation farmer = texture(r, 0);
                r.check("farmer wears the look's skin in Towntopia's Modern", farmer.getNamespace().equals(TownKit.MODID)
                    && farmer.getPath().equals(LOOK_FOLDER + farmerSkin.substring("farmer_".length()) + ".png"), farmer);
                r.check("guard stays Towntopia's", texture(r, 1).getPath().startsWith("textures/entity/modern/"), texture(r, 1));
                r.shot();
                LookChoice.setSwap(MODERN, farmerSkin, null);
                r.check("swap removed, hooks back to the Theme page ones", LookChoice.swapFrom(MODERN, farmerSkin) == null
                    && LookHooks.attachedKeys().size() == 4, LookHooks.attachedKeys());
            }));
    }

    /** Server thread: the village, a standing farmer and guard on the square facing north, the player north of them looking at them. */
    private static Object buildVillage(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000);
        ScenarioBuilder.VillageResult v = ScenarioBuilder.buildVillage(level, new BlockPos(0, FEET_Y, 0), FEET_Y, 0);
        VillageManager mgr = v.manager();
        if (mgr == null) throw new IllegalStateException("test village was not created");
        BlockPos door = v.townHallDoor();
        BlockPos at = door.north(4);
        NpcEntity farmer = stand(level, mgr, at, Profession.FARMER);
        NpcEntity guard = stand(level, mgr, at.east(2), Profession.GUARD);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        VillageClaims.claim(p, mgr);
        p.connection.teleport(at.getX() + 1.5, FEET_Y, at.getZ() - 4.5, 0f, 15f);
        return new int[] {farmer.getId(), guard.getId(), door.getX(), door.getY(), door.getZ()};
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

    private static BlockPos door(CheckRun r) {
        int[] v = r.result("village");
        return new BlockPos(v[2], v[3], v[4]);
    }

    @Nullable
    private static Entity entity(CheckRun r, int index) {
        int id = r.<int[]>result("village")[index];
        return r.mc().level == null ? null : r.mc().level.getEntity(id);
    }

    /** The texture the villager's renderer draws it with now. */
    private static ResourceLocation texture(CheckRun r, int index) {
        Entity e = entity(r, index);
        return r.mc().getEntityRenderDispatcher().getRenderer(e).getTextureLocation(e);
    }

    /** The body model the villager's renderer draws it with now. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResourceLocation model(CheckRun r, int index) {
        Entity e = entity(r, index);
        GeoRenderer renderer = (GeoRenderer) r.mc().getEntityRenderDispatcher().getRenderer(e);
        return renderer.getGeoModel().getModelResource((software.bernie.geckolib.animatable.GeoAnimatable) e);
    }

    @Nullable
    private static LookPackHooks.PackButton packButton(boolean worn) {
        return LookPackHooks.lastDrawn().stream().filter(b -> b.look().equals(LOOK) && b.skin().equals(farmerSkin) && b.worn() == worn)
            .findFirst().orElse(null);
    }

    /** The index of Towntopia's skin page entry for {@code skin} (VillagerPreview.ENTRIES, package-private). */
    private static int entryIndex(String skin) {
        try {
            Class<?> preview = Class.forName("com.alex.test.ui.screen.mayor.VillagerPreview");
            Field f = preview.getDeclaredField("ENTRIES");
            f.setAccessible(true);
            List<?> entries = (List<?>) f.get(null);
            for (int i = 0; i < entries.size(); i++) {
                Method key = entries.get(i).getClass().getDeclaredMethod("skinKey");
                key.setAccessible(true);
                if (skin.equals(key.invoke(entries.get(i)))) return i;
            }
            throw new IllegalStateException("no skin page entry for " + skin);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read Towntopia's skin page entries", e);
        }
    }

    /** Opens Towntopia's skin page of {@code look} at {@code entry}, as a click on a Theme page card does. */
    private static void openPack(Screen screen, VillagerTheme look, int entry) {
        try {
            Method open = screen.getClass().getDeclaredMethod("openPack", VillagerTheme.class, int.class);
            open.setAccessible(true);
            open.invoke(screen, look, entry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't open Towntopia's skin page", e);
        }
    }

    @Nullable
    private static LookHooks.Chip chip(boolean inUse) {
        return LookHooks.lastRow().stream().filter(c -> c.look().equals(LOOK) && c.inUse() == inUse).findFirst().orElse(null);
    }
}
