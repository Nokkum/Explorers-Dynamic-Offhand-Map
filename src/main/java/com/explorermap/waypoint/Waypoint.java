package com.explorermap.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.UUID;

public record Waypoint(UUID id,
                       String name,
                       double worldX,
                       double worldZ,
                       String iconId,
                       int color,
                       String ownerUuid) {

    public static final String DEFAULT_ICON = "explorermap:pin";

    public static final int DEFAULT_COLOR = 0xFFFFFFFF;

    public Waypoint(UUID id, String name, double worldX, double worldZ, String iconId, int color) {
        this(id, name, worldX, worldZ, iconId, color, "");
    }

    public static Waypoint create(String name, double x, double z, String iconId, int color, String ownerUuid) {
        return new Waypoint(UUID.randomUUID(), name, x, z, iconId, color, ownerUuid == null ? "" : ownerUuid);
    }

    public static Waypoint of(String name, double x, double z) {
        return create(name, x, z, DEFAULT_ICON, DEFAULT_COLOR, "");
    }

    public Waypoint withDisplay(String newName, String newIconId, int newColor) {
        return new Waypoint(id, newName, worldX, worldZ, newIconId, newColor, ownerUuid);
    }

    public Waypoint withPosition(double newWorldX, double newWorldZ) {
        return new Waypoint(id, name, newWorldX, newWorldZ, iconId, color, ownerUuid);
    }

    public boolean isOwnedBy(UUID playerId) {
        return playerId != null && playerId.toString().equals(ownerUuid);
    }

    public boolean isSystemOrLegacy() {
        return ownerUuid == null || ownerUuid.isBlank();
    }

    private static UUID parseIdOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static final Codec<Waypoint> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.optionalFieldOf("id", "")
                            .forGetter(w -> w.id() == null ? "" : w.id().toString()),
                    Codec.STRING.fieldOf("name").forGetter(Waypoint::name),
                    Codec.DOUBLE.fieldOf("world_x").forGetter(Waypoint::worldX),
                    Codec.DOUBLE.fieldOf("world_z").forGetter(Waypoint::worldZ),
                    Codec.STRING.fieldOf("icon_id").forGetter(Waypoint::iconId),
                    Codec.INT.fieldOf("color").forGetter(Waypoint::color),
                    Codec.STRING.optionalFieldOf("owner_uuid", "")
                            .forGetter(Waypoint::ownerUuid)
            ).apply(instance, (idRaw, name, worldX, worldZ, iconId, color, ownerUuid) -> {
                UUID id = parseIdOrNull(idRaw);
                if (id == null) id = UUID.randomUUID();
                return new Waypoint(id, name, worldX, worldZ, iconId, color, ownerUuid);
            })
    );
}
