package townkit.core.client.anim;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtil;
import townkit.core.TownKit;
import townkit.core.client.ClientLooks;

/**
 * Draws props ({@link Props}) on Towntopia's people: a GeckoLib render layer on the renderers of its villagers, Mayor,
 * visitors and nomads (all {@code NpcEntity}).
 *
 * <p>The layer is in each renderer's layer list only while some prop could be drawn ({@link #refresh}, {@link #needed}):
 * a prop is registered for one of Towntopia's moves, or a villager's look carries a move with props (registered or in
 * its file), or a client move listener is registered while props exist. While it is in the list Towntopia's renderer offers it every bone (its own layers say which
 * bones they use through a package-private interface TownKit can't implement), so Towntopia's skip of hidden bone
 * subtrees is off for every villager: that is why it is added only while needed.
 *
 * <p>Per villager drawn: the props of the move it shows ({@link MoveHooks#currentMove}: its file's, then the registered
 * ones); each is drawn if {@link Props#drawable} (file, model and texture there, the villager's model has the bone;
 * otherwise logged once per prop and model). Like Towntopia's own parts it keeps the bone's pose while the body is drawn
 * ({@link #renderForBone}) and draws after the body ({@link #render}), so the shared buffer isn't switched mid-body.
 * Not drawn for invisible ones or beyond {@value #MAX_DISTANCE} blocks. Render thread.
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class PropLayer extends GeoRenderLayer<NpcEntity> {
    static final int MAX_DISTANCE = 48;
    private static final FileToIdConverter FILES = FileToIdConverter.json(Props.FOLDER);

    /**
     * The GeckoLib renderers of Towntopia's people (villagers, Mayor, visitors, nomads: all {@code NpcEntity}), from
     * the last renderer reload, and the layer in each one's list (null while it isn't there).
     */
    private static final Map<GeoEntityRenderer<?>, PropLayer> RENDERERS = new java.util.LinkedHashMap<>();
    /** Whether each texture exists, cleared at each resource reload. */
    private static final Map<ResourceLocation, Boolean> TEXTURES = new HashMap<>();
    private static int drawn;

    /** The villager whose props are being drawn, and for each of them where its bone is. */
    @Nullable
    private NpcEntity drawingFor;
    private final List<Props.Def> props = new ArrayList<>();
    private final List<Matrix4f> poses = new ArrayList<>();
    private final List<Matrix3f> normals = new ArrayList<>();
    private boolean[] posed = new boolean[4];
    private int light;

    private PropLayer(GeoRenderer<NpcEntity> renderer) {
        super(renderer);
    }

    // --- files and the renderer ------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Map<ResourceLocation, Props.Def>>() {
            @Override
            protected Map<ResourceLocation, Props.Def> prepare(ResourceManager manager, ProfilerFiller profiler) {
                Map<ResourceLocation, Props.Def> out = new HashMap<>();
                for (Map.Entry<ResourceLocation, Resource> f : FILES.listMatchingResources(manager).entrySet()) {
                    ResourceLocation id = Props.idOf(f.getKey());
                    try (Reader reader = f.getValue().openAsReader()) {
                        Props.parse(id, reader).ifPresent(d -> out.put(id, d));
                    } catch (Exception ex) {
                        TownKit.LOG.warn("[TownKit] prop {} ignored: can't read {} ({})", id, f.getKey(), ex.toString());
                    }
                }
                return out;
            }

            @Override
            protected void apply(Map<ResourceLocation, Props.Def> read, ResourceManager manager, ProfilerFiller profiler) {
                Props.CLIENT.replace(read);
                TEXTURES.clear();
                if (!read.isEmpty()) TownKit.LOG.info("[TownKit] addon props: {}", read.keySet());
                refresh();
            }
        });
    }

    /** Towntopia's renderers were (re)made: keep those of its people; the layer goes back in if needed. */
    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        synchronized (PropLayer.class) {
            RENDERERS.clear();
            for (var type : List.of(DynamicVillages.NPC, DynamicVillages.MAYOR, DynamicVillages.VISITOR, DynamicVillages.NOMAD)) {
                EntityRenderer<?> r = event.getRenderer(type.get());
                if (r instanceof GeoEntityRenderer<?> g) {
                    RENDERERS.put(g, null);
                } else {
                    TownKit.LOG.warn("[TownKit] Towntopia's renderer for {} isn't a GeckoLib renderer: no props on it", type.getId());
                }
            }
        }
        refresh();
    }

    /**
     * Whether some villager could be drawn with a prop now: a prop is registered for one of Towntopia's own moves (shown
     * with no look), a move some villager's look carries (its move, or what a swap plays) has props, or a client move
     * listener is registered while a prop or a props move file exists (it can pick any move).
     *
     * @param carried the moves the clients' looks carry now ({@link ClientLooks#carriedMoves})
     */
    static boolean needed(Props props, Moves moves, java.util.Collection<ResourceLocation> carried, boolean listeners) {
        if (props.anyRegisteredIn("towntopia")) return true;
        if (listeners && (props.anyRegistered() || moves.anyProps())) return true;
        for (ResourceLocation move : carried) {
            if (!props.propsOf(move, moves.fileProps(move)).isEmpty()) return true;
        }
        return false;
    }

    /** Puts the layer in the renderer's list while some prop could be drawn, takes it out otherwise. Render thread. */
    public static synchronized void refresh() {
        boolean needed = needed(Props.CLIENT, Moves.CLIENT, ClientLooks.carriedMoves(), Moves.CLIENT.hasListeners());
        for (Map.Entry<GeoEntityRenderer<?>, PropLayer> e : RENDERERS.entrySet()) {
            List<GeoRenderLayer<?>> layers = layersOf(e.getKey());
            if (needed && e.getValue() == null) {
                PropLayer layer = new PropLayer(asNpcRenderer(e.getKey()));
                layers.add(layer);
                e.setValue(layer);
            } else if (!needed && e.getValue() != null) {
                layers.remove(e.getValue());
                e.setValue(null);
            }
        }
    }

    /**
     * Every renderer here draws an {@code NpcEntity} subclass, so a layer of {@code NpcEntity} fits each: it is only
     * ever handed the entities that renderer draws.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static GeoRenderer<NpcEntity> asNpcRenderer(GeoEntityRenderer<?> r) {
        return (GeoRenderer) r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<GeoRenderLayer<?>> layersOf(GeoEntityRenderer<?> r) {
        return (List) r.getRenderLayers();
    }

    /** Whether the layer is in the villager renderer's list now (for the client check). */
    public static synchronized boolean inRenderer() {
        for (Map.Entry<GeoEntityRenderer<?>, PropLayer> e : RENDERERS.entrySet()) {
            if (e.getKey() instanceof com.alex.test.client.NpcGeoRenderer && e.getValue() != null
                && e.getKey().getRenderLayers().contains(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** How many renderers of Towntopia's people hold the layer now (for the client check). */
    public static synchronized int layeredRenderers() {
        int n = 0;
        for (Map.Entry<GeoEntityRenderer<?>, PropLayer> e : RENDERERS.entrySet()) {
            if (e.getValue() != null && e.getKey().getRenderLayers().contains(e.getValue())) n++;
        }
        return n;
    }

    /** How many props were drawn since the game started (for the client check). */
    public static int drawn() {
        return drawn;
    }

    // --- drawing ---------------------------------------------------------------------------------------------------

    /** Picks the props to draw on {@code npc} this frame (none when it is invisible or far). */
    private void begin(NpcEntity npc) {
        drawingFor = npc;
        props.clear();
        if (npc.isInvisible() || Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(npc) > MAX_DISTANCE * MAX_DISTANCE) return;
        ResourceLocation move = MoveHooks.currentMove(npc);
        if (move == null) return;
        List<ResourceLocation> ids = Props.CLIENT.propsOf(move, Moves.CLIENT.fileProps(move));
        if (ids.isEmpty()) return;
        ResourceLocation modelId = getGeoModel().getModelResource(npc);
        BakedGeoModel model = GeckoLibCache.getBakedModels().get(modelId);
        for (ResourceLocation id : ids) {
            Props.Def d = Props.CLIENT.drawable(id, modelId, b -> model != null && model.getBone(b).isPresent(),
                g -> GeckoLibCache.getBakedModels().containsKey(g), PropLayer::textureExists);
            if (d != null) props.add(d);
        }
        if (posed.length < props.size()) posed = new boolean[props.size()];
        while (poses.size() < props.size()) {
            poses.add(new Matrix4f());
            normals.add(new Matrix3f());
        }
        java.util.Arrays.fill(posed, false);
    }

    private static boolean textureExists(ResourceLocation texture) {
        return TEXTURES.computeIfAbsent(texture, t -> Minecraft.getInstance().getResourceManager().getResource(t).isPresent());
    }

    @Override
    public void renderForBone(PoseStack poseStack, NpcEntity entity, GeoBone bone, RenderType renderType, MultiBufferSource bufferSource,
                              VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (entity != drawingFor) begin(entity);
        if (props.isEmpty()) return;
        String name = bone.getName();
        for (int i = 0; i < props.size(); i++) {
            if (posed[i] || !props.get(i).bone().equals(name)) continue;
            poseStack.pushPose();
            RenderUtil.translateToPivotPoint(poseStack, bone);
            poses.get(i).set(poseStack.last().pose());
            normals.get(i).set(poseStack.last().normal());
            poseStack.popPose();
            posed[i] = true;
            light = packedLight;
        }
    }

    @Override
    public void render(PoseStack poseStack, NpcEntity entity, BakedGeoModel bakedModel, @Nullable RenderType renderType, MultiBufferSource bufferSource,
                       @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (entity != drawingFor) {
            drawingFor = null;
            return;
        }
        drawingFor = null;
        for (int i = 0; i < props.size(); i++) {
            if (!posed[i]) continue;
            Props.Def d = props.get(i);
            BakedGeoModel prop = GeckoLibCache.getBakedModels().get(d.geo());
            if (prop == null) continue;
            PoseStack at = new PoseStack();
            at.last().pose().set(poses.get(i));
            at.last().normal().set(normals.get(i));
            VertexConsumer out = bufferSource.getBuffer(RenderType.entityCutoutNoCull(d.texture()));
            for (GeoBone b : prop.topLevelBones()) drawBone(at, b, out, light);
            drawn++;
        }
        props.clear();
    }

    private void drawBone(PoseStack at, GeoBone bone, VertexConsumer out, int packedLight) {
        at.pushPose();
        RenderUtil.prepMatrixForBone(at, bone);
        GeoRenderer<NpcEntity> r = getRenderer();
        for (GeoCube cube : bone.getCubes()) {
            at.pushPose();
            r.renderCube(at, cube, out, packedLight, OverlayTexture.NO_OVERLAY, -1);
            at.popPose();
        }
        for (GeoBone child : bone.getChildBones()) drawBone(at, child, out, packedLight);
        at.popPose();
    }
}
