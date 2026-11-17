package townkit.api.spi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;
import townkit.api.building.BuildingDetails;
import townkit.api.building.StandInReport;
import townkit.api.village.Village;

/** What {@link townkit.api.building.TownKitBuildings} asks TownKit's backend for. Addons use TownKitBuildings instead. */
@ApiStatus.Internal
public interface TownKitBuildingsView {
    Subscription countsAs(String owner, Block modded, Block vanilla);

    Subscription useCommonTags(String owner);

    Optional<StandInReport> standIn(Block modded);

    Optional<BuildingDetails> at(ServerLevel level, BlockPos pos);

    List<BuildingDetails> ofType(Village village, ResourceLocation type);

    Optional<BuildingDetails> details(Village village, UUID frameId);
}
