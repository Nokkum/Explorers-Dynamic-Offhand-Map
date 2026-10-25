package com.explorermap.waypoint;

import com.explorermap.data.CodecUtil;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

public record Waypoint(UUID id,
                       String name,
                       double worldX,
                       double worldZ,
                       String iconId,
                       int color,
                       String ownerUuid) {

    public static final String DEFAULT_ICON = "explorermap:pin";

    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_ICON_ID_LENGTH = 128;
    public static final int MAX_ID_LENGTH = 36;
    public static final int MAX_OWNER_LENGTH = 36;

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

    private static final Pattern OWNER_FORMAT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static DataResult<Waypoint> validateOwner(Waypoint waypoint) {
        String owner = waypoint.ownerUuid();
        return owner == null || owner.isEmpty()
                || (owner.length() <= MAX_OWNER_LENGTH && OWNER_FORMAT.matcher(owner).matches())
                ? DataResult.success(waypoint)
                : DataResult.error(() -> "Owner is not a canonical UUID string");
    }

    public static final Codec<Waypoint> CODEC = RecordCodecBuilder.<Waypoint>create(instance ->
            instance.group(
                    CodecUtil.boundedString(MAX_ID_LENGTH).optionalFieldOf("id", "")
                            .forGetter(w -> w.id() == null ? "" : w.id().toString()),
                    CodecUtil.boundedString(MAX_NAME_LENGTH).fieldOf("name").forGetter(Waypoint::name),
                    Codec.DOUBLE.fieldOf("world_x").forGetter(Waypoint::worldX),
                    Codec.DOUBLE.fieldOf("world_z").forGetter(Waypoint::worldZ),
                    CodecUtil.boundedString(MAX_ICON_ID_LENGTH).fieldOf("icon_id").forGetter(Waypoint::iconId),
                    Codec.INT.fieldOf("color").forGetter(Waypoint::color),
                    CodecUtil.boundedString(MAX_OWNER_LENGTH).optionalFieldOf("owner_uuid", "")
                            .forGetter(Waypoint::ownerUuid)
            ).apply(instance, (idRaw, name, worldX, worldZ, iconId, color, ownerUuid) -> {
                UUID id = parseIdOrNull(idRaw);
                if (id == null) id = UUID.randomUUID();
                return new Waypoint(id, name, worldX, worldZ, iconId, color, ownerUuid);
            })
    ).comapFlatMap(Waypoint::validateOwner, Function.identity());

    public static final StreamCodec<FriendlyByteBuf, Waypoint> PACKET_CODEC = StreamCodec.ofMember(
            (Waypoint value, FriendlyByteBuf buf) -> {
                buf.writeUUID(value.id());
                buf.writeUtf(value.name(), MAX_NAME_LENGTH);
                buf.writeDouble(value.worldX());
                buf.writeDouble(value.worldZ());
                buf.writeUtf(value.iconId(), MAX_ICON_ID_LENGTH);
                buf.writeInt(value.color());
                buf.writeUtf(value.ownerUuid() == null ? "" : value.ownerUuid(), MAX_OWNER_LENGTH);
            },
            (FriendlyByteBuf buf) -> new Waypoint(
                    buf.readUUID(),
                    buf.readUtf(MAX_NAME_LENGTH),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readUtf(MAX_ICON_ID_LENGTH),
                    buf.readInt(),
                    buf.readUtf(MAX_OWNER_LENGTH)
            )
    );
}
