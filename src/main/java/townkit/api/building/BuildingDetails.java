package townkit.api.building;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import townkit.api.village.BuildingInfo;

/**
 * A Towntopia building as it was when read: a plain copy, safe to keep. Nothing in it is re-checked; it is what
 * Towntopia last worked out (Towntopia re-checks one building every 100 ticks and whenever blocks near it change).
 *
 * @param info          frame id, type ({@code towntopia:kitchen}...) and door
 * @param valid         Towntopia's own verdict, the one that lights the token and lets villagers use the building:
 *                      the room's shape holds and villagers can reach it. Towntopia 0.1.2 does <b>not</b> make it
 *                      depend on the working blocks: a kitchen without a crafting table is valid (see
 *                      {@code workingBlocks} for what it has)
 * @param problem       when not valid, Towntopia's reason as a lowercase id such as {@code room_unreachable}; empty
 *                      when the building is valid or Towntopia names no reason (a room whose shape broke)
 * @param roomTiles     the tiles villagers stand on in the room (feet positions)
 * @param workingBlocks the blocks Towntopia counted for the building, grouped by the vanilla block each counts as:
 *                      its own id for a vanilla block, the vanilla block's id for a stand-in (a modded table under
 *                      {@code minecraft:crafting_table}), its own id for a modded block that counts on its own (a
 *                      furnace subclass). Positions whose block is gone since Towntopia's last scan, or whose chunk
 *                      isn't loaded, are left out
 * @since 0.5.0
 */
public record BuildingDetails(BuildingInfo info, boolean valid, Optional<String> problem, Set<BlockPos> roomTiles,
        Map<ResourceLocation, List<BlockPos>> workingBlocks) {
    public BuildingDetails {
        roomTiles = Set.copyOf(roomTiles);
        Map<ResourceLocation, List<BlockPos>> copy = new LinkedHashMap<>();
        workingBlocks.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        workingBlocks = java.util.Collections.unmodifiableMap(copy);
    }
}
