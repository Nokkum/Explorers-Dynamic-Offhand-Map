package com.explorermap.mod;

import com.explorermap.mod.data.ExplorerMapSavedData;
import com.explorermap.mod.data.MapIdentity;
import com.explorermap.mod.network.SyncWaypointsPayload;
import com.explorermap.mod.structure.StructureWaypointDetector;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

public final class ServerEventHandler {

    private ServerEventHandler() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();

            server.execute(() -> onPlayerJoin(player));
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

        var savedData = ExplorerMapSavedData.get(player.getServer());
        StructureWaypointDetector.checkAndPlace(player.getServer(), player, mapId, mapState, savedData);

        ExplorerMapMod.LOGGER.debug(
                "[ExplorerMap] Join-sync map #{} for {}", mapId, player.getName().getString());
    }
}
