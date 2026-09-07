package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
import net.minecraft.world.phys.Vec3;

/**
 * Bridges the hitcam picture-in-picture to Distant Horizons (DH). DH renders
 * its LOD terrain from a mixin on LevelRenderer.renderChunkLayer, so its draw
 * calls also fire inside our nested world pass — but DH reads the camera
 * position from Minecraft.gameRenderer.getMainCamera(), i.e. always the
 * player's camera, while the view/projection matrices it captured are the
 * hitcam camera's. The LOD terrain is therefore displaced by the whole
 * player-to-shell distance inside the picture-in-picture window.
 *
 * <p>hitcamDhMode=ATTEMPT (default) patches DH's camera wrapper to serve the
 * hitcam camera while the nested pass renders, so LOD terrain, fog and fades
 * line up in the window. hitcamDhMode=SUPPRESS cancels DH's render entry
 * points during the nested pass instead, leaving the window vanilla-only.
 * A thrown failure under ATTEMPT flips the session to SUPPRESS rather than
 * killing the view.</p>
 *
 * <p>All state here is render-thread only: the mixins that consult it fire on
 * the render thread inside our nested pass.</p>
 */
public final class DistantHorizonsCompat {
    private static Boolean dhPresent;
    private static boolean forceSuppress;

    /** Set for the duration of one nested picture-in-picture world pass. */
    private static boolean pipFrameActive;
    private static Vec3 pipCamPos;
    private static Vec3 pipLookDir;

    private static volatile boolean dhClassesWarned;

    public static boolean dhPresent() {
        if (dhPresent == null) {
            try {
                Class.forName("com.seibel.distanthorizons.core.api.internal.ClientApi");
                dhPresent = Boolean.TRUE;
            } catch (ClassNotFoundException | LinkageError e) {
                dhPresent = Boolean.FALSE;
            }
        }
        return dhPresent.booleanValue();
    }

    /** Marks the nested pass active with the hitcam camera's position and view direction. */
    public static void beginPipFrame(Vec3 camPos, Vec3 lookDir) {
        pipFrameActive = true;
        pipCamPos = camPos;
        pipLookDir = lookDir;
    }

    public static void endPipFrame() {
        pipFrameActive = false;
        pipCamPos = null;
        pipLookDir = null;
    }

    /** The hitcam camera's absolute position for the active nested pass (null outside it). */
    public static Vec3 pipCameraPosition() {
        return pipCamPos;
    }

    /** The hitcam camera's normalized view direction for the active nested pass (null outside it). */
    public static Vec3 pipLookDirection() {
        return pipLookDir;
    }

    /** True while DH's render camera should be redirected to the hitcam camera. */
    public static boolean shouldPatchCamera() {
        return pipFrameActive && pipCamPos != null && dhPresent()
            && !"SUPPRESS".equals(TBConfig.HITCAM_DH_MODE.get()) && !forceSuppress;
    }

    /** True while DH's LOD render entry points should be cancelled outright. */
    public static boolean shouldSuppressLods() {
        return pipFrameActive && dhPresent()
            && ("SUPPRESS".equals(TBConfig.HITCAM_DH_MODE.get()) || forceSuppress);
    }

    /**
     * Records a nested-pass failure while the camera patch was active. Returns
     * true exactly once per session so the caller can retry the frame under
     * SUPPRESS instead of tearing the view down; later failures fall through
     * to the normal stop behaviour.
     */
    public static boolean markAttemptFailure() {
        if (!forceSuppress) {
            forceSuppress = true;
            return true;
        }
        return false;
    }

    /** Builds DH's com.seibel.distanthorizons.core.util.math.Vec3d reflectively. */
    public static Object makeDhVec3d(Vec3 v) {
        try {
            Class<?> cls = Class.forName("com.seibel.distanthorizons.core.util.math.Vec3d");
            return cls.getConstructor(double.class, double.class, double.class)
                .newInstance(v.x, v.y, v.z);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /** Builds DH's com.seibel.distanthorizons.core.util.math.Vec3f reflectively. */
    public static Object makeDhVec3f(Vec3 v) {
        try {
            Class<?> cls = Class.forName("com.seibel.distanthorizons.core.util.math.Vec3f");
            return cls.getConstructor(float.class, float.class, float.class)
                .newInstance((float) v.x, (float) v.y, (float) v.z);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    private static void warnOnce(Throwable t) {
        if (!dhClassesWarned) {
            dhClassesWarned = true;
            com.mojang.logging.LogUtils.getLogger()
                .warn("CBCTB hitcam: could not build Distant Horizons math object, DH camera patch disabled", t);
        }
    }

    private DistantHorizonsCompat() {
    }
}
