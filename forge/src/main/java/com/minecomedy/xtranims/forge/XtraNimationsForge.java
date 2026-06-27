package com.minecomedy.xtranims.forge;

import com.minecomedy.xtranims.CPMPlugin;
import com.minecomedy.xtranims.XtraNimations;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.function.Supplier;

@Mod(XtraNimations.MODID)
public class XtraNimationsForge {

    public XtraNimationsForge(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(this::onCommonSetup);
        context.getModEventBus().addListener(this::enqueueIMC);
        context.getModEventBus().addListener(this::onClientSetup);

        // Forge-specific event bridges — these are the only Forge-coupled
        // classes left; everything they call into is common logic.
        MinecraftForge.EVENT_BUS.register(TriggerEventBridgeForge.class);
        MinecraftForge.EVENT_BUS.register(RgbNetworkingForge.class);
        MinecraftForge.EVENT_BUS.register(RgbKeybindForge.class);

        XtraNimations.LOGGER.info("[XtraNimations] Mod loaded (Forge).");
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        RgbNetworkingForge.register();
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        XtraNimations.initClient();
    }

    /**
     * Forge IMC registration with CPM — unchanged from the original mod.
     * CPMPlugin itself has no Forge imports; it already lives in `common`.
     */
    private void enqueueIMC(final InterModEnqueueEvent event) {
        InterModComms.sendTo("cpm", "api", () -> (Supplier<?>) CPMPlugin::new);
        XtraNimations.LOGGER.info("[XtraNimations] Plugin registered with CPM (Forge IMC).");
    }
}
