package com.explorermap.mod.engine;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import com.explorermap.mod.config.ExplorerMapConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
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
 * 2. Throttle: only run every TICK_INTERVAL ticks to reduce CPU cost.
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

    /** How many game ticks between discovery updates. Lower = more accurate, more CPU. */
    private static final int TICK_INTERVAL = 3;

    /** Horizontal rays cast per update. More = finer discovery at edges. */
    private static final int H_RAYS = 32;

    /** Vertical rays (handles looking up/down across the map plane). */
    private static final int V_RAYS = 3;

    /** Max distance (blocks) a ray travels before giving up. */
    private static final double MAX_RANGE = 256.0;

    private static int tickCounter = 0;

    /** Called from ClientTickEvents.END_CLIENT_TICK. */
    public static void tick(MinecraftClient client) {
        if (++tickCounter % TICK_INTERVAL != 0) return;

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

        MapDiscoveryAttachment attachment = ExplorerMapMod.getOrCreate(mapState);

        // ── 3. Camera parameters ──────────────────────────────────────────
        float yawDeg   = player.getYaw();
        float pitchDeg = player.getPitch();
        // Retrieve game FOV (base value, not post-effects) from config
        float fovDeg = (float) client.options.getFov().getValue();

        // ── 4. Cast rays ──────────────────────────────────────────────────
        castRays(player, mapState, attachment, yawDeg, pitchDeg, fovDeg);
    }

    private static void castRays(ClientPlayerEntity player,
                                  MapState mapState,
                                  MapDiscoveryAttachment attachment,
                                  float yawDeg, float pitchDeg, float fovDeg) {

        Vec3d origin = player.getCameraPosVec(1.0f);
        float halfFov = fovDeg * 0.5f;

        // Vertical spread: fan slightly above/below the horizon
        float[] vOffsets = { -8f, 0f, 8f }; // degrees

        for (float vOff : vOffsets) {
            float pitch = pitchDeg + vOff;

            for (int i = 0; i < H_RAYS; i++) {
                // Distribute rays evenly across the horizontal FOV
                float t   = H_RAYS == 1 ? 0f : (float) i / (H_RAYS - 1); // [0, 1]
                float yaw = yawDeg - halfFov + t * fovDeg;

                // Convert yaw/pitch to direction vector
                Vec3d dir = directionFromAngles(yaw, pitch);

                // Intersect ray with Y = mapState.centerY (map's tracked elevation)
                // Vanilla maps track at world surface; use mapState scale center.
                double targetY = mapState.centerX; // centerX is actually Z in older yarn – check yours
                // Project to map XZ plane at player's feet Y
                double landY = origin.y; // approximate: map plane at eye level projection

                // Walk along the ray until it covers max range or exits the map
                markRayOnMap(origin, dir, mapState, attachment);
            }
        }
    }

    /**
     * Steps a ray forward in world space and marks map pixels as discovered.
     * Steps in 2-block increments; stops at MAX_RANGE or when off the map.
     */
    private static void markRayOnMap(Vec3d origin, Vec3d dir,
                                      MapState mapState,
                                      MapDiscoveryAttachment attachment) {
        // Vanilla map: 128×128 pixels, each pixel = 2^scale blocks
        int scale = 1 << mapState.scale; // e.g. scale=2 → 4 blocks/pixel
        int mapCenterX = mapState.centerX;
        int mapCenterZ = mapState.centerZ;

        double stepSize = Math.max(scale, 2.0);
        double x = origin.x;
        double z = origin.z;

        for (double dist = 0; dist < MAX_RANGE; dist += stepSize) {
            x += dir.x * stepSize;
            z += dir.z * stepSize;

            // Convert world XZ → map pixel col/row
            int col = worldToPixel(x, mapCenterX, scale);
            int row = worldToPixel(z, mapCenterZ, scale);

            if (col < 0 || col >= 128 || row < 0 || row >= 128) break; // off map

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
