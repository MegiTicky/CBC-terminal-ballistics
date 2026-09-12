package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.network.ClientboundTestRayPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side debug renderer for {@code /cbctb testray}.
 * Renders the ray path, tested block AABBs, and hit point for a few seconds
 * after the command is executed.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientTestRayVisuals {
    private static final int DISPLAY_TICKS = 200; // 10 seconds
    private static long expireTick = 0;
    private static Vec3 rayFrom;
    private static Vec3 rayTo;
    private static boolean hasHit;
    private static Vec3 hitLocation;
    private static List<float[]> blockBoxes = new ArrayList<>(); // [minX,Y,Z, maxXYZ]
    private static List<float[]> blockCenters = new ArrayList<>(); // [x,y,z]

    public static void accept(ClientboundTestRayPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        rayFrom = packet.from();
        rayTo = packet.to();
        hasHit = packet.hit();
        hitLocation = packet.hitLocation();
        blockBoxes.clear();
        blockCenters.clear();

        // Unpack block data: 9 doubles per block [minX,minY,minZ, maxX,maxY,maxZ, cx,cy,cz]
        double[] data = packet.blockData();
        int count = packet.blockCount();
        for (int i = 0; i < count; i++) {
            int base = i * 9;
            if (base + 8 >= data.length) break;
            blockBoxes.add(new float[]{
                (float) data[base], (float) data[base + 1], (float) data[base + 2],
                (float) data[base + 3], (float) data[base + 4], (float) data[base + 5]
            });
            blockCenters.add(new float[]{
                (float) data[base + 6], (float) data[base + 7], (float) data[base + 8]
            });
        }

        expireTick = mc.level.getGameTime() + DISPLAY_TICKS;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP.isRenderingPip()) return;
        if (expireTick == 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.level.getGameTime() > expireTick) {
            expireTick = 0;
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer lineBuf = bufferSource.getBuffer(RenderType.lines());

        // Calculate fade alpha (fade out in last 2 seconds)
        long remaining = expireTick - mc.level.getGameTime();
        float alpha = remaining < 40 ? remaining / 40f : 1.0f;

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        // Draw ray line (green = full path, or red if hit)
        float rayR = hasHit ? 1.0f : 0.0f;
        float rayG = hasHit ? 1.0f : 1.0f;
        float rayB = hasHit ? 0.0f : 0.0f;
        drawLine(poseStack, lineBuf, rayFrom, rayTo, rayR, rayG, rayB, alpha);

        // Draw tested block AABBs (yellow wireframe, or green for the hit block)
        for (int i = 0; i < blockBoxes.size(); i++) {
            float[] box = blockBoxes.get(i);
            float[] center = blockCenters.get(i);
            AABB aabb = new AABB(box[0], box[1], box[2], box[3], box[4], box[5]);

            // Check if this is the hit block
            boolean isHitBlock = hasHit && hitLocation != null
                && Math.abs(hitLocation.x - center[0]) < 0.6
                && Math.abs(hitLocation.y - center[1]) < 0.6
                && Math.abs(hitLocation.z - center[2]) < 0.6;

            if (isHitBlock) {
                drawBox(poseStack, lineBuf, aabb, 0.0f, 1.0f, 0.0f, alpha);
            } else {
                drawBox(poseStack, lineBuf, aabb, 1.0f, 1.0f, 0.0f, alpha * 0.6f);
            }
        }

        // Draw hit point (bright green cross)
        if (hasHit && hitLocation != null) {
            double s = 0.15;
            Vec3 h = hitLocation;
            drawLine(poseStack, lineBuf, new Vec3(h.x - s, h.y, h.z), new Vec3(h.x + s, h.y, h.z), 0.0f, 1.0f, 0.0f, alpha);
            drawLine(poseStack, lineBuf, new Vec3(h.x, h.y - s, h.z), new Vec3(h.x, h.y + s, h.z), 0.0f, 1.0f, 0.0f, alpha);
            drawLine(poseStack, lineBuf, new Vec3(h.x, h.y, h.z - s), new Vec3(h.x, h.y, h.z + s), 0.0f, 1.0f, 0.0f, alpha);
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    private static void drawLine(PoseStack poseStack, VertexConsumer buf, Vec3 from, Vec3 to, float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        buf.vertex(pose.pose(), (float) from.x, (float) from.y, (float) from.z)
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
        buf.vertex(pose.pose(), (float) to.x, (float) to.y, (float) to.z)
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
    }

    private static void drawBox(PoseStack poseStack, VertexConsumer buf, AABB box, float r, float g, float b, float a) {
        Vec3 min = new Vec3(box.minX, box.minY, box.minZ);
        Vec3 max = new Vec3(box.maxX, box.maxY, box.maxZ);

        // 12 edges of the box
        // Bottom face
        drawLine(poseStack, buf, new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, min.z), new Vec3(max.x, min.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, max.z), new Vec3(min.x, min.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, min.y, max.z), new Vec3(min.x, min.y, min.z), r, g, b, a);
        // Top face
        drawLine(poseStack, buf, new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, max.y, min.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, max.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, max.y, max.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        // Vertical edges
        drawLine(poseStack, buf, new Vec3(min.x, min.y, min.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, max.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, min.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
    }

    private ClientTestRayVisuals() {}
}
