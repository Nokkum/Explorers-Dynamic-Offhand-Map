package com.explorermap.banner;

import com.explorermap.registry.ExplorerMapRegistry;
import com.explorermap.waypoint.Waypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

import java.util.UUID;

/**
 * A banner used on a map shows up as its name only, without an icon. The name is kept as a system waypoint
 * (so it syncs, stays hidden until its spot is discovered, and follows the map when it is locked or zoomed out)
 * with a reserved icon id, which the map renderer draws as plain text instead of an icon.
 */
public final class BannerLabels {

    public static final String ICON_ID = "explorermap:banner";
    // A banner standing on a block from LARGE_LABEL_BLOCKS: the same label, drawn larger.
    public static final String ICON_ID_LARGE = "explorermap:banner_large";

    public static final int TEXT_COLOR = 0xFFFFFF;
    public static final float LARGE_TEXT_SCALE = 2.0f;
    // Per map and all of its expansion tiles together.
    public static final int MAX_PER_MAP = 128;

    // Blocks that make the name of a banner standing on them larger. Lodestone by default; a data pack can edit
    // data/explorermap/tags/block/large_banner_label.json to add more.
    public static final TagKey<Block> LARGE_LABEL_BLOCKS =
            TagKey.create(Registries.BLOCK, ExplorerMapRegistry.id("large_banner_label"));

    // The waypoint id is made from the banner's block position (the first half) and this marker (the second), so
    // using the map on the same banner again finds its label, and the label knows where its banner is.
    private static final long ID_MARKER = 0x42414E4E45524C42L;

    private BannerLabels() {}

    public static UUID idFor(BlockPos bannerPos) {
        return new UUID(bannerPos.asLong(), ID_MARKER);
    }

    public static boolean isBannerLabel(Waypoint waypoint) {
        return waypoint.isSystemOrLegacy()
                && waypoint.id() != null
                && waypoint.id().getLeastSignificantBits() == ID_MARKER
                && (ICON_ID.equals(waypoint.iconId()) || ICON_ID_LARGE.equals(waypoint.iconId()));
    }

    public static boolean isLarge(Waypoint waypoint) {
        return ICON_ID_LARGE.equals(waypoint.iconId());
    }

    public static BlockPos bannerPosOf(Waypoint label) {
        return BlockPos.of(label.id().getMostSignificantBits());
    }

    /** The banner's custom name as plain text; null if there is no banner at the position or it has no name. */
    public static String nameAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity banner)) return null;
        Component name = banner.getCustomName();
        if (name == null) return null;
        String text = name.getString().trim();
        if (text.isEmpty()) return null;
        return text.length() > Waypoint.MAX_NAME_LENGTH ? text.substring(0, Waypoint.MAX_NAME_LENGTH) : text;
    }

    /** The label's icon id for the banner at the position: the large one for a standing banner on a listed block. */
    public static String iconFor(Level level, BlockPos pos) {
        boolean standing = level.getBlockState(pos).getBlock() instanceof BannerBlock;
        boolean large = standing && level.getBlockState(pos.below()).is(LARGE_LABEL_BLOCKS);
        return large ? ICON_ID_LARGE : ICON_ID;
    }
}
