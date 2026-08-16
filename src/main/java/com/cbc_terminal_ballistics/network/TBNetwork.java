package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class TBNetwork {
    private static final String VERSION = "8";
    public static SimpleChannel CHANNEL;

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(CBCTerminalBallistics.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);
        int id = 0;
        CHANNEL.messageBuilder(ClientboundImpactMarksPacket.class, id++)
            .encoder(ClientboundImpactMarksPacket::encode)
            .decoder(ClientboundImpactMarksPacket::decode)
            .consumerMainThread(ClientboundImpactMarksPacket::handle)
            .add();
        CHANNEL.messageBuilder(ClientboundSpallConePacket.class, id++)
            .encoder(ClientboundSpallConePacket::encode)
            .decoder(ClientboundSpallConePacket::decode)
            .consumerMainThread(ClientboundSpallConePacket::handle)
            .add();
        CHANNEL.messageBuilder(ClientboundProjectileSlowdownPacket.class, id++)
            .encoder(ClientboundProjectileSlowdownPacket::encode)
            .decoder(ClientboundProjectileSlowdownPacket::decode)
            .consumerMainThread(ClientboundProjectileSlowdownPacket::handle)
            .add();
        CHANNEL.messageBuilder(ServerboundInspectBlockPacket.class, id++)
            .encoder(ServerboundInspectBlockPacket::encode)
            .decoder(ServerboundInspectBlockPacket::decode)
            .consumerMainThread(ServerboundInspectBlockPacket::handle)
            .add();
        CHANNEL.messageBuilder(ClientboundInspectionSnapshotPacket.class, id++)
            .encoder(ClientboundInspectionSnapshotPacket::encode)
            .decoder(ClientboundInspectionSnapshotPacket::decode)
            .consumerMainThread(ClientboundInspectionSnapshotPacket::handle)
            .add();
        CHANNEL.messageBuilder(ClientboundIntegrityProgressPacket.class, id++)
            .encoder(ClientboundIntegrityProgressPacket::encode)
            .decoder(ClientboundIntegrityProgressPacket::decode)
            .consumerMainThread(ClientboundIntegrityProgressPacket::handle)
            .add();
        CHANNEL.messageBuilder(ClientboundImpactOutcomePacket.class, id++)
            .encoder(ClientboundImpactOutcomePacket::encode)
            .decoder(ClientboundImpactOutcomePacket::decode)
            .consumerMainThread(ClientboundImpactOutcomePacket::handle)
            .add();
        CHANNEL.messageBuilder(ClientboundEmbeddedShellsPacket.class, id++)
            .encoder(ClientboundEmbeddedShellsPacket::encode)
            .decoder(ClientboundEmbeddedShellsPacket::decode)
            .consumerMainThread(ClientboundEmbeddedShellsPacket::handle)
            .add();
        CHANNEL.messageBuilder(ServerboundSetLauncherVelocityPacket.class, id++)
            .encoder(ServerboundSetLauncherVelocityPacket::encode)
            .decoder(ServerboundSetLauncherVelocityPacket::decode)
            .consumerMainThread(ServerboundSetLauncherVelocityPacket::handle)
            .add();
    }

    private TBNetwork() {}
}
