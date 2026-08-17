package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.debug.TBProjectileSlowdown;
import com.cbc_terminal_ballistics.network.ClientboundImpactMarksPacket;
import com.cbc_terminal_ballistics.network.ClientboundInspectionSnapshotPacket;
import com.cbc_terminal_ballistics.network.ClientboundIntegrityProgressPacket;
import com.cbc_terminal_ballistics.network.ClientboundImpactOutcomePacket;
import com.cbc_terminal_ballistics.network.ClientboundEmbeddedShellsPacket;
import com.cbc_terminal_ballistics.network.ClientboundProjectileSlowdownPacket;
import com.cbc_terminal_ballistics.network.ClientboundShellDebugTogglePacket;
import com.cbc_terminal_ballistics.network.ClientboundSpallConePacket;
import com.cbc_terminal_ballistics.util.CBCReflect;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;

public final class ClientPacketHandlers {
    public static void handleImpactMarks(ClientboundImpactMarksPacket packet) {
        if (Minecraft.getInstance().level != null) {
            ClientImpactMarks.accept(packet.pos(), packet.subLevelId(), packet.marks());
        }
    }

    public static void handleEmbeddedShells(ClientboundEmbeddedShellsPacket packet) {
        if (Minecraft.getInstance().level != null) ClientEmbeddedShells.accept(packet.pos(), packet.subLevelId(), packet.shells());
    }

    public static void handleShellDebugToggle(ClientboundShellDebugTogglePacket packet) {
        ClientEmbeddedShells.setDebugEnabled(packet.enabled());
    }

    public static void handleSpallCone(ClientboundSpallConePacket packet) {
        ClientSpallConeVisuals.accept(packet);
    }

    public static void handleProjectileSlowdown(ClientboundProjectileSlowdownPacket packet) {
        TBProjectileSlowdown.setClientFactor(packet.factor());
    }

    public static void handleInspectionSnapshot(ClientboundInspectionSnapshotPacket packet) {
        if (Minecraft.getInstance().level != null) ClientInspectionSnapshots.accept(packet.snapshot());
    }

    public static void handleIntegrityProgress(ClientboundIntegrityProgressPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.levelRenderer.destroyBlockProgress(packet.pos().hashCode(), packet.pos(), packet.stage());
        }
    }

    public static void handleImpactOutcome(ClientboundImpactOutcomePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.entity.Entity projectile = mc.level.getEntity(packet.entityId());
        if (projectile == null) return;

        projectile.setPos(packet.position());
        projectile.setDeltaMovement(packet.velocity());
        boolean inGround = packet.inGround() && packet.outcome().equals("STOP");
        CBCReflect.setGroundPos(projectile, inGround ? packet.position() : null);
        CBCReflect.setInGround(projectile, inGround);
        if (packet.outcome().equals("PENETRATE")) {
            CBCReflect.setLastPenetratedBlock(projectile, mc.level.getBlockState(packet.blockPos()));
            CBCReflect.setPenetrationTime(projectile, 2);
        } else if (packet.outcome().equals("BOUNCE")) {
            CBCReflect.setLastPenetratedBlock(projectile, Blocks.AIR.defaultBlockState());
            CBCReflect.setPenetrationTime(projectile, 0);
        }
    }

    private ClientPacketHandlers() {}
}
