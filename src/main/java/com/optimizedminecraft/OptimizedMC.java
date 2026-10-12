package com.optimizedminecraft;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OptimizedMC implements ModInitializer {
	public static final String MOD_ID = "optimizedmc";
	public static final Logger LOGGER = LoggerFactory.getLogger("OptimizedMinecraft");

	@Override
	public void onInitialize() {
		OMConfig config = OMConfig.get();
		LOGGER.info("OptimizedMinecraft loaded: max render distance {}, server view distance uncapped: {}",
				config.clampedMaxRenderDistance(), config.uncapServerViewDistance);
	}
}
