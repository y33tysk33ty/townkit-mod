package townkit.core.client.skin;

import com.alex.test.client.VillagerTheme;
import java.io.Reader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import townkit.api.hooks.HookCall;
import townkit.api.skin.SkinRef;
import townkit.core.TownKit;
import townkit.core.client.ClientLooks;

/**
 * Client side of per-villager skins: reads the addon skin files at each resource reload ({@link Skins}) and works out
 * what a villager with a skin from the server ({@link ClientLooks}) is drawn with ({@link SkinResolve}). Called from
 * {@link LookHooks}' texture hook before the look's own choice, and from its {@code rigSkin} hook; both are attached
 * only while some villager has a skin. The answer per skin is cached until the viewer's look, swaps or resources
 * change, so drawing allocates nothing. Render thread.
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class VillagerSkins {
    private static final FileToIdConverter FILES = FileToIdConverter.json(Skins.FOLDER);
    private static final String PREFIX = "dynamic_villagers_";
    /** Cached "draw as usual". */
    private static final Object AS_USUAL = new Object();

    static final SkinResolve RESOLVE = new SkinResolve(Looks.CLIENT, Skins.CLIENT, TownKit.LOG::warn);

    /** Answers by skin, valid while the stamps below are unchanged. */
    private static final Map<SkinRef, Object> DRAWN = new HashMap<>();
    private static int stampChanges = -1;
    private static int stampFiles = -1;
    private static int stampLooks = -1;
    /** Bumped by {@link LookHooks#refresh} (look choice, swaps, reloads). */
    private static volatile int looksVersion;

    private VillagerSkins() {
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Map<ResourceLocation, Looks.File>>() {
            @Override
            protected Map<ResourceLocation, Looks.File> prepare(ResourceManager manager, ProfilerFiller profiler) {
                Map<ResourceLocation, Looks.File> out = new HashMap<>();
                for (Map.Entry<ResourceLocation, Resource> f : FILES.listMatchingResources(manager).entrySet()) {
                    ResourceLocation id = Skins.idOf(f.getKey());
                    try (Reader reader = f.getValue().openAsReader()) {
                        Skins.CLIENT.parse(id, reader, rl -> manager.getResource(rl).isPresent()).ifPresent(s -> out.put(id, s));
                    } catch (Exception ex) {
                        TownKit.LOG.warn("[TownKit] skin {} ignored: can't read {} ({})", id, f.getKey(), ex.toString());
                    }
                }
                return out;
            }

            @Override
            protected void apply(Map<ResourceLocation, Looks.File> read, ResourceManager manager, ProfilerFiller profiler) {
                Skins.CLIENT.replace(read);
                RESOLVE.forget();
                TowntopiaSkins.INSTANCE.forget();
                looksChanged();
                if (!read.isEmpty()) TownKit.LOG.info("[TownKit] addon skins: {}", read.keySet());
            }
        });
    }

    /** The viewer's look, swaps or files changed: answers are worked out again. */
    static void looksChanged() {
        looksVersion++;
    }

    /**
     * How {@code entity} is drawn when it has a skin from the server, or null (no skin, or one this client can't draw:
     * draw it as usual). {@code look} is the viewer's chosen addon look, null for one of Towntopia's.
     */
    @Nullable
    static SkinResolve.Drawn drawn(Entity entity, @Nullable Looks.Def look) {
        SkinRef ref = ClientLooks.get(entity).skin();
        if (ref == null) return null;
        int changes = VillagerTheme.changesVersion();
        int files = VillagerTheme.filesVersion();
        int looks = looksVersion;
        if (changes != stampChanges || files != stampFiles || looks != stampLooks) {
            DRAWN.clear();
            stampChanges = changes;
            stampFiles = files;
            stampLooks = looks;
        }
        Object known = DRAWN.get(ref);
        if (known == null) {
            SkinResolve.Drawn d = RESOLVE.resolve(ref, look, LookChoice::swapFrom, TowntopiaSkins.INSTANCE);
            known = d == null ? AS_USUAL : d;
            if (DRAWN.size() > 256) DRAWN.clear();
            DRAWN.put(ref, known);
        }
        return known == AS_USUAL ? null : (SkinResolve.Drawn) known;
    }

    /** After {@code VillagerGeoModel#rigSkin}: a Towntopia skin's own rig key, so a look with its own body for it draws that. */
    static void afterRig(HookCall call) {
        if (!(call.args()[0] instanceof Entity entity) || LookHooks.previewing()) return;
        SkinResolve.Drawn d = drawn(entity, LookHooks.activeDef());
        if (d != null && d.rig() != null) call.setReturnValue(d.rig());
    }

    /** For the client check: what a skin resolves to now, null for "as usual". */
    @Nullable
    public static SkinResolve.Drawn resolveForCheck(Entity entity) {
        return drawn(entity, LookHooks.active().orElse(null));
    }

    /**
     * Towntopia's skins by key, as {@code VillagerTheme.Skin}s (one per key, made once: Towntopia keeps each in a
     * static list). A key is known when Towntopia has its Classic file, or (for skins only some looks have) a file in
     * one of its other looks.
     */
    static final class TowntopiaSkins implements SkinResolve.Towntopia {
        static final TowntopiaSkins INSTANCE = new TowntopiaSkins();

        private final Map<String, VillagerTheme.Skin> skins = new HashMap<>();
        private final Set<String> unknown = new HashSet<>();

        @Override
        public boolean knows(String key) {
            return skin(key) != null;
        }

        @Nullable
        private VillagerTheme.Skin skin(String key) {
            VillagerTheme.Skin s = this.skins.get(key);
            if (s != null || this.unknown.contains(key)) return s;
            ResourceManager manager = Minecraft.getInstance().getResourceManager();
            String file = PREFIX + key;
            if (exists(manager, "textures/entity/" + file + ".png")) {
                s = VillagerTheme.Skin.of(file);
            } else {
                for (VillagerTheme t : VillagerTheme.values()) {
                    if (t != VillagerTheme.CLASSIC && exists(manager, "textures/entity/" + t.name().toLowerCase(Locale.ROOT) + "/" + file + ".png")) {
                        s = VillagerTheme.Skin.own(file);
                        break;
                    }
                }
            }
            if (s == null) this.unknown.add(key);
            else this.skins.put(key, s);
            return s;
        }

        private static boolean exists(ResourceManager manager, String path) {
            return manager.getResource(ResourceLocation.fromNamespaceAndPath("towntopia", path)).isPresent();
        }

        @Nullable
        @Override
        public ResourceLocation texture(String key, @Nullable String look) {
            VillagerTheme.Skin s = skin(key);
            if (s == null) return null;
            if (look == null) return s.get();
            VillagerTheme theme = LookHooks.Themes.BY_NAME.get(look);
            if (theme == null) return s.get();
            VillagerTheme.drawAs(theme);
            try {
                return s.get();
            } finally {
                VillagerTheme.drawAs(null);
            }
        }

        @Override
        public ResourceLocation inUse() {
            return LookHooks.Themes.id(VillagerTheme.current());
        }

        /** Forgets which keys are unknown (a resource reload may add them); the skins made stay (Towntopia re-reads their files). */
        void forget() {
            this.unknown.clear();
        }
    }
}
