package com.optimizedminecraft.client.gui;

import java.util.List;

import com.optimizedminecraft.OMConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/** The OptimizedMinecraft section added to the bottom of Video Settings. */
public final class OMVideoOptions {
	private OMVideoOptions() {
	}

	public static List<AbstractWidget> widgets(Options options) {
		OMConfig config = OMConfig.get();
		return List.of(
				new NumberFieldWidget(0, 0, 150, 20, Component.translatable("optimizedmc.options.lodDistance"), 0, OMConfig.MAX_LOD_DISTANCE,
						() -> OMConfig.get().clampedLodDistance(), value -> save(c -> c.lodDistance = value)),
				OptionInstance.createBoolean("optimizedmc.options.lodEnabled", config.lodEnabled,
						value -> save(c -> c.lodEnabled = value)).createButton(options),
				new NumberFieldWidget(0, 0, 150, 20, Component.translatable("optimizedmc.options.lodPixelError"), 1, 64,
						() -> Math.round(OMConfig.get().lodPixelError), value -> save(c -> c.lodPixelError = value)),
				OptionInstance.createBoolean("optimizedmc.options.lodDebugColors", config.lodDebugColors,
						value -> save(c -> c.lodDebugColors = value)).createButton(options),
				OptionInstance.createBoolean("optimizedmc.options.reduceFog", config.reduceFog,
						value -> save(c -> c.reduceFog = value)).createButton(options),
				OptionInstance.createBoolean("optimizedmc.options.chunkMap", config.showChunkMap,
						value -> save(c -> c.showChunkMap = value)).createButton(options));
	}

	private static void save(java.util.function.Consumer<OMConfig> change) {
		OMConfig config = OMConfig.get();
		change.accept(config);
		config.save();
	}
}
