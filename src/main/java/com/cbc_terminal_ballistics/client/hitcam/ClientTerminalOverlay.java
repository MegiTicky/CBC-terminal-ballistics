package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
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
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;

import java.util.ArrayList;
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
    private static final float[] SPALL_BROKEN_COLOR = {1.0F, 0.3F, 0.12F};
    private static final float[] SPALL_INTERNAL_COLOR = {1.0F, 0.55F, 0.1F};
    /** Default stroke width on the displayed PiP window, in scaled GUI pixels. */
    private static final double LINE_DISPLAY_PX = 2.8D;
    private static final float FILL_ALPHA = 0.34F;
    /** Tolerance for treating a voxel-shape box as the full block. */
    private static final float SHAPE_EPSILON = 1.0e-3F;

    // Stroke thicknesses are live-editable client config (GUI px on the PiP
    // window); read per use — the render path is a few dozen calls per frame.
    private static double pathPx() {
        return Mth.clamp(TBConfig.XRAY_PATH_THICKNESS.get(), 0.5D, 8.0D);
    }

    private static double platePx() {
        return Mth.clamp(TBConfig.XRAY_PLATE_THICKNESS.get(), 0.5D, 8.0D);
    }

    private static double spallPx() {
        return Mth.clamp(TBConfig.XRAY_SPALL_THICKNESS.get(), 0.5D, 8.0D);
    }

    private static double markerPx() {
        return Mth.clamp(TBConfig.XRAY_MARKER_SIZE.get(), 0.5D, 12.0D);
    }

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
        private final double fboPerDisplayPx;

        private Ctx(Matrix4f view, Vec3 cam, Vec3 fwd, double vzx, double vzy, double vzz,
                    double tanHalfFov, double halfFboPx, double fboPerDisplayPx) {
            this.view = view;
            this.cam = cam;
            this.fwd = fwd;
            this.vzx = vzx;
            this.vzy = vzy;
            this.vzz = vzz;
            this.tanHalfFov = tanHalfFov;
            this.halfFboPx = halfFboPx;
            this.fboPerDisplayPx = fboPerDisplayPx;
        }

        public static Ctx of(Minecraft mc, Camera camera, PoseStack viewPose) {
            // The rotation pose maps world space into the camera's view space
            // (column-vector convention), so its third ROW is the view-space Z
            // axis expressed in world coordinates; the camera looks along -Z,
            // making the negated third row the world-space forward. JOML's
            // mXY() is column X, row Y — the row is (m02, m12, m22). Reading
            // the third column here transposed the vector and made strokes
            // flip direction with the camera azimuth (invisible x-ray for
            // shots whose sweep crossed ±Z; visible for ±X).
            Matrix4f view = new Matrix4f(viewPose.last().pose());
            double vzx = view.m02();
            double vzy = view.m12();
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
            return new Ctx(view, camera.getPosition(), fwd, vzx, vzy, vzz, tanHalf, fboH * 0.5D, fboPerDisplayPx);
        }

        /**
         * World-space half-width that renders as {@code displayPxHalfWidth}
         * pixels on the displayed PiP window for geometry at view depth
         * {@code depth} — keeps strokes/dots a constant on-window size.
         */
        public double worldHalfWidth(double depth, double displayPxHalfWidth) {
            return depth * tanHalfFov * (displayPxHalfWidth * fboPerDisplayPx) / halfFboPx;
        }
    }

    public static void render(Minecraft mc, Camera camera, float partialTick, PoseStack viewPose) {
        if (!ClientTerminalView.active()) return;
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
            // Draw every actor on the stage with its OWN timeline: the primary
            // first (camera framing content), then each merged burst shell as
            // its fly-in and damage beats play. Finished actors keep their
            // content on screen so the diagram accumulates, WT style.
            float stageAge = ClientTerminalView.age(partialTick);
            for (ClientTerminalView.Actor actor : ClientTerminalView.actorsView()) {
                if (stageAge < actor.startTick) continue; // fly-in not started yet
                ClientTerminalView.Timeline tl = ClientTerminalView.actorSnapshot(actor, partialTick);
                if (tl == null || tl.fade <= 0.02F) continue;
                drawPath(buffer, ctx, actor, tl, partialTick);
                drawPlates(buffer, ctx, actor, tl, partialTick);
                drawSpall(buffer, ctx, actor, tl, partialTick);
                drawEntityHits(buffer, ctx, actor, tl, partialTick);
                drawBlast(buffer, ctx, actor, tl, partialTick);
            }
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
        drawThickBox(buffer, ctx, min, max, LINE_DISPLAY_PX, r, g, b, a);
    }

    /** A box drawn as 12 camera-facing strokes at the given on-window width. */
    public static void drawThickBox(BufferBuilder buffer, Ctx ctx, Vec3 min, Vec3 max, double widthPx,
                                    float r, float g, float b, float a) {
        // Bottom face
        drawThickLine(buffer, ctx, new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z), widthPx, r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, min.y, min.z), new Vec3(max.x, min.y, max.z), widthPx, r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, min.y, max.z), new Vec3(min.x, min.y, max.z), widthPx, r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(min.x, min.y, max.z), new Vec3(min.x, min.y, min.z), widthPx, r, g, b, a);
        // Top face
        drawThickLine(buffer, ctx, new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z), widthPx, r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, max.y, min.z), new Vec3(max.x, max.y, max.z), widthPx, r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, max.y, max.z), new Vec3(min.x, max.y, max.z), widthPx, r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(min.x, max.y, max.z), new Vec3(min.x, max.y, min.z), widthPx, r, g, b, a);
        // Vertical edges
        drawThickLine(buffer, ctx, new Vec3(min.x, min.y, min.z), new Vec3(min.x, max.y, min.z), widthPx, r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, min.y, min.z), new Vec3(max.x, max.y, min.z), widthPx, r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(max.x, min.y, max.z), new Vec3(max.x, max.y, max.z), widthPx, r, g, b, a);
        drawThickLine(buffer, ctx, new Vec3(min.x, min.y, max.z), new Vec3(min.x, max.y, max.z), widthPx, r, g, b, a);
    }

    /**
     * One camera-facing stroke quad between two world points at the default
     * stroke width. The caller owns shader/depth/blend state and the open
     * buffer; this emits four vertices into the current QUADS batch.
     */
    public static void drawThickLine(BufferBuilder buf, Ctx ctx, Vec3 from, Vec3 to,
                                     float r, float g, float b, float a) {
        drawThickLine(buf, ctx, from, to, LINE_DISPLAY_PX, r, g, b, a);
    }

    /**
     * One camera-facing stroke quad between two world points, half-width sized
     * for a constant {@code widthPx} width on the displayed PiP window.
     */
    public static void drawThickLine(BufferBuilder buf, Ctx ctx, Vec3 from, Vec3 to, double widthPx,
                                     float r, float g, float b, float a) {
        Vec3 seg = to.subtract(from);
        if (seg.lengthSqr() < 1.0e-10D) return;
        Vec3 perp = seg.cross(ctx.fwd);
        double perpLen = perp.length();
        if (perpLen < 1.0e-8D) return; // collinear with the view axis
        perp = perp.scale(1.0D / perpLen);

        double depth = viewDepth(ctx, from.add(to).scale(0.5D));
        if (depth < 0.06D) return; // entirely behind the camera
        double halfW = Mth.clamp(ctx.worldHalfWidth(depth, widthPx * 0.5D), 0.01D, 0.5D);
        perp = perp.scale(halfW);

        vertexWorld(buf, ctx, from.subtract(perp), r, g, b, a);
        vertexWorld(buf, ctx, from.add(perp), r, g, b, a);
        vertexWorld(buf, ctx, to.add(perp), r, g, b, a);
        vertexWorld(buf, ctx, to.subtract(perp), r, g, b, a);
    }

    private static void drawPath(BufferBuilder buffer, Ctx ctx, ClientTerminalView.Actor actor,
                                 ClientTerminalView.Timeline tl, float partialTick) {
        int count = ClientTerminalView.actorPathCount(actor);
        if (count < 2) return;
        Vec3 prev = ClientTerminalView.toWorld(ClientTerminalView.actorPathPoint(actor, 0), partialTick);
        for (int i = 1; i < count; i++) {
            Vec3 point = ClientTerminalView.toWorld(ClientTerminalView.actorPathPoint(actor, i), partialTick);
            if (ClientTerminalView.actorPathFraction(actor, i) <= tl.shellProgress) {
                // Already flown: bright trail.
                drawThickLine(buffer, ctx, prev, point, pathPx(), 0.95F, 0.95F, 1.0F, tl.fade * 0.9F);
            } else {
                // Segment the shell is currently on: bright up to the shell.
                if (tl.shellPos != null && ClientTerminalView.actorPathFraction(actor, i - 1) < tl.shellProgress) {
                    drawThickLine(buffer, ctx, prev, tl.shellPos, pathPx(), 0.95F, 0.95F, 1.0F, tl.fade * 0.9F);
                }
                // Still ahead: faint full path so the trajectory reads.
                drawThickLine(buffer, ctx, prev, point, pathPx(), 0.8F, 0.85F, 1.0F, tl.fade * 0.22F);
            }
            prev = point;
        }
    }

    private static void drawPlates(BufferBuilder buffer, Ctx ctx, ClientTerminalView.Actor actor,
                                   ClientTerminalView.Timeline tl, float partialTick) {
        List<ClientboundTerminalBallisticsPacket.Plate> plates = actor.packet.plates();
        for (int i = 0; i < plates.size(); i++) {
            ClientboundTerminalBallisticsPacket.Plate plate = plates.get(i);
            double appear = tl.plateAppear != null && i < tl.plateAppear.length ? tl.plateAppear[i] : 0.0D;
            if (tl.age < appear) continue; // not struck yet in the replay
            float[] color = colorFor(plate.outcome());
            float flash = tl.plateFlash(i);
            float alpha = Math.min(1.0F, tl.fade * (1.0F + flash * 0.9F));
            // The highlight hugs the struck block's real voxel shape, expressed
            // in the anchor's local space and pushed through the same per-frame
            // transform as the stored center — so it rotates with the ship and
            // matches slabs/stairs/copycats instead of a world-aligned cube.
            float[][] localBoxes = plateLocalBoxes(plate);
            boolean fullCube;
            Vec3[][] boxes;
            if (localBoxes.length == 0) {
                fullCube = true;
                boxes = new Vec3[][]{cubeCorners(plate.pos(), partialTick)};
            } else {
                BlockPos lpos = floorPos(plate.pos());
                fullCube = localBoxes.length == 1 && isFullCubeBox(localBoxes[0]);
                boxes = new Vec3[localBoxes.length][];
                for (int b = 0; b < localBoxes.length; b++) {
                    boxes[b] = boxCorners(lpos, localBoxes[b], partialTick);
                }
            }
            if (fullCube) {
                filledCorners(buffer, ctx, boxes[0], color[0], color[1], color[2], alpha * FILL_ALPHA);
            }
            for (Vec3[] c : boxes) {
                drawThickCorners(buffer, ctx, c, platePx(), color[0], color[1], color[2], alpha);
            }
            if (plate.broken()) {
                // Destroyed plate: diagonal cross over the shape's bounds.
                Vec3[] c = boxes[0];
                drawThickLine(buffer, ctx, c[0], c[6], platePx(), color[0], color[1], color[2], alpha * 0.8F);
                drawThickLine(buffer, ctx, c[5], c[3], platePx(), color[0], color[1], color[2], alpha * 0.8F);
            }
        }
        // Bright cross at the final impact point, once reached. Only the
        // primary draws it — merged burst shells already share the diagram and
        // their crosses would pile up around the same spot.
        if (actor.primary && !plates.isEmpty()) {
            double lastAppear = tl.plateAppear != null && tl.plateAppear.length > 0
                ? tl.plateAppear[tl.plateAppear.length - 1] : 0.0D;
            if (tl.age >= lastAppear) {
                Vec3 last = ClientTerminalView.toWorld(plates.get(plates.size() - 1).pos(), partialTick);
                float[] color = colorFor(plates.get(plates.size() - 1).outcome());
                double s = Mth.clamp(TBConfig.XRAY_IMPACT_CROSS_SIZE.get(), 0.1D, 1.5D);
                double w = Mth.clamp(TBConfig.XRAY_IMPACT_CROSS_THICKNESS.get(), 0.5D, 8.0D);
                drawThickLine(buffer, ctx, last.add(-s, 0, 0), last.add(s, 0, 0), w, color[0], color[1], color[2], tl.fade);
                drawThickLine(buffer, ctx, last.add(0, -s, 0), last.add(0, s, 0), w, color[0], color[1], color[2], tl.fade);
                drawThickLine(buffer, ctx, last.add(0, 0, -s), last.add(0, 0, s), w, color[0], color[1], color[2], tl.fade);
            }
        }
    }

    /**
     * Corner index layout of one axis-aligned box: bottom ring 0-1-2-3, top
     * ring 4-5-6-7 (same x/z order), so the outline edges are 0-1-2-3-0 /
     * 4-5-6-7-4 / verticals 0-4,1-5,2-6,3-7, and the broken-plate cross runs
     * 0-6 and 5-3.
     */
    private static final int[][] BOX_EDGES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    /**
     * Local-space voxel-shape boxes (block-relative 0..1) of the plate. The
     * server-captured boxes come first — copycat and framed blocks derive
     * their shape from their block entity, which the client usually cannot
     * see. Fallback: decode the state and query its shape with whatever
     * context the client can offer; empty array = full cube.
     */
    private static float[][] plateLocalBoxes(ClientboundTerminalBallisticsPacket.Plate plate) {
        if (plate.shapeBoxes() != null && plate.shapeBoxes().length > 0) {
            return plate.shapeBoxes();
        }
        if (plate.blockStateId() < 0) return new float[0][];
        BlockState state = Block.stateById(plate.blockStateId());
        if (state == null) return new float[0][];
        try {
            List<float[]> boxes = new ArrayList<>();
            shapeFor(state, floorPos(plate.pos())).forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
                if (boxes.size() < 8) {
                    boxes.add(new float[]{(float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1});
                }
            });
            return boxes.toArray(new float[0][]);
        } catch (Throwable ignored) {
            return new float[0][];
        }
    }

    /**
     * Block-entity-dependent shapes (copycat/framed) need a real level and
     * position; try the client's own level first — it holds ship blocks at
     * shipyard coordinates on VS builds that expose them — then a context-free
     * lookup.
     */
    private static VoxelShape shapeFor(BlockState state, BlockPos lpos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            try {
                return state.getShape(mc.level, lpos);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static BlockPos floorPos(Vec3 center) {
        return new BlockPos(Mth.floor(center.x), Mth.floor(center.y), Mth.floor(center.z));
    }

    /** True when one box spans the whole block — only true cubes get the translucent fill. */
    private static boolean isFullCubeBox(float[] box) {
        return box[0] <= SHAPE_EPSILON && box[1] <= SHAPE_EPSILON && box[2] <= SHAPE_EPSILON
            && box[3] >= 1.0F - SHAPE_EPSILON && box[4] >= 1.0F - SHAPE_EPSILON && box[5] >= 1.0F - SHAPE_EPSILON;
    }

    /** World-space corners of one local box, each re-anchored through the vehicle transform. */
    private static Vec3[] boxCorners(BlockPos lpos, float[] box, float partialTick) {
        double x0 = lpos.getX() + box[0], y0 = lpos.getY() + box[1], z0 = lpos.getZ() + box[2];
        double x1 = lpos.getX() + box[3], y1 = lpos.getY() + box[4], z1 = lpos.getZ() + box[5];
        return new Vec3[]{
            ClientTerminalView.toWorld(new Vec3(x0, y0, z0), partialTick),
            ClientTerminalView.toWorld(new Vec3(x1, y0, z0), partialTick),
            ClientTerminalView.toWorld(new Vec3(x1, y0, z1), partialTick),
            ClientTerminalView.toWorld(new Vec3(x0, y0, z1), partialTick),
            ClientTerminalView.toWorld(new Vec3(x0, y1, z0), partialTick),
            ClientTerminalView.toWorld(new Vec3(x1, y1, z0), partialTick),
            ClientTerminalView.toWorld(new Vec3(x1, y1, z1), partialTick),
            ClientTerminalView.toWorld(new Vec3(x0, y1, z1), partialTick)
        };
    }

    /** One box outlined as 12 camera-facing strokes between transformed corners. */
    private static void drawThickCorners(BufferBuilder buf, Ctx ctx, Vec3[] c, double widthPx,
                                         float r, float g, float b, float a) {
        for (int[] e : BOX_EDGES) {
            drawThickLine(buf, ctx, c[e[0]], c[e[1]], widthPx, r, g, b, a);
        }
    }

    /** Six translucent faces of a box given transformed corners; fills are surfaces, so overlapping plates do not double-blend. */
    private static void filledCorners(BufferBuilder buf, Ctx ctx, Vec3[] c,
                                      float r, float g, float b, float a) {
        quadWorld(buf, ctx, c[0], c[3], c[7], c[4], r, g, b, a); // -X
        quadWorld(buf, ctx, c[2], c[1], c[5], c[6], r, g, b, a); // +X
        quadWorld(buf, ctx, c[0], c[1], c[2], c[3], r, g, b, a); // -Y
        quadWorld(buf, ctx, c[7], c[6], c[5], c[4], r, g, b, a); // +Y
        quadWorld(buf, ctx, c[0], c[4], c[5], c[1], r, g, b, a); // -Z
        quadWorld(buf, ctx, c[2], c[6], c[7], c[3], r, g, b, a); // +Z
    }

    /** Corners of the unit cube around a local center, each re-anchored individually. */
    private static Vec3[] cubeCorners(Vec3 center, float partialTick) {
        double x0 = center.x - 0.5D, y0 = center.y - 0.5D, z0 = center.z - 0.5D;
        double x1 = center.x + 0.5D, y1 = center.y + 0.5D, z1 = center.z + 0.5D;
        return new Vec3[]{
            ClientTerminalView.toWorld(new Vec3(x0, y0, z0), partialTick),
            ClientTerminalView.toWorld(new Vec3(x1, y0, z0), partialTick),
            ClientTerminalView.toWorld(new Vec3(x1, y0, z1), partialTick),
            ClientTerminalView.toWorld(new Vec3(x0, y0, z1), partialTick),
            ClientTerminalView.toWorld(new Vec3(x0, y1, z0), partialTick),
            ClientTerminalView.toWorld(new Vec3(x1, y1, z0), partialTick),
            ClientTerminalView.toWorld(new Vec3(x1, y1, z1), partialTick),
            ClientTerminalView.toWorld(new Vec3(x0, y1, z1), partialTick)
        };
    }

    private static void drawSpall(BufferBuilder buffer, Ctx ctx, ClientTerminalView.Actor actor,
                                  ClientTerminalView.Timeline tl, float partialTick) {
        if (tl.spallCount() == 0) return;
        // Every cone renders; secondary cones (later plates, a second ship)
        // start growing later in the timeline but never take the camera.
        for (int cone = 0; cone < tl.spallCount(); cone++) {
            drawSpallCone(buffer, ctx, actor, tl, partialTick, cone);
        }
    }

    private static void drawSpallCone(BufferBuilder buffer, Ctx ctx, ClientTerminalView.Actor actor,
                                      ClientTerminalView.Timeline tl, float partialTick, int coneIndex) {
        ClientboundTerminalBallisticsPacket.Spall spall = actor.packet.spalls().get(coneIndex);
        float growth = tl.spallGrowth(coneIndex);
        if (spall.fragments() <= 0 || growth <= 0.0F) return;
        Vec3 origin = ClientTerminalView.toWorld(spall.origin(), partialTick);
        // The recorded direction is vehicle-local; re-anchor it to world like
        // every position, or the cone points the wrong way on rotated ships.
        Vec3 dir = ClientTerminalView.toWorldDir(spall.dir(), partialTick);
        if (dir.lengthSqr() < 1.0e-8D) return;
        dir = dir.normalize();
        float[] rayStops = spall.rayStops();
        List<ClientboundTerminalBallisticsPacket.EntityHit> entityHits = actor.packet.entities();

        // Deterministic golden-spiral cone sample, matching the server's spall shape.
        int rays = Math.min(12, Math.max(3, spall.fragments() / 2));
        double coneCos = Mth.clamp(spall.coneCos(), 0.05D, 0.999D);
        for (int i = 0; i < rays; i++) {
            Vec3 rayDir = fragmentDirection(dir, i, rays, coneCos);
            double cap = Math.min(spall.range(), 6.0D) * (0.6D + 0.4D * (i % 3) / 2.0D);
            double len = cap;
            // Terminate at the geometry the server raycast for this exact ray.
            if (rayStops != null && i < rayStops.length && rayStops[i] > 0.0F) {
                len = Math.min(len, rayStops[i]);
            }
            // And at recorded entity hits (crew) along the ray.
            len = clipRayToEntities(entityHits, origin, rayDir, len, partialTick);
            len *= growth;
            if (len < 0.05D) continue;
            drawThickLine(buffer, ctx, origin, origin.add(rayDir.scale(len)), spallPx(), 1.0F, 0.62F, 0.1F,
                tl.fade * 0.75F * (0.35F + 0.65F * growth));
        }

        // Spall-struck blocks: single billboard dots on the block surface facing
        // the spall origin (exact fragment hit points are not recorded), lit up
        // as the growing cone front passes them.
        for (int j = 0; j < spall.blocks().size(); j++) {
            ClientboundTerminalBallisticsPacket.SpallBlock block = spall.blocks().get(j);
            double distFrac = tl.blockDistFrac(coneIndex, j);
            if (growth < distFrac) continue;
            float pop = Mth.clamp((growth - (float) distFrac) * 8.0F, 0.0F, 1.0F);
            if (pop <= 0.01F) continue;
            Vec3 blockPos = ClientTerminalView.toWorld(block.pos(), partialTick);
            Vec3 toOrigin = origin.subtract(blockPos);
            Vec3 surface = toOrigin.lengthSqr() < 1.0e-8D
                ? blockPos : blockPos.add(toOrigin.normalize().scale(0.5D));
            float[] color = block.broken() ? SPALL_BROKEN_COLOR
                : (block.armorLike() ? new float[]{0.75F, 0.75F, 0.8F} : SPALL_INTERNAL_COLOR);
            drawDot(buffer, ctx, surface, markerPx() * 0.5D * pop,
                color[0], color[1], color[2], tl.fade * (block.broken() ? 0.95F : 0.6F) * pop);
        }
    }

    /** Shortens a display ray to the first recorded entity hit lying on it. */
    private static double clipRayToEntities(List<ClientboundTerminalBallisticsPacket.EntityHit> entities,
                                            Vec3 origin, Vec3 rayDir, double maxLen, float partialTick) {
        double best = maxLen;
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : entities) {
            Vec3 rel = ClientTerminalView.toWorld(hit.pos(), partialTick).subtract(origin);
            double along = rel.dot(rayDir);
            if (along <= 0.1D || along >= best) continue;
            double perpSq = rel.lengthSqr() - along * along;
            if (perpSq < 0.45D * 0.45D) best = along;
        }
        return best;
    }

    private static void drawEntityHits(BufferBuilder buffer, Ctx ctx, ClientTerminalView.Actor actor,
                                       ClientTerminalView.Timeline tl, float partialTick) {
        List<ClientboundTerminalBallisticsPacket.EntityHit> entities = actor.packet.entities();
        for (int i = 0; i < entities.size(); i++) {
            ClientboundTerminalBallisticsPacket.EntityHit hit = entities.get(i);
            double appear = tl.entityAppear != null && i < tl.entityAppear.length ? tl.entityAppear[i] : 0.0D;
            if (tl.age < appear) continue;
            float pop = Mth.clamp((float) ((tl.age - appear) / 4.0D), 0.0F, 1.0F);
            Vec3 pos = ClientTerminalView.toWorld(hit.pos(), partialTick);
            float[] color = hit.killed() ? STOP_COLOR : BOUNCE_COLOR;
            Vec3 min = pos.subtract(0.4D, 0.0D, 0.4D);
            Vec3 max = pos.add(0.4D, 1.8D, 0.4D);
            drawThickBox(buffer, ctx, min, max, platePx(), color[0], color[1], color[2], tl.fade * pop);
        }
    }

    /**
     * Camera-facing billboard dot of a constant on-window size; the quad lies in
     * the plane through {@code at} perpendicular to the view direction.
     */
    public static void drawDot(BufferBuilder buf, Ctx ctx, Vec3 at, double halfDisplayPx,
                               float r, float g, float b, float a) {
        if (halfDisplayPx <= 0.01D) return;
        double depth = viewDepth(ctx, at);
        if (depth < 0.06D) return;
        double half = ctx.worldHalfWidth(depth, halfDisplayPx);
        Vec3 upHint = Math.abs(ctx.fwd.y) > 0.95D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = ctx.fwd.cross(upHint).normalize();
        Vec3 up = right.cross(ctx.fwd).normalize();
        vertexWorld(buf, ctx, at.add(right.scale(half)).add(up.scale(half)), r, g, b, a);
        vertexWorld(buf, ctx, at.subtract(right.scale(half)).add(up.scale(half)), r, g, b, a);
        vertexWorld(buf, ctx, at.subtract(right.scale(half)).subtract(up.scale(half)), r, g, b, a);
        vertexWorld(buf, ctx, at.add(right.scale(half)).subtract(up.scale(half)), r, g, b, a);
    }

    private static void quadWorld(BufferBuilder buf, Ctx ctx, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3,
                                  float r, float g, float b, float a) {
        vertexWorld(buf, ctx, p0, r, g, b, a);
        vertexWorld(buf, ctx, p1, r, g, b, a);
        vertexWorld(buf, ctx, p2, r, g, b, a);
        vertexWorld(buf, ctx, p3, r, g, b, a);
    }

    /**
     * The shell's own detonation, drawn as a stylized x-ray animation: a
     * fireball disc (bright core over an orange body) plus an expanding
     * shockwave ring, timed to the shell's arrival at its last struck plate.
     * Drawing through the overlay (depth test off) means the blast shows even
     * when it happens far outside client render distance, where no explosion
     * particles exist.
     */
    private static void drawBlast(BufferBuilder buffer, Ctx ctx, ClientTerminalView.Actor actor,
                                  ClientTerminalView.Timeline tl, float partialTick) {
        ClientboundTerminalBallisticsPacket.Blast blast = actor.packet.blast();
        if (blast == null) return;
        double appear = tl.plateAppear.length > 0 ? tl.plateAppear[tl.plateAppear.length - 1] : actor.startTick;
        float since = (float) (tl.age - appear);
        if (since < 0.0F || since > 20.0F) return;
        float alpha = tl.fade * Mth.clamp(1.0F - since / 18.0F, 0.0F, 1.0F);
        if (alpha <= 0.02F) return;

        Vec3 center = ClientTerminalView.toWorld(blast.pos(), partialTick);
        // Camera-facing disc basis: right/up span the plane parallel to screen.
        Vec3 fwd = ctx.fwd;
        Vec3 up0 = Math.abs(fwd.y) > 0.99D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = fwd.cross(up0);
        if (right.lengthSqr() < 1.0e-8D) return;
        right = right.normalize();
        Vec3 up = right.cross(fwd).normalize();

        float growth = Mth.clamp(since / 8.0F, 0.0F, 1.0F);
        growth = 1.0F - (1.0F - growth) * (1.0F - growth); // ease-out
        float radius = (float) blast.radius() * (0.25F + 0.75F * growth);
        if (radius <= 0.05F) return;

        // Fireball: two filled discs (degenerate quads = triangles in the
        // shared QUADS batch), the hot core drawn last on top of the body.
        fillDisc(buffer, ctx, center, right, up, radius, 1.0F, 0.42F, 0.08F, alpha * 0.75F);
        float flash = Mth.clamp(1.0F - since / 6.0F, 0.0F, 1.0F);
        fillDisc(buffer, ctx, center, right, up, radius * 0.55F, 1.0F, 0.85F, 0.45F,
            alpha * (0.35F + 0.65F * flash));

        // Shockwave: one expanding, fading ring of thick strokes.
        float ringT = Mth.clamp(since / 10.0F, 0.0F, 1.0F);
        if (ringT < 1.0F) {
            float ringRadius = radius * (0.6F + 1.2F * ringT);
            float ringAlpha = tl.fade * (1.0F - ringT) * 0.7F;
            Vec3 prev = null;
            int segs = 16;
            for (int i = 0; i <= segs; i++) {
                double a = Math.PI * 2.0D * i / segs;
                Vec3 p = center.add(right.scale(Math.cos(a) * ringRadius)).add(up.scale(Math.sin(a) * ringRadius));
                if (prev != null) {
                    drawThickLine(buffer, ctx, prev, p, 1.6D, 1.0F, 0.9F, 0.7F, ringAlpha);
                }
                prev = p;
            }
        }
    }

    /** A camera-facing filled disc emitted as degenerate quads (triangles) into the open QUADS batch. */
    private static void fillDisc(BufferBuilder buffer, Ctx ctx, Vec3 center, Vec3 right, Vec3 up,
                                 float radius, float r, float g, float b, float a) {
        if (a <= 0.01F || radius <= 0.0F) return;
        int segs = 24;
        for (int i = 0; i < segs; i++) {
            double a0 = Math.PI * 2.0D * i / segs;
            double a1 = Math.PI * 2.0D * (i + 1) / segs;
            Vec3 e0 = center.add(right.scale(Math.cos(a0) * radius)).add(up.scale(Math.sin(a0) * radius));
            Vec3 e1 = center.add(right.scale(Math.cos(a1) * radius)).add(up.scale(Math.sin(a1) * radius));
            vertexWorld(buffer, ctx, center, r, g, b, a);
            vertexWorld(buffer, ctx, e0, r, g, b, a);
            vertexWorld(buffer, ctx, e1, r, g, b, a);
            vertexWorld(buffer, ctx, center, r, g, b, a);
        }
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
