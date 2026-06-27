package com.minecomedy.xtranims;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/**
 * The RGB color picker keybind itself — pure vanilla KeyMapping, no loader
 * imports. Registration is loader-specific:
 *
 *   Forge:  RegisterKeyMappingsEvent -> event.register(RgbKeybind.KEY_OPEN_RGB)
 *           (see forge/RgbKeybindForge.java)
 *
 *   Fabric: KeyBindingHelper.registerKeyBinding(RgbKeybind.KEY_OPEN_RGB)
 *           called directly during client init (no event needed)
 *
 * The actual "was it pressed" check (KEY_OPEN_RGB.consumeClick()) stays in
 * TriggerEventHandler's tick logic — that part doesn't change at all.
 *
 * Default key: NONE (user must assign it in Controls menu).
 * Category: "XtraNimations"
 */
public class RgbKeybind {

    public static final KeyMapping KEY_OPEN_RGB = new KeyMapping(
            "key.xtranims.open_rgb",           // translation key
            InputConstants.UNKNOWN.getValue(), // default: unbound
            "key.categories.xtranims"          // category
    );
}
