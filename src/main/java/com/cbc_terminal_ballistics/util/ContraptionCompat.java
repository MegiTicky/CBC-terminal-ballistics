package com.cbc_terminal_ballistics.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Optional Create contraption integration kept behind reflection so CBC Terminal Ballistics
 * can still load without Create present. When the {@code hitCreateContraptions} config is enabled,
 * projectile raycasts are extended to collide with blocks stored inside moving Create contraption
 * entities (mechanical bearings, pistons, gantries, etc.).
 *
 * Create's {@code Contraption.getBlocks()} stores blocks in local contraption coordinates.
 * To detect a hit, we transform the world-space ray into each candidate contraption's local
 * coordinate system, then run a DDA voxel traversal over the stored block map.
 */
public final class ContraptionCompat {
    private static final Logger LOG = LoggerFactory.getLogger("CBCTB/ContraptionCompat");
    private static final String CONTRAPTION_ENTITY = "com.simibubi.create.content.contraptions.AbstractContraptionEntity";

    private static Boolean loaded;
    private static Method getContraptionMethod;
    private static Method getBlocksMethod;
    private static Method toLocalVectorMethod;
    private static Method reverseRotationMethod;
    private static Method getAnchorVecMethod;

    public static boolean isLoaded() {
        if (loaded == null) {
            try {
                loaded = ModList.get().isLoaded("create");
            } catch (Throwable ignored) {
                loaded = false;
            }
        }
        return loaded;
    }

    /**
     * Check if a world block position belongs to a Create contraption.
     */
    public static boolean isContraptionPos(Level level, BlockPos worldPos) {
        if (!isLoaded()) return false;
        Vec3 center = Vec3.atCenterOf(worldPos);
        List<Entity> entities = findContraptionEntities(level, center);
        for (Entity entity : entities) {
            Object hit = raycastSingle(level, center, center, entity);
            if (hit != null) return true;
        }
        return false;
    }

    /**
     * Get the contraption block state at a world position, or null if the position
     * is not part of a contraption.
     */
    @Nullable
    public static BlockState blockStateAt(Level level, BlockPos worldPos) {
        if (!isLoaded()) return null;
        Vec3 center = Vec3.atCenterOf(worldPos);
        List<Entity> entities = findContraptionEntities(level, center);
        for (Entity entity : entities) {
            Object hit = raycastSingle(level, center, center, entity);
            if (hit instanceof ContraptionHit ch) return ch.state();
        }
        return null;
    }

    /**
     * Raycast from {@code from} to {@code to} against all Create contraption entities near the ray.
     * Returns the nearest contraption block hit, or null if none.
     */
    @Nullable
    public static ContraptionHit raycast(Level level, Vec3 from, Vec3 to) {
        if (!isLoaded()) return null;
        try {
            List<Entity> entities = findContraptionEntities(level, from, to);
            if (entities.isEmpty()) return null;

            ContraptionHit best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (Entity entity : entities) {
                Object hit = raycastSingle(level, from, to, entity);
                if (hit instanceof ContraptionHit ch) {
                    double distSq = from.distanceToSqr(ch.hitLocation());
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = ch;
                    }
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Result of a raycast against Create contraption blocks.
     */
    public record ContraptionHit(
        BlockPos worldPos,
        Vec3 hitLocation,
        Direction direction,
        BlockState state
    ) {
        public BlockHitResult toBlockHitResult() {
            return new BlockHitResult(hitLocation, direction, worldPos, false);
        }
    }

    // --- Internal logic ---

    private static Class<?> getContraptionEntityClass() {
        try {
            return Class.forName(CONTRAPTION_ENTITY);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Entity> findContraptionEntities(Level level, Vec3... rayEndpoints) {
        AABB searchBox;
        if (rayEndpoints.length == 1) {
            searchBox = new AABB(rayEndpoints[0], rayEndpoints[0]).inflate(16);
        } else {
            searchBox = new AABB(rayEndpoints[0], rayEndpoints[rayEndpoints.length - 1]).inflate(16);
        }

        Class<?> entityClass = getContraptionEntityClass();
        if (entityClass == null) return List.of();

        try {
            return level.getEntitiesOfClass(
                (Class<Entity>) entityClass, searchBox);
        } catch (Throwable t) {
            return List.of();
        }
    }

    @Nullable
    private static ContraptionHit raycastSingle(Level level, Vec3 from, Vec3 to, Entity entity) {
        try {
            Object contraption = getContraption(entity);
            if (contraption == null) return null;

            Map<BlockPos, ?> blocks = getBlocks(contraption);
            if (blocks == null || blocks.isEmpty()) return null;

            Vec3 localFrom = toLocal(entity, from, 0f);
            Vec3 localTo = toLocal(entity, to, 0f);
            if (localFrom == null || localTo == null) return null;

            Vec3 localDir = localTo.subtract(localFrom);
            double dirLen = localDir.length();
            if (dirLen < 1e-6) return null;
            Vec3 localNorm = localDir.scale(1.0 / dirLen);

            // DDA voxel traversal setup
            int sx = localNorm.x > 0 ? 1 : localNorm.x < 0 ? -1 : 0;
            int sy = localNorm.y > 0 ? 1 : localNorm.y < 0 ? -1 : 0;
            int sz = localNorm.z > 0 ? 1 : localNorm.z < 0 ? -1 : 0;

            double tDeltaX = localNorm.x != 0 ? Math.abs(1.0 / localNorm.x) : Double.MAX_VALUE;
            double tDeltaY = localNorm.y != 0 ? Math.abs(1.0 / localNorm.y) : Double.MAX_VALUE;
            double tDeltaZ = localNorm.z != 0 ? Math.abs(1.0 / localNorm.z) : Double.MAX_VALUE;

            double tMaxX = localNorm.x != 0
                ? ((localNorm.x > 0 ? (Math.ceil(localFrom.x) - localFrom.x) : (localFrom.x - Math.floor(localFrom.x))) * tDeltaX)
                : Double.MAX_VALUE;
            double tMaxY = localNorm.y != 0
                ? ((localNorm.y > 0 ? (Math.ceil(localFrom.y) - localFrom.y) : (localFrom.y - Math.floor(localFrom.y))) * tDeltaY)
                : Double.MAX_VALUE;
            double tMaxZ = localNorm.z != 0
                ? ((localNorm.z > 0 ? (Math.ceil(localFrom.z) - localFrom.z) : (localFrom.z - Math.floor(localFrom.z))) * tDeltaZ)
                : Double.MAX_VALUE;

            BlockPos cur = BlockPos.containing(localFrom);
            Direction face = Direction.NORTH;
            int maxSteps = Math.min((int) (dirLen * 3) + 4, 256);

            for (int i = 0; i < maxSteps; i++) {
                // Advance DDA
                if (tMaxX < tMaxY) {
                    if (tMaxX < tMaxZ) {
                        cur = cur.offset(sx, 0, 0);
                        tMaxX += tDeltaX;
                        face = sx > 0 ? Direction.WEST : Direction.EAST;
                    } else {
                        cur = cur.offset(0, 0, sz);
                        tMaxZ += tDeltaZ;
                        face = sz > 0 ? Direction.SOUTH : Direction.NORTH;
                    }
                } else {
                    if (tMaxY < tMaxZ) {
                        cur = cur.offset(0, sy, 0);
                        tMaxY += tDeltaY;
                        face = sy > 0 ? Direction.DOWN : Direction.UP;
                    } else {
                        cur = cur.offset(0, 0, sz);
                        tMaxZ += tDeltaZ;
                        face = sz > 0 ? Direction.SOUTH : Direction.NORTH;
                    }
                }

                // Check if we've gone past the ray end
                Vec3 voxelCenter = Vec3.atCenterOf(cur);
                double tHit;
                if (localNorm.x != 0 && sx != 0) {
                    tHit = (voxelCenter.x - localFrom.x) / localNorm.x;
                } else if (localNorm.y != 0 && sy != 0) {
                    tHit = (voxelCenter.y - localFrom.y) / localNorm.y;
                } else {
                    tHit = (voxelCenter.z - localFrom.z) / localNorm.z;
                }
                if (tHit > dirLen) break;

                // Check for hit in contraption block map
                if (blocks.containsKey(cur)) {
                    // Compute exact hit point in local space along the ray
                    // The ray entered this voxel at the face we just stepped through
                    double hitT;
                    if (face == Direction.WEST)       hitT = (cur.getX() - localFrom.x) / localNorm.x;
                    else if (face == Direction.EAST)  hitT = (cur.getX() + 1 - localFrom.x) / localNorm.x;
                    else if (face == Direction.DOWN)  hitT = (cur.getY() - localFrom.y) / localNorm.y;
                    else if (face == Direction.UP)    hitT = (cur.getY() + 1 - localFrom.y) / localNorm.y;
                    else if (face == Direction.SOUTH) hitT = (cur.getZ() + 1 - localFrom.z) / localNorm.z;
                    else /* NORTH */                  hitT = (cur.getZ() - localFrom.z) / localNorm.z;

                    hitT = Math.max(0, Math.min(hitT, dirLen));
                    Vec3 localHit = localFrom.add(localNorm.scale(hitT));

                    // Transform to world space using Create's reverseRotation + anchor
                    Vec3 worldHit = reverseRotateAndTranslate(entity, localHit);
                    if (worldHit == null) continue;

                    Vec3 worldBlockCenter = reverseRotateAndTranslate(entity, Vec3.atCenterOf(cur));
                    if (worldBlockCenter == null) continue;

                    BlockPos worldBlockPos = BlockPos.containing(worldBlockCenter);

                    // Get the block state from the contraption
                    Object blockInfo = blocks.get(cur);
                    BlockState state = getBlockState(blockInfo);
                    if (state == null) continue;

                    Direction worldFace = toWorldDirection(entity, face);

                    return new ContraptionHit(worldBlockPos, worldHit, worldFace, state);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // --- Reflection helpers ---

    @Nullable
    private static Object getContraption(Entity entity) {
        try {
            if (getContraptionMethod == null)
                getContraptionMethod = entity.getClass().getMethod("getContraption");
            return getContraptionMethod.invoke(entity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<BlockPos, ?> getBlocks(Object contraption) {
        try {
            if (getBlocksMethod == null)
                getBlocksMethod = contraption.getClass().getMethod("getBlocks");
            return (Map<BlockPos, ?>) getBlocksMethod.invoke(contraption);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Vec3 toLocal(Entity entity, Vec3 worldVec, float pt) {
        try {
            if (toLocalVectorMethod == null)
                toLocalVectorMethod = entity.getClass().getMethod("toLocalVector", Vec3.class, float.class);
            return (Vec3) toLocalVectorMethod.invoke(entity, worldVec, pt);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Transform a local-space Vec3 to world space using Create's reverseRotation and anchor.
     * This is the inverse of toLocalVector for the rotational component.
     */
    @Nullable
    private static Vec3 reverseRotateAndTranslate(Entity entity, Vec3 localVec) {
        try {
            if (reverseRotationMethod == null)
                reverseRotationMethod = entity.getClass().getMethod("reverseRotation", Vec3.class, float.class);
            Vec3 rotated = (Vec3) reverseRotationMethod.invoke(entity, localVec, 0f);
            if (rotated == null) return null;

            if (getAnchorVecMethod == null)
                getAnchorVecMethod = entity.getClass().getMethod("getAnchorVec");
            Vec3 anchor = (Vec3) getAnchorVecMethod.invoke(entity);
            if (anchor == null) return null;

            return anchor.add(rotated);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Direction toWorldDirection(Entity entity, Direction localDir) {
        Vec3 normal = Vec3.atLowerCornerOf(localDir.getNormal());
        Vec3 worldNormal = reverseRotateDirection(entity, normal);
        if (worldNormal.lengthSqr() < 1e-8) return localDir;
        worldNormal = worldNormal.normalize();
        Direction best = localDir;
        double bestDot = -Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            double dot = worldNormal.dot(Vec3.atLowerCornerOf(d.getNormal()));
            if (dot > bestDot) {
                bestDot = dot;
                best = d;
            }
        }
        return best;
    }

    @Nullable
    private static Vec3 reverseRotateDirection(Entity entity, Vec3 vec) {
        try {
            if (reverseRotationMethod == null)
                reverseRotationMethod = entity.getClass().getMethod("reverseRotation", Vec3.class, float.class);
            return (Vec3) reverseRotationMethod.invoke(entity, vec, 0f);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static BlockState getBlockState(Object blockInfo) {
        try {
            // StructureTemplate.StructureBlockInfo: try field access first (1.20.1 record)
            java.lang.reflect.Field stateField = blockInfo.getClass().getField("state");
            return (BlockState) stateField.get(blockInfo);
        } catch (Throwable ignored) {
            try {
                // Try record accessor for newer versions
                java.lang.reflect.Method stateMethod = blockInfo.getClass().getMethod("state");
                return (BlockState) stateMethod.invoke(blockInfo);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private ContraptionCompat() {}
}
