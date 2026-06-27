package com.minecomedy.xtranims;

import com.tom.cpm.shared.animation.AnimationRegistry;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.io.ModelFile;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Scans a CPM model for RGB color groups (Color Filter render effects),
 * applies chosen colors locally (into Cube.rgb so reset() preserves them),
 * and propagates them to other players via the appropriate path.
 *
 * <h3>Two model formats, two scan targets</h3>
 *
 * <b>Normal model</b> (large dataBlock > 2048 bytes):
 * After resolveAll(), ModelDefinition.parts contains ModelPartRenderEffect objects
 * directly at the top level. We scan parts.
 *
 * <b>Gist/paste-site model</b> (stub dataBlock <= 2048 bytes, link to remote data):
 * ModelDefinition.parts only contains a ModelPartDefinitionLink stub. The real geometry
 * is loaded asynchronously and placed in ModelDefinition.resolved as a ModelPartDefinition.
 * ModelPartDefinition.resolvedOtherParts holds the EffectColor entries.
 * We must scan BOTH parts (for normal format) AND resolved (for gist format).
 *
 * <h3>Two propagation paths</h3>
 *
 * <b>Normal model</b>: patchAndResend patches the binary dataBlock and re-sends the skin.
 * No mod needed on the viewer's side.
 *
 * <b>Gist model</b>: sendRgbUpdate tells the server our colors, relayed via RGB_PEER.
 * Both the server and the viewer must have the mod.
 *
 * <h3>Reset safety</h3>
 * cubeIdToOriginalGroup/TagColor are NOT cleared by scanModel — only by reset().
 * They survive patch-triggered reloads so the second scan can recover group keys.
 * isPatchingInProgress guards against the infinite patch→reload→scan→patch loop.
 */
@SuppressWarnings({"deprecation", "removal"})
public class RgbReflectionHelper {

    // ── Reflection fields ─────────────────────────────────────────────────────

    private static Field f_parts              = null;
    private static Field f_resolved           = null; // ModelDefinition.resolved
    private static Field f_otherParts         = null; // ModelPartDefinition.otherParts
    private static Field f_resolvedOtherParts = null; // ModelPartDefinition.resolvedOtherParts
    private static Field f_effect             = null;
    private static Field f_effectId           = null;
    private static Field f_effectColor        = null;
    private static Field f_cubeRgb            = null;
    private static Field f_recolor            = null; // RenderedCube.recolor
    private static Field f_renderedColor      = null; // RenderedCube.color (the actual render color)
    private static Field f_psfs               = null; // Animation.psfs     (Interpolator[][])
    private static Field f_iValues            = null; // Interpolator impl  .values (float[])
    private static Field f_trigAnimations     = null; // AnimationTrigger.animations (List<IAnimation>)
    private static Class<?> animClass         = null; // com.tom.cpm.shared.animation.Animation
    private static MethodHandle mh_getCube    = null;
    private static Field f_cubeId             = null;
    private static boolean reflectionReady    = false;

    // ── Local-player state ────────────────────────────────────────────────────

    private static final Map<String, List<Object>> groupToCubes = new LinkedHashMap<>();
    /** groupKey → ALL cubeIds in the group (for gist model cubeId anchoring). */
    private static final Map<String, List<Integer>> groupToCubeId = new LinkedHashMap<>();

    /** Returns current groupKey → cubeId list map (snapshot) for saving. */
    public static Map<String, List<Integer>> getGroupToCubeId() {
        return Collections.unmodifiableMap(groupToCubeId);
    }
    private static final Map<Integer, Integer> cubeIdToColor    = new HashMap<>();
    private static final Map<Integer, Integer> cubeIdToTagColor = new LinkedHashMap<>();

    /** PERSISTENT across patch-triggered reloads — only cleared by reset(). */
    private static final Map<Integer, String>  cubeIdToOriginalGroup    = new HashMap<>();
    private static final Map<Integer, Integer> cubeIdToOriginalTagColor = new HashMap<>();

    private static volatile boolean isPatchingInProgress = false;
    private static ModelDefinition lastDef = null;

    // Peer colors waiting for gist model to finish resolving: UUID -> groupColors
    private static final Map<java.util.UUID, Map<String, Integer>> pendingPeerColors =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Magic marker: last byte of gesture payload when we've appended RGB data
    // Byte length of our appended color data: R, G, B, MAGIC = 4 bytes

    // ── Reflection init ───────────────────────────────────────────────────────

    private static boolean initReflection() {
        if (reflectionReady) return true;
        try {
            Class<?> defCls      = Class.forName("com.tom.cpm.shared.definition.ModelDefinition");
            Class<?> partDefCls  = Class.forName("com.tom.cpm.shared.parts.ModelPartDefinition");
            Class<?> partEffCls  = Class.forName("com.tom.cpm.shared.parts.ModelPartRenderEffect");
            Class<?> effColorCls = Class.forName("com.tom.cpm.shared.effects.EffectColor");
            Class<?> rawCubeCls  = Class.forName("com.tom.cpm.shared.model.Cube");
            Class<?> rendCubeCls = Class.forName("com.tom.cpm.shared.model.RenderedCube");

            f_parts              = defCls.getDeclaredField("parts");
            f_parts.setAccessible(true);

            f_resolved           = defCls.getDeclaredField("resolved");
            f_resolved.setAccessible(true);

            f_otherParts         = partDefCls.getDeclaredField("otherParts");
            f_otherParts.setAccessible(true);

            f_resolvedOtherParts = partDefCls.getDeclaredField("resolvedOtherParts");
            f_resolvedOtherParts.setAccessible(true);

            f_effect      = partEffCls.getDeclaredField("effect");     f_effect.setAccessible(true);
            f_effectId    = effColorCls.getDeclaredField("id");        f_effectId.setAccessible(true);
            f_effectColor = effColorCls.getDeclaredField("color");     f_effectColor.setAccessible(true);
            f_cubeRgb     = rawCubeCls.getDeclaredField("rgb");        f_cubeRgb.setAccessible(true);
            f_cubeId      = rawCubeCls.getDeclaredField("id");         f_cubeId.setAccessible(true);
            f_recolor        = rendCubeCls.getDeclaredField("recolor"); f_recolor.setAccessible(true);
            f_renderedColor  = rendCubeCls.getDeclaredField("color");   f_renderedColor.setAccessible(true);

            animClass        = Class.forName("com.tom.cpm.shared.animation.Animation");
            f_psfs           = animClass.getDeclaredField("psfs");       f_psfs.setAccessible(true);
            // Interpolator is an interface; values field is in concrete impls (LinearInterpolator etc)
            // We'll find it reflectively per-instance in patchAnimationInterpolators()

            f_trigAnimations = Class.forName("com.tom.cpm.shared.animation.AnimationTrigger")
                                   .getDeclaredField("animations");
            f_trigAnimations.setAccessible(true);
            mh_getCube    = MethodHandles.publicLookup().findVirtual(
                    rendCubeCls, "getCube", MethodType.methodType(rawCubeCls));

            reflectionReady = true;
            return true;
        } catch (Exception e) {
            XtraNimations.LOGGER.error("[XtraNimations] RGB reflection init failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Model type detection ──────────────────────────────────────────────────

    /**
     * Returns true if the currently selected model is a gist/paste-site model.
     * Detected via ModelFile.convertable(): true when dataBlock.length <= 2048
     * (the stub-only case for gist models).
     */
    private static boolean isGistModel() {
        try {
            String modelName = ModConfig.getCommonConfig()
                    .getString(ConfigKeys.SELECTED_MODEL, null);
            if (modelName == null || modelName.equals("~~VANILLA~~")) return false;

            if (com.tom.cpm.shared.editor.TestIngameManager.TEST_MODEL_NAME.equals(modelName)) {
                String old = ModConfig.getCommonConfig()
                        .getString(ConfigKeys.SELECTED_MODEL_OLD, null);
                if (old != null && !old.equals("~~VANILLA~~")) {
                    modelName = old;
                } else {
                    return false;
                }
            }

            File modelsDir = new File(
                    com.tom.cpm.shared.MinecraftClientAccess.get().getGameDir(), "player_models");
            File modelFile = new File(modelsDir, modelName);
            if (!modelFile.exists()) return false;

            return ModelFile.load(modelFile).convertable();

        } catch (Exception e) {
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[XtraNimations] RGB isGistModel check failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Local scan ────────────────────────────────────────────────────────────

    /**
     * Scans the loaded CPM model for EffectColor groups.
     *
     * Scans TWO sources to handle both model formats:
     * 1. def.parts — contains ModelPartRenderEffect directly for normal models.
     * 2. def.resolved — contains the resolved ModelPartDefinition for gist models,
     *    whose resolvedOtherParts hold the EffectColor entries.
     */
    @SuppressWarnings("unchecked")
    public static void scanModel(ModelDefinition def, AnimationRegistry registry, String activeModelName) {
        groupToCubes.clear();
        groupToCubeId.clear();
        cubeIdToColor.clear();
        cubeIdToTagColor.clear();
        // NOTE: cubeIdToOriginalGroup/TagColor NOT cleared — must survive patch reloads.

        if (!initReflection() || def == null) return;
        lastDef = def;

        // Switch color store to this model's file
        RgbColorStore.switchModel(activeModelName);

        // --- Source 1: def.parts (normal model format) ---
        List<Object> parts = null;
        try {
            parts = (List<Object>) f_parts.get(def);
        } catch (Throwable e) {
            XtraNimations.LOGGER.error("[XtraNimations] RGB: can't read parts: {}", e.getMessage());
        }

        if (parts != null) {
            for (Object part : parts) {
                String cls = part.getClass().getName();
                if (cls.equals("com.tom.cpm.shared.parts.ModelPartRenderEffect")) {
                    checkRenderEffect(part, def);
                } else if (cls.equals("com.tom.cpm.shared.parts.ModelPartDefinition")) {
                    // Deprecated format: effects in otherParts
                    scanPartDefinitionOtherParts(part, def);
                }
                // ModelPartDefinitionLink / PackageLink are resolved asynchronously —
                // their content appears in def.resolved, handled below.
            }
        }

        // --- Source 2: def.resolved (gist model format) ---
        // After resolveAll(), ModelPartDefinitionLink resolves into a ModelPartDefinition
        // whose resolvedOtherParts contain the EffectColor entries.
        // This is the ONLY place gist EffectColor entries appear after async resolution.
        List<Object> resolved = null;
        try {
            resolved = (List<Object>) f_resolved.get(def);
        } catch (Throwable ignored) {}

        if (resolved != null) {
            for (Object rpart : resolved) {
                String cls = rpart.getClass().getName();
                if (cls.equals("com.tom.cpm.shared.parts.ModelPartDefinition")) {
                    // Resolved form of ModelPartDefinitionLink (gist) or plain DEFINITION part
                    scanPartDefinitionResolvedOtherParts(rpart, def);
                }
                // ModelPartCollection.Pack would be here for PACKAGE_LINK — scan recursively
                // by checking for IResolvedModelPart subtypes that contain more parts
            }
        }

        RgbColorStore.ensureLabels(groupToCubes.keySet());

        if (!groupToCubes.isEmpty()) {
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB scan done. Groups: {}", groupToCubes.keySet());

            // Inject a live color animation for each group into the registry.
            // This replaces patchAnimationInterpolators — instead of mutating existing
            // animation keyframes, we add a new high-priority GLOBAL animation that
            // drives setColor() every frame with our chosen color.
            injectedAnims.clear();
            for (Map.Entry<String, List<Object>> e : groupToCubes.entrySet()) {
                int[] rgb = RgbColorStore.get(e.getKey());
                int packed = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                InjectedColorAnim ica = injectColorAnimation(def, e.getValue(), packed);
                if (ica != null) injectedAnims.put(e.getKey(), ica);
            }

            // Apply colors locally only — do NOT propagate/patchAndResend here.
            // scanModel is called after every patchAndResend reload; re-patching
            // from here would create an infinite patch cascade.
            applyColorsLocal();
        }
    }

    /**
     * Scans a ModelPartDefinition's otherParts (unresolved) for EffectColor effects.
     * Used for the deprecated DEFINITION format in normal models.
     */
    @SuppressWarnings("unchecked")
    private static void scanPartDefinitionOtherParts(Object partDef, ModelDefinition def) {
        List<Object> otherParts;
        try {
            otherParts = (List<Object>) f_otherParts.get(partDef);
        } catch (Throwable e) {
            return;
        }
        if (otherParts == null) return;
        for (Object other : otherParts) {
            if (other.getClass().getName()
                    .equals("com.tom.cpm.shared.parts.ModelPartRenderEffect")) {
                checkRenderEffect(other, def);
            }
        }
    }

    /**
     * Scans a ModelPartDefinition's resolvedOtherParts for EffectColor effects.
     * Used for gist models: after async resolve, the link part becomes a
     * ModelPartDefinition whose EffectColor entries sit in resolvedOtherParts.
     */
    @SuppressWarnings("unchecked")
    private static void scanPartDefinitionResolvedOtherParts(Object partDef, ModelDefinition def) {
        List<Object> resolvedOtherParts;
        try {
            resolvedOtherParts = (List<Object>) f_resolvedOtherParts.get(partDef);
        } catch (Throwable e) {
            return;
        }
        if (resolvedOtherParts == null) return;
        for (Object rpart : resolvedOtherParts) {
            // After resolve(), ModelPartRenderEffect is itself (it implements both interfaces)
            if (rpart.getClass().getName()
                    .equals("com.tom.cpm.shared.parts.ModelPartRenderEffect")) {
                checkRenderEffect(rpart, def);
            }
        }
    }

    private static void checkRenderEffect(Object renderEffectPart, ModelDefinition def) {
        Object effect;
        try {
            effect = f_effect.get(renderEffectPart);
        } catch (Throwable e) {
            return;
        }
        if (effect == null) return;
        if (!effect.getClass().getName().equals("com.tom.cpm.shared.effects.EffectColor")) return;

        int cubeId, tagColor;
        try {
            cubeId   = (int) f_effectId.get(effect);
            tagColor = (int) f_effectColor.get(effect);
        } catch (Throwable e) {
            return;
        }

        int colorVal = tagColor & 0xFFFFFF;

        String groupKey;
        if (cubeIdToOriginalGroup.containsKey(cubeId)) {
            groupKey = cubeIdToOriginalGroup.get(cubeId);
            colorVal  = cubeIdToOriginalTagColor.getOrDefault(cubeId, colorVal);
        } else {
            if (colorVal == 0xFFFFFF || colorVal == 0x000000) return;
            groupKey = String.format("rgb:%06X", colorVal);
            cubeIdToOriginalGroup.put(cubeId, groupKey);
            cubeIdToOriginalTagColor.put(cubeId, colorVal);
        }

        Object cube = def.getElementById(cubeId);
        if (cube == null) {
            XtraNimations.LOGGER.warn("[XtraNimations] RGB: getElementById({}) returned null for group '{}'", cubeId, groupKey);
            return;
        }

        // For gist models: if a saved cubeId→key mapping exists (from a previous Save),
        // use that stable key instead of the current (possibly drifted) hex key.
        String savedKey = RgbColorStore.getSavedKeyForCubeId(cubeId);
        if (savedKey != null && !savedKey.equals(groupKey)) {
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[XtraNimations] RGB gist anchor: cubeId={} hex-key='{}' → saved-key='{}'",
                    cubeId, groupKey, savedKey);
            // Update the original-group cache to the stable key so future scans also use it
            cubeIdToOriginalGroup.put(cubeId, savedKey);
            groupKey = savedKey;
        }

        // Deduplicate: gist models scan the same cube via both def.parts and def.resolved.
        // Only register each cubeId once per group per scan.
        List<Integer> cubeIdList = groupToCubeId.computeIfAbsent(groupKey, k -> new ArrayList<>());
        if (cubeIdList.contains(cubeId)) {
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[XtraNimations] RGB skip duplicate cubeId={} for group '{}'", cubeId, groupKey);
            return;
        }
        cubeIdList.add(cubeId);
        groupToCubes.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(cube);
        cubeIdToTagColor.put(cubeId, colorVal);
        if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[XtraNimations] RGB group '{}' -> cubeId={}", groupKey, cubeId);

        // Initialise cube.rgb to white so no tag-color flash on first frame
        try {
            Object underlying = mh_getCube.invoke(cube);
            int before = f_cubeRgb.getInt(underlying);
            f_cubeRgb.setInt(underlying, 0xFFFFFF);
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB init cubeId={} rgb #{} -> #FFFFFF", cubeId, Integer.toHexString(before).toUpperCase());
        } catch (Throwable ex) {
            XtraNimations.LOGGER.warn("[XtraNimations] RGB: init cube.rgb failed cubeId={}: {}",
                    cubeId, ex.getMessage());
        }
    }

    // ── Apply to LOCAL player ─────────────────────────────────────────────────

    /**
     * Applies saved colors to local cube.rgb only — no patchAndResend.
     * Called from scanModel after a patch-reload so colors display correctly
     * without triggering another patch cycle.
     */
    public static void applyColorsLocal() {
        if (groupToCubes.isEmpty() || f_cubeRgb == null) return;
        for (Map.Entry<String, List<Object>> e : groupToCubes.entrySet()) {
            int[] rgb  = RgbColorStore.get(e.getKey());
            int packed = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            for (Object cube : e.getValue()) {
                try {
                    Object underlying = mh_getCube.invoke(cube);
                    f_cubeRgb.setInt(underlying, packed);
                    int id = f_cubeId.getInt(underlying);
                    cubeIdToColor.put(id, packed);
                    if (f_recolor != null) f_recolor.set(cube, true);
                } catch (Throwable t) { /* ignore */ }
            }
            // Update injected anim too
            InjectedColorAnim ica = injectedAnims.get(e.getKey());
            if (ica != null) ica.setColor(packed);
        }
    }

    /**
     * Same as applyColors() but clears the patchingInProgress guard first.
     * Use for explicit user-triggered saves so the patch always goes through
     * even if a recent reload is still within the guard window.
     */
    public static void applyColorsForced() {
        isPatchingInProgress = false;
        applyColors();
    }

    /**
     * Applies saved colors to local cube.rgb AND propagates to other players.
     * Call from save button. Do NOT call from scanModel (would re-trigger patchAndResend).
     */
    public static void applyColors() {
        if (groupToCubes.isEmpty() || f_cubeRgb == null) return;

        boolean anyColorSaved = false;
        for (Map.Entry<String, List<Object>> e : groupToCubes.entrySet()) {
            if (RgbColorStore.hasSaved(e.getKey())) {
                anyColorSaved = true;
                break;
            }
        }

        // Step 1: Write colors into local Cube.rgb
        for (Map.Entry<String, List<Object>> e : groupToCubes.entrySet()) {
            int[] rgb  = RgbColorStore.get(e.getKey());
            int packed = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB applyColors: group='{}' packed=#{} cubes={}", e.getKey(), Integer.toHexString(packed).toUpperCase(), e.getValue().size());
            for (Object cube : e.getValue()) {
                try {
                    Object underlying = mh_getCube.invoke(cube);
                    int before = f_cubeRgb.getInt(underlying);
                    f_cubeRgb.setInt(underlying, packed);
                    int after = f_cubeRgb.getInt(underlying);
                    int id = f_cubeId.getInt(underlying);
                    cubeIdToColor.put(id, packed);
                    boolean rc = f_recolor != null && (boolean) f_recolor.get(cube);
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB cube id={} rgb #{} -> #{} (readback=#{} recolor={})", id, Integer.toHexString(before).toUpperCase(), Integer.toHexString(packed).toUpperCase(), Integer.toHexString(after).toUpperCase(), rc);
                    // Ensure recolor=true so RenderedCube.reset() uses cube.rgb instead of white
                    if (f_recolor != null) f_recolor.set(cube, true);
                } catch (Throwable t) {
                    XtraNimations.LOGGER.warn("[XtraNimations] RGB write failed: {}", t.getMessage());
                }
            }
        }

        if (!anyColorSaved) return;

        // Step 2: Propagate to other players via the model-type-specific path
        boolean gist = isGistModel();
if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[XtraNimations] RGB propagate: isGistModel={}", gist);

        if (gist) {
            // Update the injected animation's color values in-place.
            // The AnimationEngine reads these every frame automatically.
            Map<String, Integer> groupColors = new LinkedHashMap<>();
            for (Map.Entry<String, List<Object>> e : groupToCubes.entrySet()) {
                int[] rgb = RgbColorStore.get(e.getKey());
                int packed = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                groupColors.put(e.getKey(), packed);
                InjectedColorAnim ica = injectedAnims.get(e.getKey());
                if (ica != null) {
                    ica.setColor(packed);
                } else {
                    ica = injectColorAnimation(lastDef, e.getValue(), packed);
                    if (ica != null) injectedAnims.put(e.getKey(), ica);
                }
            }
            // Patch overflowLocal (the full downloaded model bytes) and resend as SetSkinC2S.
            // RgbSkinPatcher now reads overflowLocal instead of the tiny stub dataBlock,
            // so all viewers including CPM-only ones get the color natively — no XtraNimations needed.
            if (!isPatchingInProgress) {
                isPatchingInProgress = true;
                try {
                    RgbSkinPatcher.patchAndResend(cubeIdToColor, cubeIdToTagColor);
                } finally {
                    scheduleGuardReset();
                }
            }

        } else {
            // Normal: patch binary dataBlock and resend as SetSkinC2S
            if (!isPatchingInProgress) {
                isPatchingInProgress = true;
                try {
                    RgbSkinPatcher.patchAndResend(cubeIdToColor, cubeIdToTagColor);
                } finally {
                    scheduleGuardReset();
                }
            }
        }
    }

    // ── Patch animation interpolators ─────────────────────────────────────────

    /**
     * Patches the baked float[] values inside the Animation's Interpolator objects
     * for COLOR_R/G/B channels so the animation engine itself outputs our chosen color.
     *
     * This is the correct approach because Animation.animate() calls
     * component.setColor(r, g, b) every frame using these interpolator values,
     * overwriting anything we write to RenderedCube.color or cube.rgb.
     * By changing the source keyframe data, the animation produces our color natively.
     */
    // ── Injected color animation ──────────────────────────────────────────────

    /**
     * Holds live references to the NoInterpolate.values arrays for ALL components
     * in the injected color animation. Each component (cube) has its own independent
     * NoInterpolate instance — we must update every one of them when color changes,
     * otherwise only the first cube gets the new color.
     *
     * Layout: allColorValues[component][channel] where channel 0=R, 1=G, 2=B
     */
    public static class InjectedColorAnim {
        private final float[][][] allColorValues; // [component][0=R/1=G/2=B][0] (values array)
        InjectedColorAnim(float[][][] allColorValues) {
            this.allColorValues = allColorValues;
        }
        public void setColor(int packed) {
            float r = (packed >> 16) & 0xFF;
            float g = (packed >>  8) & 0xFF;
            float b =  packed        & 0xFF;
            for (float[][] cv : allColorValues) {
                cv[0][0] = r;
                cv[1][0] = g;
                cv[2][0] = b;
            }
        }
    }

    // groupKey -> InjectedColorAnim for the LOCAL player model
    private static final java.util.Map<String, InjectedColorAnim> injectedAnims = new java.util.LinkedHashMap<>();

    // "uuid:groupKey" -> InjectedColorAnim for OTHER players' models (viewer-side)
    private static final java.util.Map<String, InjectedColorAnim> peerInjectedAnims = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Injects a single-frame constant-color Animation into the ModelDefinition's
     * AnimationRegistry for the given group of cubes.
     *
     * Because this is a real AnimationTrigger on GLOBAL pose, CPM's AnimationEngine
     * runs it every frame on every client that has the model loaded — no XtraNimations
     * needed on the viewer.
     */
    @SuppressWarnings("unchecked")
    public static InjectedColorAnim injectColorAnimation(
            ModelDefinition def,
            java.util.List<Object> cubes,
            int packed) {
        try {
            com.tom.cpm.shared.animation.AnimationRegistry registry = def.getAnimations();
            if (registry == null) return null;

            int numComponents = cubes.size();
            int numChannels = com.tom.cpm.shared.animation.InterpolatorChannel.VALUES.length;
            float[][][] data = new float[numComponents][numChannels][1];

            float r = (packed >> 16) & 0xFF;
            float g = (packed >>  8) & 0xFF;
            float b =  packed        & 0xFF;

            for (int ci = 0; ci < numComponents; ci++) {
                for (com.tom.cpm.shared.animation.InterpolatorChannel ch :
                        com.tom.cpm.shared.animation.InterpolatorChannel.VALUES) {
                    data[ci][ch.channelID()][0] = ch.defaultValue;
                }
                data[ci][com.tom.cpm.shared.animation.InterpolatorChannel.COLOR_R.channelID()][0] = r;
                data[ci][com.tom.cpm.shared.animation.InterpolatorChannel.COLOR_G.channelID()][0] = g;
                data[ci][com.tom.cpm.shared.animation.InterpolatorChannel.COLOR_B.channelID()][0] = b;
            }

            Boolean[][] show = new Boolean[numComponents][1];
            for (int ci = 0; ci < numComponents; ci++) show[ci][0] = true;

            // Proxy components: only forward setColor to the real cube.
            // All other calls (setPosition, setRotation, setVisible, setRenderScale) are no-ops.
            // This prevents our animation from touching positions/rotations at all,
            // avoiding NPEs when cube.pos is null (children that inherit parent transforms)
            // and preventing any layout disruption.
            com.tom.cpm.shared.animation.IModelComponent[] components =
                new com.tom.cpm.shared.animation.IModelComponent[numComponents];
            for (int ci = 0; ci < numComponents; ci++) {
                final com.tom.cpm.shared.animation.IModelComponent realCube =
                    (com.tom.cpm.shared.animation.IModelComponent) cubes.get(ci);
                components[ci] = new com.tom.cpm.shared.animation.IModelComponent() {
                    @Override public void setPosition(boolean add, float x, float y, float z) {}
                    @Override public void setRotation(boolean add, float x, float y, float z) {}
                    @Override public void setVisible(boolean v) {}
                    @Override public void setRenderScale(boolean add, float x, float y, float z) {}
                    @Override public void reset() {}
                    @Override public com.tom.cpl.math.Vec3f getPosition() { return realCube.getPosition(); }
                    @Override public com.tom.cpl.math.Rotation getRotation() { return realCube.getRotation(); }
                    @Override public com.tom.cpl.math.Vec3f getRenderScale() { return realCube.getRenderScale(); }
                    @Override public boolean isVisible() { return realCube.isVisible(); }
                    @Override public int getRGB() { return realCube.getRGB(); }
                    @Override public void setColor(float r2, float g2, float b2) { realCube.setColor(r2, g2, b2); }
                };
            }

            com.tom.cpm.shared.animation.Animation anim =
                new com.tom.cpm.shared.animation.Animation(
                    components, data, show,
                    1,     // duration=1ms → step always 0 → frame[0] always used
                    1000,  // high priority overrides model's own color animations
                    false, // add=false: fine since proxy no-ops all non-color calls
                    com.tom.cpm.shared.animation.interpolator.InterpolatorType.NO_INTERPOLATE
                );

            java.util.Set<com.tom.cpm.shared.animation.IPose> onPoses = new java.util.HashSet<>();
            onPoses.add(com.tom.cpm.shared.animation.VanillaPose.GLOBAL);

            com.tom.cpm.shared.animation.AnimationTrigger trigger =
                new com.tom.cpm.shared.animation.AnimationTrigger(
                    registry, onPoses, null,
                    java.util.Collections.singletonList(anim),
                    true, false);

            registry.register(trigger);

            // Get live references to the NoInterpolate.values arrays
            java.lang.reflect.Field fPsfs = com.tom.cpm.shared.animation.Animation.class.getDeclaredField("psfs");
            fPsfs.setAccessible(true);
            Object[][] psfs = (Object[][]) fPsfs.get(anim);

            Class<?> noInterpCls = Class.forName("com.tom.cpm.shared.animation.interpolator.NoInterpolate");
            java.lang.reflect.Field fValues = noInterpCls.getDeclaredField("values");
            fValues.setAccessible(true);

            int rIdx = com.tom.cpm.shared.animation.InterpolatorChannel.COLOR_R.channelID();
            int gIdx = com.tom.cpm.shared.animation.InterpolatorChannel.COLOR_G.channelID();
            int bIdx = com.tom.cpm.shared.animation.InterpolatorChannel.COLOR_B.channelID();

            // Collect color value arrays for EVERY component (cube), not just component 0.
            // Each component has independent NoInterpolate instances — all must be updated
            // together when setColor() is called, or only the first cube changes color.
            float[][][] allColorValues = new float[numComponents][3][];
            for (int ci = 0; ci < numComponents; ci++) {
                allColorValues[ci][0] = (float[]) fValues.get(psfs[ci][rIdx]);
                allColorValues[ci][1] = (float[]) fValues.get(psfs[ci][gIdx]);
                allColorValues[ci][2] = (float[]) fValues.get(psfs[ci][bIdx]);
            }

if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] Injected color anim: {} cubes, #{}", numComponents, String.format("%06X", packed));
            return new InjectedColorAnim(allColorValues);
        } catch (Exception e) {
            XtraNimations.LOGGER.warn("[XtraNimations] injectColorAnimation failed: {}", e.getMessage());
            return null;
        }
    }

        @SuppressWarnings("unchecked")
    public static void patchAnimationInterpolators(ModelDefinition def) {
        // patchAnimationInterpolators header log removed (redundant)
        if (groupToCubes.isEmpty() || def == null || f_trigAnimations == null) return;
        if (!initReflection()) return;

        // Build a map of cubeId -> our packed color
        Map<Integer, Integer> cubeColors = new HashMap<>();
        for (Map.Entry<String, List<Object>> e : groupToCubes.entrySet()) {
            int[] rgb  = RgbColorStore.get(e.getKey());
            int packed = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            for (Object rendCube : e.getValue()) {
                try {
                    Object underlying = mh_getCube.invoke(rendCube);
                    int id = f_cubeId.getInt(underlying);
                    cubeColors.put(id, packed);
                } catch (Throwable ignored) {}
            }
        }
        if (cubeColors.isEmpty()) return;

        // Walk all AnimationTriggers -> IAnimation -> Animation -> psfs
        com.tom.cpm.shared.animation.AnimationRegistry registry = def.getAnimations();
        if (registry == null) return;

        int patched = 0;
        java.util.Set<com.tom.cpm.shared.animation.AnimationTrigger> triggers = registry.getAnimations();
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB patch: {} triggers for {} cubes", triggers.size(), cubeColors.size());
        for (com.tom.cpm.shared.animation.AnimationTrigger trigger : triggers) {
            List<Object> anims;
            try { anims = (List<Object>) f_trigAnimations.get(trigger); }
            catch (Throwable e) { XtraNimations.LOGGER.warn("[XtraNimations] RGB: f_trigAnimations failed: {}", e.getMessage()); continue; }

            for (Object anim : anims) {
                // anim class log removed (too spammy)
                Object animToPatch = anim;
                if (!animClass.isInstance(anim)) {
                    // StagedAnimation$Anim wraps the real Animation in a 'parent' field
                    try {
                        java.lang.reflect.Field fParent = anim.getClass().getDeclaredField("parent");
                        fParent.setAccessible(true);
                        Object parent = fParent.get(anim);
                        if (parent != null && animClass.isInstance(parent)) {
                            animToPatch = parent;
                        } else {
                            continue;
                        }
                    } catch (Throwable e) { continue; }
                }
                patched += patchAnimation(animToPatch, def, cubeColors);
            }
        }

if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB patched {} interpolator channel(s) in animations.", patched);
    }

    @SuppressWarnings("unchecked")
    private static int patchAnimation(Object anim, ModelDefinition def, Map<Integer, Integer> cubeColors) {
        // Animation.psfs is Interpolator[componentCount][channelCount]
        // Animation.componentIDs is IModelComponent[] - we need the cube ids

        Object[][] psfs;
        Object[] componentIDs;
        try {
            psfs = (Object[][]) f_psfs.get(anim);
            Field fComps = animClass.getDeclaredField("componentIDs");
            fComps.setAccessible(true);
            componentIDs = (Object[]) fComps.get(anim);
        } catch (Throwable e) { return 0; }

        if (psfs == null || componentIDs == null) return 0;

        int patched = 0;
        for (int ci = 0; ci < componentIDs.length; ci++) {
            Object rendCube = componentIDs[ci];
            int cubeId;
            try {
                Object underlying = mh_getCube.invoke(rendCube);
                cubeId = f_cubeId.getInt(underlying);
            } catch (Throwable e) {
                // getCube failed log removed (too spammy)
                continue;
            }
if (XtraConfig.DEBUG) if (cubeColors.containsKey(cubeId)) XtraNimations.LOGGER.info("[XtraNimations] RGB patchAnim: comp[{}] cubeId={} inMap=true (WILL PATCH)", ci, cubeId);

            Integer packed = cubeColors.get(cubeId);
            if (packed == null) continue;

            float r = ((packed >> 16) & 0xFF);
            float g = ((packed >>  8) & 0xFF);
            float b = ( packed        & 0xFF);

            // Channels: COLOR_R=6, COLOR_G=7, COLOR_B=8
            int[] colorChannels = {6, 7, 8};
            float[] colorValues  = {r, g, b};

            for (int ch = 0; ch < 3; ch++) {
                int channelIdx = colorChannels[ch];
                if (channelIdx >= psfs[ci].length) continue;
                Object interpolator = psfs[ci][channelIdx];
                if (interpolator == null) continue;
                try {
                    Field fValues = getValuesField(interpolator);
                    if (fValues != null) {
                        // Linear/NoInterpolate: just fill the values array
                        float[] vals = (float[]) fValues.get(interpolator);
                        if (vals != null) {
                            java.util.Arrays.fill(vals, colorValues[ch]);
                            patched++;
                        }
                    } else {
                        // PolynomialSpline or unknown: replace the whole interpolator
                        // with a ConstantInterpolator that always returns our value
                        final float constVal = colorValues[ch];
                        com.tom.cpm.shared.animation.interpolator.Interpolator constInterp =
                            new com.tom.cpm.shared.animation.interpolator.Interpolator() {
                                final int[] callCount = {0};
                                @Override public double applyAsDouble(double op) {
                if (XtraConfig.DEBUG) if (callCount[0]++ < 3) XtraNimations.LOGGER.info("[XtraNimations] constInterp ch={} called, returning {}", channelIdx, constVal);
                                    return constVal;
                                }
                                @Override public void init(float[] v, java.util.function.DoubleUnaryOperator s) {}
                            };
                        psfs[ci][channelIdx] = constInterp;
                        patched++;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return patched;
    }

    // Cache for values field per concrete Interpolator class
    private static final Map<Class<?>, Field> interpolatorValuesFieldCache = new HashMap<>();

    private static Field getValuesField(Object interpolator) {
        Class<?> cls = interpolator.getClass();
        return interpolatorValuesFieldCache.computeIfAbsent(cls, c -> {
            try {
                Field f = c.getDeclaredField("values");
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                return null;
            }
        });
    }

    /** No longer needed - kept for peer-side color writes */
    public static void tickColors() {
        // Intentionally empty - replaced by patchAnimationInterpolators()
    }

    /**
     * Called each render tick. Retries any pending peer color applications
     * where the remote player's gist model wasn't fully loaded yet.
     */

    // ── Gesture piggyback (viewer-side, no XtraNimations required) ────────────

    /**
     * Sends our RGB group colors to all CPM-using clients by appending them to the
     * gesture sync packet that CPM already broadcasts to all tracking players.
     *
     * Format appended to existing gestureData: [R, G, B, MAGIC(0xAB)]
     * CPM only reads gestureData[0] and [1], so extra bytes are safely ignored.
     */
    @SuppressWarnings("unchecked")



    public static void tickPeerRetry() {
        if (pendingPeerColors.isEmpty()) return;
        // Iterate a copy to avoid ConcurrentModificationException
        for (java.util.UUID uuid : new ArrayList<>(pendingPeerColors.keySet())) {
            Map<String, Integer> colors = pendingPeerColors.get(uuid);
            if (colors == null) continue;
            applyColorsToOtherPlayer(uuid, colors);
        }
    }

    // ── Apply to OTHER player (viewer-side, RGB_PEER) ─────────────────────────

    @SuppressWarnings("unchecked")
    public static void applyColorsToOtherPlayer(java.util.UUID playerUuid,
                                                 Map<String, Integer> groupColors) {
        if (groupColors == null || groupColors.isEmpty()) return;
        if (!initReflection()) return;

        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return;
            net.minecraft.world.entity.player.Player mcPlayer = mc.level.getPlayerByUUID(playerUuid);
            if (mcPlayer == null) {
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB peer: player {} not in world", playerUuid);
                return;
            }

            Object clientAccess = com.tom.cpm.shared.MinecraftClientAccess.get();
            if (clientAccess == null) return;

            ModelDefinition def = resolveModelDef(clientAccess, mcPlayer);
            if (def == null) {
                // Queue for retry - model may not be loaded yet
                pendingPeerColors.put(playerUuid, groupColors);
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB peer: no CPM model for {}, queued", playerUuid);
                return;
            }

            Map<String, List<Object>> peerGroups = new LinkedHashMap<>();

            // Scan parts (normal format)
            List<Object> parts = null;
            try { parts = (List<Object>) f_parts.get(def); } catch (Throwable ignored) {}
            if (parts != null) {
                for (Object part : parts) {
                    String cls = part.getClass().getName();
                    if (cls.equals("com.tom.cpm.shared.parts.ModelPartRenderEffect")) {
                        checkRenderEffectInto(part, def, peerGroups);
                    } else if (cls.equals("com.tom.cpm.shared.parts.ModelPartDefinition")) {
                        scanPartDefinitionOtherPartsInto(part, def, peerGroups);
                    }
                }
            }

            // Scan resolved (gist format)
            List<Object> resolved = null;
            try { resolved = (List<Object>) f_resolved.get(def); } catch (Throwable ignored) {}
            if (resolved != null) {
                for (Object rpart : resolved) {
                    if (rpart.getClass().getName()
                            .equals("com.tom.cpm.shared.parts.ModelPartDefinition")) {
                        scanPartDefinitionResolvedOtherPartsInto(rpart, def, peerGroups);
                    }
                }
            }

            if (peerGroups.isEmpty()) {
                // Model not resolved yet — stash and retry next tick
                pendingPeerColors.put(playerUuid, groupColors);
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB peer: model not ready for {}, queued for retry", playerUuid);
                return;
            }

            // Keep in pending until model is fully LOADED.
            // Gist models load async: colors applied before LOADED get wiped when
            // the model finishes resolving and resetAnimationPos() runs.
            com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState state = def.getResolveState();
            if (state != com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.LOADED) {
                pendingPeerColors.put(playerUuid, groupColors); // keep retrying
            } else {
                pendingPeerColors.remove(playerUuid); // fully loaded, done
            }

            int applied = 0;
            for (Map.Entry<String, Integer> incoming : groupColors.entrySet()) {
                List<Object> cubes = peerGroups.get(incoming.getKey());
                if (cubes == null) continue;
                int packed = incoming.getValue();

                // Check if we already injected an animation for this player+group
                String animKey = playerUuid + ":" + incoming.getKey();
                InjectedColorAnim existing = peerInjectedAnims.get(animKey);
                if (existing != null) {
                    existing.setColor(packed);
                    applied += cubes.size();
                } else {
                    InjectedColorAnim ica = injectColorAnimation(def, cubes, packed);
                    if (ica != null) {
                        peerInjectedAnims.put(animKey, ica);
                        applied += cubes.size();
                    }
                }
            }
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB peer {} -> injected/updated {} cubes", playerUuid, applied);

        } catch (Exception e) {
            XtraNimations.LOGGER.warn("[XtraNimations] RGB applyColorsToOtherPlayer: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void scanPartDefinitionOtherPartsInto(Object partDef, ModelDefinition def,
                                                          Map<String, List<Object>> out) {
        List<Object> otherParts;
        try { otherParts = (List<Object>) f_otherParts.get(partDef); }
        catch (Throwable e) { return; }
        if (otherParts == null) return;
        for (Object other : otherParts) {
            if (other.getClass().getName()
                    .equals("com.tom.cpm.shared.parts.ModelPartRenderEffect")) {
                checkRenderEffectInto(other, def, out);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void scanPartDefinitionResolvedOtherPartsInto(Object partDef, ModelDefinition def,
                                                                   Map<String, List<Object>> out) {
        List<Object> resolvedOtherParts;
        try { resolvedOtherParts = (List<Object>) f_resolvedOtherParts.get(partDef); }
        catch (Throwable e) { return; }
        if (resolvedOtherParts == null) return;
        for (Object rpart : resolvedOtherParts) {
            if (rpart.getClass().getName()
                    .equals("com.tom.cpm.shared.parts.ModelPartRenderEffect")) {
                checkRenderEffectInto(rpart, def, out);
            }
        }
    }

    private static void checkRenderEffectInto(Object renderEffectPart, ModelDefinition def,
                                               Map<String, List<Object>> out) {
        Object effect;
        try { effect = f_effect.get(renderEffectPart); }
        catch (Throwable e) { return; }
        if (effect == null) return;
        if (!effect.getClass().getName().equals("com.tom.cpm.shared.effects.EffectColor")) return;

        int cubeId, tagColor;
        try {
            cubeId   = (int) f_effectId.get(effect);
            tagColor = (int) f_effectColor.get(effect);
        } catch (Throwable e) {
            return;
        }

        int colorVal = tagColor & 0xFFFFFF;
        if (colorVal == 0xFFFFFF || colorVal == 0x000000) return;
        String groupKey = String.format("rgb:%06X", colorVal);

        Object cube = def.getElementById(cubeId);
        if (cube == null) return;
        out.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(cube);
    }

    // ── CPM player model lookup ───────────────────────────────────────────────

    private static ModelDefinition resolveModelDef(Object clientAccess,
                                                    net.minecraft.world.entity.player.Player mcPlayer) {
        try {
            // MinecraftClientAccess.getDefinitionLoader() returns ModelDefinitionLoader<GameProfile>
            Object loader = clientAccess.getClass().getMethod("getDefinitionLoader").invoke(clientAccess);
            if (loader == null) return null;

            // getPlayers() returns List<Object> where each element is a GameProfile
            List<?> players = (List<?>) clientAccess.getClass().getMethod("getPlayers").invoke(clientAccess);
            if (players == null) return null;

            java.util.UUID targetUuid = mcPlayer.getUUID();
            Object matchedProfile = null;
            for (Object profile : players) {
                try {
                    Object profileId = profile.getClass().getMethod("getId").invoke(profile);
                    if (targetUuid.equals(profileId)) {
                        matchedProfile = profile;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (matchedProfile == null) {
                if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[XtraNimations] RGB peer: GameProfile not found for {}", targetUuid);
                return null;
            }

            // loadPlayer(GameProfile, String) → Player<?>
            Object cpmPlayer = loader.getClass()
                    .getMethod("loadPlayer", Object.class, String.class)
                    .invoke(loader, matchedProfile, "player");
            if (cpmPlayer == null) return null;

            Object d = cpmPlayer.getClass().getMethod("getModelDefinition").invoke(cpmPlayer);
            if (d instanceof ModelDefinition) return (ModelDefinition) d;
        } catch (Exception e) {
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[XtraNimations] RGB peer resolveModelDef failed: {}", e.getMessage());
        }
        return null;
    }

    // ── Misc ─────────────────────────────────────────────────────────────────

    public static Set<String> getDetectedLabels() {
        return groupToCubes.keySet();
    }

    private static void scheduleGuardReset() {
        Thread t = new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            isPatchingInProgress = false;
        }, "xtranims-patch-guard-reset");
        t.setDaemon(true);
        t.start();
    }

    public static void reset() {
        groupToCubes.clear();
        cubeIdToColor.clear();
        cubeIdToTagColor.clear();
        cubeIdToOriginalGroup.clear();
        cubeIdToOriginalTagColor.clear();
        isPatchingInProgress = false;
        lastDef = null;
        interpolatorValuesFieldCache.clear();
        injectedAnims.clear();
        peerInjectedAnims.clear();
        pendingPeerColors.clear();
    }

    /** Removes per-player state when a nearby player leaves the world. */
    public static void clearPeerPlayer(java.util.UUID uuid) {
        pendingPeerColors.remove(uuid);
        peerInjectedAnims.entrySet().removeIf(e -> e.getKey().startsWith(uuid.toString()));
    }
}
