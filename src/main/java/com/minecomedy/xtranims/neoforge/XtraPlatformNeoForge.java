package com.minecomedy.xtranims.neoforge;

import com.minecomedy.xtranims.XtraPlatform;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public class XtraPlatformNeoForge implements XtraPlatform {
    @Override
    public Path gameConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public String loaderName() {
        return "neoforge";
    }
}
