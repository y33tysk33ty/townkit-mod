package townkit.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import townkit.api.spi.TownKitBackend;
import townkit.api.village.Village;
import townkit.api.village.Villager;

/**
 * Read access to Towntopia's villages and villagers, as TownKit handles. Server thread.
 *
 * @since 0.3.0
 */
public final class TownKitVillages {
    private TownKitVillages() {
    }

    public static Collection<Village> all(MinecraftServer server) {
        return TownKitBackend.get().villages().all(server);
    }

    public static Optional<Village> byId(MinecraftServer server, UUID villageId) {
        return TownKitBackend.get().villages().byId(server, villageId);
    }

    /** The village whose border contains {@code pos} in this level. */
    public static Optional<Village> at(ServerLevel level, BlockPos pos) {
        return TownKitBackend.get().villages().at(level, pos);
    }

    /** The villager this entity is; empty for anything that is not a Towntopia villager. */
    public static Optional<Villager> of(Entity entity) {
        return TownKitBackend.get().villages().of(entity);
    }
}
