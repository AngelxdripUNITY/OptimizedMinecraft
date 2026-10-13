package com.optimizedminecraft.client;

import com.optimizedminecraft.OMConfig;
import com.optimizedminecraft.client.lod.LodManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;

public final class FogTweaks {
	/** "No fog" distance: far beyond anything that is drawn. */
	private static final float NO_FOG = 1.0e7F;

	private FogTweaks() {
	}

	/** Also what Sodium's terrain renderer uses: it reads these values right after us. */
	public static void adjust(FogData fog) {
		OMConfig config = OMConfig.get();
		float lodEnd = LodManager.INSTANCE.fogEndBlocks();
		if (lodEnd > 0) {
			// LODs continue the terrain, so the edge fog moves from the chunk edge out to the LOD edge.
			fog.renderDistanceStart = lodEnd * 0.6F;
			fog.renderDistanceEnd = lodEnd;
		}
		if (gameplayFog()) {
			return;
		}
		switch (config.fogMode()) {
			case OMConfig.FOG_OFF -> {
				fog.renderDistanceStart = NO_FOG;
				fog.renderDistanceEnd = NO_FOG;
				fog.environmentalStart = NO_FOG;
				fog.environmentalEnd = NO_FOG;
				fog.skyEnd = NO_FOG;
				fog.cloudEnd = NO_FOG;
			}
			case OMConfig.FOG_REDUCED -> {
				if (lodEnd <= 0) {
					float band = fog.renderDistanceEnd - fog.renderDistanceStart;
					fog.renderDistanceStart = fog.renderDistanceEnd - band * 0.35F;
				}
				float multiplier = Math.max(1.0F, config.fogDistanceMultiplier);
				fog.environmentalStart = Math.max(0.0F, fog.environmentalStart) * multiplier;
				fog.environmentalEnd *= multiplier;
			}
			default -> {
			}
		}
	}

	/** Underwater, lava and blindness fog are gameplay, not scenery; leave them alone. */
	private static boolean gameplayFog() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && (player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA)
				|| player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.DARKNESS));
	}
}
