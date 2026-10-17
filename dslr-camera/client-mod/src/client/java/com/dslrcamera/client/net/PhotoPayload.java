package com.dslrcamera.client.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server messages on the {@code dslrcamera:photo} plugin channel. Every message starts with
 * u8 version, u8 kind, i32 transferId. A print is one BEGIN, then its tiles, preview (multi-tile only) and source
 * chunks. Must match the Paper plugin's PhotoReceiver.
 */
public record PhotoPayload(byte[] data) implements CustomPacketPayload {
	public static final int VERSION = 3;
	public static final int MAP_SIZE = 128;
	public static final int MAX_CAPTION = 200;
	public static final int CHUNK_SIZE = 30_000;

	public static final int KIND_BEGIN = 0;
	public static final int KIND_TILE = 1;
	public static final int KIND_PREVIEW = 2;
	public static final int KIND_SOURCE = 3;
	public static final int KIND_CLOSE_EDITOR = 4;
	/** The flash fired, so the server can show it to other players. Older plugins ignore it. */
	public static final int KIND_FLASH = 5;

	public static final CustomPacketPayload.Type<PhotoPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("dslrcamera", "photo"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PhotoPayload> CODEC = StreamCodec.of(
		(buf, payload) -> buf.writeBytes(payload.data),
		buf -> {
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return new PhotoPayload(bytes);
		});

	/** @param editSession the server's editor session for prints from the photo editor, or 0 for camera prints */
	public static PhotoPayload begin(int transferId, int cols, int rows, int editSession, String caption, int sourceLength, int sourceChunks) {
		byte[] captionBytes = truncate(caption);
		ByteBuffer buf = header(KIND_BEGIN, transferId, 2 + 4 + 2 + captionBytes.length + 4 + 1);
		buf.put((byte) cols).put((byte) rows).putInt(editSession)
			.putShort((short) captionBytes.length).put(captionBytes)
			.putInt(sourceLength).put((byte) sourceChunks);
		return new PhotoPayload(buf.array());
	}

	public static PhotoPayload tile(int transferId, int index, byte[] mapColors) {
		checkTile(mapColors);
		return new PhotoPayload(header(KIND_TILE, transferId, 1 + mapColors.length).put((byte) index).put(mapColors).array());
	}

	public static PhotoPayload preview(int transferId, byte[] mapColors) {
		checkTile(mapColors);
		return new PhotoPayload(header(KIND_PREVIEW, transferId, mapColors.length).put(mapColors).array());
	}

	public static PhotoPayload sourceChunk(int transferId, int index, byte[] chunk) {
		return new PhotoPayload(header(KIND_SOURCE, transferId, 1 + chunk.length).put((byte) index).put(chunk).array());
	}

	public static PhotoPayload closeEditor(int session) {
		return new PhotoPayload(header(KIND_CLOSE_EDITOR, session, 0).array());
	}

	public static PhotoPayload flash() {
		return new PhotoPayload(header(KIND_FLASH, 0, 0).array());
	}

	private static ByteBuffer header(int kind, int transferId, int bodyLength) {
		return ByteBuffer.allocate(6 + bodyLength).put((byte) VERSION).put((byte) kind).putInt(transferId);
	}

	private static void checkTile(byte[] mapColors) {
		if (mapColors.length != MAP_SIZE * MAP_SIZE) {
			throw new IllegalArgumentException("Expected " + MAP_SIZE * MAP_SIZE + " map colours");
		}
	}

	private static byte[] truncate(String caption) {
		byte[] bytes = caption.getBytes(StandardCharsets.UTF_8);
		if (bytes.length <= MAX_CAPTION) {
			return bytes;
		}
		// Re-encode so a multi-byte character is never split.
		String cut = new String(Arrays.copyOf(bytes, MAX_CAPTION), StandardCharsets.UTF_8).replace("\uFFFD", "");
		return cut.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
