package townkit.core.shop;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * How many of each addon shop row each village bought from its Mayor (the "owned" count of an addon row's price
 * growth), saved with the world in TownKit's own file on the overworld, keyed by village id. Counts of rows whose addon
 * isn't loaded right now are kept. Server thread only.
 */
public final class ShopCounts extends SavedData {
    public static final String NAME = "townkit_shop";
    private static final SavedData.Factory<ShopCounts> FACTORY = new SavedData.Factory<>(ShopCounts::new, ShopCounts::load, null);

    private final Map<UUID, Map<ResourceLocation, Integer>> bought = new HashMap<>();

    public static ShopCounts of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public int count(UUID village, ResourceLocation entry) {
        Map<ResourceLocation, Integer> m = this.bought.get(village);
        return m == null ? 0 : m.getOrDefault(entry, 0);
    }

    public void increment(UUID village, ResourceLocation entry) {
        this.bought.computeIfAbsent(village, k -> new HashMap<>()).merge(entry, 1, Integer::sum);
        this.setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        CompoundTag villages = new CompoundTag();
        this.bought.forEach((village, counts) -> {
            CompoundTag v = new CompoundTag();
            counts.forEach((entry, n) -> v.putInt(entry.toString(), n));
            villages.put(village.toString(), v);
        });
        tag.put("villages", villages);
        return tag;
    }

    public static ShopCounts load(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        ShopCounts counts = new ShopCounts();
        CompoundTag villages = tag.getCompound("villages");
        for (String key : villages.getAllKeys()) {
            UUID village;
            try {
                village = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            CompoundTag v = villages.getCompound(key);
            Map<ResourceLocation, Integer> m = new HashMap<>();
            for (String entry : v.getAllKeys()) {
                ResourceLocation id = ResourceLocation.tryParse(entry);
                if (id != null) m.put(id, v.getInt(entry));
            }
            if (!m.isEmpty()) counts.bought.put(village, m);
        }
        return counts;
    }
}
