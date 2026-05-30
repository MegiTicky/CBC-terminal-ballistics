package com.cbc_terminal_ballistics.command;

import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.data.CopycatMaterialResolver;
import com.cbc_terminal_ballistics.data.MaterialManager;
import com.cbc_terminal_ballistics.data.MaterialStats;
import com.cbc_terminal_ballistics.debug.TBDebug;
import com.cbc_terminal_ballistics.debug.TBProjectileSlowdown;
import com.cbc_terminal_ballistics.state.ArmorIntegritySavedData;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.fml.ModList;

public final class TBCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tbinspect")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> inspect(ctx.getSource())));
        dispatcher.register(Commands.literal("tbdebug")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("status").executes(ctx -> debugStatus(ctx.getSource())))
            .then(Commands.literal("classes").executes(ctx -> debugClasses(ctx.getSource())))
            .then(Commands.literal("clear").executes(ctx -> debugClearTarget(ctx.getSource())))
            .then(Commands.literal("projectile_slow")
                .executes(ctx -> projectileSlowStatus(ctx.getSource()))
                .then(Commands.argument("factor", IntegerArgumentType.integer(TBProjectileSlowdown.MIN_FACTOR, TBProjectileSlowdown.MAX_FACTOR))
                    .executes(ctx -> setProjectileSlow(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "factor"))))
                .then(Commands.literal("reset").executes(ctx -> setProjectileSlow(ctx.getSource(), 1))))
            .then(Commands.literal("marks")
                .then(Commands.literal("refresh").executes(ctx -> refreshImpactMarks(ctx.getSource())))
                .then(Commands.literal("delete").executes(ctx -> deleteImpactMarks(ctx.getSource()))))
            .executes(ctx -> debugStatus(ctx.getSource())));
    }

    private static int inspect(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            HitResult pick = player.pick(64.0, 0.0f, false);
            if (!(pick instanceof BlockHitResult bhr) || pick.getType() == HitResult.Type.MISS) {
                source.sendFailure(Component.literal("No targeted block."));
                return 0;
            }
            ServerLevel level = player.serverLevel();
            BlockPos pos = bhr.getBlockPos();
            BlockState state = level.getBlockState(pos);
            BlockState materialState = CopycatMaterialResolver.resolve(level, pos, state).orElse(state);
            MaterialStats mat = MaterialManager.INSTANCE.get(materialState);
            ArmorIntegritySavedData.Entry entry = ArmorIntegritySavedData.get(level).getEntry(level, pos);
            TBImpactService.LastImpact last = TBImpactService.lastImpact(level, pos);
            source.sendSuccess(() -> Component.literal("Terminal ballistics @ " + pos.toShortString()).withStyle(ChatFormatting.GOLD), false);
            source.sendSuccess(() -> Component.literal("Block: " + state), false);
            if (materialState != state) {
                source.sendSuccess(() -> Component.literal("Resolved material: " + materialState), false);
            }
            source.sendSuccess(() -> Component.literal(String.format("Material: toughness x%.2f, brittleness %.2f, ductility %.2f, spall x%.2f", mat.toughnessMultiplier(), mat.brittleness(), mat.ductility(), mat.spallMultiplier())), false);
            source.sendSuccess(() -> Component.literal(entry == null ? "Integrity: no saved damage" : String.format("Integrity damage: %.2f, marks: %d", entry.damage, entry.marks.size())), false);
            if (last == null) {
                source.sendSuccess(() -> Component.literal("Last impact: none"), false);
            } else {
                source.sendSuccess(() -> Component.literal(String.format("Last impact: %s %s damage %.2f / threshold %.2f", last.outcome(), last.caliber(), last.damage(), last.threshold())), false);
                source.sendSuccess(() -> Component.literal(String.format("Physics: mass %.2f -> %.2f, vel %.2f, incidence %.2f, massLoss %.2f", last.massBefore(), last.massAfter(), last.velocity(), last.incidence(), last.massLoss())), false);
                source.sendSuccess(() -> Component.literal(String.format("Armor: CBC tough %.2f hard %.2f, effective %.2f, attack %.2f / resist %.2f = %.2f", last.armorToughness(), last.armorHardness(), last.effectiveToughness(), last.attack(), last.resistance(), last.penetrationRatio())), false);
                source.sendSuccess(() -> Component.literal(String.format("Spall: %d fragments (%s)", last.spallFragments(), last.spallReason())), false);
            }
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal(ex.getMessage()));
            return 0;
        }
    }

    private static int debugStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("CTB DEBUG status").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal(TBDebug.statusLine()), false);
        source.sendSuccess(() -> Component.literal("Projectile slow factor: " + TBProjectileSlowdown.serverFactor() + "x"), false);
        source.sendSuccess(() -> Component.literal("Material mappings: blocks=" + MaterialManager.INSTANCE.blockMappingCount() + " tags=" + MaterialManager.INSTANCE.tagMappingCount()), false);
        for (String modid : new String[]{"create", "createbigcannons", "ritchiesprojectilelib", "cbcmoreshells", "cbcmodernwarfare", "rha", "s_a_b"}) {
            boolean loaded = ModList.get().isLoaded(modid);
            source.sendSuccess(() -> Component.literal("Mod " + modid + ": " + (loaded ? "loaded" : "absent")), false);
        }
        return 1;
    }

    private static int projectileSlowStatus(CommandSourceStack source) {
        int factor = TBProjectileSlowdown.serverFactor();
        source.sendSuccess(() -> Component.literal("CBC projectile slow factor: " + factor + "x" + (factor <= 1 ? " (normal)" : "")), false);
        return factor;
    }

    private static int setProjectileSlow(CommandSourceStack source, int factor) {
        int clamped = TBProjectileSlowdown.setServerFactor(factor);
        TBProjectileSlowdown.syncAll(source.getServer());
        source.sendSuccess(() -> Component.literal("CBC projectile slow factor set to " + clamped + "x" + (clamped <= 1 ? " (normal)" : "")), true);
        return clamped;
    }


    private static int debugClearTarget(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            HitResult pick = player.pick(64.0, 0.0f, false);
            if (!(pick instanceof BlockHitResult bhr) || pick.getType() == HitResult.Type.MISS) {
                source.sendFailure(Component.literal("No targeted block."));
                return 0;
            }
            TBImpactService.clearMarks(player.serverLevel(), bhr.getBlockPos());
            source.sendSuccess(() -> Component.literal("Cleared CBCTB integrity data at " + bhr.getBlockPos().toShortString()), false);
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal(ex.getMessage()));
            return 0;
        }
    }

    private static int refreshImpactMarks(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ArmorIntegritySavedData data = ArmorIntegritySavedData.get(level);
        int syncedBlocks = 0;
        int syncedMarks = 0;
        for (java.util.Map.Entry<Long, ArmorIntegritySavedData.Entry> mapEntry : data.entriesView().entrySet()) {
            BlockPos pos = BlockPos.of(mapEntry.getKey());
            ArmorIntegritySavedData.Entry entry = data.getEntry(level, pos);
            if (entry == null || entry.marks.isEmpty()) continue;
            TBImpactService.syncMarksToPlayers(level, pos, java.util.List.copyOf(entry.marks));
            syncedBlocks++;
            syncedMarks += entry.marks.size();
        }
        int finalSyncedBlocks = syncedBlocks;
        int finalSyncedMarks = syncedMarks;
        source.sendSuccess(() -> Component.literal("Refreshed " + finalSyncedMarks + " impact marks on " + finalSyncedBlocks + " blocks."), false);
        return syncedBlocks;
    }

    private static int deleteImpactMarks(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ArmorIntegritySavedData data = ArmorIntegritySavedData.get(level);
        java.util.List<BlockPos> positions = data.entriesView().entrySet().stream()
            .map(entry -> BlockPos.of(entry.getKey()))
            .toList();
        int count = data.clearAll();
        for (BlockPos pos : positions) {
            TBImpactService.clearMarks(level, pos);
        }
        source.sendSuccess(() -> Component.literal("Deleted impact/integrity data for " + count + " blocks."), true);
        return count;
    }

    private static int debugClasses(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Base projectile: " + TBDebug.inspectClass("rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile")), false);
        source.sendSuccess(() -> Component.literal("Big projectile: " + TBDebug.inspectClass("rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile")), false);
        source.sendSuccess(() -> Component.literal("Auto projectile: " + TBDebug.inspectClass("rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile")), false);
        source.sendSuccess(() -> Component.literal("RPL ServerEntityMixin: " + TBDebug.inspectClass("rbasamoyai.ritchiesprojectilelib.mixin.ServerEntityMixin")), false);
        return 1;
    }

    private TBCommands() {}
}
