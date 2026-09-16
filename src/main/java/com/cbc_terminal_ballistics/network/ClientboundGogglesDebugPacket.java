package com.cbc_terminal_ballistics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One shell's goggles-debug verdict for the {@code /cbctb goggles debug}
 * overlay: where the shell was detected, what happened to it (captured by
 * whom, no audience at all, or removed before its first tick and therefore
 * never even seen by the recorder), and where each linked mount actually
 * resolved to at spawn time with its distance and match result.
 */
public record ClientboundGogglesDebugPacket(
    double x, double y, double z,
    String className,
    String outcome,
    List<LinkDiag> links
) {
    /** One linked mount as it resolved for this shell spawn. */
    public record LinkDiag(BlockPos resolved, double dist, boolean matched, String label) {}

    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeUtf(className, 256);
        buf.writeUtf(outcome, 128);
        buf.writeVarInt(links.size());
        for (LinkDiag link : links) {
            buf.writeBoolean(link.resolved() != null);
            if (link.resolved() != null) buf.writeBlockPos(link.resolved());
            buf.writeDouble(link.dist());
            buf.writeBoolean(link.matched());
            buf.writeUtf(link.label(), 64);
        }
    }

    public static ClientboundGogglesDebugPacket decode(FriendlyByteBuf buf) {
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        String className = buf.readUtf(256);
        String outcome = buf.readUtf(128);
        int count = buf.readVarInt();
        List<LinkDiag> links = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos resolved = buf.readBoolean() ? buf.readBlockPos() : null;
            double dist = buf.readDouble();
            boolean matched = buf.readBoolean();
            String label = buf.readUtf(64);
            links.add(new LinkDiag(resolved, dist, matched, label));
        }
        return new ClientboundGogglesDebugPacket(x, y, z, className, outcome, links);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleGogglesDebug", ClientboundGogglesDebugPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
