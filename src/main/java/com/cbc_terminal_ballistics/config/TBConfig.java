package com.cbc_terminal_ballistics.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Collections;
import java.util.List;

public final class TBConfig {
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ForgeConfigSpec COMMON_SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.DoubleValue INTEGRITY_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue GLOBAL_SPALL_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue MAX_SPALL_FRAGMENTS;
    public static final ForgeConfigSpec.IntValue OVERLAY_LIFETIME_TICKS;
    public static final ForgeConfigSpec.IntValue OVERLAY_MARKS_PER_BLOCK;
    public static final ForgeConfigSpec.DoubleValue AUTOCANNON_ARMOR_DAMAGE_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue IMPACT_DAMAGE_SCALE;
    public static final ForgeConfigSpec.DoubleValue VELOCITY_DAMPING_PER_MASS_LOSS;
    public static final ForgeConfigSpec.IntValue COPYCAT_ARMOR_MAX_LEVEL;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PROJECTILE_CLASS_OVERRIDES;

    static {
        ForgeConfigSpec.Builder server = new ForgeConfigSpec.Builder();
        server.push("terminal_ballistics");
        ENABLED = server.comment("Enable CBC terminal ballistics penetration override.").define("enabled", true);
        INTEGRITY_MULTIPLIER = server.comment("Global multiplier for accumulated armor integrity thresholds.").defineInRange("armorIntegrityMultiplier", 1.0, 0.05, 100.0);
        GLOBAL_SPALL_MULTIPLIER = server.comment("Global multiplier for AP spall fragment count/damage.").defineInRange("globalSpallMultiplier", 1.0, 0.0, 100.0);
        MAX_SPALL_FRAGMENTS = server.comment("Hard cap on generated spall fragments per impact.").defineInRange("maxSpallFragmentsPerImpact", 28, 0, 256);
        OVERLAY_LIFETIME_TICKS = server.comment("Impact overlay/record lifetime in ticks.").defineInRange("impactOverlayLifetimeTicks", 20 * 60 * 15, 20, 20 * 60 * 60);
        OVERLAY_MARKS_PER_BLOCK = server.comment("Maximum stored impact marks per block.").defineInRange("impactOverlayMarksPerBlock", 5, 1, 16);
        AUTOCANNON_ARMOR_DAMAGE_MULTIPLIER = server.comment("Integrity damage multiplier for autocannon impacts against armor.").defineInRange("autocannonArmorDamageMultiplier", 0.16, 0.0, 10.0);
        IMPACT_DAMAGE_SCALE = server.comment("Scales energy/momentum into saved integrity damage.").defineInRange("impactDamageScale", 1.0, 0.01, 100.0);
        VELOCITY_DAMPING_PER_MASS_LOSS = server.comment("Optional extra velocity damping after penetration, proportional to fractional mass loss. 0 keeps CBC-like mass-only slowing.").defineInRange("velocityDampingPerMassLoss", 0.0, 0.0, 1.0);
        COPYCAT_ARMOR_MAX_LEVEL = server.comment("Maximum armor level for copycat armor layer blocks. Changes require existing blocks to be updated with the Armor Upgrader tool.").defineInRange("copycatArmorMaxLevel", 20, 1, 100);
        server.pop();
        SERVER_SPEC = server.build();

        ForgeConfigSpec.Builder common = new ForgeConfigSpec.Builder();
        common.push("projectiles");
        PROJECTILE_CLASS_OVERRIDES = common.comment("Projectile class overrides formatted namespace:path=autocannon|small|small_medium|medium|big. These supplement built-in id namespace heuristics.")
            .defineListAllowEmpty("classOverrides", Collections.emptyList(), o -> o instanceof String && ((String) o).contains("="));
        common.pop();
        COMMON_SPEC = common.build();
    }

    private TBConfig() {}
}
