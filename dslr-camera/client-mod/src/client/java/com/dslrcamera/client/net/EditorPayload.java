package com.dslrcamera.client.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client messages on the {@code dslrcamera:editor} plugin channel, sent when a player opens the photo
 * editor at a cartography table: u8 version, u8 kind, i32 session, then for OPEN: u8 cols, u8 rows, u8 format,
 * u16 caption length + caption, i32 total data length, u8 chunk count, i32 print cost, u16 cost item length + item;
 * for CHUNK: u8 index + bytes; for RESULT (reply to an edited print): u8 success, u16 message length + message. Data is deflated 24-bit RGB, or deflated map tiles for photos with no stored source.
 */
public record EditorPayload(byte[] data) implements CustomPacketPayload {
	public static final int KIND_OPEN = 0;
	public static final int KIND_CHUNK = 1;
	public static final int KIND_RESULT = 2;
	public static final int FORMAT_RGB = 0;
	public static final int FORMAT_MAP_TILES = 1;

	public static final CustomPacketPayload.Type<EditorPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("dslrcamera", "editor"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EditorPayload> CODEC = StreamCodec.of(
		(buf, payload) -> buf.writeBytes(payload.data),
		buf -> {
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return new EditorPayload(bytes);
		});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
