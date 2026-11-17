package townkit.core.look;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.skin.SkinRef;

class VillagerLookTest {
    private static ResourceLocation rl(String s) {
        return ResourceLocation.parse(s);
    }

    private static VillagerLook roundTrip(VillagerLook look) {
        ByteBuf buf = Unpooled.buffer();
        VillagerLook.STREAM_CODEC.encode(buf, look);
        VillagerLook back = VillagerLook.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "bytes left over after decoding");
        return back;
    }

    @Test
    void roundTrip() {
        VillagerLook full = new VillagerLook(rl("myaddon:coin"), true, 80, SkinRef.towntopia("merchant"), rl("myaddon:wave"), 3,
            true, Map.of(rl("towntopia:walk"), rl("myaddon:march"), rl("towntopia:idle"), rl("myaddon:lean")));
        assertEquals(full, roundTrip(full));
        VillagerLook partial = VillagerLook.EMPTY.withSkin(SkinRef.of(rl("myaddon:glen")));
        assertEquals(partial, roundTrip(partial));
    }

    @Test
    void emptyIsEmpty() {
        VillagerLook e = VillagerLook.EMPTY;
        assertTrue(e.isEmpty());
        assertNull(e.bubble());
        assertFalse(e.speech());
        assertEquals(0, e.bubbleTicks());
        assertNull(e.skin());
        assertNull(e.move());
        assertEquals(0, e.moveSeq());
        assertEquals(Map.of(), e.moveSwaps());
        assertEquals(e, roundTrip(e));
        assertTrue(roundTrip(e).isEmpty());
        // a look that sets anything is not empty, and clearing it again gives an empty look
        VillagerLook bubble = e.withBubble(rl("myaddon:coin"), false, 40);
        assertFalse(bubble.isEmpty());
        assertTrue(bubble.withBubble(null, false, 0).isEmpty());
        // a move that ends (move null) resets its sequence and once flag, so the look can become empty again
        VillagerLook move = e.withMove(rl("myaddon:wave"), 7, true);
        assertFalse(move.isEmpty());
        assertTrue(move.moveOnce());
        assertEquals(move, roundTrip(move));
        assertTrue(move.withMove(null, 7, true).isEmpty());
    }

    @Test
    void moveSwapsAreCopied() {
        Map<ResourceLocation, ResourceLocation> swaps = new HashMap<>();
        swaps.put(rl("towntopia:walk"), rl("myaddon:march"));
        VillagerLook look = VillagerLook.EMPTY.withMoveSwaps(swaps);
        swaps.clear();
        assertEquals(Map.of(rl("towntopia:walk"), rl("myaddon:march")), look.moveSwaps());
        assertThrows(UnsupportedOperationException.class, () -> look.moveSwaps().clear());
    }

    @Test
    void negativeBubbleTicksRejected() {
        assertThrows(IllegalArgumentException.class, () -> VillagerLook.EMPTY.withBubble(rl("myaddon:coin"), false, -1));
    }
}
