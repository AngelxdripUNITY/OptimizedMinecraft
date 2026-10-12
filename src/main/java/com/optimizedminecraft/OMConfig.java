package com.optimizedminecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings stored in config/optimizedminecraft.json.
 *
 * Loaded lazily on first access because the render distance slider is built
 * while Minecraft constructs its options, which can happen before mod
 * entrypoints run.
 */
public final class OMConfig {
	/** The client/server options packet stores view distance in a signed byte. */
	public static final int HARD_MAX_RENDER_DISTANCE = 127;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static OMConfig instance;

	/** Highest value the render distance slider goes to (vanilla: 32). */
	public int maxRenderDistance = 96;
	/** Let singleplayer / dedicated servers send chunks beyond vanilla's 32 limit. */
	public boolean uncapServerViewDistance = true;
	/** On the first launch with the mod, set the FPS limit to unlimited and turn VSync off. */
	public boolean unlockFramerateOnFirstLaunch = true;
	/** Show a toast when the Java heap looks too small for the chosen render distance. */
	public boolean memoryWarnings = true;

	/** Internal: set once the first-launch tweaks have been applied. */
	public boolean firstLaunchDone = false;

	public static synchronized OMConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public int clampedMaxRenderDistance() {
		return Math.max(32, Math.min(maxRenderDistance, HARD_MAX_RENDER_DISTANCE));
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("optimizedminecraft.json");
	}

	private static OMConfig load() {
		Path path = path();
		OMConfig config = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				config = GSON.fromJson(reader, OMConfig.class);
			} catch (Exception e) {
				OptimizedMC.LOGGER.error("Could not read {}, using defaults", path, e);
			}
		}
		if (config == null) {
			config = new OMConfig();
		}
		config.save();
		return config;
	}

	public synchronized void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			OptimizedMC.LOGGER.error("Could not write {}", path, e);
		}
	}
}
