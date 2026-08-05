package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClientboundProjectileSlowdownPacket(int factor) implements CustomPacketPayload {

    public static final Type<ClientboundProjectileSlowdownPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "projectile_slowdown"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundProjectileSlowdownPacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ClientboundProjectileSlowdownPacket>) (buf, packet) -> packet.encode(buf),
        (StreamDecoder<RegistryFriendlyByteBuf, ClientboundProjectileSlowdownPacket>) ClientboundProjectileSlowdownPacket::decode
    );

    private void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(factor);
    }

    private static ClientboundProjectileSlowdownPacket decode(RegistryFriendlyByteBuf buf) {
        return new ClientboundProjectileSlowdownPacket(buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundProjectileSlowdownPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleProjectileSlowdown", ClientboundProjectileSlowdownPacket.class).invoke(null, packet);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}