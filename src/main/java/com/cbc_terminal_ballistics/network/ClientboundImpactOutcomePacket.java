package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClientboundImpactOutcomePacket(int entityId, String outcome, Vec3 position, Vec3 velocity,
                                             boolean inGround, BlockPos blockPos) implements CustomPacketPayload {
    public static final Type<ClientboundImpactOutcomePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "impact_outcome"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundImpactOutcomePacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ClientboundImpactOutcomePacket>) (buf, packet) -> packet.encode(buf),
        (StreamDecoder<RegistryFriendlyByteBuf, ClientboundImpactOutcomePacket>) ClientboundImpactOutcomePacket::decode
    );

    private void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeUtf(outcome);
        buf.writeDouble(position.x);
        buf.writeDouble(position.y);
        buf.writeDouble(position.z);
        buf.writeDouble(velocity.x);
        buf.writeDouble(velocity.y);
        buf.writeDouble(velocity.z);
        buf.writeBoolean(inGround);
        buf.writeInt(blockPos.getX());
        buf.writeInt(blockPos.getY());
        buf.writeInt(blockPos.getZ());
    }

    private static ClientboundImpactOutcomePacket decode(RegistryFriendlyByteBuf buf) {
        return new ClientboundImpactOutcomePacket(
            buf.readVarInt(),
            buf.readUtf(),
            new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
            new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
            buf.readBoolean(),
            new BlockPos(buf.readInt(), buf.readInt(), buf.readInt())
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundImpactOutcomePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleImpactOutcome", ClientboundImpactOutcomePacket.class).invoke(null, packet);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}
