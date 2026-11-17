package townkit.core.village;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import townkit.api.spi.TownKitVillagesView;
import townkit.api.village.Village;
import townkit.api.village.Villager;

/** The read API over Towntopia's VillageManagerRegistry, handing out handles. */
public final class CoreVillages implements TownKitVillagesView {
    @Override
    public Collection<Village> all(MinecraftServer server) {
        List<Village> out = new ArrayList<>();
        for (VillageManager m : VillageManagerRegistry.get(server).all()) {
            VillageHandle h = VillageHandle.of(m);
            if (h != null) out.add(h);
        }
        return out;
    }

    @Override
    public Optional<Village> byId(MinecraftServer server, UUID id) {
        VillageManager m = VillageManagerRegistry.get(server).get(id);
        return m == null ? Optional.empty() : Optional.ofNullable(VillageHandle.of(m));
    }

    @Override
    public Optional<Village> at(ServerLevel level, BlockPos pos) {
        for (VillageManager m : VillageManagerRegistry.get(level.getServer()).all()) {
            if (m.getLevel() == level && m.contains(pos)) return Optional.ofNullable(VillageHandle.of(m));
        }
        return Optional.empty();
    }

    @Override
    public Optional<Villager> of(Entity entity) {
        return entity instanceof NpcEntity npc ? Optional.of(VillagerHandle.of(npc)) : Optional.empty();
    }
}
