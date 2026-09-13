package com.explorermap.mod.dimension;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapState;

/**
 * Resolves dimension compatibility between the player's current world and
 * the map held in the off-hand.
 *
 * The problem
 * ───────────
 * Vanilla MapStates track centerX/centerZ in the Overworld coordinate space,
 * even for maps created in the Nether. There is no public field on MapState
 * that directly exposes which dimension a map was created in.
 *
 * Our approach
 * ────────────
 * We infer the map's dimension by comparing it against the player's position
 * in each dimension using a coordinate plausibility check:
 *
 *   1. If the player is in the Overworld and the map's center is within
 *      a plausible Overworld radius, treat the map as an Overworld map.
 *   2. If the player is in the Nether and the map's center divided by 8
 *      is close to the player's Nether position, treat the map as a Nether map.
 *      (Nether coordinates are 1:8 of Overworld, but vanilla maps store Overworld coords.)
 *   3. The End has only one "center" — maps made there always center near (0,0).
 *
 * This heuristic is reliable because:
 *   - Overworld maps are always centered at Overworld coords.
 *   - Nether maps are also centered at Overworld coords (Minecraft stores them
 *     in Overworld space universally), so a Nether map centered at OW (800, 800)
 *     corresponds to Nether position (100, 100).
 *   - The End is unambiguous since it only has one landmass.
 *
 * When dimension is mismatched, the HUD is suppressed rather than showing
 * a misleading map from another dimension.
 *
 * Nether scale correction
 * ───────────────────────
 * Maps made in the Nether cover the same block count as their Overworld
 * equivalent, but rendered positions need to account for the 1:8 coordinate
 * ratio. ExplorationEngine uses worldToPixel() which is purely XZ-based —
 * if the player is in the Nether at (100, 100) and the map center is at
 * OW (800, 800), the pixel math already works correctly because the map
 * center is stored in Overworld coords and we compare to Nether*8.
 */
@Environment(EnvType.CLIENT)
public final class DimensionMapTracker {

    /** Maximum distance (in map-scale blocks) the player can be from map centre
     *  before we consider the map a different-dimension map. */
    private static final int PLAUSIBILITY_RADIUS_TILES = 16; // 16 tile widths

    private DimensionMapTracker() {}

    /**
     * Returns true if the map held in the player's off-hand is relevant to
     * the player's current dimension and should be shown in the HUD.
     *
     * Returns false (suppress HUD) if:
     *  - No filled map in off-hand.
     *  - Map center is implausibly far from the player's dimension-adjusted position.
     *  - Player is in the End and the map is not an End map.
     */
    public static boolean isMapRelevantForCurrentDimension(ClientPlayerEntity player,
                                                             MapState mapState) {
        RegistryKey<World> dim = player.getWorld().getRegistryKey();
        int scale = 1 << mapState.scale;
        int tileBlocks = 128 * scale;
        int maxDist    = tileBlocks * PLAUSIBILITY_RADIUS_TILES;

        // Map center is always stored in Overworld coordinate space.
        int mapCX = mapState.centerX;
        int mapCZ = mapState.centerZ;

        if (dim.equals(World.OVERWORLD)) {
            // Player OW position vs map center
            double dx = player.getX() - mapCX;
            double dz = player.getZ() - mapCZ;
            return Math.abs(dx) <= maxDist && Math.abs(dz) <= maxDist;

        } else if (dim.equals(World.NETHER)) {
            // Nether coords * 8 = Overworld coords
            double owX = player.getX() * 8.0;
            double owZ = player.getZ() * 8.0;
            double dx = owX - mapCX;
            double dz = owZ - mapCZ;
            return Math.abs(dx) <= maxDist && Math.abs(dz) <= maxDist;

        } else if (dim.equals(World.END)) {
            // End maps are always centered near (0, 0) in OW space convention.
            // Accept any map whose center is within 2 tile widths of origin.
            int endRadius = tileBlocks * 2;
            return Math.abs(mapCX) <= endRadius && Math.abs(mapCZ) <= endRadius;

        } else {
            // Modded dimension: show the map if the player is within range
            // using the OW coordinate space (best-effort).
            double dx = player.getX() - mapCX;
            double dz = player.getZ() - mapCZ;
            return Math.abs(dx) <= maxDist && Math.abs(dz) <= maxDist;
        }
    }

    /**
     * Returns the coordinate multiplier to apply when converting player world
     * coordinates to map-pixel coordinates for the current dimension.
     *
     * In the Nether the player's X/Z are 1/8 of Overworld, but map centers
     * are stored in Overworld space, so we multiply by 8 before pixel math.
     * In all other dimensions the multiplier is 1.0.
     */
    public static double dimensionCoordMultiplier(ClientPlayerEntity player) {
        RegistryKey<World> dim = player.getWorld().getRegistryKey();
        return dim.equals(World.NETHER) ? 8.0 : 1.0;
    }

    /**
     * Human-readable dimension label for the HUD compass/status line.
     */
    public static String dimensionLabel(ClientPlayerEntity player) {
        RegistryKey<World> dim = player.getWorld().getRegistryKey();
        if (dim.equals(World.OVERWORLD)) return "Overworld";
        if (dim.equals(World.NETHER))    return "Nether";
        if (dim.equals(World.END))       return "The End";
        // Modded dimension: use last segment of registry path
        String path = dim.getValue().getPath();
        return Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
    }
}
