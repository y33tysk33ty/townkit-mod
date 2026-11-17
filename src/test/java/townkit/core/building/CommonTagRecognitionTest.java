package townkit.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommonTagRecognitionTest {
    private static final Map<String, List<String>> MEMBERS = Map.of(
            "c:barrels", List.of("minecraft:barrel", "mod:big_barrel"),
            "c:chests/wooden", List.of("minecraft:chest", "mod:oak_chest"));
    private static final Map<String, String> TAGS = Map.of("c:barrels", "minecraft:barrel",
            "c:chests/wooden", "minecraft:chest");

    @Test
    void vanillaMembersSkipped() {
        Map<String, String> e = CommonTagRecognition.entries(TAGS, t -> MEMBERS.getOrDefault(t, List.of()),
                b -> b.startsWith("minecraft:"));
        assertEquals(Set.of("mod:big_barrel", "mod:oak_chest"), e.keySet());
    }

    @Test
    void membersMapToTheirVanillaBlock() {
        Map<String, String> e = CommonTagRecognition.entries(TAGS, t -> MEMBERS.getOrDefault(t, List.of()),
                b -> b.startsWith("minecraft:"));
        assertEquals("minecraft:barrel", e.get("mod:big_barrel"));
        assertEquals("minecraft:chest", e.get("mod:oak_chest"));
    }

    @Test
    void confirmedTags() {
        assertEquals("minecraft:chest", CommonTagRecognition.TAGS.get("c:chests/wooden"));
        assertEquals("minecraft:barrel", CommonTagRecognition.TAGS.get("c:barrels"));
        assertEquals("minecraft:bookshelf", CommonTagRecognition.TAGS.get("c:bookshelves"));
        assertEquals("minecraft:crafting_table", CommonTagRecognition.TAGS.get("c:player_workstations/crafting_tables"));
        assertEquals("minecraft:furnace", CommonTagRecognition.TAGS.get("c:player_workstations/furnaces"));
        assertEquals(5, CommonTagRecognition.TAGS.size());
        assertTrue(!CommonTagRecognition.TAGS.containsKey("c:chests"));
    }
}
