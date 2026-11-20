package townkit.core.farm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import net.minecraft.ResourceLocationException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import townkit.core.work.WorkChange;
import townkit.core.work.WorkFiles;
import townkit.core.work.WorkLog;

/**
 * Reads the {@code farm_seeds} data files. A missing field, an unknown item, a count under 1 or a seed that is one of
 * Towntopia's own two skips the file with one log line.
 * <pre>
 * {"action":"add","seed":"mymod:corn_kernels","produce":"mymod:corn","seeds_per_produce":2}
 * {"action":"change","seed":"mymod:corn_kernels","seeds_per_produce":3}
 * {"action":"remove","seed":"mymod:corn_kernels"}
 * </pre>
 * A change sets each field it names and keeps the rest. A key the format doesn't know is ignored with one log line; a
 * change that names no known field sets nothing and skips the file.
 *
 * @since 0.8.0
 */
public final class SeedCodec implements WorkFiles.WorkCodec<Item, SeedTable.Entry> {
    static final Set<String> KEYS = Set.of("action", "seed", "produce", "seeds_per_produce");

    /** Item ids (the registry in game; the unit tests hand their own). */
    public interface ItemIds {
        /** The item with this id, or null when there is none (air counts as none). */
        @Nullable
        Item byId(ResourceLocation id);

        ResourceLocation id(Item item);
    }

    /** The game's items. */
    public static final ItemIds REGISTRY = new ItemIds() {
        @Nullable
        @Override
        public Item byId(ResourceLocation id) {
            Item i = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            return i == Items.AIR ? null : i;
        }

        @Override
        public ResourceLocation id(Item item) {
            return BuiltInRegistries.ITEM.getKey(item);
        }
    };

    private final ItemIds items;
    private final SeedTable table;
    private final WorkLog log;

    public SeedCodec(ItemIds items, SeedTable table, WorkLog log) {
        this.items = items;
        this.table = table;
        this.log = log;
    }

    @Override
    public WorkChange<Item, SeedTable.Entry> read(ResourceLocation file, JsonObject json) throws JsonParseException {
        WorkChange.Kind action = WorkFiles.action(json);
        Item seed = item(json.get("seed"), "seed");
        if (this.table.isTowntopias(seed)) {
            throw new JsonParseException(this.items.id(seed) + " is one of Towntopia's own seeds; they stay Towntopia's");
        }
        String owner = "data file " + file;
        for (String k : new TreeSet<>(json.keySet())) {
            if (!KEYS.contains(k)) {
                this.log.once("seed-unknown-key|" + file + "|" + k, "[" + SeedTable.KIND + "] " + owner + ": unknown key \"" + k
                    + "\" ignored (the keys are " + new TreeSet<>(KEYS) + ")");
            }
        }
        return switch (action) {
            case ADD -> WorkChange.add(owner, seed, new SeedTable.Entry(seed, item(json.get("produce"), "produce"),
                count(json.get("seeds_per_produce"))));
            case CHANGE -> {
                boolean hasProduce = json.has("produce");
                boolean hasCount = json.has("seeds_per_produce");
                if (!hasProduce && !hasCount) throw new JsonParseException("the change sets nothing");
                Item produce = hasProduce ? item(json.get("produce"), "produce") : null;
                int per = hasCount ? count(json.get("seeds_per_produce")) : 0;
                yield WorkChange.change(owner, seed, e -> new SeedTable.Entry(seed, produce != null ? produce : e.produce(),
                    hasCount ? per : e.seedsPerProduce()));
            }
            case REMOVE -> WorkChange.remove(owner, seed);
        };
    }

    private Item item(@Nullable JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("missing or bad \"" + what + "\" (an item id)");
        }
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

    private static int count(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("\"seeds_per_produce\" must be a whole number of at least 1");
        }
        int n;
        try {
            n = e.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            throw new JsonParseException("\"seeds_per_produce\" must be a whole number: " + e);
        }
        if (n < 1) throw new JsonParseException("\"seeds_per_produce\" must be at least 1, not " + n);
        return n;
    }
}
