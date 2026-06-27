package com.minecomedy.xtranims;

import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * Tiny platform abstraction. This is the ONLY loader-specific lookup
 * XtraNimations actually needs (everything else either touches CPM's own
 * loader-agnostic API, or vanilla classes that resolve identically under
 * Forge/Fabric/NeoForge when both sides build against official mappings).
 *
 * Each platform module provides its own implementation and registers it via
 * a plain Java ServiceLoader entry, so `common` never imports Forge or
 * Fabric classes:
 *
 *   forge/src/main/resources/META-INF/services/com.minecomedy.xtranims.XtraPlatform
 *       -> com.minecomedy.xtranims.forge.XtraPlatformForge
 *
 *   fabric/src/main/resources/META-INF/services/com.minecomedy.xtranims.XtraPlatform
 *       -> com.minecomedy.xtranims.fabric.XtraPlatformFabric
 */
public interface XtraPlatform {

    /** Equivalent to the old {@code FMLPaths.CONFIGDIR.get()}. */
    Path gameConfigDir();

    /** Loader name for logging ("forge", "fabric", "neoforge"). */
    String loaderName();

    XtraPlatform INSTANCE = ServiceLoader.load(XtraPlatform.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                    "[XtraNimations] No XtraPlatform implementation found on the classpath — " +
                    "missing META-INF/services entry in the forge/fabric module."));
}
