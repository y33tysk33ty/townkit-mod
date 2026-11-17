package townkit.core.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import townkit.core.TownKit;
import townkit.core.look.VillagerLook;

/** Server to client: the current {@link VillagerLook} of the entity with this network id ({@link VillagerLook#EMPTY} clears it). */
public record VillagerLookPayload(int entityId, VillagerLook look) implements CustomPacketPayload {
    public static final Type<VillagerLookPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "villager_look"));

    public static final StreamCodec<ByteBuf, VillagerLookPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, VillagerLookPayload::entityId,
        VillagerLook.STREAM_CODEC, VillagerLookPayload::look,
        VillagerLookPayload::new);

    @Override
    public Type<VillagerLookPayload> type() {
        return TYPE;
    }
}
