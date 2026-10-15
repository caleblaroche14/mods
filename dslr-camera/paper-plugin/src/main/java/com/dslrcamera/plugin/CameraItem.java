package com.dslrcamera.plugin;

import java.util.List;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * The camera is a spyglass, so players without the client mod still see and can use a normal item. The custom model
 * data string tells modded clients to render the camera model and enable the viewfinder.
 */
@SuppressWarnings("UnstableApiUsage")
final class CameraItem {
	static final String MODEL_TAG = "dslrcamera:camera";

	private final NamespacedKey key;

	CameraItem(DslrCameraPlugin plugin) {
		this.key = new NamespacedKey(plugin, "camera");
	}

	ItemStack create() {
		ItemStack stack = ItemStack.of(Material.SPYGLASS);
		stack.setData(DataComponentTypes.ITEM_NAME, Component.text("Camera"));
		stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(MODEL_TAG).build());
		stack.editMeta(meta -> {
			meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
			meta.lore(List.of(
				line("Hold right-click to look through", NamedTextColor.GRAY),
				line("Left-click to take a photo", NamedTextColor.GRAY),
				line("Needs the DSLR Camera mod", NamedTextColor.DARK_GRAY)));
		});
		return stack;
	}

	boolean isCamera(ItemStack stack) {
		return stack != null && stack.getType() == Material.SPYGLASS && stack.hasItemMeta()
			&& stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
	}

	private static Component line(String text, NamedTextColor color) {
		return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
	}
}
