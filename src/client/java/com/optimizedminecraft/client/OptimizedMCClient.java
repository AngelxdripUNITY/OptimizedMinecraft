package com.optimizedminecraft.client;

import com.optimizedminecraft.MemoryAdvisor;
import com.optimizedminecraft.OMConfig;
import com.optimizedminecraft.OptimizedMC;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

public class OptimizedMCClient implements ClientModInitializer {
	/** {@link Options#framerateLimit()} treats its maximum value as "unlimited". */
	private static final int UNLIMITED_FPS = 260;

	private boolean memoryWarningShown;

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(this::applyFirstLaunchTweaks);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> checkMemory(client)));
	}

	private void applyFirstLaunchTweaks(Minecraft client) {
		OMConfig config = OMConfig.get();
		if (config.firstLaunchDone) {
			return;
		}
		if (config.unlockFramerateOnFirstLaunch) {
			Options options = client.options;
			options.framerateLimit().set(UNLIMITED_FPS);
			options.enableVsync().set(false);
			options.save();
			OptimizedMC.LOGGER.info("First launch: FPS limit set to unlimited and VSync turned off");
		}
		config.firstLaunchDone = true;
		config.save();
	}

	private void checkMemory(Minecraft client) {
		OMConfig config = OMConfig.get();
		if (!config.memoryWarnings || memoryWarningShown) {
			return;
		}
		int renderDistance = client.options.getEffectiveRenderDistance();
		long needed = MemoryAdvisor.recommendedHeapMb(renderDistance);
		long have = MemoryAdvisor.maxHeapMb();
		if (have >= needed) {
			return;
		}
		memoryWarningShown = true;
		OptimizedMC.LOGGER.warn("Render distance {} wants about {} MB of heap but Java only has {} MB (-Xmx). "
				+ "Run tools/jvm-args from the OptimizedMinecraft repo to get launcher arguments.", renderDistance, needed, have);
		SystemToast.addOrUpdate(client.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.translatable("optimizedmc.toast.memory.title"),
				Component.translatable("optimizedmc.toast.memory.body", renderDistance, Math.ceilDiv(needed, 1024), Math.ceilDiv(have, 1024)));
	}
}
