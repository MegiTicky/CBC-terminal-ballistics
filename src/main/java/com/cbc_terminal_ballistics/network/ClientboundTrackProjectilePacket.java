package com.cbc_terminal_ballistics.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Tells the client to point the artillery hitcam (live chase-camera PiP) at a
 * just-fired projectile entity. Entity id 0 stops tracking. {@code rebind} is
 * set when this is the SAME shell re-created after its chunk unloaded (new
 * entity id, same UUID) — the client's sticky chase cam must always accept a
 * rebind of the shell it is already following, while fresh track packets for
 * other burst shells are ignored while a chase is live.
 */
public record ClientboundTrackProjectilePacket(int entityId, boolean rebind) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(rebind);
    }

    public static ClientboundTrackProjectilePacket decode(FriendlyByteBuf buf) {
        return new ClientboundTrackProjectilePacket(buf.readVarInt(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleTrackProjectile", ClientboundTrackProjectilePacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
