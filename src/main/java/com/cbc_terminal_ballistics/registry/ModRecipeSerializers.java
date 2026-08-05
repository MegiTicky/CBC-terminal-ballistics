package com.cbc_terminal_ballistics.registry;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.recipe.ArmorLayerExtractRecipe;
import com.cbc_terminal_ballistics.recipe.ArmorLayerUpgradeRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, CBCTerminalBallistics.MOD_ID);

    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<ArmorLayerUpgradeRecipe>> ARMOR_LAYER_UPGRADE =
            RECIPE_SERIALIZERS.register("crafting_special_armor_layer_upgrade",
                    () -> new SimpleCraftingRecipeSerializer<>(ArmorLayerUpgradeRecipe::new));

    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<ArmorLayerExtractRecipe>> ARMOR_LAYER_EXTRACT =
            RECIPE_SERIALIZERS.register("crafting_special_armor_layer_extract",
                    () -> new SimpleCraftingRecipeSerializer<>(ArmorLayerExtractRecipe::new));

    private ModRecipeSerializers() {}
}