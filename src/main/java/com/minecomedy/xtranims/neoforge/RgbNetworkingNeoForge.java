package com.minecomedy.xtranims.neoforge;

import com.minecomedy.xtranims.RgbReflectionHelper;
import com.minecomedy.xtranims.RgbState;
import com.minecomedy.xtranims.XtraConfig;
import com.minecomedy.xtranims.XtraNimations;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Map;
import java.util.UUID;

/**
 * NeoForge 1.21 RGB networking.
 *
 * Same channel IDs and wire format as the Forge 1.20.1 / Fabric versions —
 * wire-level compatibility is preserved. The changes are purely in the
 * registration and send/receive API:
 *
 *   Forge 1.20.1:  SimpleChannel + NetworkEvent.Context
 *   NeoForge 1.21: CustomPacketPayload records + PayloadRegistrar + IPayloadContext
 *
 * Each of the 4 packet directions is now a Java record implementing
 * CustomPacketPayload. The actual bytes written (encodeColorMap, readUUID, etc.)
 * are identical to the other loaders via RgbState's shared codec helpers.
 *
 * PacketDistributor now uses static methods instead of the old enum approach:
 *   PacketDistributor.sendToServer(payload)      — client → server
 *   PacketDistributor.sendToPlayer(player, pkt)  — server → specific client
 */
@EventBusSubscriber(modid = XtraNimations.MODID)
public class RgbNetworkingNeoForge {

    // ── Packet definitions ────────────────────────────────────────────────────

    /** Client → Server: "I support RGB sync, please send me everyone's colors." */
    public record RgbHelloC2S() implements CustomPacketPayload {
        public static final Type<RgbHelloC2S> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(XtraNimations.MODID, "rgb_hello"));
        public static final StreamCodec<ByteBuf, RgbHelloC2S> STREAM_CODEC =
                StreamCodec.unit(new RgbHelloC2S());
        @Override public Type<RgbHelloC2S> type() { return TYPE; }
    }

    /** Server → Client: "Acknowledged, RGB sync active." */
    public record RgbHelloAckS2C() implements CustomPacketPayload {
        public static final Type<RgbHelloAckS2C> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(XtraNimations.MODID, "rgb_hello_ack"));
        public static final StreamCodec<ByteBuf, RgbHelloAckS2C> STREAM_CODEC =
                StreamCodec.unit(new RgbHelloAckS2C());
        @Override public Type<RgbHelloAckS2C> type() { return TYPE; }
    }

    /** Client → Server: "Here are my current RGB group colors." */
    public record RgbUpdateC2S(Map<String, Integer> groups) implements CustomPacketPayload {
        public static final Type<RgbUpdateC2S> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(XtraNimations.MODID, "rgb_update"));
        public static final StreamCodec<FriendlyByteBuf, RgbUpdateC2S> STREAM_CODEC =
                StreamCodec.of(
                        (buf, pkt) -> RgbState.encodeColorMap(buf, pkt.groups()),
                        buf -> new RgbUpdateC2S(RgbState.decodeColorMap(buf)));
        @Override public Type<RgbUpdateC2S> type() { return TYPE; }
    }

    /** Server → Client: "Here are another player's RGB group colors." */
    public record RgbPeerS2C(UUID uuid, Map<String, Integer> groups) implements CustomPacketPayload {
        public static final Type<RgbPeerS2C> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(XtraNimations.MODID, "rgb_peer"));
        public static final StreamCodec<FriendlyByteBuf, RgbPeerS2C> STREAM_CODEC =
                StreamCodec.of(
                        (buf, pkt) -> { buf.writeUUID(pkt.uuid()); RgbState.encodeColorMap(buf, pkt.groups()); },
                        buf -> new RgbPeerS2C(buf.readUUID(), RgbState.decodeColorMap(buf)));
        @Override public Type<RgbPeerS2C> type() { return TYPE; }
    }

    // ── Registration ──────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar(RgbState.VERSION_STR)
                .optional()
                .executesOn(HandlerThread.MAIN);

        reg.playToServer(RgbHelloC2S.TYPE,   RgbHelloC2S.STREAM_CODEC,   RgbNetworkingNeoForge::handleHelloC2S);
        reg.playToClient(RgbHelloAckS2C.TYPE, RgbHelloAckS2C.STREAM_CODEC, RgbNetworkingNeoForge::handleHelloAckS2C);
        reg.playToServer(RgbUpdateC2S.TYPE,  RgbUpdateC2S.STREAM_CODEC,  RgbNetworkingNeoForge::handleRgbUpdateC2S);
        reg.playToClient(RgbPeerS2C.TYPE,    RgbPeerS2C.STREAM_CODEC,    RgbNetworkingNeoForge::handleRgbPeerS2C);

        // Wire the sendHello hook for TriggerEventHandler.onJoinServer()
        RgbState.sendHelloHook = RgbNetworkingNeoForge::sendHello;

        XtraNimations.LOGGER.info("[XtraNimations] RGB payloads registered (NeoForge 1.21, v{}).", RgbState.VERSION_STR);
    }

    // ── Server-side handlers ──────────────────────────────────────────────────

    private static void handleHelloC2S(RgbHelloC2S pkt, IPayloadContext ctx) {
        ServerPlayer sender = (ServerPlayer) ctx.player();
        if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] HELLO from {}.", sender.getName().getString());
        PacketDistributor.sendToPlayer(sender, new RgbHelloAckS2C());
        pushAllTo(sender);
    }

    private static void handleRgbUpdateC2S(RgbUpdateC2S pkt, IPayloadContext ctx) {
        ServerPlayer sender = (ServerPlayer) ctx.player();
        UUID uuid = sender.getUUID();
        RgbState.recordUpdate(uuid, pkt.groups());
        if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB_UPDATE from {}: {} groups.",
                sender.getName().getString(), pkt.groups().size());
        broadcastPeerToNearby(sender, uuid, pkt.groups());
    }

    // ── Client-side handlers ──────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private static void handleHelloAckS2C(RgbHelloAckS2C pkt, IPayloadContext ctx) {
        RgbState.serverHasCapability = true;
        if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] HELLO_ACK — RGB sync active (NeoForge).");
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleRgbPeerS2C(RgbPeerS2C pkt, IPayloadContext ctx) {
        if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB_PEER for {}: {} groups.",
                pkt.uuid(), pkt.groups().keySet());
        RgbReflectionHelper.applyColorsToOtherPlayer(pkt.uuid(), pkt.groups());
    }

    // ── NeoForge game-bus events (server-side player join/leave) ─────────────

    @EventBusSubscriber(modid = XtraNimations.MODID)
    public static class ServerEvents {
        @SubscribeEvent
        public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer newPlayer)) return;
            pushAllTo(newPlayer);
        }

        @SubscribeEvent
        public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer p)) return;
            RgbState.forgetPlayer(p.getUUID());
            if (XtraConfig.DEBUG)
                XtraNimations.LOGGER.info("[XtraNimations] Cleared RGB state for {}", p.getName().getString());
        }
    }

    // ── Client API ────────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    public static void sendHello() {
        RgbState.serverHasCapability = false;

        if (Minecraft.getInstance().hasSingleplayerServer()) {
            RgbState.serverHasCapability = true;
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] Integrated server — RGB sync active.");
            return;
        }

        try {
            PacketDistributor.sendToServer(new RgbHelloC2S());
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] Sent HELLO.");
        } catch (Exception e) {
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[XtraNimations] HELLO failed: {}", e.getMessage());
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void sendRgbUpdate(Map<String, Integer> groupColors) {
        if (!RgbState.serverHasCapability || groupColors == null || groupColors.isEmpty()) return;
        try {
            PacketDistributor.sendToServer(new RgbUpdateC2S(groupColors));
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[XtraNimations] RGB_UPDATE sent: {} groups.", groupColors.size());
        } catch (Exception e) {
            XtraNimations.LOGGER.warn("[XtraNimations] RGB_UPDATE failed: {}", e.getMessage());
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static void pushAllTo(ServerPlayer target) {
        for (Map.Entry<UUID, Map<String, Integer>> e : RgbState.allEntries()) {
            try { PacketDistributor.sendToPlayer(target, new RgbPeerS2C(e.getKey(), e.getValue())); }
            catch (Exception ignored) {}
        }
    }

    private static void broadcastPeerToNearby(ServerPlayer sender, UUID uuid, Map<String, Integer> groups) {
        for (ServerPlayer p : RgbState.nearbyTargets(sender)) {
            try { PacketDistributor.sendToPlayer(p, new RgbPeerS2C(uuid, groups)); }
            catch (Exception ignored) {}
        }
    }
}
