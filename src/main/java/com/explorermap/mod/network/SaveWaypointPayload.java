package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.waypoint.Waypoint;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.FilledMapItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * C2S: client saves a new (or updated) waypoint for a specific map.
 *
 * Flow:
 *   1. Player places / edits a waypoint in WaypointEditScreen.
 *   2. On save, WaypointEditScreen sends SaveWaypointPayload(mapId, waypoint).
 *   3. Server validates the map belongs to this player's session, then:
 *      a. Adds/replaces the waypoint in the server-side MapDiscoveryAttachment.
 *      b. Replies with SyncWaypointsPayload(mapId, fullList) so the client
 *         gets the authoritative state back.
 *
 * "Replace" semantics: if a waypoint with the same name already exists in the
 * attachment it is removed before the new one is added.
 *
 * The mapId comes from FilledMapItem.getMapId(offHand) on the client side,
 * so it is always the vanilla integer map ID.
 */
public record SaveWaypointPayload(int mapId, Waypoint waypoint) implements CustomPayload {

    public static final CustomPayload.Id<SaveWaypointPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("save_waypoint"));

    public static final PacketCodec<PacketByteBuf, SaveWaypointPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT,                      SaveWaypointPayload::mapId,
                    PacketCodecs.codec(Waypoint.CODEC),        SaveWaypointPayload::waypoint,
                    SaveWaypointPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    // ── Registration ──────────────────────────────────────────────────────

    /** Call from ExplorerMapMod.onInitialize(). */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                context.server().execute(() -> handleOnServer(context.player(), payload)));
    }

    // ── Server handler ────────────────────────────────────────────────────

    private static void handleOnServer(ServerPlayerEntity player, SaveWaypointPayload payload) {
        var world    = player.getServerWorld();
        var mapState = world.getMapState(FilledMapItem.getMapName(payload.mapId()));

        if (mapState == null) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: map #{} not found for {}",
                    payload.mapId(), player.getName().getString());
            return;
        }

        // Basic sanity: waypoint world coords should be within ~2 map-tile widths
        // of the map centre to prevent griefing across dimensions / wrong maps.
        int scale      = 1 << mapState.scale;
        int maxDist    = 256 * scale; // 2 tile radii
        if (Math.abs(payload.waypoint().worldX() - mapState.centerX) > maxDist
         || Math.abs(payload.waypoint().worldZ() - mapState.centerZ) > maxDist) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: coords out of range, rejected");
            return;
        }

        var attachment = ExplorerMapMod.getOrCreate(mapState);

        // Replace-by-name: remove old entry if editing
        attachment.removeWaypoint(payload.waypoint().name());
        attachment.addWaypoint(payload.waypoint());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Saved waypoint '{}' on map #{}",
                payload.waypoint().name(), payload.mapId());

        // Echo authoritative list back to client
        SyncWaypointsPayload.sendTo(player, payload.mapId());
    }
}
