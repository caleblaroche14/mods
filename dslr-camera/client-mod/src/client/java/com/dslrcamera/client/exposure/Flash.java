package com.dslrcamera.client.exposure;

import com.dslrcamera.client.render.DslrEffect;

/**
 * Hot-shoe speedlight with TTL metering. It lights whatever the shot sees with inverse-square falloff (see the develop
 * shader), sized so the subject under the AF point comes out correctly exposed, up to the flash's maximum power.
 *
 * <p>Power is expressed as the flash's contribution at 1 m relative to a correct exposure, so the subject at distance
 * d receives {@code power / d²}. Full power follows the guide number: correct exposure at d = GN / N at ISO 100.
 */
public final class Flash {
	public enum Mode {
		OFF("OFF", "OFF"), AUTO("AUTO", "A"), ON("ON", "ON"), SLOW("SLOW", "SL");

		public final String label;
		public final String shortLabel;

		Mode(String label, String shortLabel) {
			this.label = label;
			this.shortLabel = shortLabel;
		}
	}

	/** Metres at ISO 100, a typical full-size speedlight. */
	public static final double GUIDE_NUMBER = 36.0;
	/** Slowest shutter P and A modes choose when the flash fires (except slow sync), like a real camera. */
	private static final double SLOWEST_FLASH_SHUTTER = 1 / 60.0;
	/** AUTO fires when the subject's ambient exposure is more than a stop under. */
	private static final double AUTO_THRESHOLD = 0.5;
	/** ON and SLOW always add at least this much (about -2 EV fill), so daylight portraits get lifted shadows. */
	private static final double MIN_FILL = 0.25;
	private static final long RECYCLE_MIN_MS = 300;
	private static final long RECYCLE_FULL_MS = 2500;
	private static final long POP_MS = 220;

	private static Mode mode = Mode.OFF;
	private static boolean wanted;
	private static double power;
	private static double exposurePower;
	private static long readyAtMs;
	private static long firedAtMs = -1;

	private Flash() {
	}

	public static Mode mode() {
		return mode;
	}

	public static void step(int steps) {
		Mode[] modes = Mode.values();
		mode = modes[Math.clamp(mode.ordinal() + steps, 0, modes.length - 1)];
	}

	public static boolean isReady() {
		return System.currentTimeMillis() >= readyAtMs;
	}

	/** True if pressing the shutter now would fire the flash. */
	public static boolean willFire() {
		return wanted && isReady();
	}

	public static double power() {
		return power;
	}

	/** Power to apply to frames of the long exposure in progress (the flash fired as it opened), or 0. */
	public static double exposurePower() {
		return exposurePower;
	}

	/** Furthest distance full power can correctly expose at the current aperture and ISO. */
	public static double rangeMetres() {
		return GUIDE_NUMBER / ExposureSettings.fNumber() * Math.sqrt(ExposureSettings.iso() / 100.0);
	}

	public static boolean subjectOutOfRange() {
		return LightMeter.subjectDistance() > rangeMetres();
	}

	/**
	 * Runs every tick after auto exposure: decides whether the flash will fire, holds the shutter at a hand-holdable
	 * speed in the auto modes when it does, and sets TTL power for the subject under the AF point.
	 */
	public static void update() {
		double light = LightMeter.subjectRenderLight();
		wanted = switch (mode) {
			case OFF -> false;
			case AUTO -> light * DslrEffect.exposureGain() < AUTO_THRESHOLD;
			case ON, SLOW -> true;
		};
		if (!wanted) {
			power = 0;
			return;
		}
		if (mode != Mode.SLOW) {
			ExposureSettings.limitSlowestShutter(SLOWEST_FLASH_SHUTTER);
		}
		double target = ExposureSettings.compensationApplies() ? Math.pow(2, ExposureSettings.compensation()) : 1.0;
		double ambient = light * DslrEffect.exposureGain();
		double needed = Math.max(target - ambient, MIN_FILL * target);
		double distance = LightMeter.subjectDistance();
		// Nothing for TTL to measure (open sky): a real flash dumps full power.
		double ttl = distance >= LightMeter.INFINITY_FOCUS ? Double.MAX_VALUE : needed * distance * distance;
		power = Math.min(ttl, maxPower());
	}

	private static double maxPower() {
		double reach = GUIDE_NUMBER / ExposureSettings.fNumber();
		return reach * reach * ExposureSettings.iso() / 100.0;
	}

	/** Called as the shutter opens. Returns whether the flash fired, and starts recharging if it did. */
	public static boolean fire() {
		boolean fired = willFire() && power > 0;
		exposurePower = fired ? power : 0;
		if (fired) {
			long now = System.currentTimeMillis();
			firedAtMs = now;
			readyAtMs = now + RECYCLE_MIN_MS + Math.round((RECYCLE_FULL_MS - RECYCLE_MIN_MS) * Math.min(1.0, power / maxPower()));
		}
		return fired;
	}

	/** White flash seen by the photographer, fading out over ~{@value #POP_MS}ms. */
	public static float popAlpha() {
		if (firedAtMs < 0) {
			return 0.0F;
		}
		float t = (System.currentTimeMillis() - firedAtMs) / (float) POP_MS;
		if (t >= 1.0F) {
			firedAtMs = -1;
			return 0.0F;
		}
		return 0.85F * (1.0F - t);
	}
}
