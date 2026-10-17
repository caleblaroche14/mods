package com.dslrcamera.client.photo;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.dslrcamera.client.CameraState;
import com.dslrcamera.client.DslrCameraClient;
import com.dslrcamera.client.PrintSize;
import com.dslrcamera.client.exposure.ExposureSettings;
import com.dslrcamera.client.exposure.Flash;
import com.dslrcamera.client.net.PhotoPayload;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

/**
 * Captures the world framebuffer, saves the full-resolution photo, and sends a dithered copy split into 128x128 map
 * tiles (plus a single-map preview for multi-tile photos) to be printed by the server plugin.
 *
 * <p>Shutter speeds slower than {@value #SINGLE_FRAME_LIMIT_S}s are real long exposures: frames are read back and
 * averaged for the whole exposure time, so anything moving (including the camera) blurs.
 */
public final class PhotoCapture {
	private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
	private static final int TILE = PhotoTransfer.TILE;
	private static final double SINGLE_FRAME_LIMIT_S = 1 / 30.0;
	private static final int MAX_EXPOSURE_SAMPLES = 120;

	private record Shot(String caption, PrintSize size) {
	}

	private static Shot pendingShot;
	private static Exposure exposure;

	private PhotoCapture() {
	}

	/** An in-progress long exposure. Pixel sums are only touched on the IO pool, under the object's lock. */
	private static final class Exposure {
		final String caption;
		final PrintSize size;
		final long startMs = System.currentTimeMillis();
		final long durationMs;
		final long sampleIntervalMs;
		long lastSampleMs = -1;
		int width = -1;
		int height = -1;
		long[] sums;
		int samples;
		int pending;
		boolean sampling = true;
		boolean cancelled;

		Exposure(String caption, PrintSize size, double seconds) {
			this.caption = caption;
			this.size = size;
			this.durationMs = Math.round(seconds * 1000);
			this.sampleIntervalMs = durationMs / MAX_EXPOSURE_SAMPLES;
		}
	}

	public static boolean isExposing() {
		return exposure != null;
	}

	/** Remaining exposure time in seconds, for the viewfinder countdown. */
	public static double remainingSeconds() {
		Exposure current = exposure;
		return current == null ? 0 : Math.max(0, (current.startMs + current.durationMs - System.currentTimeMillis()) / 1000.0);
	}

	/** Called on the render thread when the shutter fires. */
	public static void begin(String caption, PrintSize size) {
		if (exposure != null) {
			return;
		}
		CameraState.flash();
		if (Flash.fire() && PhotoTransfer.canSend()) {
			ClientPlayNetworking.send(PhotoPayload.flash());
		}
		double seconds = ExposureSettings.shutterSeconds();
		if (seconds <= SINGLE_FRAME_LIMIT_S) {
			pendingShot = new Shot(caption, size);
			return;
		}
		exposure = new Exposure(caption, size, seconds);
	}

	/** Called on the render thread every frame the viewfinder is active, after the world has been drawn. */
	public static void onFrame(RenderTarget target) {
		if (pendingShot != null) {
			Shot shot = pendingShot;
			pendingShot = null;
			Screenshot.takeScreenshot(target, image -> {
				int width = image.getWidth();
				int height = image.getHeight();
				int[] abgr = image.getPixelsABGR();
				image.close();
				Util.ioPool().execute(() -> process(abgr, width, height, shot.caption(), shot.size()));
			});
			return;
		}

		Exposure current = exposure;
		if (current == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (current.sampling && now - current.lastSampleMs >= current.sampleIntervalMs) {
			current.lastSampleMs = now;
			synchronized (current) {
				current.pending++;
			}
			Screenshot.takeScreenshot(target, image -> {
				int width = image.getWidth();
				int height = image.getHeight();
				int[] abgr = image.getPixelsABGR();
				image.close();
				Util.ioPool().execute(() -> accumulate(current, abgr, width, height));
			});
		}
		if (current.sampling && now >= current.startMs + current.durationMs) {
			current.sampling = false;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2F));
			Util.ioPool().execute(() -> finishIfReady(current));
		}
	}

	public static void cancel(String reason) {
		Exposure current = exposure;
		if (current == null) {
			return;
		}
		synchronized (current) {
			current.cancelled = true;
		}
		exposure = null;
		Minecraft minecraft = Minecraft.getInstance();
		notify(minecraft, Component.literal("Exposure cancelled (" + reason + ")").withStyle(ChatFormatting.RED));
	}

	private static void accumulate(Exposure current, int[] abgr, int width, int height) {
		synchronized (current) {
			current.pending--;
			if (current.cancelled) {
				return;
			}
			if (current.sums == null) {
				current.width = width;
				current.height = height;
				current.sums = new long[width * height * 3];
			}
			if (width == current.width && height == current.height) {
				long[] sums = current.sums;
				for (int i = 0, j = 0; i < abgr.length; i++, j += 3) {
					int p = abgr[i];
					sums[j] += p & 0xFF;
					sums[j + 1] += (p >> 8) & 0xFF;
					sums[j + 2] += (p >> 16) & 0xFF;
				}
				current.samples++;
			}
		}
		finishIfReady(current);
	}

	private static void finishIfReady(Exposure current) {
		int[] averaged;
		synchronized (current) {
			if (current.cancelled || current.sampling || current.pending > 0 || current.sums == null) {
				return;
			}
			current.cancelled = true;
			long[] sums = current.sums;
			int n = Math.max(1, current.samples);
			averaged = new int[current.width * current.height];
			for (int i = 0, j = 0; i < averaged.length; i++, j += 3) {
				averaged[i] = 0xFF000000 | (int) (sums[j + 2] / n) << 16 | (int) (sums[j + 1] / n) << 8 | (int) (sums[j] / n);
			}
		}
		Minecraft.getInstance().execute(() -> {
			if (exposure == current) {
				exposure = null;
			}
		});
		DslrCameraClient.LOGGER.info("Long exposure finished: {} frames over {}ms", current.samples, current.durationMs);
		process(averaged, current.width, current.height, current.caption, current.size);
	}

	private static void process(int[] abgr, int imageWidth, int imageHeight, String caption, PrintSize size) {
		Minecraft minecraft = Minecraft.getInstance();
		File photo;
		PhotoTransfer.Print print;
		try {
			File dir = new File(minecraft.gameDirectory, "dslr_photos");
			dir.mkdirs();
			photo = uniqueFile(dir, LocalDateTime.now().format(FILE_NAME), ".png");
			try (NativeImage image = new NativeImage(imageWidth, imageHeight, false)) {
				for (int y = 0; y < imageHeight; y++) {
					for (int x = 0; x < imageWidth; x++) {
						image.setPixelABGR(x, y, abgr[y * imageWidth + x] | 0xFF000000);
					}
				}
				image.writeToFile(photo);
			}

			int width = TILE * size.cols();
			int height = TILE * size.rows();
			int[] rgb = downscale(abgr, imageWidth, cropRect(imageWidth, imageHeight, size.cols(), size.rows()), width, height);
			print = PhotoTransfer.prepare(rgb, size, caption, 0);
			writePreviewPng(print.mapColors(), width, height, new File(dir, photo.getName().replace(".png", "_map.png")));
		} catch (Exception e) {
			DslrCameraClient.LOGGER.error("Failed to process photo", e);
			minecraft.execute(() -> notify(minecraft, Component.literal("Photo failed: " + e.getMessage()).withStyle(ChatFormatting.RED)));
			return;
		}
		minecraft.execute(() -> deliver(minecraft, photo, print));
	}

	private static void deliver(Minecraft minecraft, File photo, PhotoTransfer.Print print) {
		Component fileLink = Component.literal(photo.getName())
			.withStyle(ChatFormatting.UNDERLINE)
			.withStyle(s -> s.withClickEvent(new ClickEvent.OpenFile(photo.getAbsoluteFile())));
		if (PhotoTransfer.canSend()) {
			PhotoTransfer.send(print);
			notify(minecraft, Component.literal("Saved ").append(fileLink).append(" and sent a " + print.size().label() + " print to the printer"));
		} else {
			notify(minecraft, Component.literal("Saved ").append(fileLink)
				.append(Component.literal(" (this server can't print photos)").withStyle(ChatFormatting.GRAY)));
		}
	}

	private static void notify(Minecraft minecraft, Component message) {
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(message);
		}
	}

	/** Largest centred rectangle with the print's aspect ratio: {x, y, width, height}. */
	static int[] cropRect(int width, int height, int cols, int rows) {
		int cropW = width;
		int cropH = (int) ((long) width * rows / cols);
		if (cropH > height) {
			cropH = height;
			cropW = (int) ((long) height * cols / rows);
		}
		return new int[] {(width - cropW) / 2, (height - cropH) / 2, cropW, cropH};
	}

	/** Box-filters the crop rectangle of an ABGR image down to outW x outH, returning 0xRRGGBB pixels. */
	static int[] downscale(int[] abgr, int stride, int[] crop, int outW, int outH) {
		int[] out = new int[outW * outH];
		for (int oy = 0; oy < outH; oy++) {
			int sy0 = crop[1] + (int) ((long) oy * crop[3] / outH);
			int sy1 = Math.max(sy0 + 1, crop[1] + (int) ((long) (oy + 1) * crop[3] / outH));
			for (int ox = 0; ox < outW; ox++) {
				int sx0 = crop[0] + (int) ((long) ox * crop[2] / outW);
				int sx1 = Math.max(sx0 + 1, crop[0] + (int) ((long) (ox + 1) * crop[2] / outW));
				long r = 0, g = 0, b = 0;
				for (int sy = sy0; sy < sy1; sy++) {
					int row = sy * stride;
					for (int sx = sx0; sx < sx1; sx++) {
						int p = abgr[row + sx];
						r += p & 0xFF;
						g += (p >> 8) & 0xFF;
						b += (p >> 16) & 0xFF;
					}
				}
				long n = (long) (sy1 - sy0) * (sx1 - sx0);
				out[oy * outW + ox] = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
			}
		}
		return out;
	}

	private static void writePreviewPng(byte[] colors, int width, int height, File file) throws IOException {
		try (NativeImage preview = new NativeImage(width, height, false)) {
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					preview.setPixel(x, y, MapQuantizer.toArgb(colors[y * width + x]));
				}
			}
			preview.writeToFile(file);
		}
	}

	private static File uniqueFile(File dir, String base, String ext) {
		File file = new File(dir, base + ext);
		for (int i = 1; file.exists(); i++) {
			file = new File(dir, base + "_" + i + ext);
		}
		return file;
	}
}
