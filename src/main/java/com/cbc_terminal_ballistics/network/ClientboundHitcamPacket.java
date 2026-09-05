package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Sends a recorded projectile flight to a client for hitcam replay playback.
 * An empty sample list stops any active playback.
 */
public record ClientboundHitcamPacket(String entityTypeKey, List<HitcamSample> samples, String outcomeSummary) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(entityTypeKey, 256);
        buf.writeVarInt(samples.size());
        for (HitcamSample s : samples) {
            buf.writeDouble(s.x());
            buf.writeDouble(s.y());
            buf.writeDouble(s.z());
            buf.writeFloat(s.ox());
            buf.writeFloat(s.oy());
            buf.writeFloat(s.oz());
        }
        buf.writeBoolean(outcomeSummary != null);
        if (outcomeSummary != null) {
            buf.writeUtf(outcomeSummary, 256);
        }
    }

    public static ClientboundHitcamPacket decode(FriendlyByteBuf buf) {
        String entityTypeKey = buf.readUtf(256);
        int count = buf.readVarInt();
        List<HitcamSample> samples = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            samples.add(new HitcamSample(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        String summary = buf.readBoolean() ? buf.readUtf(256) : null;
        return new ClientboundHitcamPacket(entityTypeKey, samples, summary);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleHitcam", ClientboundHitcamPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
