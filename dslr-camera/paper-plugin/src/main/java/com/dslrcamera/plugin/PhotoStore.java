package com.dslrcamera.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

/**
 * Persists photo pixels per map id. Bukkit does not save custom renderers, so without this printed photos
 * would turn into blank maps after a restart.
 */
final class PhotoStore implements Listener {
	static final int SIZE = 128;

	private final DslrCameraPlugin plugin;
	private final Path dir;
	private final Path sourceDir;

	PhotoStore(DslrCameraPlugin plugin) {
		this.plugin = plugin;
		this.dir = plugin.getDataFolder().toPath().resolve("photos");
		this.sourceDir = plugin.getDataFolder().toPath().resolve("sources");
	}

	/** Map colours for a photo map, or null if the map isn't a DSLR photo. */
	byte[] readPixels(int mapId) {
		try {
			Path file = dir.resolve(mapId + ".bin");
			byte[] pixels = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
			return pixels != null && pixels.length == SIZE * SIZE ? pixels : null;
		} catch (IOException e) {
			return null;
		}
	}

	boolean isPhotoMap(int mapId) {
		return Files.isRegularFile(dir.resolve(mapId + ".bin"));
	}

	/** Saves the deflated full-colour image a photo was printed from, so it can be edited later. */
	void saveSource(UUID photoId, byte[] deflatedRgb) {
		plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
			try {
				Files.createDirectories(sourceDir);
				Files.write(sourceDir.resolve(photoId + ".bin"), deflatedRgb);
			} catch (IOException e) {
				plugin.getLogger().log(Level.SEVERE, "Could not save source for photo " + photoId, e);
			}
		});
	}

	byte[] readSource(UUID photoId) {
		try {
			Path file = sourceDir.resolve(photoId + ".bin");
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** Creates a new locked map showing the given pixels and persists them. */
	MapView create(World world, byte[] pixels) {
		MapView view = plugin.getServer().createMap(world);
		view.setScale(MapView.Scale.CLOSEST);
		save(view, pixels);
		return view;
	}

	private void save(MapView view, byte[] pixels) {
		attach(view, pixels);
		plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
			try {
				Files.createDirectories(dir);
				Files.write(dir.resolve(view.getId() + ".bin"), pixels);
			} catch (IOException e) {
				plugin.getLogger().log(Level.SEVERE, "Could not save photo for map " + view.getId(), e);
			}
		});
	}

	@EventHandler
	public void onMapInit(MapInitializeEvent event) {
		MapView view = event.getMap();
		Path file = dir.resolve(view.getId() + ".bin");
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			byte[] pixels = Files.readAllBytes(file);
			if (pixels.length == SIZE * SIZE) {
				attach(view, pixels);
			}
		} catch (IOException e) {
			plugin.getLogger().log(Level.WARNING, "Could not load photo for map " + view.getId(), e);
		}
	}

	static void attach(MapView view, byte[] pixels) {
		view.getRenderers().forEach(view::removeRenderer);
		view.addRenderer(new PhotoRenderer(pixels));
		view.setTrackingPosition(false);
		view.setUnlimitedTracking(false);
		view.setLocked(true);
	}

	private static final class PhotoRenderer extends MapRenderer {
		private final byte[] pixels;
		private boolean drawn;

		PhotoRenderer(byte[] pixels) {
			super(false);
			this.pixels = pixels;
		}

		@Override
		@SuppressWarnings("deprecation")
		public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
			// Non-contextual canvases are shared by all players, so the image only needs drawing once.
			if (drawn) {
				return;
			}
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++) {
					canvas.setPixel(x, y, pixels[y * SIZE + x]);
				}
			}
			drawn = true;
		}
	}
}
