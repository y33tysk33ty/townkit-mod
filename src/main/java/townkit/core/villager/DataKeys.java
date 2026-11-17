package townkit.core.villager;

import com.mojang.serialization.Codec;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import townkit.api.villager.VillagerDataKey;
import townkit.core.TownKit;

/** The registered villager data keys, and their encoding to and from NBT. */
public final class DataKeys {
    private record Key<T>(ResourceLocation id, Codec<T> codec) implements VillagerDataKey<T> {
    }

    private static final Map<ResourceLocation, VillagerDataKey<?>> KEYS = new ConcurrentHashMap<>();
    /** Keys whose saved data failed to decode, warned about once each. */
    private static final Map<ResourceLocation, Boolean> WARNED = new ConcurrentHashMap<>();

    private DataKeys() {
    }

    public static <T> VillagerDataKey<T> register(ResourceLocation id, Codec<T> codec) {
        Key<T> key = new Key<>(id, codec);
        if (KEYS.putIfAbsent(id, key) != null) throw new IllegalArgumentException("Villager data key " + id + " is already registered");
        return key;
    }

    public static boolean any() {
        return !KEYS.isEmpty();
    }

    public static <T> Optional<Tag> encode(VillagerDataKey<T> key, T value) {
        return key.codec().encodeStart(NbtOps.INSTANCE, value).result();
    }

    /** Empty for no data, and for data the key can't decode (logged once per key). */
    public static <T> Optional<T> decode(VillagerDataKey<T> key, @Nullable Tag tag) {
        if (tag == null) return Optional.empty();
        var result = key.codec().parse(NbtOps.INSTANCE, tag);
        if (result.error().isPresent() && WARNED.putIfAbsent(key.id(), true) == null) {
            TownKit.LOG.warn("[TownKit] saved villager data for {} can't be read ({}); it reads as empty and is kept as it was",
                key.id(), result.error().get().message());
        }
        return result.result();
    }
}
