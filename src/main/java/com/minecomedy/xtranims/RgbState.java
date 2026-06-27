package com.minecomedy.xtranims;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared state and wire format for the {@code xtranims:rgb} channel.
 *
 * This holds everything about the RGB sync protocol that ISN'T tied to a
 * specific networking API: the channel id, the server-side color store, the
 * client-side capability flag, and the encode/decode routines (plain
 * FriendlyByteBuf reads/writes — identical under Forge and Fabric when both
 * build against official mappings).
 *
 * What's deliberately NOT here: actually registering a channel handler and
 * calling send(). Forge's SimpleChannel and Fabric's ServerPlayNetworking/
 * ClientPlayNetworking have different registration shapes, so each platform
 * module owns its own thin networking class (RgbNetworkingForge /
 * RgbNetworkingFabric) that registers handlers, deserializes the buffer
 * using the helpers below, then calls back into here for state + relay logic.
 */
public final class RgbState {

    private RgbState() {}

    
    public static final ResourceLocation CHANNEL_ID = ResourceLocation.fromNamespaceAndPath(XtraNimations.MODID, "rgb");
    public static final String VERSION_STR = "2";

    /** Set true on the client once the server replies with HELLO_ACK (or we ARE the integrated server). */
    public static volatile boolean serverHasCapability = false;

    /**
     * Set once by each platform's networking class during client init
     * (RgbNetworkingForge::sendHello / RgbNetworkingFabric::sendHello).
     * Lets common's TriggerEventHandler trigger the HELLO probe on join
     * without depending on a concrete platform networking class.
     */
    public static volatile Runnable sendHelloHook = null;

    /** Server-side: last known RGB colors per player UUID. */
    public static final ConcurrentHashMap<UUID, Map<String, Integer>> rgbStore = new ConcurrentHashMap<>();

    // ── Wire format (shared by HELLO/RGB_UPDATE/RGB_PEER payloads) ─────────────

    public static void encodeColorMap(FriendlyByteBuf buf, Map<String, Integer> groups) {
        buf.writeByte(Math.min(groups.size(), 255));
        int n = 0;
        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            if (n++ >= 255) break;
            buf.writeUtf(e.getKey(), 64);
            buf.writeInt(e.getValue());
        }
    }

    public static Map<String, Integer> decodeColorMap(FriendlyByteBuf buf) {
        int count = buf.readUnsignedByte();
        Map<String, Integer> g = new HashMap<>(count);
        for (int i = 0; i < count; i++) g.put(buf.readUtf(64), buf.readInt());
        return g;
    }

    // ── Server-side business logic (no networking calls — caller sends) ────────

    /** Snapshot of (uuid, groups) pairs to push to a player who just joined. */
    public static List<Map.Entry<UUID, Map<String, Integer>>> allEntries() {
        return new ArrayList<>(rgbStore.entrySet());
    }

    public static void recordUpdate(UUID uuid, Map<String, Integer> groups) {
        rgbStore.put(uuid, groups);
    }

    public static void forgetPlayer(UUID uuid) {
        rgbStore.remove(uuid);
    }

    /** Players (other than the sender) within relay range who should receive an RGB_PEER update. */
    public static List<ServerPlayer> nearbyTargets(ServerPlayer sender) {
        List<ServerPlayer> targets = new ArrayList<>();
        if (!(sender.level() instanceof ServerLevel sl)) return targets;
        for (ServerPlayer p : sl.players()) {
            if (p == sender) continue;
            if (sender.distanceTo(p) > 128) continue;
            targets.add(p);
        }
        return targets;
    }
}
