package com.cbc_terminal_ballistics.ballistics;

import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Bouncing spall: each fragment is a chained ray. A segment stops on the first
 * blocking surface (world clip or contraption hull), bleeds energy through any
 * living bodies it crosses, then either embeds in the block or — on a grazing
 * roll — ricochets with reduced energy and continues. Real compartment
 * casualties come from exactly these wall strikes: the aggregate "cone pass"
 * of the legacy spall could not reach crew standing behind interior structure.
 *
 * One invocation simulates one cone. The same method serves the damage rays
 * (jittered directions, entity sweeps, block handler applies integrity
 * damage) and the terminal-x-ray display rays (deterministic spiral, no
 * sweeps/damage) whose polylines are serialized for the client.
 */
public final class SpallBounce {
    /** graze fraction above which a fragment wall-skims regardless of the roll. */
    private static final double SKIM_GRAZE = 0.92D;
    private static final double SKIM_RETENTION_PENALTY = 0.5D;
    private static final double BODY_ENERGY_BLEED = 0.6D;
    private static final double MAX_RICOCHET_PROBABILITY = 0.9D;
    private static final double RICOCHET_JITTER_DEGREES = 12.0D;

    /**
     * Applied when a fragment segment stops on a block. Damage-mode handlers
     * run the legacy fragment integrity-damage path here (gated by the spall
     * toughness threshold — toughness limits damage, it never absorbs);
     * display handlers only classify the surface. Returns the surface's
     * ricochet factor, derived from the struck material's
     * {@code spall_multiplier}: ~1.0 for steel-like surfaces, near 0 for soft
     * absorptive ones (factor &lt;= 0 means the fragment always embeds; the
     * extreme wall-skim still applies to any surface).
     */
    public interface BlockStopHandler {
        double onBlockStop(ServerLevel level, Entity projectile, BlockPos worldPos, BlockState state,
                           Vec3 hitPos, Vec3 normal, double energy);
    }

    /** One fragment-vs-body contact, in cone-local terms. */
    public record EntityStrike(LivingEntity entity, double energy, double distFromOrigin) {}

    /** Display geometry for one cone: per-ray point counts into a flat xyz array. */
    public static final class Polyline {
        public final int[] rayPointCounts;
        public final float[] points;

        Polyline(int[] rayPointCounts, float[] points) {
            this.rayPointCounts = rayPointCounts;
            this.points = points;
        }
    }

    public static final class Result {
        public final List<EntityStrike> strikes = new ArrayList<>();
        public int ricochets = 0;
        public Polyline polyline = new Polyline(new int[0], new float[0]);
    }

    /**
     * @param fragments  fragment count (damage cones) or display-ray count
     * @param jitter     0 for the deterministic display spiral
     * @param sweepEntities false for display cones
     */
    public static Result simulate(ServerLevel level, Entity projectile,
                                  @Nullable ContraptionCompat.ContraptionSnapshot contraption,
                                  Vec3 origin, Vec3 coneDir, double coneCos, int fragments, double range,
                                  @Nullable List<LivingEntity> candidates, BlockStopHandler handler,
                                  int maxBounces, double ricochetChance, double retentionMin, double retentionMax,
                                  double energyFloor, double entityInflate, int maxSegments,
                                  double jitter, boolean sweepEntities, RandomSource random) {
        Result result = new Result();
        int[] counts = new int[fragments];
        List<Float> points = new ArrayList<>(fragments * 6);
        int segmentsUsed = 0;
        for (int i = 0; i < fragments; i++) {
            int rayStart = points.size() / 3;
            Vec3 pos = origin;
            Vec3 dir = fragmentDirection(coneDir, i, fragments, coneCos, jitter > 0 ? random.nextDouble() : 0.0D);
            double energy = 1.0D;
            double pathUsed = 0.0D;
            points.add((float) origin.x);
            points.add((float) origin.y);
            points.add((float) origin.z);
            // One hit per entity per fragment: a body only bleeds a ray once.
            List<LivingEntity> struckThisRay = new ArrayList<>(0);
            for (int bounce = 0; bounce <= maxBounces; bounce++) {
                if (segmentsUsed >= maxSegments || energy < energyFloor || range - pathUsed < 0.5D) break;
                segmentsUsed++;
                double remaining = range - pathUsed;
                Vec3 segEnd = pos.add(dir.scale(remaining));
                BlockHitResult worldHit = level.clip(new ClipContext(pos, segEnd,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile));
                double worldDistSqr = worldHit.getType() == HitResult.Type.BLOCK
                    ? worldHit.getLocation().distanceToSqr(pos) : Double.POSITIVE_INFINITY;
                // Contraption hulls are invisible to vanilla clip; the snapshot
                // finds the nearest hull block along the segment instead.
                ContraptionCompat.ContraptionSnapshot.Hit contraHit =
                    contraption != null ? contraption.raycast(pos, segEnd) : null;
                double contraDistSqr = contraHit != null ? contraHit.distance() * contraHit.distance() : Double.POSITIVE_INFINITY;
                double stopDistSqr = Math.min(worldDistSqr, contraDistSqr);
                Vec3 segStop = stopDistSqr == Double.POSITIVE_INFINITY ? segEnd : pos.add(dir.scale(Math.sqrt(stopDistSqr)));

                if (sweepEntities && candidates != null) {
                    List<EntityStrike> segStrikes = new ArrayList<>(0);
                    for (LivingEntity entity : candidates) {
                        if (struckThisRay.contains(entity)) continue;
                        java.util.Optional<Vec3> hitPoint = entity.getBoundingBox().inflate(entityInflate).clip(pos, segStop);
                        if (hitPoint.isEmpty()) continue;
                        double d = hitPoint.get().distanceTo(origin);
                        segStrikes.add(new EntityStrike(entity, energy, d));
                    }
                    segStrikes.sort((a, b) -> Double.compare(a.distFromOrigin(), b.distFromOrigin()));
                    for (EntityStrike strike : segStrikes) {
                        result.strikes.add(strike);
                        struckThisRay.add(strike.entity());
                        energy *= BODY_ENERGY_BLEED;
                        if (energy < energyFloor) break;
                    }
                }

                if (stopDistSqr == Double.POSITIVE_INFINITY) break; // flew out through open air
                double stopDist = Math.sqrt(stopDistSqr);
                pathUsed += stopDist;

                Vec3 hitPos;
                Vec3 normal;
                BlockPos worldPos;
                BlockState state;
                double ricochetFactor;
                if (contraHit != null && contraDistSqr <= worldDistSqr) {
                    hitPos = contraHit.hitLocation();
                    normal = contraHit.direction() == null ? dir.reverse() : Vec3.atLowerCornerOf(contraHit.direction().getNormal());
                    worldPos = contraHit.worldPos();
                    state = contraHit.state();
                } else if (worldHit.getType() == HitResult.Type.BLOCK) {
                    hitPos = worldHit.getLocation();
                    normal = Vec3.atLowerCornerOf(worldHit.getDirection().getNormal());
                    worldPos = worldHit.getBlockPos();
                    state = level.getBlockState(worldPos);
                    if (state.isAir() && contraption != null) {
                        // World cell is air because the block lives inside the
                        // contraption entity (vanilla clip outran the hull test).
                        BlockState contraState = ContraptionCompat.blockStateAt(level, worldPos);
                        if (contraState == null) break;
                        state = contraState;
                    }
                } else {
                    break;
                }
                ricochetFactor = handler.onBlockStop(level, projectile, worldPos, state, hitPos, normal, energy);
                points.add((float) hitPos.x);
                points.add((float) hitPos.y);
                points.add((float) hitPos.z);

                double dot = Math.abs(dir.dot(normal));
                double graze = 1.0D - dot;
                boolean skim = graze >= SKIM_GRAZE;
                // Linear graze shaping: spall cones aim along the shell axis, so
                // most compartment strikes land at small-to-moderate graze — a
                // steeper exponent made ricochets nearly vanish there.
                double probability = skim
                    ? 1.0D
                    : Math.min(ricochetChance * graze * ricochetFactor, MAX_RICOCHET_PROBABILITY);
                if (probability <= 0.0D) break;
                if (random.nextDouble() >= probability) break;
                result.ricochets++;
                double retention = (retentionMin + (retentionMax - retentionMin) * graze)
                    * (skim ? SKIM_RETENTION_PENALTY : 1.0D);
                energy *= retention;
                dir = ricochetDirection(dir, normal, random);
                pos = hitPos.add(normal.scale(0.02D));
            }
            counts[i] = points.size() / 3 - rayStart;
        }
        float[] flat = new float[points.size()];
        for (int i = 0; i < flat.length; i++) flat[i] = points.get(i);
        result.polyline = new Polyline(counts, flat);
        return result;
    }

    /** Mirror the direction about the surface normal, then scatter inside a small cone. */
    private static Vec3 ricochetDirection(Vec3 dir, Vec3 normal, RandomSource random) {
        Vec3 reflected = dir.subtract(normal.scale(2.0D * dir.dot(normal)));
        if (reflected.lengthSqr() < 1.0e-8D) return dir.reverse();
        reflected = reflected.normalize();
        Vec3 u = new Vec3(random.nextDouble() * 2.0D - 1.0D, random.nextDouble() * 2.0D - 1.0D, random.nextDouble() * 2.0D - 1.0D);
        Vec3 perp = u.subtract(reflected.scale(u.dot(reflected)));
        if (perp.lengthSqr() < 1.0e-8D) return reflected;
        double scale = Math.tan(Math.toRadians(RICOCHET_JITTER_DEGREES)) * (0.3D + 0.7D * random.nextDouble());
        Vec3 scattered = reflected.add(perp.normalize().scale(scale)).normalize();
        // A scatter push back into the wall would immediately re-clip the same
        // block; clamp to at least a shallow departure angle instead.
        if (scattered.dot(normal) < 0.05D) {
            scattered = reflected.add(normal.scale(0.15D)).normalize();
        }
        return scattered;
    }

    /**
     * Golden-spiral cone sample with center bias — shared with the legacy
     * spall path and identical to the client's copies, so recorded polylines
     * start along the exact rays the client would have drawn anyway.
     */
    public static Vec3 fragmentDirection(Vec3 forward, int index, int count, double coneCos, double jitter) {
        Vec3 f = forward.normalize();
        Vec3 up = Math.abs(f.y) < 0.92 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 right = f.cross(up).normalize();
        Vec3 orthoUp = right.cross(f).normalize();
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        double t = (index + 0.5) / Math.max(1, count);
        // Bias toward the center of the cone so targets directly behind the plate receive several fragments.
        double cos = 1.0 - (1.0 - coneCos) * t * t;
        double sin = Math.sqrt(Math.max(0.0, 1.0 - cos * cos));
        double phi = index * golden + jitter * 0.35;
        return f.scale(cos).add(right.scale(Math.cos(phi) * sin)).add(orthoUp.scale(Math.sin(phi) * sin)).normalize();
    }

    private SpallBounce() {}
}
