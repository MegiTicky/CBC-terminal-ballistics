package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.util.ContraptionCompat;
import com.cbc_terminal_ballistics.util.CreateInteractiveBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Client-side contraption collision visualizer, driven by {@code /cbctb viz}.
 * Wireframes the exact block geometry the server raycast tests (green), with
 * reference markers: the entity's own position (magenta), Create Interactive's
 * resolved transform position (cyan), and the local axes tripod (RGB) — a gap
 * between any of these and the rendered contraption is the bug, visible directly.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientContraptionViz {
    private static volatile long activeUntilMillis = 0L;

    public static void activate(int seconds) {
        activeUntilMillis = System.currentTimeMillis() + seconds * 1000L;
    }

    private static boolean active() {
        return System.currentTimeMillis() < activeUntilMillis;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!active()) return;
        if (com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP.isRenderingPip()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        float partialTick = event.getPartialTick();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        List<Entity> contraptions = ContraptionCompat.findNearbyContraptionEntities(mc.level, camera, camera);

        if (contraptions.isEmpty()) return;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lineBuf = buffers.getBuffer(RenderType.lines());
        long remaining = activeUntilMillis - System.currentTimeMillis();
        float alpha = Math.max(0.15f, Math.min(1.0f, remaining / 2000f));

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        for (Entity entity : contraptions) {
            Matrix4f basis = ContraptionCompat.localToWorldMatrix(entity, partialTick);
            if (basis == null) continue;
            if (new Vec3(basis.m30(), basis.m31(), basis.m32()).distanceToSqr(camera) > 128 * 128) continue;

            drawAxes(poseStack, lineBuf, basis, alpha);
            drawCross(poseStack, lineBuf, new Vec3(entity.position().x, entity.position().y, entity.position().z), 0.3f, 1.0f, 0.0f, 1.0f, alpha);

            double[] pr = CreateInteractiveBridge.posRotRender(entity, partialTick);
            if (pr != null) {
                Vec3 ciPos = new Vec3(pr[0], pr[1], pr[2]);
                drawCross(poseStack, lineBuf, ciPos, 0.25f, 0.0f, 1.0f, 1.0f, alpha);
            }

            for (BlockPos pos : ContraptionCompat.solidLocalPositions(entity)) {
                drawLocalBox(poseStack, lineBuf, basis, pos, 0.0f, 1.0f, 0.3f, alpha * 0.9f);
            }
        }

        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    /** Local axes tripod at the transform origin: red=X, green=Y, blue=Z. */
    private static void drawAxes(PoseStack poseStack, VertexConsumer buf, Matrix4f basis, float alpha) {
        Vector3f o = basis.transformPosition(new Vector3f(0f, 0f, 0f));
        Vector3f x = basis.transformPosition(new Vector3f(1.5f, 0f, 0f));
        Vector3f y = basis.transformPosition(new Vector3f(0f, 1.5f, 0f));
        Vector3f z = basis.transformPosition(new Vector3f(0f, 0f, 1.5f));
        line(poseStack, buf, new Vec3(o.x, o.y, o.z), new Vec3(x.x, x.y, x.z), 1.0f, 0.2f, 0.2f, alpha);
        line(poseStack, buf, new Vec3(o.x, o.y, o.z), new Vec3(y.x, y.y, y.z), 0.2f, 1.0f, 0.2f, alpha);
        line(poseStack, buf, new Vec3(o.x, o.y, o.z), new Vec3(z.x, z.y, z.z), 0.2f, 0.4f, 1.0f, alpha);
    }

    private static void drawCross(PoseStack poseStack, VertexConsumer buf, Vec3 p, float s, float r, float g, float b, float a) {
        line(poseStack, buf, new Vec3(p.x - s, p.y, p.z), new Vec3(p.x + s, p.y, p.z), r, g, b, a);
        line(poseStack, buf, new Vec3(p.x, p.y - s, p.z), new Vec3(p.x, p.y + s, p.z), r, g, b, a);
        line(poseStack, buf, new Vec3(p.x, p.y, p.z - s), new Vec3(p.x, p.y, p.z + s), r, g, b, a);
    }

    private static void drawLocalBox(PoseStack poseStack, VertexConsumer buf, Matrix4f basis, BlockPos pos,
                                     float r, float g, float b, float a) {
        float x0 = pos.getX(), y0 = pos.getY(), z0 = pos.getZ();
        float x1 = x0 + 1f, y1 = y0 + 1f, z1 = z0 + 1f;
        Vector3f c000 = basis.transformPosition(new Vector3f(x0, y0, z0));
        Vector3f c100 = basis.transformPosition(new Vector3f(x1, y0, z0));
        Vector3f c101 = basis.transformPosition(new Vector3f(x1, y0, z1));
        Vector3f c001 = basis.transformPosition(new Vector3f(x0, y0, z1));
        Vector3f c010 = basis.transformPosition(new Vector3f(x0, y1, z0));
        Vector3f c110 = basis.transformPosition(new Vector3f(x1, y1, z0));
        Vector3f c111 = basis.transformPosition(new Vector3f(x1, y1, z1));
        Vector3f c011 = basis.transformPosition(new Vector3f(x0, y1, z1));
        edge(poseStack, buf, c000, c100, r, g, b, a);
        edge(poseStack, buf, c100, c101, r, g, b, a);
        edge(poseStack, buf, c101, c001, r, g, b, a);
        edge(poseStack, buf, c001, c000, r, g, b, a);
        edge(poseStack, buf, c010, c110, r, g, b, a);
        edge(poseStack, buf, c110, c111, r, g, b, a);
        edge(poseStack, buf, c111, c011, r, g, b, a);
        edge(poseStack, buf, c011, c010, r, g, b, a);
        edge(poseStack, buf, c000, c010, r, g, b, a);
        edge(poseStack, buf, c100, c110, r, g, b, a);
        edge(poseStack, buf, c101, c111, r, g, b, a);
        edge(poseStack, buf, c001, c011, r, g, b, a);
    }

    private static void edge(PoseStack poseStack, VertexConsumer buf, Vector3f a, Vector3f b, float r, float g, float b2, float a2) {
        line(poseStack, buf, new Vec3(a.x, a.y, a.z), new Vec3(b.x, b.y, b.z), r, g, b2, a2);
    }

    private static void line(PoseStack poseStack, VertexConsumer buf, Vec3 from, Vec3 to, float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        buf.vertex(pose.pose(), (float) from.x, (float) from.y, (float) from.z)
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
        buf.vertex(pose.pose(), (float) to.x, (float) to.y, (float) to.z)
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
    }

    private ClientContraptionViz() {}
}
