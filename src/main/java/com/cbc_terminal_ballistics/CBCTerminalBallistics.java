package com.cbc_terminal_ballistics;

import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.command.TBCommands;
import com.cbc_terminal_ballistics.compat.CbcArmorCompat;
import com.cbc_terminal_ballistics.compat.CbcInspectionCompat;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.data.MaterialManager;
import com.cbc_terminal_ballistics.debug.TBDebug;
import com.cbc_terminal_ballistics.debug.TBProjectileSlowdown;
import com.cbc_terminal_ballistics.network.TBNetwork;
import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import com.cbc_terminal_ballistics.registry.ModBlocks;
import com.cbc_terminal_ballistics.registry.ModCreativeTabs;
import com.cbc_terminal_ballistics.registry.ModItems;
import com.cbc_terminal_ballistics.registry.ModRecipeSerializers;
import com.cbc_terminal_ballistics.state.ArmorIntegritySavedData;
import com.cbc_terminal_ballistics.state.EmbeddedShellSavedData;
import com.cbc_terminal_ballistics.state.TemporaryBlockPassage;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CBCTerminalBallistics.MOD_ID)
public class CBCTerminalBallistics {
    public static final String MOD_ID = "cbc_terminal_ballistics";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public CBCTerminalBallistics(IEventBus modBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModRecipeSerializers.RECIPE_SERIALIZERS.register(modBus);
        modBus.addListener(this::commonSetup);

        modContainer.registerConfig(ModConfig.Type.SERVER, TBConfig.SERVER_SPEC);
        modContainer.registerConfig(ModConfig.Type.COMMON, TBConfig.COMMON_SPEC);
        TBNetwork.register(modBus);
        NeoForge.EVENT_BUS.register(this);
        TBDebug.startupDiagnostics();
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CbcArmorCompat.register();
            CbcInspectionCompat.register();
        });
    }

    @SubscribeEvent
    public void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(MaterialManager.INSTANCE);
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        TBCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TBProjectileSlowdown.syncTo(player);
            TBImpactService.syncAllImpactMarksToPlayer(player);
            TBImpactService.syncAllEmbeddedShellsToPlayer(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        for (net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
            TemporaryBlockPassage.restore(level);
        }
        if (event.getServer().getTickCount() % 40 == 0) {
            for (net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
                for (ServerPlayer player : level.players()) {
                    TBImpactService.syncAllImpactMarksToPlayer(player);
                    TBImpactService.syncAllEmbeddedShellsToPlayer(player);
                }
            }
        }
        if (event.getServer().getTickCount() % 1200 != 0) return;
        for (net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
            for (net.minecraft.core.BlockPos pos : ArmorIntegritySavedData.get(level).cleanup(level)) {
                TBImpactService.clearMarks(level, pos);
            }
            for (net.minecraft.core.BlockPos pos : EmbeddedShellSavedData.get(level).cleanup(level)) {
                EmbeddedShellSavedData.Entry entry = EmbeddedShellSavedData.get(level).getEntry(level, pos);
                if (entry == null || entry.shells.isEmpty()) TBImpactService.clearEmbeddedShells(level, pos);
                else TBImpactService.syncEmbeddedShellsToPlayers(level, pos, java.util.List.copyOf(entry.shells));
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        ArmorIntegritySavedData.clearIfServer(event.getLevel(), event.getPos());
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            TBImpactService.clearMarks(level, event.getPos());
            TBImpactService.clearEmbeddedShells(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        ArmorIntegritySavedData.clearIfServer(event.getLevel(), event.getPos());
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            TBImpactService.clearMarks(level, event.getPos());
            TBImpactService.clearEmbeddedShells(level, event.getPos());
        }
    }
}
