package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import com.cbc_terminal_ballistics.registry.ModItems;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlock;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
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

public class FramedCollapsibleCopycatArmorBlock extends CCCopycatBlock implements SimpleWaterloggedBlock {
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
    public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (isCreateWrench(stack)) {
            InteractionResult wrenchResult = removeCopiedMaterial(level, pos, player);
            return wrenchResult == InteractionResult.CONSUME ? ItemInteractionResult.CONSUME
                    : wrenchResult == InteractionResult.SUCCESS ? ItemInteractionResult.SUCCESS
                    : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.is(asItem()) || stack.is(ModItems.ARMOR_UPGRADER.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BlockState material = blockItem.getBlock().defaultBlockState();
        if (!isAcceptedMaterial(level, pos, material)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BlockEntity existing = level.getBlockEntity(pos);
        if (!(existing instanceof FramedCollapsibleCopycatArmorBlockEntity armor)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (armor.hasCopiedMaterial()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        return onBlockEntityUse(level, pos, be -> {
            if (!(be instanceof FramedCollapsibleCopycatArmorBlockEntity serverArmor)) {
                return InteractionResult.PASS;
            }
            if (serverArmor.hasCopiedMaterial()) {
                return InteractionResult.PASS;
            }
            serverArmor.setCopiedMaterial(material);
            serverArmor.setConsumedItem(stack);
            level.playSound(null, pos, material.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 0.75f);
            if (!player.isCreative()) {
                stack.shrink(1);
            }
            return InteractionResult.CONSUME;
        }) == InteractionResult.CONSUME ? ItemInteractionResult.CONSUME : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    private InteractionResult removeCopiedMaterial(Level level, BlockPos pos, Player player) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return onBlockEntityUse(level, pos, be -> {
            if (!(be instanceof FramedCollapsibleCopycatArmorBlockEntity armor)) {
                return InteractionResult.PASS;
            }
            if (!armor.hasCopiedMaterial()) {
                return InteractionResult.PASS;
            }
            ItemStack removed = armor.removeCopiedMaterial();
            if (!removed.isEmpty() && !player.isCreative()) {
                player.getInventory().placeItemBackInInventory(removed);
            }
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.75f, 0.95f);
            return InteractionResult.CONSUME;
        });
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
        withBlockEntityDo(level, pos, be -> {
            if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
                armor.loadFromItem(stack);
            }
        });
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        ItemStack stack = new ItemStack(asItem());
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
            armor.saveToItem(stack, params.getLevel().registryAccess());
        }
        return List.of(stack);
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, net.minecraft.world.phys.HitResult target, net.minecraft.world.level.LevelReader level, BlockPos pos, net.minecraft.world.entity.player.Player player) {
        ItemStack stack = new ItemStack(asItem());
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
            armor.saveToItem(stack, level.registryAccess());
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

    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return false;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
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

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
            armor.requestSableCollisionRefresh();
        }
    }

    @Override
    public BlockState transform(BlockState state, StructureTransform transform) {
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Class<CCCopycatBlockEntity> getBlockEntityClass() {
        return (Class) FramedCollapsibleCopycatArmorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CCCopycatBlockEntity> getBlockEntityType() {
        return ModBlockEntities.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get();
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
            return 20.0f + (armor.getArmorLevel() - 1) * 1.0f;
        }
        return 20.0f;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get()) {
            return null;
        }
        return (tickerLevel, tickerPos, tickerState, blockEntity) -> {
            if (blockEntity instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
                armor.tickSableCollision();
            }
        };
    }
}
