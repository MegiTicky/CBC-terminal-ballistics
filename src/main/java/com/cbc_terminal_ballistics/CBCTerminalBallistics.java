package com.cbc_terminal_ballistics;

import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.command.TBCommands;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.compat.CbcArmorCompat;
import com.cbc_terminal_ballistics.compat.CbcInspectionCompat;
import com.cbc_terminal_ballistics.debug.TBDebug;
import com.cbc_terminal_ballistics.debug.TBProjectileSlowdown;
import com.cbc_terminal_ballistics.data.ImpactSurfaceManager;
import com.cbc_terminal_ballistics.data.MaterialManager;
import com.cbc_terminal_ballistics.network.TBNetwork;
import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import com.cbc_terminal_ballistics.registry.ModBlocks;
import com.cbc_terminal_ballistics.registry.ModCreativeTabs;
import com.cbc_terminal_ballistics.registry.ModItems;
import com.cbc_terminal_ballistics.registry.ModRecipeSerializers;
import com.cbc_terminal_ballistics.state.ArmorIntegritySavedData;
import com.cbc_terminal_ballistics.state.TemporaryBlockPassage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CBCTerminalBallistics.MOD_ID)
public class CBCTerminalBallistics {
    public static final String MOD_ID = "cbc_terminal_ballistics";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public CBCTerminalBallistics() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModRecipeSerializers.RECIPE_SERIALIZERS.register(modBus);
        modBus.addListener(this::commonSetup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, TBConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TBConfig.COMMON_SPEC);
        TBNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
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
        event.addListener(ImpactSurfaceManager.INSTANCE);
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        TBCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TBProjectileSlowdown.syncTo(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
            TemporaryBlockPassage.restore(level);
        }
        if (event.getServer().getTickCount() % 1200 != 0) return;
        for (net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
            for (net.minecraft.core.BlockPos pos : ArmorIntegritySavedData.get(level).cleanup(level)) {
                TBImpactService.clearMarks(level, pos);
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        ArmorIntegritySavedData.clearIfServer(event.getLevel(), event.getPos());
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            TBImpactService.clearMarks(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        ArmorIntegritySavedData.clearIfServer(event.getLevel(), event.getPos());
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            TBImpactService.clearMarks(level, event.getPos());
        }
    }
}
