package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.debug.InspectionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClientboundInspectionSnapshotPacket(InspectionSnapshot snapshot) implements CustomPacketPayload {

    public static final Type<ClientboundInspectionSnapshotPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "inspection_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundInspectionSnapshotPacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ClientboundInspectionSnapshotPacket>) (buf, packet) -> packet.encode(buf),
        (StreamDecoder<RegistryFriendlyByteBuf, ClientboundInspectionSnapshotPacket>) ClientboundInspectionSnapshotPacket::decode
    );

    private void encode(RegistryFriendlyByteBuf buf) {
        InspectionSnapshot s = snapshot;
        buf.writeBlockPos(s.pos());
        buf.writeResourceLocation(s.materialId());
        buf.writeDouble(s.armorToughness());
        buf.writeDouble(s.armorHardness());
        buf.writeDouble(s.ductility());
        buf.writeDouble(s.brittleness());
        buf.writeDouble(s.spallMultiplier());
        buf.writeDouble(s.integrityDamage());
        buf.writeDouble(s.integrityThreshold());
        buf.writeUtf(s.lastOutcome());
        buf.writeUtf(s.lastCaliber());
        buf.writeDouble(s.lastVelocity());
        buf.writeDouble(s.lastDamage());
        buf.writeVarInt(s.lastSpallFragments());
        buf.writeDouble(s.lastMassRatio());
        buf.writeDouble(s.lastSpallDamageModifier());
    }

    private static ClientboundInspectionSnapshotPacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        ResourceLocation materialId = buf.readResourceLocation();
        return new ClientboundInspectionSnapshotPacket(new InspectionSnapshot(
            pos,
            materialId,
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readUtf(),
            buf.readUtf(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readVarInt(),
            buf.readDouble(),
            buf.readDouble()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundInspectionSnapshotPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleInspectionSnapshot", ClientboundInspectionSnapshotPacket.class)
                    .invoke(null, packet);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}