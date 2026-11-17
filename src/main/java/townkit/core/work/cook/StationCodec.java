package townkit.core.work.cook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.ResourceLocationException;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import townkit.api.work.CookStation;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkFiles;

/**
 * Reads (and writes) {@code cook_stations} data files:
 * <pre>
 * {"action":"add","id":"mymod:pot","blocks":"#mymod:pots"}     (every block in a tag)
 * {"action":"add","id":"mymod:pot","blocks":"mymod:pot_block"} (one block)
 * {"action":"remove","id":"mymod:pot"}
 * </pre>
 * A station from a file has no handler (handlers are Java only). There is no {@code change}: an add of an existing id
 * replaces it whole. An unknown block or a bad id skips the file with one log line.
 */
public final class StationCodec implements WorkFiles.WorkCodec<ResourceLocation, Stations.Def> {
    private final Stations.Blocks blocks;

    public StationCodec(Stations.Blocks blocks) {
        this.blocks = blocks;
    }

    @Override
    public WorkChange<ResourceLocation, Stations.Def> read(ResourceLocation file, JsonObject json) throws JsonParseException {
        WorkChange.Kind action = WorkFiles.action(json);
        ResourceLocation id = id(string(json.get("id"), "id"), "id");
        String owner = "data file " + file;
        return switch (action) {
            case ADD -> {
                String b = string(json.get("blocks"), "blocks");
                CookStation.Builder station = CookStation.builder();
                if (b.startsWith("#")) {
                    station.blocks(TagKey.create(Registries.BLOCK, id(b.substring(1), "blocks")));
                } else {
                    ResourceLocation blockId = id(b, "blocks");
                    Block block = this.blocks.byId(blockId);
                    if (block == null) throw new JsonParseException("unknown block " + blockId);
                    station.block(block);
                }
                yield WorkChange.add(owner, id, new Stations.Def(owner, id, station.build()));
            }
            case REMOVE -> WorkChange.remove(owner, id);
            case CHANGE -> throw new JsonParseException("cook_stations files add or remove a station (an add replaces it whole)");
        };
    }

    /** The add file for {@code def} (what {@link #read} turns back into it, without the handler). */
    public JsonObject write(Stations.Def def) {
        JsonObject o = new JsonObject();
        o.addProperty("action", "add");
        o.addProperty("id", def.id().toString());
        CookStation s = def.station();
        o.addProperty("blocks", s.block() != null ? this.blocks.id(s.block()).toString() : "#" + s.blocks().location());
        return o;
    }

    private static String string(JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("missing or bad \"" + what + "\"");
        }
        return e.getAsString();
    }

    private static ResourceLocation id(String s, String what) {
        try {
            return ResourceLocation.parse(s);
        } catch (ResourceLocationException ex) {
            throw new JsonParseException("bad id \"" + s + "\" (" + what + ")");
        }
    }
}
