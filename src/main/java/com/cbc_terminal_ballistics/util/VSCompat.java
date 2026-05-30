package com.cbc_terminal_ballistics.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * Optional Valkyrien Skies integration kept behind reflection so CBC Terminal Ballistics
 * can still load without VS present. VS stores ship blocks in shipyard coordinates, so
 * vanilla distance checks against those coordinates fail for physically nearby ships.
 */
public final class VSCompat {
    private static final String VS_GAME_UTILS = "org.valkyrienskies.mod.common.VSGameUtilsKt";

    private static Boolean loaded;
    private static Method squaredDistanceMethod;
    private static Method toWorldCoordinatesMethod;
    private static Method getLoadedShipManagingPosMethod;
    private static boolean squaredDistanceLookupFailed;
    private static boolean toWorldLookupFailed;
    private static boolean getShipLookupFailed;

    public static boolean isLoaded() {
        if (loaded == null) {
            try {
                loaded = ModList.get().isLoaded("valkyrienskies");
            } catch (Throwable ignored) {
                loaded = false;
            }
        }
        return loaded;
    }

    /**
     * Distance check that understands VS shipyard coordinates on either endpoint.
     */
    public static double squaredDistanceBetweenInclShips(Level level, Vec3 first, Vec3 second) {
        if (!isLoaded() || level == null) return first.distanceToSqr(second);
        try {
            Method method = squaredDistanceMethod();
            if (method != null) {
                Object result = method.invoke(null, level, first.x, first.y, first.z, second.x, second.y, second.z);
                if (result instanceof Number number) return number.doubleValue();
            }
        } catch (Throwable ignored) {
            squaredDistanceLookupFailed = true;
        }

        Vec3 firstWorld = toWorldCoordinates(level, first);
        Vec3 secondWorld = toWorldCoordinates(level, second);
        return firstWorld.distanceToSqr(secondWorld);
    }



    /**
     * Converts a world hit position into the local shipyard coordinate system for the ship
     * managing {@code shipyardPos}. If the block is not on a VS ship, returns the original position.
     */
    public static Vec3 toShipCoordinates(Level level, BlockPos shipyardPos, Vec3 worldPosition) {
        Object ship = getLoadedShipManagingPos(level, shipyardPos);
        Object worldToShip = worldToShip(ship);
        return transformPosition(worldToShip, worldPosition);
    }

    /**
     * Converts a world-space hit face direction into the local ship face direction.
     */
    public static Direction toShipDirection(Level level, BlockPos shipyardPos, Direction worldDirection) {
        Object ship = getLoadedShipManagingPos(level, shipyardPos);
        Object worldToShip = worldToShip(ship);
        if (worldToShip == null) return worldDirection;
        Vec3 local = transformDirection(worldToShip, Vec3.atLowerCornerOf(worldDirection.getNormal()));
        if (local.lengthSqr() < 1.0e-8D) return worldDirection;
        local = local.normalize();
        Direction best = worldDirection;
        double bestDot = -Double.MAX_VALUE;
        for (Direction direction : Direction.values()) {
            double dot = local.dot(Vec3.atLowerCornerOf(direction.getNormal()));
            if (dot > bestDot) {
                bestDot = dot;
                best = direction;
            }
        }
        return best;
    }

    /**
     * Converts a world-space vector direction into local ship coordinates for the ship managing
     * {@code shipyardPos}. If the block is not on a VS ship, returns the original vector.
     */
    public static Vec3 toShipVector(Level level, BlockPos shipyardPos, Vec3 worldVector) {
        Object ship = getLoadedShipManagingPos(level, shipyardPos);
        Object worldToShip = worldToShip(ship);
        return transformDirection(worldToShip, worldVector);
    }

    /**
     * Converts a position from shipyard/ship coordinates to real world coordinates if VS owns it.
     */
    public static Vec3 toWorldCoordinates(Level level, Vec3 position) {
        if (!isLoaded() || level == null) return position;
        try {
            Method method = toWorldCoordinatesMethod();
            if (method != null) {
                Object result = method.invoke(null, level, position);
                if (result instanceof Vec3 vec) return vec;
            }
        } catch (Throwable ignored) {
            toWorldLookupFailed = true;
        }
        return position;
    }

    private static Method squaredDistanceMethod() {
        if (squaredDistanceMethod != null || squaredDistanceLookupFailed) return squaredDistanceMethod;
        try {
            Class<?> utils = Class.forName(VS_GAME_UTILS);
            for (Method method : utils.getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!method.getName().equals("squaredDistanceBetweenInclShips") || params.length != 7) continue;
                if (!params[0].isAssignableFrom(Level.class)) continue;
                boolean doubles = true;
                for (int i = 1; i < params.length; i++) {
                    if (params[i] != double.class) {
                        doubles = false;
                        break;
                    }
                }
                if (!doubles) continue;
                squaredDistanceMethod = method;
                return squaredDistanceMethod;
            }
        } catch (Throwable ignored) {
            // Fall through to vanilla distance.
        }
        squaredDistanceLookupFailed = true;
        return null;
    }

    private static Method toWorldCoordinatesMethod() {
        if (toWorldCoordinatesMethod != null || toWorldLookupFailed) return toWorldCoordinatesMethod;
        try {
            Class<?> utils = Class.forName(VS_GAME_UTILS);
            for (Method method : utils.getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!method.getName().equals("toWorldCoordinates") || params.length != 2) continue;
                if (!params[0].isAssignableFrom(Level.class)) continue;
                if (params[1] != Vec3.class) continue;
                toWorldCoordinatesMethod = method;
                return toWorldCoordinatesMethod;
            }
        } catch (Throwable ignored) {
            // Fall through to identity transform.
        }
        toWorldLookupFailed = true;
        return null;
    }


    private static Object getLoadedShipManagingPos(Level level, BlockPos pos) {
        if (!isLoaded() || level == null || pos == null) return null;
        try {
            Method method = getLoadedShipManagingPosMethod();
            return method == null ? null : method.invoke(null, level, pos);
        } catch (Throwable ignored) {
            getShipLookupFailed = true;
            return null;
        }
    }

    private static Method getLoadedShipManagingPosMethod() {
        if (getLoadedShipManagingPosMethod != null || getShipLookupFailed) return getLoadedShipManagingPosMethod;
        try {
            Class<?> utils = Class.forName(VS_GAME_UTILS);
            Method fallback = null;
            for (Method method : utils.getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!isShipManagingPosMethod(method.getName()) || params.length != 2) continue;
                if (!params[0].isAssignableFrom(Level.class)) continue;
                if (!params[1].isAssignableFrom(BlockPos.class) && !params[1].isAssignableFrom(Vec3i.class)) continue;
                if (params[0] == Level.class) {
                    getLoadedShipManagingPosMethod = method;
                    return getLoadedShipManagingPosMethod;
                }
                if (fallback == null) fallback = method;
            }
            getLoadedShipManagingPosMethod = fallback;
            return getLoadedShipManagingPosMethod;
        } catch (Throwable ignored) {
            getShipLookupFailed = true;
            return null;
        }
    }

    private static boolean isShipManagingPosMethod(String name) {
        // VS 2.4+ exposes getLoadedShipManagingPos in some builds; VS 2.3's
        // public helpers are named getShipObjectManagingPos/getShipManagingPos.
        // Accept all known spellings so shipyard coordinates can be resolved in
        // both the modern and legacy stacks.
        return name.equals("getLoadedShipManagingPos")
            || name.equals("getShipObjectManagingPos")
            || name.equals("getShipManagingPos");
    }

    private static Object worldToShip(Object ship) {
        if (ship == null) return null;
        try {
            Method direct = ship.getClass().getMethod("getWorldToShip");
            Object matrix = direct.invoke(ship);
            if (matrix != null) return matrix;
        } catch (Throwable ignored) {
            // Try the ShipTransform path below.
        }
        try {
            Method transformGetter = ship.getClass().getMethod("getTransform");
            Object transform = transformGetter.invoke(ship);
            if (transform == null) return null;
            Method matrixGetter = transform.getClass().getMethod("getWorldToShip");
            return matrixGetter.invoke(transform);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Vec3 transformPosition(Object matrix, Vec3 position) {
        if (matrix == null) return position;
        try {
            Vector3d dest = new Vector3d();
            Method method = matrix.getClass().getMethod("transformPosition", double.class, double.class, double.class, Vector3d.class);
            Object result = method.invoke(matrix, position.x, position.y, position.z, dest);
            Vector3d transformed = result instanceof Vector3d vec ? vec : dest;
            return new Vec3(transformed.x, transformed.y, transformed.z);
        } catch (Throwable ignored) {
            return position;
        }
    }

    private static Vec3 transformDirection(Object matrix, Vec3 direction) {
        if (matrix == null) return direction;
        try {
            Vector3d dest = new Vector3d();
            Method method = matrix.getClass().getMethod("transformDirection", double.class, double.class, double.class, Vector3d.class);
            Object result = method.invoke(matrix, direction.x, direction.y, direction.z, dest);
            Vector3d transformed = result instanceof Vector3d vec ? vec : dest;
            return new Vec3(transformed.x, transformed.y, transformed.z);
        } catch (Throwable ignored) {
            return direction;
        }
    }

    private VSCompat() {}
}
