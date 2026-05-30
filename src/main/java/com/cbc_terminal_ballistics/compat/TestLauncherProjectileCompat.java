package com.cbc_terminal_ballistics.compat;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class TestLauncherProjectileCompat {
    public static final String NBT_TEST_LAUNCHER = "CBCTBTestLauncher";
    public static final int MIN_LAUNCHER_LIFETIME_TICKS = 160;

    public static void initializeLauncherProjectile(Entity entity) {
        if (entity == null) return;
        entity.getPersistentData().putBoolean(NBT_TEST_LAUNCHER, true);
        ensureLauncherLifetime(entity);
    }

    public static boolean isLauncherProjectile(Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean(NBT_TEST_LAUNCHER);
    }

    private static void ensureLauncherLifetime(Entity entity) {
        Class<?> type = entity.getClass();
        if (!isCbcmsNormalOrExtendedSmallCannonShell(type) && !isCbcAutocannonProjectile(type)) {
            return;
        }
        ensureLifetimeAtLeast(entity, MIN_LAUNCHER_LIFETIME_TICKS);
    }

    private static boolean isCbcmsNormalOrExtendedSmallCannonShell(Class<?> type) {
        String name = type.getName();
        return name.startsWith("com.cainiao1053.cbcmoreshells.munitions.dual_cannon.")
                && (name.contains(".normal_") || name.contains(".extended_"));
    }

    private static boolean isCbcAutocannonProjectile(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if (name.equals("rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile")
                    || name.startsWith("rbasamoyai.createbigcannons.munitions.autocannon.")) {
                return true;
            }
        }
        return false;
    }

    private static void ensureLifetimeAtLeast(Entity entity, int minLifetime) {
        Integer current = readLifetime(entity);
        if (current != null && current >= minLifetime) return;

        if (invokeSetLifetime(entity, minLifetime)) return;

        // Fallback for CBCMS dual-cannon shells (maxAge) and CBC autocannon rounds (ageRemaining).
        setIntFieldAtLeast(entity, "maxAge", minLifetime);
        setIntFieldAtLeast(entity, "ageRemaining", minLifetime);
    }

    private static Integer readLifetime(Entity entity) {
        Object value = invokeNoArg(entity, "getLifetime");
        if (value instanceof Number number) return number.intValue();

        Integer maxAge = readIntField(entity, "maxAge");
        Integer ageRemaining = readIntField(entity, "ageRemaining");
        if (maxAge == null) return ageRemaining;
        if (ageRemaining == null) return maxAge;
        return Math.max(maxAge, ageRemaining);
    }

    private static boolean invokeSetLifetime(Entity entity, int lifetime) {
        try {
            Method method;
            try {
                // Prefer the public API used by cannons when the projectile exposes it.
                method = entity.getClass().getMethod("setLifetime", int.class);
            } catch (NoSuchMethodException ignored) {
                method = findMethod(entity.getClass(), "setLifetime", int.class);
            }
            if (method == null) return false;
            method.setAccessible(true);
            method.invoke(entity, lifetime);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method method = findMethod(target.getClass(), name);
            if (method == null) return null;
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer readIntField(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) return null;
            field.setAccessible(true);
            return field.getInt(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setIntFieldAtLeast(Object target, String name, int minValue) {
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) return;
            field.setAccessible(true);
            if (field.getInt(target) < minValue) {
                field.setInt(target, minValue);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private TestLauncherProjectileCompat() {}
}
