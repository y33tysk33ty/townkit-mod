package townkit.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.nav.TransportStop;

/** The stop store's bookkeeping (no level: the store is made directly). */
class StopStoreTest {
    private static final ResourceLocation KIND = ResourceLocation.fromNamespaceAndPath("townkit_test", "hop");
    private static final UUID A = new UUID(1, 1);
    private static final UUID B = new UUID(2, 2);
    private static final UUID FRAME = new UUID(9, 9);

    private static TransportStop stop(UUID village) {
        return new TransportStop(KIND, village, new BlockPos(0, 64, 0), new BlockPos(0, 64, 0));
    }

    /** A frame moved between villages reports both villages, the old one first. */
    @Test
    void putReportsEveryVillageItChanged() {
        StopStore store = new StopStore();
        assertEquals(List.of(A), store.put(FRAME, stop(A)));
        assertTrue(store.put(FRAME, stop(A)).isEmpty(), "the same stop again changes nothing");
        assertEquals(List.of(A, B), store.put(FRAME, stop(B)));
        assertEquals(java.util.Set.of(B), store.villages());
        assertEquals(stop(B), store.get(FRAME));
    }
}
