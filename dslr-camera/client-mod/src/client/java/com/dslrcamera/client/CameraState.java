package com.dslrcamera.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.dslrcamera.client.exposure.ExposureSettings;
import com.dslrcamera.client.exposure.Flash;
import com.dslrcamera.client.exposure.LightMeter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Client-side camera state. Right-click toggles the camera "raised"; while raised, the player keeps using the
 * spyglass underneath so other players see it held to the eye. The viewfinder is active while raised in first person.
 * Focal length is in 35mm-equivalent millimetres and maps to vertical FOV like a full-frame (24mm tall) sensor.
 */
public final class CameraState {
	public enum Setting {
		MODE("MODE"), SHUTTER("SHUTTER"), APERTURE("APERTURE"), ISO("ISO"), COMPENSATION("EXP. COMP"), FLASH("FLASH"), FOCUS("FOCUS"), ZOOM("ZOOM"), SIZE("PRINT SIZE");

		public final String label;

		Setting(String label) {
			this.label = label;
		}
	}

	public static final float MIN_FOCAL = 16.0F;
	public static final float MAX_FOCAL = 300.0F;
	private static final float SENSOR_HEIGHT_MM = 24.0F;
	private static final float ZOOM_STEP = 1.1F;
	private static final long SHUTTER_COOLDOWN_MS = 400;

	private static boolean raised;
	private static float targetFocal = 50.0F;
	private static float focal = 50.0F;
	private static float prevFocal = 50.0F;
	private static int sizeIndex;
	private static Setting selected = Setting.ZOOM;
	private static double scrollAccumulator;
	private static boolean shotRequested;
	private static long lastShotMs;
	private static long flashStartMs = -1;

	private CameraState() {
	}

	public static boolean isRaised() {
		return raised;
	}

	public static void setRaised(boolean value) {
		raised = value;
		if (value) {
			focal = prevFocal = targetFocal;
		}
		shotRequested = false;
	}

	public static boolean isActive() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		return raised && player != null && minecraft.options.getCameraType().isFirstPerson()
			&& player.isUsingItem() && CameraItems.isCamera(player.getUseItem());
	}

	public static boolean isHoldingCamera(LocalPlayer player) {
		return CameraItems.isCamera(player.getMainHandItem()) || CameraItems.isCamera(player.getOffhandItem());
	}

	public static void tick() {
		prevFocal = focal;
		focal += (targetFocal - focal) * 0.5F;
	}

	public static float targetFocal() {
		return targetFocal;
	}

	public static float fov(float partialTicks) {
		return verticalFov(Mth.lerp(partialTicks, prevFocal, focal));
	}

	public static float verticalFov(float focalMm) {
		return (float) Math.toDegrees(2.0 * Math.atan(SENSOR_HEIGHT_MM / (2.0 * focalMm)));
	}

	public static PrintSize size() {
		return PrintSize.ALL.get(sizeIndex);
	}

	public static void cycleSize(int direction) {
		sizeIndex = Math.floorMod(sizeIndex + direction, PrintSize.ALL.size());
	}

	public static Setting selected() {
		return selected;
	}

	/** Settings the dial can currently change; ones the exposure mode controls automatically are skipped. */
	public static List<Setting> adjustableSettings() {
		List<Setting> settings = new ArrayList<>(List.of(Setting.values()));
		if (ExposureSettings.shutterIsAuto()) {
			settings.remove(Setting.SHUTTER);
		}
		if (ExposureSettings.apertureIsAuto()) {
			settings.remove(Setting.APERTURE);
		}
		if (!ExposureSettings.compensationApplies()) {
			settings.remove(Setting.COMPENSATION);
		}
		return settings;
	}

	public static void cycleSelected(int direction) {
		List<Setting> settings = adjustableSettings();
		int index = settings.indexOf(selected);
		selected = settings.get(Math.floorMod((index < 0 ? 0 : index + direction), settings.size()));
	}

	/** Mouse wheel turns the command dial for the selected setting. Fractional (trackpad) scrolling accumulates. */
	public static void scroll(double amount) {
		scrollAccumulator += amount;
		int steps = (int) scrollAccumulator;
		if (steps == 0) {
			return;
		}
		scrollAccumulator -= steps;
		if (!adjustableSettings().contains(selected)) {
			selected = Setting.ZOOM;
		}
		switch (selected) {
			case MODE -> {
				ExposureSettings.cycleMode(steps);
				if (!adjustableSettings().contains(selected)) {
					selected = Setting.MODE;
				}
			}
			case SHUTTER -> ExposureSettings.stepShutter(steps);
			case APERTURE -> ExposureSettings.stepAperture(steps);
			case ISO -> ExposureSettings.stepIso(steps);
			case COMPENSATION -> ExposureSettings.stepCompensation(steps);
			case FLASH -> Flash.step(steps);
			case FOCUS -> LightMeter.stepFocus(steps);
			case ZOOM -> targetFocal = Mth.clamp((float) (targetFocal * Math.pow(ZOOM_STEP, steps)), MIN_FOCAL, MAX_FOCAL);
			case SIZE -> cycleSize(steps);
		}
	}

	public static String caption() {
		return String.format(Locale.ROOT, "%dmm · %s · %s · ISO %d%s", Math.round(targetFocal),
			ExposureSettings.apertureLabel(), ExposureSettings.shutterLabel(), ExposureSettings.iso(), Flash.willFire() ? " · flash" : "");
	}

	/** Returns false if the shutter is still cycling. */
	public static boolean requestShot() {
		long now = System.currentTimeMillis();
		if (now - lastShotMs < SHUTTER_COOLDOWN_MS) {
			return false;
		}
		lastShotMs = now;
		shotRequested = true;
		return true;
	}

	/** Returns true once per requested shot, from the render thread at capture time. */
	public static boolean consumeShotRequest() {
		boolean requested = shotRequested;
		shotRequested = false;
		return requested;
	}

	/** True during the frame that is about to be captured (the optics pass runs before the capture consumes it). */
	public static boolean shotPending() {
		return shotRequested;
	}

	public static void flash() {
		flashStartMs = System.currentTimeMillis();
	}

	/** Shutter blackout opacity in [0,1], fading out over ~150ms after a capture. */
	public static float shutterBlackout() {
		if (flashStartMs < 0) {
			return 0.0F;
		}
		float t = (System.currentTimeMillis() - flashStartMs) / 150.0F;
		if (t >= 1.0F) {
			flashStartMs = -1;
			return 0.0F;
		}
		return 1.0F - t;
	}
}
