package com.cbc_terminal_ballistics.recipe;

import com.cbc_terminal_ballistics.armor.CopycatArmorLayerItem;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorItem;
import com.cbc_terminal_ballistics.registry.ModItems;
import com.cbc_terminal_ballistics.registry.ModRecipeSerializers;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class ArmorLayerUpgradeRecipe extends CustomRecipe {
    public ArmorLayerUpgradeRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        ItemStack armor = findArmorBlock(container);
        return isArmorBlock(armor) && countUpgraders(container) == 1
                && onlyArmorBlockAndOneUpgrader(container)
                && getArmorLevel(armor) < CopycatArmorLayerItem.MAX_LEVEL;
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess registryAccess) {
        ItemStack layer = findArmorBlock(container);
        if (layer.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int nextLevel = getArmorLevel(layer) + 1;
        ItemStack result = layer.copy();
        result.setCount(1);
        setArmorLevel(result, nextLevel);
        return result;
    }

    private static ItemStack findArmorBlock(CraftingContainer container) {
        ItemStack found = ItemStack.EMPTY;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (isArmorBlock(stack)) {
                if (!found.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                found = stack;
            }
        }
        return found;
    }

    private static int countUpgraders(CraftingContainer container) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(ModItems.ARMOR_UPGRADER.get())) {
                count++;
            }
        }
        return count;
    }

    private static boolean onlyArmorBlockAndOneUpgrader(CraftingContainer container) {
        int layers = 0;
        int upgraders = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isArmorBlock(stack)) {
                layers++;
            } else if (stack.is(ModItems.ARMOR_UPGRADER.get())) {
                upgraders++;
            } else {
                return false;
            }
        }
        return layers == 1 && upgraders == 1;
    }

    private static boolean isArmorBlock(ItemStack stack) {
        return stack.getItem() instanceof CopycatArmorLayerItem
                || stack.getItem() instanceof FramedCollapsibleCopycatArmorItem;
    }

    private static int getArmorLevel(ItemStack stack) {
        if (stack.getItem() instanceof FramedCollapsibleCopycatArmorItem) {
            return FramedCollapsibleCopycatArmorItem.getArmorLevel(stack);
        }
        return CopycatArmorLayerItem.getArmorLevel(stack);
    }

    private static void setArmorLevel(ItemStack stack, int level) {
        if (stack.getItem() instanceof FramedCollapsibleCopycatArmorItem) {
            FramedCollapsibleCopycatArmorItem.setArmorLevel(stack, level);
        } else {
            CopycatArmorLayerItem.setArmorLevel(stack, level);
        }
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.ARMOR_LAYER_UPGRADE.get();
    }
}
