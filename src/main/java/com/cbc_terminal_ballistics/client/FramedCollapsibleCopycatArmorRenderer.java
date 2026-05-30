package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlock;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class FramedCollapsibleCopycatArmorRenderer implements BlockEntityRenderer<FramedCollapsibleCopycatArmorBlockEntity> {
    private static final int UP = Direction.UP.ordinal();
    private static final int DOWN = Direction.DOWN.ordinal();
    private static final int NORTH = Direction.NORTH.ordinal();
    private static final int EAST = Direction.EAST.ordinal();
    private static final int SOUTH = Direction.SOUTH.ordinal();
    private static final int WEST = Direction.WEST.ordinal();

    public FramedCollapsibleCopycatArmorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(FramedCollapsibleCopycatArmorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BlockState material = be.getCopiedMaterial();
        if (material.isAir() || material.getBlock() instanceof FramedCollapsibleCopycatArmorBlock) {
            return;
        }

        byte[] offsets = FramedCollapsibleCopycatArmorBlock.unpackOffsets(be.getPackedOffsets());
        Box box = new Box(
                offsets[WEST],
                offsets[DOWN],
                offsets[NORTH],
                16 - offsets[EAST],
                16 - offsets[UP],
                16 - offsets[SOUTH]
        );
        if (box.x0 >= box.x1 || box.y0 >= box.y1 || box.z0 >= box.z1) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getBlockRenderer().getBlockModel(material);
        FaceSprites sprites = FaceSprites.from(minecraft, be.getLevel(), be.getBlockPos(), material, model);
        RenderType renderType = ItemBlockRenderTypes.getRenderType(material, false);
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        for (Direction face : Direction.values()) {
            renderFace(box, face, sprites.get(face), poseStack, consumer, packedLight, packedOverlay);
        }
    }

    private static void renderFace(Box box, Direction face, FaceTexture texture, PoseStack poseStack,
                                   VertexConsumer consumer, int packedLight, int packedOverlay) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
        int color = texture.color;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float nX = face.getStepX();
        float nY = face.getStepY();
        float nZ = face.getStepZ();

        for (Vertex[] vertices : verticesForFace(box, face, texture.sprite)) {
            for (Vertex vertex : vertices) {
                consumer.vertex(matrix, vertex.x / 16.0f, vertex.y / 16.0f, vertex.z / 16.0f)
                        .color(r, g, b, 1.0f)
                        .uv(vertex.u, vertex.v)
                        .overlayCoords(packedOverlay)
                        .uv2(packedLight)
                        .normal(normalMatrix, nX, nY, nZ)
                        .endVertex();
            }
        }
    }

    private static List<Vertex[]> verticesForFace(Box b, Direction face, TextureAtlasSprite sprite) {
        /*
         * Match copycat-layer visual behavior: do not squash the whole
         * 16x16 texture onto a smaller face, and do not simply crop the texture
         * to the moved bounds.  Instead, keep edge slices from the copied block
         * and remove/compress the middle.
         */
        List<Vertex[]> quads = new ArrayList<>(4);
        switch (face) {
            case UP -> {
                for (Segment x : segments(b.x0, b.x1)) {
                    for (Segment z : segments(b.z0, b.z1)) {
                        quads.add(new Vertex[]{
                                vertex(x.d0, b.y1, z.d0, sprite, x.s0, z.s0),
                                vertex(x.d0, b.y1, z.d1, sprite, x.s0, z.s1),
                                vertex(x.d1, b.y1, z.d1, sprite, x.s1, z.s1),
                                vertex(x.d1, b.y1, z.d0, sprite, x.s1, z.s0)
                        });
                    }
                }
            }
            case DOWN -> {
                for (Segment x : segments(b.x0, b.x1)) {
                    for (Segment z : segments(b.z0, b.z1)) {
                        quads.add(new Vertex[]{
                                vertex(x.d0, b.y0, z.d1, sprite, x.s0, z.s1),
                                vertex(x.d0, b.y0, z.d0, sprite, x.s0, z.s0),
                                vertex(x.d1, b.y0, z.d0, sprite, x.s1, z.s0),
                                vertex(x.d1, b.y0, z.d1, sprite, x.s1, z.s1)
                        });
                    }
                }
            }
            case NORTH -> {
                for (Segment x : segments(b.x0, b.x1)) {
                    for (Segment y : segments(b.y0, b.y1)) {
                        quads.add(new Vertex[]{
                                vertex(x.d1, y.d1, b.z0, sprite, x.s1, 16 - y.s1),
                                vertex(x.d1, y.d0, b.z0, sprite, x.s1, 16 - y.s0),
                                vertex(x.d0, y.d0, b.z0, sprite, x.s0, 16 - y.s0),
                                vertex(x.d0, y.d1, b.z0, sprite, x.s0, 16 - y.s1)
                        });
                    }
                }
            }
            case SOUTH -> {
                for (Segment x : segments(b.x0, b.x1)) {
                    for (Segment y : segments(b.y0, b.y1)) {
                        quads.add(new Vertex[]{
                                vertex(x.d0, y.d1, b.z1, sprite, x.s0, 16 - y.s1),
                                vertex(x.d0, y.d0, b.z1, sprite, x.s0, 16 - y.s0),
                                vertex(x.d1, y.d0, b.z1, sprite, x.s1, 16 - y.s0),
                                vertex(x.d1, y.d1, b.z1, sprite, x.s1, 16 - y.s1)
                        });
                    }
                }
            }
            case WEST -> {
                for (Segment z : segments(b.z0, b.z1)) {
                    for (Segment y : segments(b.y0, b.y1)) {
                        quads.add(new Vertex[]{
                                vertex(b.x0, y.d1, z.d0, sprite, z.s0, 16 - y.s1),
                                vertex(b.x0, y.d0, z.d0, sprite, z.s0, 16 - y.s0),
                                vertex(b.x0, y.d0, z.d1, sprite, z.s1, 16 - y.s0),
                                vertex(b.x0, y.d1, z.d1, sprite, z.s1, 16 - y.s1)
                        });
                    }
                }
            }
            case EAST -> {
                for (Segment z : segments(b.z0, b.z1)) {
                    for (Segment y : segments(b.y0, b.y1)) {
                        quads.add(new Vertex[]{
                                vertex(b.x1, y.d1, z.d1, sprite, z.s1, 16 - y.s1),
                                vertex(b.x1, y.d0, z.d1, sprite, z.s1, 16 - y.s0),
                                vertex(b.x1, y.d0, z.d0, sprite, z.s0, 16 - y.s0),
                                vertex(b.x1, y.d1, z.d0, sprite, z.s0, 16 - y.s1)
                        });
                    }
                }
            }
        }
        return quads;
    }

    private static List<Segment> segments(float min, float max) {
        List<Segment> result = new ArrayList<>(2);
        float span = max - min;
        if (span <= 0.0f) {
            return result;
        }
        if (min <= 0.0f && max >= 16.0f) {
            result.add(new Segment(min, max, 0.0f, 16.0f));
            return result;
        }

        float minOffset = min;
        float maxOffset = 16.0f - max;
        float first = minOffset > maxOffset ? (float) Math.ceil(span / 2.0f) : (float) Math.floor(span / 2.0f);
        float second = span - first;

        if (first > 0.0f) {
            result.add(new Segment(min, min + first, 0.0f, first));
        }
        if (second > 0.0f) {
            result.add(new Segment(min + first, max, 16.0f - second, 16.0f));
        }
        return result;
    }

    private static Vertex vertex(float x, float y, float z, TextureAtlasSprite sprite, float u16, float v16) {
        return new Vertex(x, y, z, sprite.getU(u16), sprite.getV(v16));
    }

    private record Box(float x0, float y0, float z0, float x1, float y1, float z1) {
    }

    private record Vertex(float x, float y, float z, float u, float v) {
    }

    private record Segment(float d0, float d1, float s0, float s1) {
    }

    private record FaceTexture(TextureAtlasSprite sprite, int color) {
    }

    private static class FaceSprites {
        private final Map<Direction, FaceTexture> textures = new EnumMap<>(Direction.class);

        private FaceTexture get(Direction direction) {
            return textures.get(direction);
        }

        private static FaceSprites from(Minecraft minecraft, BlockAndTintGetter level, BlockPos pos,
                                        BlockState material, BakedModel model) {
            FaceSprites result = new FaceSprites();
            FaceTexture fallback = textureFromQuadList(minecraft, level, pos, material,
                    model.getQuads(material, null, RandomSource.create(42)), model);
            for (Direction direction : Direction.values()) {
                List<BakedQuad> quads = model.getQuads(material, direction, RandomSource.create(42));
                FaceTexture texture = textureFromQuadList(minecraft, level, pos, material, quads, model);
                result.textures.put(direction, texture != null ? texture : fallback);
            }
            return result;
        }

        private static FaceTexture textureFromQuadList(Minecraft minecraft, BlockAndTintGetter level, BlockPos pos,
                                                       BlockState material, List<BakedQuad> quads, BakedModel model) {
            TextureAtlasSprite sprite = null;
            int color = 0xFFFFFF;
            if (!quads.isEmpty()) {
                BakedQuad quad = quads.get(0);
                sprite = quad.getSprite();
                if (quad.isTinted()) {
                    BlockColors blockColors = minecraft.getBlockColors();
                    color = blockColors.getColor(material, level, pos, quad.getTintIndex());
                    if (color == -1) {
                        color = 0xFFFFFF;
                    }
                }
            }
            if (sprite == null) {
                sprite = model.getParticleIcon();
            }
            return new FaceTexture(sprite, color);
        }
    }
}
