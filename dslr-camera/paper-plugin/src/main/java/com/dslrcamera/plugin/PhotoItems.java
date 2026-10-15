package com.dslrcamera.plugin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Builds printed photo items. A 1x1 photo is a plain map. A larger photo is a single "framed photo" map showing a
 * preview, which remembers the map ids of its tiles so it can be hung on a wall of item frames.
 */
final class PhotoItems {
	private final NamespacedKey tilesKey;
	private final NamespacedKey colsKey;
	private final NamespacedKey rowsKey;
	private final NamespacedKey photoIdKey;
	private final NamespacedKey captionKey;

	PhotoItems(DslrCameraPlugin plugin) {
		this.tilesKey = new NamespacedKey(plugin, "photo_tiles");
		this.colsKey = new NamespacedKey(plugin, "photo_cols");
		this.rowsKey = new NamespacedKey(plugin, "photo_rows");
		this.photoIdKey = new NamespacedKey(plugin, "photo_id");
		this.captionKey = new NamespacedKey(plugin, "photo_caption");
	}

	record FramedPhoto(int cols, int rows, int[] tileMapIds) {
	}

	/** Any printed photo item: what the editor needs to load it. photoId is null for photos printed before sources were kept. */
	record PhotoRef(UUID photoId, int cols, int rows, int[] tileMapIds, String caption) {
	}

	/** @param credit e.g. "Taken by Steve" or "Edited by Alex" */
	ItemStack single(MapView view, String caption, String credit, UUID photoId) {
		ItemStack item = map(view, "Photo", lore(caption, credit, null));
		tag(item, photoId, caption);
		return item;
	}

	ItemStack framed(MapView preview, int cols, int rows, int[] tileMapIds, String caption, String credit, UUID photoId) {
		String size = cols + "×" + rows;
		ItemStack item = map(preview, "Photo (" + size + ")",
			lore(caption, credit, "Right-click the top-left frame of a " + size + " wall of empty item frames"));
		item.editMeta(meta -> {
			PersistentDataContainer pdc = meta.getPersistentDataContainer();
			pdc.set(tilesKey, PersistentDataType.INTEGER_ARRAY, tileMapIds);
			pdc.set(colsKey, PersistentDataType.INTEGER, cols);
			pdc.set(rowsKey, PersistentDataType.INTEGER, rows);
		});
		tag(item, photoId, caption);
		return item;
	}

	private void tag(ItemStack item, UUID photoId, String caption) {
		item.editMeta(meta -> {
			meta.getPersistentDataContainer().set(photoIdKey, PersistentDataType.STRING, photoId.toString());
			meta.getPersistentDataContainer().set(captionKey, PersistentDataType.STRING, caption);
		});
	}

	/** Recognises any DSLR photo: framed photos, single photos, and single photos printed before items were tagged. */
	PhotoRef readPhoto(ItemStack stack, PhotoStore store) {
		if (stack == null || stack.getType() != Material.FILLED_MAP || !(stack.getItemMeta() instanceof MapMeta meta)) {
			return null;
		}
		PersistentDataContainer pdc = meta.getPersistentDataContainer();
		UUID photoId = parseUuid(pdc.get(photoIdKey, PersistentDataType.STRING));
		String caption = pdc.getOrDefault(captionKey, PersistentDataType.STRING, "");
		FramedPhoto framed = readFramed(stack);
		if (framed != null) {
			return new PhotoRef(photoId, framed.cols(), framed.rows(), framed.tileMapIds(), caption);
		}
		if (!meta.hasMapView() || meta.getMapView() == null) {
			return null;
		}
		int mapId = meta.getMapView().getId();
		if (photoId == null && !store.isPhotoMap(mapId)) {
			return null;
		}
		return new PhotoRef(photoId, 1, 1, new int[] {mapId}, caption);
	}

	private static UUID parseUuid(String value) {
		try {
			return value == null ? null : UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** A single tile, as it appears once hung in a frame (and when it drops out of one). */
	ItemStack tile(MapView view, int cols, int rows) {
		return map(view, "Photo (" + cols + "×" + rows + " piece)", List.of());
	}

	FramedPhoto readFramed(ItemStack stack) {
		if (stack == null || stack.getType() != Material.FILLED_MAP || !stack.hasItemMeta()) {
			return null;
		}
		PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
		int[] tiles = pdc.get(tilesKey, PersistentDataType.INTEGER_ARRAY);
		Integer cols = pdc.get(colsKey, PersistentDataType.INTEGER);
		Integer rows = pdc.get(rowsKey, PersistentDataType.INTEGER);
		if (tiles == null || cols == null || rows == null || tiles.length != cols * rows) {
			return null;
		}
		return new FramedPhoto(cols, rows, tiles);
	}

	private static ItemStack map(MapView view, String name, List<Component> lore) {
		ItemStack item = new ItemStack(Material.FILLED_MAP);
		item.editMeta(MapMeta.class, meta -> {
			meta.setMapView(view);
			meta.displayName(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
			meta.lore(lore);
		});
		return item;
	}

	private static List<Component> lore(String caption, String credit, String hint) {
		List<Component> lines = new ArrayList<>();
		if (!caption.isBlank()) {
			lines.add(Component.text(caption, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		}
		lines.add(Component.text(credit + " on " + LocalDate.now(), NamedTextColor.DARK_GRAY)
			.decoration(TextDecoration.ITALIC, false));
		if (hint != null) {
			lines.add(Component.text(hint, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
		}
		return lines;
	}
}
