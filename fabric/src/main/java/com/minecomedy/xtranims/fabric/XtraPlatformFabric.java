package com.minecomedy.xtranims.fabric;

import com.minecomedy.xtranims.XtraPlatform;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class XtraPlatformFabric implements XtraPlatform {
    @Override
    public Path gameConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String loaderName() {
        return "fabric";
    }
}
