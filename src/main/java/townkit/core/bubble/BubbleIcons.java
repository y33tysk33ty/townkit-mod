package townkit.core.bubble;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.bubble.Valence;

/**
 * The addon icons TownKit knows. The server knows an icon from the addon's code registration ({@link #register}); the
 * client from its file, {@code assets/<ns>/townkit/bubbles/<name>.json} for the id {@code <ns>:<name>}, read at each
 * resource reload ({@link #parse}, {@link #replaceFiles}). Where both are known (a single-player game) and the
 * valences differ, the file's wins on the client, logged once. Every problem is logged once and never thrown: a bad
 * or missing file just leaves the icon unknown, so the client draws no bubble for it ({@link #resolve}).
 */
public final class BubbleIcons {
    /** One icon file, as read. */
    public record Entry(ResourceLocation id, ResourceLocation texture, Valence valence) {
    }

    /** Where icon files live in a namespace's assets. */
    public static final String FOLDER = "townkit/bubbles";
    private static final String SUFFIX = ".json";

    private final Consumer<String> warn;
    private final Map<ResourceLocation, Valence> registered = new ConcurrentHashMap<>();
    private volatile Map<ResourceLocation, Entry> files = Map.of();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public BubbleIcons(Consumer<String> warn) {
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    // --- server: code registrations --------------------------------------------------------------------------------

    /** Registers an addon icon's valence. Throws IllegalArgumentException if {@code id} already has another valence. */
    public void register(ResourceLocation id, Valence valence) {
        Valence before = registered.putIfAbsent(id, valence);
        if (before != null && before != valence) {
            throw new IllegalArgumentException("bubble icon " + id + " is already registered as " + before.name().toLowerCase(Locale.ROOT));
        }
    }

    /** The registered valence of an addon icon, or null if it wasn't registered. */
    @Nullable
    public Valence registered(ResourceLocation id) {
        return registered.get(id);
    }

    // --- client: files ---------------------------------------------------------------------------------------------

    /** The icon id of a file found under {@link #FOLDER}: {@code ns:townkit/bubbles/a/b.json} is {@code ns:a/b}. */
    public static ResourceLocation idOf(ResourceLocation file) {
        String path = file.getPath();
        String name = path.substring(FOLDER.length() + 1, path.length() - SUFFIX.length());
        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(), name);
    }

    /**
     * Reads one icon file for {@code id}. Empty (with a warning) if it isn't valid JSON, lacks a texture, names a
     * texture {@code textureExists} says is missing, or has a valence other than positive, neutral or negative.
     */
    public Optional<Entry> parse(ResourceLocation id, Reader json, Predicate<ResourceLocation> textureExists) {
        try {
            JsonElement root = JsonParser.parseReader(json);
            if (!root.isJsonObject()) return reject(id, "not a JSON object");
            JsonObject o = root.getAsJsonObject();
            if (!o.has("texture") || !o.get("texture").isJsonPrimitive()) return reject(id, "no \"texture\"");
            String tex = o.get("texture").getAsString();
            ResourceLocation texture = ResourceLocation.tryParse(tex);
            if (texture == null) return reject(id, "bad texture id '" + tex + "'");
            String v = o.has("valence") && o.get("valence").isJsonPrimitive() ? o.get("valence").getAsString() : null;
            Valence valence = valence(v);
            if (valence == null) return reject(id, "valence '" + v + "' is not positive, neutral or negative");
            if (!textureExists.test(texture)) return reject(id, "its texture " + texture + " is missing");
            return Optional.of(new Entry(id, texture, valence));
        } catch (RuntimeException e) { // malformed JSON
            return reject(id, "unreadable (" + e.getMessage() + ")");
        }
    }

    @Nullable
    private static Valence valence(@Nullable String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "positive" -> Valence.POSITIVE;
            case "neutral" -> Valence.NEUTRAL;
            case "negative" -> Valence.NEGATIVE;
            default -> null;
        };
    }

    private Optional<Entry> reject(ResourceLocation id, String why) {
        warn.accept("[TownKit] bubble icon " + id + " ignored: " + why);
        return Optional.empty();
    }

    /** Puts the icons read at a resource reload in place of the old ones. */
    public void replaceFiles(Map<ResourceLocation, Entry> read) {
        for (Entry e : read.values()) {
            Valence code = registered.get(e.id());
            if (code != null && code != e.valence() && warned.add("valence " + e.id())) {
                warn.accept("[TownKit] bubble icon " + e.id() + ": its file says " + e.valence().name().toLowerCase(Locale.ROOT)
                    + " but the addon registered " + code.name().toLowerCase(Locale.ROOT) + "; the client uses the file's");
            }
        }
        files = Map.copyOf(read);
    }

    /** The file of icon {@code id}; empty (logged once per id) when the client has none: then no bubble is drawn. */
    public Optional<Entry> resolve(ResourceLocation id) {
        Entry e = files.get(id);
        if (e == null && warned.add("unknown " + id)) {
            warn.accept("[TownKit] no file for bubble icon " + id + " (assets/" + id.getNamespace() + "/" + FOLDER + "/" + id.getPath()
                + SUFFIX + "): no bubble is drawn for it");
        }
        return Optional.ofNullable(e);
    }
}
