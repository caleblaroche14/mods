package com.dslrcamera.client;

import java.util.List;

import com.dslrcamera.client.config.DslrConfig;
import com.dslrcamera.client.dev.AutoTest;
import com.dslrcamera.client.editor.EditorClient;
import com.dslrcamera.client.exposure.ExposureSettings;
import com.dslrcamera.client.exposure.Flash;
import com.dslrcamera.client.exposure.LightMeter;
import com.dslrcamera.client.net.PhotoPayload;
import com.dslrcamera.client.photo.PhotoCapture;
import com.dslrcamera.client.render.DslrEffect;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DslrCameraClient implements ClientModInitializer {
	public static final String MOD_ID = "dslrcamera";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(id("camera"));
	private static KeyMapping selectKey;
	private static KeyMapping focusKey;

	/** Vanilla HUD pieces hidden while looking through the viewfinder. */
	private static final List<Identifier> HIDDEN_IN_VIEWFINDER = List.of(
		VanillaHudElements.CROSSHAIR, VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR,
		VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR,
		VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
		VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.MOB_EFFECTS, VanillaHudElements.SCOREBOARD,
		VanillaHudElements.CHAT);

	@Override
	public void onInitializeClient() {
		DslrConfig.load();
		PayloadTypeRegistry.serverboundPlay().register(PhotoPayload.TYPE, PhotoPayload.CODEC);
		EditorClient.register();
		DslrEffect.register();

		selectKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.dslrcamera.select_setting", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, CATEGORY));
		focusKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.dslrcamera.focus_lock", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));

		ClientTickEvents.START_CLIENT_TICK.register(DslrCameraClient::onStartTick);
		ClientTickEvents.END_CLIENT_TICK.register(DslrCameraClient::onEndTick);

		for (Identifier element : HIDDEN_IN_VIEWFINDER) {
			HudElementRegistry.replaceElement(element, DslrCameraClient::hiddenWhileActive);
		}
		HudElementRegistry.addLast(id("viewfinder"), ViewfinderHud::render);

		AutoTest.registerIfEnabled();
		LOGGER.info("DSLR Camera loaded");
	}

	public static KeyMapping selectKey() {
		return selectKey;
	}

	public static KeyMapping focusKey() {
		return focusKey;
	}

	private static HudElement hiddenWhileActive(HudElement original) {
		return (graphics, delta) -> {
			if (!CameraState.isActive()) {
				original.extractRenderState(graphics, delta);
			}
		};
	}

	/**
	 * Runs before vanilla input handling. While the camera is raised we read right-clicks (lower the camera) and
	 * left-clicks (shutter) ourselves, because vanilla discards both while an item is in use, and we hold the use key
	 * down so vanilla keeps the spyglass in use.
	 */
	private static void onStartTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			if (CameraState.isRaised()) {
				CameraState.setRaised(false);
			}
			return;
		}
		if (!CameraState.isRaised()) {
			return;
		}

		boolean lower = !CameraState.isHoldingCamera(player) || minecraft.gui.screen() != null && !PhotoCapture.isExposing();
		while (minecraft.options.keyUse.consumeClick()) {
			lower = true;
		}
		if (lower) {
			lowerCamera(minecraft);
			return;
		}
		minecraft.options.keyUse.setDown(true);

		boolean clicked = false;
		while (minecraft.options.keyAttack.consumeClick()) {
			clicked = true;
		}
		if (clicked && CameraState.isActive() && !PhotoCapture.isExposing() && CameraState.requestShot()) {
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.6F));
		}
	}

	private static void lowerCamera(Minecraft minecraft) {
		CameraState.setRaised(false);
		minecraft.options.keyUse.setDown(false);
		PhotoCapture.cancel("camera lowered");
	}

	private static void onEndTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		if (!CameraState.isRaised() && player.isUsingItem() && CameraItems.isCamera(player.getUseItem())) {
			CameraState.setRaised(true);
			LightMeter.reset();
		}
		while (selectKey.consumeClick()) {
			if (CameraState.isRaised()) {
				CameraState.cycleSelected(player.isShiftKeyDown() ? -1 : 1);
			}
		}
		while (focusKey.consumeClick()) {
			if (CameraState.isRaised()) {
				LightMeter.toggleFocusLock();
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 2.0F));
			}
		}
		if (CameraState.isRaised()) {
			LightMeter.tick(minecraft);
			ExposureSettings.autoExpose(LightMeter.sceneEv());
			Flash.update();
		}
		CameraState.tick();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
