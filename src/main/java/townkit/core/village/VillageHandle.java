package townkit.core.village;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import townkit.api.village.BuildingInfo;
import townkit.api.village.RaidStage;
import townkit.api.village.Village;
import townkit.api.village.VillageState;
import townkit.api.village.VillageStorage;
import townkit.api.village.Villager;

/** {@link Village} backed by a village id: every call looks the VillageManager up again, so a removed village just reads as gone. */
public final class VillageHandle implements Village {
    private final ServerRef server;
    private final UUID id;
    private final ResourceKey<Level> dimension;

    public VillageHandle(MinecraftServer server, UUID id, ResourceKey<Level> dimension) {
        this.server = new ServerRef(server);
        this.id = id;
        this.dimension = dimension;
    }

    /** Null for a village whose level isn't set up yet (such villages aren't usable). */
    @Nullable
    public static VillageHandle of(VillageManager mgr) {
        if (mgr.getLevel() == null) return null;
        return new VillageHandle(mgr.getLevel().getServer(), mgr.getVillageId(), mgr.getLevel().dimension());
    }

    @Nullable
    public VillageManager manager() {
        MinecraftServer s = this.server.live();
        return s == null ? null : VillageManagerRegistry.get(s).get(this.id);
    }

    @Override
    public UUID id() {
        return this.id;
    }

    @Override
    public ResourceKey<Level> dimension() {
        return this.dimension;
    }

    @Override
    public boolean exists() {
        return this.manager() != null;
    }

    @Override
    public boolean contains(BlockPos pos) {
        VillageManager m = this.manager();
        return m != null && m.contains(pos);
    }

    @Override
    public Collection<Villager> villagers() {
        VillageManager m = this.manager();
        if (m == null) return List.of();
        List<Villager> out = new ArrayList<>();
        for (VillagerModel model : m.getVillagers()) out.add(new VillagerHandle(this.server.live(), this.id, model.npcId, null));
        return out;
    }

    @Override
    public Optional<Villager> villager(UUID id) {
        VillageManager m = this.manager();
        if (m == null || m.getModel(id) == null) return Optional.empty();
        return Optional.of(new VillagerHandle(this.server.live(), this.id, id, null));
    }

    @Override
    public Collection<BuildingInfo> buildings() {
        VillageManager m = this.manager();
        if (m == null) return List.of();
        List<BuildingInfo> out = new ArrayList<>();
        for (Building b : m.getBuildings()) out.add(info(b));
        return out;
    }

    @Override
    public Optional<UUID> owner() {
        VillageManager m = this.manager();
        return m == null ? Optional.empty() : Optional.ofNullable(m.claim().ownerId());
    }

    @Override
    public Optional<String> ownerName() {
        VillageManager m = this.manager();
        if (m == null) return Optional.empty();
        String name = m.claim().ownerName();
        return name == null || name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    @Override
    public VillageState state() {
        MinecraftServer s = this.server.live();
        if (s == null || this.manager() == null) return VillageState.INACTIVE;
        return VillageReads.state(VillageManagerRegistry.get(s), this.id);
    }

    @Override
    public Optional<BlockPos> bellPos() {
        VillageManager m = this.manager();
        if (m == null) return Optional.empty();
        BlockPos bell = m.getBellPos();
        return bell == null || bell.equals(BlockPos.ZERO) ? Optional.empty() : Optional.of(bell.immutable());
    }

    @Override
    public RaidStage raidStage() {
        VillageManager m = this.manager();
        RaidStage stage = m == null ? null : VillageReads.raidStage(m);
        return stage == null ? RaidStage.IDLE : stage;
    }

    @Override
    public boolean raidInProgress() {
        VillageManager m = this.manager();
        return m != null && VillageReads.raidInProgress(m);
    }

    @Override
    public Optional<AABB> border() {
        VillageManager m = this.manager();
        return m == null ? Optional.empty() : Optional.ofNullable(m.borderBounds());
    }

    @Override
    public VillageStorage storage() {
        VillageManager m = this.manager();
        return m == null ? StorageSnapshot.empty() : StorageSnapshot.of(m);
    }

    public static BuildingInfo info(Building b) {
        return new BuildingInfo(b.getItemFrameId(), Ids.building(b.getClass()), b.getDoorPos());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VillageHandle h && h.id.equals(this.id);
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    @Override
    public String toString() {
        return "Village[" + this.id + (this.exists() ? "" : ", gone") + "]";
    }
}
