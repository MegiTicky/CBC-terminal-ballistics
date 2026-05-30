package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundSpallConePacket(Vec3 origin, Vec3 forward, double coneCos, double range,
                                         int visualFragments, long seed, float intensity, TBCaliber caliber) {
    public void encode(FriendlyByteBuf buf) {
        writeVec3(buf, origin);
        writeVec3(buf, forward);
        buf.writeDouble(coneCos);
        buf.writeDouble(range);
        buf.writeVarInt(visualFragments);
        buf.writeLong(seed);
        buf.writeFloat(intensity);
        buf.writeEnum(caliber);
    }

    public static ClientboundSpallConePacket decode(FriendlyByteBuf buf) {
        Vec3 origin = readVec3(buf);
        Vec3 forward = readVec3(buf);
        double coneCos = buf.readDouble();
        double range = buf.readDouble();
        int visualFragments = buf.readVarInt();
        long seed = buf.readLong();
        float intensity = buf.readFloat();
        TBCaliber caliber = buf.readEnum(TBCaliber.class);
        return new ClientboundSpallConePacket(origin, forward, coneCos, range, visualFragments, seed, intensity, caliber);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleSpallCone", ClientboundSpallConePacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }

    private static void writeVec3(FriendlyByteBuf buf, Vec3 vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
