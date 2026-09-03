package com.cbc_terminal_ballistics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Sends testray debug visualization data to the client for rendering.
 * Contains the ray, hit info, and all tested block AABBs for diagnostic rendering.
 */
public record ClientboundTestRayPacket(
    Vec3 from,
    Vec3 to,
    @Nullable Vec3 hitLocation,
    @Nullable BlockPos hitBlockPos,
    boolean hit,
    double[] blockData,
    int blockCount
) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(from.x);
        buf.writeDouble(from.y);
        buf.writeDouble(from.z);
        buf.writeDouble(to.x);
        buf.writeDouble(to.y);
        buf.writeDouble(to.z);
        buf.writeBoolean(hit);
        if (hit) {
            buf.writeDouble(hitLocation.x);
            buf.writeDouble(hitLocation.y);
            buf.writeDouble(hitLocation.z);
            buf.writeBlockPos(hitBlockPos);
        }
        buf.writeVarInt(blockCount);
        for (double v : blockData) buf.writeDouble(v);
    }

    public static ClientboundTestRayPacket decode(FriendlyByteBuf buf) {
        Vec3 from = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 to = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        boolean hit = buf.readBoolean();
        Vec3 hitLoc = null;
        BlockPos hitPos = null;
        if (hit) {
            hitLoc = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            hitPos = buf.readBlockPos();
        }
        int blockCount = buf.readVarInt();
        double[] blockData = new double[blockCount * 9];
        for (int i = 0; i < blockData.length; i++) blockData[i] = buf.readDouble();
        return new ClientboundTestRayPacket(from, to, hitLoc, hitPos, hit, blockData, blockCount);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientTestRayVisuals");
                handler.getMethod("accept", ClientboundTestRayPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
