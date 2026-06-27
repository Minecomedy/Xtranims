package com.minecomedy.xtranims.forge;

import com.minecomedy.xtranims.XtraPlatform;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

public class XtraPlatformForge implements XtraPlatform {
    @Override
    public Path gameConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public String loaderName() {
        return "forge";
    }
}
