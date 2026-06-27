package com.minecomedy.xtranims;

import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.animation.AnimationRegistry;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.UUID;

/**
 * XtraNimations — TriggerEventHandler (common, loader-agnostic)
 *
 * All Forge event annotations removed. This class now exposes plain static
 * methods that each platform's bridge class calls:
 *
 *   tick()                     ← Forge: TickEvent.ClientTickEvent (phase END)
 *                                 Fabric: ClientTickEvents.END_CLIENT_TICK
 *
 *   onJoinServer()             ← Forge: ClientPlayerNetworkEvent.LoggingIn
 *                                 Fabric: ClientPlayConnectionEvents.JOIN
 *
 *   onLeaveServer()            ← Forge: ClientPlayerNetworkEvent.LoggingOut
 *                                 Fabric: ClientPlayConnectionEvents.DISCONNECT
 *
 *   onClientEntityUnload(UUID) ← Forge: EntityLeaveLevelEvent (player, client-side)
 *                                 Fabric: ClientEntityEvents.ENTITY_UNLOAD (player)
 *
 * Performance design: unchanged from the original — state diffing, item
 * identity checks, biome/world throttling, etc.
 */
public class TriggerEventHandler {

    // ── Value cache: last sent value — skips CPM API when unchanged ───────────
    private static final Map<String, Integer> lastValues = new HashMap<>(256);

    // ── Colon-animation reverse index: base → [full names] ───────────────────
    private static Map<String, List<String>> colonIndex = Collections.emptyMap();

    // ── Pre-built moon phase keys ─────────────────────────────────────────────
    private static final String[] MOON_KEYS = new String[8];
    static { for (int i = 0; i < 8; i++) MOON_KEYS[i] = "moon_phase_" + i; }

    // ── Model change tracking ─────────────────────────────────────────────────
    private static String        lastProfileId = null;
    private static ModelDefinition lastModelRef  = null;
    /** True after a relog/join — triggers one patchAndResend once the model loads. */
    private static volatile boolean pendingPropagateOnJoin = false;
    private static int modelCheckCounter = 0;
    private static final int MODEL_CHECK_INTERVAL = 10;

    // ── Item state ────────────────────────────────────────────────────────────
    private static int        lastHotbarSlot  = -1;
    private static ItemStack  lastMainItem    = ItemStack.EMPTY;
    private static ItemStack  lastOffItem     = ItemStack.EMPTY;
    private static boolean    itemStateDirty  = true;

    // ── Potion state ──────────────────────────────────────────────────────────
    private static final Set<MobEffect> lastActiveEffects = new HashSet<>();
    private static boolean potionsDirty = true;

    // ── Player stat state ─────────────────────────────────────────────────────
    private static int lastHealthPct = -1, lastFoodPct = -1, lastAirPct = -1;
    private static int lastXpLevel = -1, lastArmorVal = -1;

    // ── Player boolean flags state ────────────────────────────────────────────
    private static int lastPlayerFlags = -1;
    private static final int F_SPRINTING  = 1, F_SNEAKING   = 2,  F_SWIMMING  = 4;
    private static final int F_UNDERWATER = 8, F_ELYTRA     = 16, F_IN_WATER  = 32;
    private static final int F_IN_LAVA    = 64, F_ON_FIRE   = 128, F_INVISIBLE = 256;

    // ── Biome/world state ─────────────────────────────────────────────────────
    private static int lastBiomeX = Integer.MIN_VALUE, lastBiomeZ = Integer.MIN_VALUE;
    private static int biomeSlowCounter = 0;
    private static final int BIOME_FORCE_INTERVAL = 80;

    // ── Moon/night state ──────────────────────────────────────────────────────
    private static boolean lastWasNight = false;
    private static int     lastMoonPhase = -1;
    private static int     lastTimeOfDay = -1;
    private static int     moonSlowCounter = 0;
    private static final int TIME_FORCE_INTERVAL = 100;

    // ── Rain/thunder state ────────────────────────────────────────────────────
    private static int lastRaining = -1, lastThundering = -1;

    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    public static void tick() {
        if (CPMPlugin.clientApi == null) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        RgbReflectionHelper.tickPeerRetry();

        if (++modelCheckCounter >= MODEL_CHECK_INTERVAL) {
            modelCheckCounter = 0;
            checkForModelChange();
        }

        Level level = mc.level;

        boolean screenOpen = mc.screen != null;

        // ── PLAYER STATS ──────────────────────────────────────────────────────
        float maxHealth = player.getMaxHealth();
        int healthPct = maxHealth > 0 ? (int)(player.getHealth() / maxHealth * 100f) : 0;
        if (healthPct != lastHealthPct) { lastHealthPct = healthPct; send("health_pct", healthPct); }

        FoodData food = player.getFoodData();
        int foodPct = food.getFoodLevel() * 5;
        if (foodPct != lastFoodPct) { lastFoodPct = foodPct; send("food_pct", foodPct); }

        int maxAir = player.getMaxAirSupply();
        int airPct = maxAir > 0 ? (int)((float)Math.max(0, player.getAirSupply()) / maxAir * 100f) : 100;
        if (airPct != lastAirPct) { lastAirPct = airPct; send("air_pct", airPct); }

        int xpLevel = Math.min(player.experienceLevel, 100);
        if (xpLevel != lastXpLevel) { lastXpLevel = xpLevel; send("xp_level", xpLevel); }

        int armorVal = player.getArmorValue();
        if (armorVal != lastArmorVal) { lastArmorVal = armorVal; send("armor_value", armorVal); }

        // ── POTION EFFECTS ────────────────────────────────────────────────────
        Set<MobEffect> currentEffects = new java.util.HashSet<>();
        for (var instance : player.getActiveEffects()) currentEffects.add(instance.getEffect());
        if (!currentEffects.equals(lastActiveEffects)) {
            lastActiveEffects.clear();
            lastActiveEffects.addAll(currentEffects);
            potionsDirty = true;
        }
        if (potionsDirty) {
            potionsDirty = false;
            send("has_nausea",          hasEffect(player, MobEffects.CONFUSION));
            send("has_night_vision",    hasEffect(player, MobEffects.NIGHT_VISION));
            send("has_blindness",       hasEffect(player, MobEffects.BLINDNESS));
            send("has_speed",           hasEffect(player, MobEffects.MOVEMENT_SPEED));
            send("has_slowness",        hasEffect(player, MobEffects.MOVEMENT_SLOWDOWN));
            send("has_strength",        hasEffect(player, MobEffects.DAMAGE_BOOST));
            send("has_weakness",        hasEffect(player, MobEffects.WEAKNESS));
            send("has_regeneration",    hasEffect(player, MobEffects.REGENERATION));
            send("has_poison",          hasEffect(player, MobEffects.POISON));
            send("has_wither",          hasEffect(player, MobEffects.WITHER));
            send("has_fire_resistance", hasEffect(player, MobEffects.FIRE_RESISTANCE));
            send("has_water_breathing", hasEffect(player, MobEffects.WATER_BREATHING));
            send("has_invisibility",    hasEffect(player, MobEffects.INVISIBILITY));
            send("has_levitation",      hasEffect(player, MobEffects.LEVITATION));
            send("has_slow_falling",    hasEffect(player, MobEffects.SLOW_FALLING));
            send("has_haste",           hasEffect(player, MobEffects.DIG_SPEED));
            send("has_mining_fatigue",  hasEffect(player, MobEffects.DIG_SLOWDOWN));
            send("has_absorption",      hasEffect(player, MobEffects.ABSORPTION));
            send("has_glowing",         hasEffect(player, MobEffects.GLOWING));
            send("has_hunger",          hasEffect(player, MobEffects.HUNGER));
            send("has_saturation",      hasEffect(player, MobEffects.SATURATION));
            send("has_luck",            hasEffect(player, MobEffects.LUCK));
            send("has_bad_luck",        hasEffect(player, MobEffects.UNLUCK));
            send("has_dolphins_grace",  hasEffect(player, MobEffects.DOLPHINS_GRACE));
            send("has_conduit_power",   hasEffect(player, MobEffects.CONDUIT_POWER));
            send("has_hero_of_village", hasEffect(player, MobEffects.HERO_OF_THE_VILLAGE));
            send("has_bad_omen",        hasEffect(player, MobEffects.BAD_OMEN));

            for (EffectTriggerLoader.EffectAnimation ea : EffectTriggerLoader.getAnimations()) {
                send(ea.animationName, player.hasEffect(ea.effect) ? 1 : 0);
            }
        }

        // ── ON FIRE ───────────────────────────────────────────────────────────
        send("is_on_fire", player.isOnFire() ? 1 : 0);

        // ── WEATHER ───────────────────────────────────────────────────────────
        int raining    = level.isRaining()    ? 1 : 0;
        int thundering = level.isThundering() ? 1 : 0;
        if (raining    != lastRaining)    { lastRaining    = raining;    send("is_raining",    raining); }
        if (thundering != lastThundering) { lastThundering = thundering; send("is_thundering", thundering); }

        if (!screenOpen) {
            // ── PLAYER FLAGS ──────────────────────────────────────────────────
            int flags = 0;
            if (player.isSprinting())  flags |= F_SPRINTING;
            if (player.isCrouching())  flags |= F_SNEAKING;
            if (player.isSwimming())   flags |= F_SWIMMING;
            if (player.isUnderWater()) flags |= F_UNDERWATER;
            if (player.isFallFlying()) flags |= F_ELYTRA;
            if (player.isInWater())    flags |= F_IN_WATER;
            if (player.isInLava())     flags |= F_IN_LAVA;
            if (player.isOnFire())     flags |= F_ON_FIRE;
            if (player.isInvisible())  flags |= F_INVISIBLE;
            if (flags != lastPlayerFlags) {
                lastPlayerFlags = flags;
                send("is_sprinting",  (flags & F_SPRINTING)  != 0 ? 1 : 0);
                send("is_sneaking",   (flags & F_SNEAKING)   != 0 ? 1 : 0);
                send("is_swimming",   (flags & F_SWIMMING)   != 0 ? 1 : 0);
                send("is_underwater", (flags & F_UNDERWATER) != 0 ? 1 : 0);
                send("is_elytra_fly", (flags & F_ELYTRA)     != 0 ? 1 : 0);
                send("is_in_water",   (flags & F_IN_WATER)   != 0 ? 1 : 0);
                send("is_in_lava",    (flags & F_IN_LAVA)    != 0 ? 1 : 0);
                send("is_on_fire",    (flags & F_ON_FIRE)    != 0 ? 1 : 0);
                send("is_invisible",  (flags & F_INVISIBLE)  != 0 ? 1 : 0);
            }

            // ── HOTBAR SLOT ───────────────────────────────────────────────────
            int hotbarSlot = player.getInventory().selected;
            if (hotbarSlot != lastHotbarSlot) {
                lastHotbarSlot = hotbarSlot;
                send("hotbar_slot", hotbarSlot);
                itemStateDirty = true;
            }

            // ── ITEM STATE ────────────────────────────────────────────────────
            ItemStack main = player.getMainHandItem();
            ItemStack off  = player.getOffhandItem();

            boolean mainChanged = !ItemStack.isSameItemSameTags(main, lastMainItem)
                               || main.getCount() != lastMainItem.getCount();
            boolean offChanged  = !ItemStack.isSameItemSameTags(off,  lastOffItem)
                               || off.getCount()  != lastOffItem.getCount();

            if (mainChanged || offChanged || itemStateDirty) {
                itemStateDirty = false;
                lastMainItem = main.copy();
                lastOffItem  = off.copy();

                String mainId = main.getItem().toString();
                send("holding_sword",    mainId.contains("sword")    ? 1 : 0);
                send("holding_bow",      main.is(Items.BOW)          ? 1 : 0);
                send("holding_crossbow", main.is(Items.CROSSBOW)     ? 1 : 0);
                send("holding_trident",  main.is(Items.TRIDENT)      ? 1 : 0);
                send("holding_shield",   (main.is(Items.SHIELD) || off.is(Items.SHIELD)) ? 1 : 0);
                send("holding_axe",      mainId.contains("_axe")     ? 1 : 0);
                send("holding_pickaxe",  mainId.contains("pickaxe")  ? 1 : 0);
                send("holding_shovel",   mainId.contains("shovel")   ? 1 : 0);
                send("holding_hoe",      mainId.contains("_hoe")     ? 1 : 0);
                send("holding_staff",    main.is(Items.STICK)        ? 1 : 0);

                boolean isBook = main.is(Items.BOOK) || main.is(Items.WRITABLE_BOOK)
                              || main.is(Items.WRITTEN_BOOK) || main.is(Items.ENCHANTED_BOOK);
                send("holding_book", isBook ? 1 : 0);
                boolean isTool = mainId.contains("pickaxe") || mainId.contains("_axe")
                              || mainId.contains("shovel")  || mainId.contains("_hoe");
                send("holding_tool",    isTool ? 1 : 0);
                boolean isFood = !main.isEmpty() && main.getItem().getFoodProperties() != null;
                send("holding_food",    isFood         ? 1 : 0);
                send("main_hand_empty", main.isEmpty() ? 1 : 0);
                send("off_hand_empty",  off.isEmpty()  ? 1 : 0);

                for (NbtTriggerLoader.ItemAnimation anim : NbtTriggerLoader.getAnimations()) {
                    ItemStack target = switch (anim.hand) {
                        case MAIN -> main;
                        case OFF  -> off;
                        case BOTH -> anim.itemMatches(main) ? main
                                   : anim.itemMatches(off)  ? off
                                   : ItemStack.EMPTY;
                    };
                    boolean active = anim.itemMatches(target) && allConditionsPass(anim.conditions, target);
                    send(anim.animationName, active ? 1 : 0);
                }
            }

            // ── BIOME & WORLD ─────────────────────────────────────────────────
            int bx = (int)Math.floor(player.getX());
            int bz = (int)Math.floor(player.getZ());
            biomeSlowCounter++;
            boolean biomeChanged = (bx != lastBiomeX || bz != lastBiomeZ);
            if (biomeChanged || biomeSlowCounter >= BIOME_FORCE_INTERVAL) {
                biomeSlowCounter = 0;
                lastBiomeX = bx; lastBiomeZ = bz;

                BlockPos pos = player.blockPosition();
                var biomeHolder = level.getBiome(pos);
                float temp = biomeHolder.value().getBaseTemperature();
                String biomeName = biomeHolder.unwrapKey()
                        .map(k -> k.location().getPath()).orElse("");

                send("biome_temperature",    Math.max(0, Math.min(100, (int)((temp + 0.5f) / 2.5f * 100f))));
                send("biome_is_snowy",       temp < 0.15f                  ? 1 : 0);
                send("biome_is_dry",         temp > 0.9f                   ? 1 : 0);
                send("biome_is_ocean",       biomeName.contains("ocean")   ? 1 : 0);
                send("biome_is_desert",      biomeName.contains("desert")  ? 1 : 0);
                send("biome_is_forest",      biomeName.contains("forest")  ? 1 : 0);
                send("biome_is_swamp",       biomeName.contains("swamp")   ? 1 : 0);
                send("biome_is_jungle",      biomeName.contains("jungle")  ? 1 : 0);
                send("biome_is_savanna",     biomeName.contains("savanna") ? 1 : 0);
                send("biome_is_nether",      level.dimension() == Level.NETHER ? 1 : 0);
                send("biome_is_the_end",     level.dimension() == Level.END    ? 1 : 0);
                send("biome_is_underground", pos.getY() < 0 ? 1 : 0);
                send("y_level", Math.max(0, Math.min(100, (int)((pos.getY() + 64f) / 384f * 100f))));

                String fullBiomeId = biomeHolder.unwrapKey()
                        .map(k -> k.location().toString()).orElse("");
                for (BiomeTriggerLoader.BiomeAnimation ba : BiomeTriggerLoader.getAnimations()) {
                    send(ba.animationName, ba.matches(fullBiomeId) ? 1 : 0);
                }
            }
        } // end !screenOpen

        // ── MOON / NIGHT ──────────────────────────────────────────────────────
        moonSlowCounter++;
        long rawTime = level.getDayTime() % 24000;
        int timeOfDay = (int)(rawTime / 1000L);
        boolean isNight = rawTime >= 13000;
        if (timeOfDay != lastTimeOfDay || moonSlowCounter >= TIME_FORCE_INTERVAL) {
            moonSlowCounter = 0;
            lastTimeOfDay = timeOfDay;
            send("time_of_day", timeOfDay);
        }
        if (isNight != lastWasNight || (isNight && level.getMoonPhase() != lastMoonPhase)) {
            lastWasNight  = isNight;
            lastMoonPhase = level.getMoonPhase();
            for (int i = 0; i < 8; i++) {
                send(MOON_KEYS[i], (isNight && lastMoonPhase == i) ? 1 : 0);
            }
        }

        // ── RGB KEYBIND ───────────────────────────────────────────────────────
        if (RgbKeybind.KEY_OPEN_RGB.consumeClick() && mc.screen == null) {
            mc.setScreen(new RgbColorScreen());
        }
    }

    private static int hasEffect(LocalPlayer player, MobEffect effect) {
        return player.hasEffect(effect) ? 1 : 0;
    }

    // ─── SERVER JOIN ──────────────────────────────────────────────────────────

    public static void onJoinServer() {
        // Send hello via whichever platform's networking is active
        if (RgbState.sendHelloHook != null) RgbState.sendHelloHook.run();
        pendingPropagateOnJoin = true;
    }

    // ─── SERVER LEAVE ─────────────────────────────────────────────────────────

    public static void onLeaveServer() {
        RgbReflectionHelper.reset();
        pendingPropagateOnJoin = false;
    }

    // ─── ENTITY UNLOAD ───────────────────────────────────────────────────────

    /** Called by platform bridge when a player entity is unloaded on the client side. */
    public static void onClientEntityUnload(UUID entityUuid) {
        RgbReflectionHelper.clearPeerPlayer(entityUuid);
    }

    // ─── SEND ─────────────────────────────────────────────────────────────────

    private static void send(String trigger, int value) {
        sendOne(trigger, value);
        List<String> targets = colonIndex.get(trigger);
        if (targets != null) for (String name : targets) sendOne(name, value);
    }

    private static void sendOne(String name, int value) {
        int clamped = value == 0 ? 0 : 1;
        Integer prev = lastValues.get(name);
        if (prev != null && prev == clamped) return;
        lastValues.put(name, clamped);
        try { CPMPlugin.clientApi.playAnimation(name, clamped); }
        catch (Exception ignored) {}
    }

    // ─── NBT EVALUATION ───────────────────────────────────────────────────────

    private static boolean allConditionsPass(
            List<NbtTriggerLoader.NbtCondition> conditions, ItemStack stack) {
        if (stack.isEmpty()) return false;
        CompoundTag root = stack.getTag();
        if (root == null) return false;
        for (NbtTriggerLoader.NbtCondition cond : conditions) {
            boolean pass;
            if (cond.op == NbtTriggerLoader.Op.HAS_ENCHANT) {
                pass = hasEnchantment(root, cond.compareValue);
            } else {
                Tag resolved = walkNbtPath(root, cond.nbtPath);
                if (resolved == null) return false;
                pass = switch (cond.op) {
                    case EXISTS     -> true;
                    case NOT_EMPTY  -> isNbtNonEmpty(resolved);
                    case EQUALS     -> nbtAsString(resolved).equalsIgnoreCase(cond.compareValue);
                    case NOT_EQUALS -> !nbtAsString(resolved).equalsIgnoreCase(cond.compareValue);
                    case GT         -> nbtAsInt(resolved) >  cond.compareInt;
                    case GTE        -> nbtAsInt(resolved) >= cond.compareInt;
                    case LT         -> nbtAsInt(resolved) <  cond.compareInt;
                    case LTE        -> nbtAsInt(resolved) <= cond.compareInt;
                    default         -> false;
                };
            }
            if (!pass) return false;
        }
        return true;
    }

    private static boolean hasEnchantment(CompoundTag root, String enchantId) {
        if (!enchantId.contains(":")) enchantId = "minecraft:" + enchantId;
        for (String listKey : new String[]{"Enchantments", "StoredEnchantments"}) {
            if (!root.contains(listKey)) continue;
            ListTag list = root.getList(listKey, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++)
                if (list.getCompound(i).getString("id").equalsIgnoreCase(enchantId)) return true;
        }
        return false;
    }

    private static Tag walkNbtPath(CompoundTag root, String[] path) {
        Tag current = root;
        for (String key : path) {
            if (!(current instanceof CompoundTag c)) return null;
            if (!c.contains(key)) return null;
            current = c.get(key);
        }
        return current;
    }

    private static boolean isNbtNonEmpty(Tag tag) {
        return switch (tag.getId()) {
            case Tag.TAG_LIST     -> ((net.minecraft.nbt.ListTag) tag).size() > 0;
            case Tag.TAG_COMPOUND -> !((CompoundTag)              tag).isEmpty();
            case Tag.TAG_STRING   -> !tag.getAsString().isEmpty();
            default               -> nbtAsInt(tag) != 0;
        };
    }

    private static String nbtAsString(Tag tag) {
        String s = tag.getAsString();
        return (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
                ? s.substring(1, s.length() - 1) : s;
    }

    private static int nbtAsInt(Tag tag) {
        return switch (tag.getId()) {
            case Tag.TAG_BYTE   -> ((net.minecraft.nbt.ByteTag)   tag).getAsInt();
            case Tag.TAG_SHORT  -> ((net.minecraft.nbt.ShortTag)  tag).getAsInt();
            case Tag.TAG_INT    -> ((net.minecraft.nbt.IntTag)    tag).getAsInt();
            case Tag.TAG_LONG   -> (int)((net.minecraft.nbt.LongTag)   tag).getAsLong();
            case Tag.TAG_FLOAT  -> (int)((net.minecraft.nbt.FloatTag)  tag).getAsFloat();
            case Tag.TAG_DOUBLE -> (int)((net.minecraft.nbt.DoubleTag) tag).getAsDouble();
            case Tag.TAG_LIST   -> ((net.minecraft.nbt.ListTag)   tag).size();
            default             -> 0;
        };
    }

    // ─── MODEL CHANGE DETECTION ───────────────────────────────────────────────

    private static void checkForModelChange() {
        try {
            Player<?> cpmPlayer = MinecraftClientAccess.get().getCurrentClientPlayer();
            if (cpmPlayer == null) { if (lastProfileId != null) resetAll(); return; }

            ModelDefinition def = cpmPlayer.getModelDefinition();
            if (def == null)       { if (lastProfileId != null) resetAll(); return; }

            AnimationRegistry registry = def.getAnimations();
            if (registry == null) return;

            String profileId = registry.getProfileId();

            if (Objects.equals(profileId, lastProfileId) && def == lastModelRef) return;

            lastProfileId = profileId;
            lastModelRef  = def;
            resetStateFlags();

            Map<String, List<String>> index = new HashMap<>();
            for (String name : registry.getCommandActionsMap().keySet()) {
                if (!name.contains(":") || name.startsWith("rgb:")) continue;
                int lastColon = name.lastIndexOf(':');
                String afterLast = name.substring(lastColon + 1);
                String fullName = (!afterLast.isEmpty() && afterLast.chars().allMatch(Character::isDigit))
                        ? name.substring(0, lastColon) : name;
                String base = fullName.substring(0, fullName.indexOf(':'));
                index.computeIfAbsent(base, k -> new ArrayList<>()).add(fullName);
            }
            index.replaceAll((k, v) -> Collections.unmodifiableList(v));
            colonIndex = Collections.unmodifiableMap(index);

            String activeModel = null;
            try {
                activeModel = com.tom.cpm.shared.config.ModConfig.getCommonConfig()
                        .getString(com.tom.cpm.shared.config.ConfigKeys.SELECTED_MODEL, null);
                if (com.tom.cpm.shared.editor.TestIngameManager.TEST_MODEL_NAME.equals(activeModel)) {
                    String old2 = com.tom.cpm.shared.config.ModConfig.getCommonConfig()
                            .getString(com.tom.cpm.shared.config.ConfigKeys.SELECTED_MODEL_OLD, null);
                    if (old2 != null && !old2.equals("~~VANILLA~~")) activeModel = old2;
                }
            } catch (Exception ignored) {}
            RgbReflectionHelper.scanModel(def, registry, activeModel);

            NbtTriggerLoader.load();
            EffectTriggerLoader.load();
            BiomeTriggerLoader.load();

            if (pendingPropagateOnJoin) {
                pendingPropagateOnJoin = false;
                RgbReflectionHelper.applyColors();
            }

            XtraNimations.LOGGER.info("[XtraNimations] Model loaded (profile: {}). Colon anims: {}",
                    profileId, colonIndex);

        } catch (Exception ignored) {}
    }

    private static void resetAll() {
        colonIndex    = Collections.emptyMap();
        lastProfileId = null;
        lastModelRef  = null;
        lastValues.clear();
        resetStateFlags();
        RgbReflectionHelper.reset();
    }

    private static void resetStateFlags() {
        modelCheckCounter = MODEL_CHECK_INTERVAL;
        lastValues.clear();
        lastPlayerFlags  = -1;
        lastHealthPct    = lastFoodPct = lastAirPct = lastXpLevel = lastArmorVal = -1;
        lastHotbarSlot   = -1;
        lastMainItem     = ItemStack.EMPTY;
        lastOffItem      = ItemStack.EMPTY;
        itemStateDirty   = true;
        lastActiveEffects.clear();
        potionsDirty     = true;
        lastBiomeX       = lastBiomeZ = Integer.MIN_VALUE;
        biomeSlowCounter = BIOME_FORCE_INTERVAL;
        lastWasNight     = false;
        lastMoonPhase    = -1;
        lastTimeOfDay    = -1;
        moonSlowCounter  = TIME_FORCE_INTERVAL;
        lastRaining      = lastThundering = -1;
    }
}
