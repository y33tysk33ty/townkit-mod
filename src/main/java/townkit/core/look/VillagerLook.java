package townkit.core.look;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import townkit.api.skin.SkinRef;

/**
 * What TownKit changes about how one villager is drawn, as the server tells tracking clients ({@link LookSync}, read on
 * the client through {@code ClientLooks}). Every field off is {@link #EMPTY}: Towntopia draws the villager as usual.
 *
 * @param bubble      addon bubble icon shown over the villager, or null for none (Towntopia's own bubble shows)
 * @param speech      the bubble is a speech bubble (else a thought bubble)
 * @param bubbleTicks how long the bubble shows, in ticks (0 or more)
 * @param skin        skin override, or null for Towntopia's choice
 * @param move        addon move (animation) playing, or null for none
 * @param moveSeq     bumped each time a move is started, so the same move started again is a change clients see
 *                    (0 with no move)
 * @param moveOnce    the move plays once, then Towntopia's animation again (else it loops)
 * @param moveSwaps   Towntopia clip id -> addon clip id played in its place (empty for none); stored as an
 *                    unmodifiable copy
 */
public record VillagerLook(@Nullable ResourceLocation bubble, boolean speech, int bubbleTicks, @Nullable SkinRef skin,
                           @Nullable ResourceLocation move, int moveSeq, boolean moveOnce, Map<ResourceLocation, ResourceLocation> moveSwaps) {
    /** No change: Towntopia draws the villager as it would without TownKit. */
    public static final VillagerLook EMPTY = new VillagerLook(null, false, 0, null, null, 0, false, Map.of());

    /** A cap on the swaps a look may carry, so a bad packet can't make the client allocate without bound. */
    static final int MAX_SWAPS = 256;

    public static final StreamCodec<ByteBuf, VillagerLook> STREAM_CODEC = StreamCodec.of(VillagerLook::write, VillagerLook::read);

    public VillagerLook {
        Objects.requireNonNull(moveSwaps, "moveSwaps");
        if (bubbleTicks < 0) {
            throw new IllegalArgumentException("bubbleTicks must be 0 or more: " + bubbleTicks);
        }
        if (moveSwaps.size() > MAX_SWAPS) {
            throw new IllegalArgumentException("at most " + MAX_SWAPS + " move swaps: " + moveSwaps.size());
        }
        moveSwaps = Map.copyOf(moveSwaps);
    }

    /** Whether this look changes nothing. */
    public boolean isEmpty() {
        return equals(EMPTY);
    }

    public VillagerLook withBubble(@Nullable ResourceLocation bubble, boolean speech, int bubbleTicks) {
        return new VillagerLook(bubble, speech, bubbleTicks, skin, move, moveSeq, moveOnce, moveSwaps);
    }

    public VillagerLook withSkin(@Nullable SkinRef skin) {
        return new VillagerLook(bubble, speech, bubbleTicks, skin, move, moveSeq, moveOnce, moveSwaps);
    }

    /** The move playing ({@code move} null: none, which also resets the sequence and the once flag). */
    public VillagerLook withMove(@Nullable ResourceLocation move, int moveSeq, boolean moveOnce) {
        return move == null ? new VillagerLook(bubble, speech, bubbleTicks, skin, null, 0, false, moveSwaps)
            : new VillagerLook(bubble, speech, bubbleTicks, skin, move, moveSeq, moveOnce, moveSwaps);
    }

    public VillagerLook withMoveSwaps(Map<ResourceLocation, ResourceLocation> moveSwaps) {
        return new VillagerLook(bubble, speech, bubbleTicks, skin, move, moveSeq, moveOnce, moveSwaps);
    }

    private static void write(ByteBuf buf, VillagerLook look) {
        writeNullable(buf, look.bubble);
        buf.writeBoolean(look.speech);
        VarInt.write(buf, look.bubbleTicks);
        writeNullable(buf, look.skin == null ? null : look.skin.id());
        writeNullable(buf, look.move);
        VarInt.write(buf, look.moveSeq);
        buf.writeBoolean(look.moveOnce);
        VarInt.write(buf, look.moveSwaps.size());
        for (Map.Entry<ResourceLocation, ResourceLocation> e : look.moveSwaps.entrySet()) {
            ResourceLocation.STREAM_CODEC.encode(buf, e.getKey());
            ResourceLocation.STREAM_CODEC.encode(buf, e.getValue());
        }
    }

    private static VillagerLook read(ByteBuf buf) {
        ResourceLocation bubble = readNullable(buf);
        boolean speech = buf.readBoolean();
        int bubbleTicks = VarInt.read(buf);
        ResourceLocation skin = readNullable(buf);
        ResourceLocation move = readNullable(buf);
        int moveSeq = VarInt.read(buf);
        boolean moveOnce = buf.readBoolean();
        int n = VarInt.read(buf);
        if (n < 0 || n > MAX_SWAPS) {
            throw new DecoderException("bad move swap count " + n);
        }
        Map<ResourceLocation, ResourceLocation> swaps = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            swaps.put(ResourceLocation.STREAM_CODEC.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf));
        }
        try {
            return new VillagerLook(bubble, speech, bubbleTicks, skin == null ? null : SkinRef.of(skin), move, moveSeq, moveOnce, swaps);
        } catch (IllegalArgumentException e) {
            throw new DecoderException(e);
        }
    }

    private static void writeNullable(ByteBuf buf, @Nullable ResourceLocation id) {
        buf.writeBoolean(id != null);
        if (id != null) ResourceLocation.STREAM_CODEC.encode(buf, id);
    }

    @Nullable
    private static ResourceLocation readNullable(ByteBuf buf) {
        return buf.readBoolean() ? ResourceLocation.STREAM_CODEC.decode(buf) : null;
    }
}
