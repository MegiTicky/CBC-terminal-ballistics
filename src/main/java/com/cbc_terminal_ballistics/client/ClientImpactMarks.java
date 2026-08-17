package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.ballistics.ImpactMarkKind;
import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.ballistics.ImpactSurfaceType;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.state.ImpactMark;
import com.cbc_terminal_ballistics.util.SableCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@EventBusSubscriber(modid = "cbc_terminal_ballistics", value = Dist.CLIENT)
public final class ClientImpactMarks {
    private static final ResourceLocation PENETRATED_AUTOCANNON_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/penetrated_autocannon.png");
    private static final ResourceLocation PENETRATED_SMALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/penetrated_small.png");
    private static final ResourceLocation PENETRATED_MEDIUM_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/penetrated_medium.png");
    private static final ResourceLocation PENETRATED_BIG_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/penetrated_big.png");
    private static final ResourceLocation STOPPED_AUTOCANNON_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/stopped_autocannon.png");
    private static final ResourceLocation STOPPED_SMALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/stopped_small.png");
    private static final ResourceLocation STOPPED_MEDIUM_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/stopped_medium.png");
    private static final ResourceLocation STOPPED_BIG_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/stopped_big.png");
    private static final ResourceLocation RICOCHET_AUTOCANNON_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/ricochet_autocannon.png");
    private static final ResourceLocation RICOCHET_SMALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/ricochet_small.png");
    private static final ResourceLocation RICOCHET_MEDIUM_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/ricochet_medium.png");
    private static final ResourceLocation RICOCHET_BIG_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/ricochet_big.png");
    private static final ResourceLocation GENERAL_PENETRATED_AUTOCANNON_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/penetrated_autocannon.png");
    private static final ResourceLocation GENERAL_PENETRATED_SMALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/penetrated_small.png");
    private static final ResourceLocation GENERAL_PENETRATED_MEDIUM_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/penetrated_medium.png");
    private static final ResourceLocation GENERAL_PENETRATED_BIG_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/penetrated_big.png");
    private static final ResourceLocation GENERAL_STOPPED_AUTOCANNON_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/stopped_autocannon.png");
    private static final ResourceLocation GENERAL_STOPPED_SMALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/stopped_small.png");
    private static final ResourceLocation GENERAL_STOPPED_MEDIUM_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/stopped_medium.png");
    private static final ResourceLocation GENERAL_STOPPED_BIG_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/stopped_big.png");
    private static final ResourceLocation GENERAL_RICOCHET_AUTOCANNON_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/ricochet_autocannon.png");
    private static final ResourceLocation GENERAL_RICOCHET_SMALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/ricochet_small.png");
    private static final ResourceLocation GENERAL_RICOCHET_MEDIUM_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/ricochet_medium.png");
    private static final ResourceLocation GENERAL_RICOCHET_BIG_TEXTURE = ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "textures/impact/general/ricochet_big.png");

    private static final Map<BlockPos, MarkSet> MARKS = new HashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_terminal_ballistics:ClientImpactMarks");
    private static int markRenderCounter = 0;
    private static int markAcceptCounter = 0;

    public static void accept(BlockPos pos, UUID subLevelId, List<ImpactMark> marks) {
        if (marks.isEmpty()) MARKS.remove(pos);
        else {
            MARKS.put(pos.immutable(), new MarkSet(subLevelId, List.copyOf(marks)));
            if (markAcceptCounter < 10) {
                LOGGER.warn("Received {} marks at {} (Sable: subLevel={})", marks.size(), pos.toShortString(), SableCompat.isInSubLevel(Minecraft.getInstance().level, pos));
                markAcceptCounter++;
            }
        }
    }

    static Vec3 debugAnchorNear(BlockPos pos, Vec3 reference) {
        MarkSet markSet = MARKS.get(pos);
        if (markSet == null || markSet.marks().isEmpty()) return null;
        ImpactMark nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (ImpactMark mark : markSet.marks()) {
            Vec3 anchor = mark.absolute(pos);
            double distance = anchor.distanceToSqr(reference);
            if (distance < nearestDistance) {
                nearest = mark;
                nearestDistance = distance;
            }
        }
        return nearest == null ? null : nearest.absolute(pos);
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            MARKS.clear();
            markAcceptCounter = 0;
            markRenderCounter = 0;
            return;
        }

        long now = mc.level.getGameTime();
        int lifetime = overlayLifetime();
        MARKS.entrySet().removeIf(entry -> {
            MarkSet markSet = entry.getValue();
            UUID currentSubLevelId = SableCompat.subLevelId(mc.level, entry.getKey());
            return currentSubLevelId != null && !Objects.equals(markSet.subLevelId(), currentSubLevelId)
                    || markSet.marks().stream().allMatch(mark -> now - mark.gameTime() >= lifetime);
        });

        // --- PERSISTENT IMPACT SPARK LOGIC ---
        for (Map.Entry<BlockPos, MarkSet> entry : MARKS.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!hasCurrentOwner(mc.level, pos, entry.getValue())) continue;
            for (ImpactMark mark : entry.getValue().marks()) {
                long age = now - mark.gameTime();

                // The server enables sparks only for armor with toughness >= 15
                // that survived the impact. Entry holes, stopped dents and
                // ricochet streaks all use the upstream FLAME + SMOKE effect;
                // penetration exit holes never create a second spark source.
                if (age >= 0 && age < 60 && mark.sparks() && mark.kind() != ImpactMarkKind.EXIT_HOLE) {

                    // 1. Determine a scale multiplier based on the caliber
                    float scale = switch (mark.caliber()) {
                        case AUTOCANNON -> 0.4f;
                        case HEAVY_AUTOCANNON, SMALL, SMALL_MEDIUM -> 1.0f;
                        case MEDIUM -> 1.5f;
                        case BIG -> 2.5f;
                    };

                    // 2. Scale the spawn chance (Big = thicker fire, Autocannon = sparse flickering)
                    if (mc.level.random.nextFloat() < 0.3f * scale) {
                        Vec3 localNormal = Vec3.atLowerCornerOf(mark.face().getNormal());

                        // 3. Add random spread (jitter) across the face of the hole based on the scale
                        double jitterA = (mc.level.random.nextDouble() - 0.5) * 0.25 * scale;
                        double jitterB = (mc.level.random.nextDouble() - 0.5) * 0.25 * scale;
                        Vec3 localJitter = switch (mark.face().getAxis()) {
                            case X -> new Vec3(0.0D, jitterA, jitterB);
                            case Y -> new Vec3(jitterA, 0.0D, jitterB);
                            case Z -> new Vec3(jitterA, jitterB, 0.0D);
                        };

                        // Apply the normal pushout AND the spread jitter
                        Vec3 localPos = mark.absolute(pos)
                                .add(localNormal.scale(0.05))
                                .add(localJitter);

                        // Use Sable's logical pose for both particle position and
                        // direction. CBC uses this same pose for physical hits, so
                        // the emitted spark follows the exact surface that was hit.
                        Vec3 worldPos = localPos;
                        Vec3 worldNormal = localNormal;
                        if (isSableSubLevelMark(mc.level, pos)) {
                            worldPos = SableCompat.toWorldCoordinates(mc.level, localPos);
                            worldNormal = SableCompat.toWorldVector(mc.level, pos, localNormal).normalize();
                        }

                        // 4. Emit strictly along the transformed surface normal.
                        // Adding a fixed world-Y velocity would skew the direction
                        // whenever a Sable structure is pitched or rolled.
                        Vec3 flameVelocity = worldNormal.scale(0.03D * scale);
                        mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.FLAME,
                                worldPos.x, worldPos.y, worldPos.z,
                                flameVelocity.x, flameVelocity.y, flameVelocity.z);

                        // Also scale the smoke!
                        if (mc.level.random.nextFloat() < 0.5f) {
                            Vec3 smokeVelocity = worldNormal.scale(0.015D * scale);
                            mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                                    worldPos.x, worldPos.y, worldPos.z,
                                    smokeVelocity.x, smokeVelocity.y, smokeVelocity.z);
                        }
                    }
                }
            }
        }
        // ------------------------------------
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || MARKS.isEmpty()) return;

        long now = mc.level.getGameTime();
        int lifetime = overlayLifetime();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        if (markRenderCounter < 15) {
            LOGGER.warn("renderLevel: MARKS.size={} camera=({}, {}, {}) player=({}, {}, {})",
                MARKS.size(),
                String.format("%.1f", camera.x), String.format("%.1f", camera.y), String.format("%.1f", camera.z),
                String.format("%.1f", mc.player.position().x), String.format("%.1f", mc.player.position().y), String.format("%.1f", mc.player.position().z));
            markRenderCounter++;
        }

        for (Map.Entry<BlockPos, MarkSet> entry : MARKS.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!hasCurrentOwner(mc.level, pos, entry.getValue())) continue;
            boolean sableSubLevelMark = isSableSubLevelMark(mc.level, pos);
            if (!sableSubLevelMark
                && SableCompat.squaredDistanceBetweenInclSubLevels(mc.level, Vec3.atCenterOf(pos), mc.player.position()) > 128 * 128) {
                if (markRenderCounter < 20) {
                    LOGGER.warn("renderLevel: SKIP distance pos={}", pos.toShortString());
                    markRenderCounter++;
                }
                continue;
            }
            BlockState state = mc.level.getBlockState(pos);
            if (!canAttach(mc.level, pos, state)) {
                if (markRenderCounter < 20) {
                    LOGGER.warn("renderLevel: SKIP canAttach pos={} state={}", pos.toShortString(), state);
                    markRenderCounter++;
                }
                continue;
            }
            for (ImpactMark mark : entry.getValue().marks()) {
                long age = now - mark.gameTime();
                if (age < 0 || age >= lifetime) continue;
                if (markRenderCounter < 20) {
                    LOGGER.warn("renderLevel: RENDERING mark kind={} caliber={} pos={}", mark.kind(), mark.caliber(), pos.toShortString());
                    markRenderCounter++;
                }
                renderMark(buffers, poseStack, mc.level, pos, mark, camera, age, lifetime);
            }
        }

        buffers.endBatch();
    }

    private static int overlayLifetime() {
        try {
            return Math.max(1, TBConfig.OVERLAY_LIFETIME_TICKS.get());
        } catch (IllegalStateException ignored) {
            // Client rendering can start before the server config is synchronized.
            return 20 * 60 * 15;
        }
    }

    private static boolean isSableSubLevelMark(Level level, BlockPos pos) {
        return SableCompat.isPresent()
            && (SableCompat.isProbablyInSubLevel(pos) || SableCompat.isInSubLevel(level, pos));
    }

    private static boolean hasCurrentOwner(Level level, BlockPos pos, MarkSet markSet) {
        UUID expected = markSet.subLevelId();
        UUID current = SableCompat.subLevelId(level, pos);
        if (expected != null) return expected.equals(current);
        return current == null && !SableCompat.isProbablyInSubLevel(pos);
    }

    private record MarkSet(UUID subLevelId, List<ImpactMark> marks) {
    }

    private static boolean canAttach(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            // Sable sub-level blocks appear as air in the main world.
            // If Sable is loaded, trust the server-validated MARKS positions.
            // Otherwise, try the Companion API reflection as a fallback.
            return SableCompat.isPresent() || SableCompat.isInSubLevel(level, pos);
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty();
    }

    private static void renderMark(MultiBufferSource buffers, PoseStack poseStack, Level level, BlockPos pos, ImpactMark mark, Vec3 camera, long age, int lifetime) {
        Vec3 absolute = mark.absolute(pos);
        if (!intersectsAttachment(level, pos, mark, absolute)) return;

        // 16x16 impact textures are rendered 1:1 with a Minecraft block face.
        // Overflow is allowed only when an adjacent cell provides a supporting face.
        List<ClippedFaceRect> rects = clippedFaceRects(level, pos, mark);
        if (rects.isEmpty()) return;

        float alpha = switch (mark.kind()) {
            case HOLE, EXIT_HOLE -> 0.95F;
            case STREAK -> 0.90F;
            case PALE -> 0.88F;
        } * fade(age, lifetime);
        float red = 1.0F;
        float green = 1.0F;
        float blue = 1.0F;

        int light = LightTexture.FULL_BRIGHT;

        Direction face = mark.face();
        Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
        Vec3 normalOffset = normal.scale(0.003D);
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucent(texture(mark)));

        poseStack.pushPose();
        // For Sable sub-level blocks, use the sub-level render transform.
        // isInSubLevel() uses coordinate heuristic (>30M) as fast pass, then
        // falls back to the Companion API for blocks in the 10M-30M range
        // that Sable typically uses for sub-level plot grids.
        boolean isSableSub = isSableSubLevelMark(level, pos);
        SableClientCompat.RenderTransform sableTransform = isSableSub && level instanceof ClientLevel clientLevel
            ? SableClientCompat.renderTransformWithSubLevel(clientLevel, pos, camera)
            : null;
        if (sableTransform == null) {
            if (isSableSub && markRenderCounter < 20) {
                LOGGER.warn("Sable sub-level mark at {} — transformRenderWithSubLevel failed, using fallback pos-camera", pos.toShortString());
                markRenderCounter++;
            }
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        } else if (markRenderCounter < 20) {
            LOGGER.warn("Sable sub-level mark at {} — renderPose transform succeeded", pos.toShortString());
            markRenderCounter++;
        }

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        for (ClippedFaceRect rect : rects) {
            boolean rotateUv = mark.kind() == ImpactMarkKind.STREAK;
            float uMinTex;
            float uMaxTex;
            float vMinTex;
            float vMaxTex;
            if (rotateUv) {
                float[] minMin = texCoords(rect.uMin() - rect.centerU(), rect.vMin() - rect.centerV(), mark.rotation());
                float[] maxMin = texCoords(rect.uMax() - rect.centerU(), rect.vMin() - rect.centerV(), mark.rotation());
                float[] maxMax = texCoords(rect.uMax() - rect.centerU(), rect.vMax() - rect.centerV(), mark.rotation());
                float[] minMax = texCoords(rect.uMin() - rect.centerU(), rect.vMax() - rect.centerV(), mark.rotation());
                if (sableTransform != null) {
                    emitTransformedClippedFaceRect(buffer, matrix, pose, sableTransform, pos, rect, normalOffset, (float) normal.x, (float) normal.y, (float) normal.z,
                        minMin[0], minMin[1], maxMin[0], maxMin[1], maxMax[0], maxMax[1], minMax[0], minMax[1], red, green, blue, alpha, light);
                } else {
                    emitClippedFaceRect(buffer, matrix, pose, rect, normalOffset, (float) normal.x, (float) normal.y, (float) normal.z,
                        minMin[0], minMin[1], maxMin[0], maxMin[1], maxMax[0], maxMax[1], minMax[0], minMax[1], red, green, blue, alpha, light);
                }
                continue;
            }
            uMinTex = texCoord(rect.uMin() - rect.centerU());
            uMaxTex = texCoord(rect.uMax() - rect.centerU());
            vMinTex = texCoord(rect.vMin() - rect.centerV());
            vMaxTex = texCoord(rect.vMax() - rect.centerV());
            if (sableTransform != null) {
                emitTransformedClippedFaceRect(buffer, matrix, pose, sableTransform, pos, rect, normalOffset, (float) normal.x, (float) normal.y, (float) normal.z,
                    uMinTex, vMinTex, uMaxTex, vMaxTex, red, green, blue, alpha, light);
            } else {
                emitClippedFaceRect(buffer, matrix, pose, rect, normalOffset, (float) normal.x, (float) normal.y, (float) normal.z,
                    uMinTex, vMinTex, uMaxTex, vMaxTex, red, green, blue, alpha, light);
            }
        }
        poseStack.popPose();
    }

    private static List<ClippedFaceRect> clippedFaceRects(Level level, BlockPos pos, ImpactMark mark) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        boolean sableFallbackShape = shape.isEmpty();
        if (sableFallbackShape) {
            // Sable sub-level blocks have no collision shape in the main world.
            // If Sable is loaded, use a default full-cube shape.
            if (SableCompat.isPresent() || SableCompat.isInSubLevel(level, pos)) {
                shape = net.minecraft.world.phys.shapes.Shapes.block();
            } else {
                return List.of();
            }
        }
        AABB bounds = shape.bounds();

        float centerU;
        float centerV;
        float baseUMin;
        float baseUMax;
        float baseVMin;
        float baseVMax;
        float plane;
        Direction face = mark.face();
        switch (face.getAxis()) {
            case X -> {
                centerU = mark.z();
                centerV = mark.y();
                baseUMin = (float) bounds.minZ;
                baseUMax = (float) bounds.maxZ;
                baseVMin = (float) bounds.minY;
                baseVMax = (float) bounds.maxY;
                plane = face == Direction.EAST ? (float) bounds.maxX : (float) bounds.minX;
            }
            case Y -> {
                centerU = mark.x();
                centerV = mark.z();
                baseUMin = (float) bounds.minX;
                baseUMax = (float) bounds.maxX;
                baseVMin = (float) bounds.minZ;
                baseVMax = (float) bounds.maxZ;
                plane = face == Direction.UP ? (float) bounds.maxY : (float) bounds.minY;
            }
            case Z -> {
                centerU = mark.x();
                centerV = mark.y();
                baseUMin = (float) bounds.minX;
                baseUMax = (float) bounds.maxX;
                baseVMin = (float) bounds.minY;
                baseVMax = (float) bounds.maxY;
                plane = face == Direction.SOUTH ? (float) bounds.maxZ : (float) bounds.minZ;
            }
            default -> throw new IncompatibleClassChangeError();
        }

        float desiredUMin = centerU - 0.5F;
        float desiredUMax = centerU + 0.5F;
        float desiredVMin = centerV - 0.5F;
        float desiredVMax = centerV + 0.5F;
        List<AxisBand> uBands = axisBands(baseUMin, baseUMax, desiredUMin, desiredUMax);
        List<AxisBand> vBands = axisBands(baseVMin, baseVMax, desiredVMin, desiredVMax);
        List<ClippedFaceRect> rects = new ArrayList<>(uBands.size() * vBands.size());
        for (AxisBand u : uBands) {
            for (AxisBand v : vBands) {
                if ((u.offset() == 0 && v.offset() == 0)
                    || (!sableFallbackShape && isSupportedOverflowNeighbor(level, pos, face, u.offset(), v.offset()))) {
                    rects.add(new ClippedFaceRect(face, centerU, centerV, u.min(), u.max(), v.min(), v.max(), plane));
                }
            }
        }
        return rects;
    }

    private static List<AxisBand> axisBands(float baseMin, float baseMax, float desiredMin, float desiredMax) {
        final float epsilon = 1.0E-4F;
        List<AxisBand> bands = new ArrayList<>(3);

        float localMin = Math.max(desiredMin, baseMin);
        float localMax = Math.min(desiredMax, baseMax);
        addBand(bands, 0, localMin, localMax);

        // Bridge to a neighboring cell only when this shape reaches the cell edge.
        if (desiredMin < 0.0F && baseMin <= epsilon) {
            addBand(bands, -1, desiredMin, Math.min(0.0F, desiredMax));
        }
        if (desiredMax > 1.0F && baseMax >= 1.0F - epsilon) {
            addBand(bands, 1, Math.max(1.0F, desiredMin), desiredMax);
        }

        return bands;
    }

    private static void addBand(List<AxisBand> bands, int offset, float min, float max) {
        if (max > min) {
            bands.add(new AxisBand(offset, min, max));
        }
    }

    private static boolean isSupportedOverflowNeighbor(Level level, BlockPos pos, Direction face, int du, int dv) {
        BlockPos neighbor = pos.offset(neighborDx(face, du, dv), neighborDy(face, du, dv), neighborDz(face, du, dv));
        BlockState state = level.getBlockState(neighbor);
        if (state.isAir()) {
            return false;
        }
        if (isCopycatOrFramedBlock(state)) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, neighbor);
        return !shape.isEmpty() && Block.isShapeFullBlock(shape);
    }

    private static boolean isCopycatOrFramedBlock(BlockState state) {
        Block block = state.getBlock();
        // Armor copycat blocks disabled pending Copycats+ API update

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id != null) {
            String namespace = id.getNamespace().toLowerCase(Locale.ROOT);
            String path = id.getPath().toLowerCase(Locale.ROOT);
            if (namespace.contains("copycat") || namespace.contains("framed") || path.contains("copycat") || path.contains("framed")) {
                return true;
            }
        }

        String className = block.getClass().getName().toLowerCase(Locale.ROOT);
        return className.contains("copycat") || className.contains("framed");
    }

    private static int neighborDx(Direction face, int du, int dv) {
        return switch (face.getAxis()) {
            case X -> 0;
            case Z -> du;
            case Y -> du;
        };
    }

    private static int neighborDy(Direction face, int du, int dv) {
        return switch (face.getAxis()) {
            case X, Z -> dv;
            case Y -> 0;
        };
    }

    private static int neighborDz(Direction face, int du, int dv) {
        return switch (face.getAxis()) {
            case X -> du;
            case Y -> dv;
            case Z -> 0;
        };
    }

    private static float texCoord(float offsetFromCenter) {
        return 0.5F - offsetFromCenter;
    }

    private static float[] texCoords(float du, float dv, float rotation) {
        float sin = Mth.sin(rotation);
        float cos = Mth.cos(rotation);
        // Texture +V/"up" follows the stored incoming-path direction in face-local UV space.
        float textureUOffset = du * cos - dv * sin;
        float textureVOffset = du * sin + dv * cos;
        // Rotating UVs on an axis-aligned clipped decal can push corner UVs outside this sprite.
        // Clamp to the transparent texture border instead of sampling neighboring atlas sprites,
        // which otherwise looks like small duplicate ricochet marks above/below the real mark.
        return new float[] {
            Mth.clamp(0.5F - textureUOffset, 0.001F, 0.999F),
            Mth.clamp(0.5F - textureVOffset, 0.001F, 0.999F)
        };
    }

    private static void emitClippedFaceRect(VertexConsumer buffer, Matrix4f matrix, PoseStack.Pose pose, ClippedFaceRect rect, Vec3 normalOffset,
                                           float nx, float ny, float nz, float uMinTex, float vMinTex, float uMaxTex, float vMaxTex,
                                           float red, float green, float blue, float alpha, int light) {
        emitClippedFaceRect(buffer, matrix, pose, rect, normalOffset, nx, ny, nz,
            uMinTex, vMinTex, uMaxTex, vMinTex, uMaxTex, vMaxTex, uMinTex, vMaxTex, red, green, blue, alpha, light);
    }

    private static void emitClippedFaceRect(VertexConsumer buffer, Matrix4f matrix, PoseStack.Pose pose, ClippedFaceRect rect, Vec3 normalOffset,
                                           float nx, float ny, float nz,
                                           float uMinVMinTex, float vMinVMinTex,
                                           float uMaxVMinTex, float vMaxVMinTex,
                                           float uMaxVMaxTex, float vMaxVMaxTex,
                                           float uMinVMaxTex, float vMinVMaxTex,
                                           float red, float green, float blue, float alpha, int light) {
        switch (rect.face()) {
            case DOWN -> {
                float y = rect.plane() + (float) normalOffset.y;
                vertex(buffer, matrix, pose, rect.uMin(), y, rect.vMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMax(), y, rect.vMin(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMax(), y, rect.vMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMin(), y, rect.vMax(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case UP -> {
                float y = rect.plane() + (float) normalOffset.y;
                vertex(buffer, matrix, pose, rect.uMin(), y, rect.vMax(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMax(), y, rect.vMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMax(), y, rect.vMin(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMin(), y, rect.vMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case NORTH -> {
                float z = rect.plane() + (float) normalOffset.z;
                vertex(buffer, matrix, pose, rect.uMin(), rect.vMax(), z, uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMax(), rect.vMax(), z, uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMax(), rect.vMin(), z, uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMin(), rect.vMin(), z, uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case SOUTH -> {
                float z = rect.plane() + (float) normalOffset.z;
                vertex(buffer, matrix, pose, rect.uMin(), rect.vMin(), z, uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMax(), rect.vMin(), z, uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMax(), rect.vMax(), z, uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, rect.uMin(), rect.vMax(), z, uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case WEST -> {
                float x = rect.plane() + (float) normalOffset.x;
                vertex(buffer, matrix, pose, x, rect.vMin(), rect.uMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, x, rect.vMin(), rect.uMax(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, x, rect.vMax(), rect.uMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, x, rect.vMax(), rect.uMin(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case EAST -> {
                float x = rect.plane() + (float) normalOffset.x;
                vertex(buffer, matrix, pose, x, rect.vMax(), rect.uMin(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, x, rect.vMax(), rect.uMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, x, rect.vMin(), rect.uMax(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, pose, x, rect.vMin(), rect.uMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
            }
        }
    }

    private static void emitTransformedClippedFaceRect(VertexConsumer buffer, Matrix4f matrix, PoseStack.Pose pose,
                                                       SableClientCompat.RenderTransform transform, BlockPos pos,
                                                       ClippedFaceRect rect, Vec3 normalOffset,
                                                       float nx, float ny, float nz, float uMinTex, float vMinTex,
                                                       float uMaxTex, float vMaxTex,
                                                       float red, float green, float blue, float alpha, int light) {
        emitTransformedClippedFaceRect(buffer, matrix, pose, transform, pos, rect, normalOffset, nx, ny, nz,
            uMinTex, vMinTex, uMaxTex, vMinTex, uMaxTex, vMaxTex, uMinTex, vMaxTex, red, green, blue, alpha, light);
    }

    private static void emitTransformedClippedFaceRect(VertexConsumer buffer, Matrix4f matrix, PoseStack.Pose pose,
                                                       SableClientCompat.RenderTransform transform, BlockPos pos,
                                                       ClippedFaceRect rect, Vec3 normalOffset,
                                                       float nx, float ny, float nz,
                                                       float uMinVMinTex, float vMinVMinTex,
                                                       float uMaxVMinTex, float vMaxVMinTex,
                                                       float uMaxVMaxTex, float vMaxVMaxTex,
                                                       float uMinVMaxTex, float vMinVMaxTex,
                                                       float red, float green, float blue, float alpha, int light) {
        switch (rect.face()) {
            case DOWN -> {
                float y = rect.plane() + (float) normalOffset.y;
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMin(), y, rect.vMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMax(), y, rect.vMin(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMax(), y, rect.vMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMin(), y, rect.vMax(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case UP -> {
                float y = rect.plane() + (float) normalOffset.y;
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMin(), y, rect.vMax(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMax(), y, rect.vMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMax(), y, rect.vMin(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMin(), y, rect.vMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case NORTH -> {
                float z = rect.plane() + (float) normalOffset.z;
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMin(), rect.vMax(), z, uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMax(), rect.vMax(), z, uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMax(), rect.vMin(), z, uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMin(), rect.vMin(), z, uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case SOUTH -> {
                float z = rect.plane() + (float) normalOffset.z;
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMin(), rect.vMin(), z, uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMax(), rect.vMin(), z, uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMax(), rect.vMax(), z, uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, rect.uMin(), rect.vMax(), z, uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case WEST -> {
                float x = rect.plane() + (float) normalOffset.x;
                vertexTransformed(buffer, matrix, pose, transform, pos, x, rect.vMin(), rect.uMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, x, rect.vMin(), rect.uMax(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, x, rect.vMax(), rect.uMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, x, rect.vMax(), rect.uMin(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case EAST -> {
                float x = rect.plane() + (float) normalOffset.x;
                vertexTransformed(buffer, matrix, pose, transform, pos, x, rect.vMax(), rect.uMin(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, x, rect.vMax(), rect.uMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, x, rect.vMin(), rect.uMax(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertexTransformed(buffer, matrix, pose, transform, pos, x, rect.vMin(), rect.uMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
            }
        }
    }

    private static boolean intersectsAttachment(Level level, BlockPos pos, ImpactMark mark, Vec3 absolute) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            // Sable sub-level blocks have no collision shape in the main world.
            // If Sable is loaded, trust the server-validated mark.
            return SableCompat.isPresent() || SableCompat.isInSubLevel(level, pos);
        }
        AABB box = shape.bounds().move(pos);
        Vec3 p = absolute.add(Vec3.atLowerCornerOf(mark.face().getNormal()).scale(0.003D));
        return box.inflate(0.12D).contains(p);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, PoseStack.Pose pose, float x, float y, float z, float u, float v, float red, float green, float blue, float alpha, int light) {
        vertex(buffer, matrix, pose, x, y, z, u, v, red, green, blue, alpha, light, 0.0F, 1.0F, 0.0F);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, PoseStack.Pose pose, float x, float y, float z, float u, float v, float red, float green, float blue, float alpha, int light, float nx, float ny, float nz) {
        buffer.addVertex(matrix, x, y, z)
            .setColor(red, green, blue, alpha)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, nx, ny, nz);
    }

    private static void vertexTransformed(VertexConsumer buffer, Matrix4f matrix, PoseStack.Pose pose,
                                          SableClientCompat.RenderTransform transform, BlockPos pos,
                                          float x, float y, float z, float u, float v,
                                          float red, float green, float blue, float alpha, int light,
                                          float nx, float ny, float nz) {
        Vec3 renderPos = transform.position(new Vec3(pos.getX() + (double) x, pos.getY() + (double) y, pos.getZ() + (double) z));
        Vec3 renderNormal = transform.normal(new Vec3(nx, ny, nz));
        buffer.addVertex(matrix, (float) renderPos.x, (float) renderPos.y, (float) renderPos.z)
            .setColor(red, green, blue, alpha)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, (float) renderNormal.x, (float) renderNormal.y, (float) renderNormal.z);
    }

    private record ClippedFaceRect(Direction face, float centerU, float centerV, float uMin, float uMax, float vMin, float vMax, float plane) {}

    private record AxisBand(int offset, float min, float max) {}

    private static float fade(long age, int lifetime) {
        float threshold = lifetime * 0.90F;
        if (age <= threshold) return 1.0F;
        return Mth.clamp(1.0F - ((float) age - threshold) / Math.max(1.0F, lifetime - threshold), 0.0F, 1.0F);
    }

    private static long seed(BlockPos pos, ImpactMark mark) {
        long seed = ((long) pos.getX() * 31L + (long) pos.getY()) * 31L + (long) pos.getZ();
        seed = seed * 31L + mark.kind().ordinal();
        seed = seed * 31L + mark.face().ordinal();
        seed = seed * 31L + Float.floatToIntBits(mark.x());
        seed = seed * 31L + Float.floatToIntBits(mark.y());
        seed = seed * 31L + Float.floatToIntBits(mark.z());
        seed = seed * 31L + mark.gameTime();
        seed ^= seed >>> 33;
        seed *= 0xff51afd7ed558ccdL;
        seed ^= seed >>> 33;
        return seed;
    }

    private static ResourceLocation texture(ImpactMark mark) {
        if (mark.surface() == ImpactSurfaceType.GENERAL) {
            return switch (mark.kind()) {
                case HOLE, EXIT_HOLE -> generalPenetratedTexture(mark.caliber());
                case PALE -> generalStoppedTexture(mark.caliber());
                case STREAK -> generalRicochetTexture(mark.caliber());
            };
        }
        return switch (mark.kind()) {
            case HOLE, EXIT_HOLE -> penetratedTexture(mark.caliber());
            case PALE -> stoppedTexture(mark.caliber());
            case STREAK -> ricochetTexture(mark.caliber());
        };
    }

    private static ResourceLocation penetratedTexture(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> PENETRATED_AUTOCANNON_TEXTURE;
            case HEAVY_AUTOCANNON, SMALL, SMALL_MEDIUM -> PENETRATED_SMALL_TEXTURE;
            case MEDIUM -> PENETRATED_MEDIUM_TEXTURE;
            case BIG -> PENETRATED_BIG_TEXTURE;
        };
    }

    private static ResourceLocation stoppedTexture(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> STOPPED_AUTOCANNON_TEXTURE;
            case HEAVY_AUTOCANNON, SMALL, SMALL_MEDIUM -> STOPPED_SMALL_TEXTURE;
            case MEDIUM -> STOPPED_MEDIUM_TEXTURE;
            case BIG -> STOPPED_BIG_TEXTURE;
        };
    }

    private static ResourceLocation ricochetTexture(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> RICOCHET_AUTOCANNON_TEXTURE;
            case HEAVY_AUTOCANNON, SMALL, SMALL_MEDIUM -> RICOCHET_SMALL_TEXTURE;
            case MEDIUM -> RICOCHET_MEDIUM_TEXTURE;
            case BIG -> RICOCHET_BIG_TEXTURE;
        };
    }

    private static ResourceLocation generalPenetratedTexture(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> GENERAL_PENETRATED_AUTOCANNON_TEXTURE;
            case HEAVY_AUTOCANNON, SMALL, SMALL_MEDIUM -> GENERAL_PENETRATED_SMALL_TEXTURE;
            case MEDIUM -> GENERAL_PENETRATED_MEDIUM_TEXTURE;
            case BIG -> GENERAL_PENETRATED_BIG_TEXTURE;
        };
    }

    private static ResourceLocation generalStoppedTexture(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> GENERAL_STOPPED_AUTOCANNON_TEXTURE;
            case HEAVY_AUTOCANNON, SMALL, SMALL_MEDIUM -> GENERAL_STOPPED_SMALL_TEXTURE;
            case MEDIUM -> GENERAL_STOPPED_MEDIUM_TEXTURE;
            case BIG -> GENERAL_STOPPED_BIG_TEXTURE;
        };
    }

    private static ResourceLocation generalRicochetTexture(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> GENERAL_RICOCHET_AUTOCANNON_TEXTURE;
            case HEAVY_AUTOCANNON, SMALL, SMALL_MEDIUM -> GENERAL_RICOCHET_SMALL_TEXTURE;
            case MEDIUM -> GENERAL_RICOCHET_MEDIUM_TEXTURE;
            case BIG -> GENERAL_RICOCHET_BIG_TEXTURE;
        };
    }

    private ClientImpactMarks() {}
}
