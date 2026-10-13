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
	/**
	 * The client options packet stores view distance in a signed byte (max 127), and the
	 * server's player ticket tracker stores chunk levels up to view distance + 2 in a byte.
	 */
	public static final int HARD_MAX_RENDER_DISTANCE = 125;

	public static final int VANILLA_MAX_VIEW_DISTANCE = 32;

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

	/** Turn clouds off (applied once; re-enable them in Video Settings if you want them back). */
	public boolean disableClouds = true;
	/**
	 * "off": no distance, weather or biome fog at all; "reduced": thin edge fog, weather fog 3x further;
	 * "vanilla": unchanged. Underwater, lava and blindness fog are always left alone.
	 */
	public String fog = FOG_OFF;
	/** How much further out weather and biome fog starts in "reduced" mode. */
	public float fogDistanceMultiplier = 3.0F;

	/** Draw low-detail terrain (LODs) beyond the real render distance. */
	public boolean lodEnabled = true;
	/** How far LODs reach, in chunks. */
	public int lodDistance = 512;
	/**
	 * How many on-screen pixels one LOD cell may cover before the next finer level is used.
	 * Lower is sharper and costs more; this is the "screen-space error" budget.
	 */
	public float lodPixelError = 6.0F;
	/** LOD worker threads; 0 picks cores - 2. */
	public int lodThreads = 0;
	/** Upper bound for GPU memory used by LOD meshes, in MB. */
	public int lodGpuBudgetMb = 768;
	/** Save predicted LOD terrain in the world folder so it loads instantly next time (singleplayer). */
	public boolean lodDiskCache = true;
	/** Tint LODs by detail level, like Nanite's cluster view. */
	public boolean lodDebugColors = false;
	/** Show the loaded-chunk map overlay (toggle in game with the O key). */
	public boolean showChunkMap = false;

	/** Internal: set once the first-launch tweaks have been applied. */
	public boolean firstLaunchDone = false;
	/** Internal: which one-time defaults (like clouds off) have been applied. */
	public int appliedDefaultsVersion = 0;

	public static synchronized OMConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public int clampedMaxRenderDistance() {
		return Math.max(VANILLA_MAX_VIEW_DISTANCE, Math.min(maxRenderDistance, HARD_MAX_RENDER_DISTANCE));
	}

	public static final int MAX_LOD_DISTANCE = 4096;
	public static final String FOG_OFF = "off";
	public static final String FOG_REDUCED = "reduced";
	public static final String FOG_VANILLA = "vanilla";

	public String fogMode() {
		return FOG_REDUCED.equals(this.fog) || FOG_VANILLA.equals(this.fog) ? this.fog : FOG_OFF;
	}

	public void cycleFog() {
		this.fog = switch (this.fogMode()) {
			case FOG_OFF -> FOG_REDUCED;
			case FOG_REDUCED -> FOG_VANILLA;
			default -> FOG_OFF;
		};
	}

	public int clampedLodDistance() {
		return Math.max(0, Math.min(lodDistance, MAX_LOD_DISTANCE));
	}

	/** Upper bound for server-side view distance. */
	public int maxServerViewDistance() {
		// -Doptimizedmc.vanillaServer=true switches the server side back to vanilla limits for debugging.
		boolean uncap = uncapServerViewDistance && !Boolean.getBoolean("optimizedmc.vanillaServer");
		return uncap ? clampedMaxRenderDistance() : VANILLA_MAX_VIEW_DISTANCE;
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
