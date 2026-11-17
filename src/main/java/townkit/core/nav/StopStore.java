package townkit.core.nav;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import townkit.api.nav.TransportStop;

/**
 * The transport stops of one level (dimension), saved with it: per village id, the stops by their item frame's
 * entity id. Server thread only. Stops of kinds that aren't live are kept here (a mod may enable its kind later in the
 * session) but never read as stops: {@link #stops} filters them out, and the marker reconcile drops one whose frame
 * no longer holds a live marker.
 */
public final class StopStore extends SavedData {
    static final String NAME = "townkit_transport_stops";
    private static final SavedData.Factory<StopStore> FACTORY = new SavedData.Factory<>(StopStore::new, StopStore::load,
            null);

    private final Map<UUID, Map<UUID, TransportStop>> byVillage = new LinkedHashMap<>();

    /** Package-private for tests; the game gets its store from {@link #of}. */
    StopStore() {
    }

    public static StopStore of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /**
     * Makes {@code stop} the stop of frame {@code frameId} (moving it out of another village). Returns the villages
     * whose stops changed: empty when nothing did; the old village first, then the new one, when the frame moved.
     */
    List<UUID> put(UUID frameId, TransportStop stop) {
        UUID moved = null;
        for (Map.Entry<UUID, Map<UUID, TransportStop>> e : byVillage.entrySet()) {
            if (!e.getKey().equals(stop.villageId()) && e.getValue().containsKey(frameId)) {
                moved = e.getKey();
                remove(frameId);
                break;
            }
        }
        TransportStop had = byVillage.computeIfAbsent(stop.villageId(), k -> new LinkedHashMap<>()).put(frameId, stop);
        if (stop.equals(had)) {
            return List.of();
        }
        setDirty();
        return moved == null ? List.of(stop.villageId()) : List.of(moved, stop.villageId());
    }

    /** Removes frame {@code frameId}'s stop; returns it, or null when it had none. */
    @Nullable
    TransportStop remove(UUID frameId) {
        for (var it = byVillage.values().iterator(); it.hasNext();) {
            Map<UUID, TransportStop> stops = it.next();
            TransportStop had = stops.remove(frameId);
            if (had != null) {
                if (stops.isEmpty()) {
                    it.remove();
                }
                setDirty();
                return had;
            }
        }
        return null;
    }

    @Nullable
    TransportStop get(UUID frameId) {
        for (Map<UUID, TransportStop> stops : byVillage.values()) {
            TransportStop s = stops.get(frameId);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** The live-kind stops of village {@code villageId}, in the order they were made. */
    public List<TransportStop> stops(UUID villageId) {
        Map<UUID, TransportStop> stops = byVillage.get(villageId);
        if (stops == null) {
            return List.of();
        }
        List<TransportStop> out = new ArrayList<>(stops.size());
        for (TransportStop s : stops.values()) {
            if (TransportKinds.GLOBAL.live(s.kind()) != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** Every frame id with a stop (any kind), with its stop: a copy. */
    Map<UUID, TransportStop> all() {
        Map<UUID, TransportStop> out = new LinkedHashMap<>();
        byVillage.values().forEach(out::putAll);
        return out;
    }

    Set<UUID> villages() {
        return Set.copyOf(byVillage.keySet());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        byVillage.values().forEach(stops -> stops.forEach((frame, s) -> {
            CompoundTag t = new CompoundTag();
            t.putUUID("frame", frame);
            t.putUUID("village", s.villageId());
            t.putString("kind", s.kind().toString());
            t.putLong("framePos", s.framePos().asLong());
            t.putLong("tile", s.tile().asLong());
            list.add(t);
        }));
        tag.put("stops", list);
        return tag;
    }

    private static StopStore load(CompoundTag tag, HolderLookup.Provider registries) {
        StopStore store = new StopStore();
        for (Tag raw : tag.getList("stops", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            ResourceLocation kind = ResourceLocation.tryParse(t.getString("kind"));
            if (kind == null || !t.hasUUID("frame") || !t.hasUUID("village")) {
                continue;
            }
            TransportStop s = new TransportStop(kind, t.getUUID("village"), BlockPos.of(t.getLong("framePos")),
                    BlockPos.of(t.getLong("tile")));
            store.byVillage.computeIfAbsent(s.villageId(), k -> new LinkedHashMap<>()).put(t.getUUID("frame"), s);
        }
        return store;
    }
}
