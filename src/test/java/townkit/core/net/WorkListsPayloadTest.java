package townkit.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** The work lists payload's layout, and what a client does with a payload no honest server sends. */
class WorkListsPayloadTest {
    private static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static WorkListsPayload.Row row(String id) {
        return new WorkListsPayload.Row(id, "Label", "Needs 1 Wheat.", 16);
    }

    @Test
    void roundTrip() {
        WorkListsPayload sent = new WorkListsPayload(7, Optional.of(List.of(
            new WorkListsPayload.Row("minecraft:bread", "Bread", "Needs 3 wheat. Made at a crafting table.\nOFF: never makes it.", 64),
            new WorkListsPayload.Row("mymod:stew", "Stew", "Needs 1 Wheat. Made at a crafting table.\nOFF: never makes it.", 16))),
            Optional.empty());
        RegistryFriendlyByteBuf b = buf();
        WorkListsPayload.STREAM_CODEC.encode(b, sent);
        assertEquals(sent, WorkListsPayload.STREAM_CODEC.decode(b));
        assertFalse(b.isReadable(), "nothing left over");

        WorkListsPayload none = new WorkListsPayload(7, Optional.empty(), Optional.empty());
        RegistryFriendlyByteBuf e = buf();
        WorkListsPayload.STREAM_CODEC.encode(e, none);
        assertEquals(none, WorkListsPayload.STREAM_CODEC.decode(e));
        // an empty list (every dish removed) is not "Towntopia's own list"
        WorkListsPayload empty = new WorkListsPayload(7, Optional.of(List.of()), Optional.of(List.of()));
        RegistryFriendlyByteBuf e2 = buf();
        WorkListsPayload.STREAM_CODEC.encode(e2, empty);
        assertEquals(empty, WorkListsPayload.STREAM_CODEC.decode(e2));
        assertTrue(sent.valid() && none.valid() && empty.valid());
        assertFalse(new WorkListsPayload(0, Optional.empty(), Optional.empty()).valid(), "version 0 is never sent");
    }

    @Test
    void answerAndWindowRoundTrip() {
        RegistryFriendlyByteBuf b = buf();
        WorkListsAppliedPayload no = new WorkListsAppliedPayload(12, false);
        WorkListsAppliedPayload.STREAM_CODEC.encode(b, no);
        assertEquals(no, WorkListsAppliedPayload.STREAM_CODEC.decode(b));
        assertFalse(b.isReadable());
        WorkWindowPayload w = new WorkWindowPayload(12);
        WorkWindowPayload.STREAM_CODEC.encode(b, w);
        assertEquals(w, WorkWindowPayload.STREAM_CODEC.decode(b));
        assertFalse(b.isReadable());
        // a cut-off answer fails decoding (the connection reports it), it is never applied
        RegistryFriendlyByteBuf cut = buf();
        cut.writeVarInt(12);
        assertThrows(RuntimeException.class, () -> WorkListsAppliedPayload.STREAM_CODEC.decode(cut));
    }

    @Test
    void oversizedOrMalformedIsRefused() {
        // more rows than a payload may carry: decoding stops at the count, before reading (or allocating) any row
        RegistryFriendlyByteBuf many = buf();
        many.writeVarInt(1);
        many.writeBoolean(true);
        many.writeVarInt(WorkListsPayload.MAX_ROWS + 1);
        assertThrows(RuntimeException.class, () -> WorkListsPayload.STREAM_CODEC.decode(many));
        // a negative count
        RegistryFriendlyByteBuf negative = buf();
        negative.writeVarInt(1);
        negative.writeBoolean(true);
        negative.writeVarInt(-1);
        assertThrows(RuntimeException.class, () -> WorkListsPayload.STREAM_CODEC.decode(negative));
        // a cut-off payload
        RegistryFriendlyByteBuf cut = buf();
        cut.writeVarInt(1);
        cut.writeBoolean(true);
        cut.writeVarInt(2);
        cut.writeUtf("minecraft:bread");
        assertThrows(RuntimeException.class, () -> WorkListsPayload.STREAM_CODEC.decode(cut));
        // a text longer than its cap
        RegistryFriendlyByteBuf longId = buf();
        longId.writeVarInt(1);
        longId.writeBoolean(true);
        longId.writeVarInt(1);
        longId.writeUtf("x".repeat(WorkListsPayload.MAX_ID + 1));
        assertThrows(RuntimeException.class, () -> WorkListsPayload.STREAM_CODEC.decode(longId));

        // decodable but not usable: the client ignores it (and the server never sends it)
        assertFalse(new WorkListsPayload(7, Optional.of(List.of(row("a:b"), row("a:b"))), Optional.empty()).valid(), "duplicate id");
        assertFalse(new WorkListsPayload(7, Optional.of(List.of(row(" "))), Optional.empty()).valid(), "blank id");
        assertFalse(new WorkListsPayload(7, Optional.of(List.of(new WorkListsPayload.Row("a:b", "L", "N", -1))), Optional.empty()).valid(),
            "negative stock");
        assertFalse(new WorkListsPayload(7, Optional.of(List.of(new WorkListsPayload.Row("a:b", "L", "n".repeat(WorkListsPayload.MAX_TEXT + 1), 1))),
            Optional.empty()).valid(), "a text the codec can't carry");
        List<WorkListsPayload.Row> tooMany = new ArrayList<>();
        for (int i = 0; i <= WorkListsPayload.MAX_ROWS; i++) tooMany.add(row("a:r" + i));
        assertFalse(new WorkListsPayload(7, Optional.of(tooMany), Optional.empty()).valid(), "too many rows");
    }
}
