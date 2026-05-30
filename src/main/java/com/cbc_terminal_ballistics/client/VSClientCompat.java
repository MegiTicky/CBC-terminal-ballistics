package com.cbc_terminal_ballistics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** Client-only optional Valkyrien Skies rendering hooks. */
final class VSClientCompat {
    private static final String VS_GAME_UTILS = "org.valkyrienskies.mod.common.VSGameUtilsKt";
    private static final String VS_CLIENT_GAME_UTILS = "org.valkyrienskies.mod.common.VSClientGameUtils";

    private static Boolean loaded;
    private static Method getLoadedShipManagingPosMethod;
    private static Method transformRenderWithShipMethod;
    private static boolean getShipLookupFailed;
    private static boolean transformLookupFailed;

    static boolean transformRenderWithShip(ClientLevel level, BlockPos pos, PoseStack poseStack, Vec3 camera) {
        if (!isLoaded() || level == null) return false;
        Object ship = getLoadedShipManagingPos(level, pos);
        if (ship == null) return false;
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
                if (!params[0].isAssignableFrom(ClientLevel.class)) continue;
                if (!params[1].isAssignableFrom(BlockPos.class) && !params[1].isAssignableFrom(Vec3i.class)) continue;
                if (params[0] == ClientLevel.class) {
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

    private static Method transformRenderWithShipMethod() {
        if (transformRenderWithShipMethod != null || transformLookupFailed) return transformRenderWithShipMethod;
        try {
            Class<?> utils = Class.forName(VS_CLIENT_GAME_UTILS);
            for (Method method : utils.getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!method.getName().equals("transformRenderWithShip") || params.length != 6) continue;
                if (params[1] != PoseStack.class) continue;
                if (params[2] != BlockPos.class) continue;
                if (params[3] != double.class || params[4] != double.class || params[5] != double.class) continue;
                transformRenderWithShipMethod = method;
                return transformRenderWithShipMethod;
            }
        } catch (Throwable ignored) {
            // Fall through to vanilla rendering.
        }
        transformLookupFailed = true;
        return null;
    }

    private static boolean isShipManagingPosMethod(String name) {
        // VS2.3 uses getShipObjectManagingPos/getShipManagingPos. Newer VS
        // builds may expose getLoadedShipManagingPos. Without this legacy-name
        // fallback, ship lookup returns null and impact marks render at raw
        // shipyard coordinates instead of being transformed with the ship.
        return name.equals("getLoadedShipManagingPos")
            || name.equals("getShipObjectManagingPos")
            || name.equals("getShipManagingPos");
    }

    private VSClientCompat() {}
}
