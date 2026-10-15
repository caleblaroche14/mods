package com.dslrcamera.plugin;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.Deflater;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Cartography tables double as photo editors: right-clicking one while holding a photo sends the photo to a modded
 * client's editor screen. Edited prints are only accepted while the player stays at that table. Players without
 * the mod keep the normal cartography table.
 *
 * <p>Server-to-client messages on {@value DslrCameraPlugin#EDITOR_CHANNEL}: u8 version, u8 kind, i32 session.
 * OPEN: u8 cols, u8 rows, u8 format (0 = deflated RGB, 1 = deflated map tiles), u16 caption length + caption,
 * i32 data length, u8 chunk count, i32 print cost, u16 cost item length + item. CHUNK: u8 index + data.
 * RESULT (after an edited print): u8 success, u16 message length + message.
 */
final class EditorSessions implements Listener {
	private static final int VERSION = 3;
	private static final int KIND_OPEN = 0;
	private static final int KIND_CHUNK = 1;
	private static final int KIND_RESULT = 2;
	private static final int FORMAT_RGB = 0;
	private static final int FORMAT_MAP_TILES = 1;
	private static final int CHUNK_SIZE = 30_000;
	private static final double MAX_DISTANCE = 8.0;
	private static final long SESSION_TIMEOUT_MS = 15 * 60_000;

	private final DslrCameraPlugin plugin;
	private final PhotoStore store;
	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

	private record Session(int id, int cols, int rows, Location table, long startedMs) {
	}

	EditorSessions(DslrCameraPlugin plugin, PhotoStore store) {
		this.plugin = plugin;
		this.store = store;
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onInteract(PlayerInteractEvent event) {
		Block block = event.getClickedBlock();
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
			|| block == null || block.getType() != Material.CARTOGRAPHY_TABLE) {
			return;
		}
		Player player = event.getPlayer();
		PhotoItems.PhotoRef photo = plugin.photoItems().readPhoto(event.getItem(), store);
		if (photo == null || !player.getListeningPluginChannels().contains(DslrCameraPlugin.EDITOR_CHANNEL)
			|| !player.hasPermission("dslrcamera.use")) {
			return;
		}
		event.setUseInteractedBlock(Event.Result.DENY);
		event.setUseItemInHand(Event.Result.DENY);

		int format = FORMAT_RGB;
		byte[] data = photo.photoId() != null ? store.readSource(photo.photoId()) : null;
		if (data == null) {
			format = FORMAT_MAP_TILES;
			data = tilesFallback(photo);
			if (data == null) {
				player.sendActionBar(Component.text("This photo's image data is missing.", NamedTextColor.RED));
				return;
			}
		}

		int session = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
		sessions.put(player.getUniqueId(), new Session(session, photo.cols(), photo.rows(), block.getLocation(), System.currentTimeMillis()));

		boolean free = player.hasPermission("dslrcamera.bypass") || player.getGameMode() == GameMode.CREATIVE;
		Material costItem = plugin.printCostItem();
		int cost = free || costItem == null ? 0 : plugin.printCost(photo.cols() * photo.rows());
		String costName = costItem == null ? "" : costItem.name().toLowerCase().replace('_', ' ');
		send(player, session, photo, format, data, cost, costName);
	}

	private byte[] tilesFallback(PhotoItems.PhotoRef photo) {
		int tileBytes = PhotoStore.SIZE * PhotoStore.SIZE;
		byte[] raw = new byte[photo.tileMapIds().length * tileBytes];
		for (int i = 0; i < photo.tileMapIds().length; i++) {
			byte[] pixels = store.readPixels(photo.tileMapIds()[i]);
			if (pixels == null) {
				return null;
			}
			System.arraycopy(pixels, 0, raw, i * tileBytes, tileBytes);
		}
		Deflater deflater = new Deflater();
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[16384];
		while (!deflater.finished()) {
			out.write(buffer, 0, deflater.deflate(buffer));
		}
		deflater.end();
		return out.toByteArray();
	}

	private void send(Player player, int session, PhotoItems.PhotoRef photo, int format, byte[] data, int cost, String costItem) {
		byte[] caption = truncate(photo.caption().getBytes(StandardCharsets.UTF_8), 200);
		byte[] item = truncate(costItem.getBytes(StandardCharsets.UTF_8), 64);
		int chunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
		ByteBuffer open = ByteBuffer.allocate(6 + 3 + 2 + caption.length + 4 + 1 + 4 + 2 + item.length)
			.put((byte) VERSION).put((byte) KIND_OPEN).putInt(session)
			.put((byte) photo.cols()).put((byte) photo.rows()).put((byte) format)
			.putShort((short) caption.length).put(caption)
			.putInt(data.length).put((byte) chunks)
			.putInt(cost).putShort((short) item.length).put(item);
		player.sendPluginMessage(plugin, DslrCameraPlugin.EDITOR_CHANNEL, open.array());
		for (int i = 0; i < chunks; i++) {
			int from = i * CHUNK_SIZE;
			int to = Math.min(data.length, from + CHUNK_SIZE);
			ByteBuffer chunk = ByteBuffer.allocate(7 + to - from)
				.put((byte) VERSION).put((byte) KIND_CHUNK).putInt(session).put((byte) i).put(data, from, to - from);
			player.sendPluginMessage(plugin, DslrCameraPlugin.EDITOR_CHANNEL, chunk.array());
		}
	}

	/** True if the player has this editor session open, for a photo of this size, and is still at the table. */
	boolean isValid(Player player, int session, int cols, int rows) {
		Session current = sessions.get(player.getUniqueId());
		if (current == null || current.id() != session || current.cols() != cols || current.rows() != rows
			|| System.currentTimeMillis() - current.startedMs() > SESSION_TIMEOUT_MS) {
			return false;
		}
		Location table = current.table();
		return table.getWorld().equals(player.getWorld())
			&& table.getBlock().getType() == Material.CARTOGRAPHY_TABLE
			&& table.clone().add(0.5, 0.5, 0.5).distance(player.getLocation()) <= MAX_DISTANCE;
	}

	/** Tells the editor screen whether its print went through, so it only closes on success. */
	void sendResult(Player player, int session, boolean success, String message) {
		byte[] text = truncate(message.getBytes(StandardCharsets.UTF_8), 256);
		ByteBuffer result = ByteBuffer.allocate(6 + 1 + 2 + text.length)
			.put((byte) VERSION).put((byte) KIND_RESULT).putInt(session)
			.put((byte) (success ? 1 : 0)).putShort((short) text.length).put(text);
		player.sendPluginMessage(plugin, DslrCameraPlugin.EDITOR_CHANNEL, result.array());
	}

	void close(Player player, int session) {
		sessions.computeIfPresent(player.getUniqueId(), (id, current) -> current.id() == session ? null : current);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		sessions.remove(event.getPlayer().getUniqueId());
	}

	private static byte[] truncate(byte[] bytes, int max) {
		return bytes.length <= max ? bytes : Arrays.copyOf(bytes, max);
	}
}
