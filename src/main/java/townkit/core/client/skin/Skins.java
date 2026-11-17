package townkit.core.client.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.Reader;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * The addon skins the client knows (per-villager skins, {@code TownKitSkins.set}), read from
 * {@code assets/<ns>/townkit/skins/<name>.json} at each resource reload: {@code {"texture": "<ns>:...png",
 * "geo": "<ns>:geo/...json"}}, geo optional. A file whose texture isn't there, whose geo isn't under {@code geo/}
 * (GeckoLib loads models from there only), or that is in Towntopia's namespace is left out, with a warning.
 */
public final class Skins {
    /** The skins this client has: filled by {@link VillagerSkins}' reload listener. */
    public static final Skins CLIENT = new Skins(LogUtils.getLogger()::warn);

    /** Where skin files live in a namespace's assets. */
    public static final String FOLDER = "townkit/skins";
    private static final String SUFFIX = ".json";
    private static final String TOWNTOPIA = "towntopia";

    private final Consumer<String> warn;
    private volatile Map<ResourceLocation, Looks.File> files = Map.of();

    public Skins(Consumer<String> warn) {
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** The skin id of a file found under {@link #FOLDER}: {@code ns:townkit/skins/envoy.json} is {@code ns:envoy}. */
    public static ResourceLocation idOf(ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(), path.substring(FOLDER.length() + 1, path.length() - SUFFIX.length()));
    }

    /** Reads one skin file for {@code id}; {@code exists} says whether a resource is there. Empty (with a warning) if it can't be used. */
    public Optional<Looks.File> parse(ResourceLocation id, Reader json, Predicate<ResourceLocation> exists) {
        if (TOWNTOPIA.equals(id.getNamespace())) return reject(id, "the towntopia namespace is for Towntopia's own skins (use SkinRef.towntopia)");
        try {
            JsonElement root = JsonParser.parseReader(json);
            if (!root.isJsonObject()) return reject(id, "not a JSON object");
            JsonObject o = root.getAsJsonObject();
            String tex = string(o, "texture");
            ResourceLocation texture = tex == null ? null : ResourceLocation.tryParse(tex);
            if (texture == null) return reject(id, "bad or missing \"texture\" '" + tex + "'");
            if (!exists.test(texture)) return reject(id, "its texture " + texture + " isn't there");
            ResourceLocation geo = null;
            if (o.has("geo")) {
                String g = string(o, "geo");
                geo = g == null ? null : ResourceLocation.tryParse(g);
                if (geo == null) return reject(id, "bad \"geo\" '" + g + "'");
                if (!geo.getPath().startsWith("geo/") || !geo.getPath().endsWith(SUFFIX)) {
                    return reject(id, "\"geo\" " + geo + " is not a .json under geo/ (GeckoLib only loads models from there)");
                }
            }
            return Optional.of(new Looks.File(texture, geo));
        } catch (RuntimeException e) { // malformed JSON
            return reject(id, "unreadable (" + e.getMessage() + ")");
        }
    }

    @Nullable
    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private Optional<Looks.File> reject(ResourceLocation id, String why) {
        this.warn.accept("[TownKit] skin " + id + " ignored: " + why);
        return Optional.empty();
    }

    /** Puts the skins read at a resource reload in place of the old ones. */
    public void replace(Map<ResourceLocation, Looks.File> read) {
        this.files = Map.copyOf(read);
    }

    /** The skin with this id, or null if this client has no usable file for it. */
    @Nullable
    public Looks.File get(ResourceLocation id) {
        return this.files.get(id);
    }

    /** How many skins this client has. */
    public int size() {
        return this.files.size();
    }
}
