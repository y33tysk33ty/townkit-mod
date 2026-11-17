package townkit.api.building;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import townkit.api.Subscription;
import townkit.api.village.Village;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitBuildingsView;

/**
 * Making modded blocks count toward Towntopia's building requirements.
 *
 * <p>A stand-in rule ({@link #countsAs}) makes a modded block pass the checks Towntopia makes for a vanilla block, at
 * each audited check site where that is safe: SAFE sites always, sites that need a behaviour (a real inventory, a real
 * bed) only if the block has it, unsafe sites (where Towntopia would write the block's state or cast it) never. For
 * checks on a class ({@code instanceof AbstractFurnaceBlock}, {@code CampfireBlock}, {@code AnvilBlock}...), pass the
 * vanilla block that class stands for ({@code Blocks.FURNACE}, {@code Blocks.CAMPFIRE}, {@code Blocks.ANVIL}).
 * Blocks built on the vanilla classes (a furnace extending {@code AbstractFurnaceBlock}, a bed extending
 * {@code BedBlock}) already pass and need no rule.
 *
 * <p>Register at mod setup; server and client both fine. With no rule and no {@link #useCommonTags} subscription,
 * Towntopia's checks cost what they did before. Where each block counts is logged at registration and readable with
 * {@link #standIn}.
 *
 * @since 0.5.0
 */
public final class TownKitBuildings {
    private TownKitBuildings() {
    }

    private static TownKitBuildingsView view() {
        return TownKitBackend.get().buildings();
    }

    /**
     * Let {@code modded} count wherever Towntopia checks for {@code vanilla}, at the sites where that is safe. Returns a
     * disabled subscription (with a log line saying why) when it can count nowhere, e.g. a block that isn't a
     * {@code BedBlock} as a bed, or when another rule already makes {@code modded} stand in for a different block.
     * {@code owner} names the addon in logs. Throws IllegalArgumentException if {@code modded == vanilla} or
     * {@code owner} is blank.
     */
    public static Subscription countsAs(String owner, Block modded, Block vanilla) {
        return view().countsAs(owner, modded, vanilla);
    }

    /**
     * Switch on recognition of shared {@code c:} tags (wooden chests, barrels, bookshelves, crafting tables, furnaces;
     * see TownKit's compat notes for the exact list): non-vanilla members count as the matching vanilla block, with the
     * same per-site safety as {@link #countsAs}. Off by default; it stays on while at least one subscription is live
     * and follows tag reloads. An explicit rule wins over a tag.
     */
    public static Subscription useCommonTags(String owner) {
        return view().useCommonTags(owner);
    }

    /** Where {@code modded} stands in and where it doesn't; empty when it stands in for nothing. */
    public static Optional<StandInReport> standIn(Block modded) {
        return view().standIn(modded);
    }

    // --- reading buildings (server thread only) ------------------------------------------------------------------

    /**
     * The building whose room holds {@code pos}: a tile villagers stand on in it, the block above such a tile, or one
     * of its working blocks. Empty when no Towntopia building in {@code level} has it. Reads only: never makes
     * Towntopia scan or re-check anything. Server thread only (throws IllegalStateException elsewhere).
     *
     * @since 0.5.0
     */
    public static Optional<BuildingDetails> at(ServerLevel level, BlockPos pos) {
        return view().at(level, pos);
    }

    /**
     * The village's buildings of {@code type} ({@code towntopia:kitchen}, {@code towntopia:forge}...), empty for an
     * unknown type or a village that's gone. Reads only. Server thread only (throws IllegalStateException elsewhere).
     *
     * @since 0.5.0
     */
    public static List<BuildingDetails> ofType(Village village, ResourceLocation type) {
        return view().ofType(village, type);
    }

    /**
     * The village's building with token frame {@code frameId} (as in {@link townkit.api.village.BuildingInfo}), empty
     * when there is none. Reads only. Server thread only (throws IllegalStateException elsewhere).
     *
     * @since 0.5.0
     */
    public static Optional<BuildingDetails> details(Village village, UUID frameId) {
        return view().details(village, frameId);
    }
}
