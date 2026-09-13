package com.cbc_terminal_ballistics.client.hitcam;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * Owns all mutable render state touched by the nested hitcam world pass.
 * Keeping this state in one scope is deliberate: a renderer or a mod hook may
 * throw after changing state, and the main frame must still be restored.
 */
public final class PipRenderScope implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static boolean framebufferWarning;
    private static boolean projectionWarning;
    private static boolean modelViewWarning;
    private static boolean cameraWarning;
    private static boolean cullWarning;

    private final Minecraft minecraft;
    private final RenderLevelStageEvent event;
    private final boolean fullScene;
    private final int readFramebuffer;
    private final int drawFramebuffer;
    private final int[] viewport = new int[4];
    private final int[] scissorBox = new int[4];
    private final boolean scissorEnabled;
    private final boolean blendEnabled;
    private final boolean depthEnabled;
    private final boolean cullEnabled;
    private final boolean polygonOffsetFill;
    private final boolean polygonOffsetLine;
    private final boolean polygonOffsetPoint;
    private final boolean depthMask;
    private final int depthFunction;
    private final int blendSrcRgb;
    private final int blendDstRgb;
    private final int blendSrcAlpha;
    private final int blendDstAlpha;
    private final int cullFace;
    private final float polygonOffsetFactor;
    private final float polygonOffsetUnits;
    private final float lineWidth;
    private final int activeTexture;
    private final int textureBinding;
    private final int currentProgram;
    private final int[] shaderTextures = new int[4];
    private final net.minecraft.client.renderer.ShaderInstance shader;
    private final Matrix4f projection;
    private final com.mojang.blaze3d.vertex.VertexSorting vertexSorting;
    private final Matrix4f modelView;
    private final Matrix4f eventPose;
    private final Matrix3f eventNormal;
    private final float fogStart;
    private final float fogEnd;
    private final float[] fogColor;
    private final com.mojang.blaze3d.shaders.FogShape fogShape;
    private final float[] shaderColor;
    private final Camera mainCamera;
    private final Quaternionf mainCameraOrientation;
    private final net.minecraft.world.entity.Entity crosshairPickEntity;
    private boolean closed;

    private PipRenderScope(Minecraft minecraft, RenderLevelStageEvent event, boolean fullScene) {
        this.minecraft = minecraft;
        this.event = event;
        this.fullScene = fullScene;
        this.readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        this.drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        this.scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        this.blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        this.depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        this.cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        this.polygonOffsetFill = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        this.polygonOffsetLine = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_LINE);
        this.polygonOffsetPoint = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_POINT);
        this.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        this.depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        this.blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        this.blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        this.blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        this.blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        this.cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        this.polygonOffsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        this.polygonOffsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        this.lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        this.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        this.textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        this.currentProgram = GL11.glGetInteger(GL20_CURRENT_PROGRAM);
        for (int i = 0; i < shaderTextures.length; i++) shaderTextures[i] = RenderSystem.getShaderTexture(i);
        this.shader = RenderSystem.getShader();
        this.projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        this.vertexSorting = RenderSystem.getVertexSorting();
        this.modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        PoseStack poseStack = event.getPoseStack();
        this.eventPose = poseStack == null ? null : new Matrix4f(poseStack.last().pose());
        this.eventNormal = poseStack == null ? null : new Matrix3f(poseStack.last().normal());
        this.fogStart = RenderSystem.getShaderFogStart();
        this.fogEnd = RenderSystem.getShaderFogEnd();
        this.fogColor = RenderSystem.getShaderFogColor().clone();
        this.fogShape = RenderSystem.getShaderFogShape();
        this.shaderColor = RenderSystem.getShaderColor().clone();
        this.mainCamera = minecraft.gameRenderer.getMainCamera();
        this.mainCameraOrientation = new Quaternionf(minecraft.getEntityRenderDispatcher().cameraOrientation());
        this.crosshairPickEntity = minecraft.getEntityRenderDispatcher().crosshairPickEntity;
        DEPTH.set(DEPTH.get() + 1);
    }

    private static final int GL20_CURRENT_PROGRAM = 0x8B8D;

    public static PipRenderScope enter(Minecraft minecraft, RenderLevelStageEvent event, boolean fullScene) {
        return new PipRenderScope(minecraft, event, fullScene);
    }

    public static int depth() {
        return DEPTH.get();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            DistantHorizonsCompat.endPipFrame();
            ClientHitcamPiP.clearShipGhostGl();
            minecraft.gameRenderer.lightTexture().turnOffLightLayer();

            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            RenderSystem.setShaderFogShape(fogShape);
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            RenderSystem.setProjectionMatrix(projection, vertexSorting);
            PoseStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.last().pose().set(modelView);
            modelViewStack.last().normal().identity();
            RenderSystem.applyModelViewMatrix();
            if (eventPose != null && event.getPoseStack() != null) {
                event.getPoseStack().last().pose().set(eventPose);
                event.getPoseStack().last().normal().set(eventNormal);
            }
            if (shader != null) RenderSystem.setShader(() -> shader);
            for (int i = 0; i < shaderTextures.length; i++) RenderSystem.setShaderTexture(i, shaderTextures[i]);

            restoreGlState();
            if (fullScene) {
                minecraft.levelRenderer.prepareCullFrustum(event.getPoseStack(), mainCamera.getPosition(), event.getProjectionMatrix());
            }
            var dispatcher = minecraft.getEntityRenderDispatcher();
            dispatcher.prepare(minecraft.level, mainCamera, crosshairPickEntity);
            dispatcher.overrideCameraOrientation(mainCameraOrientation);

            diagnose();
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("CBCTB hitcam: render-state restoration failed", t);
        } finally {
            DEPTH.set(Math.max(0, DEPTH.get() - 1));
            if (DEPTH.get() == 0) DEPTH.remove();
        }
    }

    private void restoreGlState() {
        if (blendEnabled) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        if (depthEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(depthFunction);
        GL11.glDepthMask(depthMask);
        if (cullEnabled) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glCullFace(cullFace);
        setEnabled(GL11.GL_POLYGON_OFFSET_FILL, polygonOffsetFill);
        setEnabled(GL11.GL_POLYGON_OFFSET_LINE, polygonOffsetLine);
        setEnabled(GL11.GL_POLYGON_OFFSET_POINT, polygonOffsetPoint);
        GL11.glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits);
        GL11.glLineWidth(lineWidth);
        if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL13.glActiveTexture(activeTexture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBinding);
        GL20.glUseProgram(currentProgram);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
    }

    private void diagnose() {
        if (!framebufferWarning && (GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != readFramebuffer
            || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != drawFramebuffer)) {
            framebufferWarning = true;
            log("framebuffer");
        }
        if (!projectionWarning && !projection.equals(RenderSystem.getProjectionMatrix())) { projectionWarning = true; log("projection"); }
        if (!modelViewWarning && !modelView.equals(RenderSystem.getModelViewMatrix())) { modelViewWarning = true; log("model-view"); }
        if (!cameraWarning && minecraft.getEntityRenderDispatcher().camera != mainCamera) { cameraWarning = true; log("dispatcher camera"); }
    }

    private static void log(String category) {
        com.mojang.logging.LogUtils.getLogger().warn("CBCTB hitcam: PiP {} state did not match its main-view snapshot after restore", category);
    }
}
