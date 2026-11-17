package townkit.core.work.cook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import townkit.api.work.Dish;
import townkit.api.work.DishChange;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkFiles;

/**
 * Reads (and writes) {@code cook_dishes} data files. The fields are the {@link Dish} ones:
 * <pre>
 * {"action":"add","result":"mymod:stew","output":1,"ingredients":{"mymod:bean":2,"minecraft:bowl":1},
 *  "heated":true,"nutrition":8,"station":"mymod:pot","stock":16}
 * {"action":"change","result":"minecraft:bread","ingredients":{"minecraft:wheat":2}}
 * {"action":"remove","result":"minecraft:cake"}
 * </pre>
 * An add needs {@code result} and {@code ingredients} (the rest default as in {@link Dish#builder}); a change sets only
 * the fields it names (ingredients replace the whole list; {@code "station":null} takes the station away); a remove
 * names only {@code result}. An unknown item, a count, output or stock below 1, a number that isn't a whole number, or a
 * nutrition outside 0..20 skips the file with one log line.
 */
public final class DishCodec implements WorkFiles.WorkCodec<Item, Dish> {
    private final CookOverlay.ItemIds items;

    public DishCodec(CookOverlay.ItemIds items) {
        this.items = items;
    }

    @Override
    public WorkChange<Item, Dish> read(ResourceLocation file, JsonObject json) throws JsonParseException {
        WorkChange.Kind action = WorkFiles.action(json);
        Item result = item(json.get("result"), "result");
        String owner = "data file " + file;
        return switch (action) {
            case ADD -> {
                Dish.Builder b = Dish.builder(result);
                JsonElement in = json.get("ingredients");
                if (in == null) throw new JsonParseException("missing \"ingredients\"");
                ingredients(in).forEach(b::ingredient);
                if (json.has("output")) b.output(whole(json.get("output"), "output"));
                if (json.has("heated")) b.heated(bool(json.get("heated"), "heated"));
                if (json.has("nutrition")) b.nutrition(whole(json.get("nutrition"), "nutrition"));
                if (json.has("station")) b.station(station(json.get("station")));
                if (json.has("stock")) b.stock(whole(json.get("stock"), "stock"));
                yield WorkChange.add(owner, result, b.build());
            }
            case CHANGE -> {
                DishChange.Builder b = DishChange.builder();
                if (json.has("ingredients")) ingredients(json.get("ingredients")).forEach(b::ingredient);
                if (json.has("output")) b.output(whole(json.get("output"), "output"));
                if (json.has("heated")) b.heated(bool(json.get("heated"), "heated"));
                if (json.has("nutrition")) b.nutrition(whole(json.get("nutrition"), "nutrition"));
                if (json.has("station")) b.station(station(json.get("station")));
                if (json.has("stock")) b.stock(whole(json.get("stock"), "stock"));
                DishChange change = b.build();
                if (change.isEmpty()) throw new JsonParseException("a change must set at least one field");
                yield WorkChange.change(owner, result, change::apply);
            }
            case REMOVE -> WorkChange.remove(owner, result);
        };
    }

    /** The add file for {@code dish} (what {@link #read} turns back into it). */
    public JsonObject write(Dish dish) {
        JsonObject o = new JsonObject();
        o.addProperty("action", "add");
        o.addProperty("result", this.items.id(dish.result()).toString());
        o.addProperty("output", dish.outputPerBatch());
        JsonObject in = new JsonObject();
        dish.ingredients().forEach((item, n) -> in.addProperty(this.items.id(item).toString(), n));
        o.add("ingredients", in);
        o.addProperty("heated", dish.heated());
        o.addProperty("nutrition", dish.nutrition());
        if (dish.station() != null) o.addProperty("station", dish.station().toString());
        o.addProperty("stock", dish.stock());
        return o;
    }

    private Map<Item, Integer> ingredients(JsonElement e) {
        if (!e.isJsonObject()) throw new JsonParseException("\"ingredients\" must be an object of item id to count");
        Map<Item, Integer> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> i : e.getAsJsonObject().entrySet()) {
            out.put(item(new JsonPrimitive(i.getKey()), "ingredient"), whole(i.getValue(), "count of " + i.getKey()));
        }
        if (out.isEmpty()) throw new JsonParseException("\"ingredients\" is empty");
        return out;
    }

    private Item item(JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("missing or bad \"" + what + "\" (an item id)");
        }
        ResourceLocation id = id(e.getAsString(), what);
        Item item = this.items.byId(id);
        if (item == null) throw new JsonParseException("unknown item " + id + " (" + what + ")");
        return item;
    }

    private static ResourceLocation id(String s, String what) {
        try {
            return ResourceLocation.parse(s);
        } catch (ResourceLocationException ex) {
            throw new JsonParseException("bad id \"" + s + "\" (" + what + ")");
        }
    }

    @javax.annotation.Nullable
    private static ResourceLocation station(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new JsonParseException("\"station\" must be a block id or null");
        return id(e.getAsString(), "station");
    }

    private static int whole(JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("\"" + what + "\" must be a whole number");
        }
        try {
            return e.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            throw new JsonParseException("\"" + what + "\" must be a whole number: " + e);
        }
    }

    private static boolean bool(JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException("\"" + what + "\" must be true or false");
        }
        return e.getAsBoolean();
    }
}
