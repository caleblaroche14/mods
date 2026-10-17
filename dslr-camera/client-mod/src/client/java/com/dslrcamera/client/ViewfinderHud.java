package com.dslrcamera.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.dslrcamera.client.exposure.ExposureSettings;
import com.dslrcamera.client.exposure.Flash;
import com.dslrcamera.client.exposure.LightMeter;
import com.dslrcamera.client.photo.PhotoCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * DSLR-style viewfinder: print crop mask with tile grid, AF brackets, mode dial, a command-dial ribbon for the
 * selected setting, and a green LCD status strip with an exposure meter.
 */
public final class ViewfinderHud {
	private static final int MASK = 0xA0000000;
	private static final int LINE = 0xCCFFFFFF;
	private static final int GRID = 0x40FFFFFF;
	private static final int LCD = 0xFF8CFF7A;
	private static final int LCD_DIM = 0xFF4A8A42;
	private static final int LCD_BG = 0xD0000000;
	private static final int BLACK = 0xFF000000;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int RED = 0xFFFF4040;

	private ViewfinderHud() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!CameraState.isActive()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		int w = g.guiWidth();
		int h = g.guiHeight();

		if (PhotoCapture.isExposing()) {
			renderExposing(g, font, w, h);
			renderFlashPop(g, w, h);
			return;
		}

		PrintSize size = CameraState.size();
		// Same maths as PhotoCapture.cropRect, so the mask shows exactly what gets printed.
		int cropW = w;
		int cropH = w * size.rows() / size.cols();
		if (cropH > h) {
			cropH = h;
			cropW = h * size.cols() / size.rows();
		}
		int left = (w - cropW) / 2;
		int top = (h - cropH) / 2;
		int right = left + cropW;
		int bottom = top + cropH;
		if (left > 0) {
			g.fill(0, 0, left, h, MASK);
			g.fill(right, 0, w, h, MASK);
		}
		if (top > 0) {
			g.fill(left, 0, right, top, MASK);
			g.fill(left, bottom, right, h, MASK);
		}
		for (int c = 1; c < size.cols(); c++) {
			int x = left + cropW * c / size.cols();
			g.fill(x, top, x + 1, bottom, GRID);
		}
		for (int r = 1; r < size.rows(); r++) {
			int y = top + cropH * r / size.rows();
			g.fill(left, y, right, y + 1, GRID);
		}

		int inset = 6;
		corner(g, left + inset, top + inset, 12, 1, 1, LINE);
		corner(g, right - inset, top + inset, 12, -1, 1, LINE);
		corner(g, left + inset, bottom - inset, 12, 1, -1, LINE);
		corner(g, right - inset, bottom - inset, 12, -1, -1, LINE);

		int cx = w / 2;
		int cy = h / 2;
		int afColour = LightMeter.inFocus() ? LCD : WHITE;
		corner(g, cx - 9, cy - 6, 4, 1, 1, afColour);
		corner(g, cx + 9, cy - 6, 4, -1, 1, afColour);
		corner(g, cx - 9, cy + 6, 4, 1, -1, afColour);
		corner(g, cx + 9, cy + 6, 4, -1, -1, afColour);

		// Readouts span the whole screen, not the crop, so narrow print sizes still have room for them.
		renderTopBar(g, font, 8, w - 8, 8);
		int stripY = h - 16;
		renderStatusStrip(g, font, 6, w - 6, stripY);
		renderDial(g, font, cx, stripY - 22);

		float blackout = CameraState.shutterBlackout();
		if (blackout > 0) {
			g.fill(0, 0, w, h, (int) (blackout * 255) << 24);
		}
		renderFlashPop(g, w, h);
	}

	private static void renderFlashPop(GuiGraphicsExtractor g, int w, int h) {
		float pop = Flash.popAlpha();
		if (pop > 0) {
			g.fill(0, 0, w, h, (int) (pop * 255) << 24 | 0xFFFFFF);
		}
	}

	private static void renderTopBar(GuiGraphicsExtractor g, Font font, int left, int right, int y) {
		String af = LightMeter.focusMode().label + " " + LightMeter.distanceLabel(
			LightMeter.focusMode() == LightMeter.FocusMode.MANUAL ? LightMeter.MANUAL_DISTANCES[LightMeter.manualIndex()] : LightMeter.focusDistance());
		g.fill(left - 3, y - 3, left + font.width(af) + 3, y + 10, LCD_BG);
		g.text(font, af, left, y, LightMeter.inFocus() ? LCD : LCD_DIM);

		int dialWidth = 0;
		for (ExposureSettings.Mode mode : ExposureSettings.Mode.values()) {
			dialWidth += font.width(mode.label) + 6;
		}
		String hint = DslrCameraClient.selectKey().getTranslatedKeyMessage().getString() + " select · Scroll adjust · "
			+ DslrCameraClient.focusKey().getTranslatedKeyMessage().getString() + " AF lock · LMB shoot · RMB lower";
		int hintX = (left + right - font.width(hint)) / 2;
		if (hintX > left + font.width(af) + 8 && hintX + font.width(hint) < right - dialWidth - 8) {
			g.text(font, hint, hintX, y, 0xB0FFFFFF);
		}

		// Mode dial: M A S P with the active mode lit.
		g.fill(right - dialWidth - 1, y - 3, right + 3, y + 10, LCD_BG);
		int x = right;
		ExposureSettings.Mode[] modes = ExposureSettings.Mode.values();
		for (int i = modes.length - 1; i >= 0; i--) {
			String label = modes[i].label;
			x -= font.width(label) + 6;
			boolean on = modes[i] == ExposureSettings.mode();
			if (on) {
				g.fill(x - 2, y - 2, x + font.width(label) + 2, y + 9, LCD);
			}
			g.text(font, label, x, y, on ? BLACK : LCD_DIM);
		}
	}

	/** Green LCD strip: shutter, aperture, meter, ISO, compensation, flash, focal length and print size. */
	private static void renderStatusStrip(GuiGraphicsExtractor g, Font font, int left, int right, int y) {
		g.fill(left, y - 3, right, y + 11, LCD_BG);
		List<Item> items = new ArrayList<>();
		items.add(new Item(CameraState.Setting.SHUTTER, ExposureSettings.shutterLabel()));
		items.add(new Item(CameraState.Setting.APERTURE, ExposureSettings.apertureLabel()));
		items.add(new Item(null, null));
		items.add(new Item(CameraState.Setting.ISO, "ISO " + ExposureSettings.iso()));
		if (ExposureSettings.compensationApplies()) {
			items.add(new Item(CameraState.Setting.COMPENSATION, "☀" + ExposureSettings.compLabel(ExposureSettings.compThirds())));
		}
		items.add(flashItem());
		items.add(new Item(CameraState.Setting.ZOOM, Math.round(CameraState.targetFocal()) + "mm"));
		items.add(new Item(CameraState.Setting.SIZE, CameraState.size().label()));

		int meterWidth = 44;
		int total = 0;
		for (Item item : items) {
			total += item.label == null ? meterWidth : font.width(item.label);
		}
		int gap = Math.max(6, (right - left - 8 - total) / Math.max(1, items.size() - 1));
		int x = left + 4;
		for (Item item : items) {
			if (item.label == null) {
				renderMeter(g, x, y, meterWidth);
				x += meterWidth + gap;
				continue;
			}
			int width = font.width(item.label);
			boolean selected = item.setting == CameraState.selected();
			if (selected) {
				g.fill(x - 2, y - 2, x + width + 2, y + 10, LCD);
			}
			g.text(font, item.label, x, y, selected ? BLACK : item.colour);
			x += width + gap;
		}
	}

	private record Item(CameraState.Setting setting, String label, int colour) {
		Item(CameraState.Setting setting, String label) {
			this(setting, label, LCD);
		}
	}

	/**
	 * "⚡ON 26m": mode plus full-power range. Lit when the flash will fire, dim when it won't, blinking while it
	 * recharges, red when the subject is beyond its reach.
	 */
	private static Item flashItem() {
		Flash.Mode mode = Flash.mode();
		if (mode == Flash.Mode.OFF) {
			return new Item(CameraState.Setting.FLASH, "⚡OFF", LCD_DIM);
		}
		String label = "⚡" + mode.shortLabel + " " + Math.round(Flash.rangeMetres()) + "m";
		int colour;
		if (!Flash.isReady()) {
			colour = (System.currentTimeMillis() / 250) % 2 == 0 ? LCD : LCD_DIM;
		} else if (!Flash.willFire()) {
			colour = LCD_DIM;
		} else {
			colour = Flash.subjectOutOfRange() ? RED : LCD;
		}
		return new Item(CameraState.Setting.FLASH, label, colour);
	}

	/** Classic -3..+3 exposure scale with a bar from zero to the current deviation. */
	private static void renderMeter(GuiGraphicsExtractor g, int x, int y, int width) {
		double deviation = LightMeter.sceneEv() - ExposureSettings.settingsEv();
		int centre = x + width / 2;
		int step = (width - 2) / 6;
		g.fill(x, y + 4, x + width, y + 5, LCD_DIM);
		for (int i = -3; i <= 3; i++) {
			int tx = centre + i * step;
			int th = i == 0 ? 4 : 2;
			g.fill(tx, y + 4 - th, tx + 1, y + 4, LCD);
		}
		double clamped = Mth.clamp(deviation, -3.0, 3.0);
		int end = centre + (int) Math.round(clamped * step);
		g.fill(Math.min(centre, end), y + 6, Math.max(centre, end) + 1, y + 8, LCD);
		boolean blink = (System.currentTimeMillis() / 300) % 2 == 0;
		if (Math.abs(deviation) > 3 && blink) {
			int ax = deviation > 0 ? x + width + 1 : x - 3;
			g.fill(ax, y + 2, ax + 2, y + 8, RED);
		}
	}

	/** A strip of neighbouring values for the selected setting, like looking at the edge of a command dial. */
	private static void renderDial(GuiGraphicsExtractor g, Font font, int cx, int y) {
		CameraState.Setting setting = CameraState.selected();
		List<String> values = new ArrayList<>();
		int current;
		switch (setting) {
			case MODE -> {
				for (ExposureSettings.Mode mode : ExposureSettings.Mode.values()) {
					values.add(mode.label);
				}
				current = ExposureSettings.mode().ordinal();
			}
			case SHUTTER -> {
				for (int i = 0; i < ExposureSettings.SHUTTERS.length; i++) {
					values.add(ExposureSettings.shutterLabel(i));
				}
				current = ExposureSettings.shutterIndex();
			}
			case APERTURE -> {
				for (int i = 0; i < ExposureSettings.APERTURES.length; i++) {
					values.add(ExposureSettings.apertureLabel(i));
				}
				current = ExposureSettings.apertureIndex();
			}
			case ISO -> {
				for (int i = 0; i < ExposureSettings.ISOS.length; i++) {
					values.add(ExposureSettings.isoLabel(i));
				}
				current = ExposureSettings.isoIndex();
			}
			case COMPENSATION -> {
				for (int t = -ExposureSettings.MAX_COMP_THIRDS; t <= ExposureSettings.MAX_COMP_THIRDS; t++) {
					values.add(ExposureSettings.compLabel(t));
				}
				current = ExposureSettings.compThirds() + ExposureSettings.MAX_COMP_THIRDS;
			}
			case FLASH -> {
				for (Flash.Mode mode : Flash.Mode.values()) {
					values.add(mode.label);
				}
				current = Flash.mode().ordinal();
			}
			case FOCUS -> {
				values.add("AF");
				for (double d : LightMeter.MANUAL_DISTANCES) {
					values.add(LightMeter.distanceLabel(d));
				}
				current = LightMeter.focusMode() == LightMeter.FocusMode.MANUAL ? LightMeter.manualIndex() + 1 : 0;
			}
			case SIZE -> {
				for (PrintSize size : PrintSize.ALL) {
					values.add(size.label());
				}
				current = PrintSize.ALL.indexOf(CameraState.size());
			}
			default -> {
				float focal = CameraState.targetFocal();
				for (int k = -3; k <= 3; k++) {
					float f = (float) (focal * Math.pow(1.1, k));
					values.add(f < CameraState.MIN_FOCAL - 0.01 || f > CameraState.MAX_FOCAL + 0.01 ? "" : Math.round(f) + "mm");
				}
				current = 3;
			}
		}

		int spacing = 34;
		int halfWidth = spacing * 3 + 20;
		g.fill(cx - halfWidth, y - 4, cx + halfWidth, y + 14, 0x90000000);
		g.text(font, setting.label, cx - halfWidth + 4, y - 13, 0xD0FFFFFF);
		for (int k = -3; k <= 3; k++) {
			int index = current + k;
			if (index < 0 || index >= values.size() || values.get(index).isEmpty()) {
				continue;
			}
			String label = values.get(index);
			int x = cx + k * spacing;
			int alpha = k == 0 ? 0xFF : 0xFF - Math.abs(k) * 0x45;
			int colour = alpha << 24 | (k == 0 ? 0xFFFFFF : 0x9FD89A);
			g.text(font, label, x - font.width(label) / 2, y, colour);
			g.fill(x, y + 10, x + 1, y + 13, alpha << 24 | 0xAAAAAA);
		}
		g.outline(cx - 16, y - 3, 33, 13, LCD);
	}

	private static void renderExposing(GuiGraphicsExtractor g, Font font, int w, int h) {
		g.fill(0, 0, w, h, BLACK);
		boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
		String text = String.format(Locale.ROOT, "%.1fs", PhotoCapture.remainingSeconds());
		int x = (w - font.width(text)) / 2;
		if (blink) {
			g.fill(x - 10, h / 2 - 1, x - 4, h / 2 + 5, RED);
		}
		g.text(font, text, x, h / 2 - 2, WHITE);
		String hint = "Exposing… hold still";
		g.text(font, hint, (w - font.width(hint)) / 2, h / 2 + 12, 0xFF888888);
	}

	private static void corner(GuiGraphicsExtractor g, int x, int y, int arm, int dx, int dy, int colour) {
		int x2 = x + dx * arm;
		int y2 = y + dy * arm;
		g.fill(Math.min(x, x2), y, Math.max(x, x2) + 1, y + 1, colour);
		g.fill(x, Math.min(y, y2), x + 1, Math.max(y, y2) + 1, colour);
	}
}
