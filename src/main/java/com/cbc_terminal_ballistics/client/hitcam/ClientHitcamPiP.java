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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders the hitcam replay into a private framebuffer once per frame by
 * calling the vanilla level renderer with a temporary camera (the nested-pass
 * technique other camera mods use on 1.20.1), then blits that framebuffer as
 * a small picture-in-picture window over the HUD.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientHitcamPiP {
    /** Max rows of the terminal-view critical list before folding into "+N more". */
    private static final int MAX_CRITICAL_ROWS = 5;
    /** Component rows orange, crew rows red, overflow rows gray. */
    private static final int COLOR_COMPONENT = 0xFFFFAA00;
    private static final int COLOR_CREW = 0xFFFF5555;
    private static final int COLOR_MORE = 0xFF999999;

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
        return PipRenderScope.depth() > 0;
    }

    // ------------------------------------------------------------------
    // PiP particle filtering + ship-terrain ghost (x-ray) support, driven
    // by ParticleEngineMixin and the sodium ship-pass mixins.

    /** True while VS2 is drawing a ship's terrain sections inside the nested pass (set by mixins). */
    private static boolean shipTerrainPass;
    /** True while our constant-alpha blend state is installed in the terrain draw. */
    private static boolean ghostGlApplied;
    private static int ghostEngageLogs;

    public static boolean hideAllPipParticles() {
        return isRenderingPip() && "NONE".equals(TBConfig.HITCAM_PIP_PARTICLES.get());
    }

    public static boolean shouldRenderPipParticle(net.minecraft.client.particle.Particle particle) {
        if (!isRenderingPip()) return true;
        String mode = TBConfig.HITCAM_PIP_PARTICLES.get();
        if ("ALL".equals(mode)) return true;
        if ("NONE".equals(mode)) return false;
        String name = particle.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return !name.contains("smoke") && !name.contains("plume");
    }

    /** Mixin hook: set while VS2's per-ship terrain pass is drawing inside the nested level render. */
    public static void setShipTerrainPass(boolean active) {
        shipTerrainPass = active;
    }

    private static boolean shouldGhostShipTerrain() {
        return shipTerrainPass && isRenderingPip()
            && ClientTerminalView.active()
            && TBConfig.HITCAM_PIP_TERRAIN_ALPHA.get() < 0.999D;
    }

    /**
     * Installs the ghost state inside the embeddium terrain draw, right after
     * its pass state setup: a fixed-function constant-alpha blend (embeddium's
     * chunk shader ignores RenderSystem.setShaderColor) plus depth writes off
     * in x-ray mode so hull interiors stack into the ghost.
     */
    public static void applyShipGhostGl() {
        if (!shouldGhostShipTerrain()) return;
        float alpha = TBConfig.HITCAM_PIP_TERRAIN_ALPHA.get().floatValue();
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendColor(1.0F, 1.0F, 1.0F, alpha);
        GL11.glBlendFunc(GL14.GL_CONSTANT_ALPHA, GL14.GL_ONE_MINUS_CONSTANT_ALPHA);
        if (TBConfig.HITCAM_PIP_TERRAIN_XRAY.get()) {
            RenderSystem.depthMask(false);
        }
        ghostGlApplied = true;
        if (ghostEngageLogs < 3) {
            ghostEngageLogs++;
            com.mojang.logging.LogUtils.getLogger().info(
                "[CTB-HITCAM] ship terrain ghost engaged (alpha={}, xray={})",
                alpha, TBConfig.HITCAM_PIP_TERRAIN_XRAY.get());
        }
    }

    /** Restores blend/depth state after a ghosted ship terrain pass. */
    public static void clearShipGhostGl() {
        if (!ghostGlApplied) return;
        ghostGlApplied = false;
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL14.glBlendColor(0.0F, 0.0F, 0.0F, 0.0F);
        RenderSystem.depthMask(true);
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (isRenderingPip()) return;
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

        try {
            try (PipRenderScope ignored = PipRenderScope.enter(mc, event, fullScene)) {
                renderPipScene(mc, event.getPartialTick(), fullScene, packInUse && !fullScene);
            }
        } catch (Throwable t) {
            if (DistantHorizonsCompat.shouldPatchCamera() && DistantHorizonsCompat.markAttemptFailure()) {
                // The throw happened while the Distant Horizons camera patch
                // was active. Suppress DH inside the view and keep going next
                // frame instead of tearing the replay down; if it still fails,
                // the existing stop path below runs on the following throw.
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                            "CBCTB hitcam: Distant Horizons render failed in the view, suppressing DH LODs inside it for this session."), false);
                }
                org.slf4j.Logger log = com.mojang.logging.LogUtils.getLogger();
                log.warn("CBCTB hitcam: DH camera patch failed, suppressing DH LODs inside the hitcam view", t);
            } else {
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
            }
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
    private static boolean shouldSkipFarFrame(Minecraft mc) {        int interval = Mth.clamp(TBConfig.HITCAM_PIP_FAR_FRAME_INTERVAL.get(), 1, 10);
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
        // Feed the nested pass's camera to the Distant Horizons compat (its
        // mixins read these while DH renders inside the pass below).
        DistantHorizonsCompat.beginPipFrame(camPos,
            look.lengthSqr() > 1.0E-8D ? look.normalize() : ClientHitcam.shellDir(partialTick));
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

        // Back up the main screen (color + depth) so the HUD and any later
        // rendering see the original frame. Depth must ride along: the PiP
        // pass renders into the main framebuffer and overwrites its depth, and
        // main-frame draws after this stage depth-test against it — leaving
        // the PiP scene's depth there made geometry layer wrong in the main
        // view. Depth blitting is legal here because both targets are
        // TextureTargets with the same DEPTH_COMPONENT24 renderbuffer (neither
        // has a stencil buffer, so the stencil bit must NOT be included: a
        // stencil blit against a missing buffer rejects the whole call).
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, backupTarget.frameBufferId);
        drainGlErrors();
        GL30.glBlitFramebuffer(0, 0, mainTarget.width, mainTarget.height,
            0, 0, backupTarget.width, backupTarget.height,
            GL30.GL_COLOR_BUFFER_BIT | GL30.GL_DEPTH_BUFFER_BIT, GL30.GL_NEAREST);
        int backupError = GL11.glGetError();
        if (backupError != 0) {
            // Depth format mismatch on some drivers: retry color-only so the
            // backup (and thus the restore below) never loses the screen.
            GL30.glBlitFramebuffer(0, 0, mainTarget.width, mainTarget.height,
                0, 0, backupTarget.width, backupTarget.height,
                GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
            GL11.glGetError();
            if (!failureLogged) {
                failureLogged = true;
                com.mojang.logging.LogUtils.getLogger()
                    .warn("CBCTB hitcam: main screen backup blit failed, GL error 0x{} (depth not backed up)",
                        Integer.toHexString(backupError));
            }
        }

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
            // Restore the main screen from the backup, color + depth (the PiP
            // pass left its own scene's depth in the main framebuffer; see the
            // backup comment above for the format argument).
            // The nested pass (DH, Embeddium/VS mixins) can leave stale GL
            // errors in the queue — drain them first or the check below
            // misattributes an old error to this blit.
            drainGlErrors();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, backupTarget.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainTarget.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, backupTarget.width, backupTarget.height,
                0, 0, mainTarget.width, mainTarget.height,
                GL30.GL_COLOR_BUFFER_BIT | GL30.GL_DEPTH_BUFFER_BIT, GL30.GL_NEAREST);
            int blitError = GL11.glGetError();
            if (blitError != 0) {
                // Depth restore rejected: fall back to the color-only restore,
                // then clear the main depth. Leaving the PiP scene's depth in
                // the main framebuffer makes every later main-view draw (the
                // hand, other mods' AFTER_LEVEL hooks) depth-test against a
                // close-up scene — geometry layers wrong across the screen.
                GL30.glBlitFramebuffer(0, 0, backupTarget.width, backupTarget.height,
                    0, 0, mainTarget.width, mainTarget.height,
                    GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
                GL11.glGetError();
                RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
                if (!failureLogged) {
                    failureLogged = true;
                    com.mojang.logging.LogUtils.getLogger()
                        .warn("CBCTB hitcam: main screen restore blit failed, GL error 0x{} (depth cleared); read fbo status 0x{}, draw fbo status 0x{}",
                            Integer.toHexString(blitError),
                            Integer.toHexString(GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER)),
                            Integer.toHexString(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)));
                }
            }
            mainTarget.bindWrite(true);
        }
    }

    /** Consumes queued GL errors so a stale error is not misattributed to the next call. */
    private static void drainGlErrors() {
        for (int i = 0; i < 8; i++) {
            if (GL11.glGetError() == 0) return;
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
            // The streak fallback only ever shows while a shell is genuinely in
            // flight — once the fly-in is over, no stroke may outlive the
            // diagram's own fade (a lingering full-bright streak at the impact
            // point used to ignore every per-shell fade).
            Entity ghost = ClientTerminalView.ghost(partialTick);
            boolean rendered = ghost != null && renderEntity(mc, camera, ghost, partialTick, viewPose);
            if (!rendered && ClientTerminalView.liveFlightActive(partialTick)) {
                renderGhostStreak(mc, camera, partialTick, viewPose);
            }
            renderSalvoGhosts(mc, camera, partialTick, viewPose);
            return;
        }
        if (ClientHitcam.isArtillery()) {
            // Prefer the tracked live entity; beyond tracking range, updateGhost
            // parks the relay-fed ghost shell model at the extrapolated position.
            Entity target = ClientHitcam.artilleryTarget();
            if (target == null) target = ghostShell;
            boolean rendered = target != null && renderEntity(mc, camera, target, partialTick, viewPose);
            if (!rendered) renderGhostStreak(mc, camera, partialTick, viewPose);
            renderSalvoGhosts(mc, camera, partialTick, viewPose);
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
        return renderEntity(mc, camera, entity, partialTick, viewPose, ClientHitcam.shellDir(), 1.0F);
    }

    private static boolean renderEntity(Minecraft mc, Camera camera, Entity entity, float partialTick, PoseStack viewPose,
                                        Vec3 orientDir, float scaleMul) {
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
            float scale = (float) Mth.clamp(0.55D / Math.max(longest, 0.05D), 0.45D, 2.5D) * scaleMul;
            // Seed the pose with the PiP camera rotation, then offset to the
            // camera-relative shell position and lift the model. Entity models
            // bake their per-vertex matrix at buffer-build time, so without the
            // rotation the manual shell would be projected with no camera
            // orientation (the cause of its direction-dependent drift).
            PoseStack pose = new PoseStack();
            pose.mulPoseMatrix(viewPose.last().pose());
            var buffers = mc.renderBuffers().bufferSource();
            setGhostOrientation(entity, orientDir);
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

    /**
     * Draws the non-focused shells of a salvo/broadside as relay-fed ghosts, so
     * a dozen-shell salvo does not collapse to one shell once the rest leave
     * client tracking range. Each entry in the salvo registry is extrapolated
     * from its last server relay the same way the artillery ghost is. A shell
     * whose client entity still exists is skipped — the world pass drew the
     * real one this frame.
     */
    private static void renderSalvoGhosts(Minecraft mc, Camera camera, float partialTick, PoseStack viewPose) {
        if (!TBConfig.HITCAM_PIP_SALVO_SHELLS.get()) return;
        var registry = ClientHitcam.salvoGhosts();
        if (registry.isEmpty() || mc.level == null) return;
        for (var entry : registry.entrySet()) {
            ClientHitcam.SalvoGhost relay = entry.getValue();
            if (relay.typeKey.isEmpty()) continue;
            if (mc.level.getEntity(entry.getKey()) != null) continue;
            Entity model = pooledSalvoShell(mc, relay.typeKey);
            if (model == null) continue;
            long elapsed = mc.level.getGameTime() - relay.updateGameTime;
            Vec3 pos = relay.pos.add(relay.vel.scale(Math.max(0.0D, elapsed + partialTick)));
            Vec3 dir = relay.vel.lengthSqr() > 1.0e-6D ? relay.vel.normalize() : null;
            parkGhost(mc, model, pos, dir);
            renderEntity(mc, camera, model, partialTick, viewPose,
                dir == null ? new Vec3(0, 1, 0) : dir, 0.7F);
        }
    }

    /** Ghost shell entity per type key, so a mixed-caliber salvo needs one model each. */
    private static final Map<String, Entity> salvoShellPool = new LinkedHashMap<>();

    private static Entity pooledSalvoShell(Minecraft mc, String typeKey) {
        Entity existing = salvoShellPool.get(typeKey);
        if (existing != null && existing.level() == mc.level) return existing;
        Entity created = null;
        Optional<EntityType<?>> type = EntityType.byString(typeKey);
        if (mc.level != null) {
            try {
                created = type.get().create(mc.level);
            } catch (Throwable t) {
                created = null;
            }
        }
        salvoShellPool.put(typeKey, created);
        // Bound the pool: a salvo realistically mixes one or two shell types.
        while (salvoShellPool.size() > 8) {
            var it = salvoShellPool.values().iterator();
            it.next();
            it.remove();
        }
        return created;
    }

    /** Parks a pooled ghost at an exact world position, oriented along dir. */
    private static void parkGhost(Minecraft mc, Entity model, Vec3 pos, Vec3 dir) {
        model.setPos(pos.x, pos.y, pos.z);
        model.xOld = model.xo = pos.x;
        model.yOld = model.yo = pos.y;
        model.zOld = model.zo = pos.z;
        Vec3 d = dir != null ? dir : new Vec3(0, 1, 0);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(d.y, -1.0D, 1.0D)));
        model.setYRot(yaw);
        model.setXRot(pitch);
        model.yRotO = yaw;
        model.xRotO = pitch;
        setGhostOrientation(model, d);
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
        if (ClientHitcam.isTerminal() && TBConfig.HITCAM_TERMINAL_CRITICALS.get()) {
            drawCriticalList(gui, mc, x, y, drawW);
        }
    }

    /**
     * Small damage-evaluation list in the PiP's top-left, visible as soon as
     * the terminal animation starts: each destroyed mechanical component
     * (aggregated by block) and the crew kill count. Draws at 75% font scale
     * so it reads as secondary to the status line, over a dark backdrop.
     */
    private static void drawCriticalList(net.minecraft.client.gui.GuiGraphics gui, Minecraft mc, int x, int y, int drawW) {
        List<Component> lines = buildCriticalLines();
        if (lines.isEmpty()) return;
        float scale = 0.75F;
        int lineHeight = (int) Math.ceil(mc.font.lineHeight * scale) + 1;
        int maxLineW = 0;
        int[] widths = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            widths[i] = mc.font.width(lines.get(i));
            maxLineW = Math.max(maxLineW, widths[i]);
        }
        int boxW = Math.min(drawW - 6, (int) Math.ceil(maxLineW * scale) + 6);
        int boxH = lines.size() * lineHeight + 4;
        var pose = gui.pose();
        gui.fill(x + 2, y + 2, x + 2 + boxW, y + 2 + boxH, 0x90101010);
        pose.pushPose();
        pose.translate(x + 5, y + 4, 0);
        pose.scale(scale, scale, 1.0F);
        for (int i = 0; i < lines.size(); i++) {
            gui.drawString(mc.font, lines.get(i), 0, i * (mc.font.lineHeight + 1),
                criticalLineColor(lines.get(i)), true);
        }
        pose.popPose();
    }

    private static int criticalLineColor(Component line) {
        String s = line.getString();
        if (s.startsWith("+ ")) return COLOR_MORE;
        if (s.startsWith("\u2620")) return COLOR_CREW;
        return COLOR_COMPONENT;
    }

    /** Aggregated evaluation rows: destroyed components by name, then crew kills. */
    private static List<Component> buildCriticalLines() {
        List<com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket.CriticalEntry> criticals =
            ClientTerminalView.criticals();
        if (criticals.isEmpty()) return List.of();
        Map<String, int[]> blocks = new LinkedHashMap<>(); // display name -> [count]
        int crew = 0;
        for (var entry : criticals) {
            if (entry.crew()) {
                crew++;
            } else {
                blocks.computeIfAbsent(criticalBlockName(entry.blockId()), k -> new int[1])[0]++;
            }
        }
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(blocks.entrySet());
        sorted.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue()[0], a.getValue()[0]);
            return byCount != 0 ? byCount : a.getKey().compareToIgnoreCase(b.getKey());
        });
        List<Component> lines = new ArrayList<>();
        for (Map.Entry<String, int[]> e : sorted) {
            if (lines.size() >= MAX_CRITICAL_ROWS) break;
            int count = e.getValue()[0];
            lines.add(Component.literal("\u26a1 " + e.getKey() + (count > 1 ? " \u00d7" + count : "")));
        }
        if (crew > 0 && lines.size() < MAX_CRITICAL_ROWS) {
            lines.add(Component.literal("\u2620 Crew killed \u00d7" + crew));
        }
        int total = sorted.size() + (crew > 0 ? 1 : 0);
        if (total > MAX_CRITICAL_ROWS) {
            lines.add(Component.literal("+ " + (total - MAX_CRITICAL_ROWS) + " more"));
        }
        return lines;
    }

    /** Display name of a destroyed block, resolved against the client registry. */
    private static String criticalBlockName(String blockId) {
        if (blockId == null || blockId.isEmpty()) return "Component";
        try {
            ResourceLocation id = new ResourceLocation(blockId);
            Block block = BuiltInRegistries.BLOCK.get(id);
            if (block != null) {
                String name = block.getName().getString();
                if (name != null && !name.isBlank()) return name;
            }
        } catch (Exception ignored) {
            // fall through to prettified path
        }
        String path = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        if (path.isEmpty()) return "Component";
        return Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
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
