package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.ballistics.ImpactMarkKind;
import com.cbc_terminal_ballistics.ballistics.ImpactSurfaceType;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.state.ImpactMark;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Syncs impact marks for one block. For marks anchored to a Create contraption,
 * {@code contraptionUuid} and {@code localPos} identify the hull block in
 * contraption-local coordinates; {@code pos} is then only informational.
 */
public record ClientboundImpactMarksPacket(BlockPos pos, @Nullable UUID contraptionUuid, @Nullable BlockPos localPos, List<ImpactMark> marks) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(contraptionUuid != null);
        if (contraptionUuid != null) {
            buf.writeUUID(contraptionUuid);
            buf.writeBlockPos(localPos);
        }
        buf.writeVarInt(marks.size());
        for (ImpactMark mark : marks) {
            buf.writeEnum(mark.kind());
            buf.writeEnum(mark.caliber());
            buf.writeEnum(mark.surface());
            buf.writeEnum(mark.face());
            buf.writeFloat(mark.x());
            buf.writeFloat(mark.y());
            buf.writeFloat(mark.z());
            buf.writeFloat(mark.rotation());
            buf.writeLong(mark.gameTime());
        }
    }

    public static ClientboundImpactMarksPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        UUID contraptionUuid = null;
        BlockPos localPos = null;
        if (buf.readBoolean()) {
            contraptionUuid = buf.readUUID();
            localPos = buf.readBlockPos();
        }
        int count = buf.readVarInt();
        List<ImpactMark> marks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ImpactMarkKind kind = buf.readEnum(ImpactMarkKind.class);
            TBCaliber caliber = buf.readEnum(TBCaliber.class);
            ImpactSurfaceType surface = buf.readEnum(ImpactSurfaceType.class);
            Direction face = buf.readEnum(Direction.class);
            marks.add(new ImpactMark(kind, caliber, surface, face, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readLong()));
        }
        return new ClientboundImpactMarksPacket(pos, contraptionUuid, localPos, marks);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleImpactMarks", ClientboundImpactMarksPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
