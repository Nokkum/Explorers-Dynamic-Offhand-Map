package com.explorermap.engine;

import com.explorermap.ExplorerMapMod;
import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.dimension.DimensionMapTracker;
import com.explorermap.expansion.MultiTileCanvas;
import com.explorermap.expansion.TileGrid;
import com.explorermap.network.SyncDiscoveryPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

@Environment(EnvType.CLIENT)
public class ExplorationEngine {

    private static final double MAX_RANGE = 256.0;
    private static final int MAX_UPLOADS_PER_WINDOW = 8;

    private static int tickCounter = 0;

    public static void tick(Minecraft client) {
        ExplorerMapConfig cfg = ExplorerMapConfig.get();
        if (++tickCounter % cfg.discoveryTickInterval != 0) return;

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
        float fovDeg   = (float) client.options.fov().get();

        if (cfg.fogOfDiscovery) {
            castRays(player, mapState, grid, canvas, yawDeg, pitchDeg, fovDeg, cfg.rayCount);
        } else if (mapEntry.discoveryFraction() < 1f) {
            markAllPixels(mapEntry);
        }

        if (SyncDiscoveryPayload.uploadWindowOpen()) {
            uploadDirtyTiles(grid);
        }
    }

    private static void uploadDirtyTiles(TileGrid grid) {
        int sent = 0;
        for (TileGrid.TileEntry tile : grid.tiles()) {
            if (sent >= MAX_UPLOADS_PER_WINDOW) break;
            MapEntryData entry = tile.entry();
            long gen = entry.getDiscoveryGeneration();
            if (!SyncDiscoveryPayload.isDirty(tile.mapId(), gen)) continue;
            ClientPlayNetworking.send(new SyncDiscoveryPayload.Upload(tile.mapId(), entry.getDiscoveredPixelsCopy()));
            SyncDiscoveryPayload.markUploaded(tile.mapId(), gen);
            sent++;
        }
    }

    private static void markAllPixels(MapEntryData mapEntry) {
        for (int row = 0; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                mapEntry.discover(col, row);
            }
        }
    }

    private static void castRays(LocalPlayer player,
                                  MapItemSavedData mapState,
                                  TileGrid grid,
                                  MultiTileCanvas canvas,
                                  float yawDeg, float pitchDeg, float fovDeg,
                                  int hRays) {

        Vec3 origin = player.getEyePosition(1.0f);
        float halfFov = fovDeg * 0.5f;

        float[] vOffsets = { -8f, 0f, 8f };

        for (float vOff : vOffsets) {
            float pitch = pitchDeg + vOff;

            for (int i = 0; i < hRays; i++) {
                float t   = hRays == 1 ? 0f : (float) i / (hRays - 1);
                float yaw = yawDeg - halfFov + t * fovDeg;

                Vec3 dir = directionFromAngles(yaw, pitch);
                markRayAcrossTiles(player, origin, dir, mapState, grid, canvas);
            }
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

        Vec3 visiblePoint = hit.getLocation();
        TileGrid.TileEntry owner =
                grid.findTileContaining(canvas, visiblePoint.x, visiblePoint.z);
        if (owner == null) return;

        int canvasX = canvas.worldToCanvasX(visiblePoint.x);
        int canvasZ = canvas.worldToCanvasZ(visiblePoint.z);
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
