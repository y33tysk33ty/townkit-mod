package townkit.core;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import townkit.api.building.BuildingDetails;
import townkit.api.building.StandInReport;
import townkit.api.building.TownKitBuildings;
import townkit.core.building.BuildingReport;
import townkit.core.building.StandInSites;
import townkit.core.building.StandIns;

/** Collects what {@code /townkit building} shows from the world; the text itself comes from {@link BuildingReport}. */
final class BuildingCommand {
    private BuildingCommand() {
    }

    static List<String> report(ServerLevel level, BuildingDetails d) {
        String type = d.info().type().toString();
        Set<BlockPos> counted = new HashSet<>();
        d.workingBlocks().values().forEach(counted::addAll);
        Map<BlockPos, String> uncounted = new LinkedHashMap<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos tile : d.roomTiles()) {
            probe(level, type, tile, true, counted, seen, uncounted);
            probe(level, type, tile.above(), true, counted, seen, uncounted);
            for (Direction dir : Direction.values()) {
                probe(level, type, tile.relative(dir), false, counted, seen, uncounted);
                probe(level, type, tile.above().relative(dir), false, counted, seen, uncounted);
            }
        }
        return BuildingReport.lines(d, uncounted, pos -> countedVia(level, d, pos));
    }

    /** Nearby modded blocks that Towntopia did not count (vanilla blocks are left out: floors and walls would drown the list). */
    private static void probe(ServerLevel level, String type, BlockPos pos, boolean inRoom, Set<BlockPos> counted,
            Set<BlockPos> seen, Map<BlockPos, String> out) {
        if (counted.contains(pos) || !level.isLoaded(pos)) {
            return;
        }
        BlockPos key = pos.immutable();
        BlockState state = level.getBlockState(key);
        if (state.isAir()) {
            return;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (id.startsWith("minecraft:")) {
            return;
        }
        // a position seen as "in the room" must not be hidden by an earlier "next to the room" visit
        boolean first = seen.add(key);
        if (!first && !inRoom) {
            return;
        }
        Optional<StandInReport> rule = TownKitBuildings.standIn(state.getBlock());
        StandInSites sites = rule.map(r -> new StandInSites(r.countsAt(), r.skippedAt())).orElse(null);
        out.put(key, id + ": " + BuildingReport.reason(type, sites, inRoom));
    }

    /** null: the block counts as itself; else the stand-in rule behind it, or that the block changed. */
    private static String countedVia(ServerLevel level, BuildingDetails d, BlockPos pos) {
        Block now = level.getBlockState(pos).getBlock();
        String nowId = BuiltInRegistries.BLOCK.getKey(now).toString();
        for (Map.Entry<net.minecraft.resources.ResourceLocation, List<BlockPos>> e : d.workingBlocks().entrySet()) {
            if (!e.getValue().contains(pos)) {
                continue;
            }
            if (e.getKey().toString().equals(nowId)) {
                return null;
            }
            Optional<StandInReport> rule = TownKitBuildings.standIn(now);
            if (rule.isPresent() && BuiltInRegistries.BLOCK.getKey(rule.get().vanilla()).equals(e.getKey())) {
                String owner = rule.get().fromCommonTag() ? null : StandIns.table().ownerOf(now);
                return "via " + nowId + (owner != null ? " (rule by " + owner + ")" : " (common tag)");
            }
            return BuildingReport.CHANGED + " (now " + nowId + ")";
        }
        return null;
    }
}
