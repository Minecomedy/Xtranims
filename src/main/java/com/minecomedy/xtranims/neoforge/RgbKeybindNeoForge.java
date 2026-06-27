package com.minecomedy.xtranims.neoforge;

import com.minecomedy.xtranims.RgbKeybind;
import com.minecomedy.xtranims.XtraNimations;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = XtraNimations.MODID, value = Dist.CLIENT)
public class RgbKeybindNeoForge {

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(RgbKeybind.KEY_OPEN_RGB);
    }
}
