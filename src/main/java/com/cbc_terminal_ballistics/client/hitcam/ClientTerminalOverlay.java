package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws the terminal-ballistics x-ray diagram into the hitcam PiP pass:
 * shell flight path, per-plate outcome boxes, the spall cone and entity hit
 * markers. All geometry is stored ship-local and re-anchored per frame, so a
 * moving vehicle keeps its damage diagram attached.
 */
public final class ClientTerminalOverlay {
    private static final float[] PENETRATE_COLOR = {0.25F, 1.0F, 0.3F};
    private static final float[] STOP_COLOR = {1.0F, 0.25F, 0.2F};
    private static final float[] BOUNCE_COLOR = {1.0F, 0.72F, 0.15F};
    private static final double PLATE_HALF = 0.62D;

    public static void render(Minecraft mc, Camera camera, float partialTick) {
        if (!ClientTerminalView.active()) return;
        float alpha = fadeAlpha(partialTick);
        if (alpha <= 0.02F) return;

        // X-ray diagram: draw over the rendered world, never hidden behind it.
        // Wireframe geometry hugging block faces would otherwise be depth-tested
        // away by the very plates it describes.
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        try {
            Vec3 cam = camera.getPosition();
            PoseStack poseStack = new PoseStack();
            poseStack.translate(-cam.x, -cam.y, -cam.z);
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());

            drawPath(lines, poseStack, cam, partialTick, alpha);
            drawPlates(lines, poseStack, cam, partialTick, alpha);
            drawSpall(lines, poseStack, cam, partialTick, alpha);
            drawEntityHits(lines, poseStack, cam, partialTick, alpha);

            buffers.endBatch(RenderType.lines());
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        }
    }

    private static float fadeAlpha(float partialTick) {
        int duration = ClientTerminalView.duration();
        float age = ClientTerminalView.age(partialTick);
        float alpha = 1.0F;
        // Short fade-in so the diagram does not pop in mid-turn.
        if (age < 12.0F) alpha = Math.min(alpha, age / 12.0F);
        float remaining = duration - age;
        alpha = Math.min(alpha, Mth.clamp(remaining / 20.0F, 0.0F, 1.0F));
        return alpha;
    }

    private static void drawPath(VertexConsumer lines, PoseStack poseStack, Vec3 cam, float partialTick, float alpha) {
        List<HitcamSample> path = ClientTerminalView.path();
        if (path.size() < 2) return;
        Vec3 prev = ClientTerminalView.toWorld(
            new Vec3(path.get(0).x(), path.get(0).y(), path.get(0).z()), partialTick);
        for (int i = 1; i < path.size(); i++) {
            HitcamSample sample = path.get(i);
            Vec3 point = ClientTerminalView.toWorld(new Vec3(sample.x(), sample.y(), sample.z()), partialTick);
            drawLine(lines, poseStack, cam, prev, point, 0.95F, 0.95F, 1.0F, alpha * 0.85F);
            prev = point;
        }
    }

    private static void drawPlates(VertexConsumer lines, PoseStack poseStack, Vec3 cam, float partialTick, float alpha) {
        List<ClientboundTerminalBallisticsPacket.Plate> plates = ClientTerminalView.plates();
        for (ClientboundTerminalBallisticsPacket.Plate plate : plates) {
            Vec3 pos = ClientTerminalView.toWorld(plate.pos(), partialTick);
            float[] color = colorFor(plate.outcome());
            drawBox(lines, poseStack, cam, pos.subtract(PLATE_HALF, PLATE_HALF, PLATE_HALF), pos.add(PLATE_HALF, PLATE_HALF, PLATE_HALF),
                color[0], color[1], color[2], alpha);
            if (plate.broken()) {
                // Destroyed plate: diagonal cross over the box face.
                drawLine(lines, poseStack, cam, pos.subtract(PLATE_HALF, PLATE_HALF, PLATE_HALF), pos.add(PLATE_HALF, PLATE_HALF, PLATE_HALF),
                    color[0], color[1], color[2], alpha * 0.8F);
                drawLine(lines, poseStack, cam, pos.add(PLATE_HALF, PLATE_HALF, -PLATE_HALF), pos.add(-PLATE_HALF, -PLATE_HALF, PLATE_HALF),
                    color[0], color[1], color[2], alpha * 0.8F);
            }
        }
        // Bright cross at the final impact point.
        if (!plates.isEmpty()) {
            Vec3 last = ClientTerminalView.toWorld(plates.get(plates.size() - 1).pos(), partialTick);
            float[] color = colorFor(plates.get(plates.size() - 1).outcome());
            double s = 0.45D;
            drawLine(lines, poseStack, cam, last.add(-s, 0, 0), last.add(s, 0, 0), color[0], color[1], color[2], alpha);
            drawLine(lines, poseStack, cam, last.add(0, -s, 0), last.add(0, s, 0), color[0], color[1], color[2], alpha);
            drawLine(lines, poseStack, cam, last.add(0, 0, -s), last.add(0, 0, s), color[0], color[1], color[2], alpha);
        }
    }

    private static void drawSpall(VertexConsumer lines, PoseStack poseStack, Vec3 cam, float partialTick, float alpha) {
        ClientboundTerminalBallisticsPacket.Spall spall = ClientTerminalView.spall();
        if (spall == null || spall.fragments() <= 0) return;
        Vec3 origin = ClientTerminalView.toWorld(spall.origin(), partialTick);
        Vec3 dir = spall.dir();
        if (dir.lengthSqr() < 1.0e-8D) return;
        dir = dir.normalize();

        // Deterministic golden-spiral cone sample, matching the server's spall shape.
        int rays = Math.min(12, Math.max(3, spall.fragments() / 2));
        double coneCos = Mth.clamp(spall.coneCos(), 0.05D, 0.999D);
        for (int i = 0; i < rays; i++) {
            Vec3 rayDir = fragmentDirection(dir, i, rays, coneCos);
            double len = Math.min(spall.range(), 6.0D) * (0.6D + 0.4D * (i % 3) / 2.0D);
            drawLine(lines, poseStack, cam, origin, origin.add(rayDir.scale(len)), 1.0F, 0.62F, 0.1F, alpha * 0.75F);
        }

        for (ClientboundTerminalBallisticsPacket.SpallBlock block : spall.blocks()) {
            Vec3 pos = ClientTerminalView.toWorld(block.pos(), partialTick);
            float a = alpha * (block.broken() ? 0.95F : 0.55F);
            float[] color = block.broken() ? STOP_COLOR : new float[]{0.75F, 0.75F, 0.8F};
            drawBox(lines, poseStack, cam, pos.subtract(0.5D, 0.5D, 0.5D), pos.add(0.5D, 0.5D, 0.5D),
                color[0], color[1], color[2], a);
        }
    }

    private static void drawEntityHits(VertexConsumer lines, PoseStack poseStack, Vec3 cam, float partialTick, float alpha) {
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : ClientTerminalView.entities()) {
            Vec3 pos = ClientTerminalView.toWorld(hit.pos(), partialTick);
            float[] color = hit.killed() ? STOP_COLOR : BOUNCE_COLOR;
            Vec3 min = pos.subtract(0.4D, 0.0D, 0.4D);
            Vec3 max = pos.add(0.4D, 1.8D, 0.4D);
            drawBox(lines, poseStack, cam, min, max, color[0], color[1], color[2], alpha);
        }
    }

    private static float[] colorFor(String outcome) {
        return switch (outcome) {
            case "PENETRATE" -> PENETRATE_COLOR;
            case "BOUNCE" -> BOUNCE_COLOR;
            default -> STOP_COLOR;
        };
    }

    private static Vec3 fragmentDirection(Vec3 forward, int index, int count, double coneCos) {
        Vec3 f = forward.normalize();
        Vec3 up = Math.abs(f.y) < 0.92D ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 right = f.cross(up).normalize();
        Vec3 orthoUp = right.cross(f).normalize();
        double golden = Math.PI * (3.0D - Math.sqrt(5.0D));
        double t = (index + 0.5D) / count;
        double cos = 1.0D - (1.0D - coneCos) * t * t;
        double sin = Math.sqrt(Math.max(0.0D, 1.0D - cos * cos));
        double phi = index * golden;
        return f.scale(cos).add(right.scale(Math.cos(phi) * sin)).add(orthoUp.scale(Math.sin(phi) * sin)).normalize();
    }

    private static void drawLine(VertexConsumer buf, PoseStack poseStack, Vec3 cam, Vec3 from, Vec3 to,
                                 float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        buf.vertex(pose.pose(), (float) (from.x - cam.x), (float) (from.y - cam.y), (float) (from.z - cam.z))
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
        buf.vertex(pose.pose(), (float) (to.x - cam.x), (float) (to.y - cam.y), (float) (to.z - cam.z))
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
    }

    private static void drawBox(VertexConsumer buf, PoseStack poseStack, Vec3 cam, Vec3 min, Vec3 max,
                                float r, float g, float b, float a) {
        // Bottom face
        drawLine(buf, poseStack, cam, new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z), r, g, b, a);
        drawLine(buf, poseStack, cam, new Vec3(max.x, min.y, min.z), new Vec3(max.x, min.y, max.z), r, g, b, a);
        drawLine(buf, poseStack, cam, new Vec3(max.x, min.y, max.z), new Vec3(min.x, min.y, max.z), r, g, b, a);
        drawLine(buf, poseStack, cam, new Vec3(min.x, min.y, max.z), new Vec3(min.x, min.y, min.z), r, g, b, a);
        // Top face
        drawLine(buf, poseStack, cam, new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawLine(buf, poseStack, cam, new Vec3(max.x, max.y, min.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawLine(buf, poseStack, cam, new Vec3(max.x, max.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
        drawLine(buf, poseStack, cam, new Vec3(min.x, max.y, max.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        // Vertical edges
        drawLine(buf, poseStack, cam, new Vec3(min.x, min.y, min.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        drawLine(buf, poseStack, cam, new Vec3(max.x, min.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawLine(buf, poseStack, cam, new Vec3(max.x, min.y, max.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawLine(buf, poseStack, cam, new Vec3(min.x, min.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
    }

    private ClientTerminalOverlay() {}
}
