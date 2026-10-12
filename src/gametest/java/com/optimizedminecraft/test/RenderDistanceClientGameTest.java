package com.optimizedminecraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Launches the real client, creates a superflat singleplayer world with a
 * render distance above vanilla's 32 cap and checks that the server really
 * sends chunks that far out.
 */
public class RenderDistanceClientGameTest implements FabricClientGameTest {
	private static final int RENDER_DISTANCE = 40;
	/** A chunk this far from the player can only exist if the cap was lifted. */
	private static final int PROBE_DISTANCE = 36;
	private static final Logger LOGGER = LoggerFactory.getLogger("OptimizedMinecraftTest");

	@Override
	public void runTest(ClientGameTestContext context) {
		// Fail fast if any mixin no longer matches the game code.
		context.runOnClient(client -> MixinEnvironment.getCurrentEnvironment().audit());

		int sliderMax = context.computeOnClient(client -> {
			client.options.renderDistance().set(RENDER_DISTANCE);
			return client.options.renderDistance().get();
		});
		if (sliderMax != RENDER_DISTANCE) {
			throw new AssertionError("Render distance option was clamped to " + sliderMax);
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();

			int serverViewDistance = singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getViewDistance());
			if (serverViewDistance != RENDER_DISTANCE) {
				throw new AssertionError("Server view distance is " + serverViewDistance + ", expected " + RENDER_DISTANCE);
			}

			context.waitFor(RenderDistanceClientGameTest::hasFarChunk, SharedConstants.TICKS_PER_MINUTE * 5);
			context.takeScreenshot("render-distance-" + RENDER_DISTANCE);
		}
		// CI greps for this line so a test that silently never ran can't pass.
		LOGGER.info("OPTIMIZEDMC GAMETEST PASSED");
	}

	private static boolean hasFarChunk(Minecraft client) {
		if (client.level == null || client.player == null) {
			return false;
		}
		ChunkPos pos = client.player.chunkPosition();
		return client.level.getChunkSource().hasChunk(pos.x + PROBE_DISTANCE, pos.z);
	}
}
