package townkit.core.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import townkit.core.TownKit;

/**
 * The move file format ({@code assets/<ns>/townkit/moves/<name>.json} -> move {@code <ns>:<name>}), shared by the
 * server ({@link MoveLengths}, which reads move files from mod jars) and the client ({@code core.client.anim.Moves},
 * which reads them from its resources). Server-safe: touches no client class.
 */
public final class MoveFiles {
    /** Where move files live, under {@code assets/<ns>/}. */
    public static final String FOLDER = "townkit/moves";
    /** Towntopia's villager animation file: Towntopia's moves resolve to clips in it. */
    public static final ResourceLocation TOWNTOPIA_FILE = ResourceLocation.fromNamespaceAndPath("towntopia", "animations/base_villager.animation.json");

    private MoveFiles() {
    }

    /**
     * One move file.
     *
     * @param props the props the move carries (drawn by the prop layer)
     */
    public record Def(ResourceLocation id, ResourceLocation animationFile, String animation, List<ResourceLocation> props) {
        public Def {
            props = List.copyOf(props);
        }
    }

    /** The move id of a move file found at {@code file} ({@code <ns>:townkit/moves/<name>.json}). */
    public static ResourceLocation idOf(ResourceLocation file) {
        String path = file.getPath();
        String name = path.substring(FOLDER.length() + 1, path.length() - ".json".length());
        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(), name);
    }

    /** Reads a move file; empty (logged) when it lacks {@code animation_file} or {@code animation}, or isn't valid. */
    public static Optional<Def> parse(ResourceLocation id, Reader reader) {
        try {
            JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
            ResourceLocation file = o.has("animation_file") ? ResourceLocation.tryParse(o.get("animation_file").getAsString()) : null;
            String animation = o.has("animation") ? o.get("animation").getAsString() : null;
            if (file == null || animation == null || animation.isBlank()) {
                TownKit.LOG.warn("[TownKit] move {} ignored: its file needs \"animation_file\" (a resource id) and \"animation\"", id);
                return Optional.empty();
            }
            List<ResourceLocation> props = new ArrayList<>();
            if (o.has("props")) {
                JsonArray a = o.getAsJsonArray("props");
                for (JsonElement e : a) {
                    ResourceLocation p = ResourceLocation.tryParse(e.getAsString());
                    if (p != null) props.add(p);
                }
            }
            return Optional.of(new Def(id, file, animation, props));
        } catch (RuntimeException e) {
            TownKit.LOG.warn("[TownKit] move {} ignored: can't read its file ({})", id, e.toString());
            return Optional.empty();
        }
    }
}
