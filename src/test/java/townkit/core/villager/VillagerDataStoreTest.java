package townkit.core.villager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.villager.VillagerDataKey;

class VillagerDataStoreTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("townkit_test", path);
    }

    private static VillagerDataStore reload(VillagerDataStore store) {
        CompoundTag saved = store.save(new CompoundTag(), null);
        return VillagerDataStore.load(saved, null);
    }

    @Test
    void roundTrip() {
        VillagerDataKey<String> key = DataKeys.register(id("round_trip"), Codec.STRING);
        VillagerDataStore store = new VillagerDataStore();
        store.put(A, key.id(), DataKeys.encode(key, "hello").orElseThrow());
        assertEquals(Optional.of("hello"), DataKeys.decode(key, reload(store).get(A, key.id())));
    }

    @Test
    void undecodableEntryIsKeptAndEmpty() {
        VillagerDataKey<String> text = DataKeys.register(id("text"), Codec.STRING);
        VillagerDataKey<String> other = DataKeys.register(id("other"), Codec.STRING);
        VillagerDataStore store = new VillagerDataStore();
        store.put(A, text.id(), IntTag.valueOf(7)); // an addon changed its format
        store.put(A, other.id(), DataKeys.encode(other, "fine").orElseThrow());
        VillagerDataStore loaded = reload(store);
        assertTrue(DataKeys.decode(text, loaded.get(A, text.id())).isEmpty());
        assertEquals(IntTag.valueOf(7), loaded.get(A, text.id()));
        assertEquals(Optional.of("fine"), DataKeys.decode(other, loaded.get(A, other.id())));
    }

    @Test
    void unknownKeyIdKeptOnSave() {
        VillagerDataStore store = new VillagerDataStore();
        store.put(A, id("never_registered"), IntTag.valueOf(3));
        assertEquals(IntTag.valueOf(3), reload(store).get(A, id("never_registered")));
    }

    @Test
    void dropRemovesAll() {
        VillagerDataStore store = new VillagerDataStore();
        store.put(A, id("x"), IntTag.valueOf(1));
        store.put(A, id("y"), IntTag.valueOf(2));
        store.put(B, id("x"), IntTag.valueOf(3));
        store.drop(A);
        assertNull(store.get(A, id("x")));
        assertNull(store.get(A, id("y")));
        assertEquals(IntTag.valueOf(3), store.get(B, id("x")));
    }

    @Test
    void duplicateKeyRejected() {
        DataKeys.register(id("dup"), Codec.STRING);
        var e = assertThrows(IllegalArgumentException.class, () -> DataKeys.register(id("dup"), Codec.INT));
        assertTrue(e.getMessage().contains("townkit_test:dup"));
    }
}
