package com.dslrcamera.client.mixin;

import com.dslrcamera.client.CameraState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Iris draws the first-person hand in its own pass, bypassing GameRenderer.renderItemInHand. Only applies when Iris is installed. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class IrisHandRendererMixin {
	@Inject(method = "canRender", at = @At("HEAD"), cancellable = true, require = 0)
	private void dslrcamera$hideHand(CallbackInfoReturnable<Boolean> cir) {
		if (CameraState.isActive()) {
			cir.setReturnValue(false);
		}
	}
}
