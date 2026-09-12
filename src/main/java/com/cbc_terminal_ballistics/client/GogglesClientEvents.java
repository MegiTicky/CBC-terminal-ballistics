package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.registry.ModItems;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side item rendering for the goggles. Both reuse Create's engineer's
 * goggles texture (the model JSONs point at create:item/goggles); the spotter
 * variant is tinted so the two read differently at a glance.
 */
@Mod.EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GogglesClientEvents {

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        event.getItemColors().register((stack, tintIndex) -> 0xFF9A5A, ModItems.SPOTTER_GOGGLES.get());
    }

    private GogglesClientEvents() {}
}
