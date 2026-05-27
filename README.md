# XtraNimations

An open-source Forge 1.20.1 addon for [Customizable Player Models (CPM)](https://www.curseforge.com/minecraft/mc-mods/custom-player-models) that adds many new animation triggers — including full support for modded effects, modded biomes, and custom NBT-driven item animations.

---

## Features

### 🔢 How Values Work in CPM

All triggers send a value of **0 or 1** (off/on) to CPM's animation system unless stated otherwise. Use them in the CPM editor with:

```
pluginValue("trigger_name_here")
```

For example, to show a glowing effect only while the player is on fire:
```
pluginValue("is_on_fire") > 0.5
```

---

### 🌙 Moon Phase Triggers

| Trigger Name | Values | Description |
|---|---|---|
| `moon_phase_0` | 0 or 1 | Full Moon — 1 only at night during this phase |
| `moon_phase_1` | 0 or 1 | Waning Gibbous — 1 only at night during this phase |
| `moon_phase_2` | 0 or 1 | Last Quarter — 1 only at night during this phase |
| `moon_phase_3` | 0 or 1 | Waning Crescent — 1 only at night during this phase |
| `moon_phase_4` | 0 or 1 | New Moon — 1 only at night during this phase |
| `moon_phase_5` | 0 or 1 | Waxing Crescent — 1 only at night during this phase |
| `moon_phase_6` | 0 or 1 | First Quarter — 1 only at night during this phase |
| `moon_phase_7` | 0 or 1 | Waxing Gibbous — 1 only at night during this phase |
| `time_of_day` | 0–23 | Current hour of the Minecraft day. 0 = midnight, 6 = dawn, 12 = noon, 18 = dusk. Night begins at roughly hour 13. |

> **Note:** Only one `moon_phase_X` can be 1 at a time, and only while it is night (raw day-time ≥ 13000). All eight are 0 during daytime.

---

### 🧪 Potion Effect Triggers

All vanilla effects are built in. For **modded effects**, see [Custom Effect Triggers](#-custom-effect-triggers-modded) below.

| Trigger Name | Description |
|---|---|
| `has_speed` | Speed |
| `has_slowness` | Slowness |
| `has_haste` | Haste |
| `has_mining_fatigue` | Mining Fatigue |
| `has_strength` | Strength |
| `has_weakness` | Weakness |
| `has_regeneration` | Regeneration |
| `has_poison` | Poison |
| `has_wither` | Wither |
| `has_fire_resistance` | Fire Resistance |
| `has_water_breathing` | Water Breathing |
| `has_invisibility` | Invisibility |
| `has_night_vision` | Night Vision |
| `has_blindness` | Blindness |
| `has_nausea` | Nausea |
| `has_levitation` | Levitation |
| `has_slow_falling` | Slow Falling |
| `has_absorption` | Absorption |
| `has_glowing` | Glowing |
| `has_hunger` | Hunger |
| `has_saturation` | Saturation |
| `has_luck` | Luck |
| `has_bad_luck` | Bad Luck |
| `has_dolphins_grace` | Dolphin's Grace |
| `has_conduit_power` | Conduit Power |
| `has_hero_of_village` | Hero of the Village |
| `has_bad_omen` | Bad Omen |

---

### 🧪 Custom Effect Triggers (Modded)

You can detect **any modded effect** (Iron's Spellbooks, Alex's Mobs, etc.) by creating `.txt` files in:

```
.minecraft/config/xtranims/effects/
```

**File format** — one line is all you need:

```
effect = irons_spellbooks:arcane_shield
```

The **filename** (without `.txt`) becomes the animation name in the CPM editor. A reference file (`EFFECTS_REFERENCE.txt`) with full documentation is generated automatically on first launch.

**Example files:**

`arcane_shield.txt`
```
effect = irons_spellbooks:arcane_shield
```
→ CPM animation `arcane_shield` = 1 when the player has that effect.

`mana_regen.txt`
```
effect = irons_spellbooks:mana_regen
```
→ CPM animation `mana_regen` = 1 when the player has Mana Regen.

> **How to find an effect's ID:** Use `/effect give @s` and press Tab to browse all loaded effects, or press F3 while the effect is active.

---

### 🌿 Biome Triggers (Built-in)

| Trigger Name | Description |
|---|---|
| `biome_temperature` | 0–100 scaled from biome base temperature. Vanilla range is roughly −0.5 (frozen) to 2.0 (nether) — temperate biomes like plains sit around 0.8 (≈53). |
| `biome_is_snowy` | 1 if temperature < 0.15 |
| `biome_is_dry` | 1 if temperature > 0.9 |
| `biome_is_ocean` | 1 if biome name contains "ocean" |
| `biome_is_desert` | 1 if biome name contains "desert" |
| `biome_is_forest` | 1 if biome name contains "forest" |
| `biome_is_swamp` | 1 if biome name contains "swamp" |
| `biome_is_jungle` | 1 if biome name contains "jungle" |
| `biome_is_savanna` | 1 if biome name contains "savanna" |
| `biome_is_nether` | 1 if in the Nether dimension |
| `biome_is_the_end` | 1 if in The End dimension |
| `biome_is_underground` | 1 if player Y < 0 |
| `y_level` | 0–100 scaled from Y=-64 to Y=320 |

---

### 🌿 Custom Biome Triggers (Modded)

You can detect **any modded biome** (Terralith, Biomes We've Gone, etc.) by creating `.txt` files in:

```
.minecraft/config/xtranims/biomes/
```

**File format — two options:**

```
# Option A: exact match (specific biome only)
biome = terralith:highlands

# Option B: substring match (any biome whose path contains this)
biome_contains = highland
```

Use only **one** of these two per file. The **filename** (without `.txt`) becomes the animation name. A reference file (`BIOMES_REFERENCE.txt`) with full documentation is generated automatically on first launch.

**Example files:**

`in_highlands.txt`
```
biome = terralith:highlands
```
→ CPM animation `in_highlands` = 1 only in `terralith:highlands`.

`any_highland.txt`
```
biome_contains = highland
```
→ CPM animation `any_highland` = 1 in any biome whose path contains "highland" (`terralith:highlands`, `terralith:highland_cliffs`, etc.).

`deep_dark.txt`
```
biome = minecraft:deep_dark
```
→ CPM animation `deep_dark` = 1 in the vanilla Deep Dark.

> **How to find a biome's ID:** Press **F3** in-game — the current biome is shown in the top-right corner. Or use `/locatebiome` with Tab to browse all loaded biomes.

---

### ⛅ Weather Triggers

> These update even while a GUI or inventory screen is open, the same as health, food, potion effects, moon phase, and `is_on_fire`. Movement-based triggers (`is_sprinting`, `is_sneaking`, etc.) and item/biome triggers pause while a screen is open.

| Trigger Name | Description |
|---|---|
| `is_raining` | 1 when it is raining |
| `is_thundering` | 1 when there is a thunderstorm |

---

### 🔥 Player State Triggers

| Trigger Name | Description |
|---|---|
| `is_on_fire` | 1 when the player is on fire |
| `is_sprinting` | 1 when sprinting |
| `is_sneaking` | 1 when sneaking / crouching |
| `is_swimming` | 1 when in swim animation |
| `is_underwater` | 1 when fully submerged |
| `is_in_water` | 1 when touching water |
| `is_in_lava` | 1 when touching lava |
| `is_elytra_fly` | 1 when gliding with elytra |
| `is_invisible` | 1 when invisible (any source) |

---

### ❤️ Player Stat Triggers

| Trigger Name | Values | Description |
|---|---|---|
| `health_pct` | 0–100 | Current health as a percentage of max health |
| `food_pct` | 0–100 | Food level × 5 (20 food = 100) |
| `air_pct` | 0–100 | Air supply percentage (100 = full, 0 = drowning) |
| `xp_level` | 0–100 | XP level, capped at 100 |
| `armor_value` | 0–20 | Total armor points |

---

### 🎒 Item / Inventory Triggers

| Trigger Name | Description |
|---|---|
| `holding_sword` | 1 if main hand holds any sword |
| `holding_bow` | 1 if main hand holds a bow |
| `holding_crossbow` | 1 if main hand holds a crossbow |
| `holding_trident` | 1 if main hand holds a trident |
| `holding_shield` | 1 if either hand holds a shield |
| `holding_axe` | 1 if main hand holds any axe |
| `holding_pickaxe` | 1 if main hand holds any pickaxe |
| `holding_shovel` | 1 if main hand holds any shovel |
| `holding_hoe` | 1 if main hand holds any hoe |
| `holding_tool` | 1 if main hand holds any tool (axe/pickaxe/shovel/hoe) |
| `holding_book` | 1 if main hand holds any book |
| `holding_food` | 1 if main hand holds any edible item |
| `holding_staff` | 1 if main hand holds a stick |
| `main_hand_empty` | 1 if main hand is empty |
| `off_hand_empty` | 1 if off hand is empty |
| `hotbar_slot` | 0–8: currently selected hotbar slot |

---

### 📦 Custom NBT Item Triggers

You can trigger animations based on **what item the player is holding and its NBT data** — including items from any mod. Create `.txt` files in:

```
.minecraft/config/xtranims/animations/
```

**File format:**

```
item = namespace:item_id          (required)
hand = main / off / both          (optional, default: both)

nbt.path  operator  [value]       (one or more conditions — ALL must be true)
```

**Operators:**

| Operator | Meaning |
|---|---|
| `exists` | NBT key exists |
| `not_empty` | List/string/compound is non-empty |
| `has_enchant <id>` | Item has that enchantment (vanilla or modded) |
| `== text` | String matches (case-insensitive) |
| `!= text` | String does not match |
| `> number` | Numeric value greater than |
| `>= number` | Numeric value greater than or equal |
| `< number` | Numeric value less than |
| `<= number` | Numeric value less than or equal |

**Example files:**

`enchanted_sword.txt`
```
item = minecraft:diamond_sword
tag.Enchantments not_empty
```
→ Animation `enchanted_sword` = 1 when holding any enchanted diamond sword.

`mage_staff_charged.txt`
```
item = irons_spellbooks:mage_staff
hand = main
tag.Charged > 0
tag.Mana > 50
```
→ Animation `mage_staff_charged` = 1 when holding a charged mage staff with > 50 mana.

> **How to find an item's NBT:** Hold the item and run `/data get entity @s SelectedItem` in-game. A reference file (`XTRANIMS_REFERENCE.txt`) with full documentation is generated automatically on first launch.

---

### 🎨 RGB Color System

XtraNimations includes an RGB color picker (open with the configured keybind) that lets you set custom colors for model parts. This system syncs colors to other players and persists per model file.

---

## 🔀 Freenamespace — Multiple Animations Per Trigger

CPM does not allow two animations to share the same name. XtraNimations solves this with **freenamespace**: append `:anydescription` to the animation name in the CPM editor to create multiple distinct animations all driven by the same trigger.

**Example:** You have `is_on_fire` and want both a glow layer and a particle ring. Name them in CPM:
```
is_on_fire:glow_layer
is_on_fire:particle_ring
is_on_fire:heat_distort
```

All three receive the same 0/1 value from the `is_on_fire` trigger. This works for **every trigger** in XtraNimations — built-in and custom alike.

> **Note:** The label after `:` must contain at least one letter. Pure-number labels like `:1` or `:2` are ignored to avoid conflicts with CPM's internal slot system. Use descriptive labels: `:layer1`, `:glow`, `:anim_a`.

This freenamespace system also applies to custom effect triggers, custom biome triggers, and NBT item triggers.

---

## ⚙️ Configuration

All config files live in `.minecraft/config/xtranims/`. The folder structure is created automatically on first launch:

```
config/xtranims/
├── General_Config.txt          ← global settings (debug mode)
├── XTRANIMS_REFERENCE.txt      ← full NBT trigger reference (auto-generated)
├── animations/                 ← custom NBT item triggers (.txt files)
├── effects/                    ← custom modded effect triggers (.txt files)
│   └── EFFECTS_REFERENCE.txt   ← full effects reference (auto-generated)
└── biomes/                     ← custom modded biome triggers (.txt files)
    └── BIOMES_REFERENCE.txt    ← full biomes reference (auto-generated)
```

The reference files in each folder contain complete documentation and examples — you never need to consult external docs to figure out the format.

**General_Config.txt options:**

```
debug = false    # Set to true for verbose logging when troubleshooting
```

Custom trigger files are **reloaded automatically every time you load or reload a CPM model** — no restart required.

---

## How to Use in the CPM Editor

1. Install both **CPM** and XtraNimations.
2. Open the CPM editor in-game (`Ctrl+M` → Edit Model).
3. Create or select an animation.
4. In the animation **Condition** or **Value** field, type:
   ```
   pluginValue("trigger_name_here")
   ```
5. For boolean triggers (0 or 1):
   ```
   pluginValue("is_on_fire") > 0.5
   pluginValue("has_strength") > 0.5
   pluginValue("in_highlands") > 0.5
   ```
6. For ranged triggers:
   ```
   pluginValue("health_pct")          → 0–100
   pluginValue("time_of_day")         → 0–23
   pluginValue("hotbar_slot")         → 0–8
   ```

---

## Installation & Setup (Development)

### Prerequisites
- **Java 17** (required for Minecraft 1.20.1 modding)
- **IntelliJ IDEA** (recommended) or Eclipse
- **Git**

### Step 1: Install Java 17
Download and install [Java 17 (Temurin)](https://adoptium.net/en-GB/temurin/releases/?version=17).

### Step 2: Clone this repository
```bash
git clone https://github.com/YourUsername/xtranims.git
cd xtranims
```

### Step 3: Update CPM version numbers
Check the [CPM API documentation](https://github.com/tom5454/CustomPlayerModels/wiki/API-documentation) for the latest version numbers, then update `gradle.properties`:
```properties
cpm_api_version=<latest API version>
cpm_runtime_version=<latest 1.20 runtime version>
```

### Step 4: Set up the dev environment
```bash
./gradlew genIntellijRuns   # for IntelliJ IDEA
# or
./gradlew genEclipseRuns    # for Eclipse
```

### Step 5: Open in your IDE
Open the project folder in IntelliJ IDEA. It will automatically detect the Gradle project.

### Step 6: Run the client
In IntelliJ, select the `runClient` run configuration and hit Run. Minecraft will launch with your mod and CPM loaded.

### Step 7: Build the jar
```bash
./gradlew build
```
The output jar will be in `build/libs/`.

---

## Requirements
- Minecraft **1.20.1**
- Minecraft Forge **47.x**
- [Customizable Player Models](https://www.curseforge.com/minecraft/mc-mods/custom-player-models) **0.7.x**

## License
MIT License — free to use, modify, and distribute.
