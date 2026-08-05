package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.util.SableCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Client-side Sable rendering compatibility using the Sable Companion API via reflection.
 * No compile-time dependency on Sable.
 */
public final class SableClientCompat {
    private static final String COMPANION_CLASS = "dev.ryanhcode.sable.companion.SableCompanion";
    private static final String CLIENT_SUB_LEVEL_ACCESS = "dev.ryanhcode.sable.companion.ClientSubLevelAccess";
    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_terminal_ballistics:SableClientCompat");

    private static Object companionInstance;
    private static Method getContainingClientMethod;
    private static Method renderPoseMethod;
    private static Method posePositionMethod;
    private static Method poseOrientationMethod;
    private static Method poseRotationPointMethod;
    private static Method poseTransformPositionMethod;
    private static Method poseTransformNormalMethod;
    private static boolean lookupFailed;
    private static int debugCounter = 0;

    /**
     * Applies the Sable sub-level render transform to the PoseStack if the block
     * is on a Sable ship. Returns true if the transform was applied.
     */
    public static boolean transformRenderWithSubLevel(ClientLevel level, BlockPos pos, PoseStack poseStack, Vec3 camera) {
        RenderTransform renderTransform = renderTransformWithSubLevel(level, pos, camera);
        if (renderTransform == null) {
            return false;
        }
        try {
            Object renderPose = renderTransform.renderPose();
            Vector3dc position = posePosition(renderPose);
            Quaterniondc orientation = poseOrientation(renderPose);
            Vector3dc rotationPoint = poseRotationPoint(renderPose);
            if (position == null || orientation == null || rotationPoint == null) return false;

            Vector3d rotatedRotationPoint = orientation.transform(new Vector3d(rotationPoint));
            Matrix4f transform = new Matrix4f()
                .translate(
                    (float) (position.x() - rotatedRotationPoint.x - camera.x),
                    (float) (position.y() - rotatedRotationPoint.y - camera.y),
                    (float) (position.z() - rotatedRotationPoint.z - camera.z))
                .rotate(new Quaternionf(orientation));
            poseStack.mulPose(transform);
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static RenderTransform renderTransformWithSubLevel(ClientLevel level, BlockPos pos, Vec3 camera) {
        if (!SableCompat.isLoaded() || level == null || camera == null) {
            return null;
        }
        try {
            initCompanion();
            if (companionInstance == null) {
                return null;
            }

            // Find sub-level using raw coordinates. Sable routes extreme coordinates
            // (e.g. 134217728) to the correct sub-level plot automatically.
            Method containingMethod = getContainingClientMethod();
            if (containingMethod == null) {
                return null;
            }
            Object subLevel = invokeContaining(containingMethod, level, pos);
            if (subLevel == null) {
                // Fallback: project local coords to world coords and retry lookup.
                // Some Sable sub-levels are identified by their world-space plot position.
                Vec3 worldCenter = SableCompat.toWorldCoordinates(level, Vec3.atCenterOf(pos));
                BlockPos worldPos = BlockPos.containing(worldCenter);
                subLevel = invokeContaining(containingMethod, level, worldPos);
                if (subLevel == null) {
                    return null;
                }
            }

            // Get the live render pose — contains translation + rotation of the ship.
            Method rpMethod = renderPoseMethod();
            if (rpMethod == null) return null;
            Object renderPose = rpMethod.invoke(subLevel);
            if (renderPose == null) return null;

            if (debugCounter < 10) {
                LOGGER.warn("SableClientCompat transform applied for pos={}", pos.toShortString());
                debugCounter++;
            }
            return new RenderTransform(renderPose, camera);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeContaining(Method method, Level level, BlockPos pos) throws Throwable {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 2) {
            if (paramTypes[0] == Level.class && paramTypes[1] == int.class) {
                // Unusual: (Level, int) — just try.
                return method.invoke(companionInstance, level, pos.getX());
            }
            if (paramTypes[0] == Level.class) {
                // (Level, Vec3i) or similar — use Level + BlockPos (extends Vec3i)
                return method.invoke(companionInstance, level, pos);
            }
            if (paramTypes[0] == int.class) {
                // Companion's integer overload uses chunk coordinates.
                return method.invoke(companionInstance, pos.getX() >> 4, pos.getZ() >> 4);
            }
            if (paramTypes[0] == double.class) {
                // (double x, double z)
                return method.invoke(companionInstance, (double) pos.getX(), (double) pos.getZ());
            }
        } else if (paramTypes.length == 1) {
            // (Vec3i)
            return method.invoke(companionInstance, pos);
        }
        // Fallback: just try both common signatures via reflection
        return method.invoke(companionInstance, level, pos);
    }

    // ---- reflection init ----

    private static void initCompanion() {
        if (companionInstance != null || lookupFailed) return;
        try {
            Class<?> companionClass = Class.forName(COMPANION_CLASS);
            Field instanceField = companionClass.getField("INSTANCE");
            companionInstance = instanceField.get(null);
            LOGGER.warn("SableClientCompat initCompanion: found Companion class, instance={}", companionInstance != null);

            getContainingClientMethod = findContainingClientMethod(companionClass);
            if (getContainingClientMethod == null) {
                LOGGER.warn("SableClientCompat initCompanion: getContainingClient NOT FOUND! Available methods:");
                for (Method m : companionClass.getMethods()) {
                    if (m.getName().contains("Containing"))
                        LOGGER.warn("  {} params={}", m.getName(), java.util.Arrays.toString(m.getParameterTypes()));
                }
            } else {
                LOGGER.warn("SableClientCompat initCompanion: found getContainingClient params={}", java.util.Arrays.toString(getContainingClientMethod.getParameterTypes()));
            }

            // ClientSubLevelAccess.renderPose()
            try {
                Class<?> clientSubClass = Class.forName(CLIENT_SUB_LEVEL_ACCESS);
                for (Method method : clientSubClass.getMethods()) {
                    if (method.getName().equals("renderPose") && method.getParameterCount() == 0) {
                        renderPoseMethod = method;
                        LOGGER.warn("SableClientCompat initCompanion: found renderPose() returnType={}", method.getReturnType().getName());
                        break;
                    }
                }
                if (renderPoseMethod == null) {
                    LOGGER.warn("SableClientCompat initCompanion: renderPose() NOT FOUND in ClientSubLevelAccess");
                }
            } catch (Throwable t) { LOGGER.warn("SableClientCompat initCompanion: ClientSubLevelAccess error: {}", t.toString()); }
        } catch (Throwable e) {
            lookupFailed = true;
            companionInstance = null;
            LOGGER.warn("SableClientCompat initCompanion: FAILED: {}", e.toString());
        }
    }

    private static Method findContainingClientMethod(Class<?> companionClass) {
        Method fallback = null;
        for (Method method : companionClass.getMethods()) {
            if (!method.getName().equals("getContainingClient")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0].getName().contains("Vec3i")) {
                return method;
            }
            if (params.length == 2 && params[0] == int.class && params[1] == int.class) {
                fallback = method;
            } else if (fallback == null && params.length == 2 && params[0] == double.class && params[1] == double.class) {
                fallback = method;
            } else if (fallback == null && params.length == 1) {
                fallback = method;
            }
        }
        return fallback;
    }

    private static Method getContainingClientMethod() {
        if (getContainingClientMethod == null && !lookupFailed && SableCompat.isLoaded()) {
            initCompanion();
        }
        return getContainingClientMethod;
    }

    private static Method renderPoseMethod() {
        if (renderPoseMethod == null && !lookupFailed && SableCompat.isLoaded()) {
            initCompanion();
        }
        return renderPoseMethod;
    }

    private static Vector3dc posePosition(Object renderPose) throws Throwable {
        if (posePositionMethod == null) {
            posePositionMethod = renderPose.getClass().getMethod("position");
        }
        Object result = posePositionMethod.invoke(renderPose);
        return result instanceof Vector3dc vec ? vec : null;
    }

    private static Quaterniondc poseOrientation(Object renderPose) throws Throwable {
        if (poseOrientationMethod == null) {
            poseOrientationMethod = renderPose.getClass().getMethod("orientation");
        }
        Object result = poseOrientationMethod.invoke(renderPose);
        return result instanceof Quaterniondc quat ? quat : null;
    }

    private static Vector3dc poseRotationPoint(Object renderPose) throws Throwable {
        if (poseRotationPointMethod == null) {
            poseRotationPointMethod = renderPose.getClass().getMethod("rotationPoint");
        }
        Object result = poseRotationPointMethod.invoke(renderPose);
        return result instanceof Vector3dc vec ? vec : null;
    }

    private static Vec3 transformPosition(Object renderPose, Vec3 position) throws Throwable {
        if (poseTransformPositionMethod == null) {
            poseTransformPositionMethod = renderPose.getClass().getMethod("transformPosition", Vec3.class);
        }
        Object result = poseTransformPositionMethod.invoke(renderPose, position);
        return result instanceof Vec3 vec ? vec : position;
    }

    private static Vec3 transformNormal(Object renderPose, Vec3 normal) throws Throwable {
        if (poseTransformNormalMethod == null) {
            poseTransformNormalMethod = renderPose.getClass().getMethod("transformNormal", Vec3.class);
        }
        Object result = poseTransformNormalMethod.invoke(renderPose, normal);
        return result instanceof Vec3 vec ? vec.normalize() : normal;
    }

    public record RenderTransform(Object renderPose, Vec3 camera) {
        public Vec3 position(Vec3 subLevelPosition) {
            try {
                return transformPosition(renderPose, subLevelPosition).subtract(camera);
            } catch (Throwable ignored) {
                return subLevelPosition.subtract(camera);
            }
        }

        public Vec3 normal(Vec3 subLevelNormal) {
            try {
                return transformNormal(renderPose, subLevelNormal);
            } catch (Throwable ignored) {
                return subLevelNormal;
            }
        }
    }

    private SableClientCompat() {}
}
