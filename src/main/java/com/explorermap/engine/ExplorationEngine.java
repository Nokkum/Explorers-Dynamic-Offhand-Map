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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

@Environment(EnvType.CLIENT)
public class ExplorationEngine {

    private static final double MAX_RANGE = 256.0;

    private static int tickCounter = 0;

    public static void tick(MinecraftClient client) {
        ExplorerMapConfig cfg = ExplorerMapConfig.get();
        if (++tickCounter % cfg.discoveryTickInterval != 0) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        ItemStack offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        int mapId = MapIdentity.rawIdOf(offHand);
        if (mapId < 0) return;

        MapState mapState = MapIdentity.stateOf(offHand, client.world);
        if (mapState == null) return;

        if (!DimensionMapTracker.isMapRelevantForCurrentDimension(player, mapState)) return;

        MapEntryData mapEntry = ClientMapCache.getOrCreate(mapId);
        TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, client.world);
        MultiTileCanvas canvas = MultiTileCanvas.from(grid, mapState);

        float yawDeg   = player.getYaw();
        float pitchDeg = player.getPitch();
        float fovDeg   = (float) client.options.getFov().getValue();

        if (cfg.fogOfDiscovery) {
            castRays(player, mapState, grid, canvas, yawDeg, pitchDeg, fovDeg, cfg.rayCount);
        } else if (mapEntry.discoveryFraction() < 1f) {
            markAllPixels(mapEntry);
        }

        if (SyncDiscoveryPayload.shouldUpload(mapEntry.getDiscoveryGeneration())) {
            ClientPlayNetworking.send(
                    new SyncDiscoveryPayload.Upload(mapId, mapEntry.getDiscoveredPixelsCopy()));
        }
    }

    private static void markAllPixels(MapEntryData mapEntry) {
        for (int row = 0; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                mapEntry.discover(col, row);
            }
        }
    }

    private static void castRays(ClientPlayerEntity player,
                                  MapState mapState,
                                  TileGrid grid,
                                  MultiTileCanvas canvas,
                                  float yawDeg, float pitchDeg, float fovDeg,
                                  int hRays) {

        Vec3d origin = player.getCameraPosVec(1.0f);
        float halfFov = fovDeg * 0.5f;

        float[] vOffsets = { -8f, 0f, 8f };

        for (float vOff : vOffsets) {
            float pitch = pitchDeg + vOff;

            for (int i = 0; i < hRays; i++) {
                float t   = hRays == 1 ? 0f : (float) i / (hRays - 1);
                float yaw = yawDeg - halfFov + t * fovDeg;

                Vec3d dir = directionFromAngles(yaw, pitch);
                markRayAcrossTiles(origin, dir, mapState, grid, canvas);
            }
        }
    }

    private static void markRayAcrossTiles(Vec3d origin, Vec3d dir,
                                            MapState mapState,
                                            TileGrid grid,
                                            MultiTileCanvas canvas) {
        int scale = 1 << mapState.scale;
        double stepSize = Math.max(scale, 2.0);
        double x = origin.x;
        double z = origin.z;

        for (double dist = 0; dist < MAX_RANGE; dist += stepSize) {
            x += dir.x * stepSize;
            z += dir.z * stepSize;

            TileGrid.TileEntry owner = grid.findTileContaining(canvas, x, z);
            if (owner == null) break;

            int canvasX = canvas.worldToCanvasX(x);
            int canvasZ = canvas.worldToCanvasZ(z);
            int localCol = canvasX - canvas.tileCanvasX(owner.gridX());
            int localRow = canvasZ - canvas.tileCanvasZ(owner.gridZ());

            owner.entry().discover(localCol, localRow);
        }
    }

    private static Vec3d directionFromAngles(float yawDeg, float pitchDeg) {
        float yaw   = yawDeg   * MathHelper.RADIANS_PER_DEGREE;
        float pitch = pitchDeg * MathHelper.RADIANS_PER_DEGREE;
        float cosP  = MathHelper.cos(-pitch);
        return new Vec3d(
                -MathHelper.sin(yaw) * cosP,
                 MathHelper.sin(-pitch),
                  MathHelper.cos(yaw) * cosP
        );
    }
}
