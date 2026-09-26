package com.explorermap.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Objects;

public record MapIdentity(RegistryKey<World> dimension, int mapId) {

    public MapIdentity {
        Objects.requireNonNull(dimension, "dimension");
    }

    public static MapIdentity of(World world, int mapId) {
        return new MapIdentity(world.getRegistryKey(), mapId);
    }

    public static MapIdentity of(MapState state, int mapId) {
        return new MapIdentity(state.dimension, mapId);
    }

    public static MapIdentity ofStack(ItemStack stack, World anyWorld) {
        int raw = rawIdOf(stack);
        if (raw < 0) return null;
        MapState state = stateOf(stack, anyWorld);
        if (state == null) return null;
        return new MapIdentity(state.dimension, raw);
    }

    public static MapIdComponent idOf(ItemStack stack) {
        return stack.get(DataComponentTypes.MAP_ID);
    }

    public static int rawIdOf(ItemStack stack) {
        MapIdComponent id = idOf(stack);
        return id != null ? id.id() : -1;
    }

    public static MapState stateOf(ItemStack stack, World world) {
        return FilledMapItem.getMapState(stack, world);
    }

    public static MapState stateOf(int rawId, World world) {
        return world.getMapState(new MapIdComponent(rawId));
    }

    public static MapState resolveAndVerify(World anyWorld, MapIdentity claimed) {
        if (claimed == null) return null;
        MapState state = stateOf(claimed.mapId(), anyWorld);
        if (state == null) return null;
        if (!state.dimension.equals(claimed.dimension())) return null;
        return state;
    }

    public String asKey() {
        return dimension.getValue() + "#" + mapId;
    }

    private static final Codec<RegistryKey<World>> DIMENSION_CODEC = Codec.STRING.comapFlatMap(
            raw -> {
                Identifier id = Identifier.tryParse(raw);
                return id == null
                        ? DataResult.error(() -> "Invalid dimension identifier: " + raw)
                        : DataResult.success(RegistryKey.of(RegistryKeys.WORLD, id));
            },
            key -> key.getValue().toString()
    );

    public static final Codec<MapIdentity> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    DIMENSION_CODEC.fieldOf("dimension").forGetter(MapIdentity::dimension),
                    Codec.INT.fieldOf("map_id").forGetter(MapIdentity::mapId)
            ).apply(instance, MapIdentity::new)
    );

    public static final PacketCodec<PacketByteBuf, MapIdentity> PACKET_CODEC = PacketCodec.of(
            (MapIdentity value, PacketByteBuf buf) -> {
                buf.writeIdentifier(value.dimension().getValue());
                buf.writeVarInt(value.mapId());
            },
            (PacketByteBuf buf) -> {
                Identifier dimId = buf.readIdentifier();
                int mapId = buf.readVarInt();
                return new MapIdentity(RegistryKey.of(RegistryKeys.WORLD, dimId), mapId);
            }
    );
}
