package com.explorermap.expansion;

import com.explorermap.data.CodecUtil;
import com.explorermap.data.MapIdentity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record ExpansionRecord(Direction direction, MapIdentity target, boolean highDetail) {

    public enum Direction {
        NORTH, SOUTH, EAST, WEST;

        private static final int MAX_NAME_LENGTH = 8;

        public static final Codec<Direction> CODEC =
                CodecUtil.boundedString(MAX_NAME_LENGTH).comapFlatMap(Direction::parse, Direction::name);

        private static final Direction[] VALUES = values();

        private static DataResult<Direction> parse(String raw) {
            for (Direction direction : VALUES) {
                if (direction.name().equals(raw)) return DataResult.success(direction);
            }
            return DataResult.error(() -> "Invalid ExpansionRecord.Direction: " + raw);
        }

        public static final StreamCodec<FriendlyByteBuf, Direction> PACKET_CODEC = StreamCodec.ofMember(
                (Direction value, FriendlyByteBuf buf) -> buf.writeVarInt(value.ordinal()),
                (FriendlyByteBuf buf) -> {
                    int ordinal = buf.readVarInt();
                    if (ordinal < 0 || ordinal >= VALUES.length) {
                        throw new DecoderException("Invalid ExpansionRecord.Direction ordinal: " + ordinal);
                    }
                    return VALUES[ordinal];
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

    public static final StreamCodec<FriendlyByteBuf, ExpansionRecord> PACKET_CODEC =
            StreamCodec.composite(
                    Direction.PACKET_CODEC, ExpansionRecord::direction,
                    MapIdentity.PACKET_CODEC, ExpansionRecord::target,
                    ByteBufCodecs.BOOL, ExpansionRecord::highDetail,
                    ExpansionRecord::new
            );
}
