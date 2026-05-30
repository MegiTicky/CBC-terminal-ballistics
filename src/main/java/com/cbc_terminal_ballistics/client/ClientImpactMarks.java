package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.armor.CopycatArmorLayerBlock;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlock;
import com.cbc_terminal_ballistics.ballistics.ImpactMarkKind;
import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.state.ImpactMark;
import com.cbc_terminal_ballistics.util.VSCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientImpactMarks {
    private static final ResourceLocation PENETRATED_AUTOCANNON_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/penetrated_autocannon.png");
    private static final ResourceLocation PENETRATED_SMALL_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/penetrated_small.png");
    private static final ResourceLocation PENETRATED_MEDIUM_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/penetrated_medium.png");
    private static final ResourceLocation PENETRATED_BIG_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/penetrated_big.png");
    private static final ResourceLocation STOPPED_AUTOCANNON_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/stopped_autocannon.png");
    private static final ResourceLocation STOPPED_SMALL_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/stopped_small.png");
    private static final ResourceLocation STOPPED_MEDIUM_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/stopped_medium.png");
    private static final ResourceLocation STOPPED_BIG_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/stopped_big.png");
    private static final ResourceLocation RICOCHET_AUTOCANNON_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/ricochet_autocannon.png");
    private static final ResourceLocation RICOCHET_SMALL_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/ricochet_small.png");
    private static final ResourceLocation RICOCHET_MEDIUM_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/ricochet_medium.png");
    private static final ResourceLocation RICOCHET_BIG_TEXTURE = new ResourceLocation(CBCTerminalBallistics.MOD_ID, "textures/impact/ricochet_big.png");

    private static final Map<BlockPos, List<ImpactMark>> MARKS = new HashMap<>();

    public static void accept(BlockPos pos, List<ImpactMark> marks) {
        if (marks.isEmpty()) MARKS.remove(pos);
        else MARKS.put(pos.immutable(), List.copyOf(marks));
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            MARKS.clear();
            return;
        }

        long now = mc.level.getGameTime();
        int lifetime = overlayLifetime();
        MARKS.entrySet().removeIf(entry -> entry.getValue().stream().allMatch(mark -> now - mark.gameTime() >= lifetime));
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || MARKS.isEmpty()) return;

        long now = mc.level.getGameTime();
        int lifetime = overlayLifetime();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        for (Map.Entry<BlockPos, List<ImpactMark>> entry : MARKS.entrySet()) {
            BlockPos pos = entry.getKey();
            if (VSCompat.squaredDistanceBetweenInclShips(mc.level, Vec3.atCenterOf(pos), mc.player.position()) > 128 * 128) continue;
            BlockState state = mc.level.getBlockState(pos);
            if (!canAttach(mc.level, pos, state)) continue;
            for (ImpactMark mark : entry.getValue()) {
                long age = now - mark.gameTime();
                if (age < 0 || age >= lifetime) continue;
                renderMark(buffers, poseStack, mc.level, pos, mark, camera, age, lifetime);
            }
        }

        buffers.endBatch();
    }

    private static int overlayLifetime() {
        return Math.max(1, TBConfig.OVERLAY_LIFETIME_TICKS.get());
    }

    private static boolean canAttach(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        VoxelShape shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty();
    }

    private static void renderMark(MultiBufferSource buffers, PoseStack poseStack, Level level, BlockPos pos, ImpactMark mark, Vec3 camera, long age, int lifetime) {
        Vec3 absolute = mark.absolute(pos);
        if (!intersectsAttachment(level, pos, mark, absolute)) return;

        // 16x16 impact textures are rendered 1:1 with a Minecraft block face.
        // Build the decal on the actual collision-shape face.  Pieces that
        // cross a block-cell edge are rendered only when that neighboring cell
        // has a solid/copycat/framed block supporting the overflow.
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
        boolean transformedWithShip = level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel
            && VSClientCompat.transformRenderWithShip(clientLevel, pos, poseStack, camera);
        if (transformedWithShip) {
            // VS render transform already applies -camera, ship-to-world, and block-position translation.
            // Keep the mark in block-local shipyard coordinates so it follows ship motion/rotation.
            poseStack.translate(0.0D, 0.0D, 0.0D);
        } else {
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        }

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
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
                emitClippedFaceRect(buffer, matrix, normalMatrix, rect, normalOffset, (float) normal.x, (float) normal.y, (float) normal.z,
                    minMin[0], minMin[1], maxMin[0], maxMin[1], maxMax[0], maxMax[1], minMax[0], minMax[1], red, green, blue, alpha, light);
                continue;
            }
            uMinTex = texCoord(rect.uMin() - rect.centerU());
            uMaxTex = texCoord(rect.uMax() - rect.centerU());
            vMinTex = texCoord(rect.vMin() - rect.centerV());
            vMaxTex = texCoord(rect.vMax() - rect.centerV());
            emitClippedFaceRect(buffer, matrix, normalMatrix, rect, normalOffset, (float) normal.x, (float) normal.y, (float) normal.z,
                uMinTex, vMinTex, uMaxTex, vMaxTex, red, green, blue, alpha, light);
        }
        poseStack.popPose();
    }

    private static List<ClippedFaceRect> clippedFaceRects(Level level, BlockPos pos, ImpactMark mark) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) return List.of();
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
                if ((u.offset() == 0 && v.offset() == 0) || isSupportedOverflowNeighbor(level, pos, face, u.offset(), v.offset())) {
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

        // Only bridge to the neighboring cell if this shape reaches the block
        // cell edge; otherwise keep clipping to the partial shape's own bounds.
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
        if (state.isAir()) return false;
        if (isCopycatOrFramedBlock(state)) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, neighbor);
        return !shape.isEmpty() && Block.isShapeFullBlock(shape);
    }

    private static boolean isCopycatOrFramedBlock(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CopycatArmorLayerBlock || block instanceof FramedCollapsibleCopycatArmorBlock) {
            return true;
        }

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

    private static void emitClippedFaceRect(VertexConsumer buffer, Matrix4f matrix, Matrix3f normalMatrix, ClippedFaceRect rect, Vec3 normalOffset,
                                           float nx, float ny, float nz, float uMinTex, float vMinTex, float uMaxTex, float vMaxTex,
                                           float red, float green, float blue, float alpha, int light) {
        emitClippedFaceRect(buffer, matrix, normalMatrix, rect, normalOffset, nx, ny, nz,
            uMinTex, vMinTex, uMaxTex, vMinTex, uMaxTex, vMaxTex, uMinTex, vMaxTex, red, green, blue, alpha, light);
    }

    private static void emitClippedFaceRect(VertexConsumer buffer, Matrix4f matrix, Matrix3f normalMatrix, ClippedFaceRect rect, Vec3 normalOffset,
                                           float nx, float ny, float nz,
                                           float uMinVMinTex, float vMinVMinTex,
                                           float uMaxVMinTex, float vMaxVMinTex,
                                           float uMaxVMaxTex, float vMaxVMaxTex,
                                           float uMinVMaxTex, float vMinVMaxTex,
                                           float red, float green, float blue, float alpha, int light) {
        switch (rect.face()) {
            case DOWN -> {
                float y = rect.plane() + (float) normalOffset.y;
                vertex(buffer, matrix, normalMatrix, rect.uMin(), y, rect.vMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMax(), y, rect.vMin(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMax(), y, rect.vMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMin(), y, rect.vMax(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case UP -> {
                float y = rect.plane() + (float) normalOffset.y;
                vertex(buffer, matrix, normalMatrix, rect.uMin(), y, rect.vMax(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMax(), y, rect.vMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMax(), y, rect.vMin(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMin(), y, rect.vMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case NORTH -> {
                float z = rect.plane() + (float) normalOffset.z;
                vertex(buffer, matrix, normalMatrix, rect.uMin(), rect.vMax(), z, uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMax(), rect.vMax(), z, uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMax(), rect.vMin(), z, uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMin(), rect.vMin(), z, uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case SOUTH -> {
                float z = rect.plane() + (float) normalOffset.z;
                vertex(buffer, matrix, normalMatrix, rect.uMin(), rect.vMin(), z, uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMax(), rect.vMin(), z, uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMax(), rect.vMax(), z, uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, rect.uMin(), rect.vMax(), z, uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case WEST -> {
                float x = rect.plane() + (float) normalOffset.x;
                vertex(buffer, matrix, normalMatrix, x, rect.vMin(), rect.uMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, x, rect.vMin(), rect.uMax(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, x, rect.vMax(), rect.uMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, x, rect.vMax(), rect.uMin(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
            }
            case EAST -> {
                float x = rect.plane() + (float) normalOffset.x;
                vertex(buffer, matrix, normalMatrix, x, rect.vMax(), rect.uMin(), uMinVMaxTex, vMinVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, x, rect.vMax(), rect.uMax(), uMaxVMaxTex, vMaxVMaxTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, x, rect.vMin(), rect.uMax(), uMaxVMinTex, vMaxVMinTex, red, green, blue, alpha, light, nx, ny, nz);
                vertex(buffer, matrix, normalMatrix, x, rect.vMin(), rect.uMin(), uMinVMinTex, vMinVMinTex, red, green, blue, alpha, light, nx, ny, nz);
            }
        }
    }

    private static boolean intersectsAttachment(Level level, BlockPos pos, ImpactMark mark, Vec3 absolute) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) return false;
        AABB box = shape.bounds().move(pos);
        Vec3 p = absolute.add(Vec3.atLowerCornerOf(mark.face().getNormal()).scale(0.003D));
        return box.inflate(0.12D).contains(p);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Matrix3f normal, float x, float y, float z, float u, float v, float red, float green, float blue, float alpha, int light) {
        vertex(buffer, matrix, normal, x, y, z, u, v, red, green, blue, alpha, light, 0.0F, 1.0F, 0.0F);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Matrix3f normal, float x, float y, float z, float u, float v, float red, float green, float blue, float alpha, int light, float nx, float ny, float nz) {
        buffer.vertex(matrix, x, y, z)
            .color(red, green, blue, alpha)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(normal, nx, ny, nz)
            .endVertex();
    }

    private record ClippedFaceRect(Direction face, float centerU, float centerV, float uMin, float uMax, float vMin, float vMax, float plane) {}

    private record AxisBand(int offset, float min, float max) {}

    private static float fade(long age, int lifetime) {
        float threshold = lifetime * 0.90F;
        if (age <= threshold) return 1.0F;
        return Mth.clamp(1.0F - ((float) age - threshold) / Math.max(1.0F, lifetime - threshold), 0.0F, 1.0F);
    }

    private static long seed(BlockPos pos, ImpactMark mark) {
        long seed = pos.asLong();
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
        return switch (mark.kind()) {
            case HOLE, EXIT_HOLE -> penetratedTexture(mark.caliber());
            case PALE -> stoppedTexture(mark.caliber());
            case STREAK -> ricochetTexture(mark.caliber());
        };
    }

    private static ResourceLocation penetratedTexture(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> PENETRATED_AUTOCANNON_TEXTURE;
            case SMALL, SMALL_MEDIUM -> PENETRATED_SMALL_TEXTURE;
            case MEDIUM -> PENETRATED_MEDIUM_TEXTURE;
            case BIG -> PENETRATED_BIG_TEXTURE;
        };
    }

    private static ResourceLocation stoppedTexture(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> STOPPED_AUTOCANNON_TEXTURE;
            case SMALL, SMALL_MEDIUM -> STOPPED_SMALL_TEXTURE;
            case MEDIUM -> STOPPED_MEDIUM_TEXTURE;
            case BIG -> STOPPED_BIG_TEXTURE;
        };
    }

    private static ResourceLocation ricochetTexture(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> RICOCHET_AUTOCANNON_TEXTURE;
            case SMALL, SMALL_MEDIUM -> RICOCHET_SMALL_TEXTURE;
            case MEDIUM -> RICOCHET_MEDIUM_TEXTURE;
            case BIG -> RICOCHET_BIG_TEXTURE;
        };
    }

    private ClientImpactMarks() {}
}
