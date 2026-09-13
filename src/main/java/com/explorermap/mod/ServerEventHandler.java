package com.explorermap.mod;

import com.explorermap.mod.network.SyncWaypointsPayload;
import com.explorermap.mod.structure.StructureWaypointDetector;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

/**
 * Registers server-side Fabric events for the Explorer Map mod.
 *
 * Events handled
 * ──────────────
 * JOIN  — Syncs waypoints for all filled maps in the player's off-hand/hotbar.
 *         Also runs structure detection against already-discovered pixels so
 *         structures explored in a previous session get waypoints retroactively
 *         (e.g. after updating the mod, or receiving a map from another player).
 */
public final class ServerEventHandler {

    private ServerEventHandler() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            // Delay 1 tick so inventory is fully loaded
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
        Integer mapId = FilledMapItem.getMapId(stack);
        if (mapId == null) return;

        var world    = player.getServerWorld();
        var mapState = world.getMapState(FilledMapItem.getMapName(mapId));
        if (mapState == null) return;

        var attachment = ExplorerMapMod.getOrCreate(mapState);

        SyncWaypointsPayload.sendTo(player, mapId);

        // Retroactive structure detection against previously-discovered pixels.
        StructureWaypointDetector.checkAndPlace(
                player.getServer(), player, mapId, mapState, attachment);

        ExplorerMapMod.LOGGER.debug(
                "[ExplorerMap] Join-sync map #{} for {}", mapId, player.getName().getString());
    }
}
