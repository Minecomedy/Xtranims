package com.minecomedy.xtranims.fabric;

import com.minecomedy.xtranims.TriggerEventHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.world.entity.player.Player;

/**
 * Fabric-only glue for TriggerEventHandler. All the actual trigger-evaluation
 * logic stays in common/TriggerEventHandler.java unchanged — this class just
 * translates Fabric API's callback events into the plain method calls that
 * class now exposes. Compare to forge/TriggerEventBridgeForge.java.
 *
 * Call register() once from XtraNimationsFabric#onInitializeClient.
 */
public class TriggerEventBridgeFabric {

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> TriggerEventHandler.tick());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                TriggerEventHandler.onJoinServer());

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                TriggerEventHandler.onLeaveServer());

        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof Player) {
                TriggerEventHandler.onClientEntityUnload(entity.getUUID());
            }
        });
    }
}
