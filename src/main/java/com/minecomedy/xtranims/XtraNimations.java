package com.minecomedy.xtranims;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.InterModComms;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.function.Supplier;

@Mod(XtraNimations.MODID)
public class XtraNimations {

    public static final String MODID = "xtranims";
    public static final String MOD_ID = MODID; // backwards compat alias
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public XtraNimations(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(this::onCommonSetup);
        context.getModEventBus().addListener(this::enqueueIMC);
        context.getModEventBus().addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.register(TriggerEventHandler.class);
        MinecraftForge.EVENT_BUS.register(RgbServerHandler.class);
        LOGGER.info("[XtraNimations] Mod loaded.");
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        RgbServerHandler.register();
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        XtraConfig.load();            // must be first — other systems read XtraConfig.DEBUG
        NbtTriggerLoader.load();      // config/xtranims/animations/
        EffectTriggerLoader.load();   // config/xtranims/effects/
        BiomeTriggerLoader.load();    // config/xtranims/biomes/
        RgbColorStore.loadAll();
        // RgbReflectionHelper no longer needs manual init —
        // it scans automatically when the player's model loads.
    }

    private void enqueueIMC(final InterModEnqueueEvent event) {
        InterModComms.sendTo("cpm", "api", () -> (Supplier<?>) CPMPlugin::new);
        LOGGER.info("[XtraNimations] Plugin registered with CPM.");
    }
}
