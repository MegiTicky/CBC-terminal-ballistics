package com.cbc_terminal_ballistics.recipe;

import com.cbc_terminal_ballistics.armor.CopycatArmorLayerItem;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorItem;
import com.cbc_terminal_ballistics.registry.ModItems;
import com.cbc_terminal_ballistics.registry.ModRecipeSerializers;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class ArmorLayerExtractRecipe extends CustomRecipe {
    public ArmorLayerExtractRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        ItemStack layer = ItemStack.EMPTY;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!isArmorBlock(stack) || !layer.isEmpty() || stack.getCount() != 1) {
                return false;
            }
            layer = stack;
        }
        return !layer.isEmpty() && getArmorLevel(layer) > CopycatArmorLayerItem.MIN_LEVEL;
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess registryAccess) {
        return new ItemStack(ModItems.ARMOR_UPGRADER.get());
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (isArmorBlock(stack)) {
                int level = getArmorLevel(stack);
                if (level > CopycatArmorLayerItem.MIN_LEVEL) {
                    ItemStack downgraded = stack.copy();
                    downgraded.setCount(1);
                    setArmorLevel(downgraded, level - 1);
                    remaining.set(i, downgraded);
                }
                break;
            }
        }
        return remaining;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 1;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.ARMOR_LAYER_EXTRACT.get();
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
}
