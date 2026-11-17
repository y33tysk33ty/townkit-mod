package townkit.core.net;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import townkit.core.TownKit;

/**
 * Server to client, right before a villager settings window opens (same connection, so it arrives first): which page
 * the window's values were written with. {@code version} is that of the {@link WorkListsPayload} whose rows were used,
 * or 0 for Towntopia's own schema. The client reads the window with that page, else with Towntopia's.
 */
public record WorkWindowPayload(int version) implements CustomPacketPayload {
    public static final Type<WorkWindowPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "work_window"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, WorkWindowPayload> STREAM_CODEC =
        ByteBufCodecs.VAR_INT.map(WorkWindowPayload::new, WorkWindowPayload::version);

    @Override
    public Type<WorkWindowPayload> type() {
        return TYPE;
    }
}
