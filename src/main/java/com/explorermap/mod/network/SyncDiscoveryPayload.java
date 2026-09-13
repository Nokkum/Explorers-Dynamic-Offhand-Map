package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.structure.StructureWaypointDetector;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.FilledMapItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;

/**
 * Multiplayer discovery sync — merges fog-of-discovery bitmasks across
 * all players holding the same vanilla map.
 *
 * Protocol
 * ────────
 * C2S  UploadDiscoveryPayload  – client sends its current bitmask for a map.
 *                                Sent at most once per UPLOAD_INTERVAL_TICKS.
 * S2C  BroadcastDiscoveryPayload – server ORs all received bitmasks together
 *                                  and pushes the merged result to every online
 *                                  player who holds that map ID.
 *
 * Both directions carry the bitmask encoded as 256 longs (same as the NBT codec).
 * OR-merging means discoveries are never lost: once a pixel is seen by any holder
 * of the map, all holders will eventually see it.
 *
 * The server is authoritative: it stores the merged bitmask in the MapState
 * attachment and re-broadcasts after every merge.
 *
 * Throttling
 * ──────────
 * The client uploads its bitmask every UPLOAD_INTERVAL_TICKS ticks *only if*
 * its local generation counter has advanced since the last upload. This is
 * managed by ExplorationEngine via SyncDiscoveryPayload.shouldUpload().
 */
public final class SyncDiscoveryPayload {

    // ── C2S: client → server ──────────────────────────────────────────────

    public record Upload(int mapId, List<Long> bitmaskLongs) implements CustomPayload {

        public static final CustomPayload.Id<Upload> ID =
                new CustomPayload.Id<>(ExplorerMapRegistry.id("upload_discovery"));

        public static final PacketCodec<PacketByteBuf, Upload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.VAR_INT,            Upload::mapId,
                        PacketCodecs.collection(java.util.ArrayList::new, PacketCodecs.LONG), Upload::bitmaskLongs,
                        Upload::new
                );

        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

        public static void register() {
            PayloadTypeRegistry.playC2S().register(ID, CODEC);
            ServerPlayNetworking.registerGlobalReceiver(ID, (payload, ctx) ->
                    ctx.server().execute(() -> handleOnServer(ctx.server(), ctx.player(), payload)));
        }

        private static void handleOnServer(MinecraftServer server,
                                            ServerPlayerEntity sender,
                                            Upload payload) {
            var world    = sender.getServerWorld();
            var mapState = world.getMapState(FilledMapItem.getMapName(payload.mapId()));
            if (mapState == null) return;

            var attachment = ExplorerMapMod.getOrCreate(mapState);

            // OR the incoming bitmask into the server-side attachment
            boolean changed = attachment.mergeDiscoveryLongs(payload.bitmaskLongs());
            if (!changed) return; // no new pixels — skip broadcast

            ExplorerMapMod.LOGGER.debug("[ExplorerMap] Merged discovery for map #{} from {}",
                    payload.mapId(), sender.getName().getString());

            // Check for newly-revealed structures and auto-place waypoints
            StructureWaypointDetector.checkAndPlace(
                    server, sender, payload.mapId(), mapState, attachment);

            // Broadcast updated bitmask to all online players holding this map
            List<Long> merged = attachment.getDiscoveryLongs();
            var broadcast = new Broadcast(payload.mapId(), merged);
            for (var player : server.getPlayerManager().getPlayerList()) {
                var offHand = player.getStackInHand(Hand.OFF_HAND);
                if (ExplorerMapMod.isFilledMap(offHand)) {
                    Integer heldId = FilledMapItem.getMapId(offHand);
                    if (heldId != null && heldId == payload.mapId()) {
                        ServerPlayNetworking.send(player, broadcast);
                    }
                }
            }
        }
    }

    // ── S2C: server → client ──────────────────────────────────────────────

    public record Broadcast(int mapId, List<Long> bitmaskLongs) implements CustomPayload {

        public static final CustomPayload.Id<Broadcast> ID =
                new CustomPayload.Id<>(ExplorerMapRegistry.id("broadcast_discovery"));

        public static final PacketCodec<PacketByteBuf, Broadcast> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.VAR_INT,            Broadcast::mapId,
                        PacketCodecs.collection(java.util.ArrayList::new, PacketCodecs.LONG), Broadcast::bitmaskLongs,
                        Broadcast::new
                );

        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

        public static void registerCommon() {
            PayloadTypeRegistry.playS2C().register(ID, CODEC);
        }

        @Environment(EnvType.CLIENT)
        public static void registerClient() {
            ClientPlayNetworking.registerGlobalReceiver(ID, (payload, ctx) ->
                    ctx.client().execute(() -> handleOnClient(payload)));
        }

        @Environment(EnvType.CLIENT)
        private static void handleOnClient(Broadcast payload) {
            var client = MinecraftClient.getInstance();
            if (client.world == null) return;

            var mapState = client.world.getMapState(FilledMapItem.getMapName(payload.mapId()));
            if (mapState == null) return;

            var attachment = ExplorerMapMod.getOrCreate(mapState);
            attachment.mergeDiscoveryLongs(payload.bitmaskLongs());
        }
    }

    // ── Upload throttle helpers ───────────────────────────────────────────

    /**
     * Called on disconnect to reset the upload throttle state.
     * Without this, lastUploadedGeneration from the previous session
     * could match the new session's initial generation and suppress
     * the first upload after reconnecting.
     */
    @Environment(EnvType.CLIENT)
    public static void resetUploadState() {
        ticksSinceLastUpload   = 0;
        lastUploadedGeneration = -1L;
    }

    /** Ticks between client uploads. One upload every ~3 seconds at 20 TPS. */
    public static final int UPLOAD_INTERVAL_TICKS = 60;

    private static int ticksSinceLastUpload = 0;
    private static long lastUploadedGeneration = -1L;

    /**
     * Called each client tick. Returns true if it's time to upload AND the
     * local bitmask has new pixels since the last upload.
     */
    @Environment(EnvType.CLIENT)
    public static boolean shouldUpload(long currentGeneration) {
        ticksSinceLastUpload++;
        if (ticksSinceLastUpload < UPLOAD_INTERVAL_TICKS) return false;
        if (currentGeneration == lastUploadedGeneration) {
            ticksSinceLastUpload = 0; // reset timer even if no change
            return false;
        }
        ticksSinceLastUpload       = 0;
        lastUploadedGeneration     = currentGeneration;
        return true;
    }
}
