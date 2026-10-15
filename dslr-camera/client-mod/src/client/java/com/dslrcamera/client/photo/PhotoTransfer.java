package com.dslrcamera.client.photo;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.Deflater;

import com.dslrcamera.client.PrintSize;
import com.dslrcamera.client.net.PhotoPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Turns a finished RGB image into a print request: dithered map tiles, a single-map preview for multi-tile photos,
 * and the full-colour image itself (deflated) so the photo can be edited later at full quality.
 */
public final class PhotoTransfer {
	public static final int TILE = PhotoPayload.MAP_SIZE;

	private PhotoTransfer() {
	}

	/** Everything needed to send a print; build it off-thread, send it on the client thread. */
	public record Print(PrintSize size, String caption, int editSession, byte[] mapColors, byte[][] tiles, byte[] preview, byte[] source) {
	}

	public static boolean canSend() {
		return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(PhotoPayload.TYPE);
	}

	/**
	 * @param rgb image of exactly (128 * cols) x (128 * rows) pixels as 0xRRGGBB
	 */
	public static Print prepare(int[] rgb, PrintSize size, String caption, int editSession) {
		int width = TILE * size.cols();
		int height = TILE * size.rows();
		byte[] colors = MapQuantizer.quantize(rgb, width, height);
		byte[][] tiles = slice(colors, size);
		byte[] preview = size.tiles() > 1 ? letterboxedPreview(rgb, width, height) : null;
		return new Print(size, caption, editSession, colors, tiles, preview, deflate(rgb));
	}

	public static void send(Print print) {
		int transferId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
		int chunks = (print.source().length + PhotoPayload.CHUNK_SIZE - 1) / PhotoPayload.CHUNK_SIZE;
		ClientPlayNetworking.send(PhotoPayload.begin(transferId, print.size().cols(), print.size().rows(), print.editSession(),
			print.caption(), print.source().length, chunks));
		if (print.preview() != null) {
			ClientPlayNetworking.send(PhotoPayload.preview(transferId, print.preview()));
		}
		for (int i = 0; i < print.tiles().length; i++) {
			ClientPlayNetworking.send(PhotoPayload.tile(transferId, i, print.tiles()[i]));
		}
		for (int i = 0; i < chunks; i++) {
			int from = i * PhotoPayload.CHUNK_SIZE;
			int to = Math.min(print.source().length, from + PhotoPayload.CHUNK_SIZE);
			ClientPlayNetworking.send(PhotoPayload.sourceChunk(transferId, i, Arrays.copyOfRange(print.source(), from, to)));
		}
	}

	/** Packs 0xRRGGBB pixels as R,G,B bytes and deflates them. */
	static byte[] deflate(int[] rgb) {
		byte[] raw = new byte[rgb.length * 3];
		for (int i = 0, j = 0; i < rgb.length; i++, j += 3) {
			raw[j] = (byte) (rgb[i] >> 16);
			raw[j + 1] = (byte) (rgb[i] >> 8);
			raw[j + 2] = (byte) rgb[i];
		}
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
		byte[] buffer = new byte[16384];
		while (!deflater.finished()) {
			out.write(buffer, 0, deflater.deflate(buffer));
		}
		deflater.end();
		return out.toByteArray();
	}

	private static byte[][] slice(byte[] colors, PrintSize size) {
		int width = TILE * size.cols();
		byte[][] tiles = new byte[size.tiles()][TILE * TILE];
		for (int row = 0; row < size.rows(); row++) {
			for (int col = 0; col < size.cols(); col++) {
				byte[] tile = tiles[row * size.cols() + col];
				for (int y = 0; y < TILE; y++) {
					System.arraycopy(colors, (row * TILE + y) * width + col * TILE, tile, y * TILE, TILE);
				}
			}
		}
		return tiles;
	}

	/** The whole photo shrunk into one map, centred with transparent bars. */
	private static byte[] letterboxedPreview(int[] rgb, int width, int height) {
		int pw = width >= height ? TILE : TILE * width / height;
		int ph = height >= width ? TILE : TILE * height / width;
		int[] small = PhotoCapture.downscale(rgbToAbgr(rgb), width, new int[] {0, 0, width, height}, pw, ph);
		byte[] quantized = MapQuantizer.quantize(small, pw, ph);
		byte[] out = new byte[TILE * TILE];
		int x0 = (TILE - pw) / 2;
		int y0 = (TILE - ph) / 2;
		for (int y = 0; y < ph; y++) {
			System.arraycopy(quantized, y * pw, out, (y0 + y) * TILE + x0, pw);
		}
		return out;
	}

	private static int[] rgbToAbgr(int[] rgb) {
		int[] abgr = new int[rgb.length];
		for (int i = 0; i < rgb.length; i++) {
			int p = rgb[i];
			abgr[i] = 0xFF000000 | (p & 0xFF) << 16 | (p & 0xFF00) | (p >> 16) & 0xFF;
		}
		return abgr;
	}
}
