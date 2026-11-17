package townkit.api.villager;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import townkit.api.spi.TownKitBackend;

/**
 * A kind of data an addon keeps per villager, saved with the world and readable while the villager is unloaded. Read
 * and write it on a {@link townkit.api.village.Villager} ({@code data}, {@code setData}, {@code removeData}). TownKit
 * drops a villager's data when Towntopia forgets the villager (it died or is gone), right after
 * {@code TownKitEvents.VILLAGER_REMOVED}.
 *
 * <pre>{@code
 * static final VillagerDataKey<String> PET = VillagerDataKey.register(ResourceLocation.parse("myaddon:pet"), Codec.STRING);
 * villager.setData(PET, wolf.getStringUUID());
 * }</pre>
 * Saved data a key can no longer decode (its format changed) reads as empty and is kept as it was.
 *
 * @since 0.4.0
 */
public interface VillagerDataKey<T> {
    ResourceLocation id();

    Codec<T> codec();

    /** Register a key once, e.g. in a static field. A second key with the same id throws IllegalArgumentException. */
    static <T> VillagerDataKey<T> register(ResourceLocation id, Codec<T> codec) {
        return TownKitBackend.get().registerDataKey(id, codec);
    }
}
