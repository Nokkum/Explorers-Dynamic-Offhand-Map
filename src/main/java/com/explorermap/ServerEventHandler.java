package com.explorermap;

import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.network.SyncWaypointsPayload;
import com.explorermap.network.SyncDiscoveryPayload;
import com.explorermap.structure.StructureWaypointDetector;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ServerEventHandler {

    private static final int OFFHAND_CHECK_INTERVAL_TICKS = 10;
    private static final int PENDING_STRUCTURE_RETRY_INTERVAL_TICKS = 400;

    private static final Map<UUID, MapIdentity> LAST_OFFHAND_MAP = new HashMap<>();
    private static int offHandCheckCounter;
    private static int pendingRetryCounter;

    private ServerEventHandler() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();

            server.execute(() -> onPlayerJoin(player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.getPlayer().getUUID();
            LAST_OFFHAND_MAP.remove(playerId);
            SyncDiscoveryPayload.forgetPlayer(playerId);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++offHandCheckCounter >= OFFHAND_CHECK_INTERVAL_TICKS) {
                offHandCheckCounter = 0;
                checkOffHandChanges(server);
            }
            if (++pendingRetryCounter >= PENDING_STRUCTURE_RETRY_INTERVAL_TICKS) {
                pendingRetryCounter = 0;
                retryPendingStructureChunks(server);
            }
        });
    }

    private static void checkOffHandChanges(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
            MapIdentity mapId = ExplorerMapMod.isFilledMap(offHand)
                    ? MapIdentity.ofStack(offHand, player.level()) : null;
            MapIdentity previous = LAST_OFFHAND_MAP.put(player.getUUID(), mapId);
            if (!Objects.equals(previous, mapId)) {
                syncMapForPlayer(player, offHand);
            }
        }
    }

    private static void retryPendingStructureChunks(MinecraftServer server) {
        var savedData = ExplorerMapSavedData.get(server);
        for (MapIdentity mapId : savedData.mapsWithPendingStructureChunks()) {
            var world = server.getLevel(mapId.dimension());
            if (world == null) continue;
            var mapState = MapIdentity.stateOf(mapId.mapId(), world);
            if (mapState == null) continue;
            StructureWaypointDetector.retryPending(server, mapId, mapState, savedData);
        }
    }

    private static void onPlayerJoin(ServerPlayer player) {
        syncMapForPlayer(player, player.getItemInHand(InteractionHand.OFF_HAND));
        syncMapForPlayer(player, player.getMainHandItem());
    }

    private static void syncMapForPlayer(ServerPlayer player, ItemStack stack) {
        if (!ExplorerMapMod.isFilledMap(stack)) return;

        var world  = player.level();
        MapIdentity mapId = MapIdentity.ofStack(stack, world);
        if (mapId == null) return;

        var mapState = MapIdentity.stateOf(mapId.mapId(), world);
        if (mapState == null) return;

        SyncWaypointsPayload.sendTo(player, mapId);
        SyncDiscoveryPayload.sendTo(player, mapId);

        var savedData = ExplorerMapSavedData.get(player.level().getServer());
        StructureWaypointDetector.checkAndPlace(player.level().getServer(), player, mapId, mapState, savedData, null);

        ExplorerMapMod.LOGGER.debug(
                "[ExplorerMap] Join-sync map {} for {}", mapId.asKey(), player.getName().getString());
    }
}
