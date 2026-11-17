package townkit.core.work.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.ResourceLocationException;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import townkit.api.work.ForgeRecipe;
import townkit.api.work.ForgeRecipeChange;
import townkit.api.work.GearSlot;
import townkit.api.work.TierPlace;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkFiles;
import townkit.core.work.cook.CookOverlay;

/**
 * Reads (and writes) the smith's data files. An unknown item, a bad id or number, or a field of the wrong kind skips
 * the file with one log line.
 * <pre>
 * forge_materials: {"action":"add","id":"mymod:steel","items":"#c:ingots/steel"}   (a tag, or one item id)
 *                  {"action":"remove","id":"mymod:steel"}
 * forge_tiers:     {"action":"add","id":"mymod:steel","after":"towntopia:iron","skill":30}   (skill optional, 1..100)
 *                  {"action":"remove","id":"mymod:steel"}
 * forge_recipes:   {"action":"add","result":"mymod:steel_pickaxe","count":1,"cost":{"mymod:steel":3,"towntopia:wood":2},
 *                   "tier":"mymod:steel","slot":"pickaxe","smithing":false}   (count, tier with slot, smithing optional)
 *                  {"action":"change","result":"minecraft:iron_chestplate","cost":{"towntopia:iron":6}}
 *                  {"action":"remove","result":"minecraft:bucket"}
 * </pre>
 * Materials and tiers are added or removed whole (an add of an existing id replaces it); recipes can also be changed
 * (count, cost, smithing).
 */
public final class ForgeCodecs {
    private ForgeCodecs() {
    }

    /** {@code forge_materials} files. */
    public static final class Materials implements WorkFiles.WorkCodec<ResourceLocation, ForgeOverlay.MaterialDef> {
        private final CookOverlay.ItemIds items;

        public Materials(CookOverlay.ItemIds items) {
            this.items = items;
        }

        @Override
        public WorkChange<ResourceLocation, ForgeOverlay.MaterialDef> read(ResourceLocation file, JsonObject json) throws JsonParseException {
            WorkChange.Kind action = WorkFiles.action(json);
            ResourceLocation id = id(json.get("id"), "id");
            String owner = "data file " + file;
            return switch (action) {
                case ADD -> {
                    String s = string(json.get("items"), "items");
                    ForgeOverlay.MaterialDef m = s.startsWith("#")
                        ? new ForgeOverlay.MaterialDef(id, TagKey.create(Registries.ITEM, parse(s.substring(1), "items")), null)
                        : new ForgeOverlay.MaterialDef(id, null, item(this.items, s, "items"));
                    yield WorkChange.add(owner, id, m);
                }
                case CHANGE -> throw new JsonParseException("a material can't be changed; add it again to replace it");
                case REMOVE -> WorkChange.remove(owner, id);
            };
        }

        /** The add file for {@code m}. */
        public JsonObject write(ForgeOverlay.MaterialDef m) {
            JsonObject o = new JsonObject();
            o.addProperty("action", "add");
            o.addProperty("id", m.id().toString());
            o.addProperty("items", m.tag() != null ? "#" + m.tag().location() : this.items.id(m.item()).toString());
            return o;
        }
    }

    /** {@code forge_tiers} files. */
    public static final class Tiers implements WorkFiles.WorkCodec<ResourceLocation, ForgeOverlay.TierDef> {
        @Override
        public WorkChange<ResourceLocation, ForgeOverlay.TierDef> read(ResourceLocation file, JsonObject json) throws JsonParseException {
            WorkChange.Kind action = WorkFiles.action(json);
            ResourceLocation id = id(json.get("id"), "id");
            String owner = "data file " + file;
            return switch (action) {
                case ADD -> {
                    TierPlace place = TierPlace.after(id(json.get("after"), "after"));
                    if (json.has("skill")) {
                        try {
                            place = place.skill(whole(json.get("skill"), "skill"));
                        } catch (IllegalArgumentException e) {
                            throw new JsonParseException(e.getMessage());
                        }
                    }
                    yield WorkChange.add(owner, id, new ForgeOverlay.TierDef(id, place));
                }
                case CHANGE -> throw new JsonParseException("a tier can't be changed; add it again to replace it");
                case REMOVE -> WorkChange.remove(owner, id);
            };
        }

        /** The add file for {@code t}. */
        public JsonObject write(ForgeOverlay.TierDef t) {
            JsonObject o = new JsonObject();
            o.addProperty("action", "add");
            o.addProperty("id", t.id().toString());
            o.addProperty("after", t.place().after().toString());
            t.place().skill().ifPresent(s -> o.addProperty("skill", s));
            return o;
        }
    }

    /** {@code forge_recipes} files. */
    public static final class Recipes implements WorkFiles.WorkCodec<Item, ForgeRecipe> {
        private final CookOverlay.ItemIds items;

        public Recipes(CookOverlay.ItemIds items) {
            this.items = items;
        }

        @Override
        public WorkChange<Item, ForgeRecipe> read(ResourceLocation file, JsonObject json) throws JsonParseException {
            WorkChange.Kind action = WorkFiles.action(json);
            Item result = item(this.items, string(json.get("result"), "result"), "result");
            String owner = "data file " + file;
            try {
                return switch (action) {
                    case ADD -> {
                        ForgeRecipe.Builder b = ForgeRecipe.builder(result);
                        JsonElement cost = json.get("cost");
                        if (cost == null) throw new JsonParseException("missing \"cost\"");
                        cost(cost).forEach(b::cost);
                        if (json.has("count")) b.count(whole(json.get("count"), "count"));
                        if (json.has("tier") || json.has("slot")) {
                            if (!json.has("tier") || !json.has("slot")) throw new JsonParseException("gear needs both \"tier\" and \"slot\"");
                            b.gear(id(json.get("tier"), "tier"), slot(json.get("slot")));
                        }
                        if (json.has("smithing")) b.smithing(bool(json.get("smithing"), "smithing"));
                        yield WorkChange.add(owner, result, b.build());
                    }
                    case CHANGE -> {
                        ForgeRecipeChange.Builder b = ForgeRecipeChange.builder();
                        if (json.has("cost")) cost(json.get("cost")).forEach(b::cost);
                        if (json.has("count")) b.count(whole(json.get("count"), "count"));
                        if (json.has("smithing")) b.smithing(bool(json.get("smithing"), "smithing"));
                        if (json.has("tier") || json.has("slot")) throw new JsonParseException("a change can't move gear to another tier or slot");
                        ForgeRecipeChange change = b.build();
                        if (change.isEmpty()) throw new JsonParseException("a change must set at least one field");
                        yield WorkChange.change(owner, result, change::apply);
                    }
                    case REMOVE -> WorkChange.remove(owner, result);
                };
            } catch (IllegalArgumentException e) {
                throw new JsonParseException(e.getMessage());
            }
        }

        /** The add file for {@code r}. */
        public JsonObject write(ForgeRecipe r) {
            JsonObject o = new JsonObject();
            o.addProperty("action", "add");
            o.addProperty("result", this.items.id(r.result()).toString());
            o.addProperty("count", r.count());
            JsonObject cost = new JsonObject();
            r.cost().forEach((m, n) -> cost.addProperty(m.toString(), n));
            o.add("cost", cost);
            if (r.isGear()) {
                o.addProperty("tier", r.tier().toString());
                o.addProperty("slot", r.slot().name().toLowerCase(Locale.ROOT));
            }
            o.addProperty("smithing", r.smithing());
            return o;
        }

        private static Map<ResourceLocation, Integer> cost(JsonElement e) {
            if (!e.isJsonObject()) throw new JsonParseException("\"cost\" must be an object of material id to amount");
            Map<ResourceLocation, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> c : e.getAsJsonObject().entrySet()) {
                out.put(parse(c.getKey(), "cost"), whole(c.getValue(), "amount of " + c.getKey()));
            }
            if (out.isEmpty()) throw new JsonParseException("\"cost\" is empty");
            return out;
        }

        private static GearSlot slot(JsonElement e) {
            String s = string(e, "slot");
            try {
                return GearSlot.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new JsonParseException("unknown slot \"" + s + "\" (axe, pickaxe, hoe, sword, shovel, helmet, chestplate, leggings, boots)");
            }
        }
    }

    // --- shared field readers ------------------------------------------------------------------------------------------

    private static String string(@Nullable JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new JsonParseException("missing or bad \"" + what + "\"");
        return e.getAsString();
    }

    private static ResourceLocation id(@Nullable JsonElement e, String what) {
        return parse(string(e, what), what);
    }

    private static ResourceLocation parse(String s, String what) {
        try {
            return ResourceLocation.parse(s);
        } catch (ResourceLocationException ex) {
            throw new JsonParseException("bad id \"" + s + "\" (" + what + ")");
        }
    }

    private static Item item(CookOverlay.ItemIds items, String s, String what) {
        ResourceLocation id = parse(s, what);
        Item item = items.byId(id);
        if (item == null) throw new JsonParseException("unknown item " + id + " (" + what + ")");
        return item;
    }

    private static int whole(@Nullable JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new JsonParseException("\"" + what + "\" must be a whole number");
        try {
            return e.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            throw new JsonParseException("\"" + what + "\" must be a whole number: " + e);
        }
    }

    private static boolean bool(@Nullable JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new JsonParseException("\"" + what + "\" must be true or false");
        return e.getAsBoolean();
    }
}
