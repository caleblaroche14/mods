package com.dslrcamera.client.mixin;

import com.dslrcamera.client.CameraState;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears the vanilla HUD out of the viewfinder. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractSpyglassOverlay", at = @At("HEAD"), cancellable = true)
	private void dslrcamera$hideSpyglassScope(CallbackInfo ci) {
		// The camera is a spyglass underneath; our viewfinder replaces the round scope.
		if (CameraState.isActive()) {
			ci.cancel();
		}
	}

	/** Hotbar, health and the contextual bar (whose locator waypoints Fabric's HUD element hooks don't cover). */
	@Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
	private void dslrcamera$hideHotbarAndDecorations(CallbackInfo ci) {
		if (CameraState.isActive()) {
			ci.cancel();
		}
	}
}
