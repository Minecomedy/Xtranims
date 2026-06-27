package com.minecomedy.xtranims.fabric;

import com.minecomedy.xtranims.XtraNimations;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric entrypoints. Registered in fabric.mod.json under "entrypoints":
 *   "main"   -> XtraNimationsFabric            (runs on both client and server)
 *   "client" -> XtraNimationsFabric$ClientEntry (client-only)
 *   "server" -> XtraNimationsFabric$ServerEntry (dedicated-server-only)
 *
 * CPM plugin registration needs NO Java code on Fabric — it's declared
 * directly in fabric.mod.json under the "cpmapi" entrypoint, pointing at
 * common's CPMPlugin class (which has zero Forge or Fabric imports).
 * See fabric.mod.json in this module's resources.
 */
public class XtraNimationsFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Runs on both physical sides — register the shared C2S/S2C handlers here.
        RgbNetworkingFabric.registerCommon();
        XtraNimations.LOGGER.info("[XtraNimations] Mod loaded (Fabric, common).");
    }

    public static class ClientEntry implements ClientModInitializer {
        @Override
        public void onInitializeClient() {
            RgbKeybindFabric.register();
            RgbNetworkingFabric.registerClient();
            TriggerEventBridgeFabric.register();
            XtraNimations.initClient();
            XtraNimations.LOGGER.info("[XtraNimations] Client init complete (Fabric).");
        }
    }

    public static class ServerEntry implements DedicatedServerModInitializer {
        @Override
        public void onInitializeServer() {
            // RGB relay (HELLO/RGB_UPDATE/RGB_PEER/join/disconnect) is already wired
            // in registerCommon() above — nothing dedicated-server-only is needed yet.
            XtraNimations.LOGGER.info("[XtraNimations] Dedicated server init complete (Fabric).");
        }
    }
}
