package com.dslrcamera.client.editor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Photo edits, applied in a fixed order like a basic raw developer: exposure and white balance in linear light,
 * then levels, tone (highlights/shadows/contrast), colour (vibrance/saturation/hue), fade and sepia toning, and finally
 * sharpening, vignette and grain.
 */
public final class Adjustments {
	public enum Tab {
		LIGHT("Light"), COLOR("Color"), EFFECTS("Effects");

		public final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	public enum Param {
		EXPOSURE(Tab.LIGHT, "Exposure", -2, 2, 0, 0.05, "%+.2f EV"),
		CONTRAST(Tab.LIGHT, "Contrast", -100, 100, 0, 1, "%+.0f"),
		HIGHLIGHTS(Tab.LIGHT, "Highlights", -100, 100, 0, 1, "%+.0f"),
		SHADOWS(Tab.LIGHT, "Shadows", -100, 100, 0, 1, "%+.0f"),
		BLACK_POINT(Tab.LIGHT, "Levels: Black", 0, 100, 0, 1, "%.0f"),
		MIDTONES(Tab.LIGHT, "Levels: Midtones", 0.3, 3, 1, 0.01, "%.2f"),
		WHITE_POINT(Tab.LIGHT, "Levels: White", 155, 255, 255, 1, "%.0f"),
		TEMPERATURE(Tab.COLOR, "Temperature", -100, 100, 0, 1, "%+.0f"),
		TINT(Tab.COLOR, "Tint", -100, 100, 0, 1, "%+.0f"),
		SATURATION(Tab.COLOR, "Saturation", -100, 100, 0, 1, "%+.0f"),
		VIBRANCE(Tab.COLOR, "Vibrance", -100, 100, 0, 1, "%+.0f"),
		HUE(Tab.COLOR, "Hue", -180, 180, 0, 1, "%+.0f°"),
		SHARPEN(Tab.EFFECTS, "Sharpen", 0, 100, 0, 1, "%.0f"),
		VIGNETTE(Tab.EFFECTS, "Vignette", -100, 100, 0, 1, "%+.0f"),
		GRAIN(Tab.EFFECTS, "Grain", 0, 100, 0, 1, "%.0f"),
		FADE(Tab.EFFECTS, "Fade", 0, 100, 0, 1, "%.0f"),
		SEPIA(Tab.EFFECTS, "Sepia", 0, 100, 0, 1, "%.0f");

		public final Tab tab;
		public final String label;
		public final double min;
		public final double max;
		public final double defaultValue;
		public final double step;
		public final String format;

		Param(Tab tab, String label, double min, double max, double defaultValue, double step, String format) {
			this.tab = tab;
			this.label = label;
			this.min = min;
			this.max = max;
			this.defaultValue = defaultValue;
			this.step = step;
			this.format = format;
		}
	}

	public record Preset(String name, Map<Param, Double> values) {
	}

	public static final List<Preset> PRESETS = List.of(
		new Preset("B&W", Map.of(Param.SATURATION, -100.0, Param.CONTRAST, 15.0)),
		new Preset("Sepia", Map.of(Param.SATURATION, -100.0, Param.SEPIA, 85.0, Param.FADE, 10.0, Param.VIGNETTE, 25.0)),
		new Preset("Vintage", Map.of(Param.CONTRAST, -15.0, Param.SATURATION, -30.0, Param.TEMPERATURE, 35.0,
			Param.FADE, 25.0, Param.VIGNETTE, 40.0, Param.GRAIN, 30.0)),
		new Preset("Vivid", Map.of(Param.CONTRAST, 25.0, Param.SATURATION, 20.0, Param.VIBRANCE, 40.0, Param.SHARPEN, 25.0)),
		new Preset("Noir", Map.of(Param.SATURATION, -100.0, Param.CONTRAST, 55.0, Param.SHADOWS, -30.0,
			Param.VIGNETTE, 55.0, Param.GRAIN, 25.0)),
		new Preset("Warm", Map.of(Param.TEMPERATURE, 45.0, Param.VIBRANCE, 15.0)),
		new Preset("Cool", Map.of(Param.TEMPERATURE, -45.0, Param.TINT, -10.0)),
		new Preset("Dreamy", Map.of(Param.EXPOSURE, 0.3, Param.CONTRAST, -30.0, Param.HIGHLIGHTS, 30.0, Param.FADE, 20.0,
			Param.VIBRANCE, 20.0, Param.TEMPERATURE, 10.0)));

	private final EnumMap<Param, Double> values = new EnumMap<>(Param.class);

	public Adjustments() {
		reset();
	}

	public double get(Param param) {
		return values.get(param);
	}

	public void set(Param param, double value) {
		values.put(param, Math.clamp(value, param.min, param.max));
	}

	public void reset() {
		for (Param param : Param.values()) {
			values.put(param, param.defaultValue);
		}
	}

	public void apply(Preset preset) {
		reset();
		preset.values().forEach(this::set);
	}

	public boolean isDefault() {
		for (Param param : Param.values()) {
			if (values.get(param) != param.defaultValue) {
				return false;
			}
		}
		return true;
	}

	/** @return a new 0xRRGGBB image with all adjustments applied */
	public int[] process(int[] rgb, int width, int height) {
		int n = rgb.length;
		float[] r = new float[n];
		float[] g = new float[n];
		float[] b = new float[n];

		double exposureGain = Math.pow(2, get(Param.EXPOSURE));
		double temperature = get(Param.TEMPERATURE) / 100.0;
		double tint = get(Param.TINT) / 100.0;
		float wbR = (float) (exposureGain * (1 + 0.25 * temperature) * (1 + 0.08 * tint));
		float wbG = (float) (exposureGain * (1 - 0.18 * tint));
		float wbB = (float) (exposureGain * (1 - 0.25 * temperature) * (1 + 0.08 * tint));

		float black = (float) (get(Param.BLACK_POINT) / 255.0);
		float white = (float) (get(Param.WHITE_POINT) / 255.0);
		float inverseGamma = (float) (1.0 / get(Param.MIDTONES));
		float highlights = (float) (get(Param.HIGHLIGHTS) / 100.0);
		float shadows = (float) (get(Param.SHADOWS) / 100.0);
		float contrast = (float) (get(Param.CONTRAST) / 100.0);
		float saturation = (float) (1 + get(Param.SATURATION) / 100.0);
		float vibrance = (float) (get(Param.VIBRANCE) / 100.0);
		float fade = (float) (get(Param.FADE) / 100.0 * 0.25);
		float sepia = (float) (get(Param.SEPIA) / 100.0);
		float[] hue = hueRotation(Math.toRadians(get(Param.HUE)));

		for (int i = 0; i < n; i++) {
			int p = rgb[i];
			// Linear light for exposure and white balance, back to perceptual for everything after.
			float cr = toSrgb(toLinear((p >> 16) & 0xFF) * wbR);
			float cg = toSrgb(toLinear((p >> 8) & 0xFF) * wbG);
			float cb = toSrgb(toLinear(p & 0xFF) * wbB);

			cr = levels(cr, black, white, inverseGamma);
			cg = levels(cg, black, white, inverseGamma);
			cb = levels(cb, black, white, inverseGamma);

			float luma = luma(cr, cg, cb);
			float toneShift = tone(luma, highlights, shadows);
			cr = contrast(clamp(cr + toneShift), contrast);
			cg = contrast(clamp(cg + toneShift), contrast);
			cb = contrast(clamp(cb + toneShift), contrast);

			luma = luma(cr, cg, cb);
			float chroma = Math.max(cr, Math.max(cg, cb)) - Math.min(cr, Math.min(cg, cb));
			float satScale = saturation * (1 + vibrance * (1 - chroma));
			cr = luma + (cr - luma) * satScale;
			cg = luma + (cg - luma) * satScale;
			cb = luma + (cb - luma) * satScale;

			float hr = hue[0] * cr + hue[1] * cg + hue[2] * cb;
			float hg = hue[3] * cr + hue[4] * cg + hue[5] * cb;
			float hb = hue[6] * cr + hue[7] * cg + hue[8] * cb;
			cr = clamp(hr);
			cg = clamp(hg);
			cb = clamp(hb);

			if (sepia > 0) {
				float sr = 0.393F * cr + 0.769F * cg + 0.189F * cb;
				float sg = 0.349F * cr + 0.686F * cg + 0.168F * cb;
				float sb = 0.272F * cr + 0.534F * cg + 0.131F * cb;
				cr += (Math.min(1, sr) - cr) * sepia;
				cg += (Math.min(1, sg) - cg) * sepia;
				cb += (Math.min(1, sb) - cb) * sepia;
			}

			r[i] = fade + cr * (1 - fade);
			g[i] = fade + cg * (1 - fade);
			b[i] = fade + cb * (1 - fade);
		}

		float sharpen = (float) (get(Param.SHARPEN) / 100.0 * 1.5);
		if (sharpen > 0) {
			r = sharpen(r, width, height, sharpen);
			g = sharpen(g, width, height, sharpen);
			b = sharpen(b, width, height, sharpen);
		}

		float vignette = (float) (get(Param.VIGNETTE) / 100.0 * 0.8);
		float grain = (float) (get(Param.GRAIN) / 100.0 * 0.12);
		float aspect = (float) width / height;
		float maxRadius = (float) Math.sqrt(aspect * aspect + 1) * 0.5F;
		int[] out = new int[n];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = y * width + x;
				float cr = r[i];
				float cg = g[i];
				float cb = b[i];
				if (vignette != 0) {
					float dx = ((x + 0.5F) / width - 0.5F) * aspect;
					float dy = (y + 0.5F) / height - 0.5F;
					float d = (float) Math.sqrt(dx * dx + dy * dy) / maxRadius;
					float falloff = d * d * (3 - 2 * d);
					if (vignette > 0) {
						float scale = 1 - vignette * falloff;
						cr *= scale;
						cg *= scale;
						cb *= scale;
					} else {
						cr += (1 - cr) * -vignette * falloff;
						cg += (1 - cg) * -vignette * falloff;
						cb += (1 - cb) * -vignette * falloff;
					}
				}
				if (grain > 0) {
					// Fixed per-pixel noise so the grain doesn't shimmer while other sliders move.
					float noise = (hash(x, y) + hash(x + 7919, y + 104729) - 1) * grain;
					cr += noise;
					cg += noise;
					cb += noise;
				}
				out[i] = to8(cr) << 16 | to8(cg) << 8 | to8(cb);
			}
		}
		return out;
	}

	private static float levels(float v, float black, float white, float inverseGamma) {
		float t = clamp((v - black) / Math.max(white - black, 1e-3F));
		return inverseGamma == 1F ? t : (float) Math.pow(t, inverseGamma);
	}

	/** Brightens or darkens a luminance band; positive highlights brighten brights, positive shadows lift darks. */
	private static float tone(float luma, float highlights, float shadows) {
		float highMask = luma * luma;
		float shadowMask = (1 - luma) * (1 - luma);
		float shift = 0;
		shift += highlights * highMask * (highlights > 0 ? 1 - luma : luma) * 0.8F;
		shift += shadows * shadowMask * (shadows > 0 ? 1 - luma : luma) * 0.8F;
		return shift;
	}

	/** Smooth S-curve contrast that never clips: blends towards smoothstep for positive values. */
	private static float contrast(float v, float amount) {
		if (amount >= 0) {
			float s = v * v * (3 - 2 * v);
			return v + (s - v) * amount * 1.5F;
		}
		return 0.5F + (v - 0.5F) * (1 + amount * 0.7F);
	}

	/** Rotation about the grey axis, which shifts hue while keeping luminance roughly constant. */
	private static float[] hueRotation(double angle) {
		float c = (float) Math.cos(angle);
		float s = (float) Math.sin(angle);
		float k = 1 / 3F;
		float sq = (float) Math.sqrt(k);
		return new float[] {
			c + (1 - c) * k, k * (1 - c) - sq * s, k * (1 - c) + sq * s,
			k * (1 - c) + sq * s, c + k * (1 - c), k * (1 - c) - sq * s,
			k * (1 - c) - sq * s, k * (1 - c) + sq * s, c + k * (1 - c)};
	}

	private static float[] sharpen(float[] channel, int width, int height, float amount) {
		float[] out = new float[channel.length];
		for (int y = 0; y < height; y++) {
			int y0 = Math.max(0, y - 1);
			int y1 = Math.min(height - 1, y + 1);
			for (int x = 0; x < width; x++) {
				int x0 = Math.max(0, x - 1);
				int x1 = Math.min(width - 1, x + 1);
				float centre = channel[y * width + x];
				float blur = (channel[y0 * width + x] + channel[y1 * width + x] + channel[y * width + x0] + channel[y * width + x1]) * 0.25F;
				out[y * width + x] = centre + (centre - blur) * amount;
			}
		}
		return out;
	}

	private static float hash(int x, int y) {
		int h = x * 374761393 + y * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
	}

	private static float luma(float r, float g, float b) {
		return 0.2126F * r + 0.7152F * g + 0.0722F * b;
	}

	private static final float[] LINEAR = new float[256];
	private static final int SRGB_STEPS = 4096;
	private static final float[] SRGB = new float[SRGB_STEPS + 1];

	static {
		for (int i = 0; i < 256; i++) {
			float v = i / 255F;
			LINEAR[i] = v <= 0.04045F ? v / 12.92F : (float) Math.pow((v + 0.055F) / 1.055F, 2.4F);
		}
		for (int i = 0; i <= SRGB_STEPS; i++) {
			float v = (float) i / SRGB_STEPS;
			SRGB[i] = v <= 0.0031308F ? v * 12.92F : (float) (1.055F * Math.pow(v, 1 / 2.4F) - 0.055F);
		}
	}

	private static float toLinear(int v8) {
		return LINEAR[v8];
	}

	private static float toSrgb(float v) {
		return SRGB[(int) (clamp(v) * SRGB_STEPS + 0.5F)];
	}

	private static float clamp(float v) {
		return v < 0 ? 0 : Math.min(v, 1);
	}

	private static int to8(float v) {
		return Math.round(clamp(v) * 255);
	}
}
