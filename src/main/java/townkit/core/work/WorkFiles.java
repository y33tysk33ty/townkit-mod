package townkit.core.work;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Data files for work tables: {@code data/<namespace>/townkit/<kind>/*.json}. Every file has an {@code "action"} of
 * {@code add}, {@code change} or {@code remove}; the kind's {@link WorkCodec} reads the rest. A bad file is skipped
 * with one log line naming it.
 */
public final class WorkFiles {
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    private WorkFiles() {
    }

    /** Reads one kind's file into a change; throws {@link JsonParseException} (or any runtime exception) for a bad file. */
    public interface WorkCodec<K, E> {
        WorkChange<K, E> read(ResourceLocation file, JsonObject json) throws JsonParseException;
    }

    /** The {@code "action"} of a file; throws {@link JsonParseException} when it is missing or unknown. */
    public static WorkChange.Kind action(JsonObject json) throws JsonParseException {
        JsonElement a = json.get("action");
        if (a == null || !a.isJsonPrimitive()) throw new JsonParseException("missing \"action\"");
        return switch (a.getAsString().toLowerCase(Locale.ROOT)) {
            case "add" -> WorkChange.Kind.ADD;
            case "change" -> WorkChange.Kind.CHANGE;
            case "remove" -> WorkChange.Kind.REMOVE;
            default -> throw new JsonParseException("unknown action \"" + a.getAsString() + "\" (use add, change or remove)");
        };
    }

    public static <K, E> List<WorkChange<K, E>> parse(String kind, Map<ResourceLocation, JsonElement> files, WorkCodec<K, E> codec) {
        return parse(kind, files, codec, WorkLog.GLOBAL);
    }

    static <K, E> List<WorkChange<K, E>> parse(String kind, Map<ResourceLocation, JsonElement> files, WorkCodec<K, E> codec, WorkLog log) {
        List<WorkChange<K, E>> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> f : files.entrySet()) {
            ResourceLocation id = f.getKey();
            try {
                if (!f.getValue().isJsonObject()) throw new JsonParseException("not a JSON object");
                JsonObject json = f.getValue().getAsJsonObject();
                WorkChange.Kind action = action(json);
                WorkChange<K, E> c = codec.read(id, json);
                if (c == null || c.kind() != action) throw new JsonParseException("action does not match what the file describes");
                out.add(c);
            } catch (RuntimeException ex) {
                log.once("file|" + kind + "|" + id, "[" + kind + "] skipped data file " + id + ": " + ex.getMessage());
            }
        }
        return out;
    }

    /** The server reload listener: reads every registered kind's folder and hands the files, by kind, to {@code load}. */
    static SimplePreparableReloadListener<Map<String, Map<ResourceLocation, JsonElement>>> listener(Map<String, CoreWork.Kind<?, ?>> kinds,
            java.util.function.Consumer<Map<String, Map<ResourceLocation, JsonElement>>> load) {
        return new SimplePreparableReloadListener<>() {
            @Override
            protected Map<String, Map<ResourceLocation, JsonElement>> prepare(ResourceManager manager, ProfilerFiller profiler) {
                Map<String, Map<ResourceLocation, JsonElement>> all = new HashMap<>();
                for (String kind : kinds.keySet()) {
                    Map<ResourceLocation, JsonElement> files = new java.util.LinkedHashMap<>();
                    SimpleJsonResourceReloadListener.scanDirectory(manager, "townkit/" + kind, GSON, files);
                    all.put(kind, files);
                }
                return all;
            }

            @Override
            protected void apply(Map<String, Map<ResourceLocation, JsonElement>> all, ResourceManager manager, ProfilerFiller profiler) {
                load.accept(all);
            }
        };
    }
}
