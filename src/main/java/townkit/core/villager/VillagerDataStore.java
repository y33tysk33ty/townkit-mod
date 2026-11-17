package townkit.core.villager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Addon data per villager (by Towntopia's npcId), saved with the world in TownKit's own file, never in Towntopia's.
 * Values stay as NBT until a key reads them, so data for keys that aren't registered right now is kept untouched.
 */
public final class VillagerDataStore extends SavedData {
    public static final String NAME = "townkit_villager_data";
    private static final SavedData.Factory<VillagerDataStore> FACTORY = new SavedData.Factory<>(VillagerDataStore::new, VillagerDataStore::load, null);

    private final Map<UUID, Map<ResourceLocation, Tag>> data = new HashMap<>();

    public static VillagerDataStore of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    @Nullable
    public Tag get(UUID npcId, ResourceLocation key) {
        Map<ResourceLocation, Tag> m = this.data.get(npcId);
        return m == null ? null : m.get(key);
    }

    public void put(UUID npcId, ResourceLocation key, Tag value) {
        this.data.computeIfAbsent(npcId, k -> new HashMap<>()).put(key, value);
        this.setDirty();
    }

    public boolean remove(UUID npcId, ResourceLocation key) {
        Map<ResourceLocation, Tag> m = this.data.get(npcId);
        if (m == null || m.remove(key) == null) return false;
        if (m.isEmpty()) this.data.remove(npcId);
        this.setDirty();
        return true;
    }

    public void drop(UUID npcId) {
        if (this.data.remove(npcId) != null) this.setDirty();
    }

    public boolean isEmpty() {
        return this.data.isEmpty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        CompoundTag villagers = new CompoundTag();
        this.data.forEach((npcId, values) -> {
            CompoundTag v = new CompoundTag();
            values.forEach((key, value) -> v.put(key.toString(), value.copy()));
            villagers.put(npcId.toString(), v);
        });
        tag.put("villagers", villagers);
        return tag;
    }

    public static VillagerDataStore load(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        VillagerDataStore store = new VillagerDataStore();
        CompoundTag villagers = tag.getCompound("villagers");
        for (String npc : villagers.getAllKeys()) {
            UUID npcId;
            try {
                npcId = UUID.fromString(npc);
            } catch (IllegalArgumentException e) {
                continue;
            }
            CompoundTag v = villagers.getCompound(npc);
            Map<ResourceLocation, Tag> values = new HashMap<>();
            for (String key : v.getAllKeys()) {
                ResourceLocation id = ResourceLocation.tryParse(key);
                if (id != null) values.put(id, v.get(key));
            }
            if (!values.isEmpty()) store.data.put(npcId, values);
        }
        return store;
    }
}
