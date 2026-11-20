package townkit.core.farm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import net.minecraft.ResourceLocationException;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import townkit.api.farm.AwayYield;
import townkit.api.farm.BlockRef;
import townkit.api.farm.HarvestAction;
import townkit.api.farm.HarvestRule;
import townkit.api.farm.Holding;
import townkit.api.farm.Ripe;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkFiles;
import townkit.core.work.WorkLog;

/**
 * Reads the {@code farm_harvest} data files. A missing field, an unknown block or item, a ripe property a block doesn't
 * have (or a value outside its range) skips the file with one log line.
 * <pre>
 * {"action":"add","id":"ttcompat:hh_blueberry","blocks":["hearthandharvest:blueberry_bush","#mymod:bushes"],
 *  "ripe":{"property":"age","at_least":3},"harvest":{"type":"use","holding":"empty_hand"},
 *  "away":{"item":"hearthandharvest:blueberries","count":[2,3],"picked_state":{"age":"1"}}}
 * {"action":"change","id":"ttcompat:hh_blueberry","ripe":{"property":"age","is":"3"}}
 * {"action":"remove","id":"ttcompat:hh_blueberry"}
 * </pre>
 * {@code ripe} and {@code away} are optional ({@code ripe}: {@code at_least} a number, or {@code is} a value; {@code count}
 * a number or {@code [min, max]}); {@code harvest.type} is {@code use} or {@code break}, {@code holding} (for {@code use})
 * {@code empty_hand}, {@code shears} or {@code axe}. A block starting with {@code #} is a block tag; a rule that names
 * tags is checked against their members only after the tags are loaded. A change sets each field it names and keeps the
 * rest ({@code "away": null} removes the away yield); a key the format doesn't know is ignored with one log line.
 *
 * @since 0.8.0
 */
public final class HarvestCodec implements WorkFiles.WorkCodec<ResourceLocation, HarvestRule> {
    static final Set<String> KEYS = Set.of("action", "id", "blocks", "ripe", "harvest", "away");

    private final HarvestBlocks blocks;
    private final SeedCodec.ItemIds items;
    private final WorkLog log;

    public HarvestCodec(HarvestBlocks blocks, SeedCodec.ItemIds items, WorkLog log) {
        this.blocks = blocks;
        this.items = items;
        this.log = log;
    }

    @Override
    public WorkChange<ResourceLocation, HarvestRule> read(ResourceLocation file, JsonObject json) throws JsonParseException {
        WorkChange.Kind action = WorkFiles.action(json);
        ResourceLocation id = id(json.get("id"), "id");
        String owner = "data file " + file;
        for (String k : new TreeSet<>(json.keySet())) {
            if (!KEYS.contains(k)) {
                this.log.once("harvest-unknown-key|" + file + "|" + k, "[" + HarvestTable.KIND + "] " + owner + ": unknown key \"" + k
                    + "\" ignored (the keys are " + new TreeSet<>(KEYS) + ")");
            }
        }
        return switch (action) {
            case ADD -> {
                HarvestRule.Builder b = HarvestRule.builder(id).blocks(blockRefs(json.get("blocks")));
                if (json.has("ripe")) b.ripe(ripe(json.get("ripe")));
                if (json.has("harvest")) b.action(harvest(json.get("harvest")));
                if (json.has("away") && !json.get("away").isJsonNull()) b.away(away(json.get("away")));
                HarvestRule rule = b.build();
                checkRipe(rule);
                yield WorkChange.add(owner, id, rule);
            }
            case CHANGE -> {
                if (!json.has("blocks") && !json.has("ripe") && !json.has("harvest") && !json.has("away")) {
                    throw new JsonParseException("the change sets nothing");
                }
                List<BlockRef> refs = json.has("blocks") ? blockRefs(json.get("blocks")) : null;
                Ripe ripe = json.has("ripe") ? ripe(json.get("ripe")) : null;
                HarvestAction act = json.has("harvest") ? harvest(json.get("harvest")) : null;
                boolean setsAway = json.has("away");
                AwayYield away = setsAway && !json.get("away").isJsonNull() ? away(json.get("away")) : null;
                yield WorkChange.change(owner, id, r -> {
                    HarvestRule.Builder b = r.toBuilder();
                    if (refs != null) b.blocks(refs);
                    if (ripe != null) b.ripe(ripe);
                    if (act != null) b.action(act);
                    if (setsAway) {
                        if (away != null) b.away(away);
                        else b.noAway();
                    }
                    return b.build();
                });
            }
            case REMOVE -> WorkChange.remove(owner, id);
        };
    }

    /** Checks the ripe property against the named blocks, when no tag is named (a tag's members are not known yet). */
    private void checkRipe(HarvestRule rule) {
        List<Block> named = new ArrayList<>();
        for (BlockRef ref : rule.blocks()) {
            if (ref.block() == null) return;
            named.add(ref.block());
        }
        String problem = HarvestTable.problem(rule, named, this.blocks);
        if (problem != null) throw new JsonParseException(problem);
    }

    private ResourceLocation id(@Nullable JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("missing or bad \"" + what + "\" (an id like mymod:name)");
        }
        try {
            return ResourceLocation.parse(e.getAsString());
        } catch (ResourceLocationException ex) {
            throw new JsonParseException("bad id \"" + e.getAsString() + "\" (" + what + ")");
        }
    }

    private List<BlockRef> blockRefs(@Nullable JsonElement e) {
        if (e == null) throw new JsonParseException("missing \"blocks\" (a block id, or a list of them; #tag for a block tag)");
        List<BlockRef> out = new ArrayList<>();
        if (e.isJsonArray()) {
            for (JsonElement entry : e.getAsJsonArray()) out.add(blockRef(entry));
        } else {
            out.add(blockRef(e));
        }
        if (out.isEmpty()) throw new JsonParseException("\"blocks\" is empty");
        return out;
    }

    private BlockRef blockRef(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new JsonParseException("a block must be a string like mymod:bush");
        String raw = e.getAsString();
        if (raw.startsWith("#")) {
            ResourceLocation tag = id(new JsonPrimitive(raw.substring(1)), "block tag");
            return BlockRef.ofTag(TagKey.create(Registries.BLOCK, tag));
        }
        ResourceLocation id = id(e, "block");
        Block block = this.blocks.byId(id);
        if (block == null) throw new JsonParseException("unknown block " + id);
        return BlockRef.of(block);
    }

    private static Ripe ripe(JsonElement e) {
        if (!e.isJsonObject()) throw new JsonParseException("\"ripe\" must be an object like {\"property\":\"age\",\"at_least\":3}");
        JsonObject o = e.getAsJsonObject();
        JsonElement property = o.get("property");
        if (property == null || !property.isJsonPrimitive() || property.getAsString().isBlank()) {
            throw new JsonParseException("\"ripe\" needs a \"property\" (a block property name)");
        }
        boolean atLeast = o.has("at_least");
        boolean is = o.has("is");
        if (atLeast == is) throw new JsonParseException("\"ripe\" needs exactly one of \"at_least\" and \"is\"");
        if (atLeast) {
            return new Ripe.AtLeast(property.getAsString(), wholeNumber(o.get("at_least"), "at_least"));
        }
        JsonElement v = o.get("is");
        if (!v.isJsonPrimitive()) throw new JsonParseException("\"is\" must be a value (a number, true/false or a name)");
        return new Ripe.Is(property.getAsString(), v.getAsString());
    }

    private static HarvestAction harvest(JsonElement e) {
        if (!e.isJsonObject()) throw new JsonParseException("\"harvest\" must be an object like {\"type\":\"use\"}");
        JsonObject o = e.getAsJsonObject();
        String type = text(o.get("type"), "type").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "break" -> new HarvestAction.Break();
            case "use" -> {
                String holding = o.has("holding") ? text(o.get("holding"), "holding").toLowerCase(Locale.ROOT) : "empty_hand";
                yield new HarvestAction.Use(switch (holding) {
                    case "empty_hand" -> Holding.EMPTY_HAND;
                    case "shears" -> Holding.SHEARS;
                    case "axe" -> Holding.AXE;
                    default -> throw new JsonParseException("unknown holding \"" + holding + "\" (use empty_hand, shears or axe)");
                });
            }
            default -> throw new JsonParseException("unknown harvest type \"" + type + "\" (use use or break)");
        };
    }

    private AwayYield away(JsonElement e) {
        if (!e.isJsonObject()) throw new JsonParseException("\"away\" must be an object");
        JsonObject o = e.getAsJsonObject();
        ResourceLocation itemId = id(o.get("item"), "away item");
        Item item = this.items.byId(itemId);
        if (item == null) throw new JsonParseException("unknown item " + itemId + " (away)");
        JsonElement count = o.get("count");
        int min;
        int max;
        if (count != null && count.isJsonArray()) {
            JsonArray a = count.getAsJsonArray();
            if (a.size() != 2) throw new JsonParseException("\"count\" must be a number or [min, max]");
            min = wholeNumber(a.get(0), "count");
            max = wholeNumber(a.get(1), "count");
        } else {
            min = wholeNumber(count, "count");
            max = min;
        }
        if (min < 0 || max < min) throw new JsonParseException("\"count\" must be 0 <= min <= max, not " + min + ".." + max);
        JsonElement state = o.get("picked_state");
        if (state == null || !state.isJsonObject() || state.getAsJsonObject().size() == 0) {
            throw new JsonParseException("\"picked_state\" must be an object with at least one property, like {\"age\":\"1\"}");
        }
        Map<String, String> picked = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> s : state.getAsJsonObject().entrySet()) {
            if (!s.getValue().isJsonPrimitive()) throw new JsonParseException("\"picked_state\" values must be plain values");
            picked.put(s.getKey(), s.getValue().getAsString());
        }
        return new AwayYield(item, min, max, picked);
    }

    private static String text(@Nullable JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new JsonParseException("missing or bad \"" + what + "\"");
        return e.getAsString();
    }

    private static int wholeNumber(@Nullable JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("\"" + what + "\" must be a whole number");
        }
        try {
            return e.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            throw new JsonParseException("\"" + what + "\" must be a whole number: " + e);
        }
    }
}
