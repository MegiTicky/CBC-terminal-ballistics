package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Renders the hitcam replay into a private framebuffer once per frame by
 * calling the vanilla level renderer with a temporary camera (the nested-pass
 * technique other camera mods use on 1.20.1), then blits that framebuffer as
 * a small picture-in-picture window over the HUD.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientHitcamPiP {
    /** Guards RenderLevelStageEvent subscribers (ours and, via {@link #isRenderingPip()}, the mod's other visuals) against re-entering during the nested pass. */
    private static final ThreadLocal<Boolean> RENDERING_PIP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static RenderTarget target;
    private static RenderTarget backupTarget;
    private static Entity ghostShell;
    private static String ghostShellKey = "";
    private static boolean ghostFailed;
    private static boolean shellRenderWarned;
    private static boolean shaderWarningShown;
    private static boolean failureLogged;
    private static Method irisApiShaderPackInUse;
    /** Counts rendered frames while the far-view throttle is engaged. */
    private static int farFrameCounter;

    public static boolean isRenderingPip() {
        return RENDERING_PIP.get();
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (RENDERING_PIP.get()) return;
        if (!ClientHitcam.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        boolean packInUse = shaderPackInUse();
        String mode = TBConfig.HITCAM_SHADER_MODE.get();
        if (packInUse && "SKIP".equals(mode)) {
            warnShadersOnce(mc, false);
            ClientHitcam.stop();
            return;
        }
        boolean fullScene = !packInUse || "ATTEMPT".equals(mode);
        if (packInUse && !fullScene) {
            warnShadersOnce(mc, true);
        }

        ClientHitcam.advance(event.getPartialTick());

        if (shouldSkipFarFrame(mc)) {
            // The private framebuffer still holds the previous frame; the HUD
            // blits it untouched. Skipping is the point: every full pass
            // recenters the shared chunk grid onto the PiP camera and back,
            // which resets every chunk section mesh and forces both views to
            // recompile constantly (holes in the main view's terrain).
            return;
        }

        Matrix4f mainProjection = RenderSystem.getProjectionMatrix();
        com.mojang.blaze3d.vertex.VertexSorting mainSorting = RenderSystem.getVertexSorting();
        try {
            RENDERING_PIP.set(Boolean.TRUE);
            renderPipScene(mc, event.getPartialTick(), fullScene, packInUse && !fullScene);
        } catch (Throwable t) {
            // A secondary full-scene pass can break under other mods' render hooks;
            // the replay is cosmetic, so drop it instead of crashing the frame.
            if (!failureLogged) {
                failureLogged = true;
                org.slf4j.Logger log = com.mojang.logging.LogUtils.getLogger();
                log.warn("CBCTB hitcam PiP render failed, replay stopped", t);
                Minecraft.getInstance().player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("CBCTB hitcam PiP render failed, replay stopped: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage()), false);
            }
            ClientHitcam.stop();
        } finally {
            RENDERING_PIP.set(Boolean.FALSE);
            if (mainProjection != null) {
                RenderSystem.setProjectionMatrix(mainProjection, mainSorting);
            }
            if (fullScene) {
                // Leave the level renderer with the main view's cull frustum,
                // since the PiP pass replaced it.
                mc.levelRenderer.prepareCullFrustum(event.getPoseStack(),
                    mc.gameRenderer.getMainCamera().getPosition(), event.getProjectionMatrix());
            }
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /**
     * True on frames where the far-view throttle says to hold the last PiP
     * frame instead of re-rendering the world. Only engages when the PiP
     * camera sits in a different chunk from the main camera: that is when the
     * nested world pass would recenter the shared chunk grid onto the shell
     * (and the main pass recenter it back), resetting every section mesh and
     * forcing both views to recompile constantly. Near views keep rendering
     * every frame.
     */
    private static boolean shouldSkipFarFrame(Minecraft mc) {
        int interval = Mth.clamp(TBConfig.HITCAM_PIP_FAR_FRAME_INTERVAL.get(), 1, 10);
        if (interval <= 1) return false;
        // The terminal x-ray is short (~6 s) and its whole point is a smooth
        // progressive animation; the chunk-grid thrash this throttle protects
        // against matters on long artillery flights, not here.
        if (ClientHitcam.isTerminal()) return false;
        net.minecraft.world.level.ChunkPos pipChunk = new net.minecraft.world.level.ChunkPos(
            net.minecraft.core.BlockPos.containing(ClientHitcam.cameraPos(0.0F)));
        if (pipChunk.equals(mc.gameRenderer.getMainCamera().getBlockPosition())) return false;
        return farFrameCounter++ % interval != 0;
    }

    private static void renderPipScene(Minecraft mc, float partialTick, boolean fullScene, boolean minimal) {
        Camera camera = new Camera();
        Vec3 camPos = ClientHitcam.cameraPos(partialTick);
        Vec3 look = ClientHitcam.lookTarget(partialTick).subtract(camPos);
        double horiz = Math.sqrt(look.x * look.x + look.z * look.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(look.y, horiz));
        updateGhost(mc, partialTick);

        // Camera.setup dereferences the entity (view rot / eye pos lerp), so a
        // null entity NPEs; a representative entity is passed and immediately
        // overridden below. Replay uses the ghost shell, the artillery cam the
        // live projectile, the terminal orbit just the local player.
        Entity setupEntity = mc.player;
        if (ClientHitcam.isReplay() && ghostShell != null) {
            setupEntity = ghostShell;
        } else if (ClientHitcam.isArtillery()) {
            Entity target = ClientHitcam.artilleryTarget();
            if (target != null) setupEntity = target;
        }
        camera.setup(mc.level, setupEntity, false, false, 1.0F);
        invokeSetPosition(camera, camPos);
        invokeSetRotation(camera, yaw, pitch);

        // Rotation-only pose that maps world space into the PiP camera's view
        // space, built the same way GameRenderer builds it. 1.20.1 never pushes
        // this pose onto the model-view stack for code running after the nested
        // world pass returns, so every post-pass draw (the manual shell model,
        // its bracket and the x-ray overlay) must bake it per-vertex itself.
        PoseStack viewPose = new PoseStack();
        viewPose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
        viewPose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw + 180.0F));

        if (!fullScene) {
            renderMinimal(mc, camera, partialTick, viewPose);
            return;
        }
        renderFullScene(mc, camera, partialTick, viewPose);
    }

    /**
     * Lightweight fallback for active shader packs: draws just the ghost shell
     * into the private target with a transparent background, no nested world
     * render, so the ghost composites over the live view in the PiP window.
     */
    private static void renderMinimal(Minecraft mc, Camera camera, float partialTick, PoseStack viewPose) {
        RenderTarget mainTarget = mc.getMainRenderTarget();
        ensureTarget(mainTarget.width, mainTarget.height);
        target.bindWrite(true);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        RenderSystem.clear(16640, Minecraft.ON_OSX);
        RenderSystem.enableDepthTest();

        Matrix4f projection = new Matrix4f().perspective(
            (float) Math.toRadians(mc.options.fov().get()),
            (float) target.width / target.height, 0.05F,
            Math.min(mc.options.renderDistance().get() * 16.0F + 64.0F, 1024.0F));
        RenderSystem.setProjectionMatrix(projection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);

        renderGhost(mc, camera, partialTick, viewPose);
        mc.renderBuffers().bufferSource().endBatch();
        ClientTerminalOverlay.render(mc, camera, partialTick, viewPose);
    }

    /**
     * Full scene pass using the same proven sandwich as known-working PiP
     * implementations: back up the main framebuffer, render the secondary
     * view into the main target (mods like Oculus may rebind it, so a private
     * target is not reliable), blit the frame into the private PiP target,
     * then restore the backup. Also re-prepares the cull frustum for the PiP
     * camera, which renderLevel does not do itself.
     */
    private static void renderFullScene(Minecraft mc, Camera camera, float partialTick, PoseStack viewPose) {
        RenderTarget mainTarget = mc.getMainRenderTarget();
        ensureTarget(mainTarget.width, mainTarget.height);
        if (backupTarget == null || backupTarget.width != mainTarget.width || backupTarget.height != mainTarget.height) {
            if (backupTarget != null) backupTarget.destroyBuffers();
            backupTarget = new TextureTarget(mainTarget.width, mainTarget.height, true, Minecraft.ON_OSX);
        }

        mc.renderBuffers().bufferSource().endBatch();

        // Back up the main screen (color only) so the HUD and any later
        // rendering see the original frame. Depth is deliberately not copied:
        // blitting depth between framebuffers with different depth formats is
        // invalid in GL and rejects the whole call, color included, which
        // would silently leave the main screen unrestored. Nothing after this
        // stage needs the old depth (the hand render clears it, the next frame
        // clears color+depth).
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, backupTarget.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, mainTarget.width, mainTarget.height,
            0, 0, backupTarget.width, backupTarget.height,
            GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);

        Matrix4f oldProjection = RenderSystem.getProjectionMatrix();
        try {
            mainTarget.bindWrite(true);
            RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 1.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);

            int dispW = Mth.clamp(TBConfig.HITCAM_PIP_DISPLAY_WIDTH.get(), 80, 960);
            int dispH = Mth.clamp(TBConfig.HITCAM_PIP_DISPLAY_HEIGHT.get(), 50, 540);
            // Own aspect (the display window's), not the monitor's, or the
            // scene is squashed on ultrawide screens.
            Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(mc.options.fov().get()),
                (float) dispW / dispH, 0.05F,
                Math.min(mc.options.renderDistance().get() * 16.0F + 64.0F, 1024.0F));
            RenderSystem.setProjectionMatrix(projection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);

            mc.levelRenderer.prepareCullFrustum(viewPose, camera.getPosition(), projection);
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            mc.levelRenderer.renderLevel(
                viewPose, partialTick, System.nanoTime(), false,
                camera, (GameRenderer) mc.gameRenderer, mc.gameRenderer.lightTexture(), projection);
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            mc.renderBuffers().bufferSource().endBatch();

            renderGhost(mc, camera, partialTick, viewPose);
            mc.renderBuffers().bufferSource().endBatch();

            // X-ray diagram of the terminal-ballistics view, drawn into the
            // same finished frame so it stays inside the PiP window.
            ClientTerminalOverlay.render(mc, camera, partialTick, viewPose);

            // Copy the finished frame into the private PiP texture.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, mainTarget.width, mainTarget.height,
                0, 0, target.width, target.height,
                GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
        } finally {
            // Restore the main screen from the backup, color only (see above).
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, backupTarget.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainTarget.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, backupTarget.width, backupTarget.height,
                0, 0, mainTarget.width, mainTarget.height,
                GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
            int blitError = GL11.glGetError();
            if (blitError != 0 && !failureLogged) {
                failureLogged = true;
                com.mojang.logging.LogUtils.getLogger()
                    .warn("CBCTB hitcam: main screen restore blit failed, GL error 0x{}", Integer.toHexString(blitError));
            }
            RenderSystem.setProjectionMatrix(oldProjection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
            mainTarget.bindWrite(true);
        }
    }

    /**
     * True only while a shader pack is actually rendering, not merely when
     * Oculus/Iris is installed. Uses the Iris v0 API reflectively so the class
     * never has to be present at compile or run time.
     */
    private static boolean shaderPackInUse() {
        try {
            if (irisApiShaderPackInUse == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                irisApiShaderPackInUse = api.getMethod("isShaderPackInUse");
                irisApiShaderPackInUse.setAccessible(true);
            }
            Object instance = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
            return (Boolean) irisApiShaderPackInUse.invoke(instance);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The ghost shell is a non-tracked entity, so the nested level pass never
     * draws it; render it through the entity dispatcher, which renderLevel has
     * just prepared for our PiP camera. Falls back to a small additive streak
     * if the CBC renderer misbehaves with a fake entity.
     */
    private static void renderGhost(Minecraft mc, Camera camera, float partialTick, PoseStack viewPose) {
        if (ClientHitcam.isTerminal()) {
            // Animated replay: the actual shell model flies the recorded path.
            Entity ghost = ClientTerminalView.ghost(partialTick);
            boolean rendered = ghost != null && renderEntity(mc, camera, ghost, partialTick, viewPose);
            if (!rendered) renderGhostStreak(mc, camera, partialTick, viewPose);
            return;
        }
        if (ClientHitcam.isArtillery()) {
            // Prefer the tracked live entity; beyond tracking range, updateGhost
            // parks the relay-fed ghost shell model at the extrapolated position.
            Entity target = ClientHitcam.artilleryTarget();
            if (target == null) target = ghostShell;
            boolean rendered = target != null && renderEntity(mc, camera, target, partialTick, viewPose);
            if (!rendered) renderGhostStreak(mc, camera, partialTick, viewPose);
            return;
        }
        if (!ClientHitcam.isReplay()) return;
        if (ghostShell == null || mc.level == null) return;
        boolean rendered = renderEntity(mc, camera, ghostShell, partialTick, viewPose);
        if (!rendered) renderGhostStreak(mc, camera, partialTick, viewPose);
    }

    /**
     * Render one entity through the entity renderer directly. CBC shells can be
     * tiny (autocannon calibres), so the model is lifted to a size that reads in
     * the small PiP window, War-Thunder-style. Returns false when the renderer
     * cannot be resolved or throws so the caller can fall back.
     */
    private static boolean renderEntity(Minecraft mc, Camera camera, Entity entity, float partialTick, PoseStack viewPose) {
        if (mc.level == null) return false;
        Vec3 cam = camera.getPosition();
        // Render the manual model at the partial-tick interpolated position so
        // it sits exactly on the shell the world pass drew this frame.
        Vec3 pos = ClientHitcam.lerpedPosition(entity, partialTick);
        double rx = pos.x - cam.x;
        double ry = pos.y - cam.y;
        double rz = pos.z - cam.z;
        try {
            net.minecraft.client.renderer.entity.EntityRenderer<? super Entity> renderer =
                mc.getEntityRenderDispatcher().getRenderer(entity);
            if (renderer == null) return false;
            // The dispatcher's level/camera are still the main view's; point them
            // at the PiP camera so renderer code that reads camera orientation
            // (billboards, overlays) resolves against the right frame.
            mc.getEntityRenderDispatcher().prepare(mc.level, camera, null);
            AABB bb = entity.getBoundingBox();
            double longest = Math.max(bb.getXsize(), Math.max(bb.getYsize(), bb.getZsize()));
            // CBC shells render as ~1-block block models regardless of caliber,
            // which reads huge in the slow PiP view, so the target is allowed to
            // shrink them; small-caliber entities still get lifted to be visible.
            float scale = (float) Mth.clamp(0.55D / Math.max(longest, 0.05D), 0.45D, 2.5D);
            // Seed the pose with the PiP camera rotation, then offset to the
            // camera-relative shell position and lift the model. Entity models
            // bake their per-vertex matrix at buffer-build time, so without the
            // rotation the manual shell would be projected with no camera
            // orientation (the cause of its direction-dependent drift).
            PoseStack pose = new PoseStack();
            pose.mulPoseMatrix(viewPose.last().pose());
            var buffers = mc.renderBuffers().bufferSource();
            setGhostOrientation(entity, ClientHitcam.shellDir());
            pose.pushPose();
            pose.translate(rx, ry, rz);
            pose.scale(scale, scale, scale);
            renderer.render(entity, entity.getYRot(), partialTick, pose, buffers,
                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
            pose.popPose();
            buffers.endBatch();
            return true;
        } catch (Throwable t) {
            if (!shellRenderWarned) {
                shellRenderWarned = true;
                com.mojang.logging.LogUtils.getLogger()
                    .warn("CBCTB hitcam: manual shell render failed for {}, streak fallback in effect", entity.getType(), t);
            }
            return false;
        }
    }

    /** Sets the CBC projectile orientation field on a ghost shell (package-visible for ClientTerminalView). */
    static void setGhostOrientation(Entity ghost, Vec3 dir) {
        try {
            java.lang.reflect.Field f = findField(ghost.getClass(), "orientation");
            f.set(ghost, dir.normalize());
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static java.lang.reflect.Field findField(Class<?> c, String name) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            try {
                java.lang.reflect.Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static void renderGhostStreak(Minecraft mc, Camera camera, float partialTick, PoseStack viewPose) {
        Vec3 pos = ClientHitcam.shellPos(partialTick);
        Vec3 dir = ClientHitcam.shellDir(partialTick);
        Vec3 tail = pos.subtract(dir.scale(1.6D));
        ClientTerminalOverlay.Ctx ctx = ClientTerminalOverlay.Ctx.of(mc, camera, viewPose);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        ClientTerminalOverlay.drawThickLine(buffer, ctx, tail, pos, 1.0F, 0.8F, 0.3F, 0.9F);
        tesselator.end();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /**
     * Replays move an untracked ghost shell through the world themselves; the
     * artillery cam needs one only while the real projectile is beyond tracking
     * range (fed by server relay packets). Builds the ghost from the recorded
     * type key and parks it at the current shell position each frame.
     */
    private static void updateGhost(Minecraft mc, float partialTick) {
        if (ClientHitcam.isReplay()) {
            if (ensureGhostShell(mc, ClientHitcam.entityTypeKey())) {
                Vec3 pos = ClientHitcam.shellPos();
                ghostShell.setPos(pos.x, pos.y, pos.z);
                Vec3 dir = ClientHitcam.shellDir();
                float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
                float pitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(dir.y, -1.0D, 1.0D)));
                ghostShell.setYRot(yaw);
                ghostShell.setXRot(pitch);
                ghostShell.yRotO = yaw;
                ghostShell.xRotO = pitch;
                ghostShell.xOld = ghostShell.xo = pos.x;
                ghostShell.yOld = ghostShell.yo = pos.y;
                ghostShell.zOld = ghostShell.zo = pos.z;
                setGhostOrientation(ghostShell, dir);
            }
            return;
        }
        if (ClientHitcam.isArtillery() && ClientHitcam.artilleryTarget() == null) {
            if (ensureGhostShell(mc, ClientHitcam.artilleryGhostTypeKey())) {
                Vec3 pos = ClientHitcam.shellPos(partialTick);
                Vec3 dir = ClientHitcam.shellDir(partialTick);
                ghostShell.setPos(pos.x, pos.y, pos.z);
                ghostShell.xOld = ghostShell.xo = pos.x;
                ghostShell.yOld = ghostShell.yo = pos.y;
                ghostShell.zOld = ghostShell.zo = pos.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
                float pitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(dir.y, -1.0D, 1.0D)));
                ghostShell.setYRot(yaw);
                ghostShell.setXRot(pitch);
                ghostShell.yRotO = yaw;
                ghostShell.xRotO = pitch;
                setGhostOrientation(ghostShell, dir);
            }
        }
    }

    /** Creates (or re-creates for a new type) the ghost shell; false while unavailable. */
    private static boolean ensureGhostShell(Minecraft mc, String typeKey) {
        if (typeKey == null || typeKey.isEmpty()) return false;
        if (ghostShell != null && typeKey.equals(ghostShellKey)) return true;
        if (ghostFailed && typeKey.equals(ghostShellKey)) return false;
        ghostShell = null;
        Optional<EntityType<?>> type = EntityType.byString(typeKey);
        if (type.isPresent() && mc.level != null) {
            try {
                ghostShell = type.get().create(mc.level);
            } catch (Throwable t) {
                ghostShell = null;
            }
        }
        ghostShellKey = typeKey;
        if (ghostShell == null) ghostFailed = true;
        return ghostShell != null;
    }

    private static void ensureTarget(int width, int height) {
        if (target == null) {
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
    }

    private static void warnShadersOnce(Minecraft mc, boolean fallback) {
        if (shaderWarningShown) return;
        shaderWarningShown = true;
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(fallback
                ? "CBCTB hitcam: shader pack active, using lightweight replay view (set hitcamShaderMode to change)."
                : "CBCTB hitcam disabled: shader pack active (hitcamShaderMode=SKIP)."), false);
        }
    }

    @SubscribeEvent
    public static void renderGui(RenderGuiEvent.Post event) {
        if (!ClientHitcam.active() || target == null) return;
        Minecraft mc = Minecraft.getInstance();
        var gui = event.getGuiGraphics();
        int screenW = gui.guiWidth();
        int screenH = gui.guiHeight();
        int drawW = Mth.clamp(TBConfig.HITCAM_PIP_DISPLAY_WIDTH.get(), 80, screenW - 4);
        int drawH = Mth.clamp(TBConfig.HITCAM_PIP_DISPLAY_HEIGHT.get(), 50, screenH - 4);
        int x;
        int y;
        switch (TBConfig.HITCAM_PIP_ANCHOR.get()) {
            case "TOP_LEFT" -> { x = 2; y = 2; }
            case "BOTTOM_LEFT" -> { x = 2; y = screenH - drawH - 2; }
            case "BOTTOM_RIGHT" -> { x = screenW - drawW - 2; y = screenH - drawH - 2; }
            default -> { x = screenW - drawW - 2; y = 2; }
        }

        gui.fill(x - 1, y - 1, x + drawW + 1, y + drawH + 1, 0xFF202020);
        blitFramebuffer(gui.pose().last().pose(), x, y, drawW, drawH);
        gui.drawString(mc.font, ClientHitcam.statusLine(), x + 4, y + drawH - mc.font.lineHeight - 3, 0xFFFFFF, true);
    }

    private static void blitFramebuffer(Matrix4f matrix, int x, int y, int w, int h) {
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        BufferBuilder builder = RenderSystem.renderThreadTesselator().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // FBO textures sample bottom-up relative to GUI space, so the bottom
        // edge of the quad takes v=0 (the texture's first row).
        builder.vertex(matrix, x, y + h, 0).uv(0.0F, 0.0F).endVertex();
        builder.vertex(matrix, x + w, y + h, 0).uv(1.0F, 0.0F).endVertex();
        builder.vertex(matrix, x + w, y, 0).uv(1.0F, 1.0F).endVertex();
        builder.vertex(matrix, x, y, 0).uv(0.0F, 1.0F).endVertex();
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
    }

    private static void invokeSetPosition(Camera camera, Vec3 pos) {
        callCamera(camera, "setPosition", new Class<?>[]{Vec3.class}, pos);
        callCamera(camera, "m_90581_", new Class<?>[]{Vec3.class}, pos);
    }

    private static void invokeSetRotation(Camera camera, float yaw, float pitch) {
        callCamera(camera, "setRotation", new Class<?>[]{float.class, float.class}, yaw, pitch);
        callCamera(camera, "m_90572_", new Class<?>[]{float.class, float.class}, yaw, pitch);
    }

    private static void callCamera(Camera camera, String name, Class<?>[] params, Object... args) {
        try {
            Method m = Camera.class.getDeclaredMethod(name, params);
            m.setAccessible(true);
            m.invoke(camera, args);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private ClientHitcamPiP() {}
}
