package townkit.api.spi;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.village.Village;
import townkit.api.village.Villager;

/**
 * What {@link townkit.api.TownKitVillages} asks TownKit's backend for. Addons use TownKitVillages instead.
 */
@ApiStatus.Internal
public interface TownKitVillagesView {
    Collection<Village> all(MinecraftServer server);

    Optional<Village> byId(MinecraftServer server, UUID id);

    Optional<Village> at(ServerLevel level, BlockPos pos);

    Optional<Villager> of(Entity entity);
}
