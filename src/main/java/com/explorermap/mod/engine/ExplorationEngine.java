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

/**
 * Camera-based fog-of-discovery engine.
 *
 * Algorithm (per tick, client-side)
 * ──────────────────────────────────
 * 1. Bail early if no filled map in off-hand, or player is null.
 * 2. Throttle: only run every cfg.discoveryTickInterval ticks to reduce CPU cost.
 * 3. Read player camera yaw/pitch and derive the horizontal FOV from
 *    the game's configured FOV setting.
 * 4. Cast rayCount rays fanning across the visible arc. For each ray,
 *    project to the map's XZ plane and compute the pixel coordinate.
 * 5. Mark those pixels as discovered in the client's local MapEntryData
 *    mirror (ClientMapCache), then periodically upload the bitmask so the
 *    server can merge it with other players' progress on the same map.
 *
 * No coordinate conversion between dimensions
 * ────────────────────────────────────────────
 * An earlier revision applied a ×8 multiplier to player coordinates in the
 * Nether, based on a mistaken belief that vanilla stores all map centers in
 * Overworld-space coordinates. That is not how vanilla maps work: a map
 * created in the Nether stores its center in Nether block coordinates,
 * exactly like a map created in the Overworld stores Overworld coordinates —
 * each dimension has its own independent coordinate space. Since
 * DimensionMapTracker's relevance check already guarantees we only reach
 * this code when the held map's own dimension matches the player's current
 * dimension, player.getX()/getZ() are already in the correct coordinate
 * space for that map with no conversion needed.
 */
@Environment(EnvType.CLIENT)
public class ExplorationEngine {

    /** Max distance (blocks) a ray travels before giving up. */
    private static final double MAX_RANGE = 256.0;

    private static int tickCounter = 0;

    /** Called from ClientTickEvents.END_CLIENT_TICK. */
    public static void tick(MinecraftClient client) {
        ExplorerMapConfig cfg = ExplorerMapConfig.get();
        if (++tickCounter % cfg.discoveryTickInterval != 0) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        // ── 1. Check off-hand ─────────────────────────────────────────────
        ItemStack offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        // ── 2. Resolve map identity and state ─────────────────────────────
        int mapId = MapIdentity.rawIdOf(offHand);
        if (mapId < 0) return;

        MapState mapState = MapIdentity.stateOf(offHand, client.world);
        if (mapState == null) return;

        // ── Dimension gate ─────────────────────────────────────────────────
        if (!DimensionMapTracker.isMapRelevantForCurrentDimension(player, mapState)) return;

        MapEntryData mapEntry = ClientMapCache.getOrCreate(mapState, mapId);

        // ── 3. Camera parameters ──────────────────────────────────────────
        float yawDeg   = player.getYaw();
        float pitchDeg = player.getPitch();
        float fovDeg   = (float) client.options.getFov().getValue();

        // ── 4. Cast rays / mark all ───────────────────────────────────────
        if (cfg.fogOfDiscovery) {
            castRays(player, mapState, mapEntry, yawDeg, pitchDeg, fovDeg, cfg.rayCount);
        } else if (mapEntry.discoveryFraction() < 1f) {
            markAllPixels(mapEntry);
        }

        // ── 5. Multiplayer sync (throttled) ───────────────────────────────
        // Upload this client's bitmask to the server every ~3 seconds if new
        // pixels were discovered. The server OR-merges contributions from all
        // players holding the same map and broadcasts the result back.
        if (SyncDiscoveryPayload.shouldUpload(mapEntry.getDiscoveryGeneration())) {
            ClientPlayNetworking.send(
                    new SyncDiscoveryPayload.Upload(mapId, mapEntry.getDiscoveredPixelsCopy()));
        }
    }

    /** Reveals every pixel in the 128×128 grid instantly (fog disabled mode). */
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

    /** Steps a ray forward in world space and marks map pixels as discovered. */
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

    // ── Math helpers ──────────────────────────────────────────────────────

    /**
     * Converts Minecraft yaw/pitch (degrees) to a normalized direction vector.
     * Minecraft yaw: 0 = south (+Z), 90 = west (-X), -90 = east (+X).
     */
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

    /**
     * Maps a world coordinate to a map pixel index [0, 127].
     * Returns -1 if outside the map bounds.
     */
    private static int worldToPixel(double world, int mapCenter, int scale) {
        int halfBlocks = 64 * scale;
        double relative = world - (mapCenter - halfBlocks);
        int pixel = (int) (relative / scale);
        return (pixel >= 0 && pixel < 128) ? pixel : -1;
    }
}
