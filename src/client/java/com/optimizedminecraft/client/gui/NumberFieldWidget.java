package com.optimizedminecraft.client.gui;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.optimizedminecraft.OMConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * "Label: 96" box you click and type a number into (Enter or clicking away applies it,
 * Esc cancels, arrow keys or the mouse wheel step it while focused). Replaces a slider.
 */
public class NumberFieldWidget extends AbstractWidget {
	private final Component label;
	private final int min;
	private final int max;
	private final IntSupplier getter;
	private final IntConsumer setter;
	private String typed;

	public NumberFieldWidget(int x, int y, int width, int height, Component label, int min, int max, IntSupplier getter, IntConsumer setter) {
		super(x, y, width, height, label);
		this.label = label;
		this.min = min;
		this.max = max;
		this.getter = getter;
		this.setter = setter;
		this.setTooltip(Tooltip.create(Component.translatable("optimizedmc.number_field.tooltip", min, max)));
		this.updateMessage();
	}

	public static NumberFieldWidget renderDistance(Options options, int x, int y, int width) {
		int max = OMConfig.get().clampedMaxRenderDistance();
		return new NumberFieldWidget(x, y, width, 20, Component.translatable("options.renderDistance"), 2, max,
				() -> options.renderDistance().get(), value -> options.renderDistance().set(value));
	}

	private void updateMessage() {
		this.setMessage(Component.translatable("optimizedmc.number_field.value", this.label, this.getter.getAsInt()));
	}

	private void commit() {
		if (this.typed == null) {
			return;
		}
		if (!this.typed.isEmpty()) {
			try {
				this.apply(Integer.parseInt(this.typed));
			} catch (NumberFormatException ignored) {
				// Too many digits; keep the old value.
			}
		}
		this.typed = null;
		this.updateMessage();
	}

	private void apply(int value) {
		this.setter.accept(Math.max(this.min, Math.min(this.max, value)));
		this.updateMessage();
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		boolean highlighted = this.isFocused() || this.isHovered();
		graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), highlighted ? 0xFFFFFFFF : 0xFF8B8B8B);
		graphics.fill(this.getX() + 1, this.getY() + 1, this.getRight() - 1, this.getBottom() - 1, 0xFF101010);
		String value;
		if (this.typed != null) {
			boolean caret = (System.currentTimeMillis() / 300) % 2 == 0;
			value = this.typed + (caret ? "_" : " ");
		} else {
			value = Integer.toString(this.getter.getAsInt());
		}
		Component text = Component.translatable("optimizedmc.number_field.value", this.label, value);
		graphics.drawCenteredString(Minecraft.getInstance().font, text, this.getX() + this.getWidth() / 2,
				this.getY() + (this.getHeight() - 8) / 2, this.typed != null ? 0xFFFFFF55 : 0xFFFFFFFF);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		this.typed = "";
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!this.isFocused()) {
			return false;
		}
		int codepoint = event.codepoint();
		if (codepoint >= '0' && codepoint <= '9') {
			if (this.typed == null) {
				this.typed = "";
			}
			if (this.typed.length() < 5) {
				this.typed += (char) codepoint;
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!this.isFocused()) {
			return false;
		}
		switch (event.key()) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				this.commit();
				return true;
			}
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (this.typed == null) {
					this.typed = Integer.toString(this.getter.getAsInt());
				}
				if (!this.typed.isEmpty()) {
					this.typed = this.typed.substring(0, this.typed.length() - 1);
				}
				return true;
			}
			case GLFW.GLFW_KEY_ESCAPE -> {
				if (this.typed != null) {
					this.typed = null;
					return true;
				}
				return false;
			}
			case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_RIGHT -> {
				this.typed = null;
				this.apply(this.getter.getAsInt() + 1);
				return true;
			}
			case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT -> {
				this.typed = null;
				this.apply(this.getter.getAsInt() - 1);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!this.isFocused() || scrollY == 0) {
			return false;
		}
		this.typed = null;
		this.apply(this.getter.getAsInt() + (scrollY > 0 ? 1 : -1));
		return true;
	}

	@Override
	public void setFocused(boolean focused) {
		if (!focused) {
			this.commit();
		}
		super.setFocused(focused);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}
