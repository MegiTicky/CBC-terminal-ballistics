package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.state.EmbeddedShell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record ClientboundEmbeddedShellsPacket(BlockPos pos, List<EmbeddedShell> shells) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(shells.size());
        for (EmbeddedShell shell : shells) {
            buf.writeUUID(shell.id());
            buf.writeEnum(shell.caliber());
            buf.writeEnum(shell.face());
            buf.writeFloat(shell.x());
            buf.writeFloat(shell.y());
            buf.writeFloat(shell.z());
            buf.writeFloat(shell.directionX());
            buf.writeFloat(shell.directionY());
            buf.writeFloat(shell.directionZ());
            buf.writeLong(shell.gameTime());
        }
    }

    public static ClientboundEmbeddedShellsPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int count = buf.readVarInt();
        List<EmbeddedShell> shells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = buf.readUUID();
            TBCaliber caliber = buf.readEnum(TBCaliber.class);
            Direction face = buf.readEnum(Direction.class);
            shells.add(new EmbeddedShell(id, caliber, face, buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readLong()));
        }
        return new ClientboundEmbeddedShellsPacket(pos, shells);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleEmbeddedShells", ClientboundEmbeddedShellsPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
