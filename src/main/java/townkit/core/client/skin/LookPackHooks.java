package townkit.core.client.skin;

import com.alex.test.client.VillagerTheme;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import townkit.api.hooks.HookCall;
import townkit.api.skin.Look;
import townkit.core.TownKit;

/**
 * Addon looks in Towntopia's per-skin swap: the pack page of the Theme page ({@code ThemePackView}, one look's skins,
 * with the shown skin on the right). Attached by {@link LookHooks} while any addon look exists:
 * <ul>
 *   <li>{@code ThemePackView#render} after: under the shown villager, a "Wear &lt;look&gt;'s version" button for each
 *   addon look (other than the one in use) that has a file for that skin; worn, it reads "Stop wearing &lt;look&gt;".
 *   While an addon look is in use, on one of Towntopia's looks' pages TownKit's own button covers Towntopia's
 *   "Wear in ..." button and wears that Towntopia skin in the addon look.</li>
 *   <li>{@code ThemePackView#mouseClicked} before: a click on one of those buttons writes or removes the swap line
 *   ({@link LookChoice#setSwap}) for the look in use, Towntopia's or an addon one.</li>
 * </ul>
 * Swap lines live in TownKit's config only; Towntopia's {@code skinChanges} are untouched. The layout mirrors Towntopia
 * 0.1.2's {@code ThemePackView}, read through method handles; if any part is missing nothing is drawn (logged once).
 * Render thread.
 */
public final class LookPackHooks {
    static final String RENDER = "ui.screen.mayor.ThemePackView#render(GuiGraphics,int,int)";
    static final String CLICK = "ui.screen.mayor.ThemePackView#mouseClicked(double,double,int)";

    // Towntopia 0.1.2 ThemePackView stage layout
    private static final int STAGE_PAD = 6;
    private static final int STAGE_INNER_W = 148;
    private static final int STAGE_BOX_BOTTOM_GAP = 55;
    private static final int BUTTON_H = 18;
    private static final int CHIP_H = 12;
    private static final int CHIP_GAP = 2;
    /** Above the 3D previews (Towntopia draws its greyed veil at 300), below tooltips (400). */
    private static final float Z = 350.0F;
    private static final int BORDER = -12237499;
    private static final int CHANGED_BORDER = -1649510;
    private static final int BG = -14079703;
    private static final int BG_HOVER = -13092808;
    private static final int TEXT = -789517;

    /**
     * One TownKit button on the pack page, as last drawn: clicking it swaps {@code skin} while {@code inUse} is in use
     * to {@code look} ({@code worn}: removes that swap).
     */
    public record PackButton(ResourceLocation inUse, String skin, ResourceLocation look, int x, int y, int width, int height, boolean worn) {
    }

    private static List<PackButton> lastDrawn = List.of();
    @Nullable
    private static Layout layout;
    private static boolean layoutFailed;

    private LookPackHooks() {
    }

    /** The buttons TownKit drew on the pack page last frame (for the client check). */
    public static List<PackButton> lastDrawn() {
        return lastDrawn;
    }

    static void forget() {
        lastDrawn = List.of();
    }

    static void afterRender(HookCall call) {
        Object view = call.self();
        Layout l = layout(view);
        VillagerTheme page = l == null ? null : l.set(view);
        String skin = page == null ? null : l.shownSkin(view);
        if (skin == null) {
            lastDrawn = List.of();
            return;
        }
        GuiGraphics g = (GuiGraphics) call.args()[0];
        int mouseX = (Integer) call.args()[1];
        int mouseY = (Integer) call.args()[2];
        Font font = Minecraft.getInstance().font;
        int x = l.stageX(view) + STAGE_PAD;
        ResourceLocation inUse = LookHooks.inUseId();
        ResourceLocation swapped = LookChoice.swapFrom(inUse, skin);
        Looks.SkinName name = new Looks.SkinName(skin, false);
        List<PackButton> drawn = new ArrayList<>();
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, Z);
        int y = l.bodyBottom(view) - STAGE_PAD - STAGE_BOX_BOTTOM_GAP - CHIP_H - CHIP_GAP;
        for (Look look : Looks.CLIENT.looks()) {
            if (look.id().equals(inUse)) continue;
            Looks.Def d = Looks.CLIENT.get(look.id()).orElse(null);
            if (d == null || d.file(name) == null) continue;
            boolean worn = look.id().equals(swapped);
            String label = worn
                ? Component.translatableWithFallback("screen.townkit.theme.stop_wearing_look", "Stop wearing %s", look.name()).getString()
                : Component.translatableWithFallback("screen.townkit.theme.wear_look", "Wear %s's version", look.name()).getString();
            button(g, font, label, x, y, CHIP_H, worn, mouseX, mouseY);
            drawn.add(new PackButton(inUse, skin, look.id(), x, y, STAGE_INNER_W, CHIP_H, worn));
            y -= CHIP_H + CHIP_GAP;
        }
        Looks.Def active = LookHooks.active().orElse(null);
        if (active != null && !l.yourLook(view)) {
            // Towntopia's "Wear in <its look>" would write Towntopia's skinChanges: wear this Towntopia skin in the addon look instead
            ResourceLocation towntopiaLook = LookHooks.Themes.id(page);
            boolean worn = towntopiaLook.equals(swapped);
            String label = worn
                ? Component.translatableWithFallback("screen.townkit.theme.stop_wearing", "Stop wearing").getString()
                : Component.translatableWithFallback("screen.townkit.theme.wear_in", "Wear in %s", active.look().name()).getString();
            int by = l.skinButtonY(view);
            button(g, font, label, x, by, BUTTON_H, worn, mouseX, mouseY);
            drawn.add(new PackButton(inUse, skin, towntopiaLook, x, by, STAGE_INNER_W, BUTTON_H, worn));
        }
        g.pose().popPose();
        lastDrawn = List.copyOf(drawn);
    }

    private static void button(GuiGraphics g, Font font, String text, int x, int y, int h, boolean worn, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + STAGE_INNER_W && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + STAGE_INNER_W, y + h, worn ? CHANGED_BORDER : BORDER);
        g.fill(x + 1, y + 1, x + STAGE_INNER_W - 1, y + h - 1, hovered ? BG_HOVER : BG);
        String label = font.plainSubstrByWidth(text, STAGE_INNER_W - 6);
        g.drawString(font, label, x + (STAGE_INNER_W - font.width(label)) / 2, y + (h - 8) / 2, TEXT, false);
    }

    static void beforeClick(HookCall call) {
        if ((Integer) call.args()[2] != 0) return;
        Layout l = layout(call.self());
        if (l == null || l.set(call.self()) == null) return;
        double mouseX = (Double) call.args()[0];
        double mouseY = (Double) call.args()[1];
        for (PackButton b : lastDrawn) {
            if (mouseX >= b.x() && mouseX < b.x() + b.width() && mouseY >= b.y() && mouseY < b.y() + b.height()) {
                LookChoice.setSwap(b.inUse(), b.skin(), b.worn() ? null : b.look());
                call.cancelWith(true);
                return;
            }
        }
    }

    @Nullable
    private static Layout layout(Object view) {
        if (layout != null || layoutFailed) return layout;
        try {
            layout = new Layout(view.getClass());
        } catch (ReflectiveOperationException | RuntimeException e) {
            layoutFailed = true;
            TownKit.LOG.warn("[TownKit] can't read Towntopia's skin page layout ({}): addon looks aren't offered there", e.toString());
        }
        return layout;
    }

    /** Method handles on Towntopia's ThemePackView and its VillagerPreview entries. */
    private static final class Layout {
        private final MethodHandle set;
        private final MethodHandle yourLook;
        private final MethodHandle shown;
        private final MethodHandle stageX;
        private final MethodHandle bodyBottom;
        private final MethodHandle skinButtonY;
        private final List<?> entries;
        @Nullable
        private MethodHandle skinKey;

        Layout(Class<?> view) throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(view, MethodHandles.lookup());
            MethodType intType = MethodType.methodType(int.class);
            this.set = lookup.findGetter(view, "set", VillagerTheme.class);
            this.yourLook = lookup.findGetter(view, "yourLook", boolean.class);
            this.shown = lookup.findGetter(view, "shown", int.class);
            this.stageX = lookup.findVirtual(view, "stageX", intType);
            this.bodyBottom = lookup.findVirtual(view, "bodyBottom", intType);
            this.skinButtonY = lookup.findVirtual(view, "skinButtonY", intType);
            Class<?> preview = Class.forName(view.getPackageName() + ".VillagerPreview", false, view.getClassLoader());
            MethodHandle entries = MethodHandles.privateLookupIn(preview, MethodHandles.lookup()).findStaticGetter(preview, "ENTRIES", List.class);
            try {
                this.entries = (List<?>) entries.invoke();
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        @Nullable
        VillagerTheme set(Object view) {
            try {
                return (VillagerTheme) this.set.invoke(view);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        boolean yourLook(Object view) {
            try {
                return (boolean) this.yourLook.invoke(view);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /** Towntopia's skin key of the villager shown on the page's stage, or null. */
        @Nullable
        String shownSkin(Object view) {
            try {
                int i = (int) this.shown.invoke(view);
                if (i < 0 || i >= this.entries.size()) return null;
                Object entry = this.entries.get(i);
                if (this.skinKey == null) {
                    this.skinKey = MethodHandles.privateLookupIn(entry.getClass(), MethodHandles.lookup())
                        .findVirtual(entry.getClass(), "skinKey", MethodType.methodType(String.class));
                }
                return (String) this.skinKey.invoke(entry);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        int stageX(Object view) {
            return call(this.stageX, view);
        }

        int bodyBottom(Object view) {
            return call(this.bodyBottom, view);
        }

        int skinButtonY(Object view) {
            return call(this.skinButtonY, view);
        }

        private static int call(MethodHandle h, Object view) {
            try {
                return (int) h.invoke(view);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }
}
