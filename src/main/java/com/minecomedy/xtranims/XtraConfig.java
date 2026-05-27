package com.minecomedy.xtranims;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * XtraConfig — central configuration for XtraNimations.
 *
 * File: config/xtranims/General_Config.txt
 *
 * ─── OPTIONS ────────────────────────────────────────────────────────────────
 *
 *   debug = false    (default)
 *       Set to true to enable verbose logging in the client log.
 *       Useful for troubleshooting; leave false for normal use to avoid
 *       flooding the log with internal state messages.
 *
 * ────────────────────────────────────────────────────────────────────────────
 *
 * This class is the single source of truth for:
 *   - The root config directory:   config/xtranims/
 *   - The animations sub-folder:   config/xtranims/animations/
 *   - The colors sub-folder:       config/xtranims/colors/
 *   - The debug flag:              XtraConfig.DEBUG
 *
 * All other classes should call XtraConfig.configDir() / animationsDir() /
 * colorsDir() instead of building paths themselves.
 */
public class XtraConfig {

    // ── Runtime state ─────────────────────────────────────────────────────────

    /** Whether verbose debug logging is enabled. Loaded from General_Config.txt. */
    public static volatile boolean DEBUG = false;

    // ── Path constants ────────────────────────────────────────────────────────

    /** Root config folder: .minecraft/config/xtranims/ */
    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("xtranims");
    }

    /** Subfolder for NBT item animation files: config/xtranims/animations/ */
    public static Path animationsDir() {
        return configDir().resolve("animations");
    }

    /** Subfolder for per-model RGB color files: config/xtranims/colors/ */
    public static Path colorsDir() {
        return configDir().resolve("colors");
    }

    /** Subfolder for custom effect trigger files: config/xtranims/effects/ */
    public static Path effectsDir() {
        return configDir().resolve("effects");
    }

    /** Subfolder for custom biome trigger files: config/xtranims/biomes/ */
    public static Path biomesDir() {
        return configDir().resolve("biomes");
    }

    // ── Config file name ──────────────────────────────────────────────────────

    private static final String CONFIG_FILE = "General_Config.txt";

    // ── Load ──────────────────────────────────────────────────────────────────

    /**
     * Loads (or creates) General_Config.txt.
     * Call once during mod initialisation before anything else reads DEBUG.
     */
    public static void load() {
        Path dir  = configDir();
        Path file = dir.resolve(CONFIG_FILE);

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Could not create config dir: {}", e.getMessage());
            return;
        }

        if (!Files.exists(file)) {
            writeDefaultConfig(file);
        }

        parseConfig(file);

        XtraNimations.LOGGER.info("[XtraNimations] Config loaded — debug={}", DEBUG);
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    private static void parseConfig(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Could not read {}: {}", CONFIG_FILE, e.getMessage());
            return;
        }

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip().toLowerCase();
            String val = line.substring(eq + 1).strip().toLowerCase();

            if (key.equals("debug")) {
                DEBUG = val.equals("true") || val.equals("1") || val.equals("yes");
            }
            // Future options go here
        }
    }

    // ── Default config file content ───────────────────────────────────────────

    private static void writeDefaultConfig(Path file) {
        String content =
            "# ╔══════════════════════════════════════════════════════════════════╗\n" +
            "# ║              XtraNimations — General Configuration              ║\n" +
            "# ╚══════════════════════════════════════════════════════════════════╝\n" +
            "#\n" +
            "# This file controls global behaviour for the XtraNimations mod.\n" +
            "# Edit the values below and restart Minecraft to apply changes.\n" +
            "#\n" +
            "# ─── OPTIONS ────────────────────────────────────────────────────────\n" +
            "#\n" +
            "#   debug = false  |  true\n" +
            "#       Controls verbose logging in the client log file.\n" +
            "#\n" +
            "#       false (default) — only important warnings and errors are logged.\n" +
            "#                         Best for normal gameplay; keeps the log clean.\n" +
            "#\n" +
            "#       true            — enables detailed internal state messages.\n" +
            "#                         Use when reporting bugs or troubleshooting\n" +
            "#                         unexpected animation behaviour.\n" +
            "#\n" +
            "# ────────────────────────────────────────────────────────────────────\n" +
            "\n" +
            "debug = false\n";

        try {
            Files.writeString(file, content);
            XtraNimations.LOGGER.info("[XtraNimations] Created default General_Config.txt");
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Could not write {}: {}", CONFIG_FILE, e.getMessage());
        }
    }
}
