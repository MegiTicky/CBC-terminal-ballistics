package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FramedCollapsibleCopycatArmorBlock extends Block implements EntityBlock, SimpleWaterloggedBlock {
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private static final int UP = Direction.UP.ordinal();
    private static final int DOWN = Direction.DOWN.ordinal();
    private static final int NORTH = Direction.NORTH.ordinal();
    private static final int EAST = Direction.EAST.ordinal();
    private static final int SOUTH = Direction.SOUTH.ordinal();
    private static final int WEST = Direction.WEST.ordinal();
    private static final Map<Integer, VoxelShape> SHAPE_CACHE = new ConcurrentHashMap<>();

    public FramedCollapsibleCopycatArmorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WATERLOGGED, false));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return defaultBlockState().setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (isCreateWrench(stack)) {
            return removeCopiedMaterial(level, pos, player);
        }

        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return InteractionResult.PASS;
        }
        if (stack.is(asItem()) || stack.is(ModItems.ARMOR_UPGRADER.get())) {
            return InteractionResult.PASS;
        }

        BlockState material = blockItem.getBlock().defaultBlockState();
        if (!isAcceptedMaterial(level, pos, material)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof FramedCollapsibleCopycatArmorBlockEntity armor)) {
            return InteractionResult.PASS;
        }
        armor.setCopiedMaterial(material);
        level.playSound(null, pos, material.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 0.75f);
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    private InteractionResult removeCopiedMaterial(Level level, BlockPos pos, Player player) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor && armor.hasCopiedMaterial()) {
            ItemStack removed = armor.removeCopiedMaterial();
            if (!removed.isEmpty() && !player.isCreative()) {
                player.getInventory().placeItemBackInInventory(removed);
            }
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.75f, 0.95f);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private static boolean isAcceptedMaterial(Level level, BlockPos pos, BlockState material) {
        if (material.isAir() || material.getBlock() instanceof EntityBlock || material.getBlock() instanceof FramedCollapsibleCopycatArmorBlock) {
            return false;
        }
        VoxelShape shape = material.getShape(level, pos);
        return !shape.isEmpty() && shape.bounds().equals(Shapes.block().bounds());
    }

    public static boolean isCreateWrench(ItemStack stack) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && "create".equals(id.getNamespace()) && "wrench".equals(id.getPath());
    }

    public static boolean handleWrenchLeftClick(Level level, BlockPos pos, Player player, @Nullable Direction face) {
        Direction side = face != null ? face : pickFace(player);
        if (side == null) {
            return false;
        }
        if (level.isClientSide) {
            return true;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
            boolean expand = player.isShiftKeyDown();
            boolean changed = armor.changeFaceOffset(side, expand);
            if (changed) {
                level.playSound(null, pos, expand ? SoundEvents.ITEM_FRAME_REMOVE_ITEM : SoundEvents.ITEM_FRAME_ADD_ITEM,
                        SoundSource.BLOCKS, 0.75f, 0.95f);
            }
            return true;
        }
        return false;
    }

    @Nullable
    private static Direction pickFace(Player player) {
        HitResult hit = player.pick(10.0D, 1.0F, false);
        if (hit instanceof BlockHitResult blockHit) {
            return blockHit.getDirection();
        }
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
            armor.loadFromItem(stack);
        }
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        ItemStack stack = new ItemStack(asItem());
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
            armor.saveToItem(stack);
        }
        return List.of(stack);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        ItemStack stack = new ItemStack(asItem());
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
            armor.saveToItem(stack);
        }
        return stack;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(level, pos);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(level, pos);
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    private static VoxelShape shape(BlockGetter level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
            return SHAPE_CACHE.computeIfAbsent(armor.getPackedOffsets(), FramedCollapsibleCopycatArmorBlock::buildShape);
        }
        return Shapes.block();
    }

    private static VoxelShape buildShape(int packedOffsets) {
        byte[] offsets = unpackOffsets(packedOffsets);
        return Block.box(
                offsets[WEST],
                offsets[DOWN],
                offsets[NORTH],
                16 - offsets[EAST],
                16 - offsets[UP],
                16 - offsets[SOUTH]
        );
    }

    public static byte[] unpackOffsets(int packedOffsets) {
        byte[] offsets = new byte[Direction.values().length];
        for (Direction direction : Direction.values()) {
            offsets[direction.ordinal()] = (byte) ((packedOffsets >> (direction.ordinal() * 4)) & 0xF);
        }
        return offsets;
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return false;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FramedCollapsibleCopycatArmorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }
}
