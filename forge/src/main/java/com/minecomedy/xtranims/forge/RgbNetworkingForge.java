package com.minecomedy.xtranims.forge;

import com.minecomedy.xtranims.RgbReflectionHelper;
import com.minecomedy.xtranims.RgbState;
import com.minecomedy.xtranims.XtraConfig;
import com.minecomedy.xtranims.XtraNimations;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Map;
import java.util.UUID;

/**
 * Forge transport for the {@code xtranims:rgb} channel.
 * Protocol (channel id, packet ids, wire format) is unchanged from the
 * original mod — only the registration mechanics (SimpleChannel) live here now;
 * state and business logic moved to RgbState so Fabric can share them.
 */
@Mod.EventBusSubscriber(modid = XtraNimations.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RgbNetworkingForge {

    static SimpleChannel CHANNEL;

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                RgbState.CHANNEL_ID,
                () -> RgbState.VERSION_STR,
                v -> true,
                v -> true
        );

        CHANNEL.messageBuilder(HelloC2S.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> {})
                .decoder(b -> new HelloC2S())
                .consumerMainThread((m, ctxSupplier) -> HelloC2S.handle(ctxSupplier.get()))
                .add();

        CHANNEL.messageBuilder(HelloAck.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((m, b) -> {})
                .decoder(b -> new HelloAck())
                .consumerMainThread((m, ctxSupplier) -> HelloAck.handle(ctxSupplier.get()))
                .add();

        CHANNEL.messageBuilder(RgbUpdateC2S.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RgbUpdateC2S::encode)
                .decoder(RgbUpdateC2S::decode)
                .consumerMainThread((m, ctxSupplier) -> m.handle(ctxSupplier.get()))
                .add();

        CHANNEL.messageBuilder(RgbPeerS2C.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RgbPeerS2C::encode)
                .decoder(RgbPeerS2C::decode)
                .consumerMainThread((m, ctxSupplier) -> m.handle(ctxSupplier.get()))
                .add();

        RgbState.sendHelloHook = RgbNetworkingForge::sendHello;

        XtraNimations.LOGGER.info("[Xtranims] RgbNetworkingForge registered (xtranims:rgb v{}).", RgbState.VERSION_STR);
    }

    // ── Client API ────────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    public static void sendHello() {
        RgbState.serverHasCapability = false;

        if (net.minecraft.client.Minecraft.getInstance().hasSingleplayerServer()) {
            RgbState.serverHasCapability = true;
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] Integrated server detected — RGB sync active (LAN host).");
            return;
        }

        try {
            CHANNEL.send(PacketDistributor.SERVER.noArg(), new HelloC2S());
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] Sent HELLO (probing server capability).");
        } catch (Exception e) {
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.debug("[Xtranims] HELLO send failed (no server mod): {}", e.getMessage());
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void sendRgbUpdate(Map<String, Integer> groupColors) {
        if (!RgbState.serverHasCapability || groupColors == null || groupColors.isEmpty()) return;
        try {
            CHANNEL.send(PacketDistributor.SERVER.noArg(), new RgbUpdateC2S(groupColors));
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] RGB_UPDATE sent: {} groups.", groupColors.size());
        } catch (Exception e) {
            XtraNimations.LOGGER.warn("[Xtranims] RGB_UPDATE send failed: {}", e.getMessage());
        }
    }

    // ── Forge events (server side) ────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer newPlayer)) return;
        pushAllTo(newPlayer);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        RgbState.forgetPlayer(p.getUUID());
        if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] Cleared RGB state for {}", p.getName().getString());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static void pushAllTo(ServerPlayer target) {
        for (Map.Entry<UUID, Map<String, Integer>> e : RgbState.allEntries()) {
            sendPeerTo(target, e.getKey(), e.getValue());
        }
    }

    private static void sendPeerTo(ServerPlayer target, UUID uuid, Map<String, Integer> groups) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> target), new RgbPeerS2C(uuid, groups));
        } catch (Exception ignored) {}
    }

    private static void broadcastPeerToNearby(ServerPlayer sender, UUID uuid, Map<String, Integer> groups) {
        for (ServerPlayer p : RgbState.nearbyTargets(sender)) {
            sendPeerTo(p, uuid, groups);
        }
    }

    // ── Packets ───────────────────────────────────────────────────────────────

    static class HelloC2S {
        static void handle(NetworkEvent.Context ctx) {
            ctx.setPacketHandled(true);
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] HELLO from {}.", sender.getName().getString());
            try {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), new HelloAck());
            } catch (Exception ignored) {}
            pushAllTo(sender);
        }
    }

    static class HelloAck {
        @OnlyIn(Dist.CLIENT)
        static void handle(NetworkEvent.Context ctx) {
            ctx.setPacketHandled(true);
            RgbState.serverHasCapability = true;
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] HELLO_ACK - server-assisted RGB sync active.");
        }
    }

    static class RgbUpdateC2S {
        final Map<String, Integer> groups;
        RgbUpdateC2S(Map<String, Integer> groups) { this.groups = groups; }

        static void encode(RgbUpdateC2S m, FriendlyByteBuf b) { RgbState.encodeColorMap(b, m.groups); }
        static RgbUpdateC2S decode(FriendlyByteBuf b) { return new RgbUpdateC2S(RgbState.decodeColorMap(b)); }

        void handle(NetworkEvent.Context ctx) {
            ctx.setPacketHandled(true);
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            UUID uuid = sender.getUUID();
            RgbState.recordUpdate(uuid, groups);
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] RGB_UPDATE from {}: {} groups.",
                    sender.getName().getString(), groups.size());
            broadcastPeerToNearby(sender, uuid, groups);
        }
    }

    static class RgbPeerS2C {
        final UUID uuid;
        final Map<String, Integer> groups;
        RgbPeerS2C(UUID uuid, Map<String, Integer> groups) { this.uuid = uuid; this.groups = groups; }

        static void encode(RgbPeerS2C m, FriendlyByteBuf b) {
            b.writeUUID(m.uuid);
            RgbState.encodeColorMap(b, m.groups);
        }
        static RgbPeerS2C decode(FriendlyByteBuf b) {
            UUID uuid = b.readUUID();
            return new RgbPeerS2C(uuid, RgbState.decodeColorMap(b));
        }

        @OnlyIn(Dist.CLIENT)
        void handle(NetworkEvent.Context ctx) {
            ctx.setPacketHandled(true);
            if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] RGB_PEER for {}: {} groups.", uuid, groups.keySet());
            RgbReflectionHelper.applyColorsToOtherPlayer(uuid, groups);
        }
    }
}
