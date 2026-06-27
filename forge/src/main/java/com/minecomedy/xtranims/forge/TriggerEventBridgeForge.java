package com.minecomedy.xtranims.forge;

import com.minecomedy.xtranims.TriggerEventHandler;
import com.minecomedy.xtranims.XtraNimations;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-only glue for TriggerEventHandler. All the actual trigger-evaluation
 * logic stays in common/TriggerEventHandler.java unchanged — this class just
 * translates Forge's event types into the plain method calls that class now
 * exposes. Compare to fabric/TriggerEventBridgeFabric.java.
 */
@Mod.EventBusSubscriber(modid = XtraNimations.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TriggerEventBridgeForge {

    @SuppressWarnings("deprecation")
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        TriggerEventHandler.tick();
    }

    @SubscribeEvent
    public static void onClientConnected(ClientPlayerNetworkEvent.LoggingIn event) {
        TriggerEventHandler.onJoinServer();
    }

    @SubscribeEvent
    public static void onClientDisconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        TriggerEventHandler.onLeaveServer();
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Player)) return;
        TriggerEventHandler.onClientEntityUnload(event.getEntity().getUUID());
    }
}
