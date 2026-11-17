package townkit.core.net;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import townkit.core.TownKit;

/**
 * Server to client, sent with each Mayor shop list TownKit changed: what Towntopia's offer entries can't carry, for
 * each row TownKit sells (addon rows, and Towntopia rows an addon changed). Replaces the previous one.
 */
public record ShopExtrasPayload(List<Extra> rows) implements CustomPacketPayload {
    public static final Type<ShopExtrasPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "shop_extras"));

    /**
     * One row. {@code offerId}: the offer entry's id (Towntopia's item id, or the addon row's id). {@code item}: what it
     * sells (the icon of an addon row). {@code name}: an addon row's name (empty for Towntopia's rows).
     * {@code description}: the hover text (empty keeps Towntopia's). {@code reason}: why a greyed row is greyed.
     */
    public record Extra(String offerId, ResourceLocation item, ResourceLocation currency, boolean boundCurrency, boolean available,
            Optional<Component> reason, Optional<Component> description, Optional<Component> name) {
        /** With {@code components} for the names, descriptions and reasons (Minecraft's own on the network). */
        static StreamCodec<RegistryFriendlyByteBuf, Extra> codec(StreamCodec<RegistryFriendlyByteBuf, Component> components) {
            StreamCodec<RegistryFriendlyByteBuf, Optional<Component>> optional = ByteBufCodecs.optional(components);
            return new StreamCodec<>() {
                @Override
                public Extra decode(RegistryFriendlyByteBuf buf) {
                    return new Extra(buf.readUtf(), buf.readResourceLocation(), buf.readResourceLocation(), buf.readBoolean(), buf.readBoolean(),
                        optional.decode(buf), optional.decode(buf), optional.decode(buf));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, Extra e) {
                    buf.writeUtf(e.offerId());
                    buf.writeResourceLocation(e.item());
                    buf.writeResourceLocation(e.currency());
                    buf.writeBoolean(e.boundCurrency());
                    buf.writeBoolean(e.available());
                    optional.encode(buf, e.reason());
                    optional.encode(buf, e.description());
                    optional.encode(buf, e.name());
                }
            };
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopExtrasPayload> STREAM_CODEC = codec(ComponentSerialization.STREAM_CODEC);

    /**
     * The payload's codec with {@code components} for its texts. Unit tests pass a plain-text codec: Minecraft's own
     * needs the game's registries, which only a running game has (GameTest {@code ShopTests.extrasRoundTrip}).
     */
    public static StreamCodec<RegistryFriendlyByteBuf, ShopExtrasPayload> codec(StreamCodec<RegistryFriendlyByteBuf, Component> components) {
        return Extra.codec(components).apply(ByteBufCodecs.list()).map(ShopExtrasPayload::new, ShopExtrasPayload::rows);
    }

    public ShopExtrasPayload {
        rows = List.copyOf(rows);
    }

    @Override
    public Type<ShopExtrasPayload> type() {
        return TYPE;
    }
}
