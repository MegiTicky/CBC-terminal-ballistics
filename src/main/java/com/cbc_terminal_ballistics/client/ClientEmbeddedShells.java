package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.state.EmbeddedShell;
import com.cbc_terminal_ballistics.util.VSCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.HashMap;
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
        VertexConsumer buffer = buffers.getBuffer(RenderType.lightning());
        for (Map.Entry<BlockPos, List<EmbeddedShell>> entry : SHELLS.entrySet()) {
            BlockPos pos = entry.getKey();
            if (mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) continue;
            if (VSCompat.squaredDistanceBetweenInclShips(mc.level, Vec3.atCenterOf(pos), mc.player.position()) > 128 * 128) {
                continue;
            }
            renderBlockShells(mc.level, pos, entry.getValue(), poseStack, camera, buffer);
        }
        buffers.endBatch(RenderType.lightning());
    }

    private static void renderBlockShells(Level level, BlockPos pos, List<EmbeddedShell> shells, PoseStack poseStack,
                                           Vec3 camera, VertexConsumer buffer) {
        poseStack.pushPose();
        boolean transformedWithShip = level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel
            && VSClientCompat.transformRenderWithShip(clientLevel, pos, poseStack, camera);
        if (!transformedWithShip) {
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        }

        Matrix4f matrix = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        for (EmbeddedShell shell : shells) {
            renderShell(buffer, matrix, normal, shell);
        }
        poseStack.popPose();
    }

    private static void renderShell(VertexConsumer buffer, Matrix4f matrix, Matrix3f normal, EmbeddedShell shell) {
        Vec3 axis = new Vec3(shell.directionX(), shell.directionY(), shell.directionZ());
        if (axis.lengthSqr() < 1.0E-8D) {
            axis = Vec3.atLowerCornerOf(shell.face().getNormal()).scale(-1.0D);
        }
        axis = axis.normalize();

        Vec3 faceNormal = Vec3.atLowerCornerOf(shell.face().getNormal());
        Vec3 center = new Vec3(shell.x(), shell.y(), shell.z()).add(faceNormal.scale(0.004D));
        Vec3 reference = Math.abs(axis.y) < 0.9D ? new Vec3(0.0D, 1.0D, 0.0D) : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 side = axis.cross(reference);
        if (side.lengthSqr() < 1.0E-8D) return;
        side = side.normalize();
        Vec3 up = side.cross(axis).normalize();

        Dimensions dimensions = dimensions(shell.caliber());
        Vec3 tail = center.subtract(axis.scale(dimensions.visibleTail()));
        Vec3 nose = center.add(axis.scale(dimensions.embeddedDepth()));
        int segments = 8;
        float[] bodyColor = color(shell.caliber(), false);
        float[] noseColor = color(shell.caliber(), true);

        for (int i = 0; i < segments; i++) {
            double a0 = (Math.PI * 2.0D * i) / segments;
            double a1 = (Math.PI * 2.0D * (i + 1)) / segments;
            Vec3 r0 = side.scale(Math.cos(a0) * dimensions.radius()).add(up.scale(Math.sin(a0) * dimensions.radius()));
            Vec3 r1 = side.scale(Math.cos(a1) * dimensions.radius()).add(up.scale(Math.sin(a1) * dimensions.radius()));
            Vec3 normal0 = r0.normalize();
            Vec3 normal1 = r1.normalize();
            quad(buffer, matrix, normal, tail.add(r0), tail.add(r1), nose.add(r1), nose.add(r0),
                bodyColor[0], bodyColor[1], bodyColor[2], 1.0F, normal0, normal1);
        }

        Vec3 noseTip = nose.add(axis.scale(dimensions.noseLength()));
        for (int i = 0; i < segments; i++) {
            double a0 = (Math.PI * 2.0D * i) / segments;
            double a1 = (Math.PI * 2.0D * (i + 1)) / segments;
            Vec3 r0 = side.scale(Math.cos(a0) * dimensions.radius()).add(up.scale(Math.sin(a0) * dimensions.radius()));
            Vec3 r1 = side.scale(Math.cos(a1) * dimensions.radius()).add(up.scale(Math.sin(a1) * dimensions.radius()));
            Vec3 n0 = r0.normalize();
            Vec3 n1 = r1.normalize();
            quad(buffer, matrix, normal, nose.add(r0), nose.add(r1), noseTip, noseTip,
                noseColor[0], noseColor[1], noseColor[2], 1.0F, n0, n1);
        }

        cap(buffer, matrix, normal, tail, axis.scale(-1.0D), side, up, dimensions.radius(), bodyColor);
    }

    private static void quad(VertexConsumer buffer, Matrix4f matrix, Matrix3f normal, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             float red, float green, float blue, float alpha, Vec3 normalA, Vec3 normalB) {
        vertex(buffer, matrix, normal, a, red, green, blue, alpha, normalA);
        vertex(buffer, matrix, normal, b, red, green, blue, alpha, normalB);
        vertex(buffer, matrix, normal, c, red, green, blue, alpha, normalB);
        vertex(buffer, matrix, normal, d, red, green, blue, alpha, normalA);
    }

    private static void cap(VertexConsumer buffer, Matrix4f matrix, Matrix3f normal, Vec3 center, Vec3 capNormal,
                            Vec3 side, Vec3 up, double radius, float[] color) {
        int segments = 8;
        for (int i = 0; i < segments; i++) {
            double a0 = (Math.PI * 2.0D * i) / segments;
            double a1 = (Math.PI * 2.0D * (i + 1)) / segments;
            Vec3 a = center.add(side.scale(Math.cos(a0) * radius)).add(up.scale(Math.sin(a0) * radius));
            Vec3 b = center.add(side.scale(Math.cos(a1) * radius)).add(up.scale(Math.sin(a1) * radius));
            vertex(buffer, matrix, normal, center, color[0], color[1], color[2], 1.0F, capNormal);
            vertex(buffer, matrix, normal, b, color[0], color[1], color[2], 1.0F, capNormal);
            vertex(buffer, matrix, normal, a, color[0], color[1], color[2], 1.0F, capNormal);
        }
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Matrix3f normal, Vec3 pos,
                               float red, float green, float blue, float alpha, Vec3 vertexNormal) {
        buffer.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
            .color(red, green, blue, alpha)
            .uv(0.0F, 0.0F)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(0x00F000F0)
            .normal(normal, (float) vertexNormal.x, (float) vertexNormal.y, (float) vertexNormal.z)
            .endVertex();
    }

    private static Dimensions dimensions(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> new Dimensions(0.045D, 0.22D, 0.16D, 0.035D);
            case HEAVY_AUTOCANNON, SMALL -> new Dimensions(0.065D, 0.30D, 0.22D, 0.045D);
            case SMALL_MEDIUM -> new Dimensions(0.085D, 0.38D, 0.28D, 0.055D);
            case MEDIUM -> new Dimensions(0.11D, 0.48D, 0.34D, 0.07D);
            case BIG -> new Dimensions(0.145D, 0.62D, 0.43D, 0.09D);
        };
    }

    private static float[] color(TBCaliber caliber, boolean nose) {
        if (nose) return new float[] {0.82F, 0.28F, 0.06F};
        return switch (caliber) {
            case AUTOCANNON -> new float[] {0.18F, 0.20F, 0.21F};
            case HEAVY_AUTOCANNON, SMALL -> new float[] {0.24F, 0.26F, 0.27F};
            case SMALL_MEDIUM -> new float[] {0.30F, 0.31F, 0.29F};
            case MEDIUM -> new float[] {0.34F, 0.32F, 0.25F};
            case BIG -> new float[] {0.39F, 0.34F, 0.22F};
        };
    }

    private record Dimensions(double radius, double visibleTail, double embeddedDepth, double noseLength) {}

    private ClientEmbeddedShells() {}
}
