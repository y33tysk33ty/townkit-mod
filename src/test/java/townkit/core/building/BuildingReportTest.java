package townkit.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.building.BuildingDetails;
import townkit.api.village.BuildingInfo;

class BuildingReportTest {
    private static final BlockPos DOOR = new BlockPos(0, 64, 0);

    private static BuildingDetails kitchen(boolean valid, String problem, Map<ResourceLocation, List<BlockPos>> working) {
        return new BuildingDetails(new BuildingInfo(UUID.randomUUID(), ResourceLocation.parse("towntopia:kitchen"), DOOR),
                valid, Optional.ofNullable(problem), Set.of(DOOR), working);
    }

    private static Map<ResourceLocation, List<BlockPos>> working() {
        Map<ResourceLocation, List<BlockPos>> m = new LinkedHashMap<>();
        m.put(ResourceLocation.parse("minecraft:crafting_table"), List.of(new BlockPos(1, 64, 1)));
        m.put(ResourceLocation.parse("minecraft:furnace"), List.of(new BlockPos(2, 64, 1), new BlockPos(3, 64, 1)));
        return m;
    }

    @Test
    void validKitchen() {
        List<String> l = BuildingReport.lines(kitchen(true, null, working()), Map.of(), p -> null);
        assertEquals("towntopia:kitchen: valid", l.get(0));
        assertTrue(l.contains("  minecraft:crafting_table x1"), l.toString());
        assertTrue(l.contains("  minecraft:furnace x2"), l.toString());
        assertTrue(l.stream().noneMatch(s -> s.startsWith("didn't count")), l.toString());
    }

    @Test
    void invalidWithProblem() {
        List<String> l = BuildingReport.lines(kitchen(false, "room_unreachable", Map.of()), Map.of(), p -> null);
        assertEquals("towntopia:kitchen: invalid (room_unreachable)", l.get(0));
        assertTrue(l.contains("no working blocks counted"), l.toString());
    }

    @Test
    void invalidWithoutReason() {
        assertEquals("towntopia:kitchen: invalid", BuildingReport.lines(kitchen(false, null, Map.of()), Map.of(), p -> null).get(0));
    }

    @Test
    void standInLineNamesModdedBlockAndRuleOwner() {
        BlockPos modded = new BlockPos(1, 64, 1);
        List<String> l = BuildingReport.lines(kitchen(true, null, working()), Map.of(),
                p -> p.equals(modded) ? "via mod:table (rule by pack)" : null);
        assertTrue(l.contains("  minecraft:crafting_table x1: 1 via mod:table (rule by pack)"), l.toString());
        assertTrue(l.contains("  minecraft:furnace x2"), l.toString());
    }

    @Test
    void mixedPlainAndStandInPositions() {
        Map<ResourceLocation, List<BlockPos>> w = new LinkedHashMap<>();
        BlockPos a = new BlockPos(1, 64, 1);
        BlockPos b = new BlockPos(2, 64, 1);
        BlockPos c = new BlockPos(3, 64, 1);
        w.put(ResourceLocation.parse("minecraft:crafting_table"), List.of(a, b, c));
        List<String> l = BuildingReport.lines(kitchen(true, null, w), Map.of(),
                p -> p.equals(a) ? "via mod:table (common tag)" : p.equals(b) ? BuildingReport.CHANGED + " (now minecraft:stone)" : null);
        assertTrue(l.contains("  minecraft:crafting_table x3: 1 via mod:table (common tag), 1 " + BuildingReport.CHANGED
                + " (now minecraft:stone)"), l.toString());
    }

    @Test
    void uncountedReasonsGroupedWithCounts() {
        Map<BlockPos, String> u = new LinkedHashMap<>();
        u.put(new BlockPos(5, 64, 5), "mod:a: no rule");
        u.put(new BlockPos(5, 64, 6), "mod:a: no rule");
        u.put(new BlockPos(5, 64, 7), "mod:b: rule skips this building: fails check holdsItems");
        u.put(new BlockPos(5, 64, 8), "mod:c: not in this room");
        List<String> l = BuildingReport.lines(kitchen(true, null, working()), u, p -> null);
        int i = l.indexOf("didn't count:");
        assertTrue(i > 0, l.toString());
        assertEquals("  mod:a: no rule x2", l.get(i + 1));
        assertEquals("  mod:b: rule skips this building: fails check holdsItems", l.get(i + 2));
        assertEquals("  mod:c: not in this room", l.get(i + 3));
    }

    @Test
    void capsAtThirtyTwoLines() {
        Map<BlockPos, String> u = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            u.put(new BlockPos(i, 64, 5), "mod:b" + i + ": no rule");
        }
        List<String> l = BuildingReport.lines(kitchen(true, null, working()), u, p -> null);
        assertEquals(32, l.size());
        assertTrue(l.get(31).startsWith("... "), l.get(31));
        assertEquals("towntopia:kitchen: valid", l.get(0));
    }

    @Test
    void reasonForUncountedModdedBlock() {
        assertEquals("no rule", BuildingReport.reason("towntopia:kitchen", null, false));
        // rule counts at a kitchen site, block inside the room: waiting for the next scan
        var counts = new StandInSites(List.of("village/building/KitchenBuilding#m#BLOCK_IS#CRAFTING_TABLE"), Map.of());
        assertEquals("not in this room", BuildingReport.reason("towntopia:kitchen", counts, false));
        assertEquals("not counted yet: Towntopia hasn't re-scanned", BuildingReport.reason("towntopia:kitchen", counts, true));
        var skips = new StandInSites(List.of("village/building/ForgeBuilding#m#BLOCK_IS#ANVIL"),
                Map.of("village/building/KitchenBuilding#m#BLOCK_IS#CRAFTING_TABLE", "fails check holdsItems"));
        assertEquals("rule skips this building: fails check holdsItems", BuildingReport.reason("towntopia:kitchen", skips, true));
        var none = new StandInSites(List.of("village/building/ForgeBuilding#m#BLOCK_IS#ANVIL"), Map.of());
        assertEquals("rule skips this building: rule counts only at other buildings' sites",
                BuildingReport.reason("towntopia:kitchen", none, true));
    }

    @Test
    void sharedSiteCountsForEveryBuilding() {
        var chest = new StandInSites(List.of("village/building/StorageContainers#m#INSTANCEOF#net/minecraft/world/level/block/ChestBlock"),
                Map.of());
        assertEquals("not counted yet: Towntopia hasn't re-scanned", BuildingReport.reason("towntopia:storage", chest, true));
        assertEquals("not in this room", BuildingReport.reason("towntopia:storage", chest, false));
    }

    @Test
    void siteOwnerFromTypePath() {
        assertEquals("village/building/MerchantStallBuilding", BuildingReport.siteOwner("towntopia:merchant_stall"));
        assertEquals("village/building/KitchenBuilding", BuildingReport.siteOwner("towntopia:kitchen"));
    }
}
