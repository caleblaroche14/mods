package com.dslrcamera.client.mixin;

import com.dslrcamera.client.CameraState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void dslrcamera$overrideFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (CameraState.isActive() && !((Camera) (Object) this).isPanoramicMode()) {
			cir.setReturnValue(CameraState.fov(partialTicks));
		}
	}
}
