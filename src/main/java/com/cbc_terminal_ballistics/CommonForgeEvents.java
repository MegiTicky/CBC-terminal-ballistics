package com.cbc_terminal_ballistics;

import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlock;
import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.goggles.GogglesDebug;
import com.cbc_terminal_ballistics.hitcam.TerminalBallisticsRecorder;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonForgeEvents {
    private CommonForgeEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // GogglesDebug itself filters on viewers being present and cannon-projectile
        // class names; this hook only forwards so the overlay sees shells the moment
        // they enter the level, including ones removed before their first tick.
        GogglesDebug.onEntityJoin(event);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof FramedCollapsibleCopycatArmorBlock)) {
            return;
        }
        if (!FramedCollapsibleCopycatArmorBlock.isCreateWrench(event.getItemStack())) {
            return;
        }

        boolean handled = FramedCollapsibleCopycatArmorBlock.handleWrenchLeftClick(
                level, event.getPos(), event.getEntity(), event.getFace());
        if (handled) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isCannonWelder(event.getItemStack())) return;
        boolean shift = event.getEntity().isShiftKeyDown();
        BlockPos targetPos = event.getPos();
        boolean changed = clearWelderData(level, targetPos, shift);
        if (!changed && event.getFace() != null) {
            // A thin outer layer can receive the click while the shell is stored on the block behind it.
            targetPos = targetPos.relative(event.getFace().getOpposite());
            changed = clearWelderData(level, targetPos, shift);
        }
        if (!changed) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.getEntity().displayClientMessage(Component.literal(
            shift ? "Removed embedded shells." : "Repaired ballistic damage and removed impact debris."), true);
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        Entity direct = event.getSource().getDirectEntity();
        if (direct == null) direct = event.getSource().getEntity();
        if (direct != null) {
            TerminalBallisticsRecorder.onDirectEntityHit(direct, event.getEntity(), event.getAmount());
        }
    }

    /**
     * PlayerRevive intercepts lethal hits at HIGHEST priority and CANCELS the
     * death event to put the player in its downed (bleeding-out) state, so a
     * plain subscription never sees crew casualties on PlayerRevive servers.
     * receiveCanceled = true gives us both true deaths and downs; either way
     * the crew member is out of the fight and counts for the critical
     * evaluation. Handler must not inspect isCanceled().
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onLivingDeath(LivingDeathEvent event) {
        Entity direct = event.getSource().getDirectEntity();
        if (direct == null) direct = event.getSource().getEntity();
        if (direct != null) {
            TerminalBallisticsRecorder.onDirectEntityKill(direct, event.getEntity());
        } else {
            // Secondary explosions such as delayed APHE and ammo cook-offs can
            // have no source entity. Attribute only against a freshly recorded
            // secondary explosion inside the post-mortem grace window.
            TerminalBallisticsRecorder.onSecondaryExplosionCrewKill(event.getEntity().level(), event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        net.minecraft.world.level.Explosion explosion = event.getExplosion();
        TerminalBallisticsRecorder.onSecondaryExplosionStart(
            level, explosion.getIndirectSourceEntity(), explosion.getPosition(), explosionRadius(explosion));
    }

    /**
     * Shell explosions that destroyed critical blocks feed the critical
     * evaluation. CBC shell explosions extend vanilla Explosion and call its
     * finalizeExplosion, so this event fires with the affected block list; the
     * blocks are still present when it fires. The recorder no-ops unless the
     * source entity has an active hitcam session.
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity source = event.getExplosion().getIndirectSourceEntity();
        if (source != null) {
            // Relay the shell's own detonation for the terminal view (blast
            // marker, HE camera widening). First blast per session wins.
            TerminalBallisticsRecorder.onShellExplosion(
                source, event.getExplosion().getPosition(), explosionRadius(event.getExplosion()));
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                // VS ship blocks: the world position holds air, the block lives
                // in the contraption/ship storage.
                BlockState contra = ContraptionCompat.blockStateAt(level, pos);
                if (contra != null) state = contra;
            }
            if (source == null) {
                // No attributable entity (e.g. an ammo-rack cook-off): fall back
                // to positional attribution against finished engagements.
                TerminalBallisticsRecorder.onUnattributedCriticalBlock(level, Vec3.atCenterOf(pos), state);
            } else {
                TerminalBallisticsRecorder.onCriticalBlock(source, Vec3.atCenterOf(pos), state);
            }
        }
    }

    // NOTE: EntityLeaveLevelEvent is deliberately NOT handled here. It fires on
    // the CLIENT side too (ClientLevel despawns the shell's mirror copy once it
    // leaves entity-tracking range), and in single player the recorder's static
    // session map is shared with the client thread — the client's routine
    // mirror-copy despawn was tearing down the live session and sending the
    // artillery view a removed packet, cutting the chase cam at exactly
    // tracking range while the server shell kept flying. The relay's own
    // server-thread isRemoved() check is the single authoritative teardown.

    private static boolean clearWelderData(ServerLevel level, BlockPos pos, boolean shellsOnly) {
        return shellsOnly ? TBImpactService.clearEmbeddedShellsOnly(level, pos)
            : TBImpactService.clearBlockImpactData(level, pos);
    }

    /** Cached handle for Explosion's private radius field (no getter on 1.20.1). */
    private static volatile java.lang.reflect.Field explosionRadiusField;

    /**
     * Explosion radius via reflection: the field is {@code radius} in dev
     * (official mappings) and {@code f_46017_} in production (SRG). Falls back
     * to a TNT-sized 4.0 if neither name resolves.
     */
    private static float explosionRadius(net.minecraft.world.level.Explosion explosion) {
        if (explosionRadiusField == null) {
            for (String name : new String[]{"radius", "f_46017_"}) {
                try {
                    java.lang.reflect.Field f = net.minecraft.world.level.Explosion.class.getDeclaredField(name);
                    f.setAccessible(true);
                    explosionRadiusField = f;
                    break;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            if (explosionRadiusField == null) return 4.0F;
        }
        try {
            return explosionRadiusField.getFloat(explosion);
        } catch (IllegalAccessException e) {
            return 4.0F;
        }
    }

    private static boolean isCannonWelder(ItemStack stack) {
        return ForgeRegistries.ITEMS.getKey(stack.getItem()) != null
            && ForgeRegistries.ITEMS.getKey(stack.getItem()).toString().equals("createbigcannons:cannon_welder");
    }

}
