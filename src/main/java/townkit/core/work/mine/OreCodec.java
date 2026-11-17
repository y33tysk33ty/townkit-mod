package townkit.core.work.mine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import net.minecraft.ResourceLocationException;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import townkit.api.work.OreEntry;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkFiles;
import townkit.core.work.WorkLog;

/**
 * Reads (and writes) the {@code ores} data files. An unknown block, a missing field, a bad range or a weight at or below 0
 * skips the file with one log line; so does any file naming one of Towntopia's seven ores (they stay Towntopia's).
 * <pre>
 * {"action":"add","id":"mymod:tin","stone":"mymod:tin_ore","deepslate":"mymod:deepslate_tin_ore","label":"Tin",
 *  "depths":[0,64],"lucky_rolls":{"weight":6.0,"low":0,"high":64},"druid":{"weight":2.0,"low":0,"high":32}}
 * {"action":"change","id":"mymod:tin","depths":[0,32],"druid":null}
 * {"action":"remove","id":"mymod:tin"}
 * </pre>
 * {@code stone} is needed for an add; every other field is optional. A change sets each field it names ({@code null}
 * clears {@code deepslate}, {@code label}, {@code depths}, {@code lucky_rolls} and {@code druid}) and keeps the rest.
 * A key the format doesn't know (a typo such as {@code lucky_roll}) is ignored with one log line naming the file and the
 * key; a change that names only unknown keys sets nothing and skips the file.
 */
public final class OreCodec implements WorkFiles.WorkCodec<ResourceLocation, OreEntry> {
    /** Every key an ore file may have. */
    static final Set<String> KEYS = Set.of("action", "id", "stone", "deepslate", "label", "depths", "lucky_rolls", "druid");

    private final MiningOverlay.BlockIds blocks;
    private final WorkLog log;

    public OreCodec(MiningOverlay.BlockIds blocks) {
        this(blocks, WorkLog.GLOBAL);
    }

    public OreCodec(MiningOverlay.BlockIds blocks, WorkLog log) {
        this.blocks = blocks;
        this.log = log;
    }

    @Override
    public WorkChange<ResourceLocation, OreEntry> read(ResourceLocation file, JsonObject json) throws JsonParseException {
        WorkChange.Kind action = WorkFiles.action(json);
        ResourceLocation id = id(json.get("id"));
        if (MiningOverlay.isTowntopias(id)) throw new JsonParseException(id + " is one of Towntopia's own ores; they stay Towntopia's");
        String owner = "data file " + file;
        for (String k : new TreeSet<>(json.keySet())) {
            if (!KEYS.contains(k)) {
                this.log.once("ore-unknown-key|" + file + "|" + k, "[" + MiningOverlay.KIND + "] " + owner + ": unknown key \""
                    + k + "\" ignored (the keys are " + new TreeSet<>(KEYS) + ")");
            }
        }
        return switch (action) {
            case ADD -> {
                OreEntry base = OreEntry.builder(id, block(json.get("stone"), "stone")).build();
                yield WorkChange.add(owner, id, checked(base, json));
            }
            case CHANGE -> {
                if (json.keySet().stream().allMatch(k -> k.equals("action") || k.equals("id") || !KEYS.contains(k))) {
                    throw new JsonParseException("the change sets nothing");
                }
                validate(json);
                yield WorkChange.change(owner, id, ore -> apply(ore, json));
            }
            case REMOVE -> WorkChange.remove(owner, id);
        };
    }

    /** {@code base} with the file's fields; a bad value or range is a bad file. */
    private OreEntry checked(OreEntry base, JsonObject json) {
        try {
            return apply(base, json);
        } catch (IllegalArgumentException e) {
            throw new JsonParseException(e.getMessage());
        }
    }

    /** Parses every field a change names, so a bad one skips the file now rather than when the change is applied. */
    private void validate(JsonObject json) {
        try {
            if (json.has("stone")) block(json.get("stone"), "stone");
            if (json.has("deepslate") && !json.get("deepslate").isJsonNull()) block(json.get("deepslate"), "deepslate");
            if (json.has("label") && !json.get("label").isJsonNull()) string(json.get("label"), "label");
            if (json.has("depths") && !json.get("depths").isJsonNull()) depths(json.get("depths"));
            if (json.has("lucky_rolls") && !json.get("lucky_rolls").isJsonNull()) spread(json.get("lucky_rolls"), "lucky_rolls");
            if (json.has("druid") && !json.get("druid").isJsonNull()) spread(json.get("druid"), "druid");
        } catch (IllegalArgumentException e) {
            throw new JsonParseException(e.getMessage());
        }
    }

    /** {@code ore} with each field {@code json} names replaced (null clears an optional one). Throws for a bad value or range. */
    private OreEntry apply(OreEntry ore, JsonObject json) {
        Block stone = json.has("stone") ? block(json.get("stone"), "stone") : ore.stone();
        Block deepslate = ore.deepslate();
        Component label = ore.label();
        OreEntry.Depths depths = ore.depths();
        OreEntry.Spread lucky = ore.luckyRolls();
        OreEntry.Spread druid = ore.druid();
        if (json.has("deepslate")) deepslate = json.get("deepslate").isJsonNull() ? null : block(json.get("deepslate"), "deepslate");
        if (json.has("label")) label = json.get("label").isJsonNull() ? null : Component.literal(string(json.get("label"), "label"));
        if (json.has("depths")) depths = json.get("depths").isJsonNull() ? null : depths(json.get("depths"));
        if (json.has("lucky_rolls")) lucky = json.get("lucky_rolls").isJsonNull() ? null : spread(json.get("lucky_rolls"), "lucky_rolls");
        if (json.has("druid")) druid = json.get("druid").isJsonNull() ? null : spread(json.get("druid"), "druid");
        return new OreEntry(ore.id(), stone, deepslate, label, depths, lucky, druid);
    }

    /** The add file for {@code ore}. */
    public JsonObject write(OreEntry ore) {
        JsonObject o = new JsonObject();
        o.addProperty("action", "add");
        o.addProperty("id", ore.id().toString());
        o.addProperty("stone", this.blocks.id(ore.stone()).toString());
        if (ore.deepslate() != null) o.addProperty("deepslate", this.blocks.id(ore.deepslate()).toString());
        if (ore.label() != null) o.addProperty("label", ore.label().getString());
        if (ore.depths() != null) {
            JsonArray a = new JsonArray();
            a.add(ore.depths().low());
            a.add(ore.depths().high());
            o.add("depths", a);
        }
        if (ore.luckyRolls() != null) o.add("lucky_rolls", write(ore.luckyRolls()));
        if (ore.druid() != null) o.add("druid", write(ore.druid()));
        return o;
    }

    private static JsonObject write(OreEntry.Spread s) {
        JsonObject o = new JsonObject();
        o.addProperty("weight", s.weight());
        o.addProperty("low", s.low());
        o.addProperty("high", s.high());
        return o;
    }

    private static OreEntry.Depths depths(JsonElement e) {
        if (!e.isJsonArray() || e.getAsJsonArray().size() != 2) throw new JsonParseException("\"depths\" must be [low, high]");
        JsonArray a = e.getAsJsonArray();
        return new OreEntry.Depths(integer(a.get(0), "depths"), integer(a.get(1), "depths"));
    }

    private static OreEntry.Spread spread(JsonElement e, String what) {
        if (!e.isJsonObject()) throw new JsonParseException("\"" + what + "\" must be {\"weight\":..,\"low\":..,\"high\":..}");
        JsonObject o = e.getAsJsonObject();
        for (String k : new String[] {"weight", "low", "high"}) {
            if (!o.has(k)) throw new JsonParseException("\"" + what + "\" needs \"" + k + "\"");
        }
        return new OreEntry.Spread(number(o.get("weight"), what), integer(o.get("low"), what), integer(o.get("high"), what));
    }

    private static int integer(JsonElement e, String what) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new JsonParseException("bad number in \"" + what + "\"");
        double d = e.getAsDouble();
        if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) throw new JsonParseException("\"" + what + "\" needs whole numbers: " + d);
        return (int) d;
    }

    private static double number(JsonElement e, String what) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new JsonParseException("bad number in \"" + what + "\"");
        return e.getAsDouble();
    }

    private static String string(@Nullable JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new JsonParseException("missing or bad \"" + what + "\"");
        return e.getAsString();
    }

    private static ResourceLocation id(@Nullable JsonElement e) {
        String s = string(e, "id");
        try {
            return ResourceLocation.parse(s);
        } catch (ResourceLocationException ex) {
            throw new JsonParseException("bad id \"" + s + "\"");
        }
    }

    private Block block(@Nullable JsonElement e, String what) {
        ResourceLocation id;
        try {
            id = ResourceLocation.parse(string(e, what));
        } catch (ResourceLocationException ex) {
            throw new JsonParseException("bad id \"" + e.getAsString() + "\" (" + what + ")");
        }
        Block block = this.blocks.byId(id);
        if (block == null) throw new JsonParseException("unknown block " + id + " (" + what + ")");
        return block;
    }
}
