package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClientboundIntegrityProgressPacket(BlockPos pos, int stage) implements CustomPacketPayload {

    public static final Type<ClientboundIntegrityProgressPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "integrity_progress"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundIntegrityProgressPacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ClientboundIntegrityProgressPacket>) (buf, packet) -> packet.encode(buf),
        (StreamDecoder<RegistryFriendlyByteBuf, ClientboundIntegrityProgressPacket>) ClientboundIntegrityProgressPacket::decode
    );

    private void encode(RegistryFriendlyByteBuf buf) {
        // Write x/y/z as separate ints instead of writeBlockPos (only supports 26-bit coords).
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeVarInt(stage);
    }

    private static ClientboundIntegrityProgressPacket decode(RegistryFriendlyByteBuf buf) {
        return new ClientboundIntegrityProgressPacket(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundIntegrityProgressPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleIntegrityProgress", ClientboundIntegrityProgressPacket.class)
                    .invoke(null, packet);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}