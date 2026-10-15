package com.dslrcamera.client.mixin;

import com.dslrcamera.client.CameraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	private double accumulatedDX;
	@Shadow
	private double accumulatedDY;

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void dslrcamera$turnDial(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (CameraState.isActive() && handle == this.minecraft.getWindow().handle()
			&& this.minecraft.gui.screen() == null && this.minecraft.player != null) {
			CameraState.scroll(yoffset);
			ci.cancel();
		}
	}

	/**
	 * Scale look sensitivity with zoom so long lenses stay controllable. Vanilla already divides sensitivity by 8
	 * while scoping a spyglass (unless smooth camera is on), so undo that first.
	 */
	@Inject(method = "turnPlayer", at = @At("HEAD"))
	private void dslrcamera$scaleSensitivity(double mousea, CallbackInfo ci) {
		if (CameraState.isActive()) {
			double scale = CameraState.fov(1.0F) / this.minecraft.options.fov().get();
			if (!this.minecraft.options.smoothCamera) {
				scale *= 8.0;
			}
			this.accumulatedDX *= scale;
			this.accumulatedDY *= scale;
		}
	}
}
