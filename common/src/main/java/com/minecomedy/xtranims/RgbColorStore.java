package com.minecomedy.xtranims;


import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages saved RGB colors and display names for each color group,
 * scoped per model file to avoid conflicts between models sharing the same hex.
 *
 * Normal models: one file per model, keyed by rgb:RRGGBB (hex is stable).
 * Gist/paste models: same file format but also stores cubeId per group so the
 *   key survives hex drift caused by patchAndResend reload cycles.
 *
 * File: config/xtranims/colors/<modelName>.txt
 * Format (all groups in one file, blank-line separated):
 *   name  = rgb:FD2868          ← internal key (hex OR user label after renaming)
 *   label = Ribbon               ← display name shown in GUI
 *   cubeId = 15                  ← gist models only; used to re-anchor key after hex drift
 *   r = 43
 *   g = 225
 *   b = 255
 */
public class RgbColorStore {

    /** In-memory store: internalKey → [r, g, b] */
    private static final Map<String, int[]> colors = new ConcurrentHashMap<>();
    /** In-memory store: internalKey → display name */
    private static final Map<String, String> names  = new ConcurrentHashMap<>();
    /**
     * For gist models: cubeId → saved groupKey.
     * Populated when loading a gist model's file. Lets scanModel re-use a stable
     * key even after patchAndResend causes the EffectColor hex to drift.
     * Cleared on model switch.
     */
    private static final Map<Integer, String> savedCubeIdToKey = new ConcurrentHashMap<>();

    /** The model filename currently active (e.g. "dianinha.cpmmodel"). */
    private static volatile String currentModel = null;

    private static Path colorDir() {
        return XtraConfig.colorsDir();
    }

    private static Path modelFile() {
        String model = currentModel;
        if (model == null || model.isEmpty()) model = "default";
        String safe = model.replace(":", "_").replace("/", "_").replace("\\", "_");
        return colorDir().resolve(safe + ".txt");
    }

    // ── Model switching ───────────────────────────────────────────────────────

    /**
     * Called when the player's active model changes.
     * Clears in-memory state and loads the new model's saved colors.
     */
    public static void switchModel(String modelName) {
        // Null means the model name could not be determined (config read failed).
        // In that case, keep whatever model is currently loaded — don't wipe in-memory colors.
        if (modelName == null) return;
        if (Objects.equals(modelName, currentModel)) return;
        if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RgbColorStore: switching model '{}' → '{}'",
                currentModel, modelName);
        currentModel = modelName;
        colors.clear();
        names.clear();
        savedCubeIdToKey.clear();
        loadCurrentModel();
    }

    /** Returns the currently active model name. */
    public static String getCurrentModel() { return currentModel; }

    /**
     * For gist models: returns the saved groupKey for a cubeId, or null if unknown.
     * RgbReflectionHelper calls this during scanModel so it can re-anchor drifted hex keys.
     */
    public static String getSavedKeyForCubeId(int cubeId) {
        return savedCubeIdToKey.get(cubeId);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    private static final int[] DEFAULT_WHITE = {255, 255, 255};

    public static int[] get(String key) {
        int[] v = colors.get(key);
        return v != null ? v : DEFAULT_WHITE;
    }

    public static boolean hasSaved(String key) {
        return colors.containsKey(key);
    }

    public static String getDisplayName(String key) {
        return names.getOrDefault(key, key);
    }

    public static void setInMemory(String key, int r, int g, int b) {
        int cr = clamp(r), cg = clamp(g), cb = clamp(b);
        int[] existing = colors.get(key);
        if (existing != null && existing[0] == cr && existing[1] == cg && existing[2] == cb) return;
        colors.put(key, new int[]{cr, cg, cb});
    }

    public static void setNameInMemory(String key, String displayName) {
        names.put(key, displayName.isEmpty() ? key : displayName);
    }

    /**
     * Persists all groups for the current model to disk.
     * For gist models, also saves the cubeId mapping so keys survive hex drift.
     * cubeIds are provided by RgbReflectionHelper from its current groupToCubeIds map.
     */
    public static void save(String key, Map<String, List<Integer>> groupToCubeId) {
        saveCurrentModel(groupToCubeId);
    }

    /**
     * Compatibility overload for normal models (no cubeId tracking needed).
     */
    public static void save(String key) {
        saveCurrentModel(Collections.emptyMap());
    }

    public static void revert(String key) {
        colors.clear();
        names.clear();
        savedCubeIdToKey.clear();
        loadCurrentModel();
    }

    /** No-op at startup — loading is done lazily per model via switchModel(). */
    public static void loadAll() {}

    public static Set<String> getLabels() { return colors.keySet(); }

    public static void ensureLabels(Set<String> keys) {
        for (String key : keys) {
            colors.putIfAbsent(key, new int[]{255, 255, 255});
            names.putIfAbsent(key, key);
        }
    }

    // ── Disk I/O ──────────────────────────────────────────────────────────────

    private static void loadCurrentModel() {
        Path file = modelFile();
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            String pendingKey = null;
            String pendingLabel = null;
            List<Integer> pendingCubeIds = new ArrayList<>();
            int r = 255, g = 255, b = 255;
            boolean hasData = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    if (hasData && pendingKey != null) {
                        commitBlock(pendingKey, pendingLabel, pendingCubeIds, r, g, b);
                    }
                    pendingKey = null; pendingLabel = null; pendingCubeIds = new ArrayList<>();
                    r = 255; g = 255; b = 255; hasData = false;
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                switch (k) {
                    case "name"   -> { pendingKey = v;                     hasData = true; }
                    case "label"  -> { pendingLabel = v; }
                    case "cubeId"  -> { int id = parseInt(v, -1); if (id >= 0) pendingCubeIds.add(id); }  // legacy single
                    case "cubeIds" -> { for (String p : v.split(",")) { int id = parseInt(p.trim(), -1); if (id >= 0) pendingCubeIds.add(id); } }
                    case "r"      -> { r = parseInt(v, 255);              hasData = true; }
                    case "g"      -> { g = parseInt(v, 255);              hasData = true; }
                    case "b"      -> { b = parseInt(v, 255);              hasData = true; }
                }
            }
            // Flush last block
            if (hasData && pendingKey != null) {
                commitBlock(pendingKey, pendingLabel, pendingCubeIds, r, g, b);
            }
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] Loaded {} color groups from {}",
                    colors.size(), file.getFileName());
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Failed to read model color file {}", file, e);
        }
    }

    private static void commitBlock(String key, String label, List<Integer> cubeIds, int r, int g, int b) {
        colors.put(key, new int[]{clamp(r), clamp(g), clamp(b)});
        names.put(key, (label != null && !label.isEmpty()) ? label : key);
        for (int cubeId : cubeIds) {
            savedCubeIdToKey.put(cubeId, key);
        }
    }

    private static void saveCurrentModel(Map<String, List<Integer>> groupToCubeId) {
        if (colors.isEmpty()) return;
        try {
            Files.createDirectories(colorDir());
            List<String> keys = new ArrayList<>(colors.keySet());
            Collections.sort(keys);
            try (BufferedWriter w = Files.newBufferedWriter(modelFile())) {
                boolean first = true;
                for (String key : keys) {
                    if (!first) w.newLine();
                    first = false;
                    int[] rgb = colors.getOrDefault(key, new int[]{255, 255, 255});
                    String label = names.getOrDefault(key, key);
                    w.write("name = "  + key);   w.newLine();
                    w.write("label = " + label); w.newLine();
                    List<Integer> cubeIds = groupToCubeId.get(key);
                    if (cubeIds != null && !cubeIds.isEmpty()) {
                        // Join all cubeIds as comma-separated list
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < cubeIds.size(); i++) {
                            if (i > 0) sb.append(',');
                            sb.append(cubeIds.get(i));
                        }
                        w.write("cubeIds = " + sb); w.newLine();
                    }
                    w.write("r = " + rgb[0]); w.newLine();
                    w.write("g = " + rgb[1]); w.newLine();
                    w.write("b = " + rgb[2]); w.newLine();
                }
            }
            XtraNimations.LOGGER.info("[XtraNimations] Saved {} color groups to {}",
                    colors.size(), modelFile().getFileName());
        } catch (IOException e) {
            XtraNimations.LOGGER.error("[XtraNimations] Failed to save model color file", e);
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
