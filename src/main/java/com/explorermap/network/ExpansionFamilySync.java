package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.ExpansionRecord;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ExpansionFamilySync {

    public static final int MAX_FAMILY_MAPS = 256;

    private static final Map<UUID, Set<MapIdentity>> SYNCED = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<MapIdentity>> TRUNCATION_REPORTED = new ConcurrentHashMap<>();

    private ExpansionFamilySync() {}

    public record Family(List<MapIdentity> maps, boolean truncated) {}

    public static Family familyOf(ExplorerMapSavedData savedData, MapIdentity root) {
        Set<MapIdentity> visited = new LinkedHashSet<>();
        Deque<MapIdentity> pending = new ArrayDeque<>();
        boolean truncated = false;
        visited.add(root);
        pending.addLast(root);

        while (!pending.isEmpty()) {
            MapIdentity current = pending.removeFirst();
            MapEntryData entry = savedData.get(current);
            if (entry == null) continue;

            for (ExpansionRecord record : entry.getExpansions()) {
                MapIdentity target = record.target();
                if (!target.dimension().equals(current.dimension()) || visited.contains(target)) continue;
                if (visited.size() >= MAX_FAMILY_MAPS) {
                    truncated = true;
                    continue;
                }
                visited.add(target);
                pending.addLast(target);
            }
        }
        return new Family(List.copyOf(visited), truncated);
    }

    public static void sendTo(MinecraftServer server, ServerPlayer player, MapIdentity root) {
        ExplorerMapSavedData savedData = ExplorerMapSavedData.get(server);
        Family family = familyOf(savedData, root);
        UUID playerId = player.getUUID();

        if (family.truncated()
                && TRUNCATION_REPORTED.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet()).add(root)) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] The expansion family of map {} has more than {} maps; only the first {} are synchronized to {}",
                    root.asKey(), MAX_FAMILY_MAPS, MAX_FAMILY_MAPS, player.getName().getString());
        }

        List<SyncExpansionsPayload.Entry> entries = new ArrayList<>();
        for (MapIdentity mapId : family.maps()) {
            MapEntryData entry = savedData.get(mapId);
            if (entry == null) continue;
            List<ExpansionRecord> expansions = entry.getExpansions();
            if (!expansions.isEmpty()) entries.add(new SyncExpansionsPayload.Entry(mapId, expansions));
        }
        if (!entries.isEmpty()) {
            ServerPlayNetworking.send(player, new SyncExpansionsPayload(root, entries));
        }

        Set<MapIdentity> synced = SYNCED.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet());
        synced.add(root);

        for (MapIdentity mapId : family.maps()) {
            if (synced.contains(mapId)) continue;
            MapEntryData entry = savedData.get(mapId);
            if (entry == null || MapIdentity.resolveAndVerify(server, mapId) == null) continue;
            synced.add(mapId);

            if (!savedData.visibleWaypoints(mapId, playerId).isEmpty()
                    || entry.getWaypointCapacity() > 0
                    || entry.getWaypointShareCharges() > 0) {
                SyncWaypointsPayload.sendTo(player, mapId);
            }
            if (hasAnyBit(savedData.getPlayerDiscovery(mapId, playerId))) {
                SyncDiscoveryPayload.sendTo(player, mapId);
            }
        }
    }

    public static void onExpansionChanged(MinecraftServer server, MapIdentity changed) {
        ExplorerMapSavedData savedData = ExplorerMapSavedData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MapIdentity held = heldRoot(player);
            if (held != null && savedData.isAccessibleFrom(held, changed)) {
                sendTo(server, player, held);
            }
        }
    }

    public static void invalidate(UUID playerId, MapIdentity mapId) {
        Set<MapIdentity> synced = SYNCED.get(playerId);
        if (synced != null) synced.remove(mapId);
    }

    public static void forget(UUID playerId) {
        SYNCED.remove(playerId);
        TRUNCATION_REPORTED.remove(playerId);
    }

    private static MapIdentity heldRoot(ServerPlayer player) {
        var offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return null;
        return MapIdentity.ofStack(offHand, player.level());
    }

    private static boolean hasAnyBit(byte[] mask) {
        if (mask == null) return false;
        for (byte b : mask) {
            if (b != 0) return true;
        }
        return false;
    }
}
