package com.explorermap.mod.expansion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Represents a single directional expansion of the player's map coverage.
 *
 * When a player spends paper to expand North, a new ExpansionRecord is added
 * to the map's MapEntryData with direction=NORTH and the vanilla map ID of the newly
 * tracked tile.
 *
 * highDetail = true means the player used ink+compass for a high-detail
 * expansion that supports waypoints in that tile.
 */
public record ExpansionRecord(Direction direction, int mapId, boolean highDetail) {

    public enum Direction {
        NORTH, SOUTH, EAST, WEST;

        public static final Codec<Direction> CODEC =
                Codec.STRING.xmap(Direction::valueOf, Direction::name);
    }

    public static final Codec<ExpansionRecord> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Direction.CODEC.fieldOf("direction").forGetter(ExpansionRecord::direction),
                    Codec.INT.fieldOf("map_id").forGetter(ExpansionRecord::mapId),
                    Codec.BOOL.fieldOf("high_detail").forGetter(ExpansionRecord::highDetail)
            ).apply(instance, ExpansionRecord::new)
    );
}
