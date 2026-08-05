package com.cbc_terminal_ballistics.recipe;

import com.cbc_terminal_ballistics.armor.CopycatArmorLayerItem;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorItem;
import com.cbc_terminal_ballistics.registry.ModItems;
import com.cbc_terminal_ballistics.registry.ModRecipeSerializers;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class ArmorLayerExtractRecipe extends CustomRecipe {
    public ArmorLayerExtractRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        ItemStack layer = ItemStack.EMPTY;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
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
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return new ItemStack(ModItems.ARMOR_UPGRADER.get());
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
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
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.withSize(9, Ingredient.EMPTY);
        ingredients.set(4, Ingredient.of(ModItems.COPYCAT_ARMOR_LAYER.get(),
                ModItems.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get()));
        return ingredients;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return new ItemStack(ModItems.ARMOR_UPGRADER.get());
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