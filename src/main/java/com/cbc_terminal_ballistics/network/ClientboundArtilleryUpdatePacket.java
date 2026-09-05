package com.cbc_terminal_ballistics.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Keeps the artillery chase cam alive once the projectile leaves client
 * tracking range: CBC keeps simulating shells server-side beyond chunk
 * loading, but the vanilla entity tracker despawns the client copy, so this
 * packet relays the authoritative position and velocity every couple of ticks.
 * The client extrapolates between updates. {@code removed=true} announces the
 * shell is gone (impacted or despawned).
 */
public record ClientboundArtilleryUpdatePacket(int entityId, String entityTypeKey, Vec3 pos, Vec3 vel, boolean removed) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeUtf(entityTypeKey, 128);
        buf.writeDouble(pos.x);
        buf.writeDouble(pos.y);
        buf.writeDouble(pos.z);
        buf.writeDouble(vel.x);
        buf.writeDouble(vel.y);
        buf.writeDouble(vel.z);
        buf.writeBoolean(removed);
    }

    public static ClientboundArtilleryUpdatePacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        String typeKey = buf.readUtf(128);
        Vec3 pos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 vel = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        boolean removed = buf.readBoolean();
        return new ClientboundArtilleryUpdatePacket(entityId, typeKey, pos, vel, removed);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleArtilleryUpdate", ClientboundArtilleryUpdatePacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
