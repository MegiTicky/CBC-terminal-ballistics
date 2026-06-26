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
final class VSClientCompat {
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

    private static Iterable<?> getShipsIntersecting(ClientLevel level, BlockPos pos) {
        try {
            Method method = getShipsIntersectingMethod();
            if (method == null) return null;

            AABB aabb = new AABB(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                                 pos.getX() + 2, pos.getY() + 2, pos.getZ() + 2);
            Object result = method.invoke(null, level, aabb);
            if (result instanceof Iterable<?> iter) return iter;
        } catch (Throwable t) {
            LOG.debug("[CBCTB] getShipsIntersecting: exception: {}", t.getMessage());
            shipsIntersectingLookupFailed = true;
        }
        return null;
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
