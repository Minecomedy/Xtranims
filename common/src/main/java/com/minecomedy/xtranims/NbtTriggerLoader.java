package com.minecomedy.xtranims;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * NbtTriggerLoader — loads per-item NBT condition files from config.
 *
 * Config folder: .minecraft/config/xtranims/
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Each .txt file defines ONE animation that plays when ALL its conditions
 * are met simultaneously (AND logic).
 *
 * The filename (without .txt) is the exact animation name to use in the
 * CPM editor.
 *
 * FILE FORMAT
 * ───────────
 *   item = namespace:item_id          (required) which item to check
 *   hand = main / off / both          (optional, default: both)
 *   render_item = true / false        (optional, default: true)
 *                                     set false to hide the held item while
 *                                     the animation is active (the CPM model
 *                                     animation still plays normally)
 *   condition_line                    (one or more, ALL must be true)
 *
 * Each condition line:
 *   nbt.path  operator  [value]
 *
 * ─── EXAMPLE: diamond_sword.txt ──────────────────────────────────────────────
 *
 *   item = minecraft:diamond_sword
 *   tag.Enchantments not_empty
 *   tag.Damage > 100
 *
 * → Animation "diamond_sword" = 1 only when holding a diamond sword
 *   that has enchantments AND has taken more than 100 damage.
 *   Otherwise = 0.
 *
 * ─── EXAMPLE: diamond_sword2.txt ─────────────────────────────────────────────
 *
 *   item = minecraft:diamond_sword
 *   tag.display.Name exists
 *
 * → Animation "diamond_sword2" = 1 only when holding a named diamond sword.
 *
 * ─── EXAMPLE: mage_staff_charged.txt ─────────────────────────────────────────
 *
 *   item = irons_spellbooks:mage_staff
 *   hand = main
 *   tag.Charged > 0
 *   tag.Mana > 50
 *
 * → Animation "mage_staff_charged" = 1 when holding a charged mage staff
 *   with more than 50 mana.
 *
 * ─── OPERATORS ────────────────────────────────────────────────────────────────
 *   exists             → true if the NBT key exists
 *   not_empty          → true if list/string/compound is non-empty
 *   == text            → true if string matches (case-insensitive)
 *   != text            → true if string does not match
 *   >  number          → true if numeric value greater than
 *   >= number          → true if numeric value greater or equal
 *   <  number          → true if numeric value less than
 *   <= number          → true if numeric value less or equal
 *
 * Lines starting with # are comments and are ignored.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class NbtTriggerLoader {

    public enum Op {
        EXISTS, NOT_EMPTY, EQUALS, NOT_EQUALS, GT, GTE, LT, LTE, HAS_ENCHANT
    }

    public enum Hand { MAIN, OFF, BOTH }

    /** One condition within a file — all conditions must pass for the animation to fire. */
    public static class NbtCondition {
        public final String[] nbtPath;
        public final Op op;
        public final String compareValue;
        public final int compareInt;

        public NbtCondition(String[] nbtPath, Op op, String compareValue, int compareInt) {
            this.nbtPath      = nbtPath;
            this.op           = op;
            this.compareValue = compareValue;
            this.compareInt   = compareInt;
        }
    }

    /**
     * One file = one animation.
     * animationName = filename without .txt
     * Fires (value=1) when the item matches AND all conditions pass.
     */
    public static class ItemAnimation {
        public final String animationName;
        public final Item item;
        public final Hand hand;
        public final List<NbtCondition> conditions;

        public ItemAnimation(String animationName, Item item, Hand hand,
                             List<NbtCondition> conditions) {
            this.animationName = animationName;
            this.item          = item;
            this.hand          = hand;
            this.conditions    = conditions;
        }

        public boolean itemMatches(ItemStack stack) {
            return stack.is(item);
        }
    }

    private static List<ItemAnimation> animations = Collections.emptyList();

    // ─── LOAD ────────────────────────────────────────────────────────────────

    public static void load() {
        Path rootDir      = XtraConfig.configDir();
        Path configDir    = XtraConfig.animationsDir(); // animations/ subfolder

        try {
            Files.createDirectories(rootDir);
            Files.createDirectories(configDir);
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Could not create config dirs: {}", e.getMessage());
            return;
        }

        // ── Migrate legacy files from root → animations/ ─────────────────────
        // Before this refactor, animation .txt files lived directly in config/xtranims/.
        // On first run after the update, move any .txt files found there (except
        // XTRANIMS_REFERENCE.txt and General_Config.txt) into animations/ automatically.
        try (Stream<Path> rootFiles = Files.list(rootDir)) {
            rootFiles.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".txt")
                    && !name.equals("XTRANIMS_REFERENCE.txt")
                    && !name.equals("General_Config.txt")
                    && Files.isRegularFile(p);
            }).forEach(p -> {
                Path dest = configDir.resolve(p.getFileName());
                try {
                    if (!Files.exists(dest)) {
                        Files.move(p, dest);
                        XtraNimations.LOGGER.info("[XtraNimations] Migrated {} → animations/", p.getFileName());
                    } else {
                        // File already exists in animations/, just delete the old one
                        Files.delete(p);
                        XtraNimations.LOGGER.info("[XtraNimations] Removed duplicate root file {} (animations/ copy kept)", p.getFileName());
                    }
                } catch (IOException ex) {
                    XtraNimations.LOGGER.warn("[XtraNimations] Could not migrate {}: {}", p.getFileName(), ex.getMessage());
                }
            });
        } catch (IOException e) {
            XtraNimations.LOGGER.warn("[XtraNimations] Migration scan failed: {}", e.getMessage());
        }

        // Write example files if the folder has no .txt files yet
        boolean hasAnyTxt;
        try (Stream<Path> files = Files.list(configDir)) {
            hasAnyTxt = files.anyMatch(p -> p.toString().endsWith(".txt"));
        } catch (IOException e) {
            hasAnyTxt = false;
        }
        // Reference file lives in the ROOT folder, not in animations/
        Path refFile = rootDir.resolve("XTRANIMS_REFERENCE.txt");
        if (!Files.exists(refFile)) {
            writeFile(rootDir, "XTRANIMS_REFERENCE.txt", REFERENCE_FILE);
        }

        if (!hasAnyTxt) writeExampleFiles(configDir);

        // Load every .txt file
        List<ItemAnimation> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(configDir)) {
            files.filter(p -> p.toString().endsWith(".txt")
                      && !p.getFileName().toString().equals("XTRANIMS_REFERENCE.txt"))
                 .sorted()
                 .forEach(p -> {
                     ItemAnimation anim = loadFile(p);
                     if (anim != null) loaded.add(anim);
                 });
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Error scanning config folder: {}", e.getMessage());
        }

        animations = Collections.unmodifiableList(loaded);
        XtraNimations.LOGGER.info("[XtraNimations] Loaded {} item animation(s) from config/xtranims/animations/",
            animations.size());
    }

    public static List<ItemAnimation> getAnimations() {
        return animations;
    }

    // ─── FILE PARSER ─────────────────────────────────────────────────────────

    private static ItemAnimation loadFile(Path file) {
        String filename    = file.getFileName().toString();
        String animName    = filename.substring(0, filename.length() - 4); // strip .txt

        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            XtraNimations.LOGGER.warn("[XtraNimations] Could not read {}: {}", filename, e.getMessage());
            return null;
        }

        Item   item       = null;
        Hand   hand       = Hand.BOTH;
        List<NbtCondition> conditions = new ArrayList<>();
        int lineNum = 0;

        for (String raw : lines) {
            lineNum++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // Check for key = value meta lines (item, hand)
            int eq = line.indexOf('=');
            if (eq > 0) {
                String key = line.substring(0, eq).strip().toLowerCase();
                String val = line.substring(eq + 1).strip();

                if (key.equals("item")) {
                    ResourceLocation rl = ResourceLocation.tryParse(val);
                    if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
                        XtraNimations.LOGGER.warn(
                            "[XtraNimations] {} line {}: unknown item '{}' — skipping file",
                            filename, lineNum, val);
                        return null;
                    }
                    item = BuiltInRegistries.ITEM.get(rl);
                    continue;
                }

                if (key.equals("hand")) {
                    hand = switch (val.toLowerCase()) {
                        case "main" -> Hand.MAIN;
                        case "off"  -> Hand.OFF;
                        default     -> Hand.BOTH;
                    };
                    continue;
                }
            }

            // Everything else is a condition: nbt.path operator [value]
            NbtCondition cond = parseCondition(line, filename, lineNum);
            if (cond != null) conditions.add(cond);
        }

        if (item == null) {
            XtraNimations.LOGGER.warn("[XtraNimations] {} has no 'item =' line — skipping", filename);
            return null;
        }
        if (conditions.isEmpty()) {
            XtraNimations.LOGGER.warn("[XtraNimations] {} has no conditions — skipping", filename);
            return null;
        }

        XtraNimations.LOGGER.info(
            "[XtraNimations] '{}': item={}, hand={}, conditions={}",
            animName, BuiltInRegistries.ITEM.getKey(item), hand, conditions.size());
        return new ItemAnimation(animName, item, hand, conditions);
    }

    private static NbtCondition parseCondition(String line, String filename, int lineNum) {
        String[] parts = splitOnOperator(line);
        if (parts == null) {
            warn(filename, lineNum, "could not parse condition", line);
            return null;
        }

        String pathStr = parts[0].strip();
        String opStr   = parts[1].strip().toLowerCase();
        String valStr  = parts[2].strip();

        if (pathStr.isEmpty()) {
            warn(filename, lineNum, "empty NBT path", line);
            return null;
        }

        String[] nbtPath = pathStr.split("\\.");
        Op     op           = null;
        String compareValue = "";
        int    compareInt   = 0;

        try {
            op = switch (opStr) {
                case "exists"      -> Op.EXISTS;
                case "not_empty"   -> Op.NOT_EMPTY;
                case "has_enchant" -> Op.HAS_ENCHANT;
                case "=="          -> Op.EQUALS;
                case "!="          -> Op.NOT_EQUALS;
                case ">"           -> Op.GT;
                case ">="          -> Op.GTE;
                case "<"           -> Op.LT;
                case "<="          -> Op.LTE;
                default            -> null;
            };
            if (op == null) {
                warn(filename, lineNum, "unknown operator '" + opStr + "'", line);
                return null;
            }
            if (op == Op.EQUALS || op == Op.NOT_EQUALS || op == Op.HAS_ENCHANT) {
                compareValue = valStr;
            } else if (op != Op.EXISTS && op != Op.NOT_EMPTY) {
                compareInt = Integer.parseInt(valStr);
            }
        } catch (NumberFormatException e) {
            warn(filename, lineNum, "expected integer, got '" + valStr + "'", line);
            return null;
        }

        return new NbtCondition(nbtPath, op, compareValue, compareInt);
    }

    // ─── OPERATOR SPLITTER ────────────────────────────────────────────────────

    private static String[] splitOnOperator(String line) {
        for (String op : new String[]{">=", "<=", "!=", "=="}) {
            int idx = line.indexOf(op);
            if (idx > 0)
                return new String[]{ line.substring(0, idx), op, line.substring(idx + op.length()).strip() };
        }
        for (String op : new String[]{">", "<"}) {
            int idx = line.indexOf(op);
            if (idx > 0)
                return new String[]{ line.substring(0, idx), op, line.substring(idx + 1).strip() };
        }
        for (String kw : new String[]{"not_empty", "has_enchant", "exists"}) {
            int idx = line.indexOf(kw);
            if (idx > 0) {
                String after = line.substring(idx + kw.length()).strip();
                return new String[]{ line.substring(0, idx), kw, after };
            }
        }
        return null;
    }

    private static void warn(String file, int line, String msg, String content) {
        XtraNimations.LOGGER.warn("[XtraNimations] {} line {}: {} — «{}»", file, line, msg, content);
    }

    // ─── DEFAULT EXAMPLE FILES ────────────────────────────────────────────────

    // ─── REFERENCE FILE ──────────────────────────────────────────────────────

    private static final String REFERENCE_FILE =
        "# ╔══════════════════════════════════════════════════════════════════╗\n" +
        "# ║           XtraNimations — Item Animation Reference              ║\n" +
        "# ║                  DO NOT RENAME THIS FILE                        ║\n" +
        "# ╚══════════════════════════════════════════════════════════════════╝\n" +
        "#\n" +
        "# This file is for reference only and is ignored by the mod.\n" +
        "# Create your own .txt files in this folder to add item animations.\n" +
        "#\n" +
        "# ─── HOW IT WORKS ───────────────────────────────────────────────────────\n" +
        "#\n" +
        "# Each .txt file you create defines ONE animation.\n" +
        "# The filename (without .txt) = the animation name in the CPM editor.\n" +
        "#\n" +
        "# An animation fires (value = 1) when:\n" +
        "#   • The player is holding the specified item, AND\n" +
        "#   • ALL conditions in the file are true simultaneously\n" +
        "#\n" +
        "# If any condition fails, the animation is set to 0.\n" +
        "#\n" +
        "# You can have multiple files for the same item with different conditions,\n" +
        "# each one controlling a different animation independently.\n" +
        "#\n" +
        "# ─── FILE FORMAT ────────────────────────────────────────────────────────\n" +
        "#\n" +
        "#   item = namespace:item_id        (REQUIRED) item to check\n" +
        "#   hand = main / off / both        (optional, default: both)\n" +
        "#   condition lines...              (one or more, ALL must be true)\n" +
        "#\n" +
        "# ─── MULTI-ANIMATION TIP ────────────────────────────────────────────────────────\n" +
        "#\n" +
        "# In the CPM editor you can create multiple animations driven by the\n" +
        "# same file using the colon format:\n" +
        "#\n" +
        "#   my_sword:glow_layer\n" +
        "#   my_sword:gesture\n" +
        "#   my_sword:particle_effect\n" +
        "#\n" +
        "# All of them receive the same value from my_sword.txt.\n" +
        "#\n" +
        "# IMPORTANT — avoid pure-number labels like :1 or :2.\n" +
        "# CPM internally uses numeric suffixes for its own animation slot system,\n" +
        "# so XtraNimations ignores any label that contains only digits to avoid\n" +
        "# conflicts with CPM's internals.\n" +
        "# Use descriptive labels instead: :layer1  :glow  :anim_a  :2nd_effect\n" +
        "# (anything with at least one letter is fine).\n" +
        "#\n" +
        "# ─── HOW NBT TAGS WORK ────────────────────────────────────────────────────────\n" +
        "#\n" +
        "# Every item in Minecraft can carry extra data called NBT tags. This is\n" +
        "# how the game stores enchantments, durability, custom names, and any\n" +
        "# custom data added by mods. XtraNimations reads this data and uses it\n" +
        "# as animation triggers.\n" +
        "#\n" +
        "# This works with ANY mod that stores data in item NBT — you are not\n" +
        "# limited to vanilla Minecraft. Examples:\n" +
        "#   Apotheosis        → tag.socket_data, tag.gem_count, socket gems\n" +
        "#   Iron's Spellbooks → tag.Mana, tag.Charged, tag.SpellWheel\n" +
        "#   Tetra             → tag.ModularItem and module data\n" +
        "#   Any other mod     → inspect with /data get entity @s SelectedItem\n" +
        "#\n" +
        "# ─── OPERATORS ───────────────────────────────────────────────────────────────────\n" +
        "#\n" +
        "#  exists              → true if the NBT key exists at all\n" +
        "#  not_empty           → true if list/string/compound is non-empty\n" +
        "#  has_enchant <id>    → true if the item has that specific enchantment\n" +
        "#                        checks both Enchantments and StoredEnchantments\n" +
        "#                        namespace optional: sharpness or minecraft:sharpness\n" +
        "#                        works with modded enchantments too!\n" +
        "#                        e.g.  has_enchant apotheosis:socket\n" +
        "#                              has_enchant irons_spellbooks:mana_regen\n" +
        "#  == text             → true if string value matches (case-insensitive)\n" +
        "#  != text             → true if string value does NOT match\n" +
        "#  >  number           → true if numeric value is greater than\n" +
        "#  >= number           → true if numeric value is greater than or equal\n" +
        "#  <  number           → true if numeric value is less than\n" +
        "#  <= number           → true if numeric value is less than or equal\n" +
        "#\n" +
        "# ─── FINDING AN ITEM'S NBT ──────────────────────────────────────────────\n" +
        "#\n" +
        "# Hold the item and run this command in-game:\n" +
        "#   /data get entity @s SelectedItem\n" +
        "#\n" +
        "# Example output:\n" +
        "#   {id:\"minecraft:diamond_sword\", Count:1b, tag:{\n" +
        "#     Damage: 47,\n" +
        "#     Enchantments: [{id:\"sharpness\", lvl:3}],\n" +
        "#     display: {Name: \'{\"text\":\"Excalibur\"}\'}\n" +
        "#   }}\n" +
        "#\n" +
        "# The path starts inside tag:{} — use dots to go deeper:\n" +
        "#   tag.Damage         → the Damage value\n" +
        "#   tag.Enchantments   → the Enchantments list\n" +
        "#   tag.display.Name   → the custom name string\n" +
        "#\n" +
        "# ─── FULL EXAMPLES ──────────────────────────────────────────────────────\n" +
        "#\n" +
        "# ── example: enchanted_diamond_sword.txt ────────────────────────────────\n" +
        "#\n" +
        "#   item = minecraft:diamond_sword\n" +
        "#   tag.Enchantments not_empty\n" +
        "#\n" +
        "#   → Animation \"enchanted_diamond_sword\" plays when holding any\n" +
        "#     enchanted diamond sword.\n" +
        "#\n" +
        "# ── example: damaged_sword.txt ──────────────────────────────────────────\n" +
        "#\n" +
        "#   item = minecraft:iron_sword\n" +
        "#   tag.Damage > 150\n" +
        "#\n" +
        "#   → Animation \"damaged_sword\" plays when the iron sword has taken\n" +
        "#     more than 150 damage (iron sword max = 250).\n" +
        "#\n" +
        "# ── example: named_weapon.txt ───────────────────────────────────────────\n" +
        "#\n" +
        "#   item = minecraft:netherite_sword\n" +
        "#   tag.display.Name exists\n" +
        "#   tag.Enchantments not_empty\n" +
        "#\n" +
        "#   → Animation \"named_weapon\" plays only when holding a netherite\n" +
        "#     sword that is BOTH custom-named AND enchanted.\n" +
        "#\n" +
        "# ── example: custom_model.txt ───────────────────────────────────────────\n" +
        "#\n" +
        "#   item = minecraft:stick\n" +
        "#   hand = main\n" +
        "#   tag.CustomModelData == 1001\n" +
        "#\n" +
        "#   → Animation \"custom_model\" plays when holding a stick with\n" +
        "#     CustomModelData exactly equal to 1001 in the main hand.\n" +
        "#     Useful for resource pack custom item variants.\n" +
        "#\n" +
        "# ── example: enchanted_combo.txt ──────────────────────────────────────────\n" +
"#\n" +
"#   item = minecraft:diamond_sword\n" +
"#   tag.Enchantments has_enchant sharpness\n" +
"#   tag.Enchantments has_enchant fire_aspect\n" +
"#\n" +
"#   → Animation \"enchanted_combo\" plays only when the sword has BOTH\n" +
"#     Sharpness AND Fire Aspect. Adding more has_enchant lines adds more\n" +
"#     required enchantments.\n" +
"#\n" +
"# ── example: mage_staff_charged.txt (modded item) ───────────────────────\n" +
        "#\n" +
        "#   item = irons_spellbooks:mage_staff\n" +
        "#   hand = main\n" +
        "#   tag.Charged > 0\n" +
        "#   tag.Mana > 50\n" +
        "#\n" +
        "#   → Animation \"mage_staff_charged\" plays when the mage staff is\n" +
        "#     charged AND has more than 50 mana.\n" +
        "#\n" +
        "# ── example: off_hand_totem.txt ─────────────────────────────────────────\n" +
        "#\n" +
        "#   item = minecraft:totem_of_undying\n" +
        "#   hand = off\n" +
        "#   tag.CustomModelData exists\n" +
        "#\n" +
        "#   → Animation \"off_hand_totem\" plays when a customized totem\n" +
        "#     is in the OFF hand specifically.\n" +
        "#\n" +
        "# ────────────────────────────────────────────────────────────────────────\n";


    private static void writeExampleFiles(Path configDir) {
        writeFile(configDir, "diamond_sword.txt",
            "# Animation name in CPM editor: diamond_sword\n" +
            "# Plays (value=1) when ALL conditions below are met simultaneously.\n" +
            "#\n" +
            "item = minecraft:diamond_sword\n" +
            "# hand = both\n" +
            "\n" +
            "# Conditions — ALL must be true for the animation to fire:\n" +
            "# tag.Enchantments not_empty\n" +
            "# tag.Damage > 0\n");

        writeFile(configDir, "diamond_sword2.txt",
            "# Animation name in CPM editor: diamond_sword2\n" +
            "# A second animation for the same item but different conditions.\n" +
            "#\n" +
            "item = minecraft:diamond_sword\n" +
            "\n" +
            "# tag.display.Name exists\n");
    }

    private static void writeFile(Path dir, String name, String content) {
        try {
            Files.writeString(dir.resolve(name), content);
            XtraNimations.LOGGER.info("[XtraNimations] Created example: {}", name);
        } catch (IOException e) {
            XtraNimations.LOGGER.warn("[XtraNimations] Could not write {}: {}", name, e.getMessage());
        }
    }
}
