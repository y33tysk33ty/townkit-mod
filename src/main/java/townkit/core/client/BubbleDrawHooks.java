package townkit.core.client;

import com.alex.test.client.ThoughtBubbleRenderer;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import townkit.api.hooks.HookCall;
import townkit.core.TownKit;
import townkit.core.bubble.BubbleIcons;
import townkit.core.bubble.CoreBubbles;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.look.VillagerLook;

/**
 * Client side of addon bubbles: reads the icon files at each resource reload ({@link BubbleIcons}), and draws a
 * villager's addon bubble ({@link VillagerLook#bubble()}) in place of Towntopia's, from a before-hook on
 * {@code ThoughtBubbleRenderer#render} attached when the first addon bubble arrives (removed at logout). The drawing
 * copies Towntopia 0.1.2's math (fade in/out, pop-in scale, bob, backdrop, icon quad) with Towntopia's backdrops and the
 * addon's icon. An icon the client has no file for is left to Towntopia (logged once per id). Client main thread.
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class BubbleDrawHooks {
    static final String RENDER = "client.ThoughtBubbleRenderer#render(NpcEntity,PoseStack,MultiBufferSource,float)";

    // Towntopia 0.1.2 ThoughtBubbleRenderer
    private static final float BUBBLE_ASPECT = 1.25F;
    private static final float BUBBLE_WIDTH = 0.62F;
    private static final float ICON_SCALE = 0.656F;
    private static final float ICON_CENTRE = 0.4F;
    private static final float ICON_Z_LIFT = 0.005F;
    private static final float HEAD_CLEARANCE = 0.62F;
    private static final float FADE_TICKS = 4.0F;
    private static final float BOB_AMPLITUDE = 0.035F;
    private static final float BOB_PERIOD = 50.0F;
    private static final float CHILD_SCALE = 0.6F;
    private static final double MAX_RENDER_DIST_SQ = 1600.0;
    private static final int FULL_BRIGHT = 15728880;

    private static final FileToIdConverter FILES = FileToIdConverter.json(BubbleIcons.FOLDER);

    /**
     * What TownKit draws for one villager: the addon icon showing (or fading out), since {@code changedAt} (the
     * entity's tick count when the draw first saw the change).
     */
    private record DrawState(ResourceLocation icon, ResourceLocation texture, boolean speech, int changedAt, boolean showing) {
    }

    /** One addon bubble as last drawn (for the client check). */
    public record Drawn(int entityId, ResourceLocation icon, boolean speech) {
    }

    private static final Int2ObjectMap<DrawState> STATES = new Int2ObjectOpenHashMap<>();
    @Nullable
    private static Hooks.Entry attached;
    private static boolean warnedMissingHook;
    private static int drawCount;
    @Nullable
    private static Drawn lastDrawn;

    private BubbleDrawHooks() {
    }

    // --- icon files ------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Map<ResourceLocation, BubbleIcons.Entry>>() {
            @Override
            protected Map<ResourceLocation, BubbleIcons.Entry> prepare(ResourceManager manager, ProfilerFiller profiler) {
                return readFiles(manager);
            }

            @Override
            protected void apply(Map<ResourceLocation, BubbleIcons.Entry> read, ResourceManager manager, ProfilerFiller profiler) {
                CoreBubbles.ICONS.replaceFiles(read);
            }
        });
    }

    private static Map<ResourceLocation, BubbleIcons.Entry> readFiles(ResourceManager manager) {
        Map<ResourceLocation, BubbleIcons.Entry> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, Resource> f : FILES.listMatchingResources(manager).entrySet()) {
            ResourceLocation id = BubbleIcons.idOf(f.getKey());
            try (Reader reader = f.getValue().openAsReader()) {
                Optional<BubbleIcons.Entry> e = CoreBubbles.ICONS.parse(id, reader, t -> manager.getResource(t).isPresent());
                e.ifPresent(entry -> out.put(id, entry));
            } catch (Exception ex) {
                TownKit.LOG.warn("[TownKit] bubble icon {} ignored: can't read {} ({})", id, f.getKey(), ex.toString());
            }
        }
        return out;
    }

    // --- hook ------------------------------------------------------------------------------------------------------

    /** A look with an addon bubble arrived: the render hook is needed from now on. */
    static void ensureAttached() {
        if (attached != null) return;
        HookInfo h = HookManifest.get().byKey.get(RENDER);
        if (h == null) {
            if (!warnedMissingHook) {
                warnedMissingHook = true;
                TownKit.LOG.warn("[TownKit] can't find {}: addon bubbles aren't drawn", RENDER);
            }
            return;
        }
        attached = Hooks.add(h, TownKit.MODID, BubbleDrawHooks::beforeRender, false);
    }

    public static boolean attached() {
        return attached != null;
    }

    /** How many times TownKit drew an addon bubble (for the client check). */
    public static int drawCount() {
        return drawCount;
    }

    /** The addon bubble TownKit drew last, or null (for the client check). */
    @Nullable
    public static Drawn lastDrawn() {
        return lastDrawn;
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!STATES.isEmpty() && event.getLevel().isClientSide()) STATES.remove(event.getEntity().getId());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        STATES.clear();
        if (attached != null) {
            Hooks.remove(attached);
            attached = null;
        }
    }

    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        STATES.clear();
    }

    private static void beforeRender(HookCall call) {
        if (!(call.args()[0] instanceof NpcEntity npc)) return;
        DrawState s = state(npc);
        if (s == null) return;
        float partialTick = (Float) call.args()[3];
        float age = npc.tickCount + partialTick - s.changedAt();
        float phase = Mth.clamp(age / FADE_TICKS, 0.0F, 1.0F);
        if (!s.showing()) {
            // fading out: done, or a bubble of Towntopia's own came in its place
            if (phase >= 1.0F || npc.bubbleShowing()) {
                STATES.remove(npc.getId());
                return;
            }
        }
        draw(npc, s, (PoseStack) call.args()[1], (MultiBufferSource) call.args()[2], partialTick, phase);
        call.cancel();
    }

    /** What to draw for {@code npc} now, updated from its look; null when Towntopia draws. */
    @Nullable
    private static DrawState state(NpcEntity npc) {
        VillagerLook look = ClientLooks.get(npc);
        DrawState s = STATES.get(npc.getId());
        ResourceLocation want = look.bubble();
        BubbleIcons.Entry entry = want == null ? null : CoreBubbles.ICONS.resolve(want).orElse(null);
        if (entry != null) {
            if (s == null || !s.showing() || !s.icon().equals(want) || s.speech() != look.speech()) {
                s = new DrawState(want, entry.texture(), look.speech(), npc.tickCount, true);
                STATES.put(npc.getId(), s);
            }
        } else if (s != null && s.showing()) {
            s = new DrawState(s.icon(), s.texture(), s.speech(), npc.tickCount, false);
            STATES.put(npc.getId(), s);
        }
        return s;
    }

    /** Towntopia 0.1.2's {@code ThoughtBubbleRenderer.render}, with the addon icon's texture. */
    private static void draw(NpcEntity npc, DrawState s, PoseStack poseStack, MultiBufferSource buffers, float partialTick, float phase) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getEntityRenderDispatcher().distanceToSqr(npc) > MAX_RENDER_DIST_SQ) return;
        float alpha = s.showing() ? phase : 1.0F - phase;
        if (alpha <= 0.02F) return;
        float scale = s.showing() ? 0.75F + 0.25F * (alpha * (2.0F - alpha)) : 1.0F;
        int packedAlpha = (int) (alpha * 255.0F);
        float headroom = npc.getBbHeight() * (npc.getProfession() == Profession.CHILD ? CHILD_SCALE : 1.0F);
        float bob = BOB_AMPLITUDE * Mth.sin((npc.tickCount + partialTick) / BOB_PERIOD * (float) (Math.PI * 2));
        poseStack.pushPose();
        poseStack.translate(0.0F, headroom + HEAD_CLEARANCE + bob, 0.0F);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(scale, scale, scale);
        float width = BUBBLE_WIDTH;
        float height = width * BUBBLE_ASPECT;
        quad(poseStack, buffers, ThoughtBubbleRenderer.bubbleTexture(s.speech()), -width / 2.0F, 0.0F, width / 2.0F, height, 0.0F, packedAlpha);
        float iconSize = width * ICON_SCALE;
        float iconCentre = height - height * ICON_CENTRE;
        quad(poseStack, buffers, s.texture(), -iconSize / 2.0F, iconCentre - iconSize / 2.0F, iconSize / 2.0F, iconCentre + iconSize / 2.0F,
            ICON_Z_LIFT, packedAlpha);
        poseStack.popPose();
        drawCount++;
        lastDrawn = new Drawn(npc.getId(), s.icon(), s.speech());
    }

    private static void quad(PoseStack poseStack, MultiBufferSource buffers, ResourceLocation texture, float x0, float y0, float x1, float y1,
                             float z, int alpha) {
        VertexConsumer buffer = buffers.getBuffer(RenderType.text(texture));
        PoseStack.Pose pose = poseStack.last();
        vertex(buffer, pose, x0, y0, z, 0.0F, 1.0F, alpha);
        vertex(buffer, pose, x1, y0, z, 1.0F, 1.0F, alpha);
        vertex(buffer, pose, x1, y1, z, 1.0F, 0.0F, alpha);
        vertex(buffer, pose, x0, y1, z, 0.0F, 0.0F, alpha);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int alpha) {
        buffer.addVertex(pose, x, y, z).setColor(255, 255, 255, alpha).setUv(u, v).setLight(FULL_BRIGHT);
    }
}
