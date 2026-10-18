package com.explorermap.interaction;

import com.explorermap.ExplorerMapClient;
import com.explorermap.ExplorerMapMod;
import com.explorermap.data.MapIdentity;
import com.explorermap.gui.FullMapScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

@Environment(EnvType.CLIENT)
public final class ExplorerMapClientInteractions {

    private ExplorerMapClientInteractions() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.getBlockState(hit.getBlockPos()).is(Blocks.CARTOGRAPHY_TABLE)
                    && hand == InteractionHand.OFF_HAND
                    && ExplorerMapMod.isFilledMap(player.getItemInHand(hand))) {
                ExplorerMapClient.openCartographyTable(hit.getBlockPos());
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (world.isClientSide()
                    && (hand == InteractionHand.OFF_HAND || hand == InteractionHand.MAIN_HAND)
                    && ExplorerMapMod.isFilledMap(stack)) {
                MapIdentity mapId = MapIdentity.ofStack(stack, world);
                if (mapId != null) {
                    Minecraft.getInstance().gui.setScreen(new FullMapScreen(mapId));
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() && entity instanceof ItemFrame frame
                    && hand == InteractionHand.MAIN_HAND
                    && player.getItemInHand(hand).isEmpty()
                    && ExplorerMapMod.isFilledMap(frame.getItem())) {
                MapIdentity mapId = MapIdentity.ofStack(frame.getItem(), world);
                if (mapId != null) {
                    Minecraft.getInstance().gui.setScreen(new FullMapScreen(mapId));
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });
    }
}
