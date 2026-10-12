package com.optimizedminecraft.client;

import com.optimizedminecraft.MemoryAdvisor;
import com.optimizedminecraft.OMConfig;
import com.optimizedminecraft.OptimizedMC;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import com.mojang.blaze3d.platform.InputConstants;
import com.optimizedminecraft.client.hud.ChunkMapHud;
import com.optimizedminecraft.client.lod.LodManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class OptimizedMCClient implements ClientModInitializer {
	/** {@link Options#framerateLimit()} treats its maximum value as "unlimited". */
	private static final int UNLIMITED_FPS = 260;

	private boolean memoryWarningShown;

	/** Bump when adding a new one-time default to {@link #applyNewDefaults}. */
	private static final int DEFAULTS_VERSION = 1;

	private KeyMapping chunkMapKey;

	@Override
	public void onInitializeClient() {
		LodManager.init();
		ClientLifecycleEvents.CLIENT_STARTED.register(this::applyFirstLaunchTweaks);
		ClientLifecycleEvents.CLIENT_STARTED.register(this::applyNewDefaults);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> checkMemory(client)));

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientChunkEvents.CHUNK_LOAD.register(LodManager.INSTANCE::onChunkLoad);
		WorldRenderEvents.END_EXTRACTION.register(context -> LodManager.INSTANCE.setView(context.viewMatrix()));
		WorldRenderEvents.START_MAIN.register(context -> LodManager.INSTANCE.render(context.worldState().cameraRenderState.pos));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(OptimizedMC.MOD_ID, "chunk_map"), ChunkMapHud::render);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(OptimizedMC.MOD_ID, "main"));
		this.chunkMapKey = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.optimizedmc.chunk_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
	}

	private void tick(Minecraft client) {
		LodManager.INSTANCE.tick(client);
		while (this.chunkMapKey.consumeClick()) {
			OMConfig config = OMConfig.get();
			config.showChunkMap = !config.showChunkMap;
			config.save();
		}
	}

	/** One-time defaults for settings that also live in vanilla's options, so players can still change them back. */
	private void applyNewDefaults(Minecraft client) {
		OMConfig config = OMConfig.get();
		if (config.appliedDefaultsVersion >= DEFAULTS_VERSION) {
			return;
		}
		if (config.disableClouds) {
			client.options.cloudStatus().set(CloudStatus.OFF);
			client.options.save();
			OptimizedMC.LOGGER.info("Clouds turned off (turn them back on in Video Settings if you want them)");
		}
		config.appliedDefaultsVersion = DEFAULTS_VERSION;
		config.save();
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
