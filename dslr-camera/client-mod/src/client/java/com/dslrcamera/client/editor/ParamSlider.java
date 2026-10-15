package com.dslrcamera.client.editor;

import java.util.Locale;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A slider bound to one adjustment. Double-click resets it. */
final class ParamSlider extends AbstractSliderButton {
	private final Adjustments.Param param;
	private final Adjustments adjustments;
	private final Runnable onChange;

	ParamSlider(int x, int y, int width, int height, Adjustments.Param param, Adjustments adjustments, Runnable onChange) {
		super(x, y, width, height, Component.empty(), normalise(param, adjustments.get(param)));
		this.param = param;
		this.adjustments = adjustments;
		this.onChange = onChange;
		updateMessage();
	}

	private static double normalise(Adjustments.Param param, double value) {
		return (value - param.min) / (param.max - param.min);
	}

	@Override
	protected void updateMessage() {
		String value = String.format(Locale.ROOT, param.format, adjustments.get(param));
		setMessage(Component.literal(param.label + ": " + value));
	}

	@Override
	protected void applyValue() {
		double raw = param.min + value * (param.max - param.min);
		double snapped = Math.round(raw / param.step) * param.step;
		adjustments.set(param, snapped);
		onChange.run();
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (doubleClick) {
			adjustments.set(param, param.defaultValue);
			setValue(normalise(param, param.defaultValue));
			updateMessage();
			onChange.run();
			return;
		}
		super.onClick(event, doubleClick);
	}
}
