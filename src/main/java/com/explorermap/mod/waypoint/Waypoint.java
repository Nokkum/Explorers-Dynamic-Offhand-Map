package com.explorermap.mod.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A named positional marker placed by the player on a discovered area of the map.
 *
 * Waypoints are stored per-MapState in MapDiscoveryAttachment and are only
 * rendered on the mini-map and full-scale map if the pixel at their position
 * has been discovered.
 *
 * iconId is a string key referencing an icon in the ExplorerMapWaypointRegistry
 * (e.g. "village", "temple", or a modded structure ID like "bettervillages:hamlet").
 */
public record Waypoint(String name, double worldX, double worldZ, String iconId, int color) {

    public static final Codec<Waypoint> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("name").forGetter(Waypoint::name),
                    Codec.DOUBLE.fieldOf("world_x").forGetter(Waypoint::worldX),
                    Codec.DOUBLE.fieldOf("world_z").forGetter(Waypoint::worldZ),
                    Codec.STRING.fieldOf("icon_id").forGetter(Waypoint::iconId),
                    Codec.INT.fieldOf("color").forGetter(Waypoint::color)
            ).apply(instance, Waypoint::new)
    );

    /** Default icon ID for generic waypoints. */
    public static final String DEFAULT_ICON = "explorermap:pin";

    /** Default waypoint color (white). */
    public static final int DEFAULT_COLOR = 0xFFFFFFFF;

    /** Convenience constructor with defaults. */
    public static Waypoint of(String name, double x, double z) {
        return new Waypoint(name, x, z, DEFAULT_ICON, DEFAULT_COLOR);
    }
}
