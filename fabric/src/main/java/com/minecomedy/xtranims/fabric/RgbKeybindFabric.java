package com.minecomedy.xtranims.fabric;

import com.minecomedy.xtranims.RgbKeybind;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

/**
 * Fabric-only glue: registers the (loader-agnostic) KeyMapping defined in
 * common's RgbKeybind. Unlike Forge, Fabric doesn't need an event for this —
 * just call it once during client init. Compare to forge/RgbKeybindForge.java.
 */
public class RgbKeybindFabric {
    public static void register() {
        KeyBindingHelper.registerKeyBinding(RgbKeybind.KEY_OPEN_RGB);
    }
}
