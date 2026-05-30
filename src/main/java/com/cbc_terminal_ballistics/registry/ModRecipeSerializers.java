package com.cbc_terminal_ballistics.registry;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.recipe.ArmorLayerExtractRecipe;
import com.cbc_terminal_ballistics.recipe.ArmorLayerUpgradeRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, CBCTerminalBallistics.MOD_ID);

    public static final RegistryObject<SimpleCraftingRecipeSerializer<ArmorLayerUpgradeRecipe>> ARMOR_LAYER_UPGRADE =
            RECIPE_SERIALIZERS.register("crafting_special_armor_layer_upgrade",
                    () -> new SimpleCraftingRecipeSerializer<>(ArmorLayerUpgradeRecipe::new));

    public static final RegistryObject<SimpleCraftingRecipeSerializer<ArmorLayerExtractRecipe>> ARMOR_LAYER_EXTRACT =
            RECIPE_SERIALIZERS.register("crafting_special_armor_layer_extract",
                    () -> new SimpleCraftingRecipeSerializer<>(ArmorLayerExtractRecipe::new));

    private ModRecipeSerializers() {
    }
}
