package townkit.core.net;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import townkit.core.TownKit;

/**
 * Server to client, on login, after a {@code /reload} and when an addon's registrations change: the work lists a
 * player's settings screens show where TownKit changed Towntopia's. {@code dishes}: the cook's "allowed recipes"
 * rows, in order ({@code townkit.core.work.cook.CookScreenRows}); {@code ores}: the miner's "wanted ores" rows, in
 * order ({@code townkit.core.work.mine.MinerScreenRows}; {@code stock} unused). An empty optional means "Towntopia's own list"; an empty list means
 * every entry was removed. Replaces the previous one. A client without TownKit never gets it and keeps Towntopia's.
 * {@code version} (above 0, new for each send) is what the client's {@link WorkListsAppliedPayload} answers and what a
 * {@link WorkWindowPayload} names.
 */
public record WorkListsPayload(int version, Optional<List<Row>> dishes, Optional<List<Row>> ores) implements CustomPacketPayload {
    public static final Type<WorkListsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "work_lists"));

    /** Most rows one list may carry; a larger count fails decoding before anything is read or allocated. */
    public static final int MAX_ROWS = 1024;
    /** Longest id (characters). */
    public static final int MAX_ID = 256;
    /** Longest label or needs line (characters). */
    public static final int MAX_TEXT = 4096;

    /**
     * One row: the entry's {@code id} (an item id for a dish), its {@code label}, its "needs" line (the hover text),
     * and for a dish the default of its stock row ({@code 0}: no stock row of its own).
     */
    public record Row(String id, String label, String needs, int stock) {
    }

    private static final StreamCodec<FriendlyByteBuf, Row> ROW = new StreamCodec<>() {
        @Override
        public Row decode(FriendlyByteBuf buf) {
            return new Row(buf.readUtf(MAX_ID), buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, Row r) {
            buf.writeUtf(r.id(), MAX_ID);
            buf.writeUtf(r.label(), MAX_TEXT);
            buf.writeUtf(r.needs(), MAX_TEXT);
            buf.writeVarInt(r.stock());
        }
    };

    private static final StreamCodec<FriendlyByteBuf, Optional<List<Row>>> LIST = new StreamCodec<>() {
        @Override
        public Optional<List<Row>> decode(FriendlyByteBuf buf) {
            if (!buf.readBoolean()) return Optional.empty();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_ROWS) throw new DecoderException("work list with " + n + " rows (at most " + MAX_ROWS + ")");
            List<Row> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) rows.add(ROW.decode(buf));
            return Optional.of(List.copyOf(rows));
        }

        @Override
        public void encode(FriendlyByteBuf buf, Optional<List<Row>> list) {
            buf.writeBoolean(list.isPresent());
            if (list.isEmpty()) return;
            buf.writeVarInt(list.get().size());
            for (Row r : list.get()) ROW.encode(buf, r);
        }
    };

    public static final StreamCodec<FriendlyByteBuf, WorkListsPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.VAR_INT, WorkListsPayload::version, LIST, WorkListsPayload::dishes, LIST,
            WorkListsPayload::ores, WorkListsPayload::new);

    public WorkListsPayload {
        dishes = dishes.map(List::copyOf);
        ores = ores.map(List::copyOf);
    }

    /**
     * Whether a screen can use it: a version above 0, at most {@link #MAX_ROWS} rows a list, ids not blank and not repeated, texts within
     * their caps, no negative stock. The server sends only valid payloads; a client ignores any other.
     */
    public boolean valid() {
        return this.version > 0 && valid(this.dishes) && valid(this.ores);
    }

    private static boolean valid(Optional<List<Row>> list) {
        if (list.isEmpty()) return true;
        if (list.get().size() > MAX_ROWS) return false;
        Set<String> ids = new HashSet<>();
        for (Row r : list.get()) {
            if (r.id().isBlank() || r.id().length() > MAX_ID || !ids.add(r.id())) return false;
            if (r.label().length() > MAX_TEXT || r.needs().length() > MAX_TEXT || r.stock() < 0) return false;
        }
        return true;
    }

    @Override
    public Type<WorkListsPayload> type() {
        return TYPE;
    }
}
