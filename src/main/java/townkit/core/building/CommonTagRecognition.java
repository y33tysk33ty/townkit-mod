package townkit.core.building;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** Which shared {@code c:} tags make a modded block a stand-in for a vanilla one (tags confirmed in the audit). */
public final class CommonTagRecognition {
    /** Tag id to the id of the vanilla block its modded members stand in for. */
    public static final Map<String, String> TAGS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("c:chests/wooden", "minecraft:chest");
        m.put("c:barrels", "minecraft:barrel");
        m.put("c:bookshelves", "minecraft:bookshelf");
        m.put("c:player_workstations/crafting_tables", "minecraft:crafting_table");
        m.put("c:player_workstations/furnaces", "minecraft:furnace");
        TAGS = java.util.Collections.unmodifiableMap(m);
    }

    private CommonTagRecognition() {
    }

    /**
     * Every non-vanilla member of each tag mapped to that tag's vanilla block. A block in several tags keeps the
     * first tag's mapping (iteration order of {@code tagToVanilla}).
     */
    public static <B> Map<B, B> entries(Map<String, B> tagToVanilla, Function<String, Iterable<B>> members,
            Predicate<B> isVanilla) {
        Map<B, B> out = new LinkedHashMap<>();
        for (Map.Entry<String, B> tag : tagToVanilla.entrySet()) {
            Iterable<B> list = members.apply(tag.getKey());
            if (list == null) {
                continue;
            }
            for (B member : list) {
                if (!isVanilla.test(member)) {
                    out.putIfAbsent(member, tag.getValue());
                }
            }
        }
        return out;
    }
}
