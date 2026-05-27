package com.minecomedy.xtranims;

import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * BiomeTriggerLoader — loads custom biome trigger definitions from config.
 *
 * Config folder: .minecraft/config/xtranims/biomes/
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Each .txt file defines ONE biome trigger.
 *
 * The filename (without .txt) becomes the animation name used in the CPM editor.
 * It supports the :freenamespace colon syntax just like all other XtraNimations
 * triggers — create multiple animations in CPM with names like:
 *
 *   terralith_highlands:glow
 *   terralith_highlands:wing_fold
 *
 * All receive the same value (0 or 1) from this loader.
 *
 * FILE FORMAT
 * ───────────
 *   biome = namespace:biome_id    (required) full biome ID to match exactly
 *
 * OR use partial matching (checks if the biome path contains your string):
 *   biome_contains = some_substring
 *
 * You can only use ONE of these two per file.
 *
 * Works with ANY mod that registers biomes — not just vanilla Minecraft.
 * Examples:
 *   terralith:highlands         (Terralith biome)
 *   biomeswevegone:skyris_vale  (Biomes We've Gone biome)
 *   minecraft:deep_dark         (vanilla)
 *
 * ─── EXAMPLE: in_highlands.txt ───────────────────────────────────────────────
 *
 *   biome = terralith:highlands
 *
 * → Animation "in_highlands" = 1 when the player is in that exact biome.
 *
 * ─── EXAMPLE: in_any_highland.txt ────────────────────────────────────────────
 *
 *   biome_contains = highland
 *
 * → Animation "in_any_highland" = 1 when the biome path contains "highland"
 *   (catches terralith:highlands, terralith:highland_cliffs, etc.)
 *
 * ─── FINDING A BIOME'S ID ─────────────────────────────────────────────────────
 *
 * Run this command in-game:
 *   /locatebiome <tab-complete>    — browse all loaded biomes
 * Or press F3 — the current biome is shown in the debug overlay.
 *
 * Lines starting with # are comments and are ignored.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class BiomeTriggerLoader {

    public enum MatchMode {
        /** Exact full resource location match: namespace:path */
        EXACT,
        /** The biome's path component contains this string (case-insensitive) */
        CONTAINS
    }

    /**
     * One file = one custom biome trigger.
     * animationName = filename without .txt
     * Fires (value=1) when the current biome matches according to matchMode.
     */
    public static class BiomeAnimation {
        public final String    animationName;
        public final MatchMode matchMode;
        /** For EXACT: "namespace:path". For CONTAINS: the substring to check. */
        public final String    matchValue;

        public BiomeAnimation(String animationName, MatchMode matchMode, String matchValue) {
            this.animationName = animationName;
            this.matchMode     = matchMode;
            this.matchValue    = matchValue;
        }

        /** Returns true if the given full biome ID string matches this rule. */
        public boolean matches(String biomeId) {
            return switch (matchMode) {
                case EXACT    -> biomeId.equalsIgnoreCase(matchValue);
                case CONTAINS -> {
                    // Extract only the path portion (after the colon) for contains-check,
                    // matching the same convention as the built-in biome_is_* triggers.
                    int colon = biomeId.indexOf(':');
                    String path = colon >= 0 ? biomeId.substring(colon + 1) : biomeId;
                    yield path.toLowerCase().contains(matchValue.toLowerCase());
                }
            };
        }
    }

    private static List<BiomeAnimation> animations = Collections.emptyList();

    // ─── LOAD ────────────────────────────────────────────────────────────────

    public static void load() {
        Path biomesDir = XtraConfig.biomesDir();

        try {
            Files.createDirectories(biomesDir);
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Could not create biomes dir: {}", e.getMessage());
            return;
        }

        // Write example file if folder is empty
        boolean hasAnyTxt;
        try (Stream<Path> files = Files.list(biomesDir)) {
            hasAnyTxt = files.anyMatch(p -> p.toString().endsWith(".txt"));
        } catch (IOException e) {
            hasAnyTxt = false;
        }
        if (!hasAnyTxt) writeExampleFiles(biomesDir);

        // Write/refresh reference file
        Path refFile = biomesDir.resolve("BIOMES_REFERENCE.txt");
        if (!Files.exists(refFile)) {
            writeFile(biomesDir, "BIOMES_REFERENCE.txt", REFERENCE_FILE);
        }

        // Load every .txt file
        List<BiomeAnimation> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(biomesDir)) {
            files.filter(p -> p.toString().endsWith(".txt")
                      && !p.getFileName().toString().equals("BIOMES_REFERENCE.txt"))
                 .sorted()
                 .forEach(p -> {
                     BiomeAnimation anim = loadFile(p);
                     if (anim != null) loaded.add(anim);
                 });
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Error scanning biomes folder: {}", e.getMessage());
        }

        animations = Collections.unmodifiableList(loaded);
        XtraNimations.LOGGER.info("[XtraNimations] Loaded {} custom biome trigger(s) from config/xtranims/biomes/",
            animations.size());
    }

    public static List<BiomeAnimation> getAnimations() {
        return animations;
    }

    // ─── FILE PARSER ─────────────────────────────────────────────────────────

    private static BiomeAnimation loadFile(Path file) {
        String filename = file.getFileName().toString();
        String animName = filename.substring(0, filename.length() - 4); // strip .txt

        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            XtraNimations.LOGGER.warn("[XtraNimations] Could not read biomes/{}: {}", filename, e.getMessage());
            return null;
        }

        MatchMode matchMode  = null;
        String    matchValue = null;
        int lineNum = 0;

        for (String raw : lines) {
            lineNum++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int eq = line.indexOf('=');
            if (eq < 0) {
                XtraNimations.LOGGER.warn("[XtraNimations] biomes/{} line {}: expected 'key = value', got — «{}»",
                    filename, lineNum, line);
                continue;
            }

            String key = line.substring(0, eq).strip().toLowerCase();
            String val = line.substring(eq + 1).strip();

            if (key.equals("biome")) {
                if (matchMode != null) {
                    XtraNimations.LOGGER.warn(
                        "[XtraNimations] biomes/{}: cannot use both 'biome' and 'biome_contains' — skipping file",
                        filename);
                    return null;
                }
                // Validate it looks like a valid resource location
                ResourceLocation rl = ResourceLocation.tryParse(val);
                if (rl == null) {
                    XtraNimations.LOGGER.warn(
                        "[XtraNimations] biomes/{} line {}: invalid resource location '{}' — skipping file",
                        filename, lineNum, val);
                    return null;
                }
                matchMode  = MatchMode.EXACT;
                matchValue = val.toLowerCase();

            } else if (key.equals("biome_contains")) {
                if (matchMode != null) {
                    XtraNimations.LOGGER.warn(
                        "[XtraNimations] biomes/{}: cannot use both 'biome' and 'biome_contains' — skipping file",
                        filename);
                    return null;
                }
                if (val.isEmpty()) {
                    XtraNimations.LOGGER.warn(
                        "[XtraNimations] biomes/{} line {}: 'biome_contains' value is empty — skipping file",
                        filename, lineNum);
                    return null;
                }
                matchMode  = MatchMode.CONTAINS;
                matchValue = val.toLowerCase();
            }
        }

        if (matchMode == null) {
            XtraNimations.LOGGER.warn("[XtraNimations] biomes/{} has no 'biome =' or 'biome_contains =' line — skipping",
                filename);
            return null;
        }

        XtraNimations.LOGGER.info("[XtraNimations] Custom biome trigger '{}': mode={}, value={}",
            animName, matchMode, matchValue);
        return new BiomeAnimation(animName, matchMode, matchValue);
    }

    // ─── EXAMPLE / REFERENCE FILES ────────────────────────────────────────────

    private static void writeExampleFiles(Path dir) {
        writeFile(dir, "example_terralith_highlands.txt",
            "# Animation name in CPM editor: example_terralith_highlands\n" +
            "# Delete or rename this file — it is just an example.\n" +
            "#\n" +
            "# Exact biome match:\n" +
            "# biome = terralith:highlands\n" +
            "#\n" +
            "# Substring match (catches all biomes whose path contains 'highland'):\n" +
            "# biome_contains = highland\n");
    }

    private static final String REFERENCE_FILE =
        "# ╔══════════════════════════════════════════════════════════════════╗\n" +
        "# ║           XtraNimations — Custom Biomes Reference               ║\n" +
        "# ║                  DO NOT RENAME THIS FILE                        ║\n" +
        "# ╚══════════════════════════════════════════════════════════════════╝\n" +
        "#\n" +
        "# This file is for reference only and is ignored by the mod.\n" +
        "# Create your own .txt files in this folder to detect custom biomes.\n" +
        "#\n" +
        "# ─── HOW IT WORKS ───────────────────────────────────────────────────────\n" +
        "#\n" +
        "# Each .txt file you create defines ONE biome trigger.\n" +
        "# The filename (without .txt) = the animation name in the CPM editor.\n" +
        "#\n" +
        "# A trigger fires (value = 1) when the player is in the matched biome.\n" +
        "#\n" +
        "# Biome re-evaluation happens whenever the player moves to a new block\n" +
        "# column (X/Z position changes), and at most every 4 seconds as a safety net.\n" +
        "#\n" +
        "# ─── FILE FORMAT ────────────────────────────────────────────────────────\n" +
        "#\n" +
        "# OPTION A — Exact match (only fires for this specific biome):\n" +
        "#   biome = namespace:biome_id\n" +
        "#\n" +
        "# OPTION B — Substring match (fires for any biome whose path contains this):\n" +
        "#   biome_contains = substring\n" +
        "#\n" +
        "# Use ONE of these two per file — not both.\n" +
        "#\n" +
        "# ─── MULTI-ANIMATION TIP (freenamespace) ────────────────────────────────\n" +
        "#\n" +
        "# In the CPM editor you can drive multiple animations from the same file\n" +
        "# using the colon format — the part after : must contain at least one letter:\n" +
        "#\n" +
        "#   in_highlands:wing_spread\n" +
        "#   in_highlands:glow_effect\n" +
        "#   in_highlands:ambient_sound\n" +
        "#\n" +
        "# All receive the same 0/1 value from in_highlands.txt.\n" +
        "#\n" +
        "# ─── FINDING A BIOME'S ID ───────────────────────────────────────────────\n" +
        "#\n" +
        "# Method 1 — F3 debug screen:\n" +
        "#   Press F3 in-game. The current biome is shown in the top-right corner.\n" +
        "#   Example: Biome: terralith:highlands\n" +
        "#\n" +
        "# Method 2 — Tab-complete:\n" +
        "#   /locatebiome <press Tab>   — browse all loaded biomes by ID\n" +
        "#\n" +
        "# Method 3 — JEI / REI:\n" +
        "#   Some modpacks have biome book items that list IDs.\n" +
        "#\n" +
        "# ─── EXAMPLES ───────────────────────────────────────────────────────────\n" +
        "#\n" +
        "# ── in_terralith_highlands.txt ───────────────────────────────────────────\n" +
        "#   biome = terralith:highlands\n" +
        "#   → fires ONLY in terralith:highlands\n" +
        "#\n" +
        "# ── any_terralith_highlands.txt ──────────────────────────────────────────\n" +
        "#   biome_contains = highland\n" +
        "#   → fires in terralith:highlands, terralith:highland_cliffs, etc.\n" +
        "#\n" +
        "# ── deep_dark.txt ────────────────────────────────────────────────────────\n" +
        "#   biome = minecraft:deep_dark\n" +
        "#   → fires in the vanilla Deep Dark biome\n" +
        "#\n" +
        "# ── bwg_skyris.txt ───────────────────────────────────────────────────────\n" +
        "#   biome = biomeswevegone:skyris_vale\n" +
        "#   → fires in Biomes We've Gone's Skyris Vale\n" +
        "#\n" +
        "# ─────────────────────────────────────────────────────────────────────────\n";

    private static void writeFile(Path dir, String name, String content) {
        try {
            Files.writeString(dir.resolve(name), content);
            XtraNimations.LOGGER.info("[XtraNimations] Created biomes example: {}", name);
        } catch (IOException e) {
            XtraNimations.LOGGER.warn("[XtraNimations] Could not write biomes/{}: {}", name, e.getMessage());
        }
    }
}
