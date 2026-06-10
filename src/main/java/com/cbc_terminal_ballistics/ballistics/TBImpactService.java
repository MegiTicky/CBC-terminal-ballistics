package com.cbc_terminal_ballistics.ballistics;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.compat.TestLauncherProjectileCompat;
import com.cbc_terminal_ballistics.data.CopycatMaterialResolver;
import com.cbc_terminal_ballistics.data.MaterialManager;
import com.cbc_terminal_ballistics.data.MaterialStats;
import com.cbc_terminal_ballistics.debug.TBDebug;
import com.cbc_terminal_ballistics.network.ClientboundImpactMarksPacket;
import com.cbc_terminal_ballistics.network.ClientboundIntegrityProgressPacket;
import com.cbc_terminal_ballistics.network.ClientboundSpallConePacket;
import com.cbc_terminal_ballistics.network.TBNetwork;
import com.cbc_terminal_ballistics.state.ArmorIntegritySavedData;
import com.cbc_terminal_ballistics.state.ImpactMark;
import com.cbc_terminal_ballistics.state.TemporaryBlockPassage;
import com.cbc_terminal_ballistics.util.CBCReflect;
import com.cbc_terminal_ballistics.util.VSCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

public final class TBImpactService {
    private static final Map<Long, LastImpact> LAST_IMPACTS = new HashMap<>();
    private static final double MIN_SPALL_VISUAL_CLEARANCE = 0.85D;
    private static final double RICOCHET_MARK_MIN_ANGLE_FROM_NORMAL_DEGREES = 40.0D;
    private static final double RICOCHET_MARK_MAX_INCIDENCE = Math.cos(Math.toRadians(RICOCHET_MARK_MIN_ANGLE_FROM_NORMAL_DEGREES));
    private static final double HARD_BLOCK_IMPACT_SOUND_TOUGHNESS = 5.0D;
    private static final ResourceLocation CBC_PROJECTILE_IMPACT_SOUND = new ResourceLocation("createbigcannons", "projectile_impact");

    public static Object calculate(Entity projectile, Object projectileContext, BlockState state, BlockHitResult hit, boolean autocannonHint) {
        if (!TBConfig.ENABLED.get()) return null;
        if (ProjectileClassifier.shouldBypassTB(projectile)) return null;
        try {
            Level level = projectile.level();
            BlockPos pos = hit.getBlockPos();
            Vec3 accel = CBCReflect.forces(projectile, projectile.position(), projectile.getDeltaMovement());
            Vec3 curVel = projectile.getDeltaMovement().add(accel);
            double velMag = Math.max(curVel.length(), 1e-4d);
            Vec3 velDir = curVel.normalize();
            Vec3 normal = CBCReflect.surfaceNormal(level, hit).normalize();
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

            // Let CBC ricochet behavior pass through instead of applying CBCTB terminal-ballistics logic.
            // The normal comes from CBCUtils so Valkyrien Skies' CBC compat can transform ship normals exactly as CBC expects.
            double bounceChance = 0.0D;
            if (deflection >= 1e-2D && incidence <= deflection) {
                double bounceBonus = autocannon ? 1.0D : Math.max(1.0D - hardnessPenaltyRaw, 0.0D);
                bounceChance = Math.max(CBCReflect.baseProjectileBounceChance(), 1.0D - incidence / deflection) * bounceBonus;
            }
            if (surfaceImpact && CBCReflect.projectilesCanBounce() && level.random.nextDouble() < bounceChance) {
                if (!level.isClientSide) {
                    Vec3 effectNormal = curVel.subtract(normal.scale(normal.dot(curVel) * 1.7D));
                    CBCReflect.addBlockHitEffect(projectileContext, projectile, level, state, pos, hit.getLocation(), effectNormal, true);
                    if (level instanceof ServerLevel server) {
                        playHardBlockImpactSound(server, pos, armorToughness, caliber, velMag);
                        addImpactMark(server, pos, state, hit, ImpactMarkKind.STREAK, caliber, impactSurface, curVel);
                    }
                }
                Object bounceResult = CBCReflect.newImpactResult("BOUNCE", false);
                boolean onImpactRemove = CBCReflect.callOnImpact(projectile, hit, bounceResult, projectileContext);
                return autocannon ? bounceResult : CBCReflect.newImpactResult("BOUNCE", onImpactRemove);
            }

            boolean perforates = !unbreakable && penetrationRatio >= 1.0;
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

            double massLoss = 0.0;
            int spallFragments = 0;
            double spallDamageModifier = 0.0;
            String spallReason = "not_checked";
            if (level instanceof ServerLevel server) {
                if (projectile instanceof Projectile p) state.onProjectileHit(level, state, hit, p);
                CBCReflect.addBlockHitEffect(projectileContext, projectile, level, state, pos, hit.getLocation(), curVel.reverse(), false);
                playHardBlockImpactSound(server, pos, armorToughness, caliber, velMag);
                ArmorIntegritySavedData data = ArmorIntegritySavedData.get(server);
                data.addMark(server, pos, state, mark(server, pos, hit, markKind, caliber, impactSurface, markKind == ImpactMarkKind.STREAK ? ricochetMarkRotation(server, pos, hit, curVel) : 0.0F));
                if (outcome.equals("PENETRATE")) {
                    data.addMark(server, pos, state, exitMark(server, pos, hit, caliber, impactSurface));
                }
                syncMarks(server, pos, data.entryFor(server, pos, state).marks);

                if (outcome.equals("PENETRATE") && !unbreakable) {
                    if (integrityBreak || shatter) {
                        server.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        playBreakSound(server, pos, state);
                        clearMarks(server, pos);
                    } else {
                        // Perforation that did not break the block. In stock CBC this
                        // never happens (the projectile breaks the block or stops on
                        // it), so CBC's "block break on impact" sound is not played.
                        // CTB keeps the block intact, so replay the same sound CBC
                        // would have used in the stopped branch.
                        Vec3 spallLoc = hit.getLocation().add(velDir.normalize().scale(2));
                        CBCReflect.playBlockImpactBreakSound(server, state, spallLoc);
                        TemporaryBlockPassage.phaseForThisTick(server, pos, state);
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
                        Vec3 spallOrigin = hit.getLocation().add(velDir.scale(1.05));
                        spallDamageModifier = ProjectileClassifier.shellSpallDamageModifier(projectile);
                        double massAfter = Math.max(0.0, mass - massLoss);
                        spallFragments = spawnSpall(server, projectile, spallOrigin, velDir, caliber, mass, massAfter, material, baseArmorToughness);
                        spallReason = spallFragments > 0 ? "spawned" : "zero_fragments";
                    } else {
                        spallReason = "not_ap_style";
                    }
                } else {
                    CBCReflect.setProjectileMass(projectile, 0.0);
                    if (integrityBreak && !unbreakable) {
                        server.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        playBreakSound(server, pos, state);
                        clearMarks(server, pos);
                    }
                    spallReason = integrityBreak ? "stopped_integrity_break_no_spall" : "stopped_no_spall";
                }
                LAST_IMPACTS.put(server.dimension().location().hashCode() * 31L + pos.asLong(),
                    new LastImpact(outcome, appliedDamage, threshold, caliber, material, mass, Math.max(0.0, mass - massLoss), velMag, incidence, armorToughness, armorHardness, effectiveToughness, attack, perforationResistance, penetrationRatio, massLoss, spallFragments, spallDamageModifier, spallReason));
            }

            TBDebug.serviceOutcome(outcome, appliedDamage, threshold);
            boolean launcherStop = outcome.equals("STOP") && TestLauncherProjectileCompat.isLauncherProjectile(projectile);
            if (launcherStop) {
                projectile.setDeltaMovement(Vec3.ZERO);
            }
            Object impactResult = CBCReflect.newImpactResult(outcome, shatter);
            boolean onImpactRemove = CBCReflect.callOnImpact(projectile, hit, impactResult, projectileContext);
            // Autocannon projectiles must keep flying on a successful perforation.  The previous debug build
            // treated every non-bounce autocannon result as removable, which made AP rounds disappear after
            // punching through light blocks instead of continuing into the block behind them.
            boolean shouldRemove = launcherStop || (autocannon ? (!level.isClientSide && (shatter || outcome.equals("STOP"))) : (shatter || onImpactRemove));
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

    public static void syncMarksToPlayers(ServerLevel level, BlockPos pos, java.util.List<ImpactMark> marks) {
        syncMarks(level, pos, marks);
    }

    private static void addImpactMark(ServerLevel server, BlockPos pos, BlockState state, BlockHitResult hit, ImpactMarkKind kind, TBCaliber caliber, ImpactSurfaceType surface, Vec3 incomingVelocity) {
        ImpactMark mark = mark(server, pos, hit, kind, caliber, surface, kind == ImpactMarkKind.STREAK ? ricochetMarkRotation(server, pos, hit, incomingVelocity) : 0.0F);
        ArmorIntegritySavedData data = ArmorIntegritySavedData.get(server);
        data.addMark(server, pos, state, mark);
        syncMarks(server, pos, data.entryFor(server, pos, state).marks);
    }

    private static ImpactMark mark(ServerLevel level, BlockPos pos, BlockHitResult hit, ImpactMarkKind kind, TBCaliber caliber, ImpactSurfaceType surface, float rotation) {
        Vec3 loc = localHitLocation(level, pos, hit.getLocation());
        // For VS ship raycasts the BlockHitResult direction is already the block-local/shipyard face.
        // Transforming it again by worldToShip makes marks flip/rotate incorrectly when the ship is not axis-aligned.
        Direction face = hit.getDirection();
        float x = (float) Mth.clamp(loc.x - pos.getX(), 0.001, 0.999);
        float y = (float) Mth.clamp(loc.y - pos.getY(), 0.001, 0.999);
        float z = (float) Mth.clamp(loc.z - pos.getZ(), 0.001, 0.999);
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

    private static ImpactMark exitMark(ServerLevel level, BlockPos pos, BlockHitResult hit, TBCaliber caliber, ImpactSurfaceType surface) {
        Vec3 entry = localHitLocation(level, pos, hit.getLocation());
        Direction exitFace = hit.getDirection().getOpposite();
        double x = entry.x - pos.getX();
        double y = entry.y - pos.getY();
        double z = entry.z - pos.getZ();
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
        double x = hitLocation.x - pos.getX();
        double y = hitLocation.y - pos.getY();
        double z = hitLocation.z - pos.getZ();
        if (x >= -0.01D && x <= 1.01D && y >= -0.01D && y <= 1.01D && z >= -0.01D && z <= 1.01D) {
            return hitLocation;
        }
        return VSCompat.toShipCoordinates(level, pos, hitLocation);
    }

    private static void syncMarks(ServerLevel level, BlockPos pos, java.util.List<ImpactMark> marks) {
        if (TBNetwork.CHANNEL == null) return;
        ClientboundImpactMarksPacket packet = new ClientboundImpactMarksPacket(pos, java.util.List.copyOf(marks));
        Vec3 markCenter = Vec3.atCenterOf(pos);
        for (ServerPlayer player : level.players()) {
            if (VSCompat.squaredDistanceBetweenInclShips(level, markCenter, player.position()) <= 128 * 128) {
                TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
        }
    }

    private static void syncIntegrityProgress(ServerLevel level, BlockPos pos, double damage, double threshold) {
        int stage = damage <= 0.0D || threshold <= 0.0D ? -1 : Mth.clamp((int) Math.floor((damage / threshold) * 10.0D), 0, 9);
        syncIntegrityProgress(level, pos, stage);
    }

    private static void syncIntegrityProgress(ServerLevel level, BlockPos pos, int stage) {
        if (TBNetwork.CHANNEL == null) return;
        ClientboundIntegrityProgressPacket packet = new ClientboundIntegrityProgressPacket(pos, stage);
        Vec3 markCenter = Vec3.atCenterOf(pos);
        for (ServerPlayer player : level.players()) {
            if (VSCompat.squaredDistanceBetweenInclShips(level, markCenter, player.position()) <= 128 * 128) {
                TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
        }
    }

    private static void sendSpallCone(ServerLevel level, Entity projectile, Vec3 origin, Vec3 dir, double coneCos, double range,
                                      int visualFragments, int fragments, double massRatio, TBCaliber caliber) {
        if (TBNetwork.CHANNEL == null) return;
        visualFragments = Math.min(visualFragments, 24);
        if (visualFragments <= 0) return;
        float intensity = (float) Mth.clamp(0.65D + massRatio * 1.0D + caliber.ordinal() * 0.12D, 0.45D, 2.25D);
        long seed = spallVisualSeed(level, projectile, origin, dir, fragments);
        ClientboundSpallConePacket packet = new ClientboundSpallConePacket(origin, dir.normalize(), coneCos, range,
            visualFragments, seed, intensity, caliber);
        for (ServerPlayer player : level.players()) {
            if (VSCompat.squaredDistanceBetweenInclShips(level, origin, player.position()) <= 128 * 128) {
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

    private static int spawnSpall(ServerLevel level, Entity projectile, Vec3 origin, Vec3 dir, TBCaliber caliber, double massBefore, double massAfter, MaterialStats material, double armorToughness) {
        double countMultiplier = TBConfig.GLOBAL_SPALL_MULTIPLIER.get() * material.spallMultiplier() * caliber.spallScale * ProjectileClassifier.shellSpallCountModifier(projectile);
        int fragments = Mth.clamp((int) Math.round(armorToughness * countMultiplier), 0, TBConfig.MAX_SPALL_FRAGMENTS.get());
        if (fragments <= 0) return 0;
        double massRatio = massBefore > 0.0 ? massAfter / massBefore : 0.0;
        double range = Mth.clamp(5.0 + massRatio * 12.0 + caliber.ordinal() * 1.5, 5.0, 24.0);
        double coneCos = 0.5;
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
            if (!visualOriginInsideArmor) {
                double visualClearance = blockRay.getType() == HitResult.Type.MISS ? range : Math.sqrt(blockDistSqr);
                if (visualClearance >= MIN_SPALL_VISUAL_CLEARANCE) {
                    visualClearRays++;
                    visualRange = Math.max(visualRange, Math.min(range, visualClearance));
                }
            }

            for (LivingEntity entity : candidates) {
                java.util.Optional<Vec3> hitPoint = entity.getBoundingBox().inflate(0.18).clip(origin, end);
                if (hitPoint.isPresent() && hitPoint.get().distanceToSqr(origin) + 0.04 < blockDistSqr) {
                    rayHits.merge(entity, 1, Integer::sum);
                }
            }

            if (blockRay instanceof BlockHitResult bhr && blockRay.getType() == HitResult.Type.BLOCK) {
                BlockPos bp = bhr.getBlockPos();
                BlockState st = level.getBlockState(bp);
                if (st.isAir()) continue;
                float speed = st.getDestroySpeed(level, bp);
                if (speed < 0) continue;
                double localArmor = localEffectiveToughness(level, st, bp);
                if (localArmor > spallToughnessThreshold) continue;
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
                        level.setBlock(bp, Blocks.AIR.defaultBlockState(), 3);
                        playBreakSound(level, bp, st);
                        clearMarks(level, bp);
                    }
                }
            }
        }
        if (visualClearRays > 0 && visualRange >= MIN_SPALL_VISUAL_CLEARANCE) {
            sendSpallCone(level, projectile, origin, dir, coneCos, visualRange, Math.min(fragments, visualClearRays), fragments, massRatio, caliber);
        }

        for (LivingEntity entity : candidates) {
            Vec3 to = entity.getEyePosition().subtract(origin);
            double dist = Math.max(0.5, to.length());
            double cone = to.normalize().dot(dir);
            if (cone < coneCos) continue;
            HitResult occlusion = level.clip(new ClipContext(origin, entity.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile));
            if (occlusion.getType() != HitResult.Type.MISS && occlusion.getLocation().distanceToSqr(origin) + 0.25 < entity.getEyePosition().distanceToSqr(origin)) continue;
            int directHits = rayHits.getOrDefault(entity, 0);
            double coneStrength = cone * cone;
            float damage = (float) (Mth.clamp((massRatio * 15.0 + fragments * 1.15) * coneStrength / Math.sqrt(dist) + directHits * 3.5, 4.0, 48.0) * damageModifier);
            entity.hurt(level.damageSources().generic(), damage);
        }
        return fragments;
    }

    private static boolean isCatchingArmorAt(ServerLevel level, Vec3 pos) {
        BlockPos blockPos = BlockPos.containing(pos);
        BlockState state = level.getBlockState(blockPos);
        return !state.isAir() && localEffectiveToughness(level, state, blockPos) >= 5.0D;
    }

    private static Vec3 fragmentDirection(Vec3 forward, int index, int count, double coneCos, double jitter) {
        Vec3 f = forward.normalize();
        Vec3 up = Math.abs(f.y) < 0.92 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 right = f.cross(up).normalize();
        Vec3 orthoUp = right.cross(f).normalize();
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        double t = (index + 0.5) / Math.max(1, count);
        // Bias toward the center of the cone so targets directly behind the plate receive several fragments.
        double cos = 1.0 - (1.0 - coneCos) * Math.sqrt(t);
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

    private static boolean isArmorLike(BlockState state) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String ns = id.getNamespace();
        String path = id.getPath();
        return ns.equals("rha") || ns.equals("s_a_b") || path.contains("armor") || path.contains("armour") || path.contains("rha");
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
                             int spallFragments, double spallDamageModifier, String spallReason) {}

    private TBImpactService() {}
}
