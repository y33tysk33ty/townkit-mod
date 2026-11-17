package townkit.core.client.skin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import townkit.core.TownKit;

/**
 * TownKit's client config ({@code townkit-client.toml}): the addon look this player picked and their skin swaps for
 * addon looks. Kept apart from Towntopia's own {@code villagerTheme}/{@code skinChanges}, which can only name
 * Towntopia's looks, so Towntopia's config stays valid without TownKit.
 *
 * <p>Swap lines read {@code "<look in use>: <skin> = <look its picture comes from>"}, with look ids: an addon look
 * ({@code myaddon:medieval}) or one of Towntopia's ({@code towntopia:classic}, {@code towntopia:london},
 * {@code towntopia:modern}, {@code towntopia:rustic}); skins are named as in Towntopia's {@code skinChanges}
 * ({@code cook}, {@code guard_2}, {@code farmer_female_1}). Example: {@code "myaddon:medieval: cook = towntopia:rustic"}.
 *
 * <p>The values are held here and written through to the file once the config has loaded (before that, and in unit
 * tests, they live in memory only). Changes go to {@link LookHooks#refresh}, on the render thread.
 *
 * <p>A line whose look in use is one of Towntopia's ({@code \"towntopia:rustic: cook = myaddon:medieval\"}) puts an
 * addon look's skin into Towntopia's look; Towntopia's own swaps stay in Towntopia's {@code skinChanges}.
 */
// no bus = ...: FML puts each listener on the mod bus or the game bus by its event type (IModBusEvent -> mod bus)
@EventBusSubscriber(modid = TownKit.MODID, value = Dist.CLIENT)
public final class LookChoice {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.ConfigValue<String> LOOK = BUILDER.comment(
            "The addon look you picked on the Theme page of the Mayor screen, as its id (\"myaddon:medieval\").",
            "Empty: one of Towntopia's own looks is in use (Towntopia's villagerTheme). A look whose files are gone is ignored.",
            "Only changes what you see.")
        .define("addonLook", "");
    private static final ModConfigSpec.ConfigValue<List<? extends String>> SWAPS = BUILDER.comment(
            "Your own changes to addon looks: one line per skin, \"<look in use>: <skin> = <look its picture comes from>\".",
            "Looks are ids: an addon look (\"myaddon:medieval\") or towntopia:classic, towntopia:london, towntopia:modern, towntopia:rustic.",
            "Skins are named as in Towntopia's skinChanges (cook, guard_2, farmer_female_1).",
            "\"myaddon:medieval: cook = towntopia:rustic\" draws Rustic's cook while Medieval is in use.",
            "A line that can't be read is skipped. Only changes what you see.")
        .defineListAllowEmpty("addonLookSwaps", List.of(), () -> "", line -> line instanceof String);
    public static final ModConfigSpec SPEC = BUILDER.build();

    @Nullable
    private static volatile ResourceLocation chosen;
    private static volatile List<String> swapLines = List.of();
    private static volatile Map<ResourceLocation, Map<String, ResourceLocation>> swaps = Map.of();

    private LookChoice() {
    }

    /** The chosen addon look's id, or null when one of Towntopia's looks is in use. */
    @Nullable
    public static ResourceLocation chosen() {
        return chosen;
    }

    /** Picks an addon look ({@code look} non-null) or goes back to Towntopia's own choice (null), and saves it. */
    public static void choose(@Nullable ResourceLocation look) {
        if (java.util.Objects.equals(look, chosen)) return;
        chosen = look;
        if (SPEC.isLoaded()) {
            LOOK.set(look == null ? "" : look.toString());
            LOOK.save();
        }
        LookHooks.refreshOnClientThread();
    }

    /** The look this player swapped {@code skin} to while {@code inUse} is in use, or null. */
    @Nullable
    public static ResourceLocation swapFrom(ResourceLocation inUse, String skin) {
        Map<String, ResourceLocation> forLook = swaps.get(inUse);
        return forLook == null ? null : forLook.get(skin);
    }

    /** Sets (or with a null {@code from}, removes) the swap of {@code skin} while {@code inUse} is in use, and saves it. */
    public static void setSwap(ResourceLocation inUse, String skin, @Nullable ResourceLocation from) {
        List<String> lines = withSwap(swapLines, inUse, skin, from);
        if (lines.equals(swapLines)) return;
        applySwaps(lines);
        if (SPEC.isLoaded()) {
            SWAPS.set(lines);
            SWAPS.save();
        }
        LookHooks.refreshOnClientThread();
    }

    private static void applySwaps(List<String> lines) {
        swapLines = List.copyOf(lines);
        swaps = index(lines, TownKit.LOG::warn);
    }

    @SubscribeEvent
    public static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) read();
    }

    @SubscribeEvent
    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) read();
    }

    private static void read() {
        String look = LOOK.get().trim();
        ResourceLocation id = look.isEmpty() ? null : ResourceLocation.tryParse(look);
        if (!look.isEmpty() && id == null) TownKit.LOG.warn("[TownKit] addonLook \"{}\" in townkit-client.toml is not a look id: ignored", look);
        chosen = id;
        List<String> lines = new ArrayList<>();
        for (Object line : SWAPS.get()) lines.add(String.valueOf(line));
        applySwaps(lines);
        // config events come on NeoForge's file-watcher thread: hooks and their state belong to the render thread
        LookHooks.refreshOnClientThread();
    }

    // --- swap lines (pure) -----------------------------------------------------------------------------------------

    /** One swap line. */
    record Swap(ResourceLocation inUse, String skin, ResourceLocation from) {
        @Nullable
        static Swap parse(String line) {
            int equals = line.indexOf('=');
            if (equals < 0) return null;
            String left = line.substring(0, equals);
            int colon = left.lastIndexOf(':');
            if (colon <= 0) return null;
            ResourceLocation inUse = ResourceLocation.tryParse(left.substring(0, colon).trim());
            String skin = left.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            String right = line.substring(equals + 1).trim();
            // ResourceLocation.tryParse("") reads as "minecraft:": a line with nothing after '=' must not become a swap
            ResourceLocation from = right.isEmpty() ? null : ResourceLocation.tryParse(right);
            return inUse == null || from == null || skin.isEmpty() || !left.substring(0, colon).contains(":") ? null : new Swap(inUse, skin, from);
        }

        String line() {
            return this.inUse + ": " + this.skin + " = " + this.from;
        }
    }

    /** Swap lines by look in use, then skin. Lines that can't be read are skipped, each with a warning. */
    public static Map<ResourceLocation, Map<String, ResourceLocation>> index(List<? extends String> lines, Consumer<String> warn) {
        Map<ResourceLocation, Map<String, ResourceLocation>> out = new HashMap<>();
        for (String line : lines) {
            Swap s = Swap.parse(line);
            if (s == null) {
                warn.accept("[TownKit] skipped the addonLookSwaps line \"" + line + "\": it should look like \"myaddon:medieval: cook = towntopia:rustic\"");
                continue;
            }
            if (!s.from().equals(s.inUse())) out.computeIfAbsent(s.inUse(), k -> new HashMap<>()).put(s.skin(), s.from());
        }
        Map<ResourceLocation, Map<String, ResourceLocation>> frozen = new HashMap<>();
        out.forEach((k, v) -> frozen.put(k, Map.copyOf(v)));
        return Map.copyOf(frozen);
    }

    /** Whether any line swaps a skin while one of Towntopia's looks is in use. */
    public static boolean anyTowntopiaInUse(Map<ResourceLocation, Map<String, ResourceLocation>> swaps) {
        for (ResourceLocation inUse : swaps.keySet()) {
            if ("towntopia".equals(inUse.getNamespace())) return true;
        }
        return false;
    }

    /** Whether any saved line swaps a skin while one of Towntopia's looks is in use. */
    public static boolean anyTowntopiaInUse() {
        return anyTowntopiaInUse(swaps);
    }

    /** {@code lines} with the swap of {@code skin} while {@code inUse} is in use set to {@code from} (null: removed). */
    public static List<String> withSwap(List<? extends String> lines, ResourceLocation inUse, String skin, @Nullable ResourceLocation from) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            Swap s = Swap.parse(line);
            if (s == null || !s.inUse().equals(inUse) || !s.skin().equals(skin)) out.add(line);
        }
        if (from != null && !from.equals(inUse)) out.add(new Swap(inUse, skin, from).line());
        return out;
    }
}
