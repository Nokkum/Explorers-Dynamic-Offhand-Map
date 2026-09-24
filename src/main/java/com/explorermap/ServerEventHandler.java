package com.explorermap;

import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.network.SyncWaypointsPayload;
import com.explorermap.network.SyncDiscoveryPayload;
import com.explorermap.structure.StructureWaypointDetector;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ServerEventHandler {

    private static final Map<UUID, Integer> LAST_OFFHAND_MAP = new HashMap<>();
    private static int tickCounter;

    private ServerEventHandler() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();

            server.execute(() -> onPlayerJoin(player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                LAST_OFFHAND_MAP.remove(handler.getPlayer().getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % 10 != 0) return;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ItemStack offHand = player.getStackInHand(Hand.OFF_HAND);
                int mapId = ExplorerMapMod.isFilledMap(offHand)
                        ? MapIdentity.rawIdOf(offHand) : -1;
                Integer previous = LAST_OFFHAND_MAP.put(player.getUuid(), mapId);
                if (previous == null || previous != mapId) {
                    syncMapForPlayer(player, offHand);
                }
            }
        });
    }

    private static void onPlayerJoin(ServerPlayerEntity player) {
        syncMapForPlayer(player, player.getStackInHand(Hand.OFF_HAND));
        for (int i = 0; i < 9; i++) {
            syncMapForPlayer(player, player.getInventory().getStack(i));
        }
    }

    private static void syncMapForPlayer(ServerPlayerEntity player, ItemStack stack) {
        if (!ExplorerMapMod.isFilledMap(stack)) return;

        int mapId = MapIdentity.rawIdOf(stack);
        if (mapId < 0) return;

        var world    = player.getServerWorld();
        var mapState = MapIdentity.stateOf(stack, world);
        if (mapState == null) return;

        SyncWaypointsPayload.sendTo(player, mapId);
        SyncDiscoveryPayload.sendTo(player, mapId);

        var savedData = ExplorerMapSavedData.get(player.getServer());
        StructureWaypointDetector.checkAndPlace(player.getServer(), player, mapId, mapState, savedData);

        ExplorerMapMod.LOGGER.debug(
                "[ExplorerMap] Join-sync map #{} for {}", mapId, player.getName().getString());
    }
}
