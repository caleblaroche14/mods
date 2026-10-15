package com.dslrcamera.client.exposure;

import java.util.Locale;

import net.minecraft.util.Mth;

/**
 * The exposure triangle. Stops follow real camera scales. Exposure value uses the standard definition
 * EV = log2(N^2 / t) - log2(ISO / 100); the meter compares it with the scene's EV from {@link LightMeter}.
 */
public final class ExposureSettings {
	public enum Mode {
		MANUAL("M"), APERTURE_PRIORITY("A"), SHUTTER_PRIORITY("S"), PROGRAM("P");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	/** Shutter times in seconds, slowest first. 1/8000 and faster are an electronic shutter, so wide apertures work in sunlight. */
	public static final double[] SHUTTERS = {
		30, 15, 8, 4, 2, 1, 1 / 2.0, 1 / 4.0, 1 / 8.0, 1 / 15.0, 1 / 30.0, 1 / 60.0, 1 / 125.0, 1 / 250.0,
		1 / 500.0, 1 / 1000.0, 1 / 2000.0, 1 / 4000.0, 1 / 8000.0, 1 / 16000.0, 1 / 32000.0};
	public static final double[] APERTURES = {1.4, 1.8, 2, 2.8, 3.5, 4, 5.6, 6.3, 8, 11, 13, 16, 22};
	public static final int[] ISOS = {100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600};
	public static final int MAX_COMP_THIRDS = 9;

	private static Mode mode = Mode.APERTURE_PRIORITY;
	private static int shutter = 12;
	private static int aperture = 3;
	private static int iso = 0;
	private static int compThirds;

	private ExposureSettings() {
	}

	public static Mode mode() {
		return mode;
	}

	public static double shutterSeconds() {
		return SHUTTERS[shutter];
	}

	public static double fNumber() {
		return APERTURES[aperture];
	}

	public static int iso() {
		return ISOS[iso];
	}

	public static double compensation() {
		return compThirds / 3.0;
	}

	public static void cycleMode(int steps) {
		Mode[] modes = Mode.values();
		mode = modes[Math.floorMod(mode.ordinal() + steps, modes.length)];
	}

	/** Shutter and aperture are only adjustable when the mode doesn't control them. */
	public static boolean shutterIsAuto() {
		return mode == Mode.APERTURE_PRIORITY || mode == Mode.PROGRAM;
	}

	public static boolean apertureIsAuto() {
		return mode == Mode.SHUTTER_PRIORITY || mode == Mode.PROGRAM;
	}

	public static boolean compensationApplies() {
		return mode != Mode.MANUAL;
	}

	public static void stepShutter(int steps) {
		shutter = Mth.clamp(shutter + steps, 0, SHUTTERS.length - 1);
	}

	public static void stepAperture(int steps) {
		aperture = Mth.clamp(aperture + steps, 0, APERTURES.length - 1);
	}

	public static void stepIso(int steps) {
		iso = Mth.clamp(iso + steps, 0, ISOS.length - 1);
	}

	public static void stepCompensation(int steps) {
		compThirds = Mth.clamp(compThirds + steps, -MAX_COMP_THIRDS, MAX_COMP_THIRDS);
	}

	public static double settingsEv() {
		double n = fNumber();
		return log2(n * n / shutterSeconds()) - log2(iso() / 100.0);
	}

	/**
	 * Lets the auto modes pick the controlled values for the metered scene. Values snap to the nearest stop and
	 * clamp at the ends of the scale, so extreme scenes still end up over- or under-exposed.
	 */
	public static void autoExpose(double sceneEv) {
		double target = sceneEv - compensation() + log2(iso() / 100.0);
		switch (mode) {
			case APERTURE_PRIORITY -> shutter = nearestShutter(fNumber() * fNumber() / Math.pow(2, target));
			case SHUTTER_PRIORITY -> aperture = nearestAperture(Math.sqrt(shutterSeconds() * Math.pow(2, target)));
			case PROGRAM -> {
				// Simple program line: open up in the dark, stop down in bright light, then let the shutter follow.
				aperture = nearestAperture(Mth.clamp(Math.pow(2, (sceneEv - 6) / 2.0), 2.8, 11));
				shutter = nearestShutter(fNumber() * fNumber() / Math.pow(2, target));
				aperture = nearestAperture(Math.sqrt(shutterSeconds() * Math.pow(2, target)));
			}
			case MANUAL -> {
			}
		}
	}

	public static String modeLabel() {
		return mode.label;
	}

	public static String shutterLabel(int index) {
		double t = SHUTTERS[index];
		if (t >= 1) {
			return (int) t + "\"";
		}
		return "1/" + Math.round(1 / t);
	}

	public static String shutterLabel() {
		return shutterLabel(shutter);
	}

	public static String apertureLabel(int index) {
		double n = APERTURES[index];
		return n == Math.floor(n) ? "f/" + (int) n : String.format(Locale.ROOT, "f/%.1f", n);
	}

	public static String apertureLabel() {
		return apertureLabel(aperture);
	}

	public static String isoLabel(int index) {
		return String.valueOf(ISOS[index]);
	}

	public static String compLabel(int thirds) {
		if (thirds == 0) {
			return "±0";
		}
		return String.format(Locale.ROOT, "%+.1f", thirds / 3.0);
	}

	public static int shutterIndex() {
		return shutter;
	}

	public static int apertureIndex() {
		return aperture;
	}

	public static int isoIndex() {
		return iso;
	}

	public static int compThirds() {
		return compThirds;
	}

	private static int nearestShutter(double seconds) {
		int best = 0;
		for (int i = 1; i < SHUTTERS.length; i++) {
			if (Math.abs(log2(SHUTTERS[i] / seconds)) < Math.abs(log2(SHUTTERS[best] / seconds))) {
				best = i;
			}
		}
		return best;
	}

	private static int nearestAperture(double n) {
		int best = 0;
		for (int i = 1; i < APERTURES.length; i++) {
			if (Math.abs(log2(APERTURES[i] / n)) < Math.abs(log2(APERTURES[best] / n))) {
				best = i;
			}
		}
		return best;
	}

	static double log2(double v) {
		return Math.log(v) / Math.log(2);
	}
}
