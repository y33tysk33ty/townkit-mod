package townkit.core.client.skin;

import com.alex.test.Tr;
import com.alex.test.client.VillagerGeoModel;
import com.alex.test.client.VillagerTheme;
import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.PathfinderMob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import software.bernie.geckolib.animation.AnimationProcessor;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.api.skin.Look;
import townkit.core.TownKit;
import townkit.core.client.ClientLooks;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;

/**
 * Client side of addon looks: reads the look files at each resource reload ({@link Looks}), draws the chosen look
 * ({@link LookChoice}) and lists the looks on the Theme page of the Mayor screen. Hooks, each attached only while
 * needed (with no look file nothing is attached; with looks but none chosen only the Theme page row):
 * <ul>
 *   <li>{@code VillagerGeoModel#getTextureResource} after, while a look is chosen: the skin's file from
 *   {@link Looks#pick} (swap line, the look's file, else Towntopia's Modern skin, which TownKit gets from Towntopia by
 *   calling the method again with {@code VillagerTheme.drawAs(MODERN)}, so Towntopia's own fallbacks and dirt stages
 *   apply). Left alone while Towntopia draws a preview for one of its own looks ({@code drawAs} set).</li>
 *   <li>{@code VillagerGeoModel#getModelResource} after: the look's body model for that skin, if it has one.</li>
 *   <li>{@code VillagerGeoModel#handleAnimations} after: Towntopia reads a look from its texture's folder, so an
 *   addon file reads as its Classic look and gets Classic's accessory pieces (hats, packs) shown; for a villager drawn
 *   with an addon file TownKit hides every accessory piece, as Towntopia does for London and Rustic, whose rigs carry
 *   their own.</li>
 *   <li>{@code ThemeSets#inUse} after: none of Towntopia's looks shows as in use, so each card offers "Use";
 *   {@code ThemeSets#lookSetLine} after: the "Your look" card names the addon look; {@code ClientConfig#setVillagerTheme}
 *   before: picking one of Towntopia's looks drops the addon look.</li>
 *   <li>{@code ThemeTab#render} after and {@code ThemeTab#mouseClicked} before, while any look exists: a "More looks"
 *   row under Towntopia's cards, one button per addon look; a click picks it.</li>
 * </ul>
 * A villager with a skin of its own (addon code on the server, {@code TownKitSkins.set}) is drawn first by
 * {@link VillagerSkins}, in the same texture hook, before any look; the texture, model and piece hooks and an after-hook
 * on {@code VillagerGeoModel#rigSkin} (a Towntopia skin's rig key) stay attached while any villager the client knows has
 * one ({@link ClientLooks#anySkin}).
 * The pack page of Towntopia's Theme page (one look's skins) gets its addon buttons from {@link LookPackHooks}. While
 * one of Towntopia's looks is in use, the texture, model and piece hooks stay attached only if a swap line puts an
 * addon look's skin into a Towntopia look. Render thread; {@link #refreshOnClientThread} from any thread.
 */
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class LookHooks {
    static final String TEXTURE = "client.VillagerGeoModel#getTextureResource(PathfinderMob)";
    static final String MODEL = "client.VillagerGeoModel#getModelResource(PathfinderMob)";
    static final String ANIMATE = "client.VillagerGeoModel#handleAnimations(PathfinderMob,long,AnimationState,float)";
    static final String IN_USE = "ui.screen.mayor.ThemeSets#inUse(VillagerTheme)";
    static final String LOOK_LINE = "ui.screen.mayor.ThemeSets#lookSetLine(VillagerTheme,int)";
    static final String TOWNTOPIA_PICKED = "ClientConfig#setVillagerTheme(VillagerTheme)";
    static final String THEME_RENDER = "ui.screen.mayor.ThemeTab#render(GuiGraphics,int,int)";
    static final String THEME_CLICK = "ui.screen.mayor.ThemeTab#mouseClicked(double,double,int)";
    static final String RIG = "client.VillagerGeoModel#rigSkin(PathfinderMob)";
    static final List<String> KEYS = List.of(TEXTURE, MODEL, ANIMATE, IN_USE, LOOK_LINE, TOWNTOPIA_PICKED, THEME_RENDER, THEME_CLICK,
        LookPackHooks.RENDER, LookPackHooks.CLICK, RIG);

    // Towntopia 0.1.2 ThemeTab layout
    private static final int PAGE_X = 14;
    private static final int ROW_GAP = 2;
    private static final int CHIP_H = 12;
    private static final int CHIP_GAP = 4;
    private static final int CHIP_PAD = 5;
    private static final int CHIP_MAX_TEXT = 120;
    private static final int BORDER = -12237499;
    private static final int BG = -14079703;
    private static final int BG_HOVER = -13092808;
    private static final int IN_USE_BG = -9719950;
    private static final int TEXT = -789517;
    private static final int TEXT_DARK = -15461356;
    private static final int TEXT_GREY = -5592406;

    private static final FileToIdConverter FILES = FileToIdConverter.json(Looks.FOLDER);

    /** When a hook is needed. */
    private enum Need {
        /** An addon look is chosen. */
        CHOSEN,
        /**
         * An addon look is chosen, a swap line puts an addon look's skin into one of Towntopia's looks, or a villager
         * has a skin of its own (from the server).
         */
        DRAWING,
        /** A villager has a skin of its own. */
        SKINS,
        /** Any addon look exists. */
        ANY_LOOK
    }

    private enum Slot {
        DRAW_TEXTURE(TEXTURE, true, LookHooks::afterTexture, Need.DRAWING),
        DRAW_MODEL(MODEL, true, LookHooks::afterModel, Need.DRAWING),
        HIDE_PIECES(ANIMATE, true, LookHooks::afterAnimate, Need.DRAWING),
        NONE_IN_USE(IN_USE, true, LookHooks::afterInUse, Need.CHOSEN),
        LOOK_NAME(LOOK_LINE, true, LookHooks::afterLookLine, Need.CHOSEN),
        BACK_TO_TOWNTOPIA(TOWNTOPIA_PICKED, false, LookHooks::beforeTowntopiaPicked, Need.CHOSEN),
        ROW(THEME_RENDER, true, LookHooks::afterThemeRender, Need.ANY_LOOK),
        ROW_CLICK(THEME_CLICK, false, LookHooks::beforeThemeClick, Need.ANY_LOOK),
        PACK(LookPackHooks.RENDER, true, LookPackHooks::afterRender, Need.ANY_LOOK),
        PACK_CLICK(LookPackHooks.CLICK, false, LookPackHooks::beforeClick, Need.ANY_LOOK),
        RIG_KEY(RIG, true, VillagerSkins::afterRig, Need.SKINS);

        final String key;
        final boolean after;
        final HookListener listener;
        final Need need;

        Slot(String key, boolean after, HookListener listener, Need need) {
            this.key = key;
            this.after = after;
            this.listener = listener;
            this.need = need;
        }
    }

    /** One addon look's button on the Theme page, as last drawn (also for the client check). */
    public record Chip(ResourceLocation look, int x, int y, int width, int height, boolean inUse) {
    }

    private static final Map<Slot, Hooks.Entry> ATTACHED = new EnumMap<>(Slot.class);
    private static final Map<GeoBone, GeoBone[]> PIECES_BY_RIG = new IdentityHashMap<>();
    private static final java.util.Set<ResourceLocation> UNBAKED_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The chosen look while it is drawn; null while Towntopia's own look is. */
    @Nullable
    private static volatile Looks.Def active;
    /** An addon look is chosen, or a swap line needs drawing: the texture hook does more than villagers' own skins. */
    private static volatile boolean looksDrawing;
    /** Some villager has a skin of its own: the texture hook asks {@link VillagerSkins} first. */
    private static volatile boolean skinsLive;
    /** TownKit is asking Towntopia for one of its own looks' skin: its own hooks stand aside. */
    private static boolean inner;
    /** The villager whose texture was picked last, and the addon file picked (null: one of Towntopia's). */
    @Nullable
    private static Object lastEntity;
    @Nullable
    private static Looks.File lastFile;
    private static List<Chip> lastRow = List.of();
    private static boolean warnedMissing;
    private static int piecesHidden;

    private static boolean triedForced;
    @Nullable
    private static VarHandle forced;
    private static boolean triedPieces;
    @Nullable
    private static String[] pieceNames;
    @Nullable
    private static ThemeLayout layout;
    private static boolean layoutFailed;

    private LookHooks() {
    }

    // --- look files ------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Map<ResourceLocation, Looks.Def>>() {
            @Override
            protected Map<ResourceLocation, Looks.Def> prepare(ResourceManager manager, ProfilerFiller profiler) {
                return readFiles(manager);
            }

            @Override
            protected void apply(Map<ResourceLocation, Looks.Def> read, ResourceManager manager, ProfilerFiller profiler) {
                Looks.CLIENT.replace(read);
                PIECES_BY_RIG.clear();
                UNBAKED_WARNED.clear();
                if (!read.isEmpty()) TownKit.LOG.info("[TownKit] addon looks: {}", read.values());
                refresh();
            }
        });
    }

    private static Map<ResourceLocation, Looks.Def> readFiles(ResourceManager manager) {
        Map<ResourceLocation, Looks.Def> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, Resource> f : FILES.listMatchingResources(manager).entrySet()) {
            ResourceLocation id = Looks.idOf(f.getKey());
            try (Reader reader = f.getValue().openAsReader()) {
                Looks.CLIENT.parse(id, reader, folder -> filesIn(manager, folder)).ifPresent(d -> out.put(id, d));
            } catch (Exception ex) {
                TownKit.LOG.warn("[TownKit] look {} ignored: can't read {} ({})", id, f.getKey(), ex.toString());
            }
        }
        return out;
    }

    /** The files directly inside {@code folder} (which ends in {@code /}). */
    private static List<ResourceLocation> filesIn(ResourceManager manager, ResourceLocation folder) {
        String path = folder.getPath();
        String dir = path.substring(0, path.length() - 1);
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation f : manager.listResources(dir, rl -> true).keySet()) {
            if (f.getNamespace().equals(folder.getNamespace()) && f.getPath().lastIndexOf('/') == dir.length()) out.add(f);
        }
        return out;
    }

    // --- attaching -------------------------------------------------------------------------------------------------

    /**
     * {@link #refresh} on the render thread: now if called there (or before the game runs), else queued. Config events
     * come on NeoForge's config file-watcher thread, while the hooks and their state are the render thread's.
     */
    public static void refreshOnClientThread() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isSameThread()) refresh();
        else mc.execute(LookHooks::refresh);
    }

    /** Attaches what the looks and the choice need now and detaches the rest. Render thread. */
    public static synchronized void refresh() {
        Looks.Def d = Looks.CLIENT.current(LookChoice.chosen()).orElse(null);
        active = d;
        boolean anyLook = !Looks.CLIENT.looks().isEmpty();
        boolean drawing = anyLook && (d != null || LookChoice.anyTowntopiaInUse());
        boolean skins = ClientLooks.anySkin();
        looksDrawing = drawing;
        skinsLive = skins;
        for (Slot slot : Slot.values()) {
            set(slot, switch (slot.need) {
                case CHOSEN -> d != null;
                case DRAWING -> drawing || skins;
                case SKINS -> skins;
                case ANY_LOOK -> anyLook;
            });
        }
        VillagerSkins.looksChanged();
        lastEntity = null;
        lastFile = null;
        if (!anyLook) {
            lastRow = List.of();
            LookPackHooks.forget();
        }
    }

    private static void set(Slot slot, boolean wanted) {
        Hooks.Entry e = ATTACHED.get(slot);
        if (wanted && e == null) {
            HookInfo h = HookManifest.get().byKey.get(slot.key);
            if (h == null) {
                if (!warnedMissing) {
                    warnedMissing = true;
                    TownKit.LOG.warn("[TownKit] can't find {}: addon looks can't be fully drawn or picked", slot.key);
                }
                return;
            }
            ATTACHED.put(slot, Hooks.add(h, TownKit.MODID, slot.listener, slot.after));
        } else if (!wanted && e != null) {
            Hooks.remove(e);
            ATTACHED.remove(slot);
        }
    }

    /** The hooks attached now, as {@code "before <key>"} or {@code "after <key>"} (for the client check). */
    public static synchronized List<String> attachedKeys() {
        List<String> out = new ArrayList<>();
        ATTACHED.forEach((slot, e) -> out.add((slot.after ? "after " : "before ") + slot.key));
        return out;
    }

    /** How many times TownKit hid Towntopia's accessory pieces on a villager drawn with an addon file (for the client check). */
    public static int piecesHidden() {
        return piecesHidden;
    }

    /** The addon look buttons as last drawn on the Theme page. */
    public static List<Chip> lastRow() {
        return lastRow;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        lastEntity = null;
        lastFile = null;
        lastRow = List.of();
        LookPackHooks.forget();
        PIECES_BY_RIG.clear();
    }

    // --- drawing ---------------------------------------------------------------------------------------------------

    private static void afterTexture(HookCall call) {
        if (inner || previewing()) return;
        if (!(call.returnValue() instanceof ResourceLocation texture) || !(call.args()[0] instanceof PathfinderMob entity)) return;
        Looks.Def look = active;
        // a villager's own skin (from the server) comes first; one this client can't draw falls through to the look
        SkinResolve.Drawn own = skinsLive ? VillagerSkins.drawn(entity, look) : null;
        if (own != null) {
            lastEntity = entity;
            lastFile = own.file();
            call.setReturnValue(own.texture());
            return;
        }
        if (!looksDrawing) {
            lastEntity = entity;
            lastFile = null;
            return;
        }
        Looks.SkinName skin = Looks.skinName(texture);
        if (look == null) {
            // one of Towntopia's looks is in use: only the player's swaps to an addon look's skin change it
            Looks.File f = skin == null ? null : Looks.CLIENT.pickWhileTowntopia(skin, LookChoice.swapFrom(Themes.id(VillagerTheme.current()), skin.key()));
            lastEntity = entity;
            lastFile = f;
            if (f != null) call.setReturnValue(f.texture());
            return;
        }
        Looks.Pick pick = skin == null ? Looks.Towntopia.MODERN : Looks.CLIENT.pick(look, skin, LookChoice.swapFrom(look.id(), skin.key()));
        lastEntity = entity;
        if (pick instanceof Looks.File f) {
            lastFile = f;
            call.setReturnValue(f.texture());
        } else {
            lastFile = null;
            VillagerTheme theme = Themes.BY_NAME.get(((Looks.Towntopia) pick).look());
            // without Towntopia's preview flag readable, asking Towntopia again could reset a preview's look: don't
            ResourceLocation drawn = theme == null || forced == null ? null : drawnIn(call.self(), entity, theme);
            if (drawn != null) call.setReturnValue(drawn);
        }
    }

    /** Towntopia's skin for {@code entity} as its look {@code theme} draws it. */
    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResourceLocation drawnIn(Object model, PathfinderMob entity, VillagerTheme theme) {
        inner = true;
        VillagerTheme.drawAs(theme);
        try {
            return ((VillagerGeoModel) model).getTextureResource(entity);
        } finally {
            VillagerTheme.drawAs(null);
            inner = false;
        }
    }

    private static void afterModel(HookCall call) {
        if (inner || previewing()) return;
        Looks.File f = lastFile;
        // Towntopia's getModelResource reads the texture first (for its own rigs): the pick above is this villager's
        if (f != null && f.geo() != null && call.args()[0] == lastEntity && baked(f.geo())) call.setReturnValue(f.geo());
    }

    /**
     * Whether GeckoLib has baked {@code geo}: GeckoLib throws for a model it hasn't (one that failed to load), so such a
     * model is skipped, logged once per id, and Towntopia's model is drawn.
     */
    private static boolean baked(ResourceLocation geo) {
        if (GeckoLibCache.getBakedModels().containsKey(geo)) return true;
        if (UNBAKED_WARNED.add(geo)) {
            TownKit.LOG.warn("[TownKit] skin model {} isn't loaded (GeckoLib couldn't read it): Towntopia's model is drawn instead", geo);
        }
        return false;
    }

    private static void afterAnimate(HookCall call) {
        if (inner || lastFile == null || call.args()[0] != lastEntity || previewing()) return;
        hidePieces(call.self());
    }

    /** Hides every one of Towntopia's accessory pieces on the rig being drawn (cached per rig). */
    private static void hidePieces(Object model) {
        String[] names = pieceNames();
        if (names == null || !(model instanceof GeoModel<?> geo)) return;
        AnimationProcessor<?> p = geo.getAnimationProcessor();
        GeoBone head = p.getBone("head");
        GeoBone[] bones = head == null ? null : PIECES_BY_RIG.get(head);
        if (bones == null) {
            bones = new GeoBone[names.length];
            for (int i = 0; i < names.length; i++) bones[i] = p.getBone(names[i]);
            if (head != null) {
                if (PIECES_BY_RIG.size() > 256) PIECES_BY_RIG.clear();
                PIECES_BY_RIG.put(head, bones);
            }
        }
        for (GeoBone b : bones) {
            if (b != null) b.setHidden(true);
        }
        piecesHidden++;
    }

    /** Whether Towntopia is drawing a preview of one of its own looks ({@code VillagerTheme.drawAs} set). */
    static boolean previewing() {
        if (!triedForced) {
            triedForced = true;
            try {
                forced = MethodHandles.privateLookupIn(VillagerTheme.class, MethodHandles.lookup())
                    .findStaticVarHandle(VillagerTheme.class, "forced", VillagerTheme.class);
            } catch (ReflectiveOperationException | RuntimeException e) {
                TownKit.LOG.warn("[TownKit] can't read VillagerTheme.forced ({}): the Theme page's previews show the addon look too", e.toString());
            }
        }
        VarHandle h = forced;
        return h != null && h.get() != null;
    }

    @Nullable
    private static String[] pieceNames() {
        if (!triedPieces) {
            triedPieces = true;
            try {
                String[] read = (String[]) MethodHandles.privateLookupIn(VillagerGeoModel.class, MethodHandles.lookup())
                    .findStaticVarHandle(VillagerGeoModel.class, "PIECE_BONES", String[].class).get();
                pieceNames = Arrays.copyOf(read, read.length);
            } catch (ReflectiveOperationException | RuntimeException e) {
                TownKit.LOG.warn("[TownKit] can't read VillagerGeoModel.PIECE_BONES ({}): addon look skins show Towntopia's Classic accessory pieces",
                    e.toString());
            }
        }
        return pieceNames;
    }

    // --- Theme page ------------------------------------------------------------------------------------------------

    private static void afterInUse(HookCall call) {
        if (active != null) call.setReturnValue(false);
    }

    private static void afterLookLine(HookCall call) {
        Looks.Def look = active;
        if (look != null) call.setReturnValue(Tr.s("screen.dynamicvillages.mayor.theme.look_set", "%s skins", look.look().name().getString()));
    }

    private static void beforeTowntopiaPicked(HookCall call) {
        if (LookChoice.chosen() != null) LookChoice.choose(null);
    }

    private static void afterThemeRender(HookCall call) {
        ThemeLayout l = layout(call.self());
        List<Look> looks = Looks.CLIENT.looks();
        if (l == null || looks.isEmpty()) return;
        GuiGraphics g = (GuiGraphics) call.args()[0];
        int mouseX = (Integer) call.args()[1];
        int mouseY = (Integer) call.args()[2];
        Font font = Minecraft.getInstance().font;
        Object tab = call.self();
        int x = l.left(tab) + PAGE_X;
        int right = x + l.innerW(tab);
        int y = l.top(tab) + l.cardsBottom(tab) + ROW_GAP;
        String label = Component.translatableWithFallback("screen.townkit.theme.more_looks", "More looks:").getString();
        g.drawString(font, label, x, y + 2, TEXT_GREY, false);
        x += font.width(label) + CHIP_GAP + 2;
        Looks.Def in = active;
        List<Chip> drawn = new ArrayList<>();
        for (Look look : looks) {
            String name = font.plainSubstrByWidth(look.name().getString(), CHIP_MAX_TEXT);
            int w = font.width(name) + 2 * CHIP_PAD;
            if (x + w > right) break;
            boolean inUse = in != null && in.id().equals(look.id());
            boolean hovered = !inUse && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CHIP_H;
            g.fill(x, y, x + w, y + CHIP_H, inUse ? IN_USE_BG : BORDER);
            if (!inUse) g.fill(x + 1, y + 1, x + w - 1, y + CHIP_H - 1, hovered ? BG_HOVER : BG);
            g.drawString(font, name, x + CHIP_PAD, y + 2, inUse ? TEXT_DARK : TEXT, false);
            drawn.add(new Chip(look.id(), x, y, w, CHIP_H, inUse));
            x += w + CHIP_GAP;
        }
        lastRow = List.copyOf(drawn);
    }

    private static void beforeThemeClick(HookCall call) {
        if ((Integer) call.args()[2] != 0) return;
        double mouseX = (Double) call.args()[0];
        double mouseY = (Double) call.args()[1];
        for (Chip c : lastRow) {
            if (mouseX >= c.x() && mouseX < c.x() + c.width() && mouseY >= c.y() && mouseY < c.y() + c.height()) {
                if (!c.inUse()) LookChoice.choose(c.look());
                call.cancelWith(true);
                return;
            }
        }
    }

    @Nullable
    private static ThemeLayout layout(Object themeTab) {
        if (layout != null || layoutFailed) return layout;
        try {
            layout = new ThemeLayout(themeTab.getClass());
        } catch (ReflectiveOperationException | RuntimeException e) {
            layoutFailed = true;
            TownKit.LOG.warn("[TownKit] can't read Towntopia's Theme page layout ({}): addon looks aren't listed there", e.toString());
        }
        return layout;
    }

    /** Method handles on Towntopia's ThemeTab (and its MayorPart base). */
    private static final class ThemeLayout {
        private final MethodHandle left;
        private final MethodHandle top;
        private final MethodHandle innerW;
        private final MethodHandle cardsBottom;

        ThemeLayout(Class<?> themeTab) throws ReflectiveOperationException {
            MethodHandles.Lookup tab = MethodHandles.privateLookupIn(themeTab, MethodHandles.lookup());
            Class<?> part = themeTab.getSuperclass();
            MethodHandles.Lookup base = MethodHandles.privateLookupIn(part, MethodHandles.lookup());
            MethodType intType = MethodType.methodType(int.class);
            this.cardsBottom = tab.findVirtual(themeTab, "cardsBottom", intType);
            this.left = base.findVirtual(part, "left", intType);
            this.top = base.findVirtual(part, "top", intType);
            this.innerW = base.findVirtual(part, "innerW", intType);
        }

        int left(Object tab) {
            return call(this.left, tab);
        }

        int top(Object tab) {
            return call(this.top, tab);
        }

        int innerW(Object tab) {
            return call(this.innerW, tab);
        }

        int cardsBottom(Object tab) {
            return call(this.cardsBottom, tab);
        }

        private static int call(MethodHandle h, Object tab) {
            try {
                return (int) h.invoke(tab);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }

    /** Towntopia's looks by lower-case name, and their ids (loaded on first use, so reading {@link #KEYS} loads no Towntopia class). */
    static final class Themes {
        static final Map<String, VillagerTheme> BY_NAME = new HashMap<>();
        private static final Map<VillagerTheme, ResourceLocation> IDS = new EnumMap<>(VillagerTheme.class);

        static {
            for (VillagerTheme t : VillagerTheme.values()) {
                String name = t.name().toLowerCase(Locale.ROOT);
                BY_NAME.put(name, t);
                IDS.put(t, ResourceLocation.fromNamespaceAndPath("towntopia", name));
            }
        }

        /** {@code towntopia:<look>}, the id swap lines use for one of Towntopia's looks. */
        static ResourceLocation id(VillagerTheme theme) {
            return IDS.get(theme);
        }
    }

    /** The id of the look in use: the chosen addon look, else {@code towntopia:<Towntopia's look>}. */
    static ResourceLocation inUseId() {
        Looks.Def d = active;
        return d != null ? d.id() : Themes.id(VillagerTheme.current());
    }

    /** For the client check: the current look's def, if one is being drawn. */
    public static Optional<Looks.Def> active() {
        return Optional.ofNullable(active);
    }

    /** The chosen look while it is drawn, or null (no allocation: called while drawing). */
    @Nullable
    static Looks.Def activeDef() {
        return active;
    }
}
