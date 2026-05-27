package com.minecomedy.xtranims;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers the RGB color picker keybind.
 *
 * Default key: NONE (user must assign it in Controls menu).
 * Category: "XtraNimations"
 *
 * When pressed: opens RgbColorScreen if not already open.
 */
@Mod.EventBusSubscriber(modid = XtraNimations.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class RgbKeybind {

    public static final KeyMapping KEY_OPEN_RGB = new KeyMapping(
            "key.xtranims.open_rgb",        // translation key
            InputConstants.UNKNOWN.getValue(), // default: unbound
            "key.categories.xtranims"          // category
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KEY_OPEN_RGB);
    }
}
