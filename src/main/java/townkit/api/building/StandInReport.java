package townkit.api.building;

import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;

/**
 * Where a modded block stands in for a vanilla one, and where it doesn't. A plain copy, safe to keep.
 *
 * <p>Sites are Towntopia's audited block checks, named {@code owner#method#kind#target} (see TownKit's
 * {@code dev-docs/buildings.md}). Only sites whose check is about {@code vanilla} are listed.
 *
 * @param modded        the modded block
 * @param vanilla       the vanilla block it stands in for
 * @param fromCommonTag true when it came from shared {@code c:} tag recognition rather than a {@code countsAs} rule
 * @param countsAt      the sites where it counts
 * @param skippedAt     the sites where it doesn't, each with the reason (an unsafe site, a failed behaviour check)
 * @since 0.5.0
 */
public record StandInReport(Block modded, Block vanilla, boolean fromCommonTag, List<String> countsAt,
        Map<String, String> skippedAt) {
    public StandInReport {
        countsAt = List.copyOf(countsAt);
        skippedAt = Map.copyOf(skippedAt);
    }
}
