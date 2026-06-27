package com.minecomedy.xtranims.neoforge;

import com.minecomedy.xtranims.TriggerEventHandler;
import com.minecomedy.xtranims.XtraNimations;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * NeoForge 1.21 event bridge for TriggerEventHandler.
 *
 * Differences from the Forge 1.20.1 version:
 *  - TickEvent.ClientTickEvent (phase END)  →  ClientTickEvent.Post
 *  - @Mod.EventBusSubscriber                →  @EventBusSubscriber (fml.common)
 *  - MinecraftForge.EVENT_BUS               →  NeoForge.EVENT_BUS (handled by annotation)
 *  - net.minecraftforge.*                   →  net.neoforged.*
 *
 * Everything TriggerEventHandler actually does is unchanged.
 */
@EventBusSubscriber(modid = XtraNimations.MODID)
public class TriggerEventBridgeNeoForge {

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
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
