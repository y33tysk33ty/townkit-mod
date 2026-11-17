package townkit.core.building;

import java.util.List;
import java.util.Map;

/** A stand-in rule's check sites as {@link BuildingReport} needs them: where it counts, and where it is skipped (site key to reason). */
public record StandInSites(List<String> countsAt, Map<String, String> skippedAt) {
}
