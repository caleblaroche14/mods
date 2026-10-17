package com.dslrcamera.plugin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * Reassembles prints sent by the client mod. Every message starts with u8 version, u8 kind, i32 transferId.
 * BEGIN: u8 cols, u8 rows, i32 editSession (0 = camera), u16 caption length + caption, i32 source length,
 * u8 source chunks. TILE: u8 index + 128*128 map colours. PREVIEW: 128*128 map colours. SOURCE: u8 index + chunk of
 * the deflated full-colour image. CLOSE_EDITOR: transferId is the editor session being closed. FLASH: no body; the
 * photographer's flash fired, so nearby players see it.
 */
final class PhotoReceiver implements PluginMessageListener {
	private static final int VERSION = 3;
	private static final int SIZE = PhotoStore.SIZE;
	private static final int MAX_CAPTION = 200;
	private static final int CHUNK_SIZE = 30_000;
	private static final long TRANSFER_TIMEOUT_MS = 60_000;

	private static final int KIND_BEGIN = 0;
	private static final int KIND_TILE = 1;
	private static final int KIND_PREVIEW = 2;
	private static final int KIND_SOURCE = 3;
	private static final int KIND_CLOSE_EDITOR = 4;
	private static final int KIND_FLASH = 5;
	private static final long FLASH_MIN_INTERVAL_MS = 250;
	private static final double FLASH_VISIBLE_RANGE = 64.0;

	private final DslrCameraPlugin plugin;
	private final PhotoStore store;
	private final EditorSessions editors;
	private final Map<UUID, Long> lastPrint = new ConcurrentHashMap<>();
	private final Map<UUID, Long> lastFlash = new ConcurrentHashMap<>();
	private final Map<UUID, Transfer> transfers = new ConcurrentHashMap<>();

	PhotoReceiver(DslrCameraPlugin plugin, PhotoStore store, EditorSessions editors) {
		this.plugin = plugin;
		this.store = store;
		this.editors = editors;
	}

	private static final class Transfer {
		final int id;
		final int cols;
		final int rows;
		final int editSession;
		final String caption;
		final byte[][] tiles;
		final byte[] source;
		final boolean[] sourceReceived;
		final long startedMs = System.currentTimeMillis();
		byte[] preview;
		int tilesReceived;
		int chunksReceived;
		boolean done;

		Transfer(int id, int cols, int rows, int editSession, String caption, int sourceLength, int sourceChunks) {
			this.id = id;
			this.cols = cols;
			this.rows = rows;
			this.editSession = editSession;
			this.caption = caption;
			this.tiles = new byte[cols * rows][];
			this.source = new byte[sourceLength];
			this.sourceReceived = new boolean[sourceChunks];
		}

		boolean complete() {
			return tilesReceived == tiles.length && (tiles.length == 1 || preview != null) && chunksReceived == sourceReceived.length;
		}
	}

	@Override
	public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
		if (!channel.equals(DslrCameraPlugin.CHANNEL) || !player.hasPermission("dslrcamera.use")) {
			return;
		}
		ByteBuffer buf = ByteBuffer.wrap(message);
		if (buf.remaining() < 6 || (buf.get() & 0xFF) != VERSION) {
			return;
		}
		int kind = buf.get() & 0xFF;
		int id = buf.getInt();
		UUID playerId = player.getUniqueId();

		if (kind == KIND_BEGIN) {
			begin(player, id, buf);
			return;
		}
		if (kind == KIND_CLOSE_EDITOR) {
			plugin.getServer().getScheduler().runTask(plugin, () -> editors.close(player, id));
			return;
		}
		if (kind == KIND_FLASH) {
			long now = System.currentTimeMillis();
			Long last = lastFlash.put(playerId, now);
			if (last == null || now - last >= FLASH_MIN_INTERVAL_MS) {
				plugin.getServer().getScheduler().runTask(plugin, () -> showFlash(player));
			}
			return;
		}

		Transfer transfer = transfers.get(playerId);
		if (transfer == null || transfer.id != id || System.currentTimeMillis() - transfer.startedMs > TRANSFER_TIMEOUT_MS) {
			return;
		}
		synchronized (transfer) {
			if (transfer.done) {
				return;
			}
			switch (kind) {
				case KIND_TILE -> {
					int index = buf.remaining() > 0 ? buf.get() & 0xFF : -1;
					if (index < 0 || index >= transfer.tiles.length || transfer.tiles[index] != null || buf.remaining() != SIZE * SIZE) {
						return;
					}
					transfer.tiles[index] = new byte[SIZE * SIZE];
					buf.get(transfer.tiles[index]);
					transfer.tilesReceived++;
				}
				case KIND_PREVIEW -> {
					if (transfer.tiles.length == 1 || transfer.preview != null || buf.remaining() != SIZE * SIZE) {
						return;
					}
					transfer.preview = new byte[SIZE * SIZE];
					buf.get(transfer.preview);
				}
				case KIND_SOURCE -> {
					int index = buf.remaining() > 0 ? buf.get() & 0xFF : -1;
					int offset = index * CHUNK_SIZE;
					if (index < 0 || index >= transfer.sourceReceived.length || transfer.sourceReceived[index]
						|| offset + buf.remaining() > transfer.source.length) {
						return;
					}
					buf.get(transfer.source, offset, buf.remaining());
					transfer.sourceReceived[index] = true;
					transfer.chunksReceived++;
				}
				default -> {
					return;
				}
			}
			if (!transfer.complete()) {
				return;
			}
			transfer.done = true;
		}
		transfers.remove(playerId, transfer);
		// Inventory and map work must happen on the main thread.
		plugin.getServer().getScheduler().runTask(plugin, () -> print(player, transfer));
	}

	/** A flash pop just in front of the camera, for everyone nearby except the photographer (who sees their own). */
	private void showFlash(Player photographer) {
		if (!photographer.isOnline()) {
			return;
		}
		Location eye = photographer.getEyeLocation();
		Location at = eye.clone().add(eye.getDirection().multiply(0.4));
		for (Player viewer : photographer.getWorld().getPlayers()) {
			if (viewer != photographer && viewer.getLocation().distanceSquared(at) <= FLASH_VISIBLE_RANGE * FLASH_VISIBLE_RANGE) {
				viewer.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, Color.WHITE);
			}
		}
	}

	private void begin(Player player, int id, ByteBuffer buf) {
		if (buf.remaining() < 2 + 4 + 2) {
			return;
		}
		int cols = buf.get() & 0xFF;
		int rows = buf.get() & 0xFF;
		int editSession = buf.getInt();
		int captionLength = buf.getShort() & 0xFFFF;
		if (captionLength > MAX_CAPTION || buf.remaining() != captionLength + 4 + 1) {
			return;
		}
		byte[] captionBytes = new byte[captionLength];
		buf.get(captionBytes);
		int sourceLength = buf.getInt();
		int sourceChunks = buf.get() & 0xFF;

		int maxCols = plugin.getConfig().getInt("max-cols", 8);
		int maxRows = plugin.getConfig().getInt("max-rows", 8);
		if (cols > maxCols || rows > maxRows) {
			plugin.getServer().getScheduler().runTask(plugin, () -> player.sendActionBar(Component.text(
				"This server prints photos up to " + maxCols + "×" + maxRows + " maps.", NamedTextColor.RED)));
			return;
		}
		// A deflated image is never bigger than the raw RGB plus a little framing.
		int maxSource = cols * rows * SIZE * SIZE * 3 + 4096;
		if (cols < 1 || rows < 1 || sourceLength <= 0 || sourceLength > maxSource
			|| sourceChunks != (sourceLength + CHUNK_SIZE - 1) / CHUNK_SIZE) {
			return;
		}
		String caption = new String(captionBytes, StandardCharsets.UTF_8);
		transfers.put(player.getUniqueId(), new Transfer(id, cols, rows, editSession, caption, sourceLength, sourceChunks));
	}

	private void print(Player player, Transfer photo) {
		if (!player.isOnline()) {
			return;
		}
		boolean edit = photo.editSession != 0;
		String credit = (edit ? "Edited by " : "Taken by ") + player.getName();
		String problem;
		if (!edit && !plugin.cameraItem().isCamera(player.getInventory().getItemInMainHand())
			&& !plugin.cameraItem().isCamera(player.getInventory().getItemInOffHand())) {
			problem = "You need to be holding a camera to print.";
		} else if (edit && !editors.isValid(player, photo.editSession, photo.cols, photo.rows)) {
			problem = "Stay at the cartography table to print your edit.";
		} else {
			problem = chargeAndCooldown(player, photo.tiles.length, photo.cols, photo.rows);
		}
		if (problem != null) {
			player.sendActionBar(Component.text(problem, NamedTextColor.RED));
			if (edit) {
				editors.sendResult(player, photo.editSession, false, problem);
			}
			return;
		}
		int tileCount = photo.tiles.length;

		UUID photoId = UUID.randomUUID();
		if (isValidSource(photo.source, photo.cols * SIZE * photo.rows * SIZE * 3)) {
			store.saveSource(photoId, photo.source);
		}

		ItemStack result;
		if (tileCount == 1) {
			MapView view = store.create(player.getWorld(), photo.tiles[0]);
			result = plugin.photoItems().single(view, photo.caption, credit, photoId);
		} else {
			int[] tileIds = new int[tileCount];
			for (int i = 0; i < tileCount; i++) {
				tileIds[i] = store.create(player.getWorld(), photo.tiles[i]).getId();
			}
			MapView preview = store.create(player.getWorld(), photo.preview);
			result = plugin.photoItems().framed(preview, photo.cols, photo.rows, tileIds, photo.caption, credit, photoId);
		}

		player.getInventory().addItem(result).values()
			.forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
		player.sendActionBar(Component.text(edit ? "Edited photo printed!" : "Photo printed!", NamedTextColor.GREEN));
		if (edit) {
			editors.sendResult(player, photo.editSession, true, "Printed!");
		}
		plugin.getLogger().info("Printed " + photo.cols + "x" + photo.rows + (photo.editSession == 0 ? " photo" : " edited photo")
			+ " for " + player.getName());
	}

	/** Takes the print cost and enforces the cooldown; returns why the player can't print, or null if they paid. */
	private String chargeAndCooldown(Player player, int tileCount, int cols, int rows) {
		if (player.hasPermission("dslrcamera.bypass") || player.getGameMode() == GameMode.CREATIVE) {
			return null;
		}
		long cooldownMs = plugin.getConfig().getLong("cooldown-seconds", 3) * 1000L;
		long now = System.currentTimeMillis();
		Long last = lastPrint.get(player.getUniqueId());
		if (last != null && now - last < cooldownMs) {
			return "The printer is still cooling down.";
		}
		Material cost = plugin.printCostItem();
		int amount = plugin.printCost(tileCount);
		if (cost != null && amount > 0) {
			if (!player.getInventory().containsAtLeast(new ItemStack(cost), amount)) {
				return "You need " + amount + " " + cost.name().toLowerCase().replace('_', ' ') + " to print a " + cols + "×" + rows + " photo.";
			}
			player.getInventory().removeItem(new ItemStack(cost, amount));
		}
		lastPrint.put(player.getUniqueId(), now);
		return null;
	}

	/** Only keep sources that really inflate to the expected image, so editors never receive garbage. */
	private static boolean isValidSource(byte[] deflated, int expectedSize) {
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(deflated);
			byte[] buffer = new byte[65536];
			long total = 0;
			while (!inflater.finished() && total <= expectedSize) {
				int n = inflater.inflate(buffer);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					return false;
				}
				total += n;
			}
			return inflater.finished() && total == expectedSize;
		} catch (DataFormatException e) {
			return false;
		} finally {
			inflater.end();
		}
	}
}
