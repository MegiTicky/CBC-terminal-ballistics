package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.debug.AdvancedInspectionOverlayHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.block_armor_properties.BlockArmorInspectionToolItem", remap = false)
public abstract class BlockArmorInspectionToolItemMixin {
    @Inject(method = "addBlockArmorInfo", at = @At("TAIL"), remap = false)
    private static void ctb$appendAdvancedInfo(List<Component> tooltip, Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        AdvancedInspectionOverlayHooks.addBlockArmorInfo(tooltip, pos);
    }
}
