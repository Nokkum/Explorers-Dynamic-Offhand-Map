package com.explorermap.mod.engine;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.config.ExplorerMapConfig;
import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.data.MapEntryData;
import com.explorermap.mod.data.MapIdentity;
import com.explorermap.mod.dimension.DimensionMapTracker;
import com.explorermap.mod.network.SyncDiscoveryPayload;
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

        float yawDeg   = player.getYaw();
        float pitchDeg = player.getPitch();
        float fovDeg   = (float) client.options.getFov().getValue();

        if (cfg.fogOfDiscovery) {
            castRays(player, mapState, mapEntry, yawDeg, pitchDeg, fovDeg, cfg.rayCount);
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
                                  MapEntryData mapEntry,
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
                markRayOnMap(origin, dir, mapState, mapEntry);
            }
        }
    }

    private static void markRayOnMap(Vec3d origin, Vec3d dir,
                                      MapState mapState,
                                      MapEntryData mapEntry) {
        int scale      = 1 << mapState.scale;
        int mapCenterX = mapState.centerX;
        int mapCenterZ = mapState.centerZ;

        double stepSize = Math.max(scale, 2.0);
        double x = origin.x;
        double z = origin.z;

        for (double dist = 0; dist < MAX_RANGE; dist += stepSize) {
            x += dir.x * stepSize;
            z += dir.z * stepSize;

            int col = worldToPixel(x, mapCenterX, scale);
            int row = worldToPixel(z, mapCenterZ, scale);

            if (col < 0 || col >= 128 || row < 0 || row >= 128) break;

            mapEntry.discover(col, row);
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

    private static int worldToPixel(double world, int mapCenter, int scale) {
        int halfBlocks = 64 * scale;
        double relative = world - (mapCenter - halfBlocks);
        int pixel = (int) (relative / scale);
        return (pixel >= 0 && pixel < 128) ? pixel : -1;
    }
}
