package com.cbc_terminal_ballistics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/** Client-only optional Valkyrien Skies rendering hooks. */
public final class VSClientCompat {
    private static final Logger LOG = LoggerFactory.getLogger("CBCTB/VSClientCompat");
    private static final String VS_GAME_UTILS = "org.valkyrienskies.mod.common.VSGameUtilsKt";
    private static final String VS_CLIENT_GAME_UTILS = "org.valkyrienskies.mod.common.VSClientGameUtils";

    private static Boolean loaded;
    private static Method getLoadedShipManagingPosMethod;
    private static Method transformRenderWithShipMethod;
    private static Method getShipsIntersectingMethod;
    private static boolean getShipLookupFailed;
    private static boolean transformLookupFailed;
    private static boolean shipsIntersectingLookupFailed;

    static boolean transformRenderWithShip(ClientLevel level, BlockPos pos, PoseStack poseStack, Vec3 camera) {
        if (!isLoaded() || level == null) return false;
        Object ship = getLoadedShipManagingPos(level, pos);
        if (ship == null) {
            return false;
        }
        try {
            Method transformGetter = ship.getClass().getMethod("getRenderTransform");
            Object renderTransform = transformGetter.invoke(ship);
            Method transform = transformRenderWithShipMethod();
            if (renderTransform == null || transform == null) return false;
            transform.invoke(null, renderTransform, poseStack, pos, camera.x, camera.y, camera.z);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static BlockState getBlockStateForPos(ClientLevel level, BlockPos worldPos) {
        if (!isLoaded() || level == null) return null;

        try {
            Iterable<?> ships = getShipsIntersecting(level, worldPos);
            if (ships == null) return null;

            for (Object ship : ships) {
                Object worldToShipMatrix = worldToShip(ship);
                if (worldToShipMatrix == null) continue;

                Vec3 shipyardPos = transformPosition(worldToShipMatrix, Vec3.atCenterOf(worldPos));
                BlockPos shipyardBlockPos = BlockPos.containing(shipyardPos);

                BlockState state = level.getBlockState(shipyardBlockPos);
                if (!state.isAir()) {
                    LOG.debug("[CBCTB] getBlockStateForPos: found block at shipyardPos={} for worldPos={}", shipyardBlockPos, worldPos);
                    return state;
                }
            }
        } catch (Throwable t) {
            LOG.debug("[CBCTB] getBlockStateForPos: exception: {}", t.getMessage());
        }
        return null;
    }

    static BlockPos toShipyardCoordinates(ClientLevel level, BlockPos worldPos) {
        if (!isLoaded() || level == null) return worldPos;

        try {
            Iterable<?> ships = getShipsIntersecting(level, worldPos);
            if (ships == null) return worldPos;

            for (Object ship : ships) {
                Object worldToShipMatrix = worldToShip(ship);
                if (worldToShipMatrix == null) continue;

                Vec3 shipyardPos = transformPosition(worldToShipMatrix, Vec3.atCenterOf(worldPos));
                BlockPos shipyardBlockPos = BlockPos.containing(shipyardPos);

                BlockState state = level.getBlockState(shipyardBlockPos);
                if (!state.isAir()) {
                    return shipyardBlockPos;
                }
            }
        } catch (Throwable ignored) {}
        return worldPos;
    }

    private static Iterable<?> getShipsIntersectingBox(ClientLevel level, AABB aabb) {
        try {
            Method method = getShipsIntersectingMethod();
            if (method == null) return null;
            Object result = method.invoke(null, level, aabb);
            if (result instanceof Iterable<?> iter) return iter;
        } catch (Throwable t) {
            LOG.debug("[CBCTB] getShipsIntersectingBox: exception: {}", t.getMessage());
            shipsIntersectingLookupFailed = true;
        }
        return null;
    }

    private static Iterable<?> getShipsIntersecting(ClientLevel level, BlockPos pos) {
        AABB aabb = new AABB(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                             pos.getX() + 2, pos.getY() + 2, pos.getZ() + 2);
        return getShipsIntersectingBox(level, aabb);
    }

    private static Method getShipsIntersectingMethod() {
        if (getShipsIntersectingMethod != null || shipsIntersectingLookupFailed) return getShipsIntersectingMethod;
        try {
            Class<?> utils = Class.forName(VS_GAME_UTILS);
            getShipsIntersectingMethod = utils.getMethod("getShipsIntersecting", Level.class, AABB.class);
            return getShipsIntersectingMethod;
        } catch (Throwable ignored) {}
        shipsIntersectingLookupFailed = true;
        return null;
    }

    private static Object worldToShip(Object ship) {
        if (ship == null) return null;
        try {
            Method direct = ship.getClass().getMethod("getWorldToShip");
            Object matrix = direct.invoke(ship);
            if (matrix != null) return matrix;
        } catch (Throwable ignored) {}
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

    // ---- shipyard -> world (moving-ship re-anchoring) ---------------------------

    private static Method shipToWorldMatrixMethod;

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
            if (shipToWorldMatrixMethod == null) {
                shipToWorldMatrixMethod = transform.getClass().getMethod("getShipToWorld");
            }
            return shipToWorldMatrixMethod.invoke(transform);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The ship whose current transform maps {@code shipyardRef} closest to
     * {@code worldRef} among the ships intersecting that world position, or
     * null when none matches closely enough (ship unloaded). Used to re-anchor
     * hitcam geometry stored in shipyard coordinates to the live ship.
     */
    public static Object shipForReanchor(ClientLevel level, Vec3 worldRef, Vec3 shipyardRef) {
        if (!isLoaded() || level == null) return null;
        // Primary route: the ship managing the exact world block the snapshot
        // was anchored to. Exact-position lookup cannot match a wrong ship, and
        // unlike the box scan below it does not degrade as the ship sails away
        // from the snapshot point.
        Object direct = getLoadedShipManagingPos(level, BlockPos.containing(worldRef));
        if (direct != null && shipMatches(direct, worldRef, shipyardRef, 256.0D)) {
            return direct;
        }
        // Fallback: scan ships intersecting a box around the snapshot point.
        AABB box = new AABB(worldRef.x - 64.0D, worldRef.y - 64.0D, worldRef.z - 64.0D,
            worldRef.x + 64.0D, worldRef.y + 64.0D, worldRef.z + 64.0D);
        Iterable<?> ships = getShipsIntersectingBox(level, box);
        if (ships == null) {
            LOG.debug("[CTB-HITCAM] ship reanchor: box scan unavailable (direct={} worldRef={})",
                direct != null, worldRef);
            return null;
        }
        Object best = null;
        double bestError = Double.MAX_VALUE;
        for (Object ship : ships) {
            Object matrix = shipToWorld(ship);
            if (matrix == null) continue;
            Vec3 candidate = transformPosition(matrix, shipyardRef);
            if (candidate == null) continue;
            double error = candidate.distanceToSqr(worldRef);
            if (error < bestError) {
                bestError = error;
                best = ship;
            }
        }
        // Loose cap: only trust a match when the mapping lands near the
        // snapshot world position (allows for the ship moving between the
        // impact and this frame, but rejects a wrong/unrelated ship).
        if (best != null && bestError < 48.0D * 48.0D) return best;
        LOG.debug("[CTB-HITCAM] ship reanchor: no match (direct={} bestError={} worldRef={})",
            direct != null, best == null ? "none" : String.format("%.1f", Math.sqrt(bestError)), worldRef);
        return null;
    }

    /** Sanity check: the ship's matrix must map the shipyard ref near the snapshot world ref. */
    private static boolean shipMatches(Object ship, Vec3 worldRef, Vec3 shipyardRef, double tolerance) {
        Object matrix = shipToWorld(ship);
        if (matrix == null) return false;
        Vec3 candidate = transformPosition(matrix, shipyardRef);
        return candidate != null && candidate.distanceToSqr(worldRef) <= tolerance * tolerance;
    }

    /** Map a shipyard-space point to the world using the live ship transform. */
    public static Vec3 shipyardToWorld(Object ship, Vec3 shipyardPos) {
        if (ship == null || shipyardPos == null) return null;
        Object matrix = shipToWorld(ship);
        if (matrix == null) return null;
        return transformPosition(matrix, shipyardPos);
    }

    /**
     * The VS ship managing the given WORLD block position, or null. For world
     * blocks only — shipyard entities (Create contraptions assembled on ships)
     * live in ship-space coordinates and must be resolved via
     * {@link #shipMountedAt} instead.
     */
    public static Object shipManaging(ClientLevel level, BlockPos pos) {
        if (!isLoaded() || level == null || pos == null) return null;
        return getLoadedShipManagingPos(level, pos);
    }

    // Shipyard-entity ship association (mirrors VSCompat.shipMountedAt): shipyard
    // entities live in ship-space coordinates anchored at the assembly location,
    // so only the true owner ship maps the entity position onto its own hull.

    private static final double HULL_MARGIN = 3.0;

    /**
     * The ship a shipyard entity at the given SHIP-SPACE position is mounted on,
     * or null when it lives directly in the world.
     */
    @javax.annotation.Nullable
    public static Object shipMountedAt(ClientLevel level, Vec3 shipLocalPos) {
        if (!isLoaded() || level == null || shipLocalPos == null) return null;
        Iterable<?> ships = getShipsIntersectingBox(level,
            new AABB(-1.0E7, -1.0E7, -1.0E7, 1.0E7, 1.0E7, 1.0E7));
        if (ships == null) return null;
        Object best = null;
        double bestDist = Double.MAX_VALUE;
        for (Object ship : ships) {
            Vec3 world = transformPositionOrNull(shipToWorld(ship), shipLocalPos);
            if (world == null) continue;
            AABB hull = worldHullOf(ship);
            if (hull == null) continue;
            if (!hull.inflate(HULL_MARGIN).contains(world)) continue;
            double dist = distanceToHull(hull, world);
            if (dist < bestDist) {
                bestDist = dist;
                best = ship;
            }
        }
        return best;
    }

    /** The ship's voxel hull in WORLD space (ship-space AABB corners through shipToWorld), or null. */
    @javax.annotation.Nullable
    private static AABB worldHullOf(Object ship) {
        try {
            Object box = ship.getClass().getMethod("getShipAABB").invoke(ship);
            if (box == null) return null;
            Class<?> boxClass = box.getClass();
            double minX = ((Number) boxClass.getMethod("minX").invoke(box)).doubleValue();
            double minY = ((Number) boxClass.getMethod("minY").invoke(box)).doubleValue();
            double minZ = ((Number) boxClass.getMethod("minZ").invoke(box)).doubleValue();
            double maxX = ((Number) boxClass.getMethod("maxX").invoke(box)).doubleValue();
            double maxY = ((Number) boxClass.getMethod("maxY").invoke(box)).doubleValue();
            double maxZ = ((Number) boxClass.getMethod("maxZ").invoke(box)).doubleValue();
            double wx0 = Double.POSITIVE_INFINITY, wy0 = Double.POSITIVE_INFINITY, wz0 = Double.POSITIVE_INFINITY;
            double wx1 = Double.NEGATIVE_INFINITY, wy1 = Double.NEGATIVE_INFINITY, wz1 = Double.NEGATIVE_INFINITY;
            for (double x : new double[]{minX, maxX}) {
                for (double y : new double[]{minY, maxY}) {
                    for (double z : new double[]{minZ, maxZ}) {
                        Vec3 corner = transformPositionOrNull(shipToWorld(ship), new Vec3(x, y, z));
                        if (corner == null) return null;
                        wx0 = Math.min(wx0, corner.x); wy0 = Math.min(wy0, corner.y); wz0 = Math.min(wz0, corner.z);
                        wx1 = Math.max(wx1, corner.x); wy1 = Math.max(wy1, corner.y); wz1 = Math.max(wz1, corner.z);
                    }
                }
            }
            return new AABB(wx0, wy0, wz0, wx1, wy1, wz1);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Distance from a point to an AABB (0 when inside). */
    private static double distanceToHull(AABB box, Vec3 point) {
        double dx = Math.max(box.minX - point.x, Math.max(0.0, point.x - box.maxX));
        double dy = Math.max(box.minY - point.y, Math.max(0.0, point.y - box.maxY));
        double dz = Math.max(box.minZ - point.z, Math.max(0.0, point.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * World point to ship-local via the INTERPOLATED render transform (falls back
     * to the physics transform), the inverse of {@link #shipRenderLocalToWorld}.
     * Null when VS is absent or reflection fails.
     */
    @javax.annotation.Nullable
    public static Vec3 shipRenderWorldToLocal(Object ship, Vec3 world) {
        if (ship == null || world == null) return null;
        try {
            Object renderTransform = ship.getClass().getMethod("getRenderTransform").invoke(ship);
            if (renderTransform != null) {
                Method matrixGetter = renderTransform.getClass().getMethod("getWorldToShip");
                Object matrix = matrixGetter.invoke(renderTransform);
                if (matrix != null) {
                    return transformPositionOrNull(matrix, world);
                }
            }
        } catch (Throwable ignored) {
        }
        Object matrix = worldToShip(ship);
        return matrix == null ? null : transformPositionOrNull(matrix, world);
    }

    /**
     * Ship-local point to world via the INTERPOLATED render transform (falls back to
     * the physics transform), so per-frame decal rendering does not lag a tick while
     * the ship moves. Null when VS is absent or reflection fails.
     */
    @javax.annotation.Nullable
    public static Vec3 shipRenderLocalToWorld(Object ship, Vec3 local) {
        if (ship == null || local == null) return null;
        try {
            Object renderTransform = ship.getClass().getMethod("getRenderTransform").invoke(ship);
            if (renderTransform != null) {
                Method matrixGetter = renderTransform.getClass().getMethod("getShipToWorld");
                Object matrix = matrixGetter.invoke(renderTransform);
                if (matrix != null) {
                    return transformPosition(matrix, local);
                }
            }
        } catch (Throwable ignored) {
        }
        return shipyardToWorld(ship, local);
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

    /** Like {@link #transformPosition} but returns null when the matrix is unavailable or the call fails. */
    @javax.annotation.Nullable
    private static Vec3 transformPositionOrNull(Object matrix, Vec3 position) {
        if (matrix == null || position == null) return null;
        try {
            Vector3d dest = new Vector3d();
            Method method = matrix.getClass().getMethod("transformPosition", double.class, double.class, double.class, Vector3d.class);
            Object result = method.invoke(matrix, position.x, position.y, position.z, dest);
            Vector3d transformed = result instanceof Vector3d vec ? vec : dest;
            if (!Double.isFinite(transformed.x) || !Double.isFinite(transformed.y) || !Double.isFinite(transformed.z)) return null;
            return new Vec3(transformed.x, transformed.y, transformed.z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLoaded() {
        if (loaded == null) {
            try {
                loaded = ModList.get().isLoaded("valkyrienskies");
            } catch (Throwable ignored) {
                loaded = false;
            }
        }
        return loaded;
    }

    private static Object getLoadedShipManagingPos(ClientLevel level, BlockPos pos) {
        try {
            Method method = getLoadedShipManagingPosMethod();
            if (method == null) return null;
            return method.invoke(null, level, pos);
        } catch (Throwable t) {
            getShipLookupFailed = true;
            return null;
        }
    }

    private static Method getLoadedShipManagingPosMethod() {
        if (getLoadedShipManagingPosMethod != null || getShipLookupFailed) return getLoadedShipManagingPosMethod;
        try {
            Class<?> utils = Class.forName(VS_GAME_UTILS);
            getLoadedShipManagingPosMethod = firstExistingMethod(utils, ClientLevel.class, Vec3i.class);
            if (getLoadedShipManagingPosMethod != null) return getLoadedShipManagingPosMethod;
            getLoadedShipManagingPosMethod = firstExistingMethod(utils, ClientLevel.class, BlockPos.class);
            if (getLoadedShipManagingPosMethod != null) return getLoadedShipManagingPosMethod;
            getLoadedShipManagingPosMethod = firstExistingMethod(utils, Level.class, Vec3i.class);
            if (getLoadedShipManagingPosMethod != null) return getLoadedShipManagingPosMethod;
            getLoadedShipManagingPosMethod = firstExistingMethod(utils, Level.class, BlockPos.class);
            return getLoadedShipManagingPosMethod;
        } catch (Throwable t) {
            getShipLookupFailed = true;
            return null;
        }
    }

    private static Method transformRenderWithShipMethod() {
        if (transformRenderWithShipMethod != null || transformLookupFailed) return transformRenderWithShipMethod;
        try {
            Class<?> utils = Class.forName(VS_CLIENT_GAME_UTILS);
            Class<?> shipTransform = Class.forName("org.valkyrienskies.core.api.ships.properties.ShipTransform");
            transformRenderWithShipMethod = utils.getMethod("transformRenderWithShip",
                shipTransform, PoseStack.class, BlockPos.class, double.class, double.class, double.class);
            return transformRenderWithShipMethod;
        } catch (Throwable ignored) {}
        transformLookupFailed = true;
        return null;
    }

    private static Method firstExistingMethod(Class<?> owner, Class<?> levelParam, Class<?> posParam) {
        for (String name : new String[] {"getLoadedShipManagingPos", "getShipObjectManagingPos", "getShipManagingPos"}) {
            try {
                return owner.getMethod(name, levelParam, posParam);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private VSClientCompat() {}
}
