package com.cbc_terminal_ballistics.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundProjectileSlowdownPacket(int factor) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(factor);
    }

    public static ClientboundProjectileSlowdownPacket decode(FriendlyByteBuf buf) {
        return new ClientboundProjectileSlowdownPacket(buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleProjectileSlowdown", ClientboundProjectileSlowdownPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
