package com.explorermap.engine;

import com.explorermap.ExplorerMapMod;
import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.dimension.DimensionMapTracker;
import com.explorermap.expansion.MultiTileCanvas;
import com.explorermap.expansion.TileGrid;
import com.explorermap.expansion.TileMapView;
import com.explorermap.network.ClientNet;
import com.explorermap.network.SyncDiscoveryPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.concurrent.ThreadLocalRandom;

@Environment(EnvType.CLIENT)
public class ExplorationEngine {

    private static final double MAX_RANGE = 256.0;

    private static int tickCounter = 0;

    public static void tick(Minecraft client) {
        ExplorerMapConfig cfg = ExplorerMapConfig.get();
        if (++tickCounter % Math.max(1, cfg.discoveryTickInterval) != 0) return;

        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;

        ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        int rawMapId = MapIdentity.rawIdOf(offHand);
        if (rawMapId < 0) return;

        MapItemSavedData mapState = MapIdentity.stateOf(offHand, client.level);
        if (mapState == null) return;

        if (!DimensionMapTracker.isMapRelevantForCurrentDimension(player, mapState)) return;

        MapIdentity mapId = MapIdentity.of(mapState, rawMapId);
        MapEntryData mapEntry = ClientMapCache.getOrCreate(mapId);
        TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, client.level);
        MultiTileCanvas canvas = MultiTileCanvas.from(grid, mapState);

        float yawDeg   = player.getYRot();
        float pitchDeg = player.getXRot();

        // The FOV option is the vertical field of view; the horizontal one follows from the window's aspect ratio.
        // (Temporary FOV changes from sprinting or zooming are not taken into account.)
        double vFovDeg = client.options.fov().get();
        double aspect  = (double) client.getWindow().getScreenWidth()
                / Math.max(1, client.getWindow().getScreenHeight());
        double hFovDeg = Math.min(170.0,
                Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(vFovDeg) * 0.5) * aspect)));

        if (cfg.fogOfDiscovery) {
            castRays(player, mapState, grid, canvas, yawDeg, pitchDeg, (float) hFovDeg, (float) vFovDeg, cfg.rayCount);
        } else {
            markVisitedArea(player, grid);
        }

        if (SyncDiscoveryPayload.uploadWindowOpen()) {
            uploadDirtyTiles(grid);
        }
    }

    private static void uploadDirtyTiles(TileGrid grid) {
        int sent = 0;
        for (TileGrid.TileEntry tile : grid.tiles()) {
            if (sent >= SyncDiscoveryPayload.UPLOADS_PER_WINDOW) break;
            MapEntryData entry = tile.entry();
            long gen = entry.getDiscoveryGeneration();
            if (!SyncDiscoveryPayload.needsUpload(tile.mapId(), gen)) continue;
            ClientNet.send(new SyncDiscoveryPayload.Upload(
                    tile.mapId(), gen, entry.getDiscoveredPixelsCopy()));
            SyncDiscoveryPayload.markUploaded(tile.mapId(), gen);
            sent++;
        }
    }

    /**
     * Fog off: everything around the player counts as discovered. Only pixels within ray range are marked,
     * because the server ignores uploaded pixels the player couldn't have seen from where they stand.
     */
    private static void markVisitedArea(LocalPlayer player, TileGrid grid) {
        double px = player.getX(), pz = player.getZ();
        for (TileGrid.TileEntry tile : grid.tiles()) {
            TileMapView view = tile.view();
            int scale = view.blocksPerPixel();
            int originX = view.centerX() - 64 * scale;
            int originZ = view.centerZ() - 64 * scale;
            int minCol = Math.max(0, Math.floorDiv((int) Math.floor(px - MAX_RANGE) - originX, scale));
            int maxCol = Math.min(127, Math.floorDiv((int) Math.floor(px + MAX_RANGE) - originX, scale));
            int minRow = Math.max(0, Math.floorDiv((int) Math.floor(pz - MAX_RANGE) - originZ, scale));
            int maxRow = Math.min(127, Math.floorDiv((int) Math.floor(pz + MAX_RANGE) - originZ, scale));

            MapEntryData entry = tile.entry();
            for (int row = minRow; row <= maxRow; row++) {
                for (int col = minCol; col <= maxCol; col++) {
                    if (!entry.isDiscovered(col, row)) entry.discover(col, row);
                }
            }
        }
    }

    private static void castRays(LocalPlayer player,
                                  MapItemSavedData mapState,
                                  TileGrid grid,
                                  MultiTileCanvas canvas,
                                  float yawDeg, float pitchDeg, float hFovDeg, float vFovDeg,
                                  int hRays) {

        Vec3 origin = player.getEyePosition(1.0f);
        int rays = Math.max(1, hRays);
        float cellH = hFovDeg / rays;
        float cellV = vFovDeg / 3f;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // One ray per column per tick: the row cycles and every ray is jittered inside its cell, so the
        // coverage fills in over a few ticks instead of leaving the same gaps at long range.
        for (int i = 0; i < rays; i++) {
            float yaw = yawDeg - hFovDeg * 0.5f + (i + 0.5f) * cellH + (random.nextFloat() - 0.5f) * cellH;
            int row = Math.floorMod(tickCounter + i, 3) - 1;
            float pitch = pitchDeg + row * cellV + (random.nextFloat() - 0.5f) * cellV;
            pitch = Mth.clamp(pitch, -90f, 90f);

            Vec3 dir = directionFromAngles(yaw, pitch);
            markRayAcrossTiles(player, origin, dir, mapState, grid, canvas);
        }
    }

    private static void markRayAcrossTiles(LocalPlayer player,
                                            Vec3 origin, Vec3 dir,
                                            MapItemSavedData mapState,
                                            TileGrid grid,
                                            MultiTileCanvas canvas) {
        Vec3 rayEnd = origin.add(dir.scale(MAX_RANGE));
        BlockHitResult hit = player.level().clip(new ClipContext(
                origin,
                rayEnd,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                player
        ));

        if (hit.getType() != HitResult.Type.BLOCK) return;

        // The hit point sits on a block face, i.e. on the border between two blocks. The block's own
        // center is unambiguous, so the pixel never lands next to the one that was actually seen.
        BlockPos hitBlock = hit.getBlockPos();
        double hitX = hitBlock.getX() + 0.5;
        double hitZ = hitBlock.getZ() + 0.5;
        TileGrid.TileEntry owner = grid.findTileContaining(canvas, hitX, hitZ);
        if (owner == null) return;

        int canvasX = canvas.worldToCanvasX(hitX);
        int canvasZ = canvas.worldToCanvasZ(hitZ);
        int localCol = canvasX - canvas.tileCanvasX(owner.gridX());
        int localRow = canvasZ - canvas.tileCanvasZ(owner.gridZ());
        owner.entry().discover(localCol, localRow);
    }

    private static Vec3 directionFromAngles(float yawDeg, float pitchDeg) {
        float yaw   = yawDeg   * Mth.DEG_TO_RAD;
        float pitch = pitchDeg * Mth.DEG_TO_RAD;
        float cosP  = Mth.cos(-pitch);
        return new Vec3(
                -Mth.sin(yaw) * cosP,
                 Mth.sin(-pitch),
                  Mth.cos(yaw) * cosP
        );
    }
}
