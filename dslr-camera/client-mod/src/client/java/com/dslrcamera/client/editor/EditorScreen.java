package com.dslrcamera.client.editor;

import com.dslrcamera.client.DslrCameraClient;
import com.dslrcamera.client.PrintSize;
import com.dslrcamera.client.net.PhotoPayload;
import com.dslrcamera.client.photo.MapQuantizer;
import com.dslrcamera.client.photo.PhotoTransfer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Photo editor opened at a cartography table. Shows a live preview of the adjusted photo (optionally exactly as it
 * will look once dithered onto maps) and prints a new copy through the server.
 */
public final class EditorScreen extends Screen {
	private static final Identifier PREVIEW_TEXTURE = DslrCameraClient.id("editor/preview");
	private static final int PANEL_WIDTH = 190;
	private static final int ROW = 19;

	/** Which right-hand panel is showing: one of the adjustment tabs, or the presets list. */
	private enum Panel {
		LIGHT, COLOR, EFFECTS, PRESETS
	}

	private final int session;
	private final PrintSize size;
	private final String caption;
	private final int[] source;
	private final int width0;
	private final int height0;
	private final String costLabel;
	private final Adjustments adjustments = new Adjustments();

	private Panel panel = Panel.LIGHT;
	private boolean mapView;
	private boolean showOriginal;
	private boolean dirty = true;
	private boolean printing;
	private String status;
	private boolean statusIsError;
	private DynamicTexture texture;

	public EditorScreen(int session, PrintSize size, String caption, int[] source, int cost, String costItem) {
		super(Component.literal("Photo Editor"));
		this.session = session;
		this.size = size;
		this.caption = caption;
		this.source = source;
		this.width0 = PhotoTransfer.TILE * size.cols();
		this.height0 = PhotoTransfer.TILE * size.rows();
		this.costLabel = cost > 0 ? cost + " " + costItem : "free";
	}

	@Override
	protected void init() {
		if (texture == null) {
			texture = new DynamicTexture(() -> "DSLR photo editor preview", width0, height0, true);
			minecraft.getTextureManager().register(PREVIEW_TEXTURE, texture);
			dirty = true;
		}

		int panelWidth = Math.min(PANEL_WIDTH, width * 45 / 100);
		int panelX = width - panelWidth - 8;
		int tabWidth = (panelWidth - 6) / 4;
		Panel[] panels = Panel.values();
		for (int i = 0; i < panels.length; i++) {
			Panel target = panels[i];
			String label = target == Panel.PRESETS ? "Presets" : Adjustments.Tab.values()[i].label;
			Button tab = Button.builder(Component.literal(label), b -> {
				panel = target;
				rebuildWidgets();
			}).bounds(panelX + i * (tabWidth + 2), 22, tabWidth, 16).build();
			tab.active = panel != target;
			addRenderableWidget(tab);
		}

		int y = 44;
		if (panel == Panel.PRESETS) {
			int columnWidth = (panelWidth - 4) / 2;
			int index = 0;
			for (Adjustments.Preset preset : Adjustments.PRESETS) {
				int x = panelX + (index % 2) * (columnWidth + 4);
				int rowY = y + (index / 2) * ROW;
				addRenderableWidget(Button.builder(Component.literal(preset.name()), b -> {
					adjustments.apply(preset);
					markDirty();
				}).bounds(x, rowY, columnWidth, 16).build());
				index++;
			}
		} else {
			Adjustments.Tab tab = Adjustments.Tab.values()[panel.ordinal()];
			for (Adjustments.Param param : Adjustments.Param.values()) {
				if (param.tab == tab) {
					ParamSlider slider = new ParamSlider(panelX, y, panelWidth, 16, param, adjustments, this::markDirty);
					slider.setTooltip(Tooltip.create(Component.literal("Double-click to reset")));
					addRenderableWidget(slider);
					y += ROW;
				}
			}
		}

		int bottom = height - 24;
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
			adjustments.reset();
			rebuildWidgets();
			markDirty();
		}).bounds(8, bottom, 46, 18).build());
		addRenderableWidget(Button.builder(Component.literal(mapView ? "Map view: On" : "Map view: Off"), b -> {
			mapView = !mapView;
			b.setMessage(Component.literal(mapView ? "Map view: On" : "Map view: Off"));
			markDirty();
		}).bounds(58, bottom, 84, 18).tooltip(Tooltip.create(Component.literal("Show exactly how the print will look in map colours"))).build());
		addRenderableWidget(Button.builder(Component.literal(showOriginal ? "Before" : "After"), b -> {
			showOriginal = !showOriginal;
			b.setMessage(Component.literal(showOriginal ? "Before" : "After"));
			markDirty();
		}).bounds(146, bottom, 46, 18).tooltip(Tooltip.create(Component.literal("Compare with the original"))).build());

		int printWidth = Math.max(90, font.width("Print (" + costLabel + ")") + 12);
		Button print = Button.builder(Component.literal("Print (" + costLabel + ")"), b -> print())
			.bounds(width - printWidth - 8, bottom, printWidth, 18).build();
		print.active = !printing;
		addRenderableWidget(print);
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
			.bounds(width - printWidth - 8 - 54, bottom, 50, 18).build());
	}

	public int session() {
		return session;
	}

	/** The server's answer to Print: close on success, otherwise keep the edits and show why. */
	public void onPrintResult(boolean success, String message) {
		printing = false;
		if (success) {
			onClose();
			return;
		}
		status = message;
		statusIsError = true;
		rebuildWidgets();
	}

	private void markDirty() {
		dirty = true;
	}

	@Override
	public void tick() {
		if (!dirty || texture == null) {
			return;
		}
		dirty = false;
		int[] shown = showOriginal ? source : adjustments.process(source, width0, height0);
		var pixels = texture.getPixels();
		if (mapView) {
			byte[] colors = MapQuantizer.quantize(shown, width0, height0);
			for (int i = 0; i < colors.length; i++) {
				pixels.setPixel(i % width0, i / width0, MapQuantizer.toArgb(colors[i]));
			}
		} else {
			for (int i = 0; i < shown.length; i++) {
				pixels.setPixel(i % width0, i / width0, 0xFF000000 | shown[i]);
			}
		}
		texture.upload();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);

		int panelWidth = Math.min(PANEL_WIDTH, width * 45 / 100);
		int areaX = 8;
		int areaY = 22;
		int areaW = width - panelWidth - 24;
		int areaH = height - areaY - 44;
		double scale = Math.min((double) areaW / width0, (double) areaH / height0);
		int drawW = (int) (width0 * scale);
		int drawH = (int) (height0 * scale);
		int x = areaX + (areaW - drawW) / 2;
		int y = areaY + (areaH - drawH) / 2;
		graphics.fill(x - 3, y - 3, x + drawW + 3, y + drawH + 3, 0xFFEFE8D8);
		graphics.blit(RenderPipelines.GUI_TEXTURED, PREVIEW_TEXTURE, x, y, 0, 0, drawW, drawH, width0, height0, width0, height0);

		if (status != null) {
			graphics.text(font, font.plainSubstrByWidth(status, areaW), areaX, height - 36, statusIsError ? 0xFFFF6060 : 0xFFA0FFA0);
		} else {
			String info = size.label() + " photo · " + caption;
			graphics.text(font, font.plainSubstrByWidth(info, areaW), areaX, height - 36, 0xFFA0A0A0);
		}
		if (printing) {
			graphics.centeredText(font, "Printing…", x + drawW / 2, y + drawH / 2 - 4, 0xFFFFFFFF);
		}
	}

	private void print() {
		if (printing || !PhotoTransfer.canSend()) {
			return;
		}
		printing = true;
		status = null;
		rebuildWidgets();
		int[] edited = adjustments.process(source, width0, height0);
		String newCaption = caption.isBlank() ? "Edited" : caption;
		Util.backgroundExecutor().execute(() -> {
			PhotoTransfer.Print print = PhotoTransfer.prepare(edited, size, newCaption, session);
			minecraft.execute(() -> PhotoTransfer.send(print));
		});
	}

	@Override
	public void onClose() {
		if (ClientPlayNetworking.canSend(PhotoPayload.TYPE)) {
			ClientPlayNetworking.send(PhotoPayload.closeEditor(session));
		}
		super.onClose();
	}

	@Override
	public void removed() {
		minecraft.getTextureManager().release(PREVIEW_TEXTURE);
		texture = null;
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
