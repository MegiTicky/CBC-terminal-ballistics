package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws the terminal-ballistics x-ray diagram into the hitcam PiP pass:
 * shell flight path, per-plate outcome boxes, the spall cone and entity hit
 * markers. All geometry is stored ship-local and re-anchored per frame, so a
 * moving vehicle keeps its damage diagram attached.
 *
 * <p>The 1.20.1 level renderer does not rotate the model-view stack for code
 * that runs after it returns, so a nested PiP camera pass is not "in effect"
 * for anything drawn afterwards: the rotation the world pass bakes into its own
 * vertices must be carried by every post-pass draw too. All vertices below are
 * therefore camera-relative with the PiP camera-rotation pose (the same matrix
 * the nested world pass was given) as the per-vertex matrix, projected through
 * the projection that pass installed.
 *
 * <p>{@code RenderType.lines()} cannot be used here (its screen-space line
 * shader degenerates from a constant per-vertex normal, and its render state
 * re-enables the depth test), and 1px GL lines vanish once the full-resolution
 * PiP frame is blitted down into the small window. Every stroke is instead a
 * camera-facing quad whose half-width is sized for a roughly constant width on
 * the displayed PiP window, independent of distance.
 */
public final class ClientTerminalOverlay {
    private static final float[] PENETRATE_COLOR = {0.25F, 1.0F, 0.3F};
    private static final float[] STOP_COLOR = {1.0F, 0.25F, 0.2F};
    private static final float[] BOUNCE_COLOR = {1.0F, 0.72F, 0.15F};
    private static final double PLATE_HALF = 0.62D;
    /** Stroke width on the displayed PiP window, in scaled GUI pixels. */
    private static final double LINE_DISPLAY_PX = 2.8D;
    private static final float FILL_ALPHA = 0.34F;

    /**
     * Per-frame drawing context: the PiP camera rotation pose (used as the
     * per-vertex matrix), the camera position, the camera forward direction and
     * the FOV/screen metrics that turn a constant window-pixel stroke width
     * into the world-space width a segment needs at its distance.
     */
    public static final class Ctx {
        /** Per-vertex matrix: the PiP camera-rotation pose, baking world->view. */
        private final Matrix4f view;
        /** Third row of the view rotation: the camera's view-space Z axis in world space. */
        private final double vzx;
        private final double vzy;
        private final double vzz;
        private final Vec3 cam;
        private final Vec3 fwd;
        private final double tanHalfFov;
        private final double halfFboPx;
        private final double halfPxFbo;

        private Ctx(Matrix4f view, Vec3 cam, Vec3 fwd, double vzx, double vzy, double vzz,
                    double tanHalfFov, double halfFboPx, double halfPxFbo) {
            this.view = view;
            this.cam = cam;
            this.fwd = fwd;
            this.vzx = vzx;
            this.vzy = vzy;
            this.vzz = vzz;
            this.tanHalfFov = tanHalfFov;
            this.halfFboPx = halfFboPx;
            this.halfPxFbo = halfPxFbo;
        }

        public static Ctx of(Minecraft mc, Camera camera, PoseStack viewPose) {
            // The rotation pose maps world space into the camera's view space
            // (column-vector convention), so its third row is the view-space Z
            // axis expressed in world coordinates; the camera looks along -Z,
            // making the negated third row the world-space forward.
            Matrix4f view = new Matrix4f(viewPose.last().pose());
            double vzx = view.m20();
            double vzy = view.m21();
            double vzz = view.m22();
            Vec3 fwd = new Vec3(-vzx, -vzy, -vzz);
            if (fwd.lengthSqr() < 1.0e-8D) fwd = new Vec3(0, 0, 1);
            fwd = fwd.normalize();

            double fboW = mc.getMainRenderTarget().width;
            double fboH = mc.getMainRenderTarget().height;
            // The PiP frame is rendered full-resolution and blitted into a
            // window of the configured display width, so a stroke N window px
            // wide must be N * (fbo px per window px) pixels in the FBO.
            int dispCfg = Mth.clamp(TBConfig.HITCAM_PIP_DISPLAY_WIDTH.get(), 80, 960);
            int disp = Math.max(1, Math.min(dispCfg, Math.max(1, mc.getWindow().getGuiScaledWidth() - 4)));
            double fboPerDisplayPx = Math.max(1.0D, fboW / disp);
            double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0D);
            double halfPxFbo = (LINE_DISPLAY_PX * 0.5D) * fboPerDisplayPx;
            return new Ctx(view, camera.getPosition(), fwd, vzx, vzy, vzz, tanHalf, fboH * 0.5D, halfPxFbo);
        }
    }

    public static void render(Minecraft mc, Camera camera, float partialTick, PoseStack viewPose) {
        if (!ClientTerminalView.active()) return;
        float alpha = fadeAlpha(partialTick);
        if (alpha <= 0.02F) return;
        Ctx ctx = Ctx.of(mc, camera, viewPose);

        // X-ray: draw over the rendered world, never hidden behind it.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        try {
            drawPath(buffer, ctx, partialTick, alpha);
            drawPlates(buffer, ctx, partialTick, alpha);
            drawSpall(buffer, ctx, partialTick, alpha);
            drawEntityHits(buffer, ctx, partialTick, alpha);
        } finally {
            tesselator.end();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
        }
    }

    /**
     * A box drawn as 12 camera-facing strokes with the constant on-window
     * thickness, for the shell bracket and other hitcam visuals.
     */
    public static void drawThickBox(BufferBuilder buffer, Ctx ctx, Vec3 min, Vec3 max,
                                    float r, float g, float b, float a) {
        // Bottom face
        drawThickLine(buffer, ctx, new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z), r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, min.y, min.z), new Vec3(max.x, min.y, max.z), r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, min.y, max.z), new Vec3(min.x, min.y, max.z), r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(min.x, min.y, max.z), new Vec3(min.x, min.y, min.z), r, g, b, a);
        // Top face
        drawThickLine(buffer, ctx, new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, max.y, min.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, max.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(min.x, max.y, max.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        // Vertical edges
        drawThickLine(buffer, ctx, new Vec3(min.x, min.y, min.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, min.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, min.y, max.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(min.x, min.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
    }

    /**
     * One camera-facing stroke quad between two world points. The half-width is
     * derived from the segment's distance so the stroke keeps a constant width
     * in window pixels. The caller owns shader/depth/blend state and the open
     * buffer; this emits four vertices into the current QUADS batch.
     */
    public static void drawThickLine(BufferBuilder buf, Ctx ctx, Vec3 from, Vec3 to,
                                     float r, float g, float b, float a) {
        Vec3 seg = to.subtract(from);
        if (seg.lengthSqr() < 1.0e-10D) return;
        Vec3 perp = seg.cross(ctx.fwd);
        double perpLen = perp.length();
        if (perpLen < 1.0e-8D) return; // collinear with the view axis
        perp = perp.scale(1.0D / perpLen);

        double depth = viewDepth(ctx, from.add(to).scale(0.5D));
        if (depth < 0.06D) return; // entirely behind the camera
        double halfW = Mth.clamp(depth * ctx.tanHalfFov * ctx.halfPxFbo / ctx.halfFboPx, 0.01D, 0.5D);
        perp = perp.scale(halfW);

        vertexWorld(buf, ctx, from.subtract(perp), r, g, b, a);
        vertexWorld(buf, ctx, from.add(perp), r, g, b, a);
        vertexWorld(buf, ctx, to.add(perp), r, g, b, a);
        vertexWorld(buf, ctx, to.subtract(perp), r, g, b, a);
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

    private static void drawPath(BufferBuilder buffer, Ctx ctx, float partialTick, float alpha) {
        List<HitcamSample> path = ClientTerminalView.path();
        if (path.size() < 2) return;
        Vec3 prev = ClientTerminalView.toWorld(
            new Vec3(path.get(0).x(), path.get(0).y(), path.get(0).z()), partialTick);
        for (int i = 1; i < path.size(); i++) {
            HitcamSample sample = path.get(i);
            Vec3 point = ClientTerminalView.toWorld(new Vec3(sample.x(), sample.y(), sample.z()), partialTick);
            drawThickLine(buffer, ctx, prev, point, 0.95F, 0.95F, 1.0F, alpha * 0.85F);
            prev = point;
        }
    }

    private static void drawPlates(BufferBuilder buffer, Ctx ctx, float partialTick, float alpha) {
        List<ClientboundTerminalBallisticsPacket.Plate> plates = ClientTerminalView.plates();
        for (ClientboundTerminalBallisticsPacket.Plate plate : plates) {
            Vec3 pos = ClientTerminalView.toWorld(plate.pos(), partialTick);
            float[] color = colorFor(plate.outcome());
            Vec3 min = pos.subtract(PLATE_HALF, PLATE_HALF, PLATE_HALF);
            Vec3 max = pos.add(PLATE_HALF, PLATE_HALF, PLATE_HALF);
            // Translucent filled faces first, bright outline on top.
            filledBox(buffer, ctx, min, max, color[0], color[1], color[2], alpha * FILL_ALPHA);
            drawThickBox(buffer, ctx, min, max, color[0], color[1], color[2], alpha);
            if (plate.broken()) {
                // Destroyed plate: diagonal cross over the box.
                drawThickLine(buffer, ctx, min, max, color[0], color[1], color[2], alpha * 0.8F);
                drawThickLine(buffer, ctx, new Vec3(max.x, max.y, min.z), new Vec3(min.x, min.y, max.z),
                    color[0], color[1], color[2], alpha * 0.8F);
            }
        }
        // Bright cross at the final impact point.
        if (!plates.isEmpty()) {
            Vec3 last = ClientTerminalView.toWorld(plates.get(plates.size() - 1).pos(), partialTick);
            float[] color = colorFor(plates.get(plates.size() - 1).outcome());
            double s = 0.45D;
            drawThickLine(buffer, ctx, last.add(-s, 0, 0), last.add(s, 0, 0), color[0], color[1], color[2], alpha);
            drawThickLine(buffer, ctx, last.add(0, -s, 0), last.add(0, s, 0), color[0], color[1], color[2], alpha);
            drawThickLine(buffer, ctx, last.add(0, 0, -s), last.add(0, 0, s), color[0], color[1], color[2], alpha);
        }
    }

    private static void drawSpall(BufferBuilder buffer, Ctx ctx, float partialTick, float alpha) {
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
            drawThickLine(buffer, ctx, origin, origin.add(rayDir.scale(len)), 1.0F, 0.62F, 0.1F, alpha * 0.75F);
        }

        for (ClientboundTerminalBallisticsPacket.SpallBlock block : spall.blocks()) {
            Vec3 pos = ClientTerminalView.toWorld(block.pos(), partialTick);
            float a = alpha * (block.broken() ? 0.95F : 0.55F);
            float[] color = block.broken() ? STOP_COLOR : new float[]{0.75F, 0.75F, 0.8F};
            drawThickBox(buffer, ctx, pos.subtract(0.5D, 0.5D, 0.5D), pos.add(0.5D, 0.5D, 0.5D),
                color[0], color[1], color[2], a);
        }
    }

    private static void drawEntityHits(BufferBuilder buffer, Ctx ctx, float partialTick, float alpha) {
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : ClientTerminalView.entities()) {
            Vec3 pos = ClientTerminalView.toWorld(hit.pos(), partialTick);
            float[] color = hit.killed() ? STOP_COLOR : BOUNCE_COLOR;
            Vec3 min = pos.subtract(0.4D, 0.0D, 0.4D);
            Vec3 max = pos.add(0.4D, 1.8D, 0.4D);
            drawThickBox(buffer, ctx, min, max, color[0], color[1], color[2], alpha);
        }
    }

    /** Six translucent faces of a box; fills are surfaces, so overlapping plates do not double-blend. */
    private static void filledBox(BufferBuilder buf, Ctx ctx, Vec3 min, Vec3 max,
                                  float r, float g, float b, float a) {
        double x0 = min.x, y0 = min.y, z0 = min.z;
        double x1 = max.x, y1 = max.y, z1 = max.z;
        // -X face
        quadWorld(buf, ctx, new Vec3(x0, y0, z0), new Vec3(x0, y0, z1), new Vec3(x0, y1, z1), new Vec3(x0, y1, z0), r, g, b, a);
        // +X face
        quadWorld(buf, ctx, new Vec3(x1, y0, z1), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x1, y1, z1), r, g, b, a);
        // Bottom (-Y) and top (+Y)
        quadWorld(buf, ctx, new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x0, y0, z1), r, g, b, a);
        quadWorld(buf, ctx, new Vec3(x0, y1, z1), new Vec3(x1, y1, z1), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0), r, g, b, a);
        // -Z face and +Z face
        quadWorld(buf, ctx, new Vec3(x0, y0, z0), new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x1, y0, z0), r, g, b, a);
        quadWorld(buf, ctx, new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1), new Vec3(x0, y0, z1), r, g, b, a);
    }

    private static void quadWorld(BufferBuilder buf, Ctx ctx, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3,
                                  float r, float g, float b, float a) {
        vertexWorld(buf, ctx, p0, r, g, b, a);
        vertexWorld(buf, ctx, p1, r, g, b, a);
        vertexWorld(buf, ctx, p2, r, g, b, a);
        vertexWorld(buf, ctx, p3, r, g, b, a);
    }

    private static void vertexWorld(BufferBuilder buf, Ctx ctx, Vec3 world,
                                    float r, float g, float b, float a) {
        Vec3 cam = ctx.cam;
        buf.vertex(ctx.view, (float) (world.x - cam.x), (float) (world.y - cam.y), (float) (world.z - cam.z))
            .color(r, g, b, a).endVertex();
    }

    /** View-space depth of a world point (positive in front of the camera). */
    private static double viewDepth(Ctx ctx, Vec3 world) {
        double dx = world.x - ctx.cam.x;
        double dy = world.y - ctx.cam.y;
        double dz = world.z - ctx.cam.z;
        return -(ctx.vzx * dx + ctx.vzy * dy + ctx.vzz * dz);
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

    private ClientTerminalOverlay() {}
}
