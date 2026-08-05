package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClientboundSpallConePacket(Vec3 origin, Vec3 forward, double coneCos, double range,
                                         int visualFragments, long seed, float intensity,
                                         TBCaliber caliber) implements CustomPacketPayload {

    public static final Type<ClientboundSpallConePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "spall_cone"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSpallConePacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ClientboundSpallConePacket>) (buf, packet) -> packet.encode(buf),
        (StreamDecoder<RegistryFriendlyByteBuf, ClientboundSpallConePacket>) ClientboundSpallConePacket::decode
    );

    private void encode(RegistryFriendlyByteBuf buf) {
        writeVec3(buf, origin);
        writeVec3(buf, forward);
        buf.writeDouble(coneCos);
        buf.writeDouble(range);
        buf.writeVarInt(visualFragments);
        buf.writeLong(seed);
        buf.writeFloat(intensity);
        buf.writeEnum(caliber);
    }

    private static ClientboundSpallConePacket decode(RegistryFriendlyByteBuf buf) {
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

    private static void writeVec3(RegistryFriendlyByteBuf buf, Vec3 vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    private static Vec3 readVec3(RegistryFriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundSpallConePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleSpallCone", ClientboundSpallConePacket.class).invoke(null, packet);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}