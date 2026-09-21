package com.cbc_terminal_ballistics.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A stored spall cone's display polylines (world space), sent on demand when a
 * player right-clicks with ballistic goggles. The client replays the fragment
 * paths in-world for {@code renderTicks}.
 */
public record ClientboundSpallPathPacket(float[] points, int[] rayPointCounts, int renderTicks) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarIntArray(rayPointCounts == null ? new int[0] : rayPointCounts);
        float[] polyline = points == null ? new float[0] : points;
        buf.writeVarInt(polyline.length);
        for (float point : polyline) {
            buf.writeFloat(point);
        }
        buf.writeVarInt(renderTicks);
    }

    public static ClientboundSpallPathPacket decode(FriendlyByteBuf buf) {
        int[] rayPointCounts = buf.readVarIntArray(16);
        int pointCount = buf.readVarInt();
        float[] points = new float[Math.min(pointCount, 384)];
        for (int i = 0; i < points.length; i++) {
            points[i] = buf.readFloat();
        }
        int renderTicks = buf.readVarInt();
        return new ClientboundSpallPathPacket(points, rayPointCounts, renderTicks);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleSpallPath", ClientboundSpallPathPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
