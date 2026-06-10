package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.debug.InspectionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundInspectionSnapshotPacket(InspectionSnapshot snapshot) {
    public void encode(FriendlyByteBuf buf) {
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

    public static ClientboundInspectionSnapshotPacket decode(FriendlyByteBuf buf) {
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

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleInspectionSnapshot", ClientboundInspectionSnapshotPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
