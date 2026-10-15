package com.dslrcamera.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import com.dslrcamera.client.DslrCameraClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

/** User-tunable rendering settings, saved to {@code config/dslrcamera.json}. */
public final class DslrConfig {
	public enum Option {
		DOF_STRENGTH("dofStrength", "Blur strength", "%.1fx", 0.0, 6.0, 0.1, 2.5,
			"Multiplies the real lens blur. 1.0x is physically accurate; 0 turns depth of field off."),
		MAX_BLUR("maxBlur", "Max blur size", "%.0f px", 16.0, 160.0, 1.0, 80.0,
			"Largest blur radius, in pixels at 1080p. Higher allows creamier backgrounds but costs performance."),
		HIGHLIGHT_THRESHOLD("highlightThreshold", "Bokeh threshold", "%.2f", 0.3, 0.95, 0.01, 0.7,
			"Brightness above which out-of-focus points become bokeh discs. Lower = more discs."),
		HIGHLIGHT_BOOST("highlightBoost", "Bokeh intensity", "%.1f", 0.0, 20.0, 0.5, 8.0,
			"How strongly bright points bloom into discs. 0 gives a plain, even blur.");

		public final String key;
		public final String label;
		public final String format;
		public final double min;
		public final double max;
		public final double step;
		public final double defaultValue;
		public final String description;

		Option(String key, String label, String format, double min, double max, double step, double defaultValue, String description) {
			this.key = key;
			this.label = label;
			this.format = format;
			this.min = min;
			this.max = max;
			this.step = step;
			this.defaultValue = defaultValue;
			this.description = description;
		}

		public double clamp(double value) {
			return Math.clamp(value, min, max);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<Option, Double> VALUES = new EnumMap<>(Option.class);

	static {
		reset();
	}

	private DslrConfig() {
	}

	public static double get(Option option) {
		return VALUES.get(option);
	}

	public static float getFloat(Option option) {
		return (float) get(option);
	}

	public static void set(Option option, double value) {
		VALUES.put(option, option.clamp(value));
	}

	public static void reset() {
		for (Option option : Option.values()) {
			VALUES.put(option, option.defaultValue);
		}
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(DslrCameraClient.MOD_ID + ".json");
	}

	public static void load() {
		Path path = path();
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			JsonObject json = GSON.fromJson(reader, JsonObject.class);
			if (json == null) {
				return;
			}
			for (Option option : Option.values()) {
				JsonElement element = json.get(option.key);
				if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
					set(option, element.getAsDouble());
				}
			}
		} catch (IOException | RuntimeException e) {
			DslrCameraClient.LOGGER.warn("Could not read {}, using defaults", path, e);
		}
	}

	public static void save() {
		Path path = path();
		JsonObject json = new JsonObject();
		for (Option option : Option.values()) {
			json.addProperty(option.key, get(option));
		}
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(json, writer);
			}
		} catch (IOException e) {
			DslrCameraClient.LOGGER.warn("Could not save {}", path, e);
		}
	}
}
