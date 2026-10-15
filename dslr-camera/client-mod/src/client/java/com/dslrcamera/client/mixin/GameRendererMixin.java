package com.dslrcamera.client.mixin;

import com.dslrcamera.client.CameraState;
import com.dslrcamera.client.photo.PhotoCapture;
import com.dslrcamera.client.render.DslrEffect;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void dslrcamera$hideHand(CallbackInfo ci) {
		if (CameraState.isActive()) {
			ci.cancel();
		}
	}

	/** Right after the world is drawn, while its depth buffer is still intact (the hand pass clears it next). */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
		shift = At.Shift.AFTER))
	private void dslrcamera$applyOptics(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (CameraState.isActive()) {
			GameRenderer self = (GameRenderer) (Object) this;
			DslrEffect.apply(self.mainRenderTarget(), self.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix);
		}
	}

	/** Captures after the world (and before the GUI) is drawn, so photos never contain HUD elements. */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;tryTakeScreenshotIfNeeded()V"))
	private void dslrcamera$capture(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		if (!CameraState.isActive()) {
			return;
		}
		GameRenderer self = (GameRenderer) (Object) this;
		if (CameraState.consumeShotRequest()) {
			PhotoCapture.begin(CameraState.caption(), CameraState.size());
		}
		PhotoCapture.onFrame(self.mainRenderTarget());
	}
}
