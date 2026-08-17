package com.cbc_terminal_ballistics.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.List;

public final class TBConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec COMMON_SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.DoubleValue INTEGRITY_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue GLOBAL_SPALL_MULTIPLIER;
    public static final ModConfigSpec.IntValue MAX_SPALL_FRAGMENTS;
    public static final ModConfigSpec.IntValue OVERLAY_LIFETIME_TICKS;
    public static final ModConfigSpec.IntValue OVERLAY_MARKS_PER_BLOCK;
    public static final ModConfigSpec.IntValue EMBEDDED_SHELLS_PER_BLOCK;
    public static final ModConfigSpec.IntValue EMBEDDED_SHELL_LIFETIME_TICKS;
    public static final ModConfigSpec.IntValue MAX_RENDERED_EMBEDDED_SHELLS;
    public static final ModConfigSpec.DoubleValue AUTOCANNON_ARMOR_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue IMPACT_DAMAGE_SCALE;
    public static final ModConfigSpec.DoubleValue VELOCITY_DAMPING_PER_MASS_LOSS;
    public static final ModConfigSpec.IntValue COPYCAT_ARMOR_MAX_LEVEL;
    public static final ModConfigSpec.DoubleValue SPALL_INTEGRITY_DAMAGE_SCALE;
    public static final ModConfigSpec.DoubleValue SPALL_INTEGRITY_DAMAGE_TOUGHNESS_THRESHOLD;
    public static final ModConfigSpec.DoubleValue SPALL_CONE_VELOCITY_BASELINE;
    public static final ModConfigSpec.DoubleValue SPALL_CONE_MIN_ANGLE;
    public static final ModConfigSpec.DoubleValue SPALL_CONE_MAX_ANGLE;
    public static final ModConfigSpec.DoubleValue RICOCHET_MIN_TOUGHNESS;
    public static final ModConfigSpec.DoubleValue RICOCHET_TOUGHNESS_SCALE;
    public static final ModConfigSpec.DoubleValue RICOCHET_VELOCITY_BASELINE;
    public static final ModConfigSpec.DoubleValue RICOCHET_MASS_LOSS_MIN;
    public static final ModConfigSpec.DoubleValue RICOCHET_MASS_LOSS_MAX;
    public static final ModConfigSpec.DoubleValue RICOCHET_MASS_LOSS_VELOCITY_SCALE;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> PROJECTILE_CLASS_OVERRIDES;

    static {
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();
        server.push("terminal_ballistics");
        ENABLED = server.comment("Enable CBC terminal ballistics penetration override.").define("enabled", true);
        INTEGRITY_MULTIPLIER = server.comment("Global multiplier for accumulated armor integrity thresholds.").defineInRange("armorIntegrityMultiplier", 1.0, 0.05, 100.0);
        GLOBAL_SPALL_MULTIPLIER = server.comment("Global multiplier for AP spall fragment count/damage.").defineInRange("globalSpallMultiplier", 1.0, 0.0, 100.0);
        MAX_SPALL_FRAGMENTS = server.comment("Hard cap on generated spall fragments per impact.").defineInRange("maxSpallFragmentsPerImpact", 28, 0, 256);
        OVERLAY_LIFETIME_TICKS = server.comment("Impact overlay/record lifetime in ticks.").defineInRange("impactOverlayLifetimeTicks", 20 * 60 * 15, 20, 20 * 60 * 60);
        OVERLAY_MARKS_PER_BLOCK = server.comment("Maximum stored impact marks per block.").defineInRange("impactOverlayMarksPerBlock", 5, 1, 16);
        EMBEDDED_SHELLS_PER_BLOCK = server.comment("Maximum stored embedded shell visuals per block.").defineInRange("embeddedShellsPerBlock", 8, 1, 64);
        EMBEDDED_SHELL_LIFETIME_TICKS = server.comment("Embedded shell visual lifetime in ticks. Set to 0 for permanent visuals.").defineInRange("embeddedShellLifetimeTicks", 0, 0, 20 * 60 * 60 * 24);
        MAX_RENDERED_EMBEDDED_SHELLS = server.comment("Maximum embedded shell models rendered by each client at once.").defineInRange("maxRenderedEmbeddedShells", 256, 0, 4096);
        AUTOCANNON_ARMOR_DAMAGE_MULTIPLIER = server.comment("Integrity damage multiplier for autocannon impacts against armor.").defineInRange("autocannonArmorDamageMultiplier", 0.16, 0.0, 10.0);
        IMPACT_DAMAGE_SCALE = server.comment("Scales energy/momentum into saved integrity damage.").defineInRange("impactDamageScale", 1.0, 0.01, 100.0);
        VELOCITY_DAMPING_PER_MASS_LOSS = server.comment("Optional extra velocity damping after penetration, proportional to fractional mass loss. 0 keeps CBC-like mass-only slowing.").defineInRange("velocityDampingPerMassLoss", 0.0, 0.0, 1.0);
        COPYCAT_ARMOR_MAX_LEVEL = server.comment("Maximum armor level for copycat armor layer blocks. Changes require existing blocks to be updated with the Armor Upgrader tool.").defineInRange("copycatArmorMaxLevel", 20, 1, 200);
        SPALL_INTEGRITY_DAMAGE_SCALE = server.comment("Integrity damage multiplier for spall fragments against blocks with toughness <= spallIntegrityDamageToughnessThreshold.").defineInRange("spallIntegrityDamageScale", 0.5, 0.0, 10.0);
        SPALL_INTEGRITY_DAMAGE_TOUGHNESS_THRESHOLD = server.comment("Maximum block toughness for spall to apply integrity damage. Blocks above this threshold ignore spall damage entirely.").defineInRange("spallIntegrityDamageToughnessThreshold", 16.0, 0.0, 100.0);
        SPALL_CONE_VELOCITY_BASELINE = server.comment("Velocity (m/s) at which spall cone is widest. Higher velocities produce narrower cones.").defineInRange("spallConeVelocityBaseline", 200.0, 50.0, 500.0);
        SPALL_CONE_MIN_ANGLE = server.comment("Minimum spall cone half-angle in degrees. Used for high-velocity focused penetrations.").defineInRange("spallConeMinAngle", 15.0, 5.0, 45.0);
        SPALL_CONE_MAX_ANGLE = server.comment("Maximum spall cone half-angle in degrees. Used for low-velocity oblique penetrations.").defineInRange("spallConeMaxAngle", 60.0, 30.0, 90.0);
        server.push("ricochet");
        RICOCHET_MIN_TOUGHNESS = server.comment("Blocks below this toughness cannot ricochet projectiles.").defineInRange("minToughness", 3.0, 0.0, 100.0);
        RICOCHET_TOUGHNESS_SCALE = server.comment("Block toughness at which ricochet probability reaches full strength.").defineInRange("toughnessScale", 20.0, 1.0, 100.0);
        RICOCHET_VELOCITY_BASELINE = server.comment("Projectile velocity (m/s) at which ricochet chance is reduced by half.").defineInRange("velocityBaseline", 200.0, 50.0, 1000.0);
        RICOCHET_MASS_LOSS_MIN = server.comment("Minimum fraction of projectile mass lost on ricochet.").defineInRange("massLossMin", 0.15, 0.0, 1.0);
        RICOCHET_MASS_LOSS_MAX = server.comment("Maximum fraction of projectile mass lost on ricochet.").defineInRange("massLossMax", 0.50, 0.0, 1.0);
        RICOCHET_MASS_LOSS_VELOCITY_SCALE = server.comment("Projectile velocity (m/s) used for ricochet mass loss.").defineInRange("massLossVelocityScale", 200.0, 50.0, 1000.0);
        server.pop();
        server.pop();
        SERVER_SPEC = server.build();

        ModConfigSpec.Builder common = new ModConfigSpec.Builder();
        common.push("projectiles");
        PROJECTILE_CLASS_OVERRIDES = common.comment("Projectile class overrides formatted namespace:path=autocannon|small|small_medium|medium|big. These supplement built-in id namespace heuristics.")
            .defineListAllowEmpty("classOverrides", Collections.emptyList(), o -> o instanceof String && ((String) o).contains("="));
        common.pop();
        COMMON_SPEC = common.build();
    }

    private TBConfig() {}
}
