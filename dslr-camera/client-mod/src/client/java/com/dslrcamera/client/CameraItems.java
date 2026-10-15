package com.dslrcamera.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

/** A camera is a spyglass tagged by the server plugin with the custom model data string {@value #MODEL_TAG}. */
public final class CameraItems {
	public static final String MODEL_TAG = "dslrcamera:camera";

	private CameraItems() {
	}

	public static boolean isCamera(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.SPYGLASS)) {
			return false;
		}
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return data != null && data.strings().contains(MODEL_TAG);
	}
}
