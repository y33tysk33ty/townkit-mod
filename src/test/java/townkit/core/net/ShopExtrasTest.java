package townkit.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * The payload's layout, with plain-text components: Minecraft's own component codec needs the game's registries
 * (GameTest {@code ShopTests.extrasRoundTrip} covers it).
 */
class ShopExtrasTest {
    private static final StreamCodec<RegistryFriendlyByteBuf, Component> TEXT =
        ByteBufCodecs.STRING_UTF8.<Component>map(Component::literal, Component::getString).cast();

    @Test
    void roundTrip() {
        ShopExtrasPayload sent = new ShopExtrasPayload(List.of(
            new ShopExtrasPayload.Extra("addon:war_horn", ResourceLocation.parse("minecraft:goat_horn"), ResourceLocation.parse("addon:coin"),
                true, false, Optional.of(Component.literal("Only at war")), Optional.of(Component.literal("Calls the guards")),
                Optional.of(Component.literal("War Horn"))),
            new ShopExtrasPayload.Extra("towntopia:farmer_token", ResourceLocation.parse("towntopia:farmer_token"),
                ResourceLocation.parse("minecraft:emerald"), false, true, Optional.empty(), Optional.empty(), Optional.empty())));
        StreamCodec<RegistryFriendlyByteBuf, ShopExtrasPayload> codec = ShopExtrasPayload.codec(TEXT);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        codec.encode(buf, sent);
        assertEquals(sent, codec.decode(buf));
        assertFalse(buf.isReadable(), "nothing left over");
        RegistryFriendlyByteBuf empty = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        codec.encode(empty, new ShopExtrasPayload(List.of()));
        assertEquals(List.of(), codec.decode(empty).rows());
    }
}
