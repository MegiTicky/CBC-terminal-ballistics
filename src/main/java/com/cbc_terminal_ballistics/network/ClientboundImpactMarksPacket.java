package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.ballistics.ImpactMarkKind;
import com.cbc_terminal_ballistics.ballistics.ImpactSurfaceType;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.state.ImpactMark;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ClientboundImpactMarksPacket(BlockPos pos, UUID subLevelId, List<ImpactMark> marks) implements CustomPacketPayload {

    public static final Type<ClientboundImpactMarksPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "impact_marks"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundImpactMarksPacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ClientboundImpactMarksPacket>) (buf, packet) -> packet.encode(buf),
        (StreamDecoder<RegistryFriendlyByteBuf, ClientboundImpactMarksPacket>) ClientboundImpactMarksPacket::decode
    );

    private void encode(RegistryFriendlyByteBuf buf) {
        // Write x/y/z as separate ints instead of writeBlockPos (which only supports 26-bit coords).
        // Sable sub-levels use extreme coordinates (>2^26) that overflow BlockPos.asLong().
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeBoolean(subLevelId != null);
        if (subLevelId != null) buf.writeUUID(subLevelId);
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

    private static ClientboundImpactMarksPacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        UUID subLevelId = buf.readBoolean() ? buf.readUUID() : null;
        int count = buf.readVarInt();
        List<ImpactMark> marks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ImpactMarkKind kind = buf.readEnum(ImpactMarkKind.class);
            TBCaliber caliber = buf.readEnum(TBCaliber.class);
            ImpactSurfaceType surface = buf.readEnum(ImpactSurfaceType.class);
            Direction face = buf.readEnum(Direction.class);
            marks.add(new ImpactMark(kind, caliber, surface, face,
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readLong()));
        }
        return new ClientboundImpactMarksPacket(pos, subLevelId, marks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundImpactMarksPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleImpactMarks", ClientboundImpactMarksPacket.class).invoke(null, packet);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}
