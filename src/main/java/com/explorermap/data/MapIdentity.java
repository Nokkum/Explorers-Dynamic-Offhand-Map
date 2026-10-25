package com.explorermap.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.Objects;

public record MapIdentity(ResourceKey<Level> dimension, int mapId) {

    public MapIdentity {
        Objects.requireNonNull(dimension, "dimension");
    }

    public static MapIdentity of(Level world, int mapId) {
        return new MapIdentity(world.dimension(), mapId);
    }

    public static MapIdentity of(MapItemSavedData state, int mapId) {
        return new MapIdentity(state.dimension, mapId);
    }

    public static MapIdentity ofStack(ItemStack stack, Level anyWorld) {
        int raw = rawIdOf(stack);
        if (raw < 0) return null;
        MapItemSavedData state = stateOf(stack, anyWorld);
        if (state == null) return null;
        return new MapIdentity(state.dimension, raw);
    }

    public static MapId idOf(ItemStack stack) {
        return stack.get(DataComponents.MAP_ID);
    }

    public static int rawIdOf(ItemStack stack) {
        MapId id = idOf(stack);
        return id != null ? id.id() : -1;
    }

    public static MapItemSavedData stateOf(ItemStack stack, Level world) {
        return MapItem.getSavedData(stack, world);
    }

    public static MapItemSavedData stateOf(int rawId, Level world) {
        return world.getMapData(new MapId(rawId));
    }

    public static MapItemSavedData resolveAndVerify(MinecraftServer server, MapIdentity claimed) {
        if (claimed == null) return null;
        ServerLevel world = server.getLevel(claimed.dimension());
        if (world == null) return null;
        MapItemSavedData state = stateOf(claimed.mapId(), world);
        if (state == null) return null;
        if (!state.dimension.equals(claimed.dimension())) return null;
        return state;
    }

    public static MapItemSavedData resolveAuthorized(MinecraftServer server, ExplorerMapSavedData savedData,
                                              MapIdentity heldRoot, MapIdentity claimed) {
        if (heldRoot == null || claimed == null) return null;
        if (!savedData.isAccessibleFrom(heldRoot, claimed)) return null;
        return resolveAndVerify(server, claimed);
    }

    public String asKey() {
        return dimension.identifier() + "#" + mapId;
    }

    private static final Codec<ResourceKey<Level>> DIMENSION_CODEC =
            CodecUtil.boundedString(CodecUtil.MAX_DIMENSION_ID_LENGTH).comapFlatMap(
            raw -> {
                Identifier id = Identifier.tryParse(raw);
                return id == null
                        ? DataResult.error(() -> "Invalid dimension identifier: " + raw)
                        : DataResult.success(ResourceKey.create(Registries.DIMENSION, id));
            },
            key -> key.identifier().toString()
    );

    public static final Codec<MapIdentity> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    DIMENSION_CODEC.fieldOf("dimension").forGetter(MapIdentity::dimension),
                    Codec.intRange(0, Integer.MAX_VALUE).fieldOf("map_id").forGetter(MapIdentity::mapId)
            ).apply(instance, MapIdentity::new)
    );

    public static final StreamCodec<FriendlyByteBuf, MapIdentity> PACKET_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), MapIdentity::dimension,
            ByteBufCodecs.VAR_INT, MapIdentity::mapId,
            MapIdentity::new
    );
}
