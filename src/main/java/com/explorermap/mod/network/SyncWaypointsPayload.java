package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.data.ExplorerMapSavedData;
import com.explorermap.mod.data.MapIdentity;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C: server pushes the full waypoint list for a specific map to a client.
 *
 * Sent:
 *   - To every player currently holding the map, after a save or delete
 *     (see broadcastTo() below) — an earlier revision only echoed the change
 *     back to the player who made it, so other players holding the same map
 *     would not see waypoint edits until their next relog.
 *   - To a single player when they join (ServerEventHandler.JOIN), or when
 *     they first equip a map with existing waypoints in a session.
 *
 * The client replaces its in-memory waypoint list entirely (last-write-wins),
 * so this is safe to send multiple times.
 */
public record SyncWaypointsPayload(
        int mapId,
        List<Waypoint> waypoints
) implements CustomPayload {

    /** Hard ceiling on waypoints per map accepted in a single sync packet. */
    private static final int MAX_WAYPOINTS = 512;

    public static final CustomPayload.Id<SyncWaypointsPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("sync_waypoints"));

    public static final PacketCodec<PacketByteBuf, SyncWaypointsPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT,
                    SyncWaypointsPayload::mapId,
                    PacketCodecs.collection(ArrayList::new, PacketCodecs.codec(Waypoint.CODEC)),
                    SyncWaypointsPayload::waypoints,
                    SyncWaypointsPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    // ── Server-side: send to one player ───────────────────────────────────

    /** Sends a single player the current waypoint list for the given map. */
    public static void sendTo(ServerPlayerEntity player, int mapId) {
        var world = player.getServerWorld();
        var entry = ExplorerMapSavedData.get(player.getServer()).get(world, mapId);
        List<Waypoint> waypoints = entry != null ? entry.getWaypoints() : List.of();
        ServerPlayNetworking.send(player, new SyncWaypointsPayload(mapId, waypoints));
    }

    /**
     * Sends the current waypoint list to every online player who is
     * currently holding this map ID in their off-hand.
     *
     * An earlier revision only echoed changes back to the player who made
     * them, so a second player holding the same map would never see waypoint
     * edits until their next relog — this fixes that by broadcasting.
     */
    public static void broadcastTo(MinecraftServer server, int mapId) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            var offHand = p.getStackInHand(Hand.OFF_HAND);
            if (!ExplorerMapMod.isFilledMap(offHand)) continue;
            if (MapIdentity.rawIdOf(offHand) == mapId) {
                sendTo(p, mapId);
            }
        }
    }

    // ── Client handler ────────────────────────────────────────────────────

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                context.client().execute(() -> handleOnClient(payload)));
    }

    @Environment(EnvType.CLIENT)
    private static void handleOnClient(SyncWaypointsPayload payload) {
        if (payload.waypoints().size() > MAX_WAYPOINTS) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] Ignoring oversized waypoint sync ({} entries)",
                    payload.waypoints().size());
            return;
        }

        var client = MinecraftClient.getInstance();
        if (client.world == null) return;

        var mapState = MapIdentity.stateOf(payload.mapId(), client.world);
        if (mapState == null) return;

        var mapEntry = ClientMapCache.getOrCreate(mapState, payload.mapId());
        mapEntry.replaceAllWaypoints(payload.waypoints());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Synced {} waypoints for map #{}",
                payload.waypoints().size(), payload.mapId());
    }
}
