package com.minecomedy.xtranims;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom {@code xtranims:rgb} network channel for multiplayer RGB color sync.
 */
@Mod.EventBusSubscriber(modid = XtraNimations.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RgbServerHandler {

    // ── Channel ───────────────────────────────────────────────────────────────

    // ResourceLocation(String,String) is @Deprecated(forRemoval=true) in 1.20.1 but
    // the replacement fromNamespaceAndPath() was only added in 1.20.4+.
    // "removal" suppresses [removal] warnings; "deprecation" alone does not.
    @SuppressWarnings("removal")
    static final ResourceLocation CHANNEL_ID = new ResourceLocation(XtraNimations.MODID, "rgb");
    private static final String   VERSION_STR = "2";
    static SimpleChannel           CHANNEL;

    // ── Client-side flag ──────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    public static volatile boolean serverHasCapability = false;

    // ── Server-side state ─────────────────────────────────────────────────────

    static final ConcurrentHashMap<UUID, Map<String, Integer>> rgbStore = new ConcurrentHashMap<>();

    // ── Registration ──────────────────────────────────────────────────────────

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                CHANNEL_ID,
                () -> VERSION_STR,
                v -> true,
                v -> true
        );

        // 0: C->S  HELLO
        CHANNEL.messageBuilder(HelloC2S.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> {})
                .decoder(b -> new HelloC2S())
                .consumerMainThread((m, ctxSupplier) -> HelloC2S.handle(m, ctxSupplier.get()))
                .add();

        // 1: S->C  HELLO_ACK
        CHANNEL.messageBuilder(HelloAck.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((m, b) -> {})
                .decoder(b -> new HelloAck())
                .consumerMainThread((m, ctxSupplier) -> HelloAck.handle(m, ctxSupplier.get()))
                .add();

        // 2: C->S  RGB_UPDATE
        CHANNEL.messageBuilder(RgbUpdateC2S.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RgbUpdateC2S::encode)
                .decoder(RgbUpdateC2S::decode)
                .consumerMainThread((m, ctxSupplier) -> RgbUpdateC2S.handle(m, ctxSupplier.get()))
                .add();

        // 3: S->C  RGB_PEER
        CHANNEL.messageBuilder(RgbPeerS2C.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RgbPeerS2C::encode)
                .decoder(RgbPeerS2C::decode)
                .consumerMainThread((m, ctxSupplier) -> RgbPeerS2C.handle(m, ctxSupplier.get()))
                .add();

        XtraNimations.LOGGER.info("[Xtranims] RgbServerHandler registered (xtranims:rgb v{}).", VERSION_STR);
    }

    // ── Client API ────────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    public static void sendHello() {
        serverHasCapability = false;

        // When hosting a LAN world or playing singleplayer with LAN open,
        // we ARE the integrated server — no packet round-trip needed.
        if (net.minecraft.client.Minecraft.getInstance().hasSingleplayerServer()) {
            serverHasCapability = true;
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
        if (!serverHasCapability || groupColors == null || groupColors.isEmpty()) return;
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
        if (rgbStore.remove(p.getUUID()) != null)
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] Cleared RGB state for {}", p.getName().getString());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static void pushAllTo(ServerPlayer target) {
        rgbStore.forEach((uuid, groups) -> sendPeerTo(target, uuid, groups));
    }

    private static void sendPeerTo(ServerPlayer target, UUID uuid, Map<String, Integer> groups) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> target), new RgbPeerS2C(uuid, groups));
        } catch (Exception ignored) {}
    }

    private static void broadcastPeerToNearby(ServerPlayer sender, UUID uuid,
                                               Map<String, Integer> groups) {
        if (!(sender.level() instanceof ServerLevel sl)) return;
        for (ServerPlayer p : sl.players()) {
            if (p == sender) continue;
            if (sender.distanceTo(p) > 128) continue;
            sendPeerTo(p, uuid, groups);
        }
    }

    // ── Packet: HELLO (C->S) ─────────────────────────────────────────────────

    static class HelloC2S {
        static void handle(HelloC2S m, NetworkEvent.Context ctx) {
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

    // ── Packet: HELLO_ACK (S->C) ─────────────────────────────────────────────

    static class HelloAck {
        @OnlyIn(Dist.CLIENT)
        static void handle(HelloAck m, NetworkEvent.Context ctx) {
            ctx.setPacketHandled(true);
            serverHasCapability = true;
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] HELLO_ACK - server-assisted RGB sync active.");
        }
    }

    // ── Packet: RGB_UPDATE (C->S) ────────────────────────────────────────────

    static class RgbUpdateC2S {
        final Map<String, Integer> groups;
        RgbUpdateC2S(Map<String, Integer> groups) { this.groups = groups; }

        static void encode(RgbUpdateC2S m, FriendlyByteBuf b) {
            b.writeByte(Math.min(m.groups.size(), 255));
            int n = 0;
            for (Map.Entry<String, Integer> e : m.groups.entrySet()) {
                if (n++ >= 255) break;
                b.writeUtf(e.getKey(), 64);
                b.writeInt(e.getValue());
            }
        }

        static RgbUpdateC2S decode(FriendlyByteBuf b) {
            int count = b.readUnsignedByte();
            Map<String, Integer> g = new HashMap<>(count);
            for (int i = 0; i < count; i++) g.put(b.readUtf(64), b.readInt());
            return new RgbUpdateC2S(g);
        }

        static void handle(RgbUpdateC2S m, NetworkEvent.Context ctx) {
            ctx.setPacketHandled(true);
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            UUID uuid = sender.getUUID();
            rgbStore.put(uuid, m.groups);
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] RGB_UPDATE from {}: {} groups.",
                    sender.getName().getString(), m.groups.size());
            broadcastPeerToNearby(sender, uuid, m.groups);
        }
    }

    // ── Packet: RGB_PEER (S->C) ──────────────────────────────────────────────

    static class RgbPeerS2C {
        final UUID uuid;
        final Map<String, Integer> groups;
        RgbPeerS2C(UUID uuid, Map<String, Integer> groups) { this.uuid = uuid; this.groups = groups; }

        static void encode(RgbPeerS2C m, FriendlyByteBuf b) {
            b.writeUUID(m.uuid);
            b.writeByte(Math.min(m.groups.size(), 255));
            int n = 0;
            for (Map.Entry<String, Integer> e : m.groups.entrySet()) {
                if (n++ >= 255) break;
                b.writeUtf(e.getKey(), 64);
                b.writeInt(e.getValue());
            }
        }

        static RgbPeerS2C decode(FriendlyByteBuf b) {
            UUID uuid = b.readUUID();
            int count = b.readUnsignedByte();
            Map<String, Integer> g = new HashMap<>(count);
            for (int i = 0; i < count; i++) g.put(b.readUtf(64), b.readInt());
            return new RgbPeerS2C(uuid, g);
        }

        @OnlyIn(Dist.CLIENT)
        static void handle(RgbPeerS2C m, NetworkEvent.Context ctx) {
            ctx.setPacketHandled(true);
if (XtraConfig.DEBUG) XtraNimations.LOGGER.info("[Xtranims] RGB_PEER for {}: {} groups.", m.uuid, m.groups.keySet());
            RgbReflectionHelper.applyColorsToOtherPlayer(m.uuid, m.groups);
        }
    }
}
