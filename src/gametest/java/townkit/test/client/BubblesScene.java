package townkit.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.social.ThoughtIcon;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.BubbleKind;
import townkit.api.bubble.TownKitBubbles;
import townkit.api.bubble.Valence;
import townkit.core.TownKit;
import townkit.core.client.BubbleDrawHooks;
import townkit.core.client.ClientLooks;

/**
 * Scene {@code bubbles}: the smoke scene's villager shows the placeholder addon icon {@code townkit:client_check}
 * (its file and picture are in the gametest resources). Checks TownKit's draw hook drew it (screenshot), then that an
 * icon the client has no file for is left to Towntopia, then that a Towntopia icon shown on top replaces the addon one
 * and Towntopia draws it (screenshot).
 */
final class BubblesScene {
    private static final ResourceLocation CHECK = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "client_check");
    private static final ResourceLocation NO_FILE = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "client_check_no_file");
    private static final int[] DRAWS = new int[1];

    private BubblesScene() {
    }

    static List<CheckRun.Step> steps() {
        return List.of(
            CheckRun.act("build village", r -> r.onServer("villager", SmokeScene::buildVillage)),
            CheckRun.await("villager on the client", 400, r -> r.ready("villager") && SmokeScene.clientVillager(r) != null),
            CheckRun.act("hide the hud", r -> r.mc().options.hideGui = true),
            CheckRun.await("villager's section drawn", 400, r -> r.mc().levelRenderer.isSectionCompiled(SmokeScene.clientVillager(r).blockPosition())),
            CheckRun.pause("settle", 40),
            CheckRun.act("show the addon icon", r -> r.onServer("show", SmokeScene.serverWork(r, e -> {
                if (!TownKitBubbles.show(e, TownKitBubbles.icon(CHECK, Valence.POSITIVE), BubbleKind.THOUGHT, 2400)) {
                    throw new IllegalStateException("show returned false");
                }
            }))),
            CheckRun.await("TownKit drew the addon icon", 200, r -> r.ready("show") && drewCheckOn(r)),
            // past the 4-tick fade in
            CheckRun.pause("fade in", 20),
            CheckRun.act("checks: addon icon", r -> {
                Entity v = SmokeScene.clientVillager(r);
                r.check("client look has the addon icon", CHECK.equals(ClientLooks.get(v).bubble()), ClientLooks.get(v));
                r.check("draw hook attached and ran", BubbleDrawHooks.attached() && BubbleDrawHooks.drawCount() > 0, BubbleDrawHooks.drawCount());
                r.check("last draw: the villager's addon thought bubble", drewCheckOn(r) && !BubbleDrawHooks.lastDrawn().speech(),
                    BubbleDrawHooks.lastDrawn());
                r.check("Towntopia's own bubble cleared", v instanceof NpcEntity npc && !npc.hasBubble(), v);
                r.shot();
            }),
            CheckRun.act("show an icon without a file", r -> r.onServer("nofile", SmokeScene.serverWork(r, e ->
                TownKitBubbles.show(e, TownKitBubbles.icon(NO_FILE, Valence.NEUTRAL), BubbleKind.SPEECH, 2400, true)))),
            CheckRun.await("client gets it", 100, r -> r.ready("nofile") && NO_FILE.equals(ClientLooks.get(SmokeScene.clientVillager(r)).bubble())),
            // the addon icon fades out (4 ticks), then TownKit draws nothing more
            CheckRun.pause("fade out", 10),
            CheckRun.act("count draws", r -> DRAWS[0] = BubbleDrawHooks.drawCount()),
            CheckRun.pause("frames", 10),
            CheckRun.act("checks: no file", r -> r.check("no file: TownKit draws nothing, Towntopia draws",
                BubbleDrawHooks.drawCount() == DRAWS[0], DRAWS[0] + " -> " + BubbleDrawHooks.drawCount())),
            CheckRun.act("show the addon icon again", r -> r.onServer("again", SmokeScene.serverWork(r, e ->
                TownKitBubbles.show(e, new BubbleIcon(CHECK, Valence.POSITIVE), BubbleKind.SPEECH, 2400, true)))),
            CheckRun.await("TownKit draws it again", 200, r -> r.ready("again") && drewCheckOn(r) && BubbleDrawHooks.lastDrawn().speech()
                && BubbleDrawHooks.drawCount() > DRAWS[0]),
            CheckRun.act("show a Towntopia icon", r -> r.onServer("towntopia", SmokeScene.serverWork(r, e ->
                TownKitBubbles.show(e, BubbleIcon.towntopia("status_hungry"), BubbleKind.THOUGHT, 2400)))),
            CheckRun.await("Towntopia's bubble replaces it", 200, r -> {
                Entity v = SmokeScene.clientVillager(r);
                return r.ready("towntopia") && ClientLooks.get(v).bubble() == null && v instanceof NpcEntity npc
                    && npc.bubbleShowing() && npc.renderedBubbleIcon() == ThoughtIcon.STATUS_HUNGRY;
            }),
            CheckRun.pause("Towntopia fades in", 20),
            CheckRun.act("count draws", r -> DRAWS[0] = BubbleDrawHooks.drawCount()),
            CheckRun.pause("frames", 10),
            CheckRun.act("checks: Towntopia icon", r -> {
                r.check("Towntopia's icon: TownKit draws nothing", BubbleDrawHooks.drawCount() == DRAWS[0],
                    DRAWS[0] + " -> " + BubbleDrawHooks.drawCount());
                r.shot();
            }));
    }

    private static boolean drewCheckOn(CheckRun r) {
        Entity v = SmokeScene.clientVillager(r);
        BubbleDrawHooks.Drawn d = BubbleDrawHooks.lastDrawn();
        return v != null && d != null && d.entityId() == v.getId() && CHECK.equals(d.icon());
    }
}
