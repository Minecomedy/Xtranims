package com.minecomedy.xtranims.forge;

import com.minecomedy.xtranims.RgbKeybind;
import com.minecomedy.xtranims.XtraNimations;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-only glue: registers the (loader-agnostic) KeyMapping defined in
 * common's RgbKeybind. This class is the entire Forge-specific surface for
 * keybinding — compare to fabric/RgbKeybindFabric.java.
 */
@Mod.EventBusSubscriber(modid = XtraNimations.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class RgbKeybindForge {

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(RgbKeybind.KEY_OPEN_RGB);
    }
}
