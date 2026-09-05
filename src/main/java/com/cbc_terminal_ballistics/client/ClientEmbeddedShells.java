package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.state.EmbeddedShell;
import com.cbc_terminal_ballistics.util.CBCReflect;
import com.cbc_terminal_ballistics.util.VSCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEmbeddedShells {
    private static final Map<BlockPos, List<EmbeddedShell>> SHELLS = new HashMap<>();
    /** Shells anchored to Create contraption blocks: entity UUID -> local block pos -> shells. */
    private static final Map<java.util.UUID, Map<BlockPos, List<EmbeddedShell>>> CONTRAPTION_SHELLS = new HashMap<>();

    public static void accept(BlockPos pos, List<EmbeddedShell> shells) {
        if (shells.isEmpty()) SHELLS.remove(pos);
        else SHELLS.put(pos.immutable(), List.copyOf(shells));
    }

    public static void acceptContraption(java.util.UUID contraptionUuid, BlockPos localPos, List<EmbeddedShell> shells) {
        Map<BlockPos, List<EmbeddedShell>> byPos = CONTRAPTION_SHELLS.get(contraptionUuid);
        if (shells.isEmpty()) {
            if (byPos != null) {
                byPos.remove(localPos.immutable());
                if (byPos.isEmpty()) CONTRAPTION_SHELLS.remove(contraptionUuid);
            }
            return;
        }
        if (byPos == null) {
            byPos = new HashMap<>();
            CONTRAPTION_SHELLS.put(contraptionUuid, byPos);
        }
        byPos.put(localPos.immutable(), List.copyOf(shells));
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && Minecraft.getInstance().level == null) {
            SHELLS.clear();
            CONTRAPTION_SHELLS.clear();
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP.isRenderingPip()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || SHELLS.isEmpty()) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        List<ShellRender> candidates = new ArrayList<>();
        for (Map.Entry<BlockPos, List<EmbeddedShell>> entry : SHELLS.entrySet()) {
            BlockPos pos = entry.getKey();
            if (mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) continue;
            if (VSCompat.squaredDistanceBetweenInclShips(mc.level, Vec3.atCenterOf(pos), mc.player.position()) > 128 * 128) {
                continue;
            }
            for (EmbeddedShell shell : entry.getValue()) {
                if (shell.visualState() != null || (shell.visualItem() != null && !shell.visualItem().isEmpty())) {
                    candidates.add(new ShellRender(pos, shell));
                }
            }
        }
        candidates.sort(Comparator.comparingLong((ShellRender shell) -> shell.shell().gameTime()).reversed());
        int limit = Math.max(0, TBConfig.MAX_RENDERED_EMBEDDED_SHELLS.get());
        for (int i = 0; i < Math.min(limit, candidates.size()); i++) {
            ShellRender candidate = candidates.get(i);
            renderBlockShells(mc.level, candidate.pos(), List.of(candidate.shell()), poseStack, camera, buffers);
        }

        // --- Create contraption-anchored shells ---
        // The world has air at contraption block positions, so these are anchored to the
        // owning entity and rendered in its live transformed frame (they follow the ship).
        float partialTick = event.getPartialTick();
        List<ContraShellRender> contraCandidates = new ArrayList<>();
        for (Map.Entry<java.util.UUID, Map<BlockPos, List<EmbeddedShell>>> shipEntry : CONTRAPTION_SHELLS.entrySet()) {
            net.minecraft.world.entity.Entity contraption = findContraption(mc.level, shipEntry.getKey());
            if (contraption == null) continue;
            for (Map.Entry<BlockPos, List<EmbeddedShell>> entry : shipEntry.getValue().entrySet()) {
                BlockPos localPos = entry.getKey();
                // Resolve the real hull block state via ContraptionCompat instead of the
                // world query (which is always air for contraption blocks).
                BlockState state = com.cbc_terminal_ballistics.util.ContraptionCompat.blockStateForLocal(contraption, localPos);
                if (state == null || state.isAir()) continue;
                Vec3 worldCenter = com.cbc_terminal_ballistics.util.ContraptionCompat.worldCenterForLocal(contraption, localPos, partialTick);
                if (worldCenter == null || worldCenter.distanceToSqr(mc.player.position()) > 128 * 128) continue;
                for (EmbeddedShell shell : entry.getValue()) {
                    if (shell.visualState() != null || (shell.visualItem() != null && !shell.visualItem().isEmpty())) {
                        contraCandidates.add(new ContraShellRender(contraption, localPos, worldCenter, shell));
                    }
                }
            }
        }
        contraCandidates.sort(Comparator.comparingLong((ContraShellRender shell) -> shell.shell().gameTime()).reversed());
        for (int i = 0; i < Math.min(limit, contraCandidates.size()); i++) {
            ContraShellRender candidate = contraCandidates.get(i);
            renderContraptionBlockShells(candidate.contraption(), candidate.localPos(), candidate.worldCenter(),
                List.of(candidate.shell()), poseStack, camera, buffers, partialTick);
        }
        buffers.endBatch();
    }

    private record ContraShellRender(net.minecraft.world.entity.Entity contraption, BlockPos localPos, Vec3 worldCenter, EmbeddedShell shell) {}

    @javax.annotation.Nullable
    private static net.minecraft.world.entity.Entity findContraption(Level level, java.util.UUID uuid) {
        if (level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
            // ClientLevel has no UUID lookup in 1.20.1; linear scan is fine (contraptions are few).
            for (net.minecraft.world.entity.Entity entity : clientLevel.entitiesForRendering()) {
                if (entity.getUUID().equals(uuid)) return entity;
            }
        }
        return null;
    }

    private static void renderContraptionBlockShells(net.minecraft.world.entity.Entity contraption, BlockPos localPos, Vec3 worldCenter,
                                                     List<EmbeddedShell> shells, PoseStack poseStack, Vec3 camera,
                                                     MultiBufferSource.BufferSource buffers, float partialTick) {
        Matrix4f basis = ClientImpactMarks.contraptionBasisMatrix(contraption, localPos, partialTick, worldCenter);
        if (basis == null) return;
        poseStack.pushPose();
        poseStack.translate(worldCenter.x - camera.x, worldCenter.y - camera.y, worldCenter.z - camera.z);
        poseStack.mulPoseMatrix(basis);
        poseStack.translate(-0.5D, -0.5D, -0.5D);
        for (EmbeddedShell shell : shells) {
            renderShell(contraption.level(), poseStack, buffers, shell, 0.0D);
        }
        poseStack.popPose();
    }

    private static void renderBlockShells(Level level, BlockPos pos, List<EmbeddedShell> shells, PoseStack poseStack,
                                           Vec3 camera, MultiBufferSource.BufferSource buffers) {
        poseStack.pushPose();
        boolean transformedWithShip = level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel
            && VSClientCompat.transformRenderWithShip(clientLevel, pos, poseStack, camera);
        if (!transformedWithShip) {
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        }

        for (EmbeddedShell shell : shells) {
            renderShell(level, poseStack, buffers, shell, 0.0D);
        }
        poseStack.popPose();
    }

    private static void renderShell(Level level, PoseStack poseStack,
                                    MultiBufferSource buffers, EmbeddedShell shell, double baseOffset) {
        BlockState state = shell.visualState();
        ItemStack item = shell.visualItem();
        if (state == null && (item == null || item.isEmpty())) return;

        Vec3 axis = new Vec3(shell.directionX(), shell.directionY(), shell.directionZ());
        if (axis.lengthSqr() < 1.0E-8D) {
            axis = Vec3.atLowerCornerOf(shell.face().getNormal()).scale(-1.0D);
        }
        axis = axis.normalize();

        Vec3 horizontal = new Vec3(axis.x, 0.0D, axis.z);
        poseStack.pushPose();
        double depthOffset = shell.depth() - 0.5D;
        poseStack.translate(shell.x() + baseOffset + axis.x * depthOffset,
            shell.y() + baseOffset + axis.y * depthOffset,
            shell.z() + baseOffset + axis.z * depthOffset);
        if (axis.horizontalDistanceSqr() > 1.0E-4D && Math.abs(axis.y) > 1.0E-2D) {
            poseStack.mulPoseMatrix(new Matrix4f(CBCReflect.facing(axis.reverse(), horizontal.normalize())));
            poseStack.mulPoseMatrix(new Matrix4f(CBCReflect.facing(horizontal.normalize())));
        } else {
            poseStack.mulPoseMatrix(new Matrix4f(CBCReflect.facing(axis)));
        }
        int light = LightTexture.FULL_BRIGHT;
        if (state != null) {
            poseStack.translate(-0.5D, -0.5D, -0.5D);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, poseStack, buffers,
                light, OverlayTexture.NO_OVERLAY);
        } else {
            Minecraft.getInstance().getItemRenderer().renderStatic(item, ItemDisplayContext.NONE,
                light, OverlayTexture.NO_OVERLAY, poseStack, buffers, level, 0);
        }
        poseStack.popPose();
    }

    private ClientEmbeddedShells() {}

    private record ShellRender(BlockPos pos, EmbeddedShell shell) {}
}
