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

public class ArmorLayerUpgradeRecipe extends CustomRecipe {
    public ArmorLayerUpgradeRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        ItemStack armor = findArmorBlock(input);
        return isArmorBlock(armor) && countUpgraders(input) == 1
                && onlyArmorBlockAndOneUpgrader(input)
                && getArmorLevel(armor) < CopycatArmorLayerItem.MAX_LEVEL;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack layer = findArmorBlock(input);
        if (layer.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int nextLevel = getArmorLevel(layer) + 1;
        ItemStack result = layer.copy();
        result.setCount(1);
        setArmorLevel(result, nextLevel);
        return result;
    }

    private static ItemStack findArmorBlock(CraftingInput input) {
        ItemStack found = ItemStack.EMPTY;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (isArmorBlock(stack)) {
                if (!found.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                found = stack;
            }
        }
        return found;
    }

    private static int countUpgraders(CraftingInput input) {
        int count = 0;
        for (int i = 0; i < input.size(); i++) {
            if (input.getItem(i).is(ModItems.ARMOR_UPGRADER.get())) {
                count++;
            }
        }
        return count;
    }

    private static boolean onlyArmorBlockAndOneUpgrader(CraftingInput input) {
        int layers = 0;
        int upgraders = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
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
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.withSize(9, Ingredient.EMPTY);
        ingredients.set(0, Ingredient.of(ModItems.COPYCAT_ARMOR_LAYER.get(),
                ModItems.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get()));
        ingredients.set(4, Ingredient.of(ModItems.ARMOR_UPGRADER.get()));
        return ingredients;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return new ItemStack(ModItems.COPYCAT_ARMOR_LAYER.get());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.ARMOR_LAYER_UPGRADE.get();
    }
}