package com.cbc_terminal_ballistics.util;

import com.cbc_terminal_ballistics.client.VSClientCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
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
    private static Method toLocalVectorMethod;
    private static Method getAnchorVecMethod;
    private static java.lang.reflect.Field stateField;
    private static Method stateAccessor;
    private static java.lang.reflect.Field nbtField;
    private static Method nbtAccessor;

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
        return blockStateAt(level, worldPos) != null;
    }

    /**
     * Returns true if the world position contains a contraption block (even though
     * the world chunk has air there).
     */
    public static boolean isContraptionBlock(Level level, BlockPos worldPos) {
        return blockStateAt(level, worldPos) != null;
    }

    /**
     * Get the contraption block state at a world position, or null if the position
     * is not part of a contraption.  Uses point-in-AABB containment rather than
     * raycasting so this works for single-point lookups (unlike raycastSingle which
     * requires from != to).
     */
    @Nullable
    public static BlockState blockStateAt(Level level, BlockPos worldPos) {
        if (!isLoaded()) return null;
        Vec3 center = Vec3.atCenterOf(worldPos);
        List<Entity> entities = findContraptionEntities(level, center);
        for (Entity entity : entities) {
            BlockState state = pointInContraptionBlock(center, entity);
            if (state != null) return state;
        }
        return null;
    }

    /**
     * Find the contraption entity whose block contains the given world position,
     * or null. Unlike {@link #blockStateAt}, this also identifies WHICH entity owns
     * the block, which is required for contraption-anchored decals (marks/shells).
     */
    @Nullable
    public static Entity contraptionEntityAt(Level level, BlockPos worldPos) {
        if (!isLoaded()) return null;
        Vec3 center = Vec3.atCenterOf(worldPos);
        for (Entity entity : findContraptionEntities(level, center)) {
            if (pointInContraptionBlock(center, entity) != null) return entity;
        }
        return null;
    }

    /**
     * Return the contraption-local block position whose transformed world AABB
     * contains the given world-space point, or null.
     */
    @Nullable
    public static BlockPos localPosAt(Entity entity, Vec3 worldPoint) {
        LocalHit hit = pointInContraptionBlockDetailed(worldPoint, entity);
        return hit == null ? null : hit.localPos();
    }

    /**
     * Resolve the block state for a contraption-local position directly from the
     * contraption's block map. Unlike {@link #blockStateAt} this does NOT depend on
     * the contraption's current world position, so it keeps working after the
     * contraption has moved or rotated.
     */
    @Nullable
    public static BlockState blockStateForLocal(Entity entity, BlockPos localPos) {
        try {
            Object contraption = getContraption(entity);
            if (contraption == null) return null;
            Map<BlockPos, ?> blocks = getBlocks(contraption);
            if (blocks == null) return null;
            Object blockInfo = blocks.get(localPos);
            if (blockInfo == null) return null;
            BlockState state = getBlockState(blockInfo);
            return state == null || state.isAir() ? null : state;
        } catch (Throwable t) {
            LOG.debug("[ctb] blockStateForLocal failed: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Serialized BlockEntity NBT of a contraption block, read from its block map
     * entry (StructureBlockInfo carries the BE's saved tag alongside the state).
     * Contraptions do not host live BlockEntities (Create 6 removed the live map
     * entirely), so BE-dependent shape data has to come from here.
     */
    @Nullable
    public static CompoundTag blockInfoNbtAt(Entity entity, BlockPos localPos) {
        try {
            Object contraption = getContraption(entity);
            if (contraption == null) return null;
            Map<BlockPos, ?> blocks = getBlocks(contraption);
            if (blocks == null) return null;
            Object blockInfo = blocks.get(localPos);
            if (blockInfo == null) return null;
            return getBlockInfoNbt(blockInfo);
        } catch (Throwable t) {
            LOG.debug("[ctb] blockInfoNbtAt failed: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Transform a contraption-local position to its current world center. The
     * contraption's live position/rotation at the given partial tick is applied
     * (plus the mounted ship's transform), so callers get the block's present
     * world location.
     */
    @Nullable
    public static Vec3 worldCenterForLocal(Entity entity, BlockPos localPos, float partialTick) {
        return localToWorld(entity, Vec3.atCenterOf(localPos), partialTick);
    }

    /**
     * Current orientation of an oriented contraption (e.g. mechanical bearing), in
     * degrees as {@code {yaw, pitch}}. Returns null when the entity type has no
     * orientation (stationary contraptions, etc.) or reflection fails.
     */
    @Nullable
    public static float[] orientationDegrees(Entity entity) {
        try {
            Field yawField = findFieldUpHierarchy(entity.getClass(), "yaw");
            Field pitchField = findFieldUpHierarchy(entity.getClass(), "pitch");
            if (yawField == null && pitchField == null) return null;
            float yaw = yawField != null ? yawField.getFloat(entity) : 0.0f;
            float pitch = pitchField != null ? pitchField.getFloat(entity) : 0.0f;
            return new float[]{yaw, pitch};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Build an immutable world-space snapshot of all solid blocks of a contraption
     * entity. The box list is computed once (O(blocks) reflection transforms) and can
     * then answer many raycasts with pure math - used by spall fragment occlusion.
     */
    @Nullable
    public static ContraptionSnapshot snapshot(Entity entity) {
        try {
            Object contraption = getContraption(entity);
            if (contraption == null) return null;
            Map<BlockPos, ?> blocks = getBlocks(contraption);
            if (blocks == null || blocks.isEmpty()) return null;
            List<ContraptionSnapshot.Box> boxes = new ArrayList<>(blocks.size());
            for (Map.Entry<BlockPos, ?> entry : blocks.entrySet()) {
                BlockState state = getBlockState(entry.getValue());
                if (state == null || state.isAir()) continue;
                boxes.add(new ContraptionSnapshot.Box(entry.getKey().immutable(), state));
            }
            if (boxes.isEmpty()) return null;
            return new ContraptionSnapshot(entity, boxes);
        } catch (Throwable t) {
            LOG.debug("[ctb] snapshot failed: {}", t.getMessage());
            return null;
        }
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
                    Vec3 wc = localToWorld(entity, Vec3.atCenterOf(lp), 1.0f);
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
        return raycast(level, from, to, null);
    }

    /**
     * Nearest contraption hit along the ray. {@code excludeContraption} skips one
     * entity by UUID (used to suppress self-hits on the firing cannon contraption).
     */
    @Nullable
    public static ContraptionHit raycast(Level level, Vec3 from, Vec3 to, @Nullable java.util.UUID excludeContraption) {
        if (!isLoaded()) {
            LOG.debug("[ctb] raycast: Create not loaded");
            return null;
        }
        try {
            List<Entity> entities = findContraptionEntities(level, from, to);
            LOG.debug("[ctb] raycast: found {} contraption entities near {}-{}", entities.size(), from, to);
            if (entities.isEmpty()) return null;

            ContraptionHit best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (Entity entity : entities) {
                if (excludeContraption != null && excludeContraption.equals(entity.getUUID())) continue;
                Object hit = raycastSingle(level, from, to, entity);
                if (hit instanceof ContraptionHit ch) {
                    LOG.debug("[ctb] raycast: HIT on entity at {}! worldPos={} hitLoc={}", entity.position(), ch.worldPos(), ch.hitLocation());
                    double distSq = from.distanceToSqr(ch.hitLocation());
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = ch;
                    }
                }
            }
            // Publish the exact normal of the hit actually returned (not a nearer
            // miss from an entity that was scanned later).
            if (best != null) {
                lastHitLocation = best.hitLocation();
                lastHitNormal = best.trueNormal();
            }
            return best;
        } catch (Throwable t) {
            LOG.error("[ctb] raycast threw", t);
            return null;
        }
    }

    /**
     * Find the contraption entity the given projectile most likely belongs to (was
     * fired from): the contraption whose local structure bounding box (inflated)
     * contains the projectile's position. Used to suppress self-hits on the firing
     * cannon contraption, which otherwise block the shell right at the muzzle.
     */
    @Nullable
    public static Entity sourceContraptionCandidate(Entity projectile) {
        if (!isLoaded()) return null;
        try {
            Vec3 point = projectile.position();
            for (Entity entity : findContraptionEntities(projectile.level(), point)) {
                Object contraption = getContraption(entity);
                if (contraption == null) continue;
                Map<BlockPos, ?> blocks = getBlocks(contraption);
                if (blocks == null || blocks.isEmpty()) continue;
                Vec3 localPoint = worldToLocal(entity, point, 1.0f);
                if (localPoint == null) continue;
                AABB localBounds = null;
                for (BlockPos localPos : blocks.keySet()) {
                    localBounds = localBounds == null ? new AABB(localPos) : localBounds.minmax(new AABB(localPos));
                }
                if (localBounds == null) continue;
                if (localBounds.inflate(1.5).contains(localPoint)) return entity;
            }
        } catch (Throwable t) {
            LOG.debug("[ctb] sourceContraptionCandidate failed: {}", t.getMessage());
        }
        return null;
    }

    /**
     * Result of a raycast against Create contraption blocks.
     *
     * @param worldPos   the world-snapped block position of the hit block
     * @param hitLocation exact world-space hit point
     * @param direction  axis-snapped world face (for the vanilla BlockHitResult)
     * @param state      the contraption block state
     * @param entity     the owning contraption entity
     * @param trueNormal exact world-space surface normal (rotated contraptions have
     *                   non-axis-aligned normals; {@code direction} is only the
     *                   nearest axis)
     * @param localPos   contraption-local block position of the hit block
     */
    public record ContraptionHit(
        BlockPos worldPos,
        Vec3 hitLocation,
        Direction direction,
        BlockState state,
        Entity entity,
        Vec3 trueNormal,
        BlockPos localPos
    ) {
        public BlockHitResult toBlockHitResult() {
            return new BlockHitResult(hitLocation, direction, worldPos, false);
        }
    }

    // Exact world-space impact normal of the most recent contraption raycast hit.
    // TBImpactService consumes it immediately after the clip that produced the hit;
    // matched by hit-location proximity to guard against staleness (the normal cannot
    // ride through CBC's axis-aligned BlockHitResult).
    private static Vec3 lastHitLocation;
    private static Vec3 lastHitNormal;

    /**
     * Returns the exact surface normal recorded for a contraption hit at the given
     * location, or null when no matching recent hit exists.
     */
    @Nullable
    public static Vec3 matchedContraptionNormal(Vec3 hitLocation) {
        if (lastHitLocation == null || lastHitNormal == null) return null;
        if (lastHitLocation.distanceToSqr(hitLocation) > 1.0e-6) return null;
        return lastHitNormal;
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

    // --- Ship-mounted contraption support ---
    // When a Create contraption is assembled on a VS2 ship, VS2's shipyard entity
    // handler keeps the entity in SHIP-LOCAL coordinates; Create's toGlobalVector/
    // toLocalVector only span the contraption's own transform. The ship's transform
    // must be composed on top for anything to line up in the world.

    private record ShipCacheEntry(@Nullable Object ship, long expireGameTime) {}

    private static final java.util.Map<Entity, ShipCacheEntry> SHIP_BY_CONTRAPTION = new java.util.WeakHashMap<>();
    private static final long SHIP_CACHE_TICKS = 20;

    /**
     * The VS2 ship this contraption entity is mounted on, or null when it lives
     * directly in the world. Cached briefly — ship association only changes on
     * disassembly, and lookups run per raycast/per rendered mark.
     *
     * <p>Shipyard entities live in SHIP-SPACE coordinates (VS2's shipyard entity
     * handler), so the world-chunk ship lookup cannot resolve them; the
     * association is resolved positionally per ship (see
     * {@code VSCompat.shipMountedAt}).</p>
     */
    @Nullable
    private static Object shipOf(Entity entity) {
        Level level = entity.level();
        long now = level.getGameTime();
        ShipCacheEntry cached = SHIP_BY_CONTRAPTION.get(entity);
        if (cached != null && now <= cached.expireGameTime()) return cached.ship();
        Object ship = null;
        try {
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                ship = VSCompat.shipMountedAt(serverLevel, entity.position());
            } else if (level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
                ship = VSClientCompat.shipMountedAt(clientLevel, entity.position());
            }
        } catch (Throwable t) {
            LOG.debug("[ctb] shipOf failed: {}", t.getMessage());
        }
        SHIP_BY_CONTRAPTION.put(entity, new ShipCacheEntry(ship, now + SHIP_CACHE_TICKS));
        return ship;
    }

    /**
     * Contraption-local point to world space. When the contraption is mounted on a
     * VS2 ship, the ship's transform is composed on top of Create's contraption
     * transform (client uses the interpolated render transform).
     */
    @Nullable
    public static Vec3 localToWorld(Entity entity, Vec3 contraptionLocal, float partialTick) {
        Vec3 framePos = toGlobalVectorImpl(entity, contraptionLocal, partialTick);
        if (framePos == null) return null;
        Object ship = shipOf(entity);
        if (ship == null) return framePos;
        if (entity.level().isClientSide) {
            // Guarded branch: VSClientCompat is only classloaded client-side.
            return VSClientCompat.shipRenderLocalToWorld(ship, framePos);
        }
        return VSCompat.shipLocalToWorld(ship, framePos);
    }

    /**
     * World point to contraption-local space (inverse of {@link #localToWorld}).
     */
    @Nullable
    public static Vec3 worldToLocal(Entity entity, Vec3 worldPos, float partialTick) {
        Object ship = shipOf(entity);
        Vec3 framePos = worldPos;
        if (ship != null) {
            // Client ray tests use the interpolated render transform for the same
            // reason localToWorld does; the physics transform would introduce a
            // render-lag offset into the round trip on a moving ship.
            Vec3 shipLocal = entity.level().isClientSide
                ? VSClientCompat.shipRenderWorldToLocal(ship, worldPos)
                : VSCompat.toShipLocal(ship, worldPos);
            if (shipLocal == null) return null;
            framePos = shipLocal;
        }
        return toLocalVector(entity, framePos, partialTick);
    }

    /**
     * Transform a world-space Vec3 into contraption-local coordinates using Create's
     * own toLocalVector (the inverse of {@link #toGlobalVector}). Null when Create is
     * absent or reflection fails.
     */
    @Nullable
    public static Vec3 toLocalVector(Entity entity, Vec3 worldVec, float pt) {
        try {
            if (toLocalVectorMethod == null)
                toLocalVectorMethod = findMethodUpHierarchy(entity.getClass(), "toLocalVector", Vec3.class, float.class);
            return (Vec3) toLocalVectorMethod.invoke(entity, worldVec, pt);
        } catch (Throwable t) {
            LOG.debug("[ctb] toLocalVector failed: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Convert a world-space face direction into the contraption's local frame, sampled
     * at the given world point. Returns null when the transform is unavailable (caller
     * should fall back to the world face). Probes run through worldToLocal so the ship
     * transform is composed for ship-mounted contraptions.
     */
    @Nullable
    public static Direction localFaceAt(Entity entity, Vec3 worldPoint, Direction worldFace) {
        Vec3 normal = Vec3.atLowerCornerOf(worldFace.getNormal());
        Vec3 probeA = worldToLocal(entity, worldPoint.add(normal.scale(0.25)), 1.0f);
        Vec3 probeB = worldToLocal(entity, worldPoint.subtract(normal.scale(0.25)), 1.0f);
        if (probeA == null || probeB == null) return null;
        Vec3 delta = probeA.subtract(probeB);
        double ax = Math.abs(delta.x), ay = Math.abs(delta.y), az = Math.abs(delta.z);
        if (ax > ay && ax > az) return delta.x > 0 ? Direction.EAST : Direction.WEST;
        if (ay > az) return delta.y > 0 ? Direction.UP : Direction.DOWN;
        return delta.z > 0 ? Direction.SOUTH : Direction.NORTH;
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
        Class<?> entityClass = getContraptionEntityClass();
        if (entityClass == null) return List.of();

        // Frame-independent scan. A world-frame search box cannot find ship-mounted
        // contraption entities, whose stored positions are SHIP-LOCAL (VS2 shipyard
        // entity handler). Contraption entities are few, so full iteration is cheap.
        Iterable<Entity> all;
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            all = serverLevel.getAllEntities();
        } else if (level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
            all = clientLevel.entitiesForRendering();
        } else {
            // Custom Level implementation: fall back to a box search.
            AABB searchBox = rayEndpoints.length == 1
                ? new AABB(rayEndpoints[0], rayEndpoints[0]).inflate(16)
                : new AABB(rayEndpoints[0], rayEndpoints[rayEndpoints.length - 1]).inflate(16);
            try {
                return new ArrayList<>(level.getEntitiesOfClass((Class<Entity>) entityClass, searchBox));
            } catch (Throwable t) {
                return List.of();
            }
        }

        List<Entity> filtered = new ArrayList<>();
        for (Entity e : all) {
            if (!entityClass.isInstance(e) || !e.isAlive()) continue;
            Vec3 pos = e.position();
            // Corruption guard only — shipyard contraption entities legitimately
            // carry large ship-space coordinates (ships assembled far from origin).
            if (Math.abs(pos.x) > 1000000 || Math.abs(pos.y) > 1000000 || Math.abs(pos.z) > 1000000) continue;
            filtered.add(e);
        }
        return filtered;
    }

    /**
     * Matched block within a contraption: the local position and its block state.
     */
    private record LocalHit(BlockPos localPos, BlockState state) {}

    /**
     * Check if a world-space point falls inside any contraption block, tested in the
     * contraption's LOCAL frame so it is exact for rotated contraptions. Returns the
     * matched local position and block state, or null.
     */
    @Nullable
    private static LocalHit pointInContraptionBlockDetailed(Vec3 point, Entity entity) {
        try {
            Object contraption = getContraption(entity);
            if (contraption == null) return null;

            Map<BlockPos, ?> blocks = getBlocks(contraption);
            if (blocks == null || blocks.isEmpty()) return null;

            Vec3 localPoint = worldToLocal(entity, point, 1.0f);
            if (localPoint == null) return null;

            for (Map.Entry<BlockPos, ?> entry : blocks.entrySet()) {
                BlockPos localPos = entry.getKey();
                if (!new AABB(localPos).contains(localPoint)) continue;
                BlockState state = getBlockState(entry.getValue());
                if (state == null || state.isAir()) continue;
                return new LocalHit(localPos, state);
            }
        } catch (Throwable t) {
            LOG.debug("[ctb] pointInContraptionBlock failed: {}", t.getMessage());
        }
        return null;
    }

    @Nullable
    private static BlockState pointInContraptionBlock(Vec3 point, Entity entity) {
        LocalHit hit = pointInContraptionBlockDetailed(point, entity);
        return hit == null ? null : hit.state();
    }

    @Nullable
    private static ContraptionHit raycastSingle(Level level, Vec3 from, Vec3 to, Entity entity) {
        try {
            Object contraption = getContraption(entity);
            if (contraption == null) return null;

            Map<BlockPos, ?> blocks = getBlocks(contraption);
            if (blocks == null || blocks.isEmpty()) return null;

            // Transform the RAY into contraption-local space instead of transforming
            // every block to world space: local block cells are exact axis-aligned
            // boxes for ANY contraption rotation (rigid transform, no scale). The old
            // world-space center+/-0.5 boxes made rotated hulls collide as if unrotated.
            // worldToLocal composes the ship transform for ship-mounted contraptions.
            Vec3 localFrom = worldToLocal(entity, from, 1.0f);
            Vec3 localTo = worldToLocal(entity, to, 1.0f);
            if (localFrom == null || localTo == null) return null;

            Vec3 rayDir = localTo.subtract(localFrom);
            double rayLen = rayDir.length();
            if (rayLen < 1e-6) return null;
            Vec3 rayNorm = rayDir.scale(1.0 / rayLen);

            ContraptionHit best = null;
            double bestT = Double.MAX_VALUE;

            for (Map.Entry<BlockPos, ?> entry : blocks.entrySet()) {
                BlockPos localPos = entry.getKey();
                BlockState state = getBlockState(entry.getValue());
                if (state == null || state.isAir()) continue;

                double t = rayAabbIntersect(localFrom, rayNorm, new AABB(localPos));
                if (t >= 0 && t < bestT && t <= rayLen) {
                    bestT = t;
                    Vec3 localHit = localFrom.add(rayNorm.scale(t));
                    Vec3 localCenter = Vec3.atCenterOf(localPos);
                    Direction localFace = determineFace(localCenter, localHit);

                    Vec3 worldHitLoc = localToWorld(entity, localHit, 1.0f);
                    Vec3 worldCenter = localToWorld(entity, localCenter, 1.0f);
                    if (worldHitLoc == null || worldCenter == null) continue;

                    Vec3 trueNormal = trueNormalFromLocal(entity, localCenter, localFace, worldCenter);
                    Direction worldFace = trueNormal != null ? dominantDirection(trueNormal) : localFace;
                    BlockPos worldBlockPos = BlockPos.containing(worldCenter);

                    best = new ContraptionHit(worldBlockPos, worldHitLoc, worldFace, state, entity, trueNormal, localPos);
                }
            }

            return best;
        } catch (Throwable t) {
            LOG.error("[ctb] raycastSingle threw", t);
            return null;
        }
    }

    /**
     * Exact world-space normal of a local face, sampled via localToWorld around the
     * block center (composes the ship transform for ship-mounted contraptions).
     * Null when the transform is unavailable.
     */
    @Nullable
    private static Vec3 trueNormalFromLocal(Entity entity, Vec3 localCenter, Direction localFace, Vec3 worldCenter) {
        Vec3 localNormal = Vec3.atLowerCornerOf(localFace.getNormal());
        Vec3 probe = localToWorld(entity, localCenter.add(localNormal.scale(0.5)), 1.0f);
        if (probe == null) return null;
        Vec3 delta = probe.subtract(worldCenter);
        if (delta.lengthSqr() < 1.0e-10) return null;
        return delta.normalize();
    }

    /** Nearest world axis for a (possibly rotated) normal vector. */
    private static Direction dominantDirection(Vec3 normal) {
        double ax = Math.abs(normal.x), ay = Math.abs(normal.y), az = Math.abs(normal.z);
        if (ax > ay && ax > az) return normal.x > 0 ? Direction.EAST : Direction.WEST;
        if (ay > az) return normal.y > 0 ? Direction.UP : Direction.DOWN;
        return normal.z > 0 ? Direction.SOUTH : Direction.NORTH;
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

    /**
     * Immutable snapshot of a contraption's solid blocks for repeated ray tests
     * without per-ray reflection (spall fragments, LOS checks). Rays are transformed
     * to the contraption's local frame, so collision is exact for any rotation.
     */
    public static final class ContraptionSnapshot {
        /** One solid block: local cell + state. */
        public record Box(BlockPos localPos, BlockState state) {}

        /** Nearest hit of a ray against the snapshot. */
        public record Hit(BlockPos worldPos, Vec3 hitLocation, Direction direction, BlockState state,
                          BlockPos localPos, double distance) {}

        private final Entity entity;
        private final List<Box> boxes;

        private ContraptionSnapshot(Entity entity, List<Box> boxes) {
            this.entity = entity;
            this.boxes = boxes;
        }

        public int boxCount() {
            return boxes.size();
        }

        /** Distance along the ray to the nearest contraption block, or +Infinity when clear. */
        public double rayDistance(Vec3 from, Vec3 to) {
            Hit hit = raycast(from, to);
            return hit == null ? Double.POSITIVE_INFINITY : hit.distance();
        }

        /** Nearest contraption block along the ray, or null. */
        @Nullable
        public Hit raycast(Vec3 from, Vec3 to) {
            Vec3 localFrom = worldToLocal(entity, from, 1.0f);
            Vec3 localTo = worldToLocal(entity, to, 1.0f);
            if (localFrom == null || localTo == null) return null;
            Vec3 rayDir = localTo.subtract(localFrom);
            double rayLen = rayDir.length();
            if (rayLen < 1e-6) return null;
            Vec3 rayNorm = rayDir.scale(1.0 / rayLen);

            Hit best = null;
            double bestT = Double.MAX_VALUE;
            for (Box box : boxes) {
                AABB localBox = new AABB(box.localPos());
                // A ray starting inside a block (e.g. the spall origin inside the impacted
                // block) must not treat that block as a barrier.
                if (localBox.contains(localFrom)) continue;
                double t = rayAabbIntersect(localFrom, rayNorm, localBox);
                if (t >= 0 && t <= rayLen && t < bestT) {
                    bestT = t;
                    Vec3 localHit = localFrom.add(rayNorm.scale(t));
                    Vec3 localCenter = Vec3.atCenterOf(box.localPos());
                    Direction localFace = determineFace(localCenter, localHit);
                    Vec3 worldHitLoc = localToWorld(entity, localHit, 1.0f);
                    Vec3 worldCenter = localToWorld(entity, localCenter, 1.0f);
                    if (worldHitLoc == null || worldCenter == null) continue;
                    Vec3 trueNormal = trueNormalFromLocal(entity, localCenter, localFace, worldCenter);
                    Direction worldFace = trueNormal != null ? dominantDirection(trueNormal) : localFace;
                    best = new Hit(BlockPos.containing(worldCenter), worldHitLoc, worldFace,
                        box.state(), box.localPos(), t);
                }
            }
            return best;
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

    /**
     * Walk up the class hierarchy to find a field by name, or null. This handles
     * fields declared on subclasses like OrientedContraptionEntity's yaw/pitch.
     */
    @Nullable
    private static Field findFieldUpHierarchy(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        return null;
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

    /**
     * Extract the serialized BlockEntity NBT from a contraption block-map entry.
     * Mirrors {@link #getBlockState}'s strategies: StructureBlockInfo is a record
     * whose third component is the tag (deobf {@code nbt()}, SRG {@code f_74677_()}).
     */
    @Nullable
    private static CompoundTag getBlockInfoNbt(Object blockInfo) {
        try {
            if (nbtField == null && nbtAccessor == null) {
                try {
                    Method m = blockInfo.getClass().getMethod("nbt");
                    if (m.getReturnType() != void.class) {
                        nbtAccessor = m;
                    }
                } catch (Throwable ignored) {}
                if (nbtAccessor == null) {
                    try {
                        Method m = blockInfo.getClass().getMethod("f_74677_");
                        if (m.getReturnType() != void.class) {
                            nbtAccessor = m;
                        }
                    } catch (Throwable ignored) {}
                }
                if (nbtAccessor == null) {
                    try {
                        nbtField = blockInfo.getClass().getDeclaredField("nbt");
                        nbtField.setAccessible(true);
                    } catch (Throwable ignored) {}
                }
                if (nbtField == null && nbtAccessor == null) {
                    for (Method m : blockInfo.getClass().getDeclaredMethods()) {
                        if (m.getReturnType() == CompoundTag.class && m.getParameterCount() == 0) {
                            m.setAccessible(true);
                            nbtAccessor = m;
                            break;
                        }
                    }
                }
                if (nbtField == null && nbtAccessor == null) return null;
            }
            Object val = nbtAccessor != null ? nbtAccessor.invoke(blockInfo) : nbtField.get(blockInfo);
            return val instanceof CompoundTag tag ? tag : null;
        } catch (Throwable e) {
            nbtField = null;
            nbtAccessor = null;
            LOG.debug("[ctb] getBlockInfoNbt threw on {}: {}", blockInfo.getClass().getName(), e.getMessage());
            return null;
        }
    }

    private ContraptionCompat() {}
}
