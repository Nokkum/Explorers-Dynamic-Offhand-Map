package com.explorermap.banner;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.lock.MapLocks;
import com.explorermap.network.ExpansionFamilySync;
import com.explorermap.network.SyncWaypointsPayload;
import com.explorermap.waypoint.Waypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** The server side of banner names on maps: adding and removing them, and keeping them true to the world. */
public final class BannerMapActions {

    private static final int REFRESH_INTERVAL_TICKS = 40;
    private static int refreshCounter;

    private BannerMapActions() {}

    /**
     * A filled map used on a banner, as in vanilla: the banner's name is added to the map, and used on the same
     * banner again it is removed. A banner without a name has nothing to show, so it is not added.
     */
    public static void toggle(ServerPlayer player, ItemStack map, BlockPos bannerPos) {
        Level level = player.level();
        MinecraftServer server = level.getServer();
        if (server == null) return;

        MapItemSavedData state = MapIdentity.stateOf(map, level);
        int rawId = MapIdentity.rawIdOf(map);
        if (state == null || rawId < 0) return;

        // A locked map is a snapshot, as it is for exploring and expanding.
        if (MapLocks.isLocked(state)) {
            tell(player, "explorermap.banner.locked");
            return;
        }

        MapIdentity root = MapIdentity.of(state, rawId);
        ExplorerMapSavedData savedData = ExplorerMapSavedData.get(server);

        // The label lives on the tile of the map that covers the banner, which may be an expansion of the held map.
        MapIdentity tile = tileCovering(server, savedData, root, level, bannerPos);
        if (tile == null) {
            tell(player, "explorermap.banner.outside");
            return;
        }

        UUID labelId = BannerLabels.idFor(bannerPos);
        MapEntryData entry = savedData.get(tile);
        if (entry != null && entry.findWaypoint(labelId) != null) {
            savedData.removeWaypoint(tile, labelId);
            SyncWaypointsPayload.broadcastTo(server, tile);
            return;
        }

        String name = BannerLabels.nameAt(level, bannerPos);
        if (name == null) {
            tell(player, "explorermap.banner.unnamed");
            return;
        }
        if (labelCount(savedData, root) >= BannerLabels.MAX_PER_MAP) {
            tell(player, "explorermap.banner.limit");
            return;
        }

        savedData.addWaypoint(tile, new Waypoint(labelId, name,
                bannerPos.getX() + 0.5, bannerPos.getZ() + 0.5,
                BannerLabels.iconFor(level, bannerPos), BannerLabels.TEXT_COLOR, ""));
        SyncWaypointsPayload.broadcastTo(server, tile);
    }

    /**
     * Every couple of seconds, for the maps players are holding: a label whose banner is gone (or has lost its
     * name) is removed, and one whose banner was renamed or got a Lodestone put under it (or taken away) is
     * updated. Banners in chunks that are not loaded are left alone, and locked maps stay as they were.
     */
    public static void tick(MinecraftServer server) {
        if (++refreshCounter < REFRESH_INTERVAL_TICKS) return;
        refreshCounter = 0;

        Set<MapIdentity> heldRoots = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
            if (!ExplorerMapMod.isFilledMap(offHand)) continue;
            MapIdentity root = MapIdentity.ofStack(offHand, player.level());
            if (root != null) heldRoots.add(root);
        }
        if (heldRoots.isEmpty()) return;

        ExplorerMapSavedData savedData = ExplorerMapSavedData.get(server);
        for (MapIdentity root : heldRoots) refresh(server, savedData, root);
    }

    private static void refresh(MinecraftServer server, ExplorerMapSavedData savedData, MapIdentity root) {
        for (MapIdentity tile : ExpansionFamilySync.familyOf(savedData, root).maps()) {
            MapEntryData entry = savedData.get(tile);
            if (entry == null) continue;
            MapItemSavedData tileState = MapIdentity.resolveAndVerify(server, tile);
            if (tileState == null || MapLocks.isLocked(tileState)) continue;
            ServerLevel level = server.getLevel(tile.dimension());
            if (level == null) continue;

            boolean changed = false;
            for (Waypoint label : entry.getWaypoints()) {
                if (!BannerLabels.isBannerLabel(label)) continue;
                BlockPos bannerPos = BannerLabels.bannerPosOf(label);
                if (!level.hasChunkAt(bannerPos)) continue;

                String name = BannerLabels.nameAt(level, bannerPos);
                if (name == null) {
                    savedData.removeWaypoint(tile, label.id());
                    changed = true;
                    continue;
                }
                String icon = BannerLabels.iconFor(level, bannerPos);
                if (!name.equals(label.name()) || !icon.equals(label.iconId())) {
                    savedData.addWaypoint(tile, label.withDisplay(name, icon, label.color()));
                    changed = true;
                }
            }
            if (changed) SyncWaypointsPayload.broadcastTo(server, tile);
        }
    }

    private static MapIdentity tileCovering(MinecraftServer server, ExplorerMapSavedData savedData,
                                            MapIdentity root, Level level, BlockPos pos) {
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        for (MapIdentity candidate : ExpansionFamilySync.familyOf(savedData, root).maps()) {
            MapItemSavedData tileState = MapIdentity.resolveAndVerify(server, candidate);
            if (tileState == null || !tileState.dimension.equals(level.dimension())) continue;
            // A tile spans 128 pixels, so its edge is 64 * scale blocks from the center (as for waypoints).
            int maxDist = 64 * (1 << tileState.scale);
            if (Math.abs(x - tileState.centerX) <= maxDist && Math.abs(z - tileState.centerZ) <= maxDist) {
                return candidate;
            }
        }
        return null;
    }

    private static int labelCount(ExplorerMapSavedData savedData, MapIdentity root) {
        int count = 0;
        for (MapIdentity tile : ExpansionFamilySync.familyOf(savedData, root).maps()) {
            MapEntryData entry = savedData.get(tile);
            if (entry == null) continue;
            for (Waypoint waypoint : entry.getWaypoints()) {
                if (BannerLabels.isBannerLabel(waypoint)) count++;
            }
        }
        return count;
    }

    private static void tell(ServerPlayer player, String translationKey) {
        player.sendOverlayMessage(Component.translatable(translationKey));
    }
}
