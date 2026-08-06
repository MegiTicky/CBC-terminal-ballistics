package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.compat.CbcInspectionCompat;
import com.cbc_terminal_ballistics.debug.InspectionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundInspectBlockPacket(BlockPos pos, Direction face, Vec3 hitLocation) implements CustomPacketPayload {

    public static final Type<ServerboundInspectBlockPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "inspect_block"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundInspectBlockPacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ServerboundInspectBlockPacket>) (buf, packet) -> packet.encode(buf),
        (StreamDecoder<RegistryFriendlyByteBuf, ServerboundInspectBlockPacket>) ServerboundInspectBlockPacket::decode
    );

    private void encode(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(face);
        buf.writeDouble(hitLocation.x);
        buf.writeDouble(hitLocation.y);
        buf.writeDouble(hitLocation.z);
    }

    private static ServerboundInspectBlockPacket decode(RegistryFriendlyByteBuf buf) {
        return new ServerboundInspectBlockPacket(
            buf.readBlockPos(),
            buf.readEnum(Direction.class),
            new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundInspectBlockPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) ctx.player();
            if (player == null) return;
            if (!CbcInspectionCompat.isAdvanced(player.getMainHandItem())
                && !CbcInspectionCompat.isAdvanced(player.getOffhandItem())) return;
            if (player.distanceToSqr(Vec3.atCenterOf(packet.pos)) > 64.0D * 64.0D) return;
            if (!player.serverLevel().isLoaded(packet.pos)) return;
            BlockHitResult hit = new BlockHitResult(packet.hitLocation, packet.face, packet.pos, false);
            ClientboundInspectionSnapshotPacket response = new ClientboundInspectionSnapshotPacket(
                InspectionSnapshot.build(player.serverLevel(), packet.pos, hit));
            PacketDistributor.sendToPlayer(player, response);
        });
    }
}
