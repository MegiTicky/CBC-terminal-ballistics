package com.cbc_terminal_ballistics.compat;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.armor.CopycatArmorLayerBlockEntity;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlockEntity;
import com.cbc_terminal_ballistics.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

public final class CbcArmorCompat {
    private static final String HANDLER_CLASS = "rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesHandler";
    private static final String PROVIDER_CLASS = "rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesProvider";
    private static final String SERIALIZER_CLASS = "rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesSerializer";

    private static boolean registered;

    private CbcArmorCompat() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        try {
            Class<?> providerInterface = Class.forName(PROVIDER_CLASS);
            Class<?> serializerInterface = Class.forName(SERIALIZER_CLASS);
            Class<?> handlerClass = Class.forName(HANDLER_CLASS);

            Object provider = createProvider(providerInterface);
            Object serializer = createSerializer(serializerInterface, provider);
            Method registerCustomSerializer = handlerClass.getMethod("registerCustomSerializer", Block.class, serializerInterface);

            registerCustomSerializer.invoke(null, ModBlocks.COPYCAT_ARMOR_LAYER.get(), serializer);
            registerCustomSerializer.invoke(null, ModBlocks.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get(), serializer);
            registered = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            CBCTerminalBallistics.LOGGER.warn("Unable to register CBC armor properties for copycat armor blocks", e);
        }
    }

    private static Object createProvider(Class<?> providerInterface) {
        return Proxy.newProxyInstance(providerInterface.getClassLoader(), new Class<?>[]{providerInterface}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "hardness" -> hardness((Level) args[0], (BlockPos) args[2], (boolean) args[3]);
                case "toughness" -> toughness((Level) args[0], (BlockPos) args[2], (boolean) args[3]);
                case "containedBlockStates" -> containedBlockStates((Level) args[0], (BlockPos) args[2], (boolean) args[3]);
                case "toString" -> "CBCTBCopycatArmorProperties";
                default -> defaultValue(method.getReturnType());
            };
        });
    }

    private static Object createSerializer(Class<?> serializerInterface, Object provider) {
        return Proxy.newProxyInstance(serializerInterface.getClassLoader(), new Class<?>[]{serializerInterface}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "loadBlockArmorPropertiesFromJson", "fromNetwork" -> provider;
                case "toNetwork" -> null;
                case "toString" -> "CBCTBCopycatArmorPropertiesSerializer";
                default -> defaultValue(method.getReturnType());
            };
        });
    }

    private static double toughness(Level level, BlockPos pos, boolean recurse) {
        if (recurse) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CopycatArmorLayerBlockEntity armorLayer) {
                return armorLayer.getToughness();
            }
            if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armorBlock) {
                return armorBlock.getToughness();
            }
        }
        return CopycatArmorLayerBlockEntity.MIN_LEVEL * CopycatArmorLayerBlockEntity.TOUGHNESS_PER_LEVEL;
    }

    private static double hardness(Level level, BlockPos pos, boolean recurse) {
        if (recurse) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CopycatArmorLayerBlockEntity armorLayer) {
                return armorLayer.getArmorLevel();
            }
            if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armorBlock) {
                return armorBlock.getArmorLevel();
            }
        }
        return CopycatArmorLayerBlockEntity.MIN_LEVEL;
    }

    private static List<BlockState> containedBlockStates(Level level, BlockPos pos, boolean recurse) {
        if (recurse) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CopycatArmorLayerBlockEntity armorLayer) {
                return List.of(armorLayer.getCopiedMaterial());
            }
            if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armorBlock) {
                return List.of(armorBlock.getCopiedMaterial());
            }
        }
        return List.of(Blocks.IRON_BLOCK.defaultBlockState());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }
}