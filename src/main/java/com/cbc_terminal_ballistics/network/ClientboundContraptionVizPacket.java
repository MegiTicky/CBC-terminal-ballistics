package com.cbc_terminal_ballistics.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Turns on the client-side contraption collision visualizer for a few seconds:
 * wireframes the exact block geometry the shell raycast tests, so misalignment
 * against the rendered contraption is directly visible.
 */
public record ClientboundContraptionVizPacket(int seconds) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(seconds);
    }

    public static ClientboundContraptionVizPacket decode(FriendlyByteBuf buf) {
        return new ClientboundContraptionVizPacket(buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientContraptionViz");
                handler.getMethod("activate", int.class).invoke(null, this.seconds());
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
