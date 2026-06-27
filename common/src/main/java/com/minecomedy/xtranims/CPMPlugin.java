package com.minecomedy.xtranims;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.api.ICPMPlugin;
import com.tom.cpm.api.IClientAPI;
import com.tom.cpm.api.ICommonAPI;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CPMPlugin implements ICPMPlugin {

    public static IClientAPI clientApi = null;

    /** CPM plugin-message sender — set during initClient, used to broadcast RGB via CPM's channel */
    public static IClientAPI.MessageSender rgbMessageSender = null;

    @Override
    public String getOwnerModId() {
        return XtraNimations.MOD_ID;
    }

    @Override
    public void initClient(IClientAPI api) {
        clientApi = api;

        try {
            api.registerEditorGenerator(
                "xtranims_reference",
                "XtraNimations Triggers",
                gui -> {}
            );
        } catch (Exception e) {
            XtraNimations.LOGGER.warn(
                "[XtraNimations] Could not register CPM editor panel: {}", e.getMessage());
        }

        // Register RGB plugin message through CPM's channel.
        // broadcastToTracking=true → CPM server relays to all nearby players with CPM,
        // no XtraNimations required on the receiving client.
        try {
            rgbMessageSender = api.registerPluginMessage(
                "rgb",
                (UUID senderUuid, NBTTagCompound tag) -> {
                    // Received another player's RGB colors — apply to their model
                    int count = tag.getInteger("n");
                    Map<String, Integer> groups = new HashMap<>(count);
                    for (int i = 0; i < count; i++) {
                        String key   = tag.getString("k" + i);
                        int    color = tag.getInteger("v" + i);
                        if (key != null && !key.isEmpty()) groups.put(key, color);
                    }
                    if (!groups.isEmpty()) {
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] CPM RGB_PEER from {}: {} groups", senderUuid, groups.keySet());
                        RgbReflectionHelper.applyColorsToOtherPlayer(senderUuid, groups);
                    }
                },
                true  // broadcastToTracking
            );
            XtraNimations.LOGGER.info("[XtraNimations] CPM RGB plugin message registered.");
        } catch (Exception e) {
            XtraNimations.LOGGER.warn("[XtraNimations] Could not register CPM RGB plugin message: {}", e.getMessage());
        }
    }

    @Override
    public void initCommon(ICommonAPI api) {
    }

    /** Send our RGB colors to all tracking players via CPM's built-in relay channel. */
    public static void sendRgbViaCpm(Map<String, Integer> groupColors) {
        if (rgbMessageSender == null || groupColors == null || groupColors.isEmpty()) return;
        try {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("n", groupColors.size());
            int i = 0;
            for (Map.Entry<String, Integer> e : groupColors.entrySet()) {
                tag.setString("k" + i, e.getKey());
                tag.setInteger("v" + i, e.getValue());
                i++;
            }
            rgbMessageSender.sendMessage(tag);
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] CPM RGB sent: {} groups via CPM channel.", groupColors.size());
        } catch (Exception e) {
            XtraNimations.LOGGER.warn("[XtraNimations] CPM RGB send failed: {}", e.getMessage());
        }
    }
}
