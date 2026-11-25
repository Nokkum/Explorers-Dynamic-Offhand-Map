package com.explorermap.mixin;

import com.explorermap.lock.MapPostProcessHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Vanilla locks or zooms out a map in MapItem#onCraftedPostProcess (taking the result at the cartography table or
// the crafting grid). The hook sees the map id before and after, which is how the mod's data follows the map.
@Mixin(MapItem.class)
public abstract class MapItemMixin {

    @Inject(method = "onCraftedPostProcess", at = @At("HEAD"))
    private void explorermap$beforePostProcess(ItemStack stack, Level level, CallbackInfo ci) {
        MapPostProcessHook.before(stack, level);
    }

    @Inject(method = "onCraftedPostProcess", at = @At("RETURN"))
    private void explorermap$afterPostProcess(ItemStack stack, Level level, CallbackInfo ci) {
        MapPostProcessHook.after(stack, level);
    }
}
