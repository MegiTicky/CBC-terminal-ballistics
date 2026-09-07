package com.cbc_terminal_ballistics.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Collections;
import java.util.List;

public final class TBConfig {
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ForgeConfigSpec COMMON_SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue HIT_CREATE_CONTRAPTIONS;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PROJECTILE_CLASS_OVERRIDES;

    public static final ForgeConfigSpec.DoubleValue INTEGRITY_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue IMPACT_DAMAGE_SCALE;
    public static final ForgeConfigSpec.DoubleValue AUTOCANNON_ARMOR_DAMAGE_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue VELOCITY_DAMPING_PER_MASS_LOSS;

    public static final ForgeConfigSpec.DoubleValue GLOBAL_SPALL_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue MAX_SPALL_FRAGMENTS;
    public static final ForgeConfigSpec.DoubleValue SPALL_INTEGRITY_DAMAGE_SCALE;
    public static final ForgeConfigSpec.DoubleValue SPALL_INTEGRITY_DAMAGE_TOUGHNESS_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue SPALL_CONE_VELOCITY_BASELINE;
    public static final ForgeConfigSpec.DoubleValue SPALL_CONE_MIN_ANGLE;
    public static final ForgeConfigSpec.DoubleValue SPALL_CONE_MAX_ANGLE;

    public static final ForgeConfigSpec.DoubleValue RICOCHET_MIN_TOUGHNESS;
    public static final ForgeConfigSpec.DoubleValue RICOCHET_TOUGHNESS_SCALE;
    public static final ForgeConfigSpec.DoubleValue RICOCHET_VELOCITY_BASELINE;
    public static final ForgeConfigSpec.DoubleValue RICOCHET_MASS_LOSS_MIN;
    public static final ForgeConfigSpec.DoubleValue RICOCHET_MASS_LOSS_MAX;
    public static final ForgeConfigSpec.DoubleValue RICOCHET_MASS_LOSS_VELOCITY_SCALE;

    public static final ForgeConfigSpec.IntValue OVERLAY_LIFETIME_TICKS;
    public static final ForgeConfigSpec.IntValue OVERLAY_MARKS_PER_BLOCK;
    public static final ForgeConfigSpec.IntValue EMBEDDED_SHELLS_PER_BLOCK;
    public static final ForgeConfigSpec.IntValue EMBEDDED_SHELL_LIFETIME_TICKS;
    public static final ForgeConfigSpec.IntValue MAX_RENDERED_EMBEDDED_SHELLS;
    public static final ForgeConfigSpec.IntValue COPYCAT_ARMOR_MAX_LEVEL;

    public static final ForgeConfigSpec.BooleanValue HITCAM_ENABLED;
    public static final ForgeConfigSpec.BooleanValue HITCAM_ARTILLERY_CAM;
    public static final ForgeConfigSpec.BooleanValue HITCAM_TERMINAL_VIEW;
    public static final ForgeConfigSpec.BooleanValue HITCAM_TERMINAL_CRITICALS;
    public static final ForgeConfigSpec.IntValue HITCAM_TERMINAL_POSTHUMOUS_TICKS;
    public static final ForgeConfigSpec.BooleanValue HITCAM_TERMINAL_BURST;
    public static final ForgeConfigSpec.IntValue HITCAM_TERMINAL_BURST_QUIET_TICKS;
    public static final ForgeConfigSpec.IntValue HITCAM_TERMINAL_BURST_ACTORS;
    public static final ForgeConfigSpec.IntValue HITCAM_TERMINAL_DURATION;
    public static final ForgeConfigSpec.IntValue HITCAM_TERMINAL_PATH_BLOCKS;
    public static final ForgeConfigSpec.IntValue HITCAM_MAX_SAMPLES;
    public static final ForgeConfigSpec.DoubleValue HITCAM_SLOWMO_END_FRACTION;
    public static final ForgeConfigSpec.DoubleValue HITCAM_SLOWMO_MIN_SPEED;
    public static final ForgeConfigSpec.DoubleValue HITCAM_BASE_SPEED;
    public static final ForgeConfigSpec.IntValue HITCAM_PIP_DISPLAY_WIDTH;
    public static final ForgeConfigSpec.IntValue HITCAM_PIP_DISPLAY_HEIGHT;
    public static final ForgeConfigSpec.ConfigValue<String> HITCAM_PIP_ANCHOR;
    public static final ForgeConfigSpec.ConfigValue<String> HITCAM_SHADER_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> HITCAM_DH_MODE;
    public static final ForgeConfigSpec.BooleanValue HITCAM_TERRAIN_STREAM;
    public static final ForgeConfigSpec.IntValue HITCAM_TERRAIN_STREAM_RADIUS;
    public static final ForgeConfigSpec.IntValue HITCAM_TERRAIN_STREAM_LEAD_TICKS;
    public static final ForgeConfigSpec.BooleanValue HITCAM_PREFLIGHT_CHUNKLOAD;
    public static final ForgeConfigSpec.IntValue HITCAM_PIP_FAR_FRAME_INTERVAL;
    public static final ForgeConfigSpec.ConfigValue<String> HITCAM_PIP_PARTICLES;
    public static final ForgeConfigSpec.DoubleValue HITCAM_PIP_TERRAIN_ALPHA;
    public static final ForgeConfigSpec.BooleanValue HITCAM_PIP_TERRAIN_XRAY;

    static {
        ForgeConfigSpec.Builder server = new ForgeConfigSpec.Builder();

        server.push("general");
        ENABLED = server.comment("Enable CBC terminal ballistics penetration override.").define("enabled", true);
        HIT_CREATE_CONTRAPTIONS = server.comment("Enable CBC projectile collision with Create contraption blocks (mechanical bearings, pistons, gantries, etc.). Projectiles will hit moving contraption blocks with full CBCTB ballistics.").define("hitCreateContraptions", false);
        server.pop();

        server.push("projectiles");
        PROJECTILE_CLASS_OVERRIDES = server.comment("Projectile class overrides formatted namespace:path=autocannon|small|small_medium|medium|big. These supplement built-in id namespace heuristics.")
            .defineListAllowEmpty("classOverrides", Collections.emptyList(), o -> o instanceof String && ((String) o).contains("="));
        server.pop();

        server.push("armor_integrity");
        INTEGRITY_MULTIPLIER = server.comment("Global multiplier for accumulated armor integrity thresholds.").defineInRange("integrityMultiplier", 1.0, 0.05, 100.0);
        IMPACT_DAMAGE_SCALE = server.comment("Scales energy/momentum into saved integrity damage.").defineInRange("impactDamageScale", 1.0, 0.01, 100.0);
        AUTOCANNON_ARMOR_DAMAGE_MULTIPLIER = server.comment("Integrity damage multiplier for autocannon impacts against armor.").defineInRange("autocannonArmorDamageMultiplier", 0.16, 0.0, 10.0);
        VELOCITY_DAMPING_PER_MASS_LOSS = server.comment("Optional extra velocity damping after penetration, proportional to fractional mass loss. 0 keeps CBC-like mass-only slowing.").defineInRange("velocityDampingPerMassLoss", 0.0, 0.0, 1.0);
        server.pop();

        server.push("spalling");
        GLOBAL_SPALL_MULTIPLIER = server.comment("Global multiplier for AP spall fragment count/damage.").defineInRange("globalSpallMultiplier", 1.0, 0.0, 100.0);
        MAX_SPALL_FRAGMENTS = server.comment("Hard cap on generated spall fragments per impact.").defineInRange("maxSpallFragmentsPerImpact", 28, 0, 256);
        SPALL_INTEGRITY_DAMAGE_SCALE = server.comment("Integrity damage multiplier for spall fragments against blocks with toughness <= spallIntegrityDamageToughnessThreshold.").defineInRange("spallIntegrityDamageScale", 0.5, 0.0, 10.0);
        SPALL_INTEGRITY_DAMAGE_TOUGHNESS_THRESHOLD = server.comment("Maximum block toughness for spall to apply integrity damage. Blocks above this threshold ignore spall damage entirely.").defineInRange("spallIntegrityDamageToughnessThreshold", 16.0, 0.0, 100.0);
        SPALL_CONE_VELOCITY_BASELINE = server.comment("Velocity (m/s) at which spall cone is widest. Higher velocities produce narrower cones.").defineInRange("spallConeVelocityBaseline", 200.0, 50.0, 500.0);
        SPALL_CONE_MIN_ANGLE = server.comment("Minimum spall cone half-angle in degrees. Used for high-velocity focused penetrations.").defineInRange("spallConeMinAngle", 15.0, 5.0, 45.0);
        SPALL_CONE_MAX_ANGLE = server.comment("Maximum spall cone half-angle in degrees. Used for low-velocity oblique penetrations.").defineInRange("spallConeMaxAngle", 60.0, 30.0, 90.0);
        server.pop();

        server.push("ricochet");
        RICOCHET_MIN_TOUGHNESS = server.comment("Blocks below this toughness cannot ricochet projectiles. Soft materials like leaves, wool, sand will absorb impacts instead.").defineInRange("minToughness", 3.0, 0.0, 100.0);
        RICOCHET_TOUGHNESS_SCALE = server.comment("Block toughness at which ricochet probability reaches full strength. Ricochet chance scales between minToughness and this value.").defineInRange("toughnessScale", 20.0, 1.0, 100.0);
        RICOCHET_VELOCITY_BASELINE = server.comment("Projectile velocity (m/s) at which ricochet chance is reduced by half. Higher velocities penetrate rather than bounce.").defineInRange("velocityBaseline", 200.0, 50.0, 1000.0);
        RICOCHET_MASS_LOSS_MIN = server.comment("Minimum fraction of projectile mass lost on ricochet.").defineInRange("massLossMin", 0.15, 0.0, 1.0);
        RICOCHET_MASS_LOSS_MAX = server.comment("Maximum fraction of projectile mass lost on ricochet.").defineInRange("massLossMax", 0.50, 0.0, 1.0);
        RICOCHET_MASS_LOSS_VELOCITY_SCALE = server.comment("Projectile velocity (m/s) used for calculating average mass loss on ricochet.").defineInRange("massLossVelocityScale", 200.0, 50.0, 1000.0);
        server.pop();

        server.push("visuals");
        OVERLAY_LIFETIME_TICKS = server.comment("Impact overlay/record lifetime in ticks.").defineInRange("impactOverlayLifetimeTicks", 20 * 60 * 15, 20, 20 * 60 * 60);
        OVERLAY_MARKS_PER_BLOCK = server.comment("Maximum stored impact marks per block.").defineInRange("impactOverlayMarksPerBlock", 5, 1, 16);
        EMBEDDED_SHELLS_PER_BLOCK = server.comment("Maximum permanent embedded shell visuals stored on one block.").defineInRange("embeddedShellsPerBlock", 8, 1, 64);
        EMBEDDED_SHELL_LIFETIME_TICKS = server.comment("Embedded shell visual lifetime in ticks. Set to 0 for permanent shell visuals.").defineInRange("embeddedShellLifetimeTicks", 0, 0, 20 * 60 * 60 * 24);
        MAX_RENDERED_EMBEDDED_SHELLS = server.comment("Maximum embedded shell models rendered by each client at once.").defineInRange("maxRenderedEmbeddedShells", 256, 0, 4096);
        server.pop();

        server.push("copycat_armor");
        COPYCAT_ARMOR_MAX_LEVEL = server.comment("Maximum armor level for copycat armor layer blocks. Changes require existing blocks to be updated with the Armor Upgrader tool.").defineInRange("maxLevel", 20, 1, 200);
        server.pop();

        server.push("hitcam");
        HITCAM_ENABLED = server.comment("Record projectile flights so /cbctb hitcam can replay the last fired shell in a slow-motion picture-in-picture view.").define("hitcamEnabled", true);
        HITCAM_ARTILLERY_CAM = server.comment("Artillery view: automatically show a live chase-camera picture-in-picture when a projectile you fired (or are operating) spawns.").define("hitcamArtilleryCam", true);
        HITCAM_TERMINAL_VIEW = server.comment("Terminal ballistic view: after a shell engages armour, show a War-Thunder-style x-ray reconstruction (shell path, plate outcomes, spall, crew hits) in the picture-in-picture window.").define("hitcamTerminalView", true);
        HITCAM_TERMINAL_CRITICALS = server.comment("Critical hits in the terminal view: when the shell, its spall or its explosion BREAKS a mechanical component block (data-driven critical_blocks datapack list) or kills/downs a crew member, flag the engagement as CRITICAL and list the destroyed components and crew kills in the picture-in-picture window.").define("hitcamTerminalCriticals", true);
        HITCAM_TERMINAL_POSTHUMOUS_TICKS = server.comment("Critical hits: how long (ticks) a finished engagement keeps listening for post-mortem events after the shell entity is gone — ammo-rack cook-offs, chained crew deaths from the cook-off explosion, secondary detonations. Events near the impact point within this window still update the terminal view's verdict (e.g. NO PENETRATION becomes CRITICAL HIT). 0 disables.").defineInRange("hitcamTerminalPosthumousTicks", 100, 0, 600);
        HITCAM_TERMINAL_BURST = server.comment("Terminal view burst accumulation: when a second shell (from an autocannon burst, a ship broadside, etc.) hits the SAME vehicle/position while the terminal x-ray is still showing, keep the view's camera and anchor on the first engagement and pop the new shell's impact marks (plates, spall, crew hits) onto the running diagram instead of restarting the view. Merged shells start the tick their packet arrives — the shell has already hit — with their own fade timer, so a sustained burst renders as an overlapping mess of fresh impacts (no serialized wait behind an earlier shell's animation; the full cinematic fly-in plays only when a view starts from rest). Disable to always restart the view per shell (old behaviour).").define("hitcamTerminalMergeBurst", true);
        HITCAM_TERMINAL_BURST_QUIET_TICKS = server.comment("Burst accumulation: how many ticks after the last accumulated hit the terminal x-ray keeps holding before it fades, so a pause in fire (reload, retarget) ends the view. Also how long each accumulated shell's own marks stay up after ITS impact before they fade (each shell fades on its own timer; raise to linger longer, lower for a faster-churning high-RPM mess). Extends the base terminal duration.").defineInRange("hitcamTerminalBurstQuietTicks", 40, 10, 400);
        HITCAM_TERMINAL_BURST_ACTORS = server.comment("Burst accumulation: maximum shells whose marks may be on the diagram at once (the concurrent window). When the view already holds this many hits and another shell arrives, the oldest fully-shown shell is dropped so the newest hit always renders — the view never restarts mid-burst. Raise (up to 64) for a deeper high-RPM overlap; 1 disables merging.").defineInRange("hitcamTerminalBurstActors", 8, 1, 64);
        HITCAM_TERMINAL_DURATION = server.comment("How long the terminal ballistic x-ray diagram stays on screen, in ticks. This is the base duration of a single-shell engagement; burst hits extend it (see hitcamTerminalBurstQuietTicks).").defineInRange("hitcamTerminalDurationTicks", 120, 20, 600);
        HITCAM_TERMINAL_PATH_BLOCKS = server.comment("Maximum length of the flight path, in blocks, replayed by the terminal view. Longer shots trim their approach so the impact dominates, War-Thunder style.").defineInRange("hitcamTerminalPathBlocks", 40, 10, 200);
        HITCAM_MAX_SAMPLES = server.comment("Maximum recorded flight samples per projectile. 20 samples = 1 second of flight.").defineInRange("hitcamMaxSamples", 100, 20, 600);
        HITCAM_SLOWMO_END_FRACTION = server.comment("Fraction of the replay's flight during which playback eases from normal speed into slow motion (0.3 = last 30%).").defineInRange("hitcamSlowmoEndFraction", 0.3, 0.0, 1.0);
        HITCAM_SLOWMO_MIN_SPEED = server.comment("Slowest replay playback speed reached at impact (1.0 = real time).").defineInRange("hitcamSlowmoMinSpeed", 0.12, 0.01, 1.0);
        HITCAM_BASE_SPEED = server.comment("Replay playback speed at the start of the clip (1.0 = real time). Lower values make the replay read as slowed footage.").defineInRange("hitcamBaseSpeed", 1.0, 0.05, 1.0);
        HITCAM_PIP_DISPLAY_WIDTH = server.comment("Picture-in-picture replay window width in GUI-scaled pixels. The replay itself renders at full framebuffer resolution and is downscaled, so this only affects display size.").defineInRange("hitcamPipDisplayWidth", 240, 80, 960);
        HITCAM_PIP_DISPLAY_HEIGHT = server.comment("Picture-in-picture replay window height in GUI-scaled pixels.").defineInRange("hitcamPipDisplayHeight", 135, 50, 540);
        HITCAM_PIP_ANCHOR = server.comment("Corner of the screen the replay window is pinned to. One of TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT.").defineInList("hitcamPipAnchor", "TOP_RIGHT", List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"));
        HITCAM_SHADER_MODE = server.comment("Picture-in-picture behaviour when a shader pack is actively rendering: AUTO = lightweight fallback (ghost shell over live view, no nested world render), SKIP = disable with a chat notice, ATTEMPT = try the full nested render anyway. When no shader pack is active the full render is always used.").defineInList("hitcamShaderMode", "AUTO", List.of("AUTO", "SKIP", "ATTEMPT"));
        HITCAM_DH_MODE = server.comment("Distant Horizons behaviour inside the hitcam picture-in-picture. DH renders its LOD terrain during the nested world pass but points it at the player's camera, which displaces the LODs in the window. ATTEMPT = patch DH's render camera to the hitcam camera so LOD terrain, fog and fades line up in the view (falls back to SUPPRESS for the session if it throws). SUPPRESS = never render DH LODs inside the picture-in-picture; the window shows only streamed vanilla chunks while the main view keeps DH untouched.").defineInList("hitcamDhMode", "ATTEMPT", List.of("ATTEMPT", "SUPPRESS"));
        HITCAM_TERRAIN_STREAM = server.comment("Artillery/terminal view terrain streaming: send the chunks around the flying shell to the viewer's client so the picture-in-picture world pass keeps showing terrain past the chunk render distance boundary (the client normally discards chunks beyond its view range).").define("hitcamTerrainStream", true);
        HITCAM_TERRAIN_STREAM_RADIUS = server.comment("Chunk radius around the shell streamed to the artillery/terminal viewer.").defineInRange("hitcamTerrainStreamRadius", 2, 1, 4);
        HITCAM_TERRAIN_STREAM_LEAD_TICKS = server.comment("How far ahead (in ticks along the shell's current velocity) to also stream chunks, so terrain arrives before the shell does.").defineInRange("hitcamTerrainStreamLeadTicks", 15, 0, 40);
        HITCAM_PREFLIGHT_CHUNKLOAD = server.comment("Preflight shell chunk loading: while the artillery cam tracks a shell, queue the chunks ahead of it through CBC's own projectile chunk loader (RitchiesProjectileLib) so they are generated before the shell arrives. Without this a fast shell stalls at every not-yet-generated chunk boundary.").define("hitcamPreflightChunkload", true);
        HITCAM_PIP_FAR_FRAME_INTERVAL = server.comment("When the hitcam camera is far from the player (different chunk), the nested world render recenters the shared chunk grid every frame, which makes both views recompile all their chunk sections constantly and can leave holes in the main view's terrain. This throttles the far-view picture-in-picture to render every Nth frame instead (the window holds its last frame in between). 1 = render every frame (old behaviour).").defineInRange("hitcamPipFarFrameInterval", 3, 1, 10);
        HITCAM_PIP_PARTICLES = server.comment("Particles inside the hitcam picture-in-picture window. NONE = no particles (old behaviour). FILTERED = hide only smoke-family particles (class names containing 'smoke' or 'plume': cannon muzzle plumes, smoke trails, breech smoke, vanilla smoke) so explosions, fire, cook-off and debris stay visible. ALL = show every particle.").defineInList("hitcamPipParticles", "FILTERED", List.of("NONE", "FILTERED", "ALL"));
        HITCAM_PIP_TERRAIN_ALPHA = server.comment("X-ray view: opacity of the ship's own terrain blocks inside the terminal picture-in-picture (0.5 = half-transparent ghost). Only ship chunks are ghosted; world terrain, entities and block entities (cannon barrels etc.) stay fully rendered. 1.0 disables the ghost.").defineInRange("hitcamPipTerrainAlpha", 0.5, 0.05, 1.0);
        HITCAM_PIP_TERRAIN_XRAY = server.comment("X-ray view: while ship terrain is ghosted, skip its depth writes so hull interiors and the turrets/barrels behind them show through the hull, War-Thunder style. False keeps proper depth occlusion (a ghosted hull hides what is directly behind it).").define("hitcamPipTerrainXray", true);
        server.pop();

        SERVER_SPEC = server.build();

        ForgeConfigSpec.Builder common = new ForgeConfigSpec.Builder();
        COMMON_SPEC = common.build();
    }

    private TBConfig() {}
}
