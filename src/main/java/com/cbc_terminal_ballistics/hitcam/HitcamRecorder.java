package com.cbc_terminal_ballistics.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Records per-tick flight samples of CBC projectiles so a shell can be
 * replayed on the client as a slow-motion hitcam. The most recently ticked
 * projectile is the target of {@code /cbctb hitcam}; records of older
 * projectiles are dropped as soon as the entities are garbage collected.
 */
public final class HitcamRecorder {
    private static final WeakHashMap<Entity, Deque<HitcamSample>> RECORDS = new WeakHashMap<>();
    private static Entity lastProjectile;
    private static Field orientationField;

    /** @return true only for a projectile's first recorded tick. */
    public static boolean record(Entity projectile) {
        if (!TBConfig.HITCAM_ENABLED.get()) return false;
        if (projectile.isRemoved()) return false;
        Deque<HitcamSample> deque = RECORDS.computeIfAbsent(projectile, e -> new ArrayDeque<>());
        boolean first = deque.isEmpty();
        lastProjectile = projectile;
        Vec3 pos = projectile.position();
        Vec3 orient = orientationOf(projectile);
        deque.addLast(new HitcamSample(pos.x, pos.y, pos.z, (float) orient.x, (float) orient.y, (float) orient.z));
        int max = TBConfig.HITCAM_MAX_SAMPLES.get();
        while (deque.size() > max) {
            deque.pollFirst();
        }
        return first;
    }

    private static Vec3 orientationOf(Entity projectile) {
        try {
            if (orientationField == null) {
                Class<?> c = projectile.getClass();
                while (c != null && orientationField == null) {
                    try {
                        Field f = c.getDeclaredField("orientation");
                        f.setAccessible(true);
                        orientationField = f;
                    } catch (NoSuchFieldException ignored) {
                        c = c.getSuperclass();
                    }
                }
            }
            if (orientationField != null) {
                Object value = orientationField.get(projectile);
                if (value instanceof Vec3 v && v.lengthSqr() > 1.0e-6D) return v.normalize();
            }
        } catch (ReflectiveOperationException | ClassCastException ignored) {
        }
        Vec3 look = projectile.getLookAngle();
        return look.lengthSqr() < 1.0e-6D ? new Vec3(0, 1, 0) : look.normalize();
    }

    /** Most recently ticked CBC projectile, or null if none seen since launch. */
    public static Entity lastProjectile() {
        return lastProjectile;
    }

    public static List<HitcamSample> lastSamples() {
        if (lastProjectile == null) return List.of();
        return samplesOf(lastProjectile);
    }

    public static List<HitcamSample> samplesOf(Entity projectile) {
        if (projectile == null) return List.of();
        Deque<HitcamSample> deque = RECORDS.get(projectile);
        return deque == null ? List.of() : new ArrayList<>(deque);
    }

    public static void clearAll() {
        RECORDS.clear();
        lastProjectile = null;
        orientationField = null;
    }

    private HitcamRecorder() {}
}
