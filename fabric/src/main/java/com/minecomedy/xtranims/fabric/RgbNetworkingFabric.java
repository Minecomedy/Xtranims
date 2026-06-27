package com.minecomedy.xtranims.fabric;

import com.minecomedy.xtranims.RgbReflectionHelper;
import com.minecomedy.xtranims.RgbState;
import com.minecomedy.xtranims.XtraConfig;
import com.minecomedy.xtranims.XtraNimations;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/**
 * Fabric transport for the {@code xtranims:rgb} channel.
 *
 * Same channel id, same packet ids (encoded implicitly as separate channel
 * names here since Fabric's classic networking API keys handlers by channel
 * name rather than a single channel + numeric sub-ids like Forge's
 * SimpleChannel), same wire format via RgbState's shared codec — a Forge
 * server and a Fabric server speak the same bytes on the wire.
 *
 * Requires fabric-networking-api-v1 (bundled in the full fabric-api).
 *
 * NOTE ON TYPES: this assumes the fabric module is built with
 * loom.officialMojangMappings() (matching gradle.properties' Forge-side
 * mapping_channel=official), so Fabric API's MC parameter types resolve to
 * the same Mojang names you already use (FriendlyByteBuf, ResourceLocation,
 * ServerPlayer) instead of Yarn's PacketByteBuf/Identifier/ServerPlayerEntity.
 */
public class RgbNetworkingFabric {

    @SuppressWarnings("removal")
    private static final ResourceLocation HELLO_ID       = new ResourceLocation(XtraNimations.MODID, "rgb_hello");
    @SuppressWarnings("removal")
    private static final ResourceLocation HELLO_ACK_ID    = new ResourceLocation(XtraNimations.MODID, "rgb_hello_ack");
    @SuppressWarnings("removal")
    private static final ResourceLocation RGB_UPDATE_ID   = new ResourceLocation(XtraNimations.MODID, "rgb_update");
    @SuppressWarnings("removal")
    private static final ResourceLocation RGB_PEER_ID     = new ResourceLocation(XtraNimations.MODID, "rgb_peer");

    // ── Common registration (both sides) ────────────────────────────────────────

    /** Call once from XtraNimationsFabric (common init point for both client and dedicated server). */
    public static void registerCommon() {
        ServerPlayNetworking.registerGlobalReceiver(HELLO_ID, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] HELLO from {}.", player.getName().getString());
                FriendlyByteBuf ack = PacketByteBufs.create();
                ServerPlayNetworking.send(player, HELLO_ACK_ID, ack);
                pushAllTo(player);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RGB_UPDATE_ID, (server, player, handler, buf, responseSender) -> {
            Map<String, Integer> groups = RgbState.decodeColorMap(buf);
            server.execute(() -> {
                UUID uuid = player.getUUID();
                RgbState.recordUpdate(uuid, groups);
                if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] RGB_UPDATE from {}: {} groups.",
                        player.getName().getString(), groups.size());
                broadcastPeerToNearby(player, uuid, groups);
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> pushAllTo(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer p = handler.player;
            RgbState.forgetPlayer(p.getUUID());
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] Cleared RGB state for {}", p.getName().getString());
        });

        XtraNimations.LOGGER.info("[Xtranims] RgbNetworkingFabric server-side handlers registered (xtranims:rgb v{}).", RgbState.VERSION_STR);
    }

    /** Call once from XtraNimationsFabric#onInitializeClient — client-only receivers. */
    public static void registerClient() {
        RgbState.sendHelloHook = RgbNetworkingFabric::sendHello;

        ClientPlayNetworking.registerGlobalReceiver(HELLO_ACK_ID, (client, handler, buf, responseSender) -> {
            client.execute(() -> {
                RgbState.serverHasCapability = true;
                if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] HELLO_ACK - server-assisted RGB sync active.");
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RGB_PEER_ID, (client, handler, buf, responseSender) -> {
            UUID uuid = buf.readUUID();
            Map<String, Integer> groups = RgbState.decodeColorMap(buf);
            client.execute(() -> {
                if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] RGB_PEER for {}: {} groups.", uuid, groups.keySet());
                RgbReflectionHelper.applyColorsToOtherPlayer(uuid, groups);
            });
        });
    }

    // ── Client API (mirrors RgbNetworkingForge) ─────────────────────────────────

    public static void sendHello() {
        RgbState.serverHasCapability = false;

        if (Minecraft.getInstance().hasSingleplayerServer()) {
            RgbState.serverHasCapability = true;
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] Integrated server detected — RGB sync active (LAN host).");
            return;
        }

        if (!ClientPlayNetworking.canSend(HELLO_ID)) {
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[Xtranims] Server has no xtranims:rgb_hello channel registered — skipping HELLO.");
            return;
        }

        try {
            ClientPlayNetworking.send(HELLO_ID, PacketByteBufs.create());
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] Sent HELLO (probing server capability).");
        } catch (Exception e) {
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[Xtranims] HELLO send failed: {}", e.getMessage());
        }
    }

    public static void sendRgbUpdate(Map<String, Integer> groupColors) {
        if (!RgbState.serverHasCapability || groupColors == null || groupColors.isEmpty()) return;
        try {
            FriendlyByteBuf buf = PacketByteBufs.create();
            RgbState.encodeColorMap(buf, groupColors);
            ClientPlayNetworking.send(RGB_UPDATE_ID, buf);
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] RGB_UPDATE sent: {} groups.", groupColors.size());
        } catch (Exception e) {
            XtraNimations.LOGGER.warn("[Xtranims] RGB_UPDATE send failed: {}", e.getMessage());
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static void pushAllTo(ServerPlayer target) {
        for (Map.Entry<UUID, Map<String, Integer>> e : RgbState.allEntries()) {
            sendPeerTo(target, e.getKey(), e.getValue());
        }
    }

    private static void sendPeerTo(ServerPlayer target, UUID uuid, Map<String, Integer> groups) {
        try {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeUUID(uuid);
            RgbState.encodeColorMap(buf, groups);
            ServerPlayNetworking.send(target, RGB_PEER_ID, buf);
        } catch (Exception ignored) {}
    }

    private static void broadcastPeerToNearby(ServerPlayer sender, UUID uuid, Map<String, Integer> groups) {
        for (ServerPlayer p : RgbState.nearbyTargets(sender)) {
            sendPeerTo(p, uuid, groups);
        }
    }
}
