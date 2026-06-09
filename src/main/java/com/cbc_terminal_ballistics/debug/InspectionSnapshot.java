package com.cbc_terminal_ballistics.debug;

import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.data.CopycatMaterialResolver;
import com.cbc_terminal_ballistics.data.MaterialManager;
import com.cbc_terminal_ballistics.data.MaterialStats;
import com.cbc_terminal_ballistics.state.ArmorIntegritySavedData;
import com.cbc_terminal_ballistics.util.CBCReflect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public record InspectionSnapshot(
        BlockPos pos,
        ResourceLocation materialId,
        double armorToughness,
        double armorHardness,
        double ductility,
        double brittleness,
        double spallMultiplier,
        double integrityDamage,
        double integrityThreshold,
        String lastOutcome,
        String lastCaliber,
        double lastVelocity,
        double lastDamage,
        int lastSpallFragments) {

    public static InspectionSnapshot build(ServerLevel level, BlockPos pos, BlockHitResult hit) {
        BlockState state = level.getBlockState(pos);
        BlockState materialState = CopycatMaterialResolver.resolve(level, pos, state, hit).orElse(state);
        double toughness = CBCReflect.armorToughness(level, state, pos, Math.max(0.0, state.getBlock().getExplosionResistance()));
        double hardness = CBCReflect.armorHardness(level, state, pos, 1.0D);
        MaterialStats material = MaterialManager.INSTANCE.get(materialState, toughness);
        ArmorIntegritySavedData.Entry entry = ArmorIntegritySavedData.get(level).getEntry(level, pos);
        double damage = entry == null ? 0.0D : entry.damage;
        double threshold = TBImpactService.integrityThreshold(materialState, material, toughness);
        double degradedToughness = TBImpactService.degradedToughness(toughness, damage, threshold);
        TBImpactService.LastImpact impact = TBImpactService.lastImpact(level, pos);
        ResourceLocation materialId = BuiltInRegistries.BLOCK.getKey(materialState.getBlock());
        return new InspectionSnapshot(
                pos,
                materialId,
                degradedToughness,
                hardness,
                material.ductility(),
                material.brittleness(),
                material.spallMultiplier(),
                damage,
                threshold,
                impact == null ? "" : impact.outcome(),
                impact == null ? "" : impact.caliber().name(),
                impact == null ? 0.0D : impact.velocity() * 20.0D,
                impact == null ? 0.0D : impact.damage(),
                impact == null ? 0 : impact.spallFragments());
    }
}
