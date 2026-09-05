package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.debug.TBProjectileSlowdown;
import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.hitcam.HitcamRecorder;
import com.cbc_terminal_ballistics.hitcam.TerminalBallisticsRecorder;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile", remap = false)
public abstract class AbstractCannonProjectileTickMixin {
    @Inject(method = {"tick", "m_8119_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void ctb$slowProjectileTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (HitcamRecorder.record(self)) TerminalBallisticsRecorder.onProjectileSpawned(self);
        if (TBProjectileSlowdown.shouldSkip(self)) ci.cancel();
    }

    @Inject(method = "clipAndDamage", at = @At("TAIL"), remap = false)
    private void ctb$finalizeDeferredMassReset(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        TBImpactService.finalizeDeferredMassReset(self);
        TerminalBallisticsRecorder.onClipAndDamageTail(self);
    }
}
