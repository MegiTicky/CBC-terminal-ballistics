package com.cbc_terminal_ballistics.util;

import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection bridge to Create Interactive ({@code org.valkyrienskies.create_interactive}),
 * the VS2 x Create compat layer present in modern modpacks. Create Interactive resolves
 * the frames CBCTB used to hand-compose: {@code CreateInteractiveUtil.getContraptionPosRot(entity)}
 * returns the contraption's full WORLD transform — the mounted ship's transform composed
 * with Create's anchor/rotation, including the shadow-ship cases CI itself introduces —
 * and {@code getContraptionPosRotForRender(entity, partialTick)} is the interpolated
 * client variant. When CI is present, CBCTB's contraption math delegates to these values
 * so it cannot drift from the runtime's own coordinate handling.
 *
 * <p>{@code ContraptionPosRot} is a Kotlin data class {@code (pos: Vector3dc,
 * rot: Quaterniondc, scale: Double)}.</p>
 */
public final class CreateInteractiveBridge {
    private static final Logger LOG = LoggerFactory.getLogger("CBCTB/CreateInteractiveBridge");
    private static final String UTIL_CLASS = "org.valkyrienskies.create_interactive.CreateInteractiveUtil";
    private static final String CONTRAPTION_ENTITY = "com.simibubi.create.content.contraptions.AbstractContraptionEntity";

    private static Boolean available;
    private static Object utilInstance;
    private static Method posRotTickMethod;
    private static Method posRotRenderMethod;
    private static boolean tickLookupFailed;
    private static boolean renderLookupFailed;

    /** True when Create Interactive's util is loadable and its singleton was found. */
    public static boolean available() {
        if (available == null) {
            try {
                Class<?> utils = Class.forName(UTIL_CLASS);
                Field instance = utils.getDeclaredField("INSTANCE");
                instance.setAccessible(true);
                utilInstance = instance.get(null);
                available = utilInstance != null;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /**
     * The contraption's world transform at the current tick:
     * {@code {px, py, pz, qx, qy, qz, qw, scale}}, or null when CI is absent
     * or the call fails (caller falls back to the VS2-composed path).
     */
    @Nullable
    public static double[] posRotTick(Entity entity) {
        if (!available()) return null;
        try {
            if (posRotTickMethod == null && !tickLookupFailed) {
                posRotTickMethod = findPosRotMethod("getContraptionPosRot");
                if (posRotTickMethod == null) tickLookupFailed = true;
            }
            if (posRotTickMethod == null) return null;
            return extract(posRotTickMethod.invoke(utilInstance, entity));
        } catch (Throwable t) {
            LOG.debug("[ctb] CI posRotTick failed: {}", t.getMessage());
            return null;
        }
    }

    /**
     * The contraption's interpolated world transform for rendering at
     * {@code partialTick}, same array layout, or null.
     */
    @Nullable
    public static double[] posRotRender(Entity entity, float partialTick) {
        if (!available()) return null;
        try {
            if (posRotRenderMethod == null && !renderLookupFailed) {
                posRotRenderMethod = findRenderPosRotMethod();
                if (posRotRenderMethod == null) renderLookupFailed = true;
            }
            if (posRotRenderMethod == null) return null;
            return extract(posRotRenderMethod.invoke(utilInstance, entity, (double) partialTick));
        } catch (Throwable t) {
            // Try the float overload once before giving up (beta signature drift).
            if (posRotRenderMethod != null && posRotRenderMethod.getParameterTypes()[1] == float.class) {
                try {
                    return extract(posRotRenderMethod.invoke(utilInstance, entity, partialTick));
                } catch (Throwable ignored) {
                }
            }
            LOG.debug("[ctb] CI posRotRender failed: {}", t.getMessage());
            return null;
        }
    }

    @Nullable
    private static Method findPosRotMethod(String name) {
        try {
            Class<?> utils = Class.forName(UTIL_CLASS);
            Class<?> contraption = Class.forName(CONTRAPTION_ENTITY);
            return utils.getMethod(name, contraption);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Method findRenderPosRotMethod() {
        try {
            Class<?> utils = Class.forName(UTIL_CLASS);
            Class<?> contraption = Class.forName(CONTRAPTION_ENTITY);
            try {
                return utils.getMethod("getContraptionPosRotForRender", contraption, double.class);
            } catch (NoSuchMethodException ignored) {
            }
            try {
                return utils.getMethod("getContraptionPosRotForRender", contraption, float.class);
            } catch (NoSuchMethodException ignored) {
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static double[] extract(Object posRot) {
        if (posRot == null) return null;
        try {
            Vector3d pos = getVec(posRot, "getPos", "pos");
            Quaterniond rot = getQuat(posRot, "getRot", "rot");
            if (pos == null || rot == null) return null;
            double scale = getDouble(posRot, "getScale", "scale", 1.0);
            if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)
                || !Double.isFinite(rot.x) || !Double.isFinite(rot.y) || !Double.isFinite(rot.z)
                || !Double.isFinite(rot.w) || !Double.isFinite(scale) || scale == 0.0) {
                return null;
            }
            return new double[]{pos.x, pos.y, pos.z, rot.x, rot.y, rot.z, rot.w, scale};
        } catch (Throwable t) {
            LOG.debug("[ctb] CI posRot extract failed: {}", t.getMessage());
            return null;
        }
    }

    @Nullable
    private static Vector3d getVec(Object owner, String... names) throws Exception {
        for (String name : names) {
            try {
                Method m = owner.getClass().getMethod(name);
                Object v = m.invoke(owner);
                if (v instanceof Vector3d vec) return vec;
                if (v instanceof org.joml.Vector3dc vec) return new Vector3d(vec.x(), vec.y(), vec.z());
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    @Nullable
    private static Quaterniond getQuat(Object owner, String... names) throws Exception {
        for (String name : names) {
            try {
                Method m = owner.getClass().getMethod(name);
                Object v = m.invoke(owner);
                if (v instanceof Quaterniond quat) return quat;
                if (v instanceof org.joml.Quaterniondc quat) return new Quaterniond(quat.x(), quat.y(), quat.z(), quat.w());
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static double getDouble(Object owner, String getter, String accessor, double fallback) {
        try {
            Method m = owner.getClass().getMethod(getter);
            Object v = m.invoke(owner);
            if (v instanceof Number number) return number.doubleValue();
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }
        try {
            Method m = owner.getClass().getMethod(accessor);
            Object v = m.invoke(owner);
            if (v instanceof Number number) return number.doubleValue();
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private CreateInteractiveBridge() {}
}
