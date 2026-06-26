package com.cbc_terminal_ballistics.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Optional Valkyrien Skies integration kept behind reflection so CBC Terminal Ballistics
 * can still load without VS present. VS stores ship blocks in shipyard coordinates, so
 * vanilla distance checks against those coordinates fail for physically nearby ships.
 * 
 * IMPLEMENTATION NOTE:
 * VSGameUtilsKt contains methods with ClientLevel parameters. On dedicated server,
 * Class.getMethod() triggers JVM verification of ALL methods in the class, which fails
 * because ClientLevel doesn't exist. We work around this by:
 * 1. Getting methods from the implementing class (MinecraftServer) rather than the interface
 * 2. Using Class.getDeclaredMethods() and filtering by name to avoid signature matching
 */
public final class VSCompat {
    private static final Logger LOG = LoggerFactory.getLogger("CBCTB/VSCompat");
    private static final String VS_GAME_UTILS = "org.valkyrienskies.mod.common.VSGameUtilsKt";
    private static final String DIMENSION_ID_PROVIDER = "org.valkyrienskies.mod.common.util.DimensionIdProvider";

    private static Boolean loaded;
    private static Method squaredDistanceMethod;
    private static Method toWorldCoordinatesMethod;
    private static boolean squaredDistanceLookupFailed;
    private static boolean toWorldLookupFailed;
    
    // Cached for direct ship world access
    private static Method getShipObjectWorldMethod;
    private static Method getDimensionIdMethod;
    private static Method getAllShipsMethod;
    private static Method isChunkInShipyardMethod;
    private static Method getByChunkPosMethod;
    private static boolean shipWorldAccessFailed;

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
        Object ship = getShipManagingPos(level, shipyardPos);
        Object worldToShip = worldToShip(ship);
        return transformPosition(worldToShip, worldPosition);
    }

    /**
     * Converts a world-space hit face direction into the local ship face direction.
     */
    public static Direction toShipDirection(Level level, BlockPos shipyardPos, Direction worldDirection) {
        Object ship = getShipManagingPos(level, shipyardPos);
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
        Object ship = getShipManagingPos(level, shipyardPos);
        Object worldToShip = worldToShip(ship);
        return transformDirection(worldToShip, worldVector);
    }

    /**
     * Converts a position from shipyard/ship coordinates to real world coordinates if VS owns it.
     */
    public static Vec3 toWorldCoordinates(Level level, Vec3 position) {
        if (!isLoaded() || level == null) return position;
        boolean isLikelyShipyard = position.x < -100000 || position.x > 100000 || position.z < -100000 || position.z > 100000;
        if (!isLikelyShipyard) return position;

        // Try VS method first (works on client/integrated server)
        try {
            Method method = toWorldCoordinatesMethod();
            if (method != null) {
                Object result = method.invoke(null, level, position);
                if (result instanceof Vec3 vec) {
                    return vec;
                }
            }
        } catch (Throwable ignored) {}

        // Manual fallback: find ship and transform (works on dedicated server)
        BlockPos pos = BlockPos.containing(position);
        Object ship = getShipManagingPos(level, pos);
        if (ship != null) {
            Object shipToWorldMatrix = shipToWorld(ship);
            if (shipToWorldMatrix != null) {
                return transformPosition(shipToWorldMatrix, position);
            }
        }
        return position;
    }

    private static Method squaredDistanceMethod() {
        if (squaredDistanceMethod != null || squaredDistanceLookupFailed) return squaredDistanceMethod;
        try {
            Class<?> utils = Class.forName(VS_GAME_UTILS);
            squaredDistanceMethod = utils.getMethod("squaredDistanceBetweenInclShips",
                Level.class, double.class, double.class, double.class, double.class, double.class, double.class);
            return squaredDistanceMethod;
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
            toWorldCoordinatesMethod = utils.getMethod("toWorldCoordinates", Level.class, Vec3.class);
            return toWorldCoordinatesMethod;
        } catch (Throwable t) {
            // This catches the class verification error on dedicated server
            // where ClientLevel doesn't exist in VSGameUtilsKt
            if (LOG.isDebugEnabled()) {
                LOG.debug("toWorldCoordinatesMethod lookup failed (expected on dedicated server): {}", t.getMessage());
            }
        }
        
        toWorldLookupFailed = true;
        return null;
    }


    /**
     * Core ship lookup using direct reflection on implementing classes.
     * This avoids VSGameUtilsKt which has client-only methods that fail verification on dedicated server.
     */
    private static Object getShipManagingPos(Level level, BlockPos pos) {
        if (!isLoaded() || level == null || pos == null) return null;

        Object ship = tryDirectShipWorldAccess(level, pos);
        if (ship != null) return ship;

        return null;
    }

    /**
     * Direct access to ship world via MinecraftServer's implementation of IShipObjectWorldServerProvider.
     * This bypasses VSGameUtilsKt entirely to avoid client-only method signatures.
     * 
     * Key insight: We get methods from the ACTUAL implementing class (DedicatedServer/IntegratedServer)
     * rather than the interface, which avoids JVM verification of the interface file that contains
     * IShipObjectWorldClientProvider (which has ClientShipWorldCore references).
     */
    private static Object tryDirectShipWorldAccess(Level level, BlockPos pos) {
        if (shipWorldAccessFailed) return null;
        if (!(level instanceof ServerLevel serverLevel)) return null;

        try {
            // Step 1: Get MinecraftServer
            MinecraftServer server = serverLevel.getServer();

            // Step 2: Get shipObjectWorld - use getDeclaredMethods() to avoid interface verification
            if (getShipObjectWorldMethod == null) {
                getShipObjectWorldMethod = findMethodByName(server.getClass(), "getShipObjectWorld");
                if (getShipObjectWorldMethod == null) {
                    shipWorldAccessFailed = true;
                    return null;
                }
                getShipObjectWorldMethod.setAccessible(true);
            }

            Object shipWorld = getShipObjectWorldMethod.invoke(server);
            if (shipWorld == null) {
                shipWorldAccessFailed = true;
                return null;
            }

            // Step 3: Get dimensionId from Level
            if (getDimensionIdMethod == null) {
                getDimensionIdMethod = findMethodByName(level.getClass(), "getDimensionId");
                if (getDimensionIdMethod == null) {
                    // Try the interface
                    Class<?> dimProviderClass = Class.forName(DIMENSION_ID_PROVIDER);
                    getDimensionIdMethod = findMethodByName(dimProviderClass, "getDimensionId");
                }
                if (getDimensionIdMethod != null) {
                    getDimensionIdMethod.setAccessible(true);
                }
            }

            if (getDimensionIdMethod == null) {
                shipWorldAccessFailed = true;
                return null;
            }

            Object dimensionId = getDimensionIdMethod.invoke(level);

            // Step 4: Get allShips
            if (getAllShipsMethod == null) {
                getAllShipsMethod = findMethodByName(shipWorld.getClass(), "getAllShips");
                if (getAllShipsMethod != null) {
                    getAllShipsMethod.setAccessible(true);
                }
            }

            if (getAllShipsMethod == null) {
                shipWorldAccessFailed = true;
                return null;
            }

            Object allShips = getAllShipsMethod.invoke(shipWorld);

            // Step 5: Check isChunkInShipyard
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            if (isChunkInShipyardMethod == null) {
                isChunkInShipyardMethod = findMethodByName(shipWorld.getClass(), "isChunkInShipyard");
                if (isChunkInShipyardMethod != null) {
                    isChunkInShipyardMethod.setAccessible(true);
                }
            }

            if (isChunkInShipyardMethod != null) {
                try {
                    Boolean inShipyard = (Boolean) isChunkInShipyardMethod.invoke(shipWorld, chunkX, chunkZ, dimensionId);
                    if (!inShipyard) return null;
                } catch (Throwable ignored) {}
            }

            // Step 6: Get ship by chunk position
            if (getByChunkPosMethod == null) {
                getByChunkPosMethod = findMethodByName(allShips.getClass(), "getByChunkPos");
                if (getByChunkPosMethod != null) {
                    getByChunkPosMethod.setAccessible(true);
                }
            }

            if (getByChunkPosMethod == null) {
                shipWorldAccessFailed = true;
                return null;
            }

            return getByChunkPosMethod.invoke(allShips, chunkX, chunkZ, dimensionId);

        } catch (Throwable t) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Direct ship world access failed: {}", t.getMessage());
            }
        }

        shipWorldAccessFailed = true;
        return null;
    }

    /**
     * Find a method by name, ignoring parameter types.
     * This avoids JVM verification issues with specific parameter type matching.
     */
    private static Method findMethodByName(Class<?> clazz, String methodName) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        // Also check declared methods (for non-public)
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        return null;
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

    private static Object shipToWorld(Object ship) {
        if (ship == null) return null;

        try {
            Method direct = ship.getClass().getMethod("getShipToWorld");
            Object matrix = direct.invoke(ship);
            if (matrix != null) return matrix;
        } catch (Throwable ignored) {}
        try {
            Method transformGetter = ship.getClass().getMethod("getTransform");
            Object transform = transformGetter.invoke(ship);
            if (transform == null) return null;
            Method matrixGetter = transform.getClass().getMethod("getShipToWorld");
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
