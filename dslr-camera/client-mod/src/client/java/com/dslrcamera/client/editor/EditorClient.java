package com.dslrcamera.client.editor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import com.dslrcamera.client.DslrCameraClient;
import com.dslrcamera.client.PrintSize;
import com.dslrcamera.client.net.EditorPayload;
import com.dslrcamera.client.photo.MapQuantizer;
import com.dslrcamera.client.photo.PhotoTransfer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;

/** Receives a photo from the server when the player opens the editor at a cartography table, then opens the screen. */
public final class EditorClient {
	private static final int MAX_DATA = 4 * 1024 * 1024;

	private static Incoming incoming;

	private EditorClient() {
	}

	private static final class Incoming {
		final int session;
		final PrintSize size;
		final int format;
		final String caption;
		final byte[] data;
		final boolean[] received;
		final int cost;
		final String costItem;
		int count;

		Incoming(int session, PrintSize size, int format, String caption, int length, int chunks, int cost, String costItem) {
			this.session = session;
			this.size = size;
			this.format = format;
			this.caption = caption;
			this.data = new byte[length];
			this.received = new boolean[chunks];
			this.cost = cost;
			this.costItem = costItem;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(EditorPayload.TYPE, EditorPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(EditorPayload.TYPE, (payload, context) -> handle(context.client(), payload.data()));
	}

	private static void handle(Minecraft minecraft, byte[] message) {
		ByteBuffer buf = ByteBuffer.wrap(message);
		if (buf.remaining() < 6 || buf.get() != 3) {
			return;
		}
		int kind = buf.get();
		int session = buf.getInt();
		if (kind == EditorPayload.KIND_OPEN) {
			int cols = buf.get() & 0xFF;
			int rows = buf.get() & 0xFF;
			int format = buf.get() & 0xFF;
			String caption = readString(buf);
			int length = buf.getInt();
			int chunks = buf.get() & 0xFF;
			int cost = buf.getInt();
			String costItem = readString(buf);
			if (cols < 1 || rows < 1 || cols > 8 || rows > 8 || length <= 0 || length > MAX_DATA || chunks < 1) {
				return;
			}
			incoming = new Incoming(session, new PrintSize(cols, rows), format, caption, length, chunks, cost, costItem);
		} else if (kind == EditorPayload.KIND_RESULT) {
			boolean success = buf.get() != 0;
			String text = readString(buf);
			if (minecraft.gui.screen() instanceof EditorScreen editor && editor.session() == session) {
				editor.onPrintResult(success, text);
			}
		} else if (kind == EditorPayload.KIND_CHUNK) {
			Incoming current = incoming;
			if (current == null || current.session != session) {
				return;
			}
			int index = buf.get() & 0xFF;
			int offset = index * 30_000;
			if (index >= current.received.length || current.received[index] || offset + buf.remaining() > current.data.length) {
				return;
			}
			buf.get(current.data, offset, buf.remaining());
			current.received[index] = true;
			if (++current.count == current.received.length) {
				incoming = null;
				open(minecraft, current);
			}
		}
	}

	private static void open(Minecraft minecraft, Incoming photo) {
		int width = PhotoTransfer.TILE * photo.size.cols();
		int height = PhotoTransfer.TILE * photo.size.rows();
		int[] rgb;
		try {
			rgb = photo.format == EditorPayload.FORMAT_RGB
				? rgbFromBytes(inflate(photo.data, width * height * 3))
				: rgbFromTiles(inflate(photo.data, photo.size.tiles() * PhotoTransfer.TILE * PhotoTransfer.TILE), photo.size);
		} catch (DataFormatException | IllegalArgumentException e) {
			DslrCameraClient.LOGGER.warn("Couldn't open photo for editing", e);
			return;
		}
		minecraft.gui.setScreen(new EditorScreen(photo.session, photo.size, photo.caption, rgb, photo.cost, photo.costItem));
	}

	private static byte[] inflate(byte[] data, int expected) throws DataFormatException {
		Inflater inflater = new Inflater();
		inflater.setInput(data);
		ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
		byte[] buffer = new byte[16384];
		while (!inflater.finished() && out.size() <= expected) {
			int n = inflater.inflate(buffer);
			if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
				break;
			}
			out.write(buffer, 0, n);
		}
		inflater.end();
		if (out.size() != expected) {
			throw new IllegalArgumentException("Photo data has the wrong size: " + out.size() + " != " + expected);
		}
		return out.toByteArray();
	}

	private static int[] rgbFromBytes(byte[] raw) {
		int[] rgb = new int[raw.length / 3];
		for (int i = 0, j = 0; i < rgb.length; i++, j += 3) {
			rgb[i] = (raw[j] & 0xFF) << 16 | (raw[j + 1] & 0xFF) << 8 | (raw[j + 2] & 0xFF);
		}
		return rgb;
	}

	/** Photos printed before sources were stored only exist as map colours; rebuild an RGB image from the tiles. */
	private static int[] rgbFromTiles(byte[] tiles, PrintSize size) {
		int tile = PhotoTransfer.TILE;
		int width = tile * size.cols();
		int[] rgb = new int[width * tile * size.rows()];
		for (int t = 0; t < size.tiles(); t++) {
			int col = t % size.cols();
			int row = t / size.cols();
			for (int y = 0; y < tile; y++) {
				for (int x = 0; x < tile; x++) {
					int argb = MapQuantizer.toArgb(tiles[t * tile * tile + y * tile + x]);
					rgb[(row * tile + y) * width + col * tile + x] = argb & 0xFFFFFF;
				}
			}
		}
		return rgb;
	}

	private static String readString(ByteBuffer buf) {
		int length = buf.getShort() & 0xFFFF;
		byte[] bytes = new byte[Math.min(length, buf.remaining())];
		buf.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
