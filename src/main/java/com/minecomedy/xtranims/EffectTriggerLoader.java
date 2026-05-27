package com.minecomedy.xtranims;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * EffectTriggerLoader — loads custom potion/effect trigger definitions from config.
 *
 * Config folder: .minecraft/config/xtranims/effects/
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Each .txt file defines ONE effect trigger.
 *
 * The filename (without .txt) becomes the animation name used in the CPM editor.
 * It supports the :freenamespace colon syntax just like all other XtraNimations
 * triggers — create multiple animations in CPM with names like:
 *
 *   my_effect_trigger:layer_glow
 *   my_effect_trigger:gesture
 *
 * All receive the same value (0 or 1) from this loader.
 *
 * FILE FORMAT
 * ───────────
 *   effect = namespace:effect_id    (required) the effect to detect
 *
 * Works with ANY mod that registers its effects via Forge's registry — not just
 * vanilla Minecraft. Examples:
 *   irons_spellbooks:arcane_shield
 *   irons_spellbooks:mana_regen
 *   alexsmobs:toxin
 *   undergarden:rot
 *
 * ─── EXAMPLE: arcane_shield.txt ──────────────────────────────────────────────
 *
 *   effect = irons_spellbooks:arcane_shield
 *
 * → Animation "arcane_shield" = 1 whenever the player has the Arcane Shield
 *   effect from Iron's Spellbooks. Otherwise = 0.
 *
 * ─── EXAMPLE: modded_speed.txt ───────────────────────────────────────────────
 *
 *   effect = somecoolmod:super_speed
 *
 * → Animation "modded_speed" = 1 when the player has that effect.
 *
 * ─── FINDING AN EFFECT'S ID ───────────────────────────────────────────────────
 *
 * Run this command while the effect is active:
 *   /effect give @s <tab-complete to browse>
 * Or open F3 debug screen with the effect active; most mods show the effect ID.
 * You can also check the mod's source or a tool like Just Enough Items (JEI).
 *
 * Lines starting with # are comments and are ignored.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class EffectTriggerLoader {

    /**
     * One file = one custom effect trigger.
     * animationName = filename without .txt
     * Fires (value=1) when the player has the registered effect active.
     */
    public static class EffectAnimation {
        public final String animationName;
        public final MobEffect effect;

        public EffectAnimation(String animationName, MobEffect effect) {
            this.animationName = animationName;
            this.effect        = effect;
        }
    }

    private static List<EffectAnimation> animations = Collections.emptyList();

    // ─── LOAD ────────────────────────────────────────────────────────────────

    public static void load() {
        Path effectsDir = XtraConfig.effectsDir();

        try {
            Files.createDirectories(effectsDir);
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Could not create effects dir: {}", e.getMessage());
            return;
        }

        // Write example file if folder is empty
        boolean hasAnyTxt;
        try (Stream<Path> files = Files.list(effectsDir)) {
            hasAnyTxt = files.anyMatch(p -> p.toString().endsWith(".txt"));
        } catch (IOException e) {
            hasAnyTxt = false;
        }
        if (!hasAnyTxt) writeExampleFiles(effectsDir);

        // Write/refresh reference file
        Path refFile = effectsDir.resolve("EFFECTS_REFERENCE.txt");
        if (!Files.exists(refFile)) {
            writeFile(effectsDir, "EFFECTS_REFERENCE.txt", REFERENCE_FILE);
        }

        // Load every .txt file
        List<EffectAnimation> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(effectsDir)) {
            files.filter(p -> p.toString().endsWith(".txt")
                      && !p.getFileName().toString().equals("EFFECTS_REFERENCE.txt"))
                 .sorted()
                 .forEach(p -> {
                     EffectAnimation anim = loadFile(p);
                     if (anim != null) loaded.add(anim);
                 });
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Error scanning effects folder: {}", e.getMessage());
        }

        animations = Collections.unmodifiableList(loaded);
        XtraNimations.LOGGER.info("[XtraNimations] Loaded {} custom effect trigger(s) from config/xtranims/effects/",
            animations.size());
    }

    public static List<EffectAnimation> getAnimations() {
        return animations;
    }

    // ─── FILE PARSER ─────────────────────────────────────────────────────────

    private static EffectAnimation loadFile(Path file) {
        String filename = file.getFileName().toString();
        String animName = filename.substring(0, filename.length() - 4); // strip .txt

        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            XtraNimations.LOGGER.warn("[XtraNimations] Could not read effects/{}: {}", filename, e.getMessage());
            return null;
        }

        MobEffect effect = null;
        int lineNum = 0;

        for (String raw : lines) {
            lineNum++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int eq = line.indexOf('=');
            if (eq < 0) {
                XtraNimations.LOGGER.warn("[XtraNimations] effects/{} line {}: expected 'effect = id', got — «{}»",
                    filename, lineNum, line);
                continue;
            }

            String key = line.substring(0, eq).strip().toLowerCase();
            String val = line.substring(eq + 1).strip();

            if (key.equals("effect")) {
                ResourceLocation rl = ResourceLocation.tryParse(val);
                if (rl == null || !ForgeRegistries.MOB_EFFECTS.containsKey(rl)) {
                    XtraNimations.LOGGER.warn(
                        "[XtraNimations] effects/{} line {}: unknown effect '{}' — skipping file " +
                        "(make sure the mod providing this effect is loaded)",
                        filename, lineNum, val);
                    return null;
                }
                effect = ForgeRegistries.MOB_EFFECTS.getValue(rl);
            }
            // Future keys can go here
        }

        if (effect == null) {
            XtraNimations.LOGGER.warn("[XtraNimations] effects/{} has no 'effect =' line — skipping", filename);
            return null;
        }

        XtraNimations.LOGGER.info("[XtraNimations] Custom effect trigger '{}': effect={}",
            animName, ForgeRegistries.MOB_EFFECTS.getKey(effect));
        return new EffectAnimation(animName, effect);
    }

    // ─── EXAMPLE / REFERENCE FILES ────────────────────────────────────────────

    private static void writeExampleFiles(Path dir) {
        writeFile(dir, "example_arcane_shield.txt",
            "# Animation name in CPM editor: example_arcane_shield\n" +
            "# Delete or rename this file — it is just an example.\n" +
            "#\n" +
            "# Uncomment the line below and change it to your actual effect ID.\n" +
            "# effect = irons_spellbooks:arcane_shield\n");
    }

    private static final String REFERENCE_FILE =
        "# ╔══════════════════════════════════════════════════════════════════╗\n" +
        "# ║          XtraNimations — Custom Effects Reference               ║\n" +
        "# ║                  DO NOT RENAME THIS FILE                        ║\n" +
        "# ╚══════════════════════════════════════════════════════════════════╝\n" +
        "#\n" +
        "# This file is for reference only and is ignored by the mod.\n" +
        "# Create your own .txt files in this folder to detect custom effects.\n" +
        "#\n" +
        "# ─── HOW IT WORKS ───────────────────────────────────────────────────────\n" +
        "#\n" +
        "# Each .txt file you create defines ONE effect trigger.\n" +
        "# The filename (without .txt) = the animation name in the CPM editor.\n" +
        "#\n" +
        "# A trigger fires (value = 1) when the player has that effect active.\n" +
        "#\n" +
        "# ─── FILE FORMAT ────────────────────────────────────────────────────────\n" +
        "#\n" +
        "#   effect = namespace:effect_id    (the only required line)\n" +
        "#\n" +
        "# Works with any mod effect that is registered through Forge's registry.\n" +
        "# Vanilla effects work too, but XtraNimations already provides built-in\n" +
        "# triggers for all vanilla effects (has_speed, has_strength, etc.).\n" +
        "# Use this folder only for modded or extra effects you need.\n" +
        "#\n" +
        "# ─── MULTI-ANIMATION TIP (freenamespace) ────────────────────────────────\n" +
        "#\n" +
        "# In the CPM editor you can drive multiple animations from the same file\n" +
        "# using the colon format — the part after : must contain at least one letter:\n" +
        "#\n" +
        "#   arcane_shield:glow_layer\n" +
        "#   arcane_shield:gesture\n" +
        "#   arcane_shield:particle_rings\n" +
        "#\n" +
        "# All receive the same 0/1 value from arcane_shield.txt.\n" +
        "#\n" +
        "# ─── FINDING AN EFFECT'S ID ─────────────────────────────────────────────\n" +
        "#\n" +
        "# Method 1 — Tab-complete in chat:\n" +
        "#   /effect give @s <press Tab>   — browse all loaded effects\n" +
        "#\n" +
        "# Method 2 — F3 debug screen:\n" +
        "#   Have the effect active and press F3; most mods display the effect ID.\n" +
        "#\n" +
        "# Method 3 — Just Enough Items / REI:\n" +
        "#   Search for the effect in JEI/REI to find its registry ID.\n" +
        "#\n" +
        "# ─── EXAMPLES ───────────────────────────────────────────────────────────\n" +
        "#\n" +
        "# ── arcane_shield.txt ────────────────────────────────────────────────────\n" +
        "#   effect = irons_spellbooks:arcane_shield\n" +
        "#   → plays when the player has Iron's Spellbooks' Arcane Shield effect\n" +
        "#\n" +
        "# ── terracurse.txt ───────────────────────────────────────────────────────\n" +
        "#   effect = undergarden:rot\n" +
        "#   → plays when the player has the Rot effect from The Undergarden\n" +
        "#\n" +
        "# ── super_speed.txt ──────────────────────────────────────────────────────\n" +
        "#   effect = somecoolmod:super_speed\n" +
        "#   → plays when the player has that custom speed effect\n" +
        "#\n" +
        "# ─────────────────────────────────────────────────────────────────────────\n";

    private static void writeFile(Path dir, String name, String content) {
        try {
            Files.writeString(dir.resolve(name), content);
            XtraNimations.LOGGER.info("[XtraNimations] Created effects example: {}", name);
        } catch (IOException e) {
            XtraNimations.LOGGER.warn("[XtraNimations] Could not write effects/{}: {}", name, e.getMessage());
        }
    }
}
