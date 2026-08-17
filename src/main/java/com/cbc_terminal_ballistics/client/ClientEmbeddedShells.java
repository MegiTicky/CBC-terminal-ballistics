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

    public static void accept(BlockPos pos, List<EmbeddedShell> shells) {
        if (shells.isEmpty()) SHELLS.remove(pos);
        else SHELLS.put(pos.immutable(), List.copyOf(shells));
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && Minecraft.getInstance().level == null) {
            SHELLS.clear();
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
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
        buffers.endBatch();
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
            renderShell(level, pos, poseStack, buffers, shell);
        }
        poseStack.popPose();
    }

    private static void renderShell(Level level, BlockPos pos, PoseStack poseStack,
                                    MultiBufferSource buffers, EmbeddedShell shell) {
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
        poseStack.translate(shell.x() + axis.x * depthOffset,
            shell.y() + axis.y * depthOffset,
            shell.z() + axis.z * depthOffset);
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
