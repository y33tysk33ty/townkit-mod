package townkit.core.work.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import javax.annotation.Nullable;
import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkFiles;
import townkit.core.work.cook.CookOverlay;

/**
 * Reads (and writes) the {@code smelting} data files. An unknown item or a missing field skips the file with one log line.
 * <pre>
 * {"action":"add","raw":"mymod:raw_tin","result":"mymod:tin_ingot"}
 * {"action":"change","raw":"minecraft:raw_copper","result":"mymod:tin_ingot"}
 * {"action":"remove","raw":"mymod:raw_tin"}
 * </pre>
 * Entries are keyed by the raw item, Towntopia's raw iron, gold and copper included (see {@link SmeltOverlay} for what
 * can be removed).
 */
public final class SmeltCodec implements WorkFiles.WorkCodec<Item, SmeltOverlay.Smelt> {
    private final CookOverlay.ItemIds items;

    public SmeltCodec(CookOverlay.ItemIds items) {
        this.items = items;
    }

    @Override
    public WorkChange<Item, SmeltOverlay.Smelt> read(ResourceLocation file, JsonObject json) throws JsonParseException {
        WorkChange.Kind action = WorkFiles.action(json);
        Item raw = item(json.get("raw"), "raw");
        String owner = "data file " + file;
        return switch (action) {
            case ADD -> {
                Item result = item(json.get("result"), "result");
                check(raw, result);
                yield WorkChange.add(owner, raw, new SmeltOverlay.Smelt(raw, result));
            }
            case CHANGE -> {
                Item result = item(json.get("result"), "result");
                check(raw, result);
                yield WorkChange.change(owner, raw, s -> new SmeltOverlay.Smelt(s.raw(), result));
            }
            case REMOVE -> WorkChange.remove(owner, raw);
        };
    }

    private static void check(Item raw, Item result) {
        String problem = SmeltOverlay.problem(raw, result);
        if (problem != null) throw new JsonParseException(problem);
    }

    /** The add file for {@code s}. */
    public JsonObject write(SmeltOverlay.Smelt s) {
        JsonObject o = new JsonObject();
        o.addProperty("action", "add");
        o.addProperty("raw", this.items.id(s.raw()).toString());
        o.addProperty("result", this.items.id(s.result()).toString());
        return o;
    }

    private Item item(@Nullable JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new JsonParseException("missing or bad \"" + what + "\"");
        ResourceLocation id;
        try {
            id = ResourceLocation.parse(e.getAsString());
        } catch (ResourceLocationException ex) {
            throw new JsonParseException("bad id \"" + e.getAsString() + "\" (" + what + ")");
        }
        Item item = this.items.byId(id);
        if (item == null) throw new JsonParseException("unknown item " + id + " (" + what + ")");
        return item;
    }
}
