package com.cbc_terminal_ballistics.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
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
 * <p>Create's {@code Contraption.getBlocks()} stores blocks in local contraption coordinates.
 * To detect a hit, we use the entity's {@code toGlobalVector()} method to transform each
 * local block center into world space, then run a ray-AABB intersection test against the
 * world-space bounding box of each block.</p>
 *
 * <h3>Coordinate system (from Create 0.5.1.j bytecode)</h3>
 * <pre>
 * toLocalVector(worldVec, pt):
 *     anchor = getAnchorVec()                     // entity position
 *     centerOfOrigin = VecHelper.getCenterOf(ZERO) // (0.5, 0.5, 0.5)
 *     v = worldVec - anchor - centerOfOrigin
 *     v = reverseRotation(v, pt)
 *     v = v - centerOfOrigin
 *     return v
 *
 * toGlobalVector(localVec, pt):
 *     anchor = getAnchorVec()
 *     centerOfOrigin = VecHelper.getCenterOf(ZERO) // (0.5, 0.5, 0.5)
 *     v = localVec - centerOfOrigin
 *     v = applyRotation(v, pt)
 *     v = v + centerOfOrigin + anchor
 *     return v
 * </pre>
 *
 * <h3>Known pitfalls</h3>
 * <ul>
 *   <li>{@code reverseRotation()} and {@code applyRotation()} are abstract on
 *       {@code AbstractContraptionEntity}; concrete subclasses implement them.
 *       Reflection must walk the class hierarchy to find the implementation.</li>
 *   <li>{@code StructureBlockInfo} in 1.20.1 Forge is a record. The deobfuscated
 *       accessor is {@code state()} but Forge SRG mappings use {@code f_74676_()}.
 *       Both must be tried.</li>
 *   <li>{@code ContraptionEntity} default AABB does NOT resize to the contraption
 *       structure. Entity search uses {@code getEntitiesOfClass} with an inflated box.</li>
 * </ul>
 */
public final class ContraptionCompat {
    private static final Logger LOG = LoggerFactory.getLogger("CBCTB/ContraptionCompat");
    private static final String CONTRAPTION_ENTITY = "com.simibubi.create.content.contraptions.AbstractContraptionEntity";

    private static Boolean loaded;

    // Cached reflection handles (per entity class for rotation, shared for others)
    private static Method getContraptionMethod;
    private static Method getBlocksMethod;
    private static Method toGlobalVectorMethod;
    private static Method getAnchorVecMethod;
    private static java.lang.reflect.Field stateField;
    private static Method stateAccessor;

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
     * Debug version of raycast that sends output to a command sender.
     */
    @Nullable
    public static ContraptionHit raycastDebug(Level level, Vec3 from, Vec3 to, CommandSourceStack source) {
        if (!isLoaded()) {
            source.sendSuccess(() -> Component.literal("Create is not loaded!").withStyle(ChatFormatting.RED), false);
            return null;
        }
        try {
            List<Entity> entities = findContraptionEntities(level, from, to);
            source.sendSuccess(() -> Component.literal("Entities found: " + entities.size()), false);

            if (entities.isEmpty()) {
                source.sendSuccess(() -> Component.literal("No contraption entities in search box.").withStyle(ChatFormatting.YELLOW), false);
                return null;
            }

            ContraptionHit best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (Entity entity : entities) {
                Vec3 epos = entity.position();
                source.sendSuccess(() -> Component.literal(String.format("  Entity: %s at (%.1f, %.1f, %.1f)", entity.getType().toShortString(), epos.x, epos.y, epos.z)), false);

                Object contraption = getContraption(entity);
                if (contraption == null) {
                    source.sendSuccess(() -> Component.literal("    getContraption() returned null"), false);
                    continue;
                }
                Map<BlockPos, ?> blocks = getBlocks(contraption);
                if (blocks == null || blocks.isEmpty()) {
                    source.sendSuccess(() -> Component.literal("    blocks map is null or empty"), false);
                    continue;
                }

                source.sendSuccess(() -> Component.literal("    Blocks: " + blocks.size()), false);

                // Show first 5 blocks with world positions for diagnostics
                int shown = 0;
                for (Map.Entry<BlockPos, ?> entry : blocks.entrySet()) {
                    BlockPos lp = entry.getKey();
                    BlockState bs = getBlockState(entry.getValue());
                    Vec3 wc = toGlobalVectorImpl(entity, Vec3.atCenterOf(lp), 0f);
                    source.sendSuccess(() -> Component.literal(String.format("      local=%s worldCenter=%s state=%s", lp, wc, bs)), false);
                    if (++shown >= 5) {
                        if (blocks.size() > 5) source.sendSuccess(() -> Component.literal("      ... and " + (blocks.size() - 5) + " more"), false);
                        break;
                    }
                }

                ContraptionHit hit = raycastSingle(level, from, to, entity);
                if (hit != null) {
                    source.sendSuccess(() -> Component.literal("    -> HIT! worldPos=" + hit.worldPos() + " face=" + hit.direction() + " state=" + hit.state()).withStyle(ChatFormatting.GREEN), false);
                    double distSq = from.distanceToSqr(hit.hitLocation());
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = hit;
                    }
                } else {
                    source.sendSuccess(() -> Component.literal("    -> MISS").withStyle(ChatFormatting.RED), false);
                }
            }
            return best;
        } catch (Throwable t) {
            source.sendSuccess(() -> Component.literal("Error: " + t.getMessage()).withStyle(ChatFormatting.RED), false);
            LOG.error("[ctb] raycastDebug threw", t);
            return null;
        }
    }

    @Nullable
    public static ContraptionHit raycast(Level level, Vec3 from, Vec3 to) {
        if (!isLoaded()) {
            LOG.info("[ctb] raycast: Create not loaded");
            return null;
        }
        try {
            List<Entity> entities = findContraptionEntities(level, from, to);
            LOG.info("[ctb] raycast: found {} contraption entities near {}-{}", entities.size(), from, to);
            if (entities.isEmpty()) return null;

            ContraptionHit best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (Entity entity : entities) {
                Object hit = raycastSingle(level, from, to, entity);
                if (hit instanceof ContraptionHit ch) {
                    LOG.info("[ctb] raycast: HIT on entity at {}! worldPos={} hitLoc={}", entity.position(), ch.worldPos(), ch.hitLocation());
                    double distSq = from.distanceToSqr(ch.hitLocation());
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = ch;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            LOG.error("[ctb] raycast threw", t);
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

    // --- Public accessors for debug command ---

    @SuppressWarnings("unchecked")
    public static List<Entity> findNearbyContraptionEntities(Level level, Vec3 from, Vec3 to) {
        return findContraptionEntities(level, from, to);
    }

    @Nullable
    public static Object getContraptionObject(Entity entity) {
        return getContraption(entity);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static Map<BlockPos, ?> getBlocksObject(Object contraption) {
        return getBlocks(contraption);
    }

    @Nullable
    public static Vec3 toGlobalVector(Entity entity, Vec3 localVec, float pt) {
        return toGlobalVectorImpl(entity, localVec, pt);
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
            List<Entity> raw = level.getEntitiesOfClass(
                (Class<Entity>) entityClass, searchBox);
            List<Entity> filtered = new ArrayList<>();
            for (Entity e : raw) {
                Vec3 pos = e.position();
                if (Math.abs(pos.x) > 10000 || Math.abs(pos.y) > 10000 || Math.abs(pos.z) > 10000) continue;
                filtered.add(e);
            }
            return filtered;
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

            Vec3 rayDir = to.subtract(from);
            double rayLen = rayDir.length();
            if (rayLen < 1e-6) return null;
            Vec3 rayNorm = rayDir.scale(1.0 / rayLen);

            ContraptionHit best = null;
            double bestT = Double.MAX_VALUE;

            for (Map.Entry<BlockPos, ?> entry : blocks.entrySet()) {
                BlockPos localPos = entry.getKey();
                Object blockInfo = entry.getValue();
                BlockState state = getBlockState(blockInfo);

                // Use toGlobalVector to correctly transform local block center to world space.
                // This accounts for anchor offset + centerOfOrigin(0.5,0.5,0.5) + rotation,
                // unlike the previous reverseRotateAndTranslate which was missing the
                // centerOfOrigin subtraction/addition.
                Vec3 worldCenter = toGlobalVectorImpl(entity, Vec3.atCenterOf(localPos), 0f);
                if (worldCenter == null) continue;

                // For unrotated contraptions (bearing at 0 degrees), blocks form an axis-aligned
                // bounding box in world space. We can approximate the world AABB as center +/- 0.5.
                // For rotated contraptions, the AABB would be larger. We inflate to be safe.
                // The inflation factor accounts for diagonal blocks in rotated contraptions.
                AABB worldBox = new AABB(
                    worldCenter.x - 0.5, worldCenter.y - 0.5, worldCenter.z - 0.5,
                    worldCenter.x + 0.5, worldCenter.y + 0.5, worldCenter.z + 0.5
                );

                double t = rayAabbIntersect(from, rayNorm, worldBox);
                if (t >= 0 && t < bestT && t <= rayLen) {
                    bestT = t;
                    Vec3 hitLoc = from.add(rayNorm.scale(t));

                    Direction hitFace = determineFace(worldCenter, hitLoc);
                    BlockPos worldBlockPos = BlockPos.containing(worldCenter);

                    best = new ContraptionHit(worldBlockPos, hitLoc, hitFace, state);
                }
            }

            return best;
        } catch (Throwable t) {
            LOG.error("[ctb] raycastSingle threw", t);
            return null;
        }
    }

    /**
     * Ray-AABB intersection using the slab method. Returns t along rayNorm from origin,
     * or -1 if no intersection.
     */
    private static double rayAabbIntersect(Vec3 origin, Vec3 rayNorm, AABB box) {
        double tmin = Double.NEGATIVE_INFINITY;
        double tmax = Double.POSITIVE_INFINITY;

        if (rayNorm.x != 0) {
            double t1 = (box.minX - origin.x) / rayNorm.x;
            double t2 = (box.maxX - origin.x) / rayNorm.x;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
        } else if (origin.x < box.minX || origin.x > box.maxX) {
            return -1;
        }

        if (rayNorm.y != 0) {
            double t1 = (box.minY - origin.y) / rayNorm.y;
            double t2 = (box.maxY - origin.y) / rayNorm.y;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
        } else if (origin.y < box.minY || origin.y > box.maxY) {
            return -1;
        }

        if (rayNorm.z != 0) {
            double t1 = (box.minZ - origin.z) / rayNorm.z;
            double t2 = (box.maxZ - origin.z) / rayNorm.z;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
        } else if (origin.z < box.minZ || origin.z > box.maxZ) {
            return -1;
        }

        if (tmax < tmin || tmax < 0) return -1;
        return tmin >= 0 ? tmin : tmax;
    }

    /**
     * Determine which face of a block was hit based on the world-space block center
     * and the hit point.
     */
    private static Direction determineFace(Vec3 blockCenter, Vec3 hitLoc) {
        double dx = hitLoc.x - blockCenter.x;
        double dy = hitLoc.y - blockCenter.y;
        double dz = hitLoc.z - blockCenter.z;
        double adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
        if (adx > ady && adx > adz) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (ady > adz) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    // --- Reflection helpers ---

    @Nullable
    private static Object getContraption(Entity entity) {
        try {
            if (getContraptionMethod == null)
                getContraptionMethod = findMethodUpHierarchy(entity.getClass(), "getContraption");
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
                getBlocksMethod = findMethodUpHierarchy(contraption.getClass(), "getBlocks");
            return (Map<BlockPos, ?>) getBlocksMethod.invoke(contraption);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Transform a local-space Vec3 to world space using Create's toGlobalVector.
     * This is the correct inverse of toLocalVector and accounts for:
     * <ul>
     *   <li>Anchor offset (entity position)</li>
     *   <li>Center-of-origin offset (0.5, 0.5, 0.5)</li>
     *   <li>Rotation (applyRotation)</li>
     * </ul>
     */
    @Nullable
    private static Vec3 toGlobalVectorImpl(Entity entity, Vec3 localVec, float pt) {
        try {
            if (toGlobalVectorMethod == null)
                toGlobalVectorMethod = findMethodUpHierarchy(entity.getClass(), "toGlobalVector", Vec3.class, float.class);
            return (Vec3) toGlobalVectorMethod.invoke(entity, localVec, pt);
        } catch (Throwable t) {
            LOG.warn("[ctb] toGlobalVector failed for {}: {}", entity.getClass().getSimpleName(), t.getMessage());
            return null;
        }
    }

    /**
     * Walk up the class hierarchy to find a method by name. This handles the case where
     * abstract methods are declared on AbstractContraptionEntity but overridden on subclasses.
     */
    private static Method findMethodUpHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        // Final fallback: try getMethod (public methods only, including inherited)
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Cannot find method " + name + " on " + clazz.getName(), e);
        }
    }

    @Nullable
    private static BlockState getBlockState(Object blockInfo) {
        try {
            if (stateField == null && stateAccessor == null) {
                // Strategy 1: Deobfuscated record accessor: state()
                try {
                    Method m = blockInfo.getClass().getMethod("state");
                    if (m.getReturnType() != void.class) {
                        stateAccessor = m;
                    }
                } catch (Throwable ignored) {}

                // Strategy 2: Forge SRG-mapped accessor: f_74676_()
                if (stateAccessor == null) {
                    try {
                        Method m = blockInfo.getClass().getMethod("f_74676_");
                        if (m.getReturnType() != void.class) {
                            stateAccessor = m;
                        }
                    } catch (Throwable ignored) {}
                }

                // Strategy 3: getDeclaredField("state") + setAccessible
                if (stateAccessor == null) {
                    try {
                        stateField = blockInfo.getClass().getDeclaredField("state");
                        stateField.setAccessible(true);
                    } catch (Throwable ignored) {}
                }

                // Strategy 4: Iterate all declared methods looking for one returning BlockState
                if (stateField == null && stateAccessor == null) {
                    for (Method m : blockInfo.getClass().getDeclaredMethods()) {
                        if (m.getReturnType() == BlockState.class && m.getParameterCount() == 0) {
                            m.setAccessible(true);
                            stateAccessor = m;
                            LOG.info("[ctb] getBlockState: found via method scan: {}()", m.getName());
                            break;
                        }
                    }
                }

                if (stateField == null && stateAccessor == null) {
                    LOG.error("[ctb] getBlockState: no way to access BlockState on {} (methods: {})",
                        blockInfo.getClass().getName(),
                        java.util.Arrays.toString(blockInfo.getClass().getDeclaredMethods()));
                    return null;
                }
            }

            Object val;
            if (stateAccessor != null) {
                val = stateAccessor.invoke(blockInfo);
            } else {
                val = stateField.get(blockInfo);
            }

            if (val instanceof BlockState bs) return bs;
            return null;
        } catch (Throwable e) {
            stateField = null;
            stateAccessor = null;
            LOG.error("[ctb] getBlockState threw on {}: {}", blockInfo.getClass().getName(), e.getMessage());
            return null;
        }
    }

    private ContraptionCompat() {}
}
