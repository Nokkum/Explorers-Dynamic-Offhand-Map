package com.explorermap.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

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

    public static final String DEFAULT_ICON = "explorermap:pin";

    public static final int DEFAULT_COLOR = 0xFFFFFFFF;

    public static Waypoint of(String name, double x, double z) {
        return new Waypoint(name, x, z, DEFAULT_ICON, DEFAULT_COLOR);
    }
}
