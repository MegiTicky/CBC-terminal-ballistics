package com.cbc_terminal_ballistics.ballistics;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.armor.ArmorCopycatItemData;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlock;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.compat.TestLauncherProjectileCompat;
import com.cbc_terminal_ballistics.data.CopycatMaterialResolver;
import com.cbc_terminal_ballistics.data.IgnoredBlockManager;
import com.cbc_terminal_ballistics.data.MaterialManager;
import com.cbc_terminal_ballistics.data.MaterialStats;
import com.cbc_terminal_ballistics.debug.TBDebug;
import com.cbc_terminal_ballistics.hitcam.TerminalBallisticsRecorder;
import com.cbc_terminal_ballistics.network.ClientboundImpactOutcomePacket;
import com.cbc_terminal_ballistics.network.ClientboundImpactMarksPacket;
import com.cbc_terminal_ballistics.network.ClientboundEmbeddedShellsPacket;
import com.cbc_terminal_ballistics.network.ClientboundIntegrityProgressPacket;
import com.cbc_terminal_ballistics.network.ClientboundSpallConePacket;
import com.cbc_terminal_ballistics.network.TBNetwork;
import com.cbc_terminal_ballistics.state.ArmorIntegritySavedData;
import com.cbc_terminal_ballistics.state.ImpactMark;
import com.cbc_terminal_ballistics.state.EmbeddedShell;
import com.cbc_terminal_ballistics.state.EmbeddedShellSavedData;
import com.cbc_terminal_ballistics.state.TemporaryBlockPassage;
import com.cbc_terminal_ballistics.util.CBCReflect;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import com.cbc_terminal_ballistics.util.VSCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class TBImpactService {
    private static final org.slf4j.Logger CTB_LOG = org.slf4j.LoggerFactory.getLogger("CBCTB/ImpactService");
    private static final Map<Long, LastImpact> LAST_IMPACTS = new HashMap<>();
    private static final Set<Entity> DEFERRED_MASS_RESETS = Collections.newSetFromMap(new WeakHashMap<>());
    /** Consecutive same-block impacts before a projectile is declared jammed and terminated. */
    private static final int MAX_CONSECUTIVE_SAME_IMPACTS = 4;
    private static final java.util.Map<Entity, ImpactJam> IMPACT_JAMS =
        java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final class ImpactJam {
        final BlockPos pos;
        final BlockState state;
        int count = 1;

        ImpactJam(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }
    }
    private static final double MIN_SPALL_VISUAL_CLEARANCE = 0.85D;
    private static final double RICOCHET_MARK_MIN_ANGLE_FROM_NORMAL_DEGREES = 40.0D;
    private static final double RICOCHET_MARK_MAX_INCIDENCE = Math.cos(Math.toRadians(RICOCHET_MARK_MIN_ANGLE_FROM_NORMAL_DEGREES));
    private static final double HARD_BLOCK_IMPACT_SOUND_TOUGHNESS = 5.0D;
    private static final ResourceLocation CBC_PROJECTILE_IMPACT_SOUND = new ResourceLocation("createbigcannons", "projectile_impact");

    public static void deferMassReset(Entity projectile) {
        DEFERRED_MASS_RESETS.add(projectile);
    }

    public static void finalizeDeferredMassReset(Entity projectile) {
        if (DEFERRED_MASS_RESETS.remove(projectile)) {
            CBCReflect.setProjectileMass(projectile, 0.0);
        }
    }

    public static Object calculate(Entity projectile, Object projectileContext, BlockState state, BlockHitResult hit, boolean autocannonHint) {
        if (!TBConfig.ENABLED.get()) return null;
        if (IgnoredBlockManager.INSTANCE.isIgnored(state)) {
            // Ignored blocks (data-driven list) are treated as if they do not exist. The clip
            // mixin already skips past the block (IgnoredBlockSkip) so it is never phased to air
            // or otherwise mutated, and this PENETRATE covers the state the loop re-fetches from
            // the skipped position. Runs before the HEAP/HEAT bypass so bypassed shells also pass
            // through. No world mutation happens here.
            return CBCReflect.newImpactResult("PENETRATE", false);
        }
        if (ProjectileClassifier.shouldBypassTB(projectile)) return null;
        try {
            Level level = projectile.level();
            BlockPos pos = hit.getBlockPos();
            // Jam breaker: a projectile hitting the exact same block state on
            // consecutive impacts is not advancing (observed as a shell
            // re-penetrating one block forever when a contraption or clip edge
            // traps it). A real shell always moves on to different blocks, so
            // terminate it as a STOP instead of looping — the loop also kept
            // the hitcam session and relay alive indefinitely.
            ImpactJam jam = IMPACT_JAMS.get(projectile);
            if (jam != null && jam.pos.equals(pos) && jam.state == state) {
                if (++jam.count >= MAX_CONSECUTIVE_SAME_IMPACTS) {
                    IMPACT_JAMS.remove(projectile);
                    if (!level.isClientSide) {
                        CBCTerminalBallistics.LOGGER.warn(
                            "[CTB] projectile {} jammed re-impacting {} at {} for {} ticks; terminating",
                            projectile.getType(), state.getBlock(), pos, jam.count);
                    }
                    return CBCReflect.newImpactResult("STOP", true);
                }
            } else {
                jam = new ImpactJam(pos, state);
                IMPACT_JAMS.put(projectile, jam);
            }
            boolean isContraption = ContraptionCompat.isContraptionBlock(level, pos);
            // Resolve the owning contraption entity + local block position once per impact.
            // The entity drives spall blocking (snapshot raycasts); the local position anchors
            // marks and embedded shells so they follow the contraption when it moves.
            // Primary: the raycast that produced this hit publishes its exact entity + local
            // cell — re-deriving from the world-snapped BlockPos loses precision on rotated
            // or moving contraptions (back-mapped center crosses into a neighbor cell).
            Entity contraptionEntity = null;
            BlockPos contraptionLocalPos = null;
            ContraptionCompat.MatchedContraptionHit matchedHit = ContraptionCompat.matchedContraptionHit(hit.getLocation());
            if (matchedHit != null) {
                isContraption = true;
                contraptionEntity = matchedHit.entity();
                contraptionLocalPos = matchedHit.localPos();
            } else if (isContraption) {
                contraptionEntity = ContraptionCompat.contraptionEntityAt(level, pos);
                if (contraptionEntity != null) {
                    contraptionLocalPos = ContraptionCompat.localPosAt(contraptionEntity, hit.getLocation());
                    if (contraptionLocalPos == null) {
                        contraptionLocalPos = ContraptionCompat.localPosAt(contraptionEntity, Vec3.atCenterOf(pos));
                    }
                }
            }
            Vec3 accel = CBCReflect.forces(projectile, projectile.position(), projectile.getDeltaMovement());
            Vec3 curVel = projectile.getDeltaMovement().add(accel);
            double velMag = Math.max(curVel.length(), 1e-4d);
            Vec3 velDir = curVel.normalize();
            Vec3 rawNormal = CBCReflect.surfaceNormal(level, hit);
            if (rawNormal == null || rawNormal.lengthSqr() < 1e-8) {
                // Fallback: derive normal from hit direction (works for contraption blocks
                // where CBCUtils.getSurfaceNormalVector may not resolve world block data)
                rawNormal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
            }
            // Contraption hits carry the exact rotated surface normal. The BlockHitResult
            // direction is only axis-snapped, which would make angled hull plates behave
            // as if flat (90 degrees) and let shells penetrate far too easily.
            if (isContraption) {
                Vec3 contraptionNormal = ContraptionCompat.matchedContraptionNormal(hit.getLocation());
                if (contraptionNormal != null) rawNormal = contraptionNormal;
            }
            Vec3 normal = rawNormal.normalize();
            double incidence = Math.max(0.0, velDir.dot(normal.reverse()));
            double incidentVel = velMag * incidence;
            double mass = Math.max(0.0, CBCReflect.projectileMass(projectile));
            double penetration = CBCReflect.ballistic(projectile, "penetration", 1.0);
            double projectileToughness = Math.max(0.25, CBCReflect.ballistic(projectile, "toughness", 1.0));
            double deflection = CBCReflect.ballistic(projectile, "deflection", 0.2);
            double fallbackHardness = 1.0; // CBC fallback hardness
            double fallbackToughness = Math.max(0.0, state.getBlock().getExplosionResistance()); // CBC fallback toughness
            double baseArmorToughness = CBCReflect.armorToughness(level, state, pos, fallbackToughness);
            double armorHardness = CBCReflect.armorHardness(level, state, pos, fallbackHardness);
            boolean unbreakable = CBCReflect.griefNoDamage(projectileContext) || state.getDestroySpeed(level, pos) < 0;

            BlockState materialState = CopycatMaterialResolver.resolve(level, pos, state, hit).orElse(state);
            MaterialStats material = MaterialManager.INSTANCE.get(materialState, baseArmorToughness);
            ImpactSurfaceType impactSurface = material.surface();
            TBCaliber caliber = ProjectileClassifier.classify(projectile, autocannonHint);
            boolean autocannon = caliber == TBCaliber.AUTOCANNON || caliber == TBCaliber.HEAVY_AUTOCANNON;
            boolean surfaceImpact = autocannon ? CBCReflect.lastPenetratedBlockIsAir(projectile) : CBCReflect.canHitSurface(projectile);
            // The terminal-ballistics hitcam only reconstructs vehicle/armour engagements.
            boolean tbArmorHit = isContraption || isArmorLike(state) || isMetalLike(state) || isVehiclePanelLike(state);

            // Penetration/no-penetration intentionally follows CBC's original basis for now:
            // block is perforated if projectile_mass * incident_velocity * velocity_bonus >= CBC block toughness. (default CBC penetration)
            // This means datapacks that tune CBC "durability_mass" on each munition directly control penetration.
            // CBCTB material toughness multipliers and caliber scales are not used for this decision.
            double bonusMomentum = 1.0 + Math.max(0.0, velMag - 1.0) * 0.10;
            double momentum = mass * incidentVel * bonusMomentum;
            double attack = momentum * caliber.penetrationScale;
            double effectiveDuctility = effectiveDuctility(materialState, material, baseArmorToughness);
            double threshold = integrityThreshold(materialState, material, baseArmorToughness);
            double savedDamage = 0.0D;
            if (level instanceof ServerLevel server) {
                ArmorIntegritySavedData.Entry entry = ArmorIntegritySavedData.get(server).getEntry(server, pos);
                savedDamage = entry == null ? 0.0D : entry.damage;
            }
            double armorToughness = degradedToughness(baseArmorToughness, savedDamage, threshold);
            double effectiveToughness = Math.max(0.02, armorToughness);
            double hardnessPenaltyRaw = armorHardness - penetration;
            double hardnessPenalty = Math.max(0.0, hardnessPenaltyRaw);
            double perforationResistance = effectiveToughness;
            double penetrationRatio = attack / perforationResistance;

            // Integrity damage is calibrated from CBC block toughness, not from CBCTB toughness multipliers.
            // A 20-toughness armor block with ductility 5 should roughly survive:
            //   big: 2 hits, medium: 4 hits, small: 8 hits, autocannon: many hits.
            // Datapacks can still change this mainly by overriding material ductility.
            double impactSeverity = penetrationRatio >= 1.0
                ? Mth.clamp(penetrationRatio, 0.90, 1.35)
                : Mth.clamp(penetrationRatio * 0.55, 0.10, 0.70);
            double rawDamage = baseArmorToughness * caliberIntegrityWear(caliber) * impactSeverity * TBConfig.IMPACT_DAMAGE_SCALE.get();
            if (autocannon && baseArmorToughness > 8.0) rawDamage *= TBConfig.AUTOCANNON_ARMOR_DAMAGE_MULTIPLIER.get();
            // fragile blocks get broken by autocannon straight away

            String outcome = "STOP";
            ImpactMarkKind markKind = ImpactMarkKind.PALE;
            boolean shatter = false;
            boolean integrityBreak = false;
            double appliedDamage = rawDamage;

            double bounceChance = 0.0D;
            if (deflection >= 1e-2D && incidence <= deflection) {
                double baseBounce = Math.max(CBCReflect.baseProjectileBounceChance(), 1.0D - incidence / deflection);
                double minTough = TBConfig.RICOCHET_MIN_TOUGHNESS.get();
                double scaleTough = TBConfig.RICOCHET_TOUGHNESS_SCALE.get();
                double toughnessFactor = armorToughness <= minTough ? 0.0 : Math.min((armorToughness - minTough) / (scaleTough - minTough), 1.0);
                double velocityFactor = Math.max(0.2, 1.0 - velMag / TBConfig.RICOCHET_VELOCITY_BASELINE.get());
                double bounceBonus = Math.max(1.0D - hardnessPenaltyRaw, 0.0D);
                bounceChance = baseBounce * bounceBonus * toughnessFactor * velocityFactor;
            }
            if (surfaceImpact && CBCReflect.projectilesCanBounce() && deterministicImpactRoll(projectile, pos, hit.getDirection()) < bounceChance) {
                if (!level.isClientSide) {
                    double massLossFraction = calculateRicochetMassLoss(velMag, incidence, armorToughness);
                    CBCReflect.setProjectileMass(projectile, Math.max(0.0, mass * (1.0 - massLossFraction)));
                    Vec3 effectNormal = curVel.subtract(normal.scale(normal.dot(curVel) * 1.7D));
                    CBCReflect.addBlockHitEffect(projectileContext, projectile, level, state, pos, hit.getLocation(), effectNormal, true);
                    if (level instanceof ServerLevel server) {
                        playHardBlockImpactSound(server, pos, armorToughness, caliber, velMag);
                        addImpactMark(server, pos, state, hit, ImpactMarkKind.STREAK, caliber, impactSurface, curVel, contraptionEntity, contraptionLocalPos);
                    }
                }
                Object bounceResult = CBCReflect.newImpactResult("BOUNCE", false);
                boolean onImpactRemove = CBCReflect.callOnImpact(projectile, hit, bounceResult, projectileContext);
                if (level instanceof ServerLevel server) {
                    Vec3 authoritativeVelocity = curVel.subtract(normal.scale(normal.dot(curVel) * 1.7D));
                    syncImpactOutcome(server, projectile, "BOUNCE", projectile.position(), authoritativeVelocity, false, pos);
                    if (tbArmorHit) {
                        TerminalBallisticsRecorder.onPlateImpact(projectile, Vec3.atCenterOf(pos), "BOUNCE",
                            0.0D, baseArmorToughness, false, net.minecraft.world.level.block.Block.getId(state),
                            captureShapeBoxes(level, pos, state), contraptionEntity);
                    }
                }
                return autocannon ? bounceResult : CBCReflect.newImpactResult("BOUNCE", onImpactRemove);
            }

            boolean perforates = !unbreakable && penetrationRatio >= 1.0;
            if (isContraption && CTB_LOG.isDebugEnabled()) {
                CTB_LOG.debug("[ctb] contraption impact: state={} pos={} velMag={} mass={} attack={} toughness={} ratio={} outcome={} perforates={}",
                    state, pos, String.format("%.2f", velMag), String.format("%.2f", mass),
                    String.format("%.2f", attack), String.format("%.2f", baseArmorToughness),
                    String.format("%.2f", penetrationRatio), perforates ? "PENETRATE" : "STOP", perforates);
            }
            if (!level.isClientSide && level instanceof ServerLevel server && !unbreakable) {
                ArmorIntegritySavedData data = ArmorIntegritySavedData.get(server);
                if (appliedDamage > 0) data.addDamage(server, pos, state, appliedDamage);
                double currentDamage = data.damage(server, pos, state);
                shatter = material.brittleness() > 0.70 && effectiveDuctility < 1.0 && attack > perforationResistance * (1.25 - material.brittleness() * 0.25);
                syncIntegrityProgress(server, pos, currentDamage, threshold);
                boolean immediateBreak = shouldBreakImmediately(materialState, material, baseArmorToughness, attack, false);
                integrityBreak = immediateBreak || currentDamage >= threshold || shatter;
            }
            if (perforates) {
                outcome = "PENETRATE";
                markKind = ImpactMarkKind.HOLE;
            }
            // Visual-only ricochet scrape mark: use the ricochet texture only for non-penetrating
            // glancing impacts (angle from the surface normal > 40 degrees) without changing
            // CBC/CBCTB bounce logic. Penetrations keep the hole mark.
            if (!perforates && incidence < RICOCHET_MARK_MAX_INCIDENCE) {
                markKind = ImpactMarkKind.STREAK;
            }
            ShatterDecision shatterDecision = projectileShatterDecision(projectile, velMag, incidence, penetrationRatio, markKind);
            boolean projectileShatter = shatterDecision.shattered();

            double massLoss = 0.0;
            int spallFragments = 0;
            double spallDamageModifier = 0.0;
            String spallReason = "not_checked";
            boolean temporaryPassagePending = false;
            boolean blockBreakPending = false;
            if (level instanceof ServerLevel server) {
                if (projectile instanceof Projectile p) state.onProjectileHit(level, state, hit, p);
                CBCReflect.addBlockHitEffect(projectileContext, projectile, level, state, pos, hit.getLocation(), curVel.reverse(), false);
                playHardBlockImpactSound(server, pos, armorToughness, caliber, velMag);
                ArmorIntegritySavedData data = ArmorIntegritySavedData.get(server);
                ImpactMark impactMark = mark(server, pos, hit, markKind, caliber, impactSurface,
                    markKind == ImpactMarkKind.STREAK ? ricochetMarkRotation(server, pos, hit, curVel) : 0.0F,
                    contraptionEntity, contraptionLocalPos);
                ArmorIntegritySavedData.Entry markEntry = addMarkEntry(server, data, pos, state, contraptionEntity, contraptionLocalPos, impactMark);
                if (outcome.equals("PENETRATE")) {
                    ImpactMark exit = exitMark(server, pos, hit, caliber, impactSurface, contraptionEntity, contraptionLocalPos);
                    if (exit != null) markEntry = addMarkEntry(server, data, pos, state, contraptionEntity, contraptionLocalPos, exit);
                }
                syncMarksForEntry(server, markEntry, pos, contraptionEntity);

                if (outcome.equals("PENETRATE") && !unbreakable) {
                    if (integrityBreak || shatter) {
                        blockBreakPending = true;
                    } else {
                        // Keep the contact block present until CBC's native onImpact/fuze callback
                        // has run. Immediate fuzes must detonate against the real block; delayed
                        // fuzes can request temporary passage after the callback returns.
                        temporaryPassagePending = true;
                    }
                    // (Removed) Velocity dependent mass loss formula (not default CBC behaivor): massLoss = Mth.clamp(((hardnessPenalty + 1.0) * effectiveToughness / Math.max(incidentVel, 0.1)) * caliber.massLossScale, 0.01, Math.max(0.01, mass * 0.95));
                    massLoss = ((float) Math.max(0, hardnessPenalty) + 1) * (float) effectiveToughness / (float) incidentVel; //Same as CBC durabilityPenalty formula
                    CBCReflect.setProjectileMass(projectile, Math.max(0.0, mass - massLoss));
                    double damping = TBConfig.VELOCITY_DAMPING_PER_MASS_LOSS.get();
                    if (damping > 0 && mass > 1e-4) {
                        double factor = Mth.clamp(1.0 - (massLoss / mass) * damping, 0.05, 1.0);
                        projectile.setDeltaMovement(projectile.getDeltaMovement().scale(factor));
                    }
                    if (ProjectileClassifier.canSpall(projectile)) {
                        Vec3 spallOrigin = computeSpallOrigin(server, state, pos, hit.getLocation(), velDir,
                            isContraption, contraptionEntity, contraptionLocalPos);
                        spallDamageModifier = ProjectileClassifier.shellSpallDamageModifier(projectile);
                        double massAfter = Math.max(0.0, mass - massLoss);
                        spallFragments = spawnSpall(server, projectile, spallOrigin, velDir, velMag, caliber, mass, massAfter, material, baseArmorToughness, contraptionEntity);
                        spallReason = spallFragments > 0 ? "spawned" : "zero_fragments";
                    } else {
                        spallReason = "not_ap_style";
                    }
                } else {
                    // CBC applies queued entity hits after block penetration. Keep mass
                    // available until that pass completes, then clear it from the mixin tail.
                    deferMassReset(projectile);
                    if (integrityBreak && !unbreakable) {
                        blockBreakPending = true;
                    }
                    if (projectileShatter && ProjectileClassifier.canSpall(projectile)) {
                        double massAfter = Math.max(0.0, mass * 0.20);
                        spallDamageModifier = ProjectileClassifier.shellSpallDamageModifier(projectile);
                        spallFragments = spawnSpall(server, projectile, hit.getLocation().add(velDir.scale(0.08)),
                            velDir, velMag, caliber, mass, massAfter, material, baseArmorToughness, contraptionEntity);
                        spallReason = spallFragments > 0 ? "shattered" : "shattered_no_fragments";
                    } else {
                        spallReason = integrityBreak ? "stopped_integrity_break_no_spall" : "stopped_no_spall";
                    }
                }
                LAST_IMPACTS.put(server.dimension().location().hashCode() * 31L + pos.asLong(),
                    new LastImpact(outcome, appliedDamage, threshold, caliber, material, mass, Math.max(0.0, mass - massLoss), velMag, incidence, armorToughness, armorHardness, effectiveToughness, attack, perforationResistance, penetrationRatio, massLoss, spallFragments, spallDamageModifier, spallReason, shatterDecision.chance(), projectileShatter));
                if (tbArmorHit) {
                    TerminalBallisticsRecorder.onPlateImpact(projectile, Vec3.atCenterOf(pos), outcome,
                        appliedDamage, baseArmorToughness, blockBreakPending, net.minecraft.world.level.block.Block.getId(state),
                        captureShapeBoxes(level, pos, state), contraptionEntity);
                    if (blockBreakPending) {
                        TerminalBallisticsRecorder.onCriticalBlock(projectile, Vec3.atCenterOf(pos), state);
                    }
                } else if (perforates || blockBreakPending) {
                    // Shell body chewed through a non-armour block; only counts as
                    // internal damage after real armour was hit (recorder-side check).
                    TerminalBallisticsRecorder.onInternalBlockDamage(projectile);
                    if (blockBreakPending) {
                        TerminalBallisticsRecorder.onCriticalBlock(projectile, Vec3.atCenterOf(pos), state);
                    }
                }
            }

            TBDebug.serviceOutcome(outcome, appliedDamage, threshold);
            boolean launcherStop = outcome.equals("STOP") && TestLauncherProjectileCompat.isLauncherProjectile(projectile);
            if (launcherStop) {
                projectile.setDeltaMovement(Vec3.ZERO);
            }
            boolean hasFuze = CBCReflect.hasFuze(projectile);
            // CBC impact fuzes refuse an impact result marked for removal. Let every loaded
            // fuze receive a non-removing result so delayed and timed fuzes can keep ticking.
            Object impactResult = CBCReflect.newImpactResult(outcome, hasFuze ? false : shatter || projectileShatter);
            BlockState renderedProjectileState = outcome.equals("STOP") ? renderedProjectileState(projectile) : null;
            ItemStack renderedProjectileItem = renderedProjectileState == null && outcome.equals("STOP")
                ? CBCReflect.projectileItem(projectile) : ItemStack.EMPTY;
            boolean onImpactRemove = CBCReflect.callOnImpact(projectile, hit, impactResult, projectileContext);

            // For contraption blocks the world has AIR at the position, so the normal
            // "worldBlock.equals(state)" check fails. Use ContraptionCompat to detect
            // whether the block still exists in the contraption at this position.
            boolean worldBlockMatchesState;
            if (isContraption) {
                worldBlockMatchesState = ContraptionCompat.blockStateAt(level, pos) != null;
            } else if (level instanceof ServerLevel srv) {
                worldBlockMatchesState = srv.getBlockState(pos).equals(state);
            } else {
                worldBlockMatchesState = false;
            }

            if (level instanceof ServerLevel server && !worldBlockMatchesState && !isContraption) {
                clearMarks(server, pos);
            }
            if (!onImpactRemove && level instanceof ServerLevel server && worldBlockMatchesState) {
                if (blockBreakPending) {
                    if (isContraption) {
                        // Cannot set world block to air for contraption blocks; the block lives
                        // inside the contraption entity. Clear marks only — the block will be
                        // removed by CBC's own block-break handling or left as damage metadata.
                        clearMarks(server, pos);
                    } else {
                        fireProjectileHitHook(server, state, pos, projectile);
                        server.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        playBreakSound(server, pos, state);
                        clearMarks(server, pos);
                    }
                } else if (temporaryPassagePending && !isContraption) {
                    // Contraption blocks already have air in the world — no phasing needed.
                    TemporaryBlockPassage.phaseForThisTick(server, pos, state);
                }
            }
            boolean embeddedShell = false;
            boolean fuzeLinger = outcome.equals("STOP") && CBCReflect.canLingerInGround(projectile);
            // For contraption blocks, allow embedded shell placement even though the world block
            // is air — the visual and collision are handled by the contraption entity.
            if (outcome.equals("STOP") && !hasFuze && !onImpactRemove && !fuzeLinger && !projectileShatter && level instanceof ServerLevel server
                && worldBlockMatchesState) {
                embeddedShell = addEmbeddedShell(server, pos, state, hit, caliber, curVel,
                    renderedProjectileState, renderedProjectileItem, embeddedDepth(penetrationRatio),
                    contraptionEntity, contraptionLocalPos);
            }
            // Autocannon projectiles must keep flying on a successful perforation.  The previous debug build
            // treated every non-bounce autocannon result as removable, which made AP rounds disappear after
            // punching through light blocks instead of continuing into the block behind them.
            boolean shouldRemove = onImpactRemove || (!hasFuze && (embeddedShell || projectileShatter || (autocannon
                ? (!level.isClientSide && (shatter || outcome.equals("STOP")))
                : shatter)));
            if (level instanceof ServerLevel server) {
                Vec3 authoritativeVelocity = outcome.equals("STOP") ? Vec3.ZERO : projectile.getDeltaMovement();
                Vec3 authoritativePosition = outcome.equals("STOP") ? hit.getLocation() : projectile.position();
                syncImpactOutcome(server, projectile, outcome, authoritativePosition, authoritativeVelocity,
                    outcome.equals("STOP"), pos);
            }
            return CBCReflect.newImpactResult(outcome, shouldRemove);
        } catch (Throwable t) {
            TBDebug.fallback(t);
            CBCTerminalBallistics.LOGGER.error("Terminal ballistics override failed; falling back to CBC behavior", t);
            return null;
        }
    }

    public static LastImpact lastImpact(ServerLevel level, BlockPos pos) {
        return LAST_IMPACTS.get(level.dimension().location().hashCode() * 31L + pos.asLong());
    }

    public static double integrityThreshold(BlockState materialState, MaterialStats material, double armorToughness) {
        return Math.max(0.05, armorToughness * Math.max(0.05, effectiveDuctility(materialState, material, armorToughness)) * TBConfig.INTEGRITY_MULTIPLIER.get());
    }

    public static double degradedToughness(double baseToughness, double savedDamage, double threshold) {
        double damageRatio = threshold <= 0.0D ? 0.0D : Mth.clamp(savedDamage / threshold, 0.0D, 1.0D);
        return baseToughness * Mth.lerp(damageRatio, 1.0D, 0.5D);
    }

    public static void clearMarks(ServerLevel level, BlockPos pos) {
        ArmorIntegritySavedData.get(level).clear(pos);
        syncMarks(level, pos, java.util.List.of());
        syncIntegrityProgress(level, pos, -1);
    }

    public static boolean clearEmbeddedShellsOnly(ServerLevel level, BlockPos pos) {
        if (EmbeddedShellSavedData.get(level).getEntry(level, pos) == null) return false;
        clearEmbeddedShells(level, pos);
        return true;
    }

    public static void clearEmbeddedShells(ServerLevel level, BlockPos pos) {
        EmbeddedShellSavedData.get(level).clear(pos);
        syncEmbeddedShells(level, pos, java.util.List.of());
    }

    /**
     * Clear a removed cleanup entry client-side: contraption-anchored entries are
     * identified to clients by entity UUID + local position, world entries by position.
     */
    public static void clearMarksForEntry(ServerLevel level, ArmorIntegritySavedData.Entry entry) {
        if (entry.contraptionUuid != null && entry.localPos != null) {
            Entity contraption = level.getEntity(entry.contraptionUuid);
            Vec3 cull = contraption != null ? ContraptionCompat.worldCenterForLocal(contraption, entry.localPos, 1.0f) : null;
            sendMarksToNearby(level, new ClientboundImpactMarksPacket(entry.localPos, entry.contraptionUuid,
                entry.localPos, java.util.List.of()), cull != null ? cull : Vec3.atCenterOf(entry.localPos));
        } else {
            clearMarks(level, entry.keyPos());
        }
    }

    public static void clearShellsForEntry(ServerLevel level, EmbeddedShellSavedData.Entry entry) {
        if (entry.contraptionUuid != null && entry.localPos != null) {
            Entity contraption = level.getEntity(entry.contraptionUuid);
            Vec3 cull = contraption != null ? ContraptionCompat.worldCenterForLocal(contraption, entry.localPos, 1.0f) : null;
            syncEmbeddedShellsPacket(level, new ClientboundEmbeddedShellsPacket(entry.localPos, entry.contraptionUuid,
                entry.localPos, java.util.List.of()), cull != null ? cull : Vec3.atCenterOf(entry.localPos));
        } else {
            clearEmbeddedShells(level, entry.keyPos());
        }
    }

    public static boolean clearBlockImpactData(ServerLevel level, BlockPos pos) {
        boolean hadIntegrityData = ArmorIntegritySavedData.get(level).getEntry(level, pos) != null;
        boolean hadShells = EmbeddedShellSavedData.get(level).getEntry(level, pos) != null;
        if (!hadIntegrityData && !hadShells) return false;
        clearMarks(level, pos);
        clearEmbeddedShells(level, pos);
        return true;
    }

    public static void syncEmbeddedShellsToPlayers(ServerLevel level, BlockPos pos, java.util.List<EmbeddedShell> shells) {
        syncEmbeddedShells(level, pos, shells);
    }

    public static void syncEmbeddedShellsToPlayers(ServerLevel level, BlockPos pos, java.util.UUID contraptionUuid, BlockPos localPos, java.util.List<EmbeddedShell> shells) {
        syncEmbeddedShellsPacket(level, new ClientboundEmbeddedShellsPacket(pos, contraptionUuid, localPos, java.util.List.copyOf(shells)),
            Vec3.atCenterOf(localPos));
    }

    public static void syncAllEmbeddedShellsToPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        for (EmbeddedShellSavedData.Entry entry
             : new HashMap<>(EmbeddedShellSavedData.get(level).entriesView()).values()) {
            if (entry.contraptionUuid != null && entry.localPos != null) {
                Entity contraption = level.getEntity(entry.contraptionUuid);
                if (contraption == null || entry.shells.isEmpty()) continue;
                Vec3 center = ContraptionCompat.worldCenterForLocal(contraption, entry.localPos, 1.0f);
                if (center == null || center.distanceToSqr(player.position()) > 128 * 128) continue;
                sendEmbeddedShells(player, new ClientboundEmbeddedShellsPacket(entry.localPos,
                    entry.contraptionUuid, entry.localPos, java.util.List.copyOf(entry.shells)));
            } else {
                BlockPos pos = entry.keyPos();
                Vec3 center = VSCompat.toWorldCoordinates(level, Vec3.atCenterOf(pos));
                if (center.distanceToSqr(player.position()) <= 128 * 128) {
                    sendEmbeddedShells(player, new ClientboundEmbeddedShellsPacket(pos, null, null, java.util.List.copyOf(entry.shells)));
                }
            }
        }
    }

    public static void syncAllImpactMarksToPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        for (ArmorIntegritySavedData.Entry entry
             : new HashMap<>(ArmorIntegritySavedData.get(level).entriesView()).values()) {
            if (entry.marks.isEmpty()) continue;
            if (entry.contraptionUuid != null && entry.localPos != null) {
                Entity contraption = level.getEntity(entry.contraptionUuid);
                if (contraption == null) continue;
                Vec3 center = ContraptionCompat.worldCenterForLocal(contraption, entry.localPos, 1.0f);
                if (center == null || center.distanceToSqr(player.position()) > 128 * 128) continue;
                sendImpactMarks(player, new ClientboundImpactMarksPacket(entry.localPos,
                    entry.contraptionUuid, entry.localPos, java.util.List.copyOf(entry.marks)));
            } else {
                BlockPos pos = entry.keyPos();
                if (ArmorIntegritySavedData.get(level).getEntry(level, pos) == null) continue;
                Vec3 center = VSCompat.toWorldCoordinates(level, Vec3.atCenterOf(pos));
                if (center.distanceToSqr(player.position()) <= 128 * 128) {
                    sendImpactMarks(player, new ClientboundImpactMarksPacket(pos, null, null, java.util.List.copyOf(entry.marks)));
                }
            }
        }
    }

    private static boolean addEmbeddedShell(ServerLevel level, BlockPos pos, BlockState state, BlockHitResult hit,
                                             TBCaliber caliber, Vec3 incomingVelocity, BlockState visualState,
                                             ItemStack visualItem, float depth, Entity contraptionEntity, BlockPos contraptionLocalPos) {
        boolean contraptionAnchored = contraptionEntity != null && contraptionLocalPos != null;
        Vec3 local;
        Vec3 direction;
        Direction face = hit.getDirection();
        if (contraptionAnchored) {
            local = ContraptionCompat.toLocalVector(contraptionEntity, hit.getLocation(), 1.0f);
            Vec3 dirA = ContraptionCompat.toLocalVector(contraptionEntity, hit.getLocation().add(incomingVelocity.scale(0.5)), 1.0f);
            Vec3 dirB = ContraptionCompat.toLocalVector(contraptionEntity, hit.getLocation().subtract(incomingVelocity.scale(0.5)), 1.0f);
            direction = dirA != null && dirB != null ? dirA.subtract(dirB) : Vec3.ZERO;
            Direction localFace = ContraptionCompat.localFaceAt(contraptionEntity, hit.getLocation(), face);
            if (localFace != null) face = localFace;
        } else {
            local = localHitLocation(level, pos, hit.getLocation());
            direction = VSCompat.toShipVector(level, pos, incomingVelocity);
        }
        if (local == null || direction.lengthSqr() < 1.0E-8D) return false;
        direction = direction.normalize();

        BlockPos base = contraptionAnchored ? contraptionLocalPos : pos;
        EmbeddedShell shell = new EmbeddedShell(
            java.util.UUID.randomUUID(), caliber, face,
            (float) Mth.clamp(local.x - base.getX(), 0.001D, 0.999D),
            (float) Mth.clamp(local.y - base.getY(), 0.001D, 0.999D),
            (float) Mth.clamp(local.z - base.getZ(), 0.001D, 0.999D),
            (float) direction.x, (float) direction.y, (float) direction.z,
            visualState,
            visualItem,
            depth,
            level.getGameTime());
        EmbeddedShellSavedData data = EmbeddedShellSavedData.get(level);
        if (contraptionAnchored) {
            EmbeddedShellSavedData.Entry entry = data.addContraption(level, contraptionEntity.getUUID(), contraptionLocalPos, state, shell);
            Vec3 cull = ContraptionCompat.worldCenterForLocal(contraptionEntity, contraptionLocalPos, 1.0f);
            syncEmbeddedShellsPacket(level, new ClientboundEmbeddedShellsPacket(contraptionLocalPos,
                contraptionEntity.getUUID(), contraptionLocalPos, java.util.List.copyOf(entry.shells)),
                cull != null ? cull : Vec3.atCenterOf(contraptionLocalPos));
        } else {
            data.add(level, pos, state, shell);
            syncEmbeddedShells(level, pos, data.entryFor(level, pos, state).shells);
        }
        return true;
    }

    private static BlockState renderedProjectileState(Entity projectile) {
        return CBCReflect.renderedProjectileState(projectile);
    }

    private static float embeddedDepth(double penetrationRatio) {
        return (float) Mth.clamp(0.10D + penetrationRatio * 0.70D, 0.10D, 0.90D);
    }

    private static ShatterDecision projectileShatterDecision(Entity projectile, double velocity, double incidence,
                                                              double penetrationRatio, ImpactMarkKind markKind) {
        if (ProjectileClassifier.shouldBypassTB(projectile) || penetrationRatio >= 1.0D) {
            return new ShatterDecision(0.0D, false);
        }
        double velocityMps = velocity * 20.0D;
        double velocityFactor = Mth.clamp((velocityMps - 60.0D) / 140.0D, 0.0D, 1.0D);
        double energy = Mth.clamp((penetrationRatio - 0.35D) / 0.65D, 0.0D, 1.0D);
        double shallow = markKind == ImpactMarkKind.STREAK
            ? Mth.clamp((RICOCHET_MARK_MAX_INCIDENCE - incidence) / RICOCHET_MARK_MAX_INCIDENCE, 0.0D, 1.0D)
            : 0.0D;
        double chance = Mth.clamp((energy * 0.75D + velocityFactor * 0.45D + shallow * 0.35D)
            * ProjectileClassifier.projectileShatterScale(projectile), 0.0D, 0.98D);
        boolean shattered = chance > 0.0D
            && deterministicImpactRoll(projectile, projectile.blockPosition(), Direction.UP) < chance;
        return new ShatterDecision(chance, shattered);
    }

    public static void syncMarksToPlayers(ServerLevel level, BlockPos pos, java.util.List<ImpactMark> marks) {
        syncMarks(level, pos, marks);
    }

    public static void syncMarksToPlayers(ServerLevel level, BlockPos pos, java.util.UUID contraptionUuid, BlockPos localPos, java.util.List<ImpactMark> marks) {
        sendMarksToNearby(level, new ClientboundImpactMarksPacket(pos, contraptionUuid, localPos, java.util.List.copyOf(marks)),
            Vec3.atCenterOf(localPos));
    }

    private static void addImpactMark(ServerLevel server, BlockPos pos, BlockState state, BlockHitResult hit, ImpactMarkKind kind, TBCaliber caliber, ImpactSurfaceType surface, Vec3 incomingVelocity, Entity contraptionEntity, BlockPos contraptionLocalPos) {
        ImpactMark mark = mark(server, pos, hit, kind, caliber, surface,
            kind == ImpactMarkKind.STREAK ? ricochetMarkRotation(server, pos, hit, incomingVelocity) : 0.0F,
            contraptionEntity, contraptionLocalPos);
        ArmorIntegritySavedData.Entry entry = addMarkEntry(server, ArmorIntegritySavedData.get(server), pos, state, contraptionEntity, contraptionLocalPos, mark);
        syncMarksForEntry(server, entry, pos, contraptionEntity);
    }

    /** Store a mark under the right entry (world position or contraption anchor) and return the entry for syncing. */
    private static ArmorIntegritySavedData.Entry addMarkEntry(ServerLevel server, ArmorIntegritySavedData data, BlockPos pos, BlockState state,
                                                              Entity contraptionEntity, BlockPos contraptionLocalPos, ImpactMark mark) {
        if (mark == null) return null;
        if (contraptionEntity != null && contraptionLocalPos != null) {
            return data.addContraptionMark(server, contraptionEntity.getUUID(), contraptionLocalPos, state, mark);
        }
        data.addMark(server, pos, state, mark);
        return data.entryFor(server, pos, state);
    }

    private static ImpactMark mark(ServerLevel level, BlockPos pos, BlockHitResult hit, ImpactMarkKind kind, TBCaliber caliber, ImpactSurfaceType surface, float rotation,
                                   Entity contraptionEntity, BlockPos contraptionLocalPos) {
        boolean contraptionAnchored = contraptionEntity != null && contraptionLocalPos != null;
        Vec3 loc;
        Direction face = hit.getDirection();
        if (contraptionAnchored) {
            loc = ContraptionCompat.toLocalVector(contraptionEntity, hit.getLocation(), 1.0f);
            if (loc != null) {
                Direction localFace = ContraptionCompat.localFaceAt(contraptionEntity, hit.getLocation(), face);
                if (localFace != null) face = localFace;
            }
        } else {
            loc = null;
        }
        if (loc == null) {
            // For VS ship raycasts the BlockHitResult direction is already the block-local/shipyard face.
            // Transforming it again by worldToShip makes marks flip/rotate incorrectly when the ship is not axis-aligned.
            loc = localHitLocation(level, pos, hit.getLocation());
            if (loc == null) return null;
        }
        BlockPos base = contraptionAnchored && ContraptionCompat.toLocalVector(contraptionEntity, hit.getLocation(), 1.0f) != null
            ? contraptionLocalPos : pos;
        float x = (float) Mth.clamp(loc.x - base.getX(), 0.001, 0.999);
        float y = (float) Mth.clamp(loc.y - base.getY(), 0.001, 0.999);
        float z = (float) Mth.clamp(loc.z - base.getZ(), 0.001, 0.999);
        return new ImpactMark(kind, caliber, surface, face, x, y, z, rotation, level.getGameTime());
    }

    private static float ricochetMarkRotation(ServerLevel level, BlockPos pos, BlockHitResult hit, Vec3 incomingVelocity) {
        Vec3 localVelocity = VSCompat.toShipVector(level, pos, incomingVelocity);
        if (localVelocity.lengthSqr() < 1.0e-8D) return 0.0F;

        Direction face = hit.getDirection();
        double u;
        double v;
        switch (face.getAxis()) {
            case X -> {
                u = localVelocity.z;
                v = localVelocity.y;
            }
            case Y -> {
                u = localVelocity.x;
                v = localVelocity.z;
            }
            case Z -> {
                u = localVelocity.x;
                v = localVelocity.y;
            }
            default -> throw new IncompatibleClassChangeError();
        }
        if (u * u + v * v < 1.0e-8D) return 0.0F;
        double angle = Math.atan2(u, v); // angle from the texture's default upward (+V) direction.
        angle = angle % Math.PI; // Ricochet streak art is up/down symmetric.
        if (angle < 0.0D) angle += Math.PI;
        return (float) angle;
    }

    private static ImpactMark exitMark(ServerLevel level, BlockPos pos, BlockHitResult hit, TBCaliber caliber, ImpactSurfaceType surface,
                                       Entity contraptionEntity, BlockPos contraptionLocalPos) {
        boolean contraptionAnchored = contraptionEntity != null && contraptionLocalPos != null;
        Vec3 entryLoc;
        Direction exitFace = hit.getDirection().getOpposite();
        if (contraptionAnchored) {
            entryLoc = ContraptionCompat.toLocalVector(contraptionEntity, hit.getLocation(), 1.0f);
            Direction entryFace = ContraptionCompat.localFaceAt(contraptionEntity, hit.getLocation(), hit.getDirection());
            if (entryFace != null) exitFace = entryFace.getOpposite();
        } else {
            entryLoc = null;
        }
        if (entryLoc == null) entryLoc = localHitLocation(level, pos, hit.getLocation());
        if (entryLoc == null) return null;
        BlockPos base = contraptionAnchored && ContraptionCompat.toLocalVector(contraptionEntity, hit.getLocation(), 1.0f) != null
            ? contraptionLocalPos : pos;
        double x = entryLoc.x - base.getX();
        double y = entryLoc.y - base.getY();
        double z = entryLoc.z - base.getZ();
        switch (exitFace) {
            case DOWN -> y = 0.001D;
            case UP -> y = 0.999D;
            case NORTH -> z = 0.001D;
            case SOUTH -> z = 0.999D;
            case WEST -> x = 0.001D;
            case EAST -> x = 0.999D;
        }
        return new ImpactMark(ImpactMarkKind.EXIT_HOLE, caliber, surface, exitFace,
            (float) Mth.clamp(x, 0.001D, 0.999D),
            (float) Mth.clamp(y, 0.001D, 0.999D),
            (float) Mth.clamp(z, 0.001D, 0.999D),
            0.0F,
            level.getGameTime());
    }

    private static Vec3 localHitLocation(ServerLevel level, BlockPos pos, Vec3 hitLocation) {
        // CBC receives a world-space hit location while VS supplies the impacted
        // ship block position in shipyard coordinates. Always normalize through
        // VS instead of guessing the coordinate space from numeric bounds.
        return VSCompat.toShipCoordinates(level, pos, hitLocation);
    }

    private static void syncMarks(ServerLevel level, BlockPos pos, java.util.List<ImpactMark> marks) {
        sendMarksToNearby(level, new ClientboundImpactMarksPacket(pos, null, null, java.util.List.copyOf(marks)), Vec3.atCenterOf(pos));
    }

    private static void syncMarksForEntry(ServerLevel level, ArmorIntegritySavedData.Entry entry, BlockPos pos, Entity contraptionEntity) {
        if (entry == null || entry.marks.isEmpty()) return;
        if (entry.contraptionUuid != null && entry.localPos != null) {
            ClientboundImpactMarksPacket packet = new ClientboundImpactMarksPacket(pos, entry.contraptionUuid, entry.localPos,
                java.util.List.copyOf(entry.marks));
            Vec3 cull = contraptionEntity != null ? ContraptionCompat.worldCenterForLocal(contraptionEntity, entry.localPos, 1.0f) : null;
            sendMarksToNearby(level, packet, cull != null ? cull : Vec3.atCenterOf(entry.localPos));
        } else {
            syncMarks(level, pos, entry.marks);
        }
    }

    private static void sendMarksToNearby(ServerLevel level, ClientboundImpactMarksPacket packet, Vec3 cullCenter) {
        if (TBNetwork.CHANNEL == null) return;
        Vec3 markCenterWorld = VSCompat.toWorldCoordinates(level, cullCenter);
        for (ServerPlayer player : level.players()) {
            if (markCenterWorld.distanceToSqr(player.position()) <= 128 * 128) {
                TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
        }
    }

    private static void sendImpactMarks(ServerPlayer player, ClientboundImpactMarksPacket packet) {
        TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static void syncEmbeddedShells(ServerLevel level, BlockPos pos, java.util.List<EmbeddedShell> shells) {
        syncEmbeddedShellsPacket(level, new ClientboundEmbeddedShellsPacket(pos.immutable(), null, null, java.util.List.copyOf(shells)),
            Vec3.atCenterOf(pos));
    }

    private static void syncEmbeddedShellsPacket(ServerLevel level, ClientboundEmbeddedShellsPacket packet, Vec3 cullCenter) {
        if (TBNetwork.CHANNEL == null) return;
        Vec3 center = VSCompat.toWorldCoordinates(level, cullCenter);
        for (ServerPlayer player : level.players()) {
            if (center.distanceToSqr(player.position()) <= 128 * 128) {
                sendEmbeddedShells(player, packet);
            }
        }
    }

    private static void sendEmbeddedShells(ServerPlayer player, BlockPos pos, java.util.List<EmbeddedShell> shells) {
        sendEmbeddedShells(player, new ClientboundEmbeddedShellsPacket(pos.immutable(), null, null, java.util.List.copyOf(shells)));
    }

    private static void sendEmbeddedShells(ServerPlayer player, ClientboundEmbeddedShellsPacket packet) {
        TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static void syncIntegrityProgress(ServerLevel level, BlockPos pos, double damage, double threshold) {
        int stage = damage <= 0.0D || threshold <= 0.0D ? -1 : Mth.clamp((int) Math.floor((damage / threshold) * 10.0D), 0, 9);
        syncIntegrityProgress(level, pos, stage);
    }

    private static void syncIntegrityProgress(ServerLevel level, BlockPos pos, int stage) {
        if (TBNetwork.CHANNEL == null) return;
        ClientboundIntegrityProgressPacket packet = new ClientboundIntegrityProgressPacket(pos, stage);
        Vec3 markCenterShipyard = Vec3.atCenterOf(pos);
        Vec3 markCenterWorld = VSCompat.toWorldCoordinates(level, markCenterShipyard);
        for (ServerPlayer player : level.players()) {
            if (markCenterWorld.distanceToSqr(player.position()) <= 128 * 128) {
                TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
        }
    }

    private static void sendSpallCone(ServerLevel level, Entity projectile, Vec3 origin, Vec3 dir, double coneCos, double range,
                                       int visualFragments, int fragments, double massRatio, TBCaliber caliber) {
        if (TBNetwork.CHANNEL == null) return;
        visualFragments = Math.min(visualFragments, 24);
        if (visualFragments <= 0) return;
        float intensity = (float) Mth.clamp(0.65D + massRatio * 1.0D + caliber.ordinal() * 0.12D, 0.45F, 2.25F);
        long seed = spallVisualSeed(level, projectile, origin, dir, fragments);
        Vec3 worldOrigin = VSCompat.toWorldCoordinates(level, origin);
        ClientboundSpallConePacket packet = new ClientboundSpallConePacket(worldOrigin, dir.normalize(), coneCos, range,
            visualFragments, seed, intensity, caliber);
        for (ServerPlayer player : level.players()) {
            if (worldOrigin.distanceToSqr(player.position()) <= 128 * 128) {
                TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
        }
    }

    private static long spallVisualSeed(ServerLevel level, Entity projectile, Vec3 origin, Vec3 dir, int fragments) {
        long seed = 0xcbc7b41115f5a11L;
        seed = mixSeed(seed, level.getGameTime());
        seed = mixSeed(seed, projectile.getUUID().getMostSignificantBits());
        seed = mixSeed(seed, projectile.getUUID().getLeastSignificantBits());
        seed = mixSeed(seed, Double.doubleToLongBits(origin.x));
        seed = mixSeed(seed, Double.doubleToLongBits(origin.y));
        seed = mixSeed(seed, Double.doubleToLongBits(origin.z));
        seed = mixSeed(seed, Double.doubleToLongBits(dir.x));
        seed = mixSeed(seed, Double.doubleToLongBits(dir.y));
        seed = mixSeed(seed, Double.doubleToLongBits(dir.z));
        return mixSeed(seed, fragments);
    }

    private static long mixSeed(long seed, long value) {
        seed ^= value;
        return seed * 0x100000001b3L;
    }

    private static double deterministicImpactRoll(Entity projectile, BlockPos pos, Direction face) {
        long seed = 0x9E3779B97F4A7C15L;
        seed = mixSeed(seed, projectile.getUUID().getMostSignificantBits());
        seed = mixSeed(seed, projectile.getUUID().getLeastSignificantBits());
        seed = mixSeed(seed, pos.asLong());
        seed = mixSeed(seed, face.ordinal());
        long mixed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static void syncImpactOutcome(ServerLevel level, Entity projectile, String outcome, Vec3 position,
                                          Vec3 velocity, boolean inGround, BlockPos blockPos) {
        if (TBNetwork.CHANNEL == null) return;
        ClientboundImpactOutcomePacket packet = new ClientboundImpactOutcomePacket(
            projectile.getId(), outcome, position, velocity, inGround, blockPos.immutable());
        for (ServerPlayer player : level.players()) {
            if (position.distanceToSqr(player.position()) <= 128 * 128) {
                TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
        }
    }

    private static void playBreakSound(ServerLevel level, BlockPos pos, BlockState state) {
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getBreakSound(), SoundSource.BLOCKS, sound.getVolume(), sound.getPitch());
    }

    //this function doesnt quite do anything now
    private static void playHardBlockImpactSound(ServerLevel level, BlockPos pos, double armorToughness, TBCaliber caliber, double velocity) {
        if (armorToughness < HARD_BLOCK_IMPACT_SOUND_TOUGHNESS) return;
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(CBC_PROJECTILE_IMPACT_SOUND);
        if (sound == null) return;
        float caliberVolume = switch (caliber) {
            case AUTOCANNON -> 0.55F;
            case HEAVY_AUTOCANNON, SMALL, SMALL_MEDIUM -> 0.8F;
            case MEDIUM -> 1.05F;
            case BIG -> 1.35F;
        };
        float toughnessVolume = (float) Mth.clamp(armorToughness / HARD_BLOCK_IMPACT_SOUND_TOUGHNESS, 1.0D, 1.8D);
        float velocityPitch = (float) Mth.clamp(0.95D + velocity * 0.01D, 0.85D, 1.25D);
        //level.playSound(null, pos, sound, SoundSource.BLOCKS, caliberVolume * toughnessVolume, velocityPitch);
    }

    /**
     * Stop distances for the terminal-view display rays. The client draws a
     * deterministic golden-spiral cone sample — this is this class's own
     * {@code fragmentDirection} with jitter 0 — so raycasting those same
     * directions here lets the drawn rays terminate exactly at the geometry
     * that stopped them. Display-only: the real (jittered) damage rays above
     * are unaffected.
     */
    private static float[] computeVisualRayStops(ServerLevel level, Entity projectile,
                                                 @Nullable ContraptionCompat.ContraptionSnapshot contraption,
                                                 Vec3 origin, Vec3 dir, double coneCos, double range, int fragments) {
        int rayCount = Math.min(12, Math.max(3, fragments / 2));
        float[] stops = new float[rayCount];
        for (int i = 0; i < rayCount; i++) {
            Vec3 rayDir = fragmentDirection(dir, i, rayCount, coneCos, 0.0D);
            Vec3 end = origin.add(rayDir.scale(range));
            HitResult blockRay = level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile));
            double dist = blockRay.getType() == HitResult.Type.MISS ? range : blockRay.getLocation().distanceTo(origin);
            if (contraption != null) {
                ContraptionCompat.ContraptionSnapshot.Hit hullHit = contraption.raycast(origin, end);
                if (hullHit != null && hullHit.distance() < dist) dist = hullHit.distance();
            }
            stops[i] = (float) Math.min(range, Math.max(0.0D, dist));
        }
        return stops;
    }

    /**
     * Spall ejects from the surface the shell actually exits, not a fixed offset:
     * thin plates (copycat armor layers are 2-16 px thick) otherwise spawn fragments
     * a full block behind the real plate, skipping the block directly behind it.
     * The exit point is the far side of the impacted block's voxel shape along the
     * travel direction. For contraption blocks the ray and shape are handled in the
     * contraption's local frame (rigid transform, exact axis-aligned local cells).
     * Falls back to the legacy fixed 1.05-block offset whenever geometry cannot be
     * resolved (BE-dependent shapes without a block entity, grazing float edges).
     */
    private static Vec3 computeSpallOrigin(Level level, BlockState state, BlockPos pos, Vec3 hitLoc, Vec3 velDir,
                                           boolean isContraption, @Nullable Entity contraptionEntity,
                                           @Nullable BlockPos contraptionLocalPos) {
        Vec3 fallback = hitLoc.add(velDir.scale(1.05));
        try {
            VoxelShape shape;
            Vec3 cellOrigin;
            Vec3 cellDir;
            if (isContraption) {
                if (contraptionEntity == null || contraptionLocalPos == null) return fallback;
                Vec3 localHit = ContraptionCompat.worldToLocal(contraptionEntity, hitLoc, 1.0f);
                Vec3 localAhead = ContraptionCompat.worldToLocal(contraptionEntity, hitLoc.add(velDir), 1.0f);
                if (localHit == null || localAhead == null) return fallback;
                Vec3 localDir = localAhead.subtract(localHit);
                if (localDir.lengthSqr() < 1e-8) return fallback;
                cellDir = localDir.normalize();
                // The contraption local frame is absolute; shape boxes are 0..1 block
                // space. Rebase the ray into the struck block's cell so the slab test
                // sees comparable magnitudes.
                cellOrigin = localHit.subtract(contraptionLocalPos.getX(), contraptionLocalPos.getY(), contraptionLocalPos.getZ());
                shape = contraptionShape(state, contraptionEntity, contraptionLocalPos);
            } else {
                cellOrigin = hitLoc.subtract(pos.getX(), pos.getY(), pos.getZ());
                cellDir = velDir;
                shape = state.getShape(level, pos);
            }
            if (shape.isEmpty()) return fallback;
            final Vec3 rayO = cellOrigin;
            final Vec3 rayD = cellDir;
            double[] exitT = {-1.0D};
            shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
                double t = rayBoxExit(rayO, rayD, x0, y0, z0, x1, y1, z1);
                if (t > exitT[0]) exitT[0] = t;
            });
            if (exitT[0] <= 0.0D) return fallback;
            double t = Math.min(exitT[0] + 0.05D, 2.0D);
            Vec3 cellExit = cellOrigin.add(cellDir.scale(t));
            if (isContraption) {
                Vec3 localExit = cellExit.add(contraptionLocalPos.getX(), contraptionLocalPos.getY(), contraptionLocalPos.getZ());
                Vec3 worldExit = ContraptionCompat.localToWorld(contraptionEntity, localExit, 1.0f);
                return worldExit != null ? worldExit : fallback;
            }
            return cellExit.add(pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Shape of a contraption block. Contraptions host no live BlockEntities
     * (Create 6 dropped the live map entirely), so BE-dependent shapes — framed
     * collapsible armor — are rebuilt from the packed offsets serialized in the
     * block's saved NBT. Everything else (copycat layer shapes are pure
     * block-state functions) resolves against an empty getter.
     */
    private static VoxelShape contraptionShape(BlockState state, Entity contraptionEntity, BlockPos localPos) {
        if (state.getBlock() instanceof FramedCollapsibleCopycatArmorBlock) {
            CompoundTag nbt = ContraptionCompat.blockInfoNbtAt(contraptionEntity, localPos);
            if (nbt != null && nbt.contains(ArmorCopycatItemData.OFFSETS_TAG)) {
                return FramedCollapsibleCopycatArmorBlock.shapeForOffsets(
                    ArmorCopycatItemData.sanitizeOffsets(nbt.getInt(ArmorCopycatItemData.OFFSETS_TAG)));
            }
        }
        return state.getShape(EmptyBlockGetter.INSTANCE, localPos);
    }

    /**
     * Far-side exit distance of a ray through an axis-aligned box in block-local
     * coordinates, or -1 when the ray never passes through the box interior.
     */
    private static double rayBoxExit(Vec3 origin, Vec3 dir, double x0, double y0, double z0, double x1, double y1, double z1) {
        double tMin = -1.0D;
        double tMax = Double.POSITIVE_INFINITY;
        double[][] axes = {
            {origin.x, dir.x, x0, x1},
            {origin.y, dir.y, y0, y1},
            {origin.z, dir.z, z0, z1},
        };
        for (double[] axis : axes) {
            double o = axis[0], d = axis[1], lo = axis[2], hi = axis[3];
            if (Math.abs(d) < 1e-9) {
                if (o < lo || o > hi) return -1.0D;
                continue;
            }
            double t0 = (lo - o) / d;
            double t1 = (hi - o) / d;
            if (t0 > t1) { double tmp = t0; t0 = t1; t1 = tmp; }
            tMin = Math.max(tMin, t0);
            tMax = Math.min(tMax, t1);
            if (tMin > tMax) return -1.0D;
        }
        // The ray must actually cross the box: exit ahead of the start, entry not far behind it.
        if (tMax < 1e-6 || tMin > 0.5D) return -1.0D;
        return tMax;
    }

    private static int spawnSpall(ServerLevel level, Entity projectile, Vec3 origin, Vec3 dir, double velMag, TBCaliber caliber, double massBefore, double massAfter, MaterialStats material, double armorToughness, Entity contraptionEntity) {        double countMultiplier = TBConfig.GLOBAL_SPALL_MULTIPLIER.get() * material.spallMultiplier() * caliber.spallScale * ProjectileClassifier.shellSpallCountModifier(projectile);
        int fragments = Mth.clamp((int) Math.round(armorToughness * countMultiplier), 0, TBConfig.MAX_SPALL_FRAGMENTS.get());
        if (fragments <= 0) return 0;
        double massRatio = massBefore > 0.0 ? massAfter / massBefore : 0.0;
        double range = Mth.clamp(5.0 + massRatio * 12.0 + caliber.ordinal() * 1.5, 5.0, 24.0);

        // For contraption impacts, build a world-space snapshot of the hull once so every
        // fragment ray can be blocked by contraption blocks (vanilla level.clip cannot see
        // them). Detection uses the impact-resolved entity, NOT the origin block: the
        // origin is deliberately advanced past the impact point and often lands outside
        // the hull, which made the old BlockPos-based detection miss.
        ContraptionCompat.ContraptionSnapshot contraption = contraptionEntity != null ? ContraptionCompat.snapshot(contraptionEntity) : null;
        boolean isContraptionOrigin = contraption != null;
        if (isContraptionOrigin) {
            range = Math.min(range, 4.0);
        }

        double baselineVel = TBConfig.SPALL_CONE_VELOCITY_BASELINE.get();
        double minAngle = TBConfig.SPALL_CONE_MIN_ANGLE.get();
        double maxAngle = TBConfig.SPALL_CONE_MAX_ANGLE.get();
        double velocityFactor = Mth.clamp(1.0 - (velMag - baselineVel) / 400.0, 0.0, 1.0);
        double massRatioFactor = 1.0 - massRatio;
        double toughnessFactor = Mth.clamp(armorToughness / 40.0, 0.0, 1.0);
        double caliberFactor = caliber.ordinal() / 4.0;
        double shellTypeFactor = ProjectileClassifier.shellSpallConeModifier(projectile);
        double combined = velocityFactor * 0.15 + massRatioFactor * 0.25 + toughnessFactor * 0.25 + caliberFactor * 0.15 + shellTypeFactor * 0.20;
        double coneAngleDeg = Mth.lerp(combined, minAngle, maxAngle);
        double coneCos = Math.cos(Math.toRadians(coneAngleDeg));
        TerminalBallisticsRecorder.onSpall(projectile, origin, dir, coneCos, range, fragments,
            computeVisualRayStops(level, projectile, contraption, origin, dir, coneCos, range, fragments));

        AABB box = new AABB(origin, origin.add(dir.scale(range))).inflate(range * 0.55 + 1.0);
        Entity owner = projectile instanceof Projectile proj ? proj.getOwner() : null;
        double damageModifier = ProjectileClassifier.shellSpallDamageModifier(projectile);

        java.util.List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != owner);
        Map<LivingEntity, Integer> rayHits = new HashMap<>();
        double spallToughnessThreshold = TBConfig.SPALL_INTEGRITY_DAMAGE_TOUGHNESS_THRESHOLD.get();
        double spallDamageScale = TBConfig.SPALL_INTEGRITY_DAMAGE_SCALE.get();
        boolean visualOriginInsideArmor = isCatchingArmorAt(level, origin);
        int visualClearRays = 0;
        double visualRange = 0.0D;
        for (int i = 0; i < fragments; i++) {
            Vec3 fragDir = fragmentDirection(dir, i, fragments, coneCos, level.random.nextDouble());
            Vec3 end = origin.add(fragDir.scale(range));
            HitResult blockRay = level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile));
            double blockDistSqr = blockRay.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY : blockRay.getLocation().distanceToSqr(origin);
            // Contraption hulls are invisible to vanilla clip; find the nearest hull block
            // along the fragment ray so it can stop the fragment like a real block would.
            ContraptionCompat.ContraptionSnapshot.Hit contraptionHit = contraption != null ? contraption.raycast(origin, end) : null;
            double contraptionDistSqr = contraptionHit == null ? Double.POSITIVE_INFINITY : contraptionHit.distance() * contraptionHit.distance();
            double stopDistSqr = Math.min(blockDistSqr, contraptionDistSqr);
            if (!visualOriginInsideArmor) {
                double visualClearance = stopDistSqr == Double.POSITIVE_INFINITY ? range : Math.sqrt(stopDistSqr);
                if (visualClearance >= MIN_SPALL_VISUAL_CLEARANCE) {
                    visualClearRays++;
                    visualRange = Math.max(visualRange, Math.min(range, visualClearance));
                }
            }

            for (LivingEntity entity : candidates) {
                java.util.Optional<Vec3> hitPoint = entity.getBoundingBox().inflate(0.18).clip(origin, end);
                if (hitPoint.isPresent() && hitPoint.get().distanceToSqr(origin) + 0.04 < stopDistSqr) {
                    rayHits.merge(entity, 1, Integer::sum);
                }
            }

            if (contraptionHit != null && contraptionDistSqr <= blockDistSqr) {
                // Fragment stopped by a contraption hull block: integrity damage accumulates,
                // but the block itself is never removed (it lives inside the entity).
                boolean broken = applyFragmentBlockDamage(level, projectile, caliber, fragments, massRatio, damageModifier,
                    contraptionHit.worldPos(), contraptionHit.state(), spallToughnessThreshold, spallDamageScale);
                TerminalBallisticsRecorder.onSpallBlock(projectile, Vec3.atCenterOf(contraptionHit.worldPos()), broken,
                    isArmorLike(contraptionHit.state()) || isMetalLike(contraptionHit.state()) || isVehiclePanelLike(contraptionHit.state()));
                if (broken) {
                    TerminalBallisticsRecorder.onCriticalBlock(projectile, Vec3.atCenterOf(contraptionHit.worldPos()), contraptionHit.state());
                }
            } else if (blockRay instanceof BlockHitResult bhr && blockRay.getType() == HitResult.Type.BLOCK) {
                BlockPos bp = bhr.getBlockPos();
                BlockState st = level.getBlockState(bp);
                // For contraption blocks, the world has air — check the contraption
                if (st.isAir()) {
                    BlockState contraState = ContraptionCompat.blockStateAt(level, bp);
                    if (contraState != null) st = contraState;
                    else continue;
                }
                boolean broken = applyFragmentBlockDamage(level, projectile, caliber, fragments, massRatio, damageModifier,
                    bp, st, spallToughnessThreshold, spallDamageScale);
                TerminalBallisticsRecorder.onSpallBlock(projectile, Vec3.atCenterOf(bp), broken,
                    isArmorLike(st) || isMetalLike(st) || isVehiclePanelLike(st));
                if (broken) {
                    TerminalBallisticsRecorder.onCriticalBlock(projectile, Vec3.atCenterOf(bp), st);
                }
            }
        }
        if (visualClearRays > 0 && visualRange >= MIN_SPALL_VISUAL_CLEARANCE) {
            sendSpallCone(level, projectile, origin, dir, coneCos, visualRange, Math.min(fragments, visualClearRays), fragments, massRatio, caliber);
        }

        // Then apply cone lethality.  This keeps spall dangerous even when the finite ray sample does not
        // geometrically intersect a player's small AABB, while the LOS check still prevents damage through armor.
        for (LivingEntity entity : candidates) {
            Vec3 to = entity.getEyePosition().subtract(origin);
            double dist = Math.max(0.5, to.length());
            double cone = to.normalize().dot(dir);
            if (cone < coneCos) continue;
            Vec3 eye = entity.getEyePosition();
            double eyeDistSqr = eye.distanceToSqr(origin);
            HitResult occlusion = level.clip(new ClipContext(origin, eye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile));
            double occlusionDistSqr = occlusion.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY : occlusion.getLocation().distanceToSqr(origin);
            // Contraption hull blocks occlude spall lethality even though vanilla clip misses them.
            if (contraption != null) {
                double hullDist = contraption.rayDistance(origin, eye);
                occlusionDistSqr = Math.min(occlusionDistSqr, hullDist * hullDist);
            }
            if (occlusionDistSqr + 0.25 < eyeDistSqr) continue;
            int directHits = rayHits.getOrDefault(entity, 0);
            double coneStrength = cone * cone;
            float damage = (float) (Mth.clamp((massRatio * 15.0 + fragments * 1.15) * coneStrength / Math.sqrt(dist) + directHits * 3.5, 4.0, 48.0) * damageModifier);
            // Attribute the kill to the shell so LivingDeathEvent carries the
            // projectile as direct entity (generic() has none and crew-kill
            // criticals would never fire).
            entity.hurt(level.damageSources().mobProjectile(projectile, null), damage);
            TerminalBallisticsRecorder.onSpallEntityHit(projectile, entity, damage);
        }
        return fragments;
    }

    /**
     * Fires a destroyed block's "shot by a projectile" reaction before it is
     * replaced with air. Mod blocks route cook-off logic through
     * {@code Block.onProjectileHit} (combatgear ammo racks detonate this way),
     * which vanilla never calls on a raw setBlock — and our break paths are
     * not vanilla explosions, so {@code wasExploded} never fires either.
     */
    private static void fireProjectileHitHook(ServerLevel level, BlockState state, BlockPos pos, Entity projectile) {
        if (!(projectile instanceof net.minecraft.world.entity.projectile.Projectile proj)) return;
        try {
            state.onProjectileHit(level, state,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), proj);
        } catch (Throwable t) {
            CTB_LOG.warn("onProjectileHit hook failed at {}: {}", pos, t.toString());
        }
    }

    /**
     * Apply spall fragment integrity damage to a single blocking block (world or
     * contraption hull). The block is never set to air when it belongs to a
     * contraption; damage still accumulates and break sounds play.
     *
     * @return true when this fragment pushed the block past its integrity threshold.
     */
    private static boolean applyFragmentBlockDamage(ServerLevel level, Entity projectile, TBCaliber caliber, int fragments, double massRatio, double damageModifier,
                                                 BlockPos bp, BlockState st, double spallToughnessThreshold, double spallDamageScale) {
        float speed = st.getDestroySpeed(level, bp);
        if (speed < 0) return false;
        double localArmor = localEffectiveToughness(level, st, bp);
        if (localArmor > spallToughnessThreshold) return false;
        if (spallDamageScale > 0.0 && localArmor > 0.0) {
            double baseDamage = localArmor * caliberIntegrityWear(caliber) * spallDamageScale * damageModifier;
            double fragmentShare = Math.max(1.0, fragments / 8.0);
            double fragmentDamage = baseDamage * massRatio / fragmentShare;
            ArmorIntegritySavedData data = ArmorIntegritySavedData.get(level);
            data.addDamage(level, bp, st, fragmentDamage);
            BlockState materialState = CopycatMaterialResolver.resolve(level, bp, st, null).orElse(st);
            MaterialStats blockMaterial = MaterialManager.INSTANCE.get(materialState, localArmor);
            double threshold = integrityThreshold(materialState, blockMaterial, localArmor);
            double currentDamage = data.damage(level, bp, st);
            syncIntegrityProgress(level, bp, currentDamage, threshold);
            if (currentDamage >= threshold) {
                if (!ContraptionCompat.isContraptionBlock(level, bp)) {
                    fireProjectileHitHook(level, st, bp, projectile);
                    level.setBlock(bp, Blocks.AIR.defaultBlockState(), 3);
                }
                playBreakSound(level, bp, st);
                clearMarks(level, bp);
                return true;
            }
        }
        return false;
    }

    private static boolean isCatchingArmorAt(ServerLevel level, Vec3 pos) {
        BlockPos blockPos = BlockPos.containing(pos);
        BlockState state = level.getBlockState(blockPos);
        if (state.isAir()) return false;
        if (localEffectiveToughness(level, state, blockPos) < 5.0D) return false;
        // The origin must be embedded in the block's actual geometry, not merely in
        // its cell: thin plates (copycat layers) leave the spall origin inside a
        // mostly empty cell, and their rear-face spall cone must still render.
        Vec3 local = pos.subtract(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        boolean[] inside = {false};
        state.getShape(level, blockPos).forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            if (!inside[0] && local.x >= x0 && local.x <= x1 && local.y >= y0 && local.y <= y1 && local.z >= z0 && local.z <= z1) {
                inside[0] = true;
            }
        });
        return inside[0];
    }

    private static Vec3 fragmentDirection(Vec3 forward, int index, int count, double coneCos, double jitter) {
        Vec3 f = forward.normalize();
        Vec3 up = Math.abs(f.y) < 0.92 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 right = f.cross(up).normalize();
        Vec3 orthoUp = right.cross(f).normalize();
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        double t = (index + 0.5) / Math.max(1, count);
        // Bias toward the center of the cone so targets directly behind the plate receive several fragments.
        double cos = 1.0 - (1.0 - coneCos) * t * t;
        double sin = Math.sqrt(Math.max(0.0, 1.0 - cos * cos));
        double phi = index * golden + jitter * 0.35;
        return f.scale(cos).add(right.scale(Math.cos(phi) * sin)).add(orthoUp.scale(Math.sin(phi) * sin)).normalize();
    }

    private static double localEffectiveToughness(ServerLevel level, BlockState state, BlockPos pos) {
        double base = CBCReflect.armorToughness(level, state, pos, Math.max(0.0, state.getBlock().getExplosionResistance()));
        BlockState materialState = CopycatMaterialResolver.resolve(level, pos, state, null).orElse(state);
        MaterialStats material = MaterialManager.INSTANCE.get(materialState);
        double threshold = integrityThreshold(materialState, material, base);
        ArmorIntegritySavedData.Entry entry = ArmorIntegritySavedData.get(level).getEntry(level, pos);
        double damage = entry == null ? 0.0D : entry.damage;
        return degradedToughness(base, damage, threshold);
    }

    private static double caliberIntegrityWear(TBCaliber caliber) {
        return switch (caliber) {
            case AUTOCANNON -> 0.15;
            case HEAVY_AUTOCANNON, SMALL -> 0.625;
            case SMALL_MEDIUM -> 0.85;
            case MEDIUM -> 1.25;
            case BIG -> 2.50;
        };
    }

    private static double effectiveDuctility(BlockState state, MaterialStats material, double cbcToughness) {
        // Explicit datapack/fallback material entries own their ductility. For unclassified blocks,
        // infer only broad material behavior from id/toughness so ductility scales sensibly with CBC toughness.
        if (material != MaterialStats.DEFAULT) return material.ductility();
        double scale = Math.sqrt(Math.max(1.0, cbcToughness) / 20.0);
        if (isArmorLike(state)) return Mth.clamp(5.0 * scale, 2.5, 8.0);
        if (isMetalLike(state)) return Mth.clamp(3.0 * scale, 1.5, 5.0);
        if (isSoilLike(state)) return Mth.clamp(1.35 * scale, 0.8, 2.5);
        if (isMasonryLike(state)) return Mth.clamp(1.50 * scale, 0.9, 3.0);
        return cbcToughness >= 8.0 ? 0.75 : 0.35;
    }

    private static boolean shouldBreakImmediately(BlockState state, MaterialStats material, double cbcToughness, double attack, boolean bounced) {
        if (bounced || attack <= 0) return false;
        // Blocks below 8 CBC toughness/blast resistance should not use the persistent armor-integrity model
        // unless a datapack intentionally gives them high ductility.
        if (cbcToughness < 8.0 && effectiveDuctility(state, material, cbcToughness) <= 1.25) return true;
        // Above that, only armor/metal/soil/masonry-style blocks get multi-hit survivability by default.
        // Other blocks are treated as fragile/internal structure and break immediately on a cannon hit.
        return !isDurableIntegrityMaterial(state) && effectiveDuctility(state, material, cbcToughness) <= 1.25;
    }

    private static boolean isDurableIntegrityMaterial(BlockState state) {
        return isArmorLike(state) || isMetalLike(state) || isSoilLike(state) || isMasonryLike(state);
    }

    /**
     * Captures the struck block's voxel shape at the impact site. Copycat and
     * framed blocks derive their shape from their block entity, which needs
     * real level+pos context the client rarely has (ship blocks especially),
     * so the boxes travel with the terminal packet. Empty array = not captured.
     */
    private static float[][] captureShapeBoxes(Level level, BlockPos pos, BlockState state) {
        try {
            List<float[]> boxes = new ArrayList<>();
            state.getShape(level, pos).forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
                if (boxes.size() < 8) {
                    boxes.add(new float[]{(float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1});
                }
            });
            return boxes.toArray(new float[0][]);
        } catch (Throwable ignored) {
            return new float[0][];
        }
    }

    private static boolean isArmorLike(BlockState state) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String ns = id.getNamespace();
        String path = id.getPath();
        return ns.equals("rha") || ns.equals("s_a_b") || path.contains("armor") || path.contains("armour") || path.contains("rha");
    }

    /**
     * Vehicle hull skins that are neither classic armour nor metal blocks:
     * Copycats+ shapes, Create copycat panels/steps and Framed Blocks. Tanks are
     * usually dressed in these, so hits on them belong in the terminal view too.
     */
    private static boolean isVehiclePanelLike(BlockState state) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String ns = id.getNamespace();
        if (ns.equals("copycats") || ns.equals("framedblocks")) return true;
        String path = id.getPath();
        return path.contains("copycat") || path.contains("framed");
    }

    private static boolean isMetalLike(BlockState state) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("steel") || path.contains("iron") || path.contains("copper") || path.contains("bronze")
            || path.contains("brass") || path.contains("netherite") || path.contains("metal");
    }

    private static boolean isSoilLike(BlockState state) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("dirt") || path.contains("sand") || path.contains("gravel") || path.contains("mud")
            || path.contains("clay") || path.contains("soil") || path.contains("grass_block") || path.contains("podzol");
    }

    private static boolean isMasonryLike(BlockState state) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("stone") || path.contains("deepslate") || path.contains("cobble") || path.contains("brick")
            || path.contains("concrete") || path.contains("basalt") || path.contains("tuff") || path.contains("andesite")
            || path.contains("diorite") || path.contains("granite") || path.contains("blackstone") || path.contains("obsidian");
    }

    public record LastImpact(String outcome, double damage, double threshold, TBCaliber caliber, MaterialStats material,
                              double massBefore, double massAfter, double velocity, double incidence,
                              double armorToughness, double armorHardness, double effectiveToughness,
                              double attack, double resistance, double penetrationRatio, double massLoss,
                              int spallFragments, double spallDamageModifier, String spallReason,
                              double shatterChance, boolean shattered) {}

    private record ShatterDecision(double chance, boolean shattered) {}

    private static double calculateRicochetMassLoss(double velocity, double incidence, double toughness) {
        double minLoss = TBConfig.RICOCHET_MASS_LOSS_MIN.get();
        double maxLoss = TBConfig.RICOCHET_MASS_LOSS_MAX.get();
        double velScale = TBConfig.RICOCHET_MASS_LOSS_VELOCITY_SCALE.get();
        double velocityFactor = Math.min(velocity / velScale, 1.0);
        double incidenceFactor = incidence;
        double toughnessFactor = Math.min(toughness / 20.0, 1.0);
        double combined = velocityFactor * 0.4 + incidenceFactor * 0.35 + toughnessFactor * 0.25;
        return Mth.lerp(combined, minLoss, maxLoss);
    }

    private TBImpactService() {}
}
