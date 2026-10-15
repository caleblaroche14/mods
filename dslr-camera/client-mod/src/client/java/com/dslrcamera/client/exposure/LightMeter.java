package com.dslrcamera.client.exposure;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Meters the scene and drives focus. Focus is continuous AF on whatever is under the centre point, AF-L (locked at
 * the last AF distance so you can recompose), or manual focus at a set distance. Minecraft has no physical light units, so light levels are mapped to
 * plausible real-world exposure values: full daylight ~EV15, moonlit night ~EV0, torch-lit rooms ~EV7, caves ~EV-2.
 * Metering is centre-weighted between the camera's own position and whatever is under the crosshair.
 */
public final class LightMeter {
	private static final double FOCUS_RANGE = 256.0;
	private static final double INFINITY_FOCUS = 1000.0;

	public enum FocusMode {
		AUTO("AF"), LOCKED("AF-L"), MANUAL("MF");

		public final String label;

		FocusMode(String label) {
			this.label = label;
		}
	}

	/** Manual focus distances in metres (blocks); the last one is infinity. */
	public static final double[] MANUAL_DISTANCES = {0.3, 0.5, 0.7, 1, 1.5, 2, 3, 5, 7, 10, 15, 20, 30, 50, 100, INFINITY_FOCUS};

	private static double sceneEv = 12.0;
	private static double focusDistance = 10.0;
	private static double targetFocusDistance = 10.0;
	private static double subjectDistance = 10.0;
	private static FocusMode focusMode = FocusMode.AUTO;
	private static int manualIndex = 7;
	private static boolean initialised;

	private LightMeter() {
	}

	public static double sceneEv() {
		return sceneEv;
	}

	public static double focusDistance() {
		return focusDistance;
	}

	/** True when whatever is under the centre point is within the lens's depth of field. */
	public static boolean inFocus() {
		return Math.abs(Math.log(focusDistance / subjectDistance)) < 0.08
			|| subjectDistance >= INFINITY_FOCUS && focusDistance >= 100;
	}

	public static FocusMode focusMode() {
		return focusMode;
	}

	public static int manualIndex() {
		return manualIndex;
	}

	/** V key: lock AF on the current subject, or go back to continuous AF. */
	public static void toggleFocusLock() {
		focusMode = focusMode == FocusMode.AUTO ? FocusMode.LOCKED : FocusMode.AUTO;
	}

	/** Turns the focus ring. Step 0 on the dial is AF; anything else is a manual distance. */
	public static void stepFocus(int steps) {
		int position = focusMode == FocusMode.MANUAL ? manualIndex + 1 : 0;
		if (position == 0 && steps > 0) {
			// Leaving AF: start from the distance AF had found.
			manualIndex = nearestManualIndex(focusDistance);
			position = manualIndex + 1;
			steps--;
		}
		position = Math.clamp(position + steps, 0, MANUAL_DISTANCES.length);
		if (position == 0) {
			focusMode = FocusMode.AUTO;
		} else {
			focusMode = FocusMode.MANUAL;
			manualIndex = position - 1;
		}
	}

	public static String distanceLabel(double metres) {
		if (metres >= INFINITY_FOCUS) {
			return "∞";
		}
		if (metres >= 10) {
			return Math.round(metres) + "m";
		}
		return (metres == Math.floor(metres) ? String.valueOf((int) metres) : String.format(java.util.Locale.ROOT, "%.1f", metres)) + "m";
	}

	private static int nearestManualIndex(double metres) {
		int best = 0;
		for (int i = 1; i < MANUAL_DISTANCES.length; i++) {
			if (Math.abs(Math.log(MANUAL_DISTANCES[i] / metres)) < Math.abs(Math.log(MANUAL_DISTANCES[best] / metres))) {
				best = i;
			}
		}
		return best;
	}

	/** Snap meter and focus to the current scene, e.g. when raising the camera. */
	public static void reset() {
		initialised = false;
		if (focusMode == FocusMode.LOCKED) {
			focusMode = FocusMode.AUTO;
		}
	}

	public static void tick(Minecraft minecraft) {
		Entity camera = minecraft.getCameraEntity();
		if (camera == null || minecraft.level == null) {
			return;
		}
		Vec3 eye = camera.getEyePosition();
		HitResult hit = camera.pick(FOCUS_RANGE, 1.0F, false);
		Entity target = minecraft.crosshairPickEntity;

		double distance = INFINITY_FOCUS;
		BlockPos meteredPos = BlockPos.containing(eye);
		if (target != null && target.position().distanceTo(eye) < FOCUS_RANGE) {
			distance = target.getBoundingBox().getCenter().distanceTo(eye);
			meteredPos = target.blockPosition();
		} else if (hit.getType() == HitResult.Type.BLOCK) {
			distance = hit.getLocation().distanceTo(eye);
			BlockHitResult blockHit = (BlockHitResult) hit;
			meteredPos = blockHit.getBlockPos().relative(blockHit.getDirection());
		}
		subjectDistance = Math.max(0.3, distance);
		switch (focusMode) {
			case AUTO -> targetFocusDistance = subjectDistance;
			case MANUAL -> targetFocusDistance = MANUAL_DISTANCES[manualIndex];
			case LOCKED -> {
			}
		}

		double ev = 0.4 * evAt(minecraft, BlockPos.containing(eye)) + 0.6 * evAt(minecraft, meteredPos);
		if (!initialised) {
			sceneEv = ev;
			focusDistance = targetFocusDistance;
			initialised = true;
		} else {
			sceneEv += (ev - sceneEv) * 0.25;
			// Focus moves in log space so near and far subjects settle at a similar perceived speed.
			focusDistance = Math.exp(Math.log(focusDistance) + (Math.log(targetFocusDistance) - Math.log(focusDistance)) * 0.35);
		}
	}

	private static double evAt(Minecraft minecraft, BlockPos pos) {
		var level = minecraft.level;
		int sky = Math.max(0, level.getBrightness(LightLayer.SKY, pos) - level.getSkyDarken());
		int block = level.getBrightness(LightLayer.BLOCK, pos);
		double skyEv = -2.0 + 17.0 * Math.pow(sky / 15.0, 1.5);
		double blockEv = 1.0 + 6.5 * (block / 15.0);
		double ambientFloor = -2.0 + 80.0 * level.dimensionType().ambientLight();
		return Math.max(Math.max(skyEv, blockEv), ambientFloor);
	}

	/**
	 * How much brighter a correctly exposed photo should look than the raw render. Minecraft renders nights and
	 * caves far brighter than reality, so a "correct" long exposure there should lift the image, but only a bit.
	 */
	public static double renderGain(double ev) {
		return ev >= 12 ? 1.0 : Math.min(4.0, Math.pow(2, (12 - ev) / 6.0));
	}
}
