package com.cbc_terminal_ballistics.util;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

public final class CBCReflect {
    private static Class<?> impactResultClass;
    private static Class<?> outcomeClass;
    private static Constructor<?> impactCtor;

    public static Object newImpactResult(String outcome, boolean shouldRemove) {
        try {
            if (impactCtor == null) {
                impactResultClass = Class.forName("rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile$ImpactResult");
                outcomeClass = Class.forName("rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile$ImpactResult$KinematicOutcome");
                impactCtor = impactResultClass.getDeclaredConstructor(outcomeClass, boolean.class);
                impactCtor.setAccessible(true);
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enumValue = Enum.valueOf((Class<Enum>) outcomeClass.asSubclass(Enum.class), outcome.toUpperCase(Locale.ROOT));
            return impactCtor.newInstance(enumValue, shouldRemove);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not construct CBC ImpactResult", ex);
        }
    }


    public static Object callCalculateBlockPenetration(Object projectile, Object projectileContext, BlockState state, net.minecraft.world.phys.BlockHitResult hit) {
        try {
            Method m = findMethod(projectile.getClass(), "calculateBlockPenetration", projectileContext.getClass(), BlockState.class, net.minecraft.world.phys.BlockHitResult.class);
            if (m == null) {
                Class<?> ctxClass = Class.forName("rbasamoyai.createbigcannons.munitions.ProjectileContext");
                m = findMethod(projectile.getClass(), "calculateBlockPenetration", ctxClass, BlockState.class, net.minecraft.world.phys.BlockHitResult.class);
            }
            if (m == null) throw new NoSuchMethodException("calculateBlockPenetration on " + projectile.getClass().getName());
            m.setAccessible(true);
            return m.invoke(projectile, projectileContext, state, hit);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not call original CBC calculateBlockPenetration", ex);
        }
    }

    public static boolean callOnImpact(Entity projectile, HitResult hit, Object impactResult, Object context) {
        try {
            Method m = findMethod(projectile.getClass(), "onImpact", HitResult.class, impactResult.getClass(), context.getClass());
            if (m == null) return false;
            m.setAccessible(true);
            Object r = m.invoke(projectile, hit, impactResult, context);
            return r instanceof Boolean b && b;
        } catch (Exception ex) {
            CBCTerminalBallistics.LOGGER.debug("Failed to call CBC onImpact", ex);
            return false;
        }
    }


    public static Vec3 surfaceNormal(Level level, BlockHitResult hit) {
        try {
            Class<?> utils = Class.forName("rbasamoyai.createbigcannons.utils.CBCUtils");
            Method method = utils.getMethod("getSurfaceNormalVector", Level.class, BlockHitResult.class);
            Object result = method.invoke(null, level, hit);
            if (result instanceof Vec3 vec) return vec;
        } catch (Throwable ignored) {
        }
        return Vec3.atLowerCornerOf(hit.getDirection().getNormal());
    }

    /**
     * Plays the block's break sound at the given world position. This is the
     * exact same call CBC's AbstractBigCannonProjectile makes in its stopped
     * branch (line 230-235 of 5.10.2 / 6.x):
     * <pre>
     *   level.playSound(null, x, y, z, state.getSoundType().getBreakSound(),
     *                   SoundSource.BLOCKS, volume, pitch);
     * </pre>
     * With penetration CTB replaces calculateBlockPenetration, so this sound
     * is never played by CBC anymore. We replay it here for the perforation
     * case (the block stays intact and the projectile continues, just like the
     * stopped case in stock CBC).
     */
    public static void playBlockImpactBreakSound(Level level, BlockState state, Vec3 hitLoc) {
        if (level == null || state == null || hitLoc == null) return;
        if (level.isClientSide) return;
        try {
            Object soundType = tryInvoke(state, "getSoundType", new Class<?>[]{});
            if (soundType == null) return;
            Object breakSound = tryInvoke(soundType, "getBreakSound", new Class<?>[]{});
            if (breakSound == null) return;
            float volume = callFloat(soundType, 1.0F, "getVolume");
            float pitch = callFloat(soundType, 1.0F, "getPitch");
            // Level.playSound(Player, double, double, double, SoundEvent, SoundSource, float, float)
            Class<?> soundSourceCls = Class.forName("net.minecraft.sounds.SoundSource");
            Object blocksSource = soundSourceCls.getField("BLOCKS").get(null);
            Method playSound = Level.class.getMethod("playSound",
                net.minecraft.world.entity.player.Player.class,
                double.class, double.class, double.class,
                Class.forName("net.minecraft.sounds.SoundEvent"),
                soundSourceCls,
                float.class, float.class);
            playSound.invoke(level, null, hitLoc.x, hitLoc.y, hitLoc.z, breakSound, blocksSource, volume, pitch);
        } catch (Throwable t) {
            CBCTerminalBallistics.LOGGER.debug("Failed to play CBC block impact break sound", t);
        }
    }

    public static float callFloat(Object target, float fallback, String... methodNames) {
        for (String name : methodNames) {
            Object v = tryInvoke(target, name, new Class<?>[]{});
            if (v instanceof Number n) return n.floatValue();
        }
        return fallback;
    }


    public static void addBlockHitEffect(Object projectileContext, Entity projectile, Level level, BlockState state, BlockPos pos, Vec3 hitLoc, Vec3 effectNormal, boolean bounced) {
        if (projectileContext == null || projectile == null || level == null || state == null || pos == null || hitLoc == null || effectNormal == null) return;
        try {
            Class<?> packetClass = Class.forName("rbasamoyai.createbigcannons.network.ClientboundPlayBlockHitEffectPacket");
            Constructor<?> ctor = packetClass.getConstructor(BlockState.class, EntityType.class, boolean.class, boolean.class,
                double.class, double.class, double.class, float.class, float.class, float.class);
            Method addPlayedEffect = findMethod(projectileContext.getClass(), "addPlayedEffect", packetClass);
            if (addPlayedEffect == null) return;
            addPlayedEffect.setAccessible(true);

            for (BlockState contained : containedBlockStates(level, state, pos)) {
                Object packet = ctor.newInstance(contained, projectile.getType(), bounced, false,
                    hitLoc.x, hitLoc.y, hitLoc.z, (float) effectNormal.x, (float) effectNormal.y, (float) effectNormal.z);
                addPlayedEffect.invoke(projectileContext, packet);
            }
        } catch (Throwable ex) {
            CBCTerminalBallistics.LOGGER.debug("Failed to add CBC block hit effect", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Iterable<BlockState> containedBlockStates(Level level, BlockState state, BlockPos pos) {
        Object provider = blockArmorProperties(state);
        if (provider != null) {
            try {
                Method method = provider.getClass().getMethod("containedBlockStates", Level.class, BlockState.class, BlockPos.class, boolean.class);
                method.setAccessible(true);
                Object result = method.invoke(provider, level, state, pos.immutable(), true);
                if (result instanceof Iterable<?> iterable) return (Iterable<BlockState>) iterable;
            } catch (Throwable ignored) {
            }
        }
        return java.util.List.of(state);
    }

    public static double projectileMass(Entity projectile) {
        return callDouble(projectile, 1.0, "getProjectileMass");
    }

    public static void setProjectileMass(Entity projectile, double mass) {
        tryInvoke(projectile, "setProjectileMass", new Class<?>[]{double.class}, mass);
        tryInvoke(projectile, "setProjectileMass", new Class<?>[]{float.class}, (float) mass);
    }

    public static Vec3 forces(Entity projectile, Vec3 pos, Vec3 vel) {
        Object v = tryInvoke(projectile, "getForces", new Class<?>[]{Vec3.class, Vec3.class}, pos, vel);
        return v instanceof Vec3 vec ? vec : Vec3.ZERO;
    }

    public static boolean canHitSurface(Entity projectile) {
        Object v = tryInvoke(projectile, "canHitSurface", new Class<?>[]{ });
        return !(v instanceof Boolean b) || b;
    }

    public static boolean lastPenetratedBlockIsAir(Entity projectile) {
        try {
            Field f = findField(projectile.getClass(), "lastPenetratedBlock");
            if (f == null) return true;
            f.setAccessible(true);
            Object o = f.get(projectile);
            return o instanceof BlockState s && s.isAir();
        } catch (Exception ex) {
            return true;
        }
    }

    public static double ballistic(Entity projectile, String method, double fallback) {
        Object props = tryInvoke(projectile, "getBallisticProperties", new Class<?>[]{});
        return props == null ? fallback : callDouble(props, fallback, method);
    }



    public static boolean projectilesCanBounce() {
        Object config = cbcMunitionsConfig();
        Object value = field(config, "projectilesCanBounce");
        Object result = value == null ? null : tryInvoke(value, "get", new Class<?>[]{});
        return !(result instanceof Boolean b) || b;
    }

    public static double baseProjectileBounceChance() {
        Object config = cbcMunitionsConfig();
        Object value = field(config, "baseProjectileBounceChance");
        Object result = value == null ? null : tryInvoke(value, "getF", new Class<?>[]{});
        return result instanceof Number n ? n.doubleValue() : 0.33D;
    }

    private static Object cbcMunitionsConfig() {
        try {
            Class<?> configs = Class.forName("rbasamoyai.createbigcannons.config.CBCConfigs");
            Method server = configs.getMethod("server");
            Object serverConfig = server.invoke(null);
            return field(serverConfig, "munitions");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            Field f = findField(target.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static double armorToughness(Level level, BlockState state, BlockPos pos, double fallback) {
        return armorValue(level, state, pos, fallback, "toughness");
    }

    public static double armorHardness(Level level, BlockState state, BlockPos pos, double fallback) {
        return armorValue(level, state, pos, fallback, "hardness");
    }

    private static double armorValue(Level level, BlockState state, BlockPos pos, double fallback, String method) {
        Object provider = blockArmorProperties(state);
        if (provider != null) {
            try {
                Method m = provider.getClass().getMethod(method, Level.class, BlockState.class, BlockPos.class, boolean.class);
                m.setAccessible(true);
                Object v = m.invoke(provider, level, state, pos, true);
                return v instanceof Number n ? n.doubleValue() : fallback;
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    private static Object blockArmorProperties(BlockState state) {
        for (String className : new String[] {
            "rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesHandler",
            "rbasamoyai.createbigcannons.munitions.config.BlockArmorPropertiesHandler"
        }) {
            try {
                Class<?> handler = Class.forName(className);
                Method get = handler.getMethod("getProperties", BlockState.class);
                get.setAccessible(true);
                return get.invoke(null, state);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static boolean griefNoDamage(Object projectileContext) {
        try {
            Object grief = tryInvoke(projectileContext, "griefState", new Class<?>[]{});
            return grief != null && grief.toString().equals("NO_DAMAGE");
        } catch (Exception ex) {
            return false;
        }
    }

    public static double callDouble(Object target, double fallback, String... methodNames) {
        for (String name : methodNames) {
            Object v = tryInvoke(target, name, new Class<?>[]{});
            if (v instanceof Number n) return n.doubleValue();
        }
        return fallback;
    }

    public static Object tryInvoke(Object target, String name, Class<?>[] params, Object... args) {
        try {
            Method m = findMethod(target.getClass(), name, params);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            try { return cur.getDeclaredMethod(name, params); } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private CBCReflect() {}
}
