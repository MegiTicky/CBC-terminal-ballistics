package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.debug.TBProjectileSlowdown;
import com.cbc_terminal_ballistics.hitcam.HitcamRecorder;
import com.cbc_terminal_ballistics.hitcam.TerminalBallisticsRecorder;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CBC Modern Warfare's medium cannon base overrides the CBC tick and only
 * calls {@code super} when the shell's own chunk is loaded, so the base-class
 * tick mixin — and with it first-tick session creation — is skipped on those
 * ticks. This injection sits on the TOP override, which always runs, so every
 * MW medium cannon shell (AP/APHE/APFSDS/APDS/canister/fuzed) is recorded and
 * gets a hitcam session on its first tick regardless of the chunk gate. The
 * base-class mixin then sees {@code first=false} and stays quiet.
 */
@Pseudo
@Mixin(targets = "riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile", remap = false)
public abstract class CBCMWMediumCannonProjectileTickMixin {
    @Inject(method = {"tick", "m_8119_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void ctb$mediumCannonTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (HitcamRecorder.record(self)) TerminalBallisticsRecorder.onProjectileSpawned(self);
        if (TBProjectileSlowdown.shouldSkip(self)) ci.cancel();
    }
}
