package com.minecomedy.xtranims.neoforge;

import com.minecomedy.xtranims.CPMPlugin;
import com.minecomedy.xtranims.XtraNimations;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.InterModComms;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;

import java.util.function.Supplier;

/**
 * NeoForge 1.21 @Mod entrypoint.
 *
 * Key differences from the Forge 1.20.1 version:
 *  - Constructor receives IEventBus directly (no FMLJavaModLoadingContext.get())
 *  - net.neoforged.* packages throughout
 *  - NeoForge.EVENT_BUS game-bus listeners registered via @EventBusSubscriber
 *    on the bridge/networking classes instead of here
 *  - RegisterPayloadHandlersEvent replaces NetworkRegistry in RgbNetworkingNeoForge
 *
 * CPM plugin registration: IMC still works in NeoForge 1.21 (CPM's
 * processIMC still handles it — confirmed from CPM 1.21 source).
 * When CPM API 0.6.26+ becomes your build dependency, you can also add
 * @com.tom.cpm.api.CPMPlugin to CPMPlugin.java for annotation-based
 * registration as an alternative.
 */
@Mod(XtraNimations.MODID)
public class XtraNimationsNeoForge {

    public XtraNimationsNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::enqueueIMC);
        // RgbNetworkingNeoForge, RgbKeybindNeoForge, and their ServerEvents
        // inner class are registered via @EventBusSubscriber on those classes —
        // no manual registration needed here.
        XtraNimations.LOGGER.info("[XtraNimations] Mod loaded (NeoForge 1.21).");
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        XtraNimations.initClient();
    }

    private void enqueueIMC(final InterModEnqueueEvent event) {
        InterModComms.sendTo("cpm", "api", () -> (Supplier<?>) CPMPlugin::new);
        XtraNimations.LOGGER.info("[XtraNimations] Plugin registered with CPM (NeoForge IMC).");
    }
}
