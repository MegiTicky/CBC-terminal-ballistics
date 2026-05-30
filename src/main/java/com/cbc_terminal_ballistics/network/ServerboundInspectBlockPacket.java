package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.compat.CbcInspectionCompat;
import com.cbc_terminal_ballistics.debug.InspectionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ServerboundInspectBlockPacket(BlockPos pos, Direction face, Vec3 hitLocation) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(face);
        buf.writeDouble(hitLocation.x);
        buf.writeDouble(hitLocation.y);
        buf.writeDouble(hitLocation.z);
    }

    public static ServerboundInspectBlockPacket decode(FriendlyByteBuf buf) {
        return new ServerboundInspectBlockPacket(buf.readBlockPos(), buf.readEnum(Direction.class),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || TBNetwork.CHANNEL == null) return;
            if (!CbcInspectionCompat.isAdvanced(player.getMainHandItem()) && !CbcInspectionCompat.isAdvanced(player.getOffhandItem())) return;
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0D * 64.0D) return;
            if (!player.serverLevel().isLoaded(pos)) return;
            BlockHitResult hit = new BlockHitResult(hitLocation, face, pos, false);
            ClientboundInspectionSnapshotPacket response = new ClientboundInspectionSnapshotPacket(InspectionSnapshot.build(player.serverLevel(), pos, hit));
            TBNetwork.CHANNEL.sendTo(response, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        });
        ctx.get().setPacketHandled(true);
    }
}
