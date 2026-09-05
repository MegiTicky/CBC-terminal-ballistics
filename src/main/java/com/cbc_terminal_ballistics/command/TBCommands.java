package com.cbc_terminal_ballistics.command;

import com.cbc_terminal_ballistics.armor.ArmorCopycatItemData;
import com.cbc_terminal_ballistics.armor.CopycatArmorLayerBlockEntity;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlockEntity;
import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.data.CopycatMaterialResolver;
import com.cbc_terminal_ballistics.data.MaterialManager;
import com.cbc_terminal_ballistics.data.MaterialStats;
import com.cbc_terminal_ballistics.debug.TBDebug;
import com.cbc_terminal_ballistics.debug.TBProjectileSlowdown;
import com.cbc_terminal_ballistics.state.ArmorIntegritySavedData;
import com.cbc_terminal_ballistics.state.EmbeddedShellSavedData;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.cbc_terminal_ballistics.network.ClientboundTestRayPacket;
import com.cbc_terminal_ballistics.network.TBNetwork;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TBCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cbctb")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> status(ctx.getSource()))
            .then(Commands.literal("inspect")
                .executes(ctx -> inspect(ctx.getSource())))
            .then(Commands.literal("status")
                .executes(ctx -> status(ctx.getSource())))
            .then(Commands.literal("classes")
                .executes(ctx -> classes(ctx.getSource())))
            .then(Commands.literal("clear")
                .executes(ctx -> clearTarget(ctx.getSource())))
            .then(Commands.literal("projectile_slow")
                .executes(ctx -> projectileSlowStatus(ctx.getSource()))
                .then(Commands.argument("factor", IntegerArgumentType.integer(TBProjectileSlowdown.MIN_FACTOR, TBProjectileSlowdown.MAX_FACTOR))
                    .executes(ctx -> setProjectileSlow(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "factor"))))
                .then(Commands.literal("reset").executes(ctx -> setProjectileSlow(ctx.getSource(), 1))))
            .then(Commands.literal("marks")
                .then(Commands.literal("refresh").executes(ctx -> refreshImpactMarks(ctx.getSource())))
                .then(Commands.literal("delete").executes(ctx -> deleteImpactMarks(ctx.getSource()))))
            .then(Commands.literal("shells")
                .then(Commands.literal("refresh").executes(ctx -> refreshEmbeddedShells(ctx.getSource())))
                .then(Commands.literal("delete").executes(ctx -> deleteEmbeddedShells(ctx.getSource()))))
            .then(Commands.literal("checkarmor")
                .executes(ctx -> checkArmor(ctx.getSource(), false))
                .then(Commands.literal("fix")
                    .executes(ctx -> checkArmor(ctx.getSource(), true))))
            .then(Commands.literal("testray")
                .executes(ctx -> testRay(ctx.getSource()))
                .then(Commands.argument("distance", IntegerArgumentType.integer(1, 200))
                    .executes(ctx -> testRay(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "distance")))))
            .then(Commands.literal("hitcam")
                .executes(ctx -> hitcamReplay(ctx.getSource()))
                .then(Commands.literal("stop").executes(ctx -> hitcamStop(ctx.getSource()))))
    );
    }

    private static int hitcamReplay(CommandSourceStack source) {
        try {
            if (!TBConfig.HITCAM_ENABLED.get()) {
                source.sendFailure(Component.literal("Hitcam recording is disabled in config."));
                return 0;
            }
            ServerPlayer player = source.getPlayerOrException();
            List<com.cbc_terminal_ballistics.hitcam.HitcamSample> samples =
                com.cbc_terminal_ballistics.hitcam.HitcamRecorder.lastSamples();
            if (samples.size() < 2) {
                source.sendFailure(Component.literal("No projectile flight recorded yet. Fire a cannon shell first."));
                return 0;
            }
            net.minecraft.world.entity.Entity last = com.cbc_terminal_ballistics.hitcam.HitcamRecorder.lastProjectile();
            String typeKey = net.minecraft.world.entity.EntityType.getKey(last.getType()).toString();
            TBNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new com.cbc_terminal_ballistics.network.ClientboundHitcamPacket(typeKey, samples, null));
            source.sendSuccess(() -> Component.literal("Playing hitcam replay of the last projectile flight (" + samples.size() + " samples, " + typeKey + ")."), false);
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Error: " + ex.getMessage()));
            return 0;
        }
    }

    private static int hitcamStop(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            TBNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new com.cbc_terminal_ballistics.network.ClientboundHitcamPacket("", List.of(), null));
            source.sendSuccess(() -> Component.literal("Stopped hitcam replay."), false);
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Error: " + ex.getMessage()));
            return 0;
        }
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("CBC Terminal Ballistics status").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(TBDebug.statusLine()), false);
        source.sendSuccess(() -> Component.literal("Projectile slow factor: " + TBProjectileSlowdown.serverFactor() + "x"), false);
        source.sendSuccess(() -> Component.literal("Max armor level: " + TBConfig.COPYCAT_ARMOR_MAX_LEVEL.get() + " (max toughness: " + (TBConfig.COPYCAT_ARMOR_MAX_LEVEL.get() * ArmorCopycatItemData.TOUGHNESS_PER_LEVEL) + ")"), false);
        source.sendSuccess(() -> Component.literal("Material mappings: blocks=" + MaterialManager.INSTANCE.blockMappingCount() + " tags=" + MaterialManager.INSTANCE.tagMappingCount()), false);
        for (String modid : new String[]{"create", "createbigcannons", "ritchiesprojectilelib", "cbcmoreshells", "cbcmodernwarfare", "rha", "s_a_b"}) {
            boolean loaded = ModList.get().isLoaded(modid);
            source.sendSuccess(() -> Component.literal("Mod " + modid + ": " + (loaded ? "loaded" : "absent")), false);
        }
        return 1;
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
                source.sendSuccess(() -> Component.literal(String.format("Physics: mass %.2f -> %.2f, vel %.2f m/s, incidence %.2f, massLoss %.2f", last.massBefore(), last.massAfter(), last.velocity() * 20.0D, last.incidence(), last.massLoss())), false);
                source.sendSuccess(() -> Component.literal(String.format("Armor: CBC tough %.2f hard %.2f, effective %.2f, attack %.2f / resist %.2f = %.2f", last.armorToughness(), last.armorHardness(), last.effectiveToughness(), last.attack(), last.resistance(), last.penetrationRatio())), false);
                source.sendSuccess(() -> Component.literal(String.format("Spall: %d fragments (%s)", last.spallFragments(), last.spallReason())), false);
                source.sendSuccess(() -> Component.literal(String.format("Shatter: %.1f%% chance, %s", last.shatterChance() * 100.0D, last.shattered() ? "SHATTERED" : "embedded/stopped")), false);
            }
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal(ex.getMessage()));
            return 0;
        }
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

    private static int clearTarget(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            HitResult pick = player.pick(64.0, 0.0f, false);
            if (!(pick instanceof BlockHitResult bhr) || pick.getType() == HitResult.Type.MISS) {
                source.sendFailure(Component.literal("No targeted block."));
                return 0;
            }
            TBImpactService.clearMarks(player.serverLevel(), bhr.getBlockPos());
            TBImpactService.clearEmbeddedShells(player.serverLevel(), bhr.getBlockPos());
            source.sendSuccess(() -> Component.literal("Cleared CBCTB integrity data and embedded shells at " + bhr.getBlockPos().toShortString()), false);
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
        for (ArmorIntegritySavedData.Entry entry
             : new java.util.HashMap<>(data.entriesView()).values()) {
            if (entry.marks.isEmpty()) continue;
            if (entry.contraptionUuid != null && entry.localPos != null) {
                // Contraption-anchored marks are synced with their entity identity.
                net.minecraft.world.entity.Entity contraption = level.getEntity(entry.contraptionUuid);
                if (contraption == null) continue;
                TBImpactService.syncMarksToPlayers(level, entry.localPos, entry.contraptionUuid, entry.localPos, List.copyOf(entry.marks));
            } else {
                BlockPos pos = entry.keyPos();
                if (data.getEntry(level, pos) == null) continue;
                TBImpactService.syncMarksToPlayers(level, pos, List.copyOf(entry.marks));
            }
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
        List<ArmorIntegritySavedData.Entry> removed = new java.util.ArrayList<>(data.entriesView().values());
        int count = data.clearAll();
        for (ArmorIntegritySavedData.Entry entry : removed) {
            TBImpactService.clearMarksForEntry(level, entry);
        }
        source.sendSuccess(() -> Component.literal("Deleted impact/integrity data for " + count + " blocks."), true);
        return count;
    }

    private static int refreshEmbeddedShells(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int syncedBlocks = 0;
        int syncedShells = 0;
        for (EmbeddedShellSavedData.Entry entry
             : new java.util.HashMap<>(EmbeddedShellSavedData.get(level).entriesView()).values()) {
            if (entry.shells.isEmpty()) continue;
            if (entry.contraptionUuid != null && entry.localPos != null) {
                net.minecraft.world.entity.Entity contraption = level.getEntity(entry.contraptionUuid);
                if (contraption == null) continue;
                TBImpactService.syncEmbeddedShellsToPlayers(level, entry.localPos, entry.contraptionUuid, entry.localPos, List.copyOf(entry.shells));
            } else {
                BlockPos pos = entry.keyPos();
                TBImpactService.syncEmbeddedShellsToPlayers(level, pos, List.copyOf(entry.shells));
            }
            syncedBlocks++;
            syncedShells += entry.shells.size();
        }
        int finalBlocks = syncedBlocks;
        int finalShells = syncedShells;
        source.sendSuccess(() -> Component.literal("Refreshed " + finalShells + " embedded shells on " + finalBlocks + " blocks."), false);
        return syncedBlocks;
    }

    private static int deleteEmbeddedShells(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        List<EmbeddedShellSavedData.Entry> removed = new java.util.ArrayList<>(EmbeddedShellSavedData.get(level).entriesView().values());
        int count = EmbeddedShellSavedData.get(level).clearAll();
        for (EmbeddedShellSavedData.Entry entry : removed) {
            TBImpactService.clearShellsForEntry(level, entry);
        }
        source.sendSuccess(() -> Component.literal("Deleted embedded shell data for " + count + " blocks."), true);
        return count;
    }

    private static int classes(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Base projectile: " + TBDebug.inspectClass("rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile")), false);
        source.sendSuccess(() -> Component.literal("Big projectile: " + TBDebug.inspectClass("rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile")), false);
        source.sendSuccess(() -> Component.literal("Auto projectile: " + TBDebug.inspectClass("rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile")), false);
        source.sendSuccess(() -> Component.literal("RPL ServerEntityMixin: " + TBDebug.inspectClass("rbasamoyai.ritchiesprojectilelib.mixin.ServerEntityMixin")), false);
        return 1;
    }

    private static int checkArmor(CommandSourceStack source, boolean fix) {
        int maxLevel = TBConfig.COPYCAT_ARMOR_MAX_LEVEL.get();
        int totalChecked = 0;
        int totalExceeded = 0;
        int totalFixed = 0;
        List<String> levelSummaries = new ArrayList<>();

        for (ServerLevel level : source.getServer().getAllLevels()) {
            int levelChecked = 0;
            int levelExceeded = 0;
            int levelFixed = 0;
            Set<BlockEntity> checked = new HashSet<>();

            for (ServerPlayer player : level.players()) {
                int chunkX = player.chunkPosition().x;
                int chunkZ = player.chunkPosition().z;
                int viewDistance = 16;

                for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                    for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                        if (!level.hasChunk(chunkX + dx, chunkZ + dz)) continue;
                        for (BlockEntity be : level.getChunk(chunkX + dx, chunkZ + dz).getBlockEntities().values()) {
                            if (!checked.add(be)) continue;
                            if (be instanceof CopycatArmorLayerBlockEntity armor) {
                                levelChecked++;
                                int current = armor.getArmorLevel();
                                if (current > maxLevel) {
                                    levelExceeded++;
                                    if (fix) {
                                        armor.setArmorLevel(maxLevel);
                                        levelFixed++;
                                    }
                                }
                            } else if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
                                levelChecked++;
                                int current = armor.getArmorLevel();
                                if (current > maxLevel) {
                                    levelExceeded++;
                                    if (fix) {
                                        armor.setArmorLevel(maxLevel);
                                        levelFixed++;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            totalChecked += levelChecked;
            totalExceeded += levelExceeded;
            totalFixed += levelFixed;
            if (levelExceeded > 0) {
                levelSummaries.add(String.format("%s: %d/%d exceeded", level.dimension().location().toString(), levelExceeded, levelChecked));
            }
        }

        int finalTotalChecked = totalChecked;
        int finalTotalExceeded = totalExceeded;
        int finalTotalFixed = totalFixed;
        List<String> finalSummaries = new ArrayList<>(levelSummaries);

        source.sendSuccess(() -> Component.literal("=== Armor Level Check ===").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format("Config max level: %d (toughness: %d)", maxLevel, maxLevel * ArmorCopycatItemData.TOUGHNESS_PER_LEVEL)), false);
        source.sendSuccess(() -> Component.literal(String.format("Checked: %d armor blocks in %d loaded chunks", finalTotalChecked, finalSummaries.size())), false);

        if (fix) {
            source.sendSuccess(() -> Component.literal(String.format("Fixed: %d blocks exceeded max, clamped to %d", finalTotalFixed, maxLevel)).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.literal(String.format("Found: %d blocks exceed max level", finalTotalExceeded)).withStyle(finalTotalExceeded > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN), false);
        }

        if (!finalSummaries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Per-dimension breakdown:"), false);
            for (String summary : finalSummaries) {
                source.sendSuccess(() -> Component.literal("  " + summary), false);
            }
        }

        if (!fix && totalExceeded > 0) {
            source.sendSuccess(() -> Component.literal("Run '/cbctb checkarmor fix' to clamp exceeded levels.").withStyle(ChatFormatting.AQUA), false);
        }

        return totalExceeded;
    }

    private static int testRay(CommandSourceStack source) {
        return testRay(source, 50);
    }

    private static int testRay(CommandSourceStack source, int distance) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookVec = player.getLookAngle();
            Vec3 from = eyePos;
            Vec3 to = eyePos.add(lookVec.scale(distance));

            source.sendSuccess(() -> Component.literal("=== Contraption Ray Test ===").withStyle(ChatFormatting.GOLD), false);
            source.sendSuccess(() -> Component.literal(String.format("From: %.2f, %.2f, %.2f", from.x, from.y, from.z)), false);
            source.sendSuccess(() -> Component.literal(String.format("To:   %.2f, %.2f, %.2f", to.x, to.y, to.z)), false);

            if (!com.cbc_terminal_ballistics.util.ContraptionCompat.isLoaded()) {
                source.sendSuccess(() -> Component.literal("Create is not loaded!").withStyle(ChatFormatting.RED), false);
                return 0;
            }

            long startMs = System.nanoTime();
            com.cbc_terminal_ballistics.util.ContraptionCompat.ContraptionHit hit =
                com.cbc_terminal_ballistics.util.ContraptionCompat.raycastDebug(source.getLevel(), from, to, source);
            long elapsedUs = (System.nanoTime() - startMs) / 1000;

            source.sendSuccess(() -> Component.literal("Raycast completed in " + elapsedUs + " us"), false);

            if (hit == null) {
                source.sendSuccess(() -> Component.literal("No contraption hit.").withStyle(ChatFormatting.YELLOW), false);
            } else {
                source.sendSuccess(() -> Component.literal("HIT!").withStyle(ChatFormatting.GREEN), false);
                source.sendSuccess(() -> Component.literal(String.format("  World pos: %s", hit.worldPos())), false);
                source.sendSuccess(() -> Component.literal(String.format("  Hit loc: %.2f, %.2f, %.2f", hit.hitLocation().x, hit.hitLocation().y, hit.hitLocation().z)), false);
                source.sendSuccess(() -> Component.literal(String.format("  Face: %s", hit.direction())), false);
                source.sendSuccess(() -> Component.literal(String.format("  State: %s", hit.state())), false);
            }

            // Collect block AABB data for client-side debug rendering
            List<double[]> blockDataList = new ArrayList<>();
            try {
                List<net.minecraft.world.entity.Entity> entities =
                    com.cbc_terminal_ballistics.util.ContraptionCompat.findNearbyContraptionEntities(source.getLevel(), from, to);
                for (net.minecraft.world.entity.Entity entity : entities) {
                    Object contraption = com.cbc_terminal_ballistics.util.ContraptionCompat.getContraptionObject(entity);
                    if (contraption == null) continue;
                    java.util.Map<BlockPos, ?> blocks = com.cbc_terminal_ballistics.util.ContraptionCompat.getBlocksObject(contraption);
                    if (blocks == null) continue;
                    for (BlockPos localPos : blocks.keySet()) {
                        Vec3 worldCenter = com.cbc_terminal_ballistics.util.ContraptionCompat.toGlobalVector(entity, Vec3.atCenterOf(localPos), 0f);
                        if (worldCenter == null) continue;
                        blockDataList.add(new double[]{
                            worldCenter.x - 0.5, worldCenter.y - 0.5, worldCenter.z - 0.5,
                            worldCenter.x + 0.5, worldCenter.y + 0.5, worldCenter.z + 0.5,
                            worldCenter.x, worldCenter.y, worldCenter.z
                        });
                    }
                }
            } catch (Throwable ignored) {}

            double[] blockData = new double[blockDataList.size() * 9];
            for (int i = 0; i < blockDataList.size(); i++) {
                System.arraycopy(blockDataList.get(i), 0, blockData, i * 9, 9);
            }

            ClientboundTestRayPacket packet = new ClientboundTestRayPacket(
                from, to,
                hit != null ? hit.hitLocation() : null,
                hit != null ? hit.worldPos() : null,
                hit != null,
                blockData,
                blockDataList.size()
            );
            TBNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);

            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Error: " + ex.getMessage()));
            return 0;
        }
    }

    private TBCommands() {}
}
