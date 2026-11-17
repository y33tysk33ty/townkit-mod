package townkit.core.building;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import townkit.api.building.BuildingDetails;

/** Plain-text report for {@code /townkit building}. Pure: the command collects the inputs. */
public final class BuildingReport {
    /** Maximum number of lines {@link #lines} returns. */
    public static final int MAX_LINES = 32;
    /** Label for a working-block position whose block is no longer what Towntopia counted. */
    public static final String CHANGED = "changed since Towntopia's last scan";

    private BuildingReport() {
    }

    /**
     * @param d          the building
     * @param uncounted  nearby blocks that did not count: position to "{@code <block id>: <reason>}"
     * @param countedVia per working-block position: null for a block that counts as itself, else what it counts through
     *                   ("via mod:table (rule by pack)", "via mod:table (common tag)", or {@link #CHANGED}...)
     */
    public static List<String> lines(BuildingDetails d, Map<BlockPos, String> uncounted,
            Function<BlockPos, String> countedVia) {
        List<String> out = new ArrayList<>();
        out.add(d.info().type() + ": " + (d.valid() ? "valid"
                : "invalid" + d.problem().map(p -> " (" + p + ")").orElse("")));
        if (d.workingBlocks().isEmpty()) {
            out.add("no working blocks counted");
        } else {
            out.add("counted:");
            for (Map.Entry<ResourceLocation, List<BlockPos>> e : d.workingBlocks().entrySet()) {
                StringBuilder sb = new StringBuilder("  ").append(e.getKey()).append(" x").append(e.getValue().size());
                Map<String, Integer> via = new LinkedHashMap<>();
                for (BlockPos p : e.getValue()) {
                    String v = countedVia.apply(p);
                    if (v != null) {
                        via.merge(v, 1, Integer::sum);
                    }
                }
                String sep = ": ";
                for (Map.Entry<String, Integer> v : via.entrySet()) {
                    sb.append(sep).append(v.getValue()).append(' ').append(v.getKey());
                    sep = ", ";
                }
                out.add(sb.toString());
            }
        }
        if (!uncounted.isEmpty()) {
            out.add("didn't count:");
            Map<String, Integer> grouped = new LinkedHashMap<>();
            uncounted.values().forEach(t -> grouped.merge(t, 1, Integer::sum));
            for (Map.Entry<String, Integer> g : grouped.entrySet()) {
                out.add("  " + g.getKey() + (g.getValue() > 1 ? " x" + g.getValue() : ""));
            }
        }
        // Towntopia 0.1.2 has no "missing requirement" data (its validity ignores working blocks), so no such section.
        if (out.size() > MAX_LINES) {
            int hidden = out.size() - (MAX_LINES - 1);
            out = new ArrayList<>(out.subList(0, MAX_LINES - 1));
            out.add("... " + hidden + " more lines not shown");
        }
        return out;
    }

    /** Towntopia's site owner class for a building type: {@code towntopia:merchant_stall} to {@code village/building/MerchantStallBuilding}. */
    static String siteOwner(String type) {
        String path = type.substring(type.indexOf(':') + 1);
        StringBuilder sb = new StringBuilder("village/building/");
        for (String part : path.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.append("Building").toString();
    }

    /**
     * Why a modded block near the room did not count.
     *
     * @param sites  its stand-in rule's sites, or null when it has no rule
     * @param inRoom the block sits in the room (on or above a tile villagers stand on), not just next to it
     */
    public static String reason(String buildingType, @Nullable StandInSites sites, boolean inRoom) {
        if (sites == null) {
            return "no rule";
        }
        String owner = siteOwner(buildingType) + "#";
        if (sites.countsAt().stream().anyMatch(k -> k.startsWith(owner) || !isBuildingClass(k))) {
            return inRoom ? "not counted yet: Towntopia hasn't re-scanned" : "not in this room";
        }
        for (Map.Entry<String, String> e : sites.skippedAt().entrySet()) {
            if (e.getKey().startsWith(owner)) {
                return "rule skips this building: " + e.getValue();
            }
        }
        return "rule skips this building: rule counts only at other buildings' sites";
    }

    /** A site in some building's own class ({@code village/building/XBuilding#...}); shared helpers (storage, beds, rooms) are not. */
    private static boolean isBuildingClass(String siteKey) {
        int hash = siteKey.indexOf('#');
        return (hash < 0 ? siteKey : siteKey.substring(0, hash)).endsWith("Building");
    }
}
