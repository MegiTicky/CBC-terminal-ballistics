package com.cbc_terminal_ballistics.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * One shell's contraption-hit verdict for the {@code /cbctb shelldebug}
 * overlay: where the shell was when the decision was made, what the decision
 * was (MISS / SKIP / OUTCOME / JAM) and the reason behind it.
 */
public record ClientboundShellHitDebugPacket(
    double x, double y, double z,
    String shell,
    String verdict,
    String detail
) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeUtf(shell, 128);
        buf.writeUtf(verdict, 128);
        buf.writeUtf(detail, 256);
    }

    public static ClientboundShellHitDebugPacket decode(FriendlyByteBuf buf) {
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        String shell = buf.readUtf(128);
        String verdict = buf.readUtf(128);
        String detail = buf.readUtf(256);
        return new ClientboundShellHitDebugPacket(x, y, z, shell, verdict, detail);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientShellHitDebug");
                handler.getMethod("accept", ClientboundShellHitDebugPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
