package com.explorermap.mod.engine;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import com.explorermap.mod.config.ExplorerMapConfig;
import com.explorermap.mod.dimension.DimensionMapTracker;
import com.explorermap.mod.network.SyncDiscoveryPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.storage.MapState;

/**
 * Camera-based fog-of-discovery engine.
 *
 * Algorithm (per tick, client-side)
 * ──────────────────────────────────
 * 1. Bail early if no filled map in off-hand, or player is null.
 * 2. Throttle: only run every cfg.discoveryTickInterval ticks to reduce CPU cost.
 * 3. Read player camera yaw/pitch and derive the horizontal FOV from
 *    the game's configured FOV setting.
 * 4. Cast RAY_COUNT rays fanning across the visible arc. For each ray,
 *    project to the map's XZ plane and compute the pixel coordinate.
 * 5. Mark those pixels as discovered in the MapDiscoveryAttachment.
 *
 * Performance notes
 * ─────────────────
 * - Throttled to every 3 ticks (~5 updates/sec) by default.
 * - Ray count defaults to 32 horizontal × 3 vertical = 96 rays/update.
 * - All math is integer-friendly after the initial trig.
 * - The bitmask write is synchronized in MapDiscoveryAttachment.
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

        // ── 2. Resolve MapState and attachment ───────────────────────────
        Integer mapId = FilledMapItem.getMapId(offHand);
        if (mapId == null) return;

        MapState mapState = FilledMapItem.getMapState(offHand, client.world);
        if (mapState == null) return;

        // ── Dimension gate ─────────────────────────────────────────────────
        // Don't mark pixels if this map belongs to a different dimension.
        if (!DimensionMapTracker.isMapRelevantForCurrentDimension(player, mapState)) return;

        MapDiscoveryAttachment attachment = ExplorerMapMod.getOrCreate(mapState);

        // ── 3. Camera parameters ──────────────────────────────────────────
        float yawDeg   = player.getYaw();
        float pitchDeg = player.getPitch();
        float fovDeg   = (float) client.options.getFov().getValue();

        // Coordinate multiplier: in the Nether, player coords are 1/8 of Overworld.
        // Map centers are always stored in Overworld space, so we scale up.
        double coordMult = DimensionMapTracker.dimensionCoordMultiplier(player);

        // ── 4. Cast rays / mark all ───────────────────────────────────────
        if (cfg.fogOfDiscovery) {
            castRays(player, mapState, attachment, yawDeg, pitchDeg, fovDeg, cfg.rayCount, coordMult);
        } else if (attachment.discoveryFraction() < 1f) {
            // Fog disabled: reveal every pixel within the map bounds instantly.
            // Skip once fully discovered so we don't re-scan 16 384 pixels
            // (and take a lock per pixel) forever on every throttle tick.
            markAllPixels(attachment);
        }

        // ── 5. Multiplayer sync (throttled) ───────────────────────────────
        // Upload this client's bitmask to the server every ~3 seconds if new
        // pixels were discovered. The server OR-merges contributions from all
        // players holding the same map and broadcasts the result back.
        if (SyncDiscoveryPayload.shouldUpload(attachment.getDiscoveryGeneration())) {
            ClientPlayNetworking.send(
                    new SyncDiscoveryPayload.Upload(mapId, attachment.getDiscoveryLongs()));
        }
    }

    /** Reveals every pixel in the 128×128 grid instantly (fog disabled mode). */
    private static void markAllPixels(MapDiscoveryAttachment attachment) {
        for (int row = 0; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                attachment.discover(col, row);
            }
        }
    }

    private static void castRays(ClientPlayerEntity player,
                                  MapState mapState,
                                  MapDiscoveryAttachment attachment,
                                  float yawDeg, float pitchDeg, float fovDeg,
                                  int hRays, double coordMult) {

        Vec3d origin = player.getCameraPosVec(1.0f);
        float halfFov = fovDeg * 0.5f;

        float[] vOffsets = { -8f, 0f, 8f };

        for (float vOff : vOffsets) {
            float pitch = pitchDeg + vOff;

            for (int i = 0; i < hRays; i++) {
                float t   = hRays == 1 ? 0f : (float) i / (hRays - 1);
                float yaw = yawDeg - halfFov + t * fovDeg;

                Vec3d dir = directionFromAngles(yaw, pitch);
                markRayOnMap(origin, dir, mapState, attachment, coordMult);
            }
        }
    }

    /**
     * Steps a ray forward in world space and marks map pixels as discovered.
     *
     * @param coordMult  1.0 for Overworld/End, 8.0 for Nether.
     *                   Scales player world coords up to Overworld space
     *                   so they match map centres (always stored as OW coords).
     */
    private static void markRayOnMap(Vec3d origin, Vec3d dir,
                                      MapState mapState,
                                      MapDiscoveryAttachment attachment,
                                      double coordMult) {
        int scale      = 1 << mapState.scale;
        int mapCenterX = mapState.centerX;
        int mapCenterZ = mapState.centerZ;

        double stepSize = Math.max(scale, 2.0);
        double x = origin.x;
        double z = origin.z;

        for (double dist = 0; dist < MAX_RANGE; dist += stepSize) {
            x += dir.x * stepSize;
            z += dir.z * stepSize;

            int col = worldToPixel(x * coordMult, mapCenterX, scale);
            int row = worldToPixel(z * coordMult, mapCenterZ, scale);

            if (col < 0 || col >= 128 || row < 0 || row >= 128) break;

            attachment.discover(col, row);
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
     *
     * @param world       world coordinate (X or Z)
     * @param mapCenter   mapState.centerX / centerZ
     * @param scale       blocks per pixel (1 << mapState.scale)
     */
    private static int worldToPixel(double world, int mapCenter, int scale) {
        // Map covers 128 * scale blocks, centered at mapCenter
        int halfBlocks = 64 * scale;
        double relative = world - (mapCenter - halfBlocks);
        int pixel = (int) (relative / scale);
        return (pixel >= 0 && pixel < 128) ? pixel : -1;
    }
}
