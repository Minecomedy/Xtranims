package com.minecomedy.xtranims;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loader-agnostic mod constants and shared init sequence.
 *
 * This replaces the old @Mod entrypoint class. The actual entrypoints now
 * live in the platform modules:
 *   - com.minecomedy.xtranims.forge.XtraNimationsForge   (@Mod, Forge event bus)
 *   - com.minecomedy.xtranims.fabric.XtraNimationsFabric (ModInitializer / ClientModInitializer)
 *
 * Both call XtraNimations.initClient() at the equivalent point in their own
 * startup sequence (FMLClientSetupEvent on Forge, ClientModInitializer#onInitializeClient
 * on Fabric) — the actual loading logic is identical either way, so it stays here once.
 */
public class XtraNimations {

    public static final String MODID = "xtranims";
    public static final String MOD_ID = MODID; // backwards compat alias
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    /**
     * Shared client-side init sequence. Order matters:
     * XtraConfig must load first since other systems read XtraConfig.DEBUG.
     */
    public static void initClient() {
        XtraConfig.load();            // must be first — other systems read XtraConfig.DEBUG
        NbtTriggerLoader.load();      // config/xtranims/animations/
        EffectTriggerLoader.load();   // config/xtranims/effects/
        BiomeTriggerLoader.load();    // config/xtranims/biomes/
        RgbColorStore.loadAll();
        LOGGER.info("[XtraNimations] Common client init complete ({}).", XtraPlatform.INSTANCE.loaderName());
    }
}
