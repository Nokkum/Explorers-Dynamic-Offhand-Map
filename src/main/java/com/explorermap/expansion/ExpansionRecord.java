package com.explorermap.expansion;

import com.explorermap.data.MapIdentity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;

public record ExpansionRecord(Direction direction, MapIdentity target, boolean highDetail) {

    public enum Direction {
        NORTH, SOUTH, EAST, WEST;

        public static final Codec<Direction> CODEC =
                Codec.STRING.xmap(Direction::valueOf, Direction::name);

        public static final PacketCodec<PacketByteBuf, Direction> PACKET_CODEC = PacketCodec.of(
                (Direction value, PacketByteBuf buf) -> buf.writeString(value.name()),
                (PacketByteBuf buf) -> {
                    String raw = buf.readString();
                    try {
                        return Direction.valueOf(raw);
                    } catch (IllegalArgumentException e) {
                        throw new DecoderException("Invalid ExpansionRecord.Direction: " + raw);
                    }
                }
        );
    }

    public static final Codec<ExpansionRecord> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Direction.CODEC.fieldOf("direction").forGetter(ExpansionRecord::direction),
                    MapIdentity.CODEC.fieldOf("target").forGetter(ExpansionRecord::target),
                    Codec.BOOL.fieldOf("high_detail").forGetter(ExpansionRecord::highDetail)
            ).apply(instance, ExpansionRecord::new)
    );
}
