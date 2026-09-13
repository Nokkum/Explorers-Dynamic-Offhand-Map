package com.explorermap.mod;

import com.explorermap.mod.network.SyncWaypointsPayload;
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
 * JOIN  — When a player joins the server, sync waypoints for any filled map
 *         they are currently holding in their off-hand. This covers the case
 *         where a player logged out with a map in hand.
 *
 * Future: also hook into ServerPlayNetworkHandler.onSlotChanged / tick to
 * detect mid-session map swaps and push waypoint sync proactively.
 */
public final class ServerEventHandler {

    private ServerEventHandler() {}

    /** Call from ExplorerMapMod.onInitialize(). */
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            // Delay by 1 tick so the player's inventory is fully loaded
            server.execute(() -> syncOffHandMapWaypoints(player));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * If the player has a filled map in their off-hand, push its waypoint list.
     * Also scans the hotbar for filled maps (players may have several).
     */
    private static void syncOffHandMapWaypoints(ServerPlayerEntity player) {
        // Off-hand first
        trySync(player, player.getStackInHand(Hand.OFF_HAND));

        // Hotbar slots 0-8
        for (int i = 0; i < 9; i++) {
            trySync(player, player.getInventory().getStack(i));
        }
    }

    private static void trySync(ServerPlayerEntity player, ItemStack stack) {
        if (!ExplorerMapMod.isFilledMap(stack)) return;
        Integer mapId = FilledMapItem.getMapId(stack);
        if (mapId == null) return;
        SyncWaypointsPayload.sendTo(player, mapId);
        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Join-sync waypoints for map #{} → {}",
                mapId, player.getName().getString());
    }
}
