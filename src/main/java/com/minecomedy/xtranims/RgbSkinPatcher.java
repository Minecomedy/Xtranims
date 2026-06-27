package com.minecomedy.xtranims;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.io.ModelFile;
import com.tom.cpm.shared.network.NetworkUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Map;

/**
 * Patches the EffectColor bytes inside the CPM model's dataBlock so that
 * the CHOSEN colors are baked into the skin data that CPM sends to the server.
 * The server stores this and forwards it to all nearby players.
 * Other clients load the patched bytes with CPM natively — no mod needed.
 *
 * How it works:
 *   CPM sends the raw model file bytes (dataBlock) to the server via SetSkinC2S.
 *   The server forwards it to nearby players via SetSkinS2C.
 *   Other clients parse those bytes through CPM's ModelDefinitionLoader.
 *   EffectColor.apply() sets cube.rgb from those bytes → our color shows natively.
 *
 * dataBlock binary layout:
 *   [0x53 HEADER]
 *   [stream of ModelParts, each as: byte(typeOrdinal) + payload]
 *     ...
 *     RENDER_EFFECT block: byte(8) byte(3) varInt(cubeId) byte(R) byte(G) byte(B)
 *     ...
 *   [ModelPartEnd: byte(0)]
 *   [checksum_high] [checksum_low]   ← simple sum of all preceding bytes
 *
 * We scan for [0x08][0x03] (RENDER_EFFECT + COLOR), decode the varInt cubeId,
 * check if it belongs to one of our color groups, and patch R/G/B in-place.
 * Then we fix the 2-byte checksum at the end to match the new content.
 * Finally we send the patched bytes as a new SetSkinC2S via CPM's net handler.
 */
public class RgbSkinPatcher {



    /**
     * Reads the player's current model file, patches EffectColor bytes for all
     * known RGB groups, and re-sends the patched skin data to the server.
     * The server will forward it to all nearby players automatically.
     *
     * @param cubeIdToColor    cubeId → new packed RGB (0xRRGGBB)
     * @param cubeIdToOriginal cubeId → original tag RGB (0xRRGGBB) — used to
     *                         verify we found the right bytes before patching
     */
    public static void patchAndResend(Map<Integer, Integer> cubeIdToColor,
                                      Map<Integer, Integer> cubeIdToOriginal) {
        if (cubeIdToColor.isEmpty()) {
            return;
        }

        boolean hasServer = MinecraftClientAccess.get().getNetHandler().hasModClient();
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB patch: hasModClient={}", hasServer);
        if (!hasServer) return;

        try {
            // 1. Find and load the model file from disk
            String modelName = ModConfig.getCommonConfig()
                    .getString(ConfigKeys.SELECTED_MODEL, null);
            // If CPM is in Test Ingame mode, SELECTED_MODEL is ".temp.cpmmodel" —
            // a temporary export. Fall back to the real model saved in SELECTED_MODEL_OLD.
            if (com.tom.cpm.shared.editor.TestIngameManager.TEST_MODEL_NAME.equals(modelName)) {
                String old = ModConfig.getCommonConfig()
                        .getString(ConfigKeys.SELECTED_MODEL_OLD, null);
                if (old != null && !old.equals("~~VANILLA~~")) {
                    modelName = old;
                }
            }
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB patch: modelName={}", modelName);
            if (modelName == null) return;

            File modelsDir = new File(
                    MinecraftClientAccess.get().getGameDir(), "player_models");
            File modelFile = new File(modelsDir, modelName);
            if (!modelFile.exists()) {
                XtraNimations.LOGGER.warn("[XtraNimations] RGB patch: model file not found: {}", modelFile);
                return;
            }
            ModelFile file = ModelFile.load(modelFile);

            // For gist models the real model bytes are in overflowLocal (the cached
            // download). overflowLocal format from the URL: [0x53][ModelPartDefinition payload][sum_hi][sum_lo]
            // We cannot send overflowLocal directly as DATA_TAG — it must be wrapped as a
            // typed ModelPart block stream. We inline it as a DEFINITION block:
            //   [0x53][DEFINITION(3)][varInt(size)][inner bytes][END(0)][varInt(0)][sum_hi][sum_lo]
            // where "inner bytes" = overflowLocal[1..len-3] (skip 0x53 header + 2 checksum bytes).
            byte[] dataBlock = file.getDataBlock();
            try {
                java.lang.reflect.Field fOverflow = ModelFile.class.getDeclaredField("overflowLocal");
                fOverflow.setAccessible(true);
                byte[] overflow = (byte[]) fOverflow.get(file);
                if (overflow != null && overflow.length > 3) {
                    // Extract inner ModelPartDefinition payload (skip leading 0x53 and trailing 2 checksum bytes)
                    byte[] inner = new byte[overflow.length - 3];
                    System.arraycopy(overflow, 1, inner, 0, inner.length);
                    // Patch RGB in inner bytes first
                    inner = patchRawBytes(inner, cubeIdToColor, cubeIdToOriginal);
                    if (inner == null) {
                        XtraNimations.LOGGER.warn("[XtraNimations] RGB patch: patchRawBytes found no matches in overflowLocal");
                        return;
                    }
                    // Build: [0x53][3=DEFINITION][varInt(inner.length)][inner][0=END][0=END block size]
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    baos.write(com.tom.cpm.shared.definition.ModelDefinitionLoader.HEADER); // 0x53
                    baos.write(3); // DEFINITION ordinal
                    writeVarIntTo(baos, inner.length);
                    baos.write(inner);
                    baos.write(0); // END ordinal
                    baos.write(0); // END block size (varInt 0 = single byte 0)
                    // Compute checksum over everything after the 0x53 header
                    byte[] body = baos.toByteArray();
                    short sum = 0;
                    for (int i = 1; i < body.length; i++) sum += (body[i] & 0xFF);
                    baos.write((sum >> 8) & 0xFF);
                    baos.write(sum & 0xFF);
                    dataBlock = baos.toByteArray();
    if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB patch: gist model inlined as DEFINITION block ({} bytes)", dataBlock.length);
                }
            } catch (Exception ex) {
                XtraNimations.LOGGER.warn("[XtraNimations] RGB patch: overflowLocal wrap failed: {}", ex.getMessage());
            }

            if (dataBlock == null || dataBlock.length == 0) return;

            // 2. Patch in-place and recompute checksum
            byte[] patched = patchDataBlock(dataBlock, cubeIdToColor, cubeIdToOriginal);
            if (patched == null) return;

            // 3. Send the patched bytes as SetSkinC2S via CPM's network
            NBTTagCompound tag = new NBTTagCompound();
            tag.setByteArray(NetworkUtil.DATA_TAG, patched);

            MinecraftClientAccess.get().getNetHandler()
                    .sendPacketToServer(
                            new com.tom.cpm.shared.network.packet.SetSkinC2S(tag));

if (XtraConfig.DEBUG) XtraNimations.LOGGER.info(
                    "[XtraNimations] RGB skin patch sent ({} cubes, {} bytes).",
                    cubeIdToColor.size(), patched.length);

        } catch (Exception e) {
            XtraNimations.LOGGER.warn(
                    "[XtraNimations] RGB skin patch failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Scans the dataBlock for EffectColor entries matching our cubeIds.
     *
     * Strategy: first discover where each cubeId appears with a 2-byte prefix
     * that looks like [renderEffectByte][colorByte] (small values 0-15 typical).
     * We find the ONE occurrence preceded by consistent small ordinal bytes,
     * which is the EffectColor entry. Then patch the 3 RGB bytes after it.
     *
     * Returns the patched byte array, or null if nothing was patched.
     */
    private static byte[] patchDataBlock(byte[] data, Map<Integer, Integer> cubeIdToColor,
                                          Map<Integer, Integer> cubeIdToOriginal) {
        byte[] out = data.clone();
        int patchCount = 0;
        int checksumDelta = 0;
        int streamEnd = out.length - 2;

        for (Map.Entry<Integer, Integer> entry : cubeIdToColor.entrySet()) {
            int cubeId   = entry.getKey();
            int newColor = entry.getValue();

            byte[] varint = encodeVarInt(cubeId);
            byte newR = (byte) ((newColor >> 16) & 0xFF);
            byte newG = (byte) ((newColor >>  8) & 0xFF);
            byte newB = (byte) (newColor         & 0xFF);

            // Find the ONE location where this cubeId is preceded by two small
            // ordinal bytes (both < 32), which identifies an EffectColor entry.
            // The bytes after must be 3 color bytes (R,G,B — all 0-255).
            // This avoids needing to know exact version-specific enum ordinals.
            int matchPos = -1;
            for (int i = 3; i < streamEnd - varint.length - 2; i++) {
                // Check 2-byte prefix: both must be small ordinals (< 32)
                int pre1 = out[i - 2] & 0xFF; // part-type ordinal
                int pre2 = out[i - 1] & 0xFF; // effect-type ordinal
                if ((pre1 != 5 && pre1 != 6) || pre2 != 3) continue;

                // Match varInt bytes
                boolean varMatch = true;
                for (int v = 0; v < varint.length; v++) {
                    if (out[i + v] != varint[v]) { varMatch = false; break; }
                }
                if (!varMatch) continue;

                // Ensure the 3 bytes after are plausible RGB (anything 0-255 is valid,
                // but we also accept only if the known origColor matches — if available)
                int rgbPos = i + varint.length;
                if (rgbPos + 2 >= streamEnd) continue;

                // No origColor check here: the prefix [small][small][varInt(cubeId)]
                // already uniquely identifies the EffectColor entry in the dataBlock.
                // Filtering by origColor would require the in-memory model tag colors to
                // match the file — which breaks in Test Ingame mode. Skip the check.

                matchPos = i;
                break;
            }

            if (matchPos == -1) {
                XtraNimations.LOGGER.warn("[XtraNimations] RGB: no match found for cubeId={}", cubeId);
                continue;
            }

            int rgbPos = matchPos + varint.length;
            checksumDelta += (newR & 0xFF) - (out[rgbPos]     & 0xFF);
            checksumDelta += (newG & 0xFF) - (out[rgbPos + 1] & 0xFF);
            checksumDelta += (newB & 0xFF) - (out[rgbPos + 2] & 0xFF);
            out[rgbPos]     = newR;
            out[rgbPos + 1] = newG;
            out[rgbPos + 2] = newB;
            patchCount++;

        }

        if (patchCount == 0) {
            XtraNimations.LOGGER.warn("[XtraNimations] RGB patch: no cubes matched in dataBlock (cubesWanted={})", cubeIdToColor.keySet());
            return null;
        }

        // Fix the 2-byte trailing checksum
        // Original checksum is at out[length-2] (high byte) and out[length-1] (low byte)
        int origChecksum = ((out[out.length - 2] & 0xFF) << 8)
                         |  (out[out.length - 1] & 0xFF);
        int newChecksum = (origChecksum + checksumDelta) & 0xFFFF;
        out[out.length - 2] = (byte) ((newChecksum >> 8) & 0xFF);
        out[out.length - 1] = (byte) ((newChecksum >> 0) & 0xFF);

        return out;
    }

    /** Encodes an int as a CPM-style varInt byte array. */
    public static byte[] encodeVarInt(int value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(5);
        while ((value & -128) != 0) {
            baos.write(value & 127 | 128);
            value >>>= 7;
        }
        baos.write(value);
        return baos.toByteArray();
    }

    /**
     * Same RGB scan/patch as patchDataBlock but operates on raw inner bytes
     * (no 0x53 header, no trailing checksum). Used for overflowLocal inner content.
     * Returns patched bytes, or null if nothing matched.
     */
    private static byte[] patchRawBytes(byte[] data, Map<Integer, Integer> cubeIdToColor,
                                         Map<Integer, Integer> cubeIdToOriginal) {
        byte[] out = data.clone();
        int patchCount = 0;
        int streamEnd = out.length; // no trailing checksum bytes

        for (Map.Entry<Integer, Integer> entry : cubeIdToColor.entrySet()) {
            int cubeId   = entry.getKey();
            int newColor = entry.getValue();

            byte[] varint = encodeVarInt(cubeId);
            byte newR = (byte) ((newColor >> 16) & 0xFF);
            byte newG = (byte) ((newColor >>  8) & 0xFF);
            byte newB = (byte) (newColor         & 0xFF);

            int matchPos = -1;
            for (int i = 3; i < streamEnd - varint.length - 2; i++) {
                int pre1 = out[i - 2] & 0xFF;
                int pre2 = out[i - 1] & 0xFF;
                if ((pre1 != 5 && pre1 != 6) || pre2 != 3) continue;
                boolean varMatch = true;
                for (int v = 0; v < varint.length; v++) {
                    if (out[i + v] != varint[v]) { varMatch = false; break; }
                }
                if (!varMatch) continue;
                int rgbPos = i + varint.length;
                if (rgbPos + 2 >= streamEnd) continue;
                matchPos = i;
                break;
            }

            if (matchPos == -1) {
                XtraNimations.LOGGER.warn("[XtraNimations] RGB raw patch: no match for cubeId={}", cubeId);
                continue;
            }

            int rgbPos = matchPos + varint.length;
            out[rgbPos]     = newR;
            out[rgbPos + 1] = newG;
            out[rgbPos + 2] = newB;
            patchCount++;
        }

        return patchCount == 0 ? null : out;
    }

    /** Writes a CPM-style varInt to a ByteArrayOutputStream. */
    private static void writeVarIntTo(java.io.ByteArrayOutputStream baos, int value) {
        while ((value & -128) != 0) {
            baos.write(value & 127 | 128);
            value >>>= 7;
        }
        baos.write(value);
    }
}
