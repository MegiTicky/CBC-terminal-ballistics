package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.debug.TBProjectileSlowdown;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile", remap = false)
public abstract class AbstractAutocannonProjectileTickMixin {
    @Inject(method = {"tick", "m_8119_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void ctb$slowAutocannonProjectileTick(CallbackInfo ci) {
        if (TBProjectileSlowdown.shouldSkip((Entity) (Object) this)) ci.cancel();
    }
}
