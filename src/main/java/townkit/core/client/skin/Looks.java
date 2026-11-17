package townkit.core.client.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import townkit.api.skin.Look;

/**
 * The addon looks the client knows, read from {@code assets/<ns>/townkit/looks/<name>.json} at each resource reload
 * ({@link #parse}, {@link #replace}), and the choice of a skin's file while one of them is in use ({@link #pick}).
 * Every problem is logged once and never thrown: a bad file leaves its look out, an unknown chosen look leaves
 * Towntopia's own choice in place. {@link #pick} and {@link #skinName} run for every villager drawn and allocate
 * nothing once warmed up.
 */
public final class Looks {
    /** The looks this client has: filled by {@link LookHooks}' reload listener. */
    public static final Looks CLIENT = new Looks(LogUtils.getLogger()::warn);

    /** Where look files live in a namespace's assets. */
    public static final String FOLDER = "townkit/looks";
    private static final String SUFFIX = ".json";
    private static final String TOWNTOPIA = "towntopia";
    private static final String PREFIX = "dynamic_villagers_";
    private static final String DIRTY = "_dirty";
    private static final String PNG = ".png";
    private static final int NAME_CACHE_MAX = 1024;

    /**
     * One of Towntopia's skins, as Towntopia names it in its skin swaps: {@code cook}, {@code guard_2},
     * {@code farmer_female_1} (its file name without {@code dynamic_villagers_}); {@code dirty} for a farmer's dirty
     * twin.
     */
    public record SkinName(String key, boolean dirty) {
    }

    /** What to draw a skin with. */
    public sealed interface Pick permits File, Towntopia {
    }

    /** An addon look's file, with its body model ({@code geo}) or null for Towntopia's base model. */
    public record File(ResourceLocation texture, @Nullable ResourceLocation geo) implements Pick {
    }

    /** Towntopia's skin as one of its own looks draws it ({@code classic}, {@code london}, {@code modern}, {@code rustic}). */
    public record Towntopia(String look) implements Pick {
        public static final Towntopia MODERN = new Towntopia("modern");
    }

    /** One look, as read from its file and the files in its folders. */
    public static final class Def {
        private final Look look;
        private final Map<String, File> clean;
        private final Map<String, File> dirty;

        Def(Look look, Map<String, File> clean, Map<String, File> dirty) {
            this.look = look;
            this.clean = Map.copyOf(clean);
            this.dirty = Map.copyOf(dirty);
        }

        public ResourceLocation id() {
            return this.look.id();
        }

        public Look look() {
            return this.look;
        }

        /** This look's file for {@code skin}, or null if it has none. A dirty skin without a dirty file is drawn clean. */
        @Nullable
        public File file(SkinName skin) {
            if (skin.dirty()) {
                File d = this.dirty.get(skin.key());
                if (d != null) return d;
            }
            return this.clean.get(skin.key());
        }

        /** How many skins this look has (clean ones). */
        public int skinCount() {
            return this.clean.size();
        }

        @Override
        public String toString() {
            return "Look[" + this.look.id() + ", " + this.clean.size() + " skins]";
        }
    }

    private static final Map<ResourceLocation, Object> NAMES = new ConcurrentHashMap<>();
    private static final Object NOT_A_SKIN = new Object();

    private final Consumer<String> warn;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile Map<ResourceLocation, Def> defs = Map.of();
    private volatile List<Look> sorted = List.of();

    public Looks(Consumer<String> warn) {
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** The look id of a file found under {@link #FOLDER}: {@code ns:townkit/looks/medieval.json} is {@code ns:medieval}. */
    public static ResourceLocation idOf(ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(), path.substring(FOLDER.length() + 1, path.length() - SUFFIX.length()));
    }

    /**
     * The skin a texture of Towntopia's is: {@code .../dynamic_villagers_guard_2.png} in any look's folder, or one of
     * the farmers' generated in-between dirt stages ({@code dirt_stage/[<look>/]farmer_<skin>_<stage>}, stages 1 and 2
     * clean, 3 dirty, as Towntopia picks the clean or dirty file for them). Null for any other texture. Cached.
     */
    @Nullable
    public static SkinName skinName(ResourceLocation texture) {
        Object known = NAMES.get(texture);
        if (known == null) {
            SkinName read = readSkinName(texture);
            known = read == null ? NOT_A_SKIN : read;
            if (NAMES.size() >= NAME_CACHE_MAX) NAMES.clear();
            NAMES.put(texture, known);
        }
        return known == NOT_A_SKIN ? null : (SkinName) known;
    }

    @Nullable
    private static SkinName readSkinName(ResourceLocation texture) {
        String path = texture.getPath();
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (file.startsWith(PREFIX) && file.endsWith(PNG)) {
            String key = file.substring(PREFIX.length(), file.length() - PNG.length());
            if (key.isEmpty()) return null;
            return key.endsWith(DIRTY) ? new SkinName(key.substring(0, key.length() - DIRTY.length()), true) : new SkinName(key, false);
        }
        if (path.startsWith("dirt_stage/") && file.startsWith("farmer_")) {
            int underscore = file.lastIndexOf('_');
            if (underscore <= 0) return null;
            try {
                int stage = Integer.parseInt(file.substring(underscore + 1));
                return new SkinName(file.substring(0, underscore), stage >= 3);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Reads one look file for {@code id}. {@code list} gives the files directly inside a folder (a resource location
     * ending in {@code /}). Empty (with a warning) if the file isn't a JSON object, names a bad folder, or the id is in
     * Towntopia's namespace (reserved for Towntopia's own looks).
     */
    public Optional<Def> parse(ResourceLocation id, Reader json, Function<ResourceLocation, Collection<ResourceLocation>> list) {
        if (TOWNTOPIA.equals(id.getNamespace())) return reject(id, "the towntopia namespace is for Towntopia's own looks");
        try {
            JsonElement root = JsonParser.parseReader(json);
            if (!root.isJsonObject()) return reject(id, "not a JSON object");
            JsonObject o = root.getAsJsonObject();
            String name = string(o, "name");
            ResourceLocation folder = folder(o, "folder", id.getNamespace() + ":textures/townkit/looks/" + id.getPath() + "/");
            if (folder == null) return reject(id, "bad \"folder\" '" + string(o, "folder") + "'");
            ResourceLocation geoFolder = folder(o, "geo_folder", id.getNamespace() + ":geo/townkit/looks/" + id.getPath() + "/");
            if (geoFolder == null) return reject(id, "bad \"geo_folder\" '" + string(o, "geo_folder") + "'");
            // GeckoLib bakes only models under geo/, and throws when asked for a model elsewhere
            if (!geoFolder.getPath().startsWith("geo/")) return reject(id, "\"geo_folder\" " + geoFolder + " is not under geo/ (GeckoLib only loads models from there)");

            Map<String, ResourceLocation> geos = new HashMap<>();
            for (ResourceLocation f : list.apply(geoFolder)) {
                String file = fileName(f);
                if (file.endsWith(SUFFIX)) geos.put(file.substring(0, file.length() - SUFFIX.length()), f);
            }
            Map<String, File> clean = new HashMap<>();
            Map<String, File> dirty = new HashMap<>();
            for (ResourceLocation f : list.apply(folder)) {
                String file = fileName(f);
                if (!file.startsWith(PREFIX) || !file.endsWith(PNG)) continue;
                String key = file.substring(PREFIX.length(), file.length() - PNG.length());
                if (key.isEmpty()) continue;
                if (key.endsWith(DIRTY)) {
                    String base = key.substring(0, key.length() - DIRTY.length());
                    dirty.put(base, new File(f, geos.get(base)));
                } else {
                    clean.put(key, new File(f, geos.get(key)));
                }
            }
            if (clean.isEmpty()) {
                this.warn.accept("[TownKit] look " + id + " has no skin files (" + PREFIX + "<skin>.png) in " + folder
                    + ": every villager is drawn as in Modern");
            }
            Component shown = Component.translatable(name == null ? id.toString() : name);
            return Optional.of(new Def(new Look(id, shown), clean, dirty));
        } catch (RuntimeException e) { // malformed JSON
            return reject(id, "unreadable (" + e.getMessage() + ")");
        }
    }

    @Nullable
    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** The folder under {@code key}, or {@code fallback} when absent; null when it isn't a valid resource location. */
    @Nullable
    private static ResourceLocation folder(JsonObject o, String key, String fallback) {
        String s = o.has(key) ? string(o, key) : fallback;
        if (s == null) return null;
        if (!s.endsWith("/")) s = s + "/";
        return ResourceLocation.tryParse(s);
    }

    private static String fileName(ResourceLocation f) {
        String path = f.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private Optional<Def> reject(ResourceLocation id, String why) {
        this.warn.accept("[TownKit] look " + id + " ignored: " + why);
        return Optional.empty();
    }

    /** Puts the looks read at a resource reload in place of the old ones. */
    public void replace(Map<ResourceLocation, Def> read) {
        this.defs = Map.copyOf(read);
        List<Look> out = new ArrayList<>();
        read.values().forEach(d -> out.add(d.look()));
        out.sort(Comparator.comparing(l -> l.id().toString()));
        this.sorted = List.copyOf(out);
        this.warned.clear();
    }

    /** Every look, by id. */
    public List<Look> looks() {
        return this.sorted;
    }

    public Optional<Def> get(@Nullable ResourceLocation id) {
        return id == null ? Optional.empty() : Optional.ofNullable(this.defs.get(id));
    }

    /**
     * The chosen look, if this client has it; empty when nothing is chosen, or (logged once until the next reload) when
     * the chosen look is unknown, e.g. its pack was removed: then Towntopia's own choice is drawn.
     */
    public Optional<Def> current(@Nullable ResourceLocation chosen) {
        if (chosen == null) return Optional.empty();
        Def d = this.defs.get(chosen);
        if (d == null && this.warned.add("chosen " + chosen)) {
            this.warn.accept("[TownKit] the chosen look " + chosen + " has no file (assets/" + chosen.getNamespace() + "/" + FOLDER + "/"
                + chosen.getPath() + SUFFIX + "): Towntopia's own look is drawn");
        }
        return Optional.ofNullable(d);
    }

    /**
     * What to draw {@code skin} with while {@code look} is in use: the look the player swapped this skin to
     * ({@code swapFrom}, an addon look with a file for it or one of Towntopia's looks), else the look's own file,
     * else Towntopia's Modern skin.
     */
    public Pick pick(Def look, SkinName skin, @Nullable ResourceLocation swapFrom) {
        if (swapFrom != null) {
            if (TOWNTOPIA.equals(swapFrom.getNamespace())) {
                Towntopia t = towntopia(swapFrom.getPath());
                if (t != null) return t;
            } else {
                Def from = this.defs.get(swapFrom);
                File f = from == null ? null : from.file(skin);
                if (f != null) return f;
            }
        }
        File own = look.file(skin);
        return own != null ? own : Towntopia.MODERN;
    }

    /**
     * What to draw {@code skin} with while one of Towntopia's looks is in use and the player swapped this skin to
     * {@code swapFrom}: that addon look's file, or null (Towntopia draws, its own swaps included) when the line names
     * no addon look with a file for it.
     */
    @Nullable
    public File pickWhileTowntopia(SkinName skin, @Nullable ResourceLocation swapFrom) {
        if (swapFrom == null || TOWNTOPIA.equals(swapFrom.getNamespace())) return null;
        Def from = this.defs.get(swapFrom);
        return from == null ? null : from.file(skin);
    }

    /** Towntopia's looks, as {@code towntopia:<name>} in swap lines. */
    private static final Map<String, Towntopia> TOWNTOPIA_PICKS = Map.of(
        "classic", new Towntopia("classic"), "london", new Towntopia("london"), "modern", Towntopia.MODERN, "rustic", new Towntopia("rustic"));

    @Nullable
    private static Towntopia towntopia(String look) {
        return TOWNTOPIA_PICKS.get(look);
    }
}
