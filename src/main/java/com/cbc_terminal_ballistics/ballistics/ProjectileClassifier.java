package com.cbc_terminal_ballistics.ballistics;

import com.cbc_terminal_ballistics.config.TBConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

public final class ProjectileClassifier {
    public static boolean shouldBypassTB(Entity projectile) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(projectile.getType());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        String cls = projectile.getClass().getName().toLowerCase(Locale.ROOT);
        String text = path + " " + cls;
        return text.contains("heap") || text.contains("heat") || text.contains("heapburst") || cls.contains("cbcprojectileburst") && cls.contains("heap");
    }

    public static TBCaliber classify(Entity projectile, boolean autocannonMixin) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(projectile.getType());
        String idStr = id == null ? "" : id.toString();
        for (String raw : TBConfig.PROJECTILE_CLASS_OVERRIDES.get()) {
            String[] parts = raw.split("=", 2);
            if (parts.length == 2 && parts[0].trim().equals(idStr)) return parse(parts[1], TBCaliber.BIG);
        }

        String ns = id == null ? "" : id.getNamespace();
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        String cls = projectile.getClass().getName().toLowerCase(Locale.ROOT);

        if (cls.contains("heavy_autocannon") || cls.contains("heavyautocannon")) return TBCaliber.HEAVY_AUTOCANNON;
        if (autocannonMixin || path.contains("autocannon") || path.contains("rotarycannon") || cls.contains("autocannon") || cls.contains("rotarycannon")) return TBCaliber.AUTOCANNON;
        if (ns.equals("cbcmodernwarfare") || path.contains("medium") || cls.contains("medium_cannon") || cls.contains("mediumcannon")) return TBCaliber.MEDIUM;
        if (ns.equals("cbcmoreshells") || cls.contains("cbcmoreshells")) {
            if (path.contains("small_medium") || path.contains("smallmedium") || path.contains("racked") || cls.contains("racked_projectile")) return TBCaliber.SMALL_MEDIUM;
            if (path.contains("small") || path.contains("dual") || cls.contains("dual_cannon")) return TBCaliber.SMALL;
            if (path.contains("torpedo") || cls.contains("torpedo")) return TBCaliber.SMALL_MEDIUM;
        }
        return TBCaliber.BIG;
    }

    public static boolean isApStyle(Entity projectile) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(projectile.getType());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        String cls = projectile.getClass().getName().toLowerCase(Locale.ROOT);
        String text = path + " " + cls;
        return text.contains("ap") || text.contains("shot") || text.contains("inert");
    }

    public static double shellSpallCountModifier(Entity projectile) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(projectile.getType());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        String cls = projectile.getClass().getName().toLowerCase(Locale.ROOT);
        String text = path + " " + cls;
        if (text.contains("apfsds")) return 0.5;
        if (text.contains("apds")) return 0.6;
        if (text.contains("apbc")) return 0.9;
        if (text.contains("aphe")) return 1.0;
        if (text.contains("ap")) return 1.0;
        if (text.contains("shot") || text.contains("inert")) return 0.7;
        return 0.0;
    }

    public static double shellSpallDamageModifier(Entity projectile) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(projectile.getType());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        String cls = projectile.getClass().getName().toLowerCase(Locale.ROOT);
        String text = path + " " + cls;
        if (text.contains("apfsds")) return 1.5;
        if (text.contains("apds")) return 1.3;
        if (text.contains("apbc")) return 0.9;
        if (text.contains("aphe")) return 0.85;
        if (text.contains("ap")) return 1.0;
        if (text.contains("shot") || text.contains("inert")) return 0.8;
        return 0.0;
    }

    public static boolean canSpall(Entity projectile) {
        return shellSpallCountModifier(projectile) > 0;
    }

    private static TBCaliber parse(String raw, TBCaliber fallback) {
        try { return TBCaliber.valueOf(raw.trim().toUpperCase(Locale.ROOT)); } catch (Exception ignored) { return fallback; }
    }

    private ProjectileClassifier() {}
}
