package com.optimizedminecraft.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.optimizedminecraft.OMConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * All of the mod's settings on one screen, reachable from the Options screen button or the
 * F8 key. Exists because Sodium replaces Video Settings, hiding anything added there.
 */
public class OptimizedSettingsScreen extends Screen {
	private final Screen parent;

	public OptimizedSettingsScreen(Screen parent) {
		super(Component.translatable("optimizedmc.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Options options = this.minecraft.options;
		List<AbstractWidget> widgets = new ArrayList<>();
		widgets.add(NumberFieldWidget.renderDistance(options, 0, 0, 150));
		widgets.add(options.cloudStatus().createButton(options, 0, 0, 150));
		widgets.addAll(OMVideoOptions.widgets(options));

		int left = this.width / 2 - 155;
		int top = Math.max(32, this.height / 6);
		for (int i = 0; i < widgets.size(); i++) {
			AbstractWidget widget = widgets.get(i);
			widget.setPosition(left + (i % 2) * 160, top + (i / 2) * 24);
			this.addRenderableWidget(widget);
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.width / 2 - 100, this.height - 27, 200, 20)
				.build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
		graphics.drawCenteredString(this.font, Component.translatable("optimizedmc.settings.hint"), this.width / 2, this.height - 42, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		this.minecraft.options.save();
		OMConfig.get().save();
		this.minecraft.setScreen(this.parent);
	}
}
