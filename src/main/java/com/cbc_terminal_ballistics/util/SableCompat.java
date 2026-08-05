package com.cbc_terminal_ballistics.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional Sable integration via the Sable Companion ServiceLoader API.
 * No compile-time dependency on Sable — everything is reflection-based and
 * safe to call when Sable is absent.
 */
public final class SableCompat {
    private static final String SABLE_MOD_ID = "sable";
    private static final String COMPANION_CLASS = "dev.ryanhcode.sable.companion.SableCompanion";
    private static final String SUB_LEVEL_ACCESS_CLASS = "dev.ryanhcode.sable.companion.SubLevelAccess";
    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_terminal_ballistics:SableCompat");

    private static final Boolean SABLE_PRESENT = ModList.get().isLoaded(SABLE_MOD_ID);
    private static Object companionInstance;
    private static Method projectOutOfSubLevelMethod;
    private static Method projectOutOfSubLevelJomlMethod;
    private static Method distanceSquaredWithSubLevelsMethod;
    private static Method getContainingMethod;
    private static Method isInPlotGridMethod;
    private static Method logicalPoseMethod;
    private static Method getUniqueIdMethod;
    private static Method transformPositionMethod;
    private static Method transformPositionInverseMethod;
    private static Method transformNormalInverseMethod;
    private static boolean reflectionFailed;
    private static boolean reflectionInitialized;

    // ---- public API ----

    /**
     * True if the Sable mod JAR is loaded. Does NOT require reflection to succeed.
     */
    public static boolean isPresent() {
        return SABLE_PRESENT;
    }

    /**
     * True if Sable is loaded AND the Companion API reflection succeeded.
     * For rendering, always check {@link #isPresent()} as a fallback.
     */
    public static boolean isLoaded() {
        if (!SABLE_PRESENT) return false;
        if (!reflectionInitialized) initCompanion();
        return companionInstance != null;
    }

    /**
     * Transforms a position out of a Sable sub-level to world coordinates.
     * If the position is not on a sub-level, returns it unchanged.
     */
    public static Vec3 toWorldCoordinates(Level level, Vec3 position) {
        if (!isLoaded() || level == null) return position;
        try {
            Method m = projectOutOfSubLevelMethod();
            if (m != null) {
                Object result = m.invoke(companionInstance, level, position);
                if (result instanceof Vec3 vec) return vec;
            }
            if (projectOutOfSubLevelJomlMethod != null) {
                Vector3d input = new Vector3d(position.x, position.y, position.z);
                Object result = projectOutOfSubLevelJomlMethod.invoke(companionInstance, level, input);
                if (result instanceof Vector3d vec) return new Vec3(vec.x, vec.y, vec.z);
            }
        } catch (Throwable ignored) { }
        return position;
    }

    /**
     * Distance check that understands sub-level coordinates.
     */
    public static double squaredDistanceBetweenInclSubLevels(Level level, Vec3 first, Vec3 second) {
        if (isLoaded() && level != null) {
            try {
                Method method = distanceSquaredWithSubLevelsMethod;
                if (method != null) {
                    Object result = method.invoke(companionInstance, level, first, second);
                    if (result instanceof Number number) return number.doubleValue();
                }
            } catch (Throwable ignored) { }
        }

        // Both supported versions also expose projectOutOfSubLevel(Level, Vec3).
        Vec3 a = toWorldCoordinates(level, first);
        Vec3 b = toWorldCoordinates(level, second);
        return a.distanceToSqr(b);
    }

    /**
     * Converts a world hit position into the local sub-level coordinate system.
     */
    public static Vec3 toSubLevelCoordinates(Level level, BlockPos subLevelPos, Vec3 worldPosition) {
        if (!isLoaded()) return worldPosition;
        return transformWithContainingPose(level, subLevelPos, worldPosition, transformPositionInverseMethod);
    }

    /**
     * Converts a world-space hit face direction into the local sub-level face direction.
     */
    public static Direction toSubLevelDirection(Level level, BlockPos subLevelPos, Direction worldDirection) {
        Vec3 transformed = toSubLevelVector(level, subLevelPos, Vec3.atLowerCornerOf(worldDirection.getNormal()));
        return Direction.getNearest(transformed.x, transformed.y, transformed.z);
    }

    /**
     * Converts a world-space vector direction into local sub-level coordinates.
     */
    public static Vec3 toSubLevelVector(Level level, BlockPos subLevelPos, Vec3 worldVector) {
        if (!isLoaded()) return worldVector;
        return transformWithContainingPose(level, subLevelPos, worldVector, transformNormalInverseMethod);
    }

    // ---- reflection init ----

    private static void initCompanion() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        if (!SABLE_PRESENT) return;
        try {
            Class<?> companionClass = Class.forName(COMPANION_CLASS);
            LOGGER.info("Sable Companion class found: {}", companionClass.getName());
            // SableCompanion.INSTANCE is a static field
            Field instanceField = companionClass.getField("INSTANCE");
            companionInstance = instanceField.get(null);
            LOGGER.info("Sable Companion INSTANCE obtained: {}", companionInstance != null);

            // Sable 1.2.2 and 2.0.3 both bundle Companion 1.6.0. Select exact
            // stable overloads instead of depending on reflection iteration order.
            for (Method method : companionClass.getMethods()) {
                if (method.getName().equals("projectOutOfSubLevel")
                    && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == Level.class
                    && method.getParameterTypes()[1] == Vec3.class) {
                    projectOutOfSubLevelMethod = method;
                }
                if (method.getName().equals("projectOutOfSubLevel")
                    && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == Level.class
                    && method.getParameterTypes()[1] == Vector3d.class) {
                    projectOutOfSubLevelJomlMethod = method;
                }
                if (method.getName().equals("distanceSquaredWithSubLevels")
                    && method.getParameterCount() == 3
                    && method.getParameterTypes()[0] == Level.class
                    && method.getParameterTypes()[1].getName().equals("net.minecraft.core.Position")
                    && method.getParameterTypes()[2].getName().equals("net.minecraft.core.Position")) {
                    distanceSquaredWithSubLevelsMethod = method;
                }
                if (isLevelAndVec3iOverload(method, "getContaining")) {
                    getContainingMethod = method;
                }
                if (isLevelAndVec3iOverload(method, "isInPlotGrid")) {
                    isInPlotGridMethod = method;
                }
            }

            // SubLevelAccess.logicalPose()
            try {
                Class<?> subLevelClass = Class.forName(SUB_LEVEL_ACCESS_CLASS);
                for (Method method : subLevelClass.getMethods()) {
                    if (method.getName().equals("logicalPose") && method.getParameterCount() == 0) {
                        logicalPoseMethod = method;
                    } else if (method.getName().equals("getUniqueId") && method.getParameterCount() == 0) {
                        getUniqueIdMethod = method;
                    }
                }
                Class<?> returnType = logicalPoseMethod != null ? logicalPoseMethod.getReturnType() : null;
                if (returnType != null) {
                    for (Method method : returnType.getMethods()) {
                        if (method.getName().equals("transformPosition")
                            && isVec3Transform(method)) {
                            transformPositionMethod = method;
                        } else if (method.getName().equals("transformPositionInverse")
                            && isVec3Transform(method)) {
                            transformPositionInverseMethod = method;
                        } else if (method.getName().equals("transformNormalInverse")
                            && isVec3Transform(method)) {
                            transformNormalInverseMethod = method;
                        }
                    }
                }
            } catch (Throwable ignored) { }
        } catch (Throwable e) {
            reflectionFailed = true;
            LOGGER.warn("Sable Companion API reflection failed — Sable visual compatibility will use fallback: {}", e.toString());
            companionInstance = null;
        }
    }

    private static boolean isLevelAndVec3iOverload(Method method, String name) {
        return method.getName().equals(name)
            && method.getParameterCount() == 2
            && method.getParameterTypes()[0] == Level.class
            && method.getParameterTypes()[1].getName().equals("net.minecraft.core.Vec3i");
    }

    private static boolean isVec3Transform(Method method) {
        return method.getParameterCount() == 1
            && method.getParameterTypes()[0] == Vec3.class
            && method.getReturnType() == Vec3.class;
    }

    private static Vec3 transformWithContainingPose(Level level, BlockPos subLevelPos, Vec3 value, Method transformMethod) {
        if (!isLoaded() || level == null || subLevelPos == null || transformMethod == null) return value;
        try {
            Method containingMethod = getContainingMethod();
            Method poseMethod = logicalPoseMethod();
            if (containingMethod == null || poseMethod == null) return value;
            Object subLevel = containingMethod.invoke(companionInstance, level, subLevelPos);
            if (subLevel == null) return value;
            Object pose = poseMethod.invoke(subLevel);
            if (pose == null) return value;
            Object result = transformMethod.invoke(pose, value);
            return result instanceof Vec3 vec ? vec : value;
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static boolean isSableAndCompanionWorking() {
        return companionInstance != null && !reflectionFailed;
    }

    /** Public diagnostic: whether the Companion API was successfully initialised. */
    public static boolean isCompanionWorking() {
        if (!SABLE_PRESENT) return false;
        if (!reflectionInitialized) initCompanion();
        return isSableAndCompanionWorking();
    }

    private static Method projectOutOfSubLevelMethod() {
        if (projectOutOfSubLevelMethod == null && !reflectionFailed && SABLE_PRESENT) {
            initCompanion();
        }
        return projectOutOfSubLevelMethod;
    }

    private static Method getContainingMethod() {
        if (getContainingMethod == null && !reflectionFailed && SABLE_PRESENT) {
            initCompanion();
        }
        return getContainingMethod;
    }

    private static Method logicalPoseMethod() {
        if (logicalPoseMethod == null && !reflectionFailed && SABLE_PRESENT) {
            initCompanion();
        }
        return logicalPoseMethod;
    }

    private static Method transformPositionMethod() {
        if (transformPositionMethod == null && !reflectionFailed && SABLE_PRESENT) {
            initCompanion();
        }
        return transformPositionMethod;
    }

    // ---- coordinate-based heuristics ----

    /**
     * Sable plot grids can sit well inside the vanilla ±30M world border.
     * Logs from Aeronautics/Sable 1.2.x show plot coordinates around 20M, so
     * use a lower threshold only as a rendering/network fallback.
     */
    private static final int SUB_LEVEL_COORD_THRESHOLD = 10_000_000;

    public static boolean isProbablyInSubLevel(BlockPos pos) {
        if (pos == null) return false;
        return Math.abs(pos.getX()) > SUB_LEVEL_COORD_THRESHOLD
            || Math.abs(pos.getY()) > SUB_LEVEL_COORD_THRESHOLD
            || Math.abs(pos.getZ()) > SUB_LEVEL_COORD_THRESHOLD;
    }

    /**
     * Checks whether the given BlockPos is inside a Sable sub-level plot grid.
     * Uses the Companion API first; falls back to coordinate heuristics.
     */
    public static boolean isInSubLevel(Level level, BlockPos pos) {
        if (!isPresent() || level == null || pos == null) return false;
        // Fast pass: extreme coords are always a sub-level
        if (isProbablyInSubLevel(pos)) return true;
        if (!isLoaded()) return false;
        // Try Companion API
        try {
            if (isSableAndCompanionWorking()) {
                Method m = getContainingMethod();
                if (m != null) {
                    Object subLevel = m.invoke(companionInstance, level, pos);
                    if (subLevel != null) return true;
                }
                if (isInPlotGridMethod != null) {
                    Object result = isInPlotGridMethod.invoke(companionInstance, level, pos);
                    if (result instanceof Boolean inPlotGrid) return inPlotGrid;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * Checks for an actually loaded sub-level without using the coordinate
     * heuristic. Physics integration must use this before touching Sable's
     * native collider data.
     */
    public static boolean hasLoadedSubLevel(Level level, BlockPos pos) {
        if (!isLoaded() || level == null || pos == null) return false;
        try {
            Method method = getContainingMethod();
            return method != null && method.invoke(companionInstance, level, pos) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns the stable identity of the loaded sub-level containing a plot
     * position. Plot coordinates are reused by Sable, but this UUID is not.
     */
    public static UUID subLevelId(Level level, BlockPos pos) {
        if (!isLoaded() || level == null || pos == null || getUniqueIdMethod == null) return null;
        try {
            Method method = getContainingMethod();
            if (method == null) return null;
            Object subLevel = method.invoke(companionInstance, level, pos);
            if (subLevel == null) return null;
            Object result = getUniqueIdMethod.invoke(subLevel);
            return result instanceof UUID id ? id : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Transforms a BlockPos from sub-level coordinates to world coordinates
     * using the sub-level's logical pose. For non-sub-level positions returns Vec3.atCenterOf(pos).
     * Uses Companion API when available; falls back to identity for sub-level positions.
     */
    public static Vec3 subLevelPosToWorld(Level level, BlockPos pos) {
        if (!isPresent() || level == null || pos == null) return Vec3.atCenterOf(pos);
        if (!isLoaded()) return Vec3.atCenterOf(pos);
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 transformed = transformWithContainingPose(level, pos, center, transformPositionMethod());
        if (transformed != center) return transformed;
        // Fallback: return sub-level center coords (extreme values).
        // In sub-level space the camera is also in sub-level space, so relative
        // position pos-camera will be correct for rendering.
        return Vec3.atCenterOf(pos);
    }

    private SableCompat() {}
}
