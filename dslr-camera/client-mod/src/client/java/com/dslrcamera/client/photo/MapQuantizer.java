package com.dslrcamera.client.photo;

import net.minecraft.world.level.material.MapColor;

/**
 * Converts an RGB image to Minecraft's packed map colour ids. Matches colours in OKLab (perceptual) space and uses
 * serpentine Floyd-Steinberg dithering with damped error, which avoids the saturated speckles plain RGB matching
 * produces in pale skies.
 */
public final class MapQuantizer {
	private static final float ERROR_DIFFUSION = 0.75F;
	private static final int[] PALETTE_RGB;
	private static final byte[] PALETTE_IDS;
	private static final float[][] PALETTE_LAB;
	/** Nearest-palette cache over 6 bits per channel; entries are filled lazily (races are benign, values are idempotent). */
	private static final int[] NEAREST_CACHE = new int[1 << 18];

	static {
		int[] rgb = new int[256];
		byte[] ids = new byte[256];
		int count = 0;
		for (int colorId = 1; colorId < 64; colorId++) {
			MapColor color = MapColor.byId(colorId);
			if (color.id != colorId || color.col == 0) {
				continue;
			}
			for (int brightness = 0; brightness < 4; brightness++) {
				MapColor.Brightness b = MapColor.Brightness.byId(brightness);
				rgb[count] = color.calculateARGBColor(b) & 0xFFFFFF;
				ids[count] = color.getPackedId(b);
				count++;
			}
		}
		PALETTE_RGB = java.util.Arrays.copyOf(rgb, count);
		PALETTE_IDS = java.util.Arrays.copyOf(ids, count);
		java.util.Arrays.fill(NEAREST_CACHE, -1);
		PALETTE_LAB = new float[count][];
		for (int i = 0; i < count; i++) {
			int p = PALETTE_RGB[i];
			PALETTE_LAB[i] = oklab((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
		}
	}

	private MapQuantizer() {
	}

	/**
	 * @param rgb    width*height pixels as 0xRRGGBB
	 * @return width*height packed map colour ids
	 */
	public static byte[] quantize(int[] rgb, int width, int height) {
		float[] r = new float[rgb.length];
		float[] g = new float[rgb.length];
		float[] b = new float[rgb.length];
		for (int i = 0; i < rgb.length; i++) {
			r[i] = (rgb[i] >> 16) & 0xFF;
			g[i] = (rgb[i] >> 8) & 0xFF;
			b[i] = rgb[i] & 0xFF;
		}

		byte[] out = new byte[rgb.length];
		for (int y = 0; y < height; y++) {
			boolean leftToRight = (y & 1) == 0;
			int dir = leftToRight ? 1 : -1;
			for (int step = 0; step < width; step++) {
				int x = leftToRight ? step : width - 1 - step;
				int i = y * width + x;
				float cr = clamp(r[i]);
				float cg = clamp(g[i]);
				float cb = clamp(b[i]);

				int best = nearest(cr, cg, cb);
				out[i] = PALETTE_IDS[best];
				int p = PALETTE_RGB[best];
				float er = (cr - ((p >> 16) & 0xFF)) * ERROR_DIFFUSION;
				float eg = (cg - ((p >> 8) & 0xFF)) * ERROR_DIFFUSION;
				float eb = (cb - (p & 0xFF)) * ERROR_DIFFUSION;

				spread(r, g, b, width, height, x + dir, y, er, eg, eb, 7 / 16F);
				spread(r, g, b, width, height, x - dir, y + 1, er, eg, eb, 3 / 16F);
				spread(r, g, b, width, height, x, y + 1, er, eg, eb, 5 / 16F);
				spread(r, g, b, width, height, x + dir, y + 1, er, eg, eb, 1 / 16F);
			}
		}
		return out;
	}

	/** Resolves packed map colour ids back to 0xAARRGGBB, for local previews. */
	public static int toArgb(byte packedId) {
		return MapColor.getColorFromPackedId(packedId);
	}

	private static void spread(float[] r, float[] g, float[] b, int width, int height, int x, int y, float er, float eg, float eb, float w) {
		if (x < 0 || x >= width || y >= height) {
			return;
		}
		int i = y * width + x;
		r[i] += er * w;
		g[i] += eg * w;
		b[i] += eb * w;
	}

	private static float clamp(float v) {
		return v < 0 ? 0 : Math.min(v, 255);
	}

	private static int nearest(float r, float g, float b) {
		int key = ((int) r >> 2) << 12 | ((int) g >> 2) << 6 | ((int) b >> 2);
		int cached = NEAREST_CACHE[key];
		if (cached < 0) {
			cached = searchNearest(((int) r & ~3) + 2, ((int) g & ~3) + 2, ((int) b & ~3) + 2);
			NEAREST_CACHE[key] = cached;
		}
		return cached;
	}

	private static int searchNearest(float r, float g, float b) {
		float[] lab = oklab(r, g, b);
		int best = 0;
		float bestDist = Float.MAX_VALUE;
		for (int i = 0; i < PALETTE_LAB.length; i++) {
			float[] p = PALETTE_LAB[i];
			float dl = lab[0] - p[0];
			float da = lab[1] - p[1];
			float db = lab[2] - p[2];
			float dist = dl * dl + da * da + db * db;
			if (dist < bestDist) {
				bestDist = dist;
				best = i;
			}
		}
		return best;
	}

	private static float[] oklab(float r8, float g8, float b8) {
		double r = toLinear(r8 / 255.0);
		double g = toLinear(g8 / 255.0);
		double b = toLinear(b8 / 255.0);
		double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
		double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
		double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
		return new float[] {
			(float) (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s),
			(float) (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s),
			(float) (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
		};
	}

	private static double toLinear(double c) {
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}
}
