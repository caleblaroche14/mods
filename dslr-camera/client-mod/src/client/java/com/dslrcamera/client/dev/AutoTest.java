package com.dslrcamera.client.dev;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.dslrcamera.client.CameraItems;
import com.dslrcamera.client.CameraState;
import com.dslrcamera.client.DslrCameraClient;
import com.dslrcamera.client.PrintSize;
import com.dslrcamera.client.editor.EditorScreen;
import com.dslrcamera.client.exposure.ExposureSettings;
import com.dslrcamera.client.exposure.Flash;
import com.dslrcamera.client.exposure.LightMeter;
import com.dslrcamera.client.photo.PhotoCapture;
import com.dslrcamera.client.render.DslrEffect;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Opt-in end-to-end test (-Ddslrcamera.autotest=true) against a Paper server running the plugin, where the test
 * player is op on join. Using real mouse clicks it raises the camera, prints a 3x2 photo and hangs it on a wall of
 * item frames, then exercises depth of field, exposure compensation, high ISO and a 1s long exposure. Screenshots
 * go to screenshots/autotest_*.png; it logs PASS/FAIL and quits.
 */
public final class AutoTest {
	private static final PrintSize TEST_SIZE = new PrintSize(3, 2);
	// Frames hang on the north face of a stone wall at z=9; viewed from the south, the top-left frame is at the highest x.
	private static final int WALL_TOP_LEFT_X = 4;
	private static final int WALL_TOP_Y = -59;
	private static final int FRAME_Z = 8;

	private record Step(String name, int timeoutTicks, Predicate<Minecraft> action) {
	}

	private static final List<Step> STEPS = new ArrayList<>();
	private static final List<String> SETUP_COMMANDS = new ArrayList<>();
	private static int stepIndex;
	private static int stepTicks;
	private static int ticksInWorld;
	private static boolean done;

	private AutoTest() {
	}

	public static void registerIfEnabled() {
		if (!Boolean.getBoolean("dslrcamera.autotest")) {
			return;
		}
		DslrCameraClient.LOGGER.info("[autotest] enabled");
		buildSteps();
		ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
	}

	private static void buildSteps() {
		// If a previous run left a photo wall, this shows whether it survived a server restart.
		STEPS.add(new Step("screenshot previous wall", 50, mc -> {
			if (stepTicks == 1) {
				mc.player.setYRot(0.0F);
				mc.player.setXRot(15.0F);
			}
			return waitTicks(40) && screenshot(mc, "previous_wall");
		}));
		// One command per tick: Paper kicks clients that send a burst of commands.
		STEPS.add(new Step("setup world", 100, mc -> {
			if (stepTicks == 1) {
				String name = mc.player.getGameProfile().name();
				SETUP_COMMANDS.addAll(List.of(
					"difficulty peaceful", "time set noon", "gamerule advance_time false", "gamemode survival", "clear", "kill @e[type=item_frame]",
					"fill -1 -60 9 6 -55 9 stone", "fill -1 -60 4 6 -55 8 air",
					"fill 3 -60 1 3 -58 1 gold_block", "fill -6 -60 -12 -5 -54 -12 diamond_block",
					"fill 9 -60 -27 10 -50 -27 emerald_block", "fill -1 -60 -5 -1 -57 -5 redstone_block", "fill 5 -60 3 5 -60 3 cartography_table",
					"dslrcamera give " + name, "give " + name + " paper 64",
					"tp " + name + " 3.0 -60 4.5 0 0"));
				for (int row = 0; row < TEST_SIZE.rows(); row++) {
					for (int col = 0; col < TEST_SIZE.cols(); col++) {
						SETUP_COMMANDS.add("summon item_frame " + (WALL_TOP_LEFT_X - col) + " " + (WALL_TOP_Y - row) + " " + FRAME_Z + " {Facing:2b}");
					}
				}
				// Drop op so printing goes through the normal paper cost path (after the flash test, which needs /time).
				deopCommand = "deop " + name;
			}
			if ((stepTicks & 1) == 0 && !SETUP_COMMANDS.isEmpty()) {
				mc.player.connection.sendCommand(SETUP_COMMANDS.removeFirst());
			}
			return SETUP_COMMANDS.isEmpty();
		}));
		STEPS.add(new Step("camera given", 100, mc -> {
			if (!CameraItems.isCamera(mc.player.getInventory().getItem(0))) {
				return false;
			}
			mc.player.getInventory().setSelectedSlot(0);
			return true;
		}));
		STEPS.add(new Step("screenshot holding camera", 40, mc -> waitTicks(30) && screenshot(mc, "hold")));
		STEPS.add(new Step("right-click raises camera", 40, mc -> {
			if (stepTicks == 1) {
				mc.player.setYRot(180.0F);
				mc.player.setXRot(-5.0F);
				click(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			}
			return CameraState.isActive();
		}));
		STEPS.add(new Step("camera stays up after release (toggle)", 30, mc -> waitTicks(25) && CameraState.isActive()));
		addFlashSteps();
		STEPS.add(new Step("choose 3x2 in program mode", 5, mc -> {
			setMode(ExposureSettings.Mode.PROGRAM);
			select(CameraState.Setting.SIZE);
			while (!CameraState.size().equals(TEST_SIZE)) {
				CameraState.scroll(1);
			}
			select(CameraState.Setting.ZOOM);
			CameraState.scroll(-2);
			logExposure("program");
			return true;
		}));
		STEPS.add(new Step("screenshot viewfinder", 30, mc -> waitTicks(20) && screenshot(mc, "viewfinder")));
		STEPS.add(new Step("left-click shoots", 5, mc -> {
			click(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			return true;
		}));
		STEPS.add(new Step("framed photo printed", 200, mc -> findFramedPhotoSlot(mc.player) >= 0));
		STEPS.add(new Step("paper charged per tile", 5, mc -> {
			int paper = mc.player.getInventory().countItem(Items.PAPER);
			DslrCameraClient.LOGGER.info("[autotest] paper left: {}", paper);
			return paper == 64 - TEST_SIZE.tiles();
		}));
		STEPS.add(new Step("right-click lowers camera", 20, mc -> {
			if (stepTicks == 1) {
				click(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			}
			return !mc.player.isUsingItem() && !CameraState.isRaised();
		}));
		STEPS.add(new Step("hang photo", 5, mc -> {
			int slot = findFramedPhotoSlot(mc.player);
			if (slot < 0 || slot > 8) {
				return false;
			}
			mc.player.getInventory().setSelectedSlot(slot);
			mc.player.setYRot(0.0F);
			mc.player.setXRot(0.0F);
			ItemFrame topLeft = frameAt(mc, WALL_TOP_LEFT_X, WALL_TOP_Y);
			if (topLeft == null) {
				return false;
			}
			mc.gameMode.interact(mc.player, topLeft, new EntityHitResult(topLeft), InteractionHand.MAIN_HAND);
			return true;
		}));
		STEPS.add(new Step("wall filled", 100, mc -> {
			for (int row = 0; row < TEST_SIZE.rows(); row++) {
				for (int col = 0; col < TEST_SIZE.cols(); col++) {
					ItemFrame frame = frameAt(mc, WALL_TOP_LEFT_X - col, WALL_TOP_Y - row);
					if (frame == null || !frame.getItem().is(Items.FILLED_MAP)) {
						return false;
					}
				}
			}
			return findFramedPhotoSlot(mc.player) < 0;
		}));
		STEPS.add(new Step("screenshot wall", 40, mc -> {
			if (stepTicks == 1) {
				mc.player.getInventory().setSelectedSlot(0);
				mc.player.setYRot(0.0F);
				mc.player.setXRot(15.0F);
			}
			return waitTicks(30) && screenshot(mc, "wall");
		}));
		STEPS.add(new Step("raise camera at wall edge", 40, mc -> {
			if (stepTicks == 1) {
				mc.player.setYRot(35.0F);
				mc.player.setXRot(5.0F);
				click(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			}
			return CameraState.isActive();
		}));
		STEPS.add(new Step("screenshot shallow depth of field", 40, mc -> {
			if (stepTicks == 1) {
				setMode(ExposureSettings.Mode.APERTURE_PRIORITY);
				select(CameraState.Setting.APERTURE);
				CameraState.scroll(-20);
				CameraState.scroll(3);
				select(CameraState.Setting.ZOOM);
				CameraState.scroll(6);
				select(CameraState.Setting.SIZE);
				while (!CameraState.size().equals(new PrintSize(1, 1))) {
					CameraState.scroll(1);
				}
				select(CameraState.Setting.APERTURE);
			}
			if (stepTicks == 30) {
				logExposure("f/2.8 aperture priority");
			}
			return waitTicks(30) && screenshot(mc, "dof");
		}));
		STEPS.add(new Step("screenshot overexposed +2", 30, mc -> {
			if (stepTicks == 1) {
				select(CameraState.Setting.COMPENSATION);
				CameraState.scroll(6);
			}
			return waitTicks(20) && screenshot(mc, "overexposed");
		}));
		STEPS.add(new Step("screenshot ISO 6400", 30, mc -> {
			if (stepTicks == 1) {
				CameraState.scroll(-6);
				setMode(ExposureSettings.Mode.MANUAL);
				select(CameraState.Setting.APERTURE);
				CameraState.scroll(20);
				select(CameraState.Setting.SHUTTER);
				while (ExposureSettings.shutterSeconds() > 1 / 4000.0) {
					CameraState.scroll(1);
				}
				select(CameraState.Setting.ISO);
				CameraState.scroll(6);
			}
			if (stepTicks == 15) {
				logExposure("ISO 6400 manual");
			}
			return waitTicks(20) && screenshot(mc, "iso");
		}));
		STEPS.add(new Step("one second long exposure", 140, mc -> {
			if (stepTicks == 1) {
				select(CameraState.Setting.ISO);
				CameraState.scroll(-20);
				select(CameraState.Setting.SHUTTER);
				while (ExposureSettings.shutterSeconds() < 1.0) {
					CameraState.scroll(-1);
				}
				logExposure("manual 1s");
				mapsBefore = mc.player.getInventory().countItem(Items.FILLED_MAP);
				click(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			}
			if (stepTicks == 10) {
				exposingSeen = PhotoCapture.isExposing();
				screenshot(mc, "exposing");
			}
			return exposingSeen && !PhotoCapture.isExposing() && mc.player.getInventory().countItem(Items.FILLED_MAP) > mapsBefore;
		}));
		STEPS.add(new Step("shallow focus on near pillar", 50, mc -> {
			if (stepTicks == 1) {
				mc.player.setYRot(180.0F);
				mc.player.setXRot(0.0F);
				setMode(ExposureSettings.Mode.APERTURE_PRIORITY);
				select(CameraState.Setting.ISO);
				CameraState.scroll(-20);
				select(CameraState.Setting.APERTURE);
				CameraState.scroll(-20);
				select(CameraState.Setting.FOCUS);
				CameraState.scroll(-(LightMeter.MANUAL_DISTANCES.length + 1));
			}
			if (stepTicks == 40) {
				logExposure("f/1.4 AF near");
				DslrCameraClient.LOGGER.info("[autotest] fps {}", mc.getFps());
			}
			return waitTicks(40) && LightMeter.inFocus() && screenshot(mc, "dof_near");
		}));
		STEPS.add(new Step("manual focus at infinity", 40, mc -> {
			if (stepTicks == 1) {
				select(CameraState.Setting.FOCUS);
				CameraState.scroll(LightMeter.MANUAL_DISTANCES.length + 1);
			}
			if (stepTicks == 30) {
				logExposure("f/1.4 MF infinity");
			}
			return waitTicks(30) && LightMeter.focusMode() == LightMeter.FocusMode.MANUAL && screenshot(mc, "dof_far");
		}));
		STEPS.add(new Step("deep focus at f/16", 40, mc -> {
			if (stepTicks == 1) {
				CameraState.scroll(-(LightMeter.MANUAL_DISTANCES.length + 1));
				select(CameraState.Setting.APERTURE);
				while (ExposureSettings.fNumber() < 16) {
					CameraState.scroll(1);
				}
			}
			if (stepTicks == 30) {
				logExposure("f/16 AF");
			}
			return waitTicks(30) && screenshot(mc, "dof_deep");
		}));
		STEPS.add(new Step("take a 1x1 photo to edit", 200, mc -> {
			if (stepTicks == 1) {
				setMode(ExposureSettings.Mode.PROGRAM);
				select(CameraState.Setting.FOCUS);
				CameraState.scroll(-(LightMeter.MANUAL_DISTANCES.length + 1));
				mc.player.setYRot(160.0F);
				mapsBefore = mc.player.getInventory().countItem(Items.FILLED_MAP);
			}
			if (stepTicks == 30) {
				logExposure("photo to edit");
				click(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			}
			return stepTicks > 30 && mc.player.getInventory().countItem(Items.FILLED_MAP) > mapsBefore;
		}));
		STEPS.add(new Step("lower camera for editing", 20, mc -> {
			if (stepTicks == 1) {
				click(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			}
			return !CameraState.isRaised() && !mc.player.isUsingItem();
		}));
		STEPS.add(new Step("open editor at cartography table", 60, mc -> {
			if (stepTicks == 1) {
				int slot = findSinglePhotoSlot(mc.player);
				if (slot < 0 || slot > 8) {
					throw new IllegalStateException("no single photo in hotbar");
				}
				mc.player.getInventory().setSelectedSlot(slot);
				paperBefore = mc.player.getInventory().countItem(Items.PAPER);
				mapsBefore = mc.player.getInventory().countItem(Items.FILLED_MAP);
				useCartographyTable(mc);
			}
			return mc.gui.screen() instanceof EditorScreen;
		}));
		STEPS.add(new Step("apply Vintage preset with map view", 40, mc -> {
			switch (stepTicks) {
				case 2 -> clickButton(mc, "Presets");
				case 4 -> clickButton(mc, "Vintage");
				case 6 -> clickButton(mc, "Map view: Off");
				default -> {
				}
			}
			return waitTicks(20) && screenshot(mc, "editor");
		}));
		STEPS.add(new Step("print during cooldown is refused, editor stays open", 100, mc -> {
			if (stepTicks == 1) {
				clickButtonStartingWith(mc, "Print");
			}
			return stepTicks > 20 && mc.gui.screen() instanceof EditorScreen && hasActiveButton(mc, "Print");
		}));
		STEPS.add(new Step("print edited copy after cooldown", 200, mc -> {
			if (stepTicks == 60) {
				clickButtonStartingWith(mc, "Print");
			}
			return mc.gui.screen() == null && mc.player.getInventory().countItem(Items.FILLED_MAP) > mapsBefore;
		}));
		STEPS.add(new Step("edit cost one paper, original kept", 5, mc -> {
			int paper = mc.player.getInventory().countItem(Items.PAPER);
			DslrCameraClient.LOGGER.info("[autotest] paper {} -> {}", paperBefore, paper);
			return paper == paperBefore - 1 && findEditedPhotoSlot(mc.player) >= 0;
		}));
		STEPS.add(new Step("reopen edited copy from its full-colour source", 60, mc -> {
			if (stepTicks == 1) {
				int slot = findEditedPhotoSlot(mc.player);
				if (slot < 0 || slot > 8) {
					throw new IllegalStateException("edited photo not in hotbar");
				}
				mc.player.getInventory().setSelectedSlot(slot);
				useCartographyTable(mc);
			}
			if (mc.gui.screen() instanceof EditorScreen && waitTicks(10)) {
				screenshot(mc, "editor_reopen");
				clickButton(mc, "Cancel");
				return true;
			}
			return false;
		}));
		STEPS.add(new Step("screenshot third person", 60, mc -> {
			if (stepTicks == 1) {
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				mc.player.setYRot(180.0F);
			}
			if (waitTicks(25) && screenshot(mc, "third_person")) {
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				return true;
			}
			return false;
		}));
	}

	private static int mapsBefore;
	private static int paperBefore;
	private static String deopCommand;
	private static int photosBefore;

	/**
	 * Same night scene and manual settings twice, flash off then on: the flash photo's centre (the gold pillar ~3.5m
	 * away) must come out clearly brighter. Compares the saved full-resolution photos, since the live view never
	 * shows the flash.
	 */
	private static void addFlashSteps() {
		STEPS.add(new Step("night, manual f/2.8 1/60 ISO 400, flash off", 200, mc -> {
			if (stepTicks == 1) {
				mc.player.connection.sendCommand("time set midnight");
				mc.player.setYRot(180.0F);
				mc.player.setXRot(0.0F);
				setMode(ExposureSettings.Mode.MANUAL);
				select(CameraState.Setting.APERTURE);
				CameraState.scroll(-20);
				CameraState.scroll(3);
				select(CameraState.Setting.SHUTTER);
				while (ExposureSettings.shutterSeconds() > 1 / 60.0 + 1e-9) {
					CameraState.scroll(1);
				}
				while (ExposureSettings.shutterSeconds() < 1 / 60.0 - 1e-9) {
					CameraState.scroll(-1);
				}
				select(CameraState.Setting.ISO);
				CameraState.scroll(-20);
				CameraState.scroll(2);
				select(CameraState.Setting.SIZE);
				while (!CameraState.size().equals(new PrintSize(1, 1))) {
					CameraState.scroll(1);
				}
				select(CameraState.Setting.FLASH);
				CameraState.scroll(-5);
			}
			return waitTicks(60) && mc.level.getSkyDarken() > 8 && !Flash.willFire();
		}));
		STEPS.add(new Step("photo without flash", 200, mc -> {
			if (stepTicks == 1) {
				logExposure("night, no flash");
				photosBefore = countPhotos(mc);
				click(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			}
			return countPhotos(mc) > photosBefore;
		}));
		STEPS.add(new Step("photo with flash", 200, mc -> {
			if (stepTicks == 1) {
				select(CameraState.Setting.FLASH);
				CameraState.scroll(-5);
				CameraState.scroll(2);
			}
			if (stepTicks == 20) {
				DslrCameraClient.LOGGER.info("[autotest] flash {} willFire {} power {} subject {}m range {}m renderLight {}",
					Flash.mode(), Flash.willFire(), String.format("%.1f", Flash.power()), String.format("%.2f", LightMeter.subjectDistance()),
					String.format("%.1f", Flash.rangeMetres()), String.format("%.4f", LightMeter.subjectRenderLight()));
				if (!Flash.willFire()) {
					throw new IllegalStateException("flash ON but not ready to fire");
				}
				photosBefore = countPhotos(mc);
				click(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			}
			if (stepTicks == 22) {
				screenshot(mc, "flash_pop");
			}
			return stepTicks > 22 && countPhotos(mc) > photosBefore;
		}));
		STEPS.add(new Step("flash photo is brighter", 5, mc -> {
			List<File> photos = photoFiles(mc);
			double without = centreLuma(photos.get(photos.size() - 2));
			double with = centreLuma(photos.get(photos.size() - 1));
			DslrCameraClient.LOGGER.info("[autotest] centre luma: no flash {} / flash {} = {}x", String.format("%.1f", without),
				String.format("%.1f", with), String.format("%.2f", with / Math.max(without, 0.5)));
			return with > without * 1.5 + 10;
		}));
		STEPS.add(new Step("back to noon, flash off, drop op", 120, mc -> {
			if (stepTicks == 1) {
				select(CameraState.Setting.FLASH);
				CameraState.scroll(-5);
				select(CameraState.Setting.ISO);
				CameraState.scroll(-20);
				mc.player.connection.sendCommand("time set noon");
			}
			if (stepTicks == 70) {
				// Let the server's print cooldown pass before dropping op, so the 3x2 print isn't refused.
				mc.player.connection.sendCommand(deopCommand);
			}
			return waitTicks(90) && mc.level.getSkyDarken() < 3;
		}));
	}

	/** Full-resolution photos (not the _map previews) in the photo folder, oldest first. */
	private static List<File> photoFiles(Minecraft mc) {
		File[] files = new File(mc.gameDirectory, "dslr_photos").listFiles((dir, name) -> name.endsWith(".png") && !name.endsWith("_map.png"));
		List<File> list = new ArrayList<>(files == null ? List.of() : List.of(files));
		list.sort(java.util.Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
		return list;
	}

	/** Counts photos whose _map preview has also been written, so the full-resolution file is complete. */
	private static int countPhotos(Minecraft mc) {
		File[] maps = new File(mc.gameDirectory, "dslr_photos").listFiles((dir, name) -> name.endsWith("_map.png"));
		return maps == null ? 0 : maps.length;
	}

	/** Mean 0-255 luma of the middle third of an image. */
	private static double centreLuma(File file) {
		try (var in = new java.io.FileInputStream(file); NativeImage image = NativeImage.read(in)) {
			int w = image.getWidth();
			int h = image.getHeight();
			double sum = 0;
			int n = 0;
			for (int y = h / 3; y < 2 * h / 3; y += 2) {
				for (int x = w / 3; x < 2 * w / 3; x += 2) {
					int p = image.getPixel(x, y);
					sum += 0.2126 * ((p >> 16) & 0xFF) + 0.7152 * ((p >> 8) & 0xFF) + 0.0722 * (p & 0xFF);
					n++;
				}
			}
			return sum / n;
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Could not read " + file, e);
		}
	}

	private static void useCartographyTable(Minecraft mc) {
		BlockPos table = new BlockPos(5, -60, 3);
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(table).add(0, 0.5, 0), Direction.UP, table, false));
	}

	private static void clickButton(Minecraft mc, String label) {
		click(mc, widget -> widget.getMessage().getString().equals(label), label);
	}

	private static void clickButtonStartingWith(Minecraft mc, String prefix) {
		click(mc, widget -> widget.getMessage().getString().startsWith(prefix), prefix);
	}

	/** Clicks a widget on the current screen through the normal mouse handling. */
	private static void click(Minecraft mc, Predicate<AbstractWidget> match, String description) {
		for (var child : mc.gui.screen().children()) {
			if (child instanceof AbstractWidget widget && widget.active && match.test(widget)) {
				double x = widget.getX() + widget.getWidth() / 2.0;
				double y = widget.getY() + widget.getHeight() / 2.0;
				MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
				var screen = mc.gui.screen();
				screen.mouseClicked(event, false);
				screen.mouseReleased(event);
				return;
			}
		}
		throw new IllegalStateException("No button " + description);
	}

	/** The most recently printed single photo (the highest hotbar slot holding one). */
	private static boolean hasActiveButton(Minecraft mc, String prefix) {
		return mc.gui.screen().children().stream().anyMatch(child -> child instanceof AbstractWidget widget
			&& widget.active && widget.getMessage().getString().startsWith(prefix));
	}

	private static int findSinglePhotoSlot(LocalPlayer player) {
		for (int slot = 8; slot >= 0; slot--) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(Items.FILLED_MAP) && stack.getHoverName().getString().equals("Photo")) {
				return slot;
			}
		}
		return -1;
	}

	private static int findEditedPhotoSlot(LocalPlayer player) {
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
			if (stack.is(Items.FILLED_MAP) && lore != null && lore.lines().stream().anyMatch(line -> line.getString().startsWith("Edited by"))) {
				return slot;
			}
		}
		return -1;
	}
	private static boolean exposingSeen;

	private static void click(int mouseButton) {
		KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(mouseButton));
	}

	private static void select(CameraState.Setting setting) {
		for (int i = 0; i < CameraState.Setting.values().length && CameraState.selected() != setting; i++) {
			CameraState.cycleSelected(1);
		}
	}

	private static void setMode(ExposureSettings.Mode mode) {
		select(CameraState.Setting.MODE);
		while (ExposureSettings.mode() != mode) {
			CameraState.scroll(1);
		}
	}

	private static void logExposure(String label) {
		DslrCameraClient.LOGGER.info("[autotest] {}: mode {} {} {} ISO {} comp {} | scene EV {} settings EV {} gain {} focus {}m",
			label, ExposureSettings.modeLabel(), ExposureSettings.shutterLabel(), ExposureSettings.apertureLabel(),
			ExposureSettings.iso(), ExposureSettings.compLabel(ExposureSettings.compThirds()),
			String.format("%.2f", LightMeter.sceneEv()), String.format("%.2f", ExposureSettings.settingsEv()),
			String.format("%.3f", DslrEffect.exposureGain()), String.format("%.2f", LightMeter.focusDistance()));
	}

	private static void tick(Minecraft mc) {
		if (done || mc.player == null || mc.player.connection == null) {
			return;
		}
		if (++ticksInWorld < 60) {
			return;
		}
		Step step = STEPS.get(stepIndex);
		stepTicks++;
		boolean passed;
		try {
			passed = step.action().test(mc);
		} catch (RuntimeException e) {
			DslrCameraClient.LOGGER.error("[autotest] step '{}' threw", step.name(), e);
			finish(mc, "FAIL at '" + step.name() + "': " + e);
			return;
		}
		if (passed) {
			DslrCameraClient.LOGGER.info("[autotest] ok: {} ({} ticks)", step.name(), stepTicks);
			stepIndex++;
			stepTicks = 0;
			if (stepIndex == STEPS.size()) {
				finish(mc, "PASS");
			}
		} else if (stepTicks >= step.timeoutTicks()) {
			finish(mc, "FAIL at '" + step.name() + "' after " + stepTicks + " ticks (screen: "
				+ (mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName()) + ")");
		}
	}

	private static boolean waitTicks(int ticks) {
		return stepTicks >= ticks;
	}

	private static boolean screenshot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "autotest_" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
			message -> DslrCameraClient.LOGGER.info("[autotest] screenshot {}", name));
		return true;
	}

	private static int findFramedPhotoSlot(LocalPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(Items.FILLED_MAP) && stack.getHoverName().getString().startsWith("Photo (" + TEST_SIZE.label() + ")")) {
				return slot;
			}
		}
		return -1;
	}

	private static ItemFrame frameAt(Minecraft mc, int x, int y) {
		AABB box = new AABB(x, y, FRAME_Z, x + 1, y + 1, FRAME_Z + 1);
		List<ItemFrame> frames = mc.level.getEntitiesOfClass(ItemFrame.class, box);
		return frames.isEmpty() ? null : frames.getFirst();
	}

	private static void finish(Minecraft mc, String result) {
		done = true;
		CameraState.setRaised(false);
		mc.options.keyUse.setDown(false);
		DslrCameraClient.LOGGER.info("[autotest] {}", result);
		mc.stop();
	}
}
