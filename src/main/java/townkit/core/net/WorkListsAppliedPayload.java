package townkit.core.net;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import townkit.core.TownKit;

/**
 * Client to server, the answer to a {@link WorkListsPayload}: whether the client's settings screens now show the rows
 * of that {@code version} ({@code applied}), or kept Towntopia's lists (a payload it couldn't use, a page it couldn't
 * build, its settings hook missing or hooks switched off on that install). The server writes a player's settings window
 * with TownKit's page only after a {@code true} answer for the lists it sent last; an answer for any other version is
 * ignored.
 */
public record WorkListsAppliedPayload(int version, boolean applied) implements CustomPacketPayload {
    public static final Type<WorkListsAppliedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "work_lists_applied"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, WorkListsAppliedPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, WorkListsAppliedPayload::version, ByteBufCodecs.BOOL, WorkListsAppliedPayload::applied,
        WorkListsAppliedPayload::new);

    @Override
    public Type<WorkListsAppliedPayload> type() {
        return TYPE;
    }
}
