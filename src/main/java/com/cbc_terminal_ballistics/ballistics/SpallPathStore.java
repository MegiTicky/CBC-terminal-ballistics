package com.cbc_terminal_ballistics.ballistics;

import com.cbc_terminal_ballistics.config.TBConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Server-side ring buffer of recent spall cones' display polylines (world
 * space, exactly the geometry the bounce simulator drew for the x-ray), so a
 * ballistic-goggles right-click can replay the last projectile's spall path
 * in-world. Records are a static snapshot: a VS ship that moves afterwards
 * leaves its recorded path floating where the ship used to be.
 */
public final class SpallPathStore {
    /** Records older than this are never replayed (stale worlds, moved ships). */
    private static final long EXPIRY_TICKS = 12000L;
    private static final int CAPACITY = 8;

    public record Record(ResourceKey<Level> dimension, Vec3 origin,
                         int[] rayPointCounts, float[] points, long gameTick) {}

    private static final ArrayDeque<Record> RECORDS = new ArrayDeque<>();

    public static void record(ServerLevel level, Vec3 origin, float[] points, int[] rayPointCounts) {
        if (TBConfig.SPALL_PATH_RENDER_TICKS.get() <= 0) return;
        if (points == null || rayPointCounts == null || points.length < 6 || rayPointCounts.length == 0) return;
        synchronized (RECORDS) {
            RECORDS.addLast(new Record(level.dimension(), origin,
                rayPointCounts.clone(), points.clone(), level.getGameTime()));
            while (RECORDS.size() > CAPACITY) RECORDS.removeFirst();
        }
    }

    /**
     * The nearest record within {@code maxDist} of {@code pos} (0 disables the
     * radius check), else the newest record in the same dimension. Expired
     * records and other dimensions never match.
     */
    @Nullable
    public static Record nearest(ServerLevel level, Vec3 pos, double maxDist) {
        synchronized (RECORDS) {
            long now = level.getGameTime();
            Record best = null;
            double bestDistSqr = maxDist > 0.0D ? maxDist * maxDist : Double.MAX_VALUE;
            for (Record record : RECORDS) {
                if (record.dimension() != level.dimension()) continue;
                if (now - record.gameTick() > EXPIRY_TICKS) continue;
                double distSqr = record.origin().distanceToSqr(pos);
                if (distSqr <= bestDistSqr) {
                    bestDistSqr = distSqr;
                    best = record;
                }
            }
            if (best != null) return best;
            Iterator<Record> newestFirst = RECORDS.descendingIterator();
            while (newestFirst.hasNext()) {
                Record record = newestFirst.next();
                if (record.dimension() == level.dimension() && now - record.gameTick() <= EXPIRY_TICKS) return record;
            }
            return null;
        }
    }

    public static void clear() {
        synchronized (RECORDS) {
            RECORDS.clear();
        }
    }

    private SpallPathStore() {}
}
