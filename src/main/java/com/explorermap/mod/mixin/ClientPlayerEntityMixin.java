package com.explorermap.mod.mixin;

import com.explorermap.mod.engine.ExplorationEngine;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Mixin on ClientPlayerEntity.
 *
 * Currently a no-op placeholder. Reserved for cases where we need to hook into
 * per-frame camera state that isn't available via ClientTickEvents — for example,
 * intercepting the camera pitch/yaw mid-render for sub-tick interpolation, or
 * catching the moment the player first equips a map in the off-hand.
 *
 * To add an inject here, follow the standard Mixin pattern:
 *
 *   @Inject(method = "tick", at = @At("TAIL"))
 *   private void explorermap$onTick(CallbackInfo ci) { ... }
 *
 * The mixin is registered in explorermap.mixins.json under "client".
 */
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {
    // Reserved — add @Inject / @Redirect annotations here as needed.
}
