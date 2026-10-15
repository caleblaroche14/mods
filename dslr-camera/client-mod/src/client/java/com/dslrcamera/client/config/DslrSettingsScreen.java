package com.dslrcamera.client.config;

import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Depth of field and bokeh tuning. Opened from Mod Menu; changes apply immediately and are saved on close. */
public final class DslrSettingsScreen extends Screen {
	private static final int WIDTH = 260;
	private static final int ROW = 24;

	private final Screen parent;

	public DslrSettingsScreen(Screen parent) {
		super(Component.literal("DSLR Camera Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = (width - WIDTH) / 2;
		int y = 48;
		for (DslrConfig.Option option : DslrConfig.Option.values()) {
			OptionSlider slider = new OptionSlider(x, y, WIDTH, 20, option);
			slider.setTooltip(Tooltip.create(Component.literal(option.description + "\nDouble-click to reset.")));
			addRenderableWidget(slider);
			y += ROW;
		}

		int buttonWidth = (WIDTH - 4) / 2;
		int bottom = Math.max(y + 12, height - 28);
		addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> {
			DslrConfig.reset();
			rebuildWidgets();
		}).bounds(x, bottom, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
			.bounds(x + buttonWidth + 4, bottom, buttonWidth, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
		graphics.centeredText(font, "Depth of field & bokeh", width / 2, 30, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		DslrConfig.save();
		minecraft.gui.setScreen(parent);
	}

	private static final class OptionSlider extends AbstractSliderButton {
		private final DslrConfig.Option option;

		OptionSlider(int x, int y, int width, int height, DslrConfig.Option option) {
			super(x, y, width, height, Component.empty(), normalise(option, DslrConfig.get(option)));
			this.option = option;
			updateMessage();
		}

		private static double normalise(DslrConfig.Option option, double value) {
			return (value - option.min) / (option.max - option.min);
		}

		@Override
		protected void updateMessage() {
			String value = String.format(Locale.ROOT, option.format, DslrConfig.get(option));
			setMessage(Component.literal(option.label + ": " + value));
		}

		@Override
		protected void applyValue() {
			double raw = option.min + value * (option.max - option.min);
			DslrConfig.set(option, Math.round(raw / option.step) * option.step);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			if (doubleClick) {
				DslrConfig.set(option, option.defaultValue);
				setValue(normalise(option, option.defaultValue));
				updateMessage();
				return;
			}
			super.onClick(event, doubleClick);
		}
	}
}
