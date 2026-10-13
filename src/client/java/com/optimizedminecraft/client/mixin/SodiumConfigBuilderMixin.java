package com.optimizedminecraft.client.mixin;

import com.optimizedminecraft.OMConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Sodium's own Video Settings hard-codes the render distance slider to 2-32
 * ({@code setRange(2, 32, 1)}), which would undo our higher limit. Only applies when Sodium is installed.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder", remap = false)
public abstract class SodiumConfigBuilderMixin {
	@ModifyArg(
			method = "buildGeneralPage",
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;setRange(III)Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;",
					ordinal = 0, remap = false),
			index = 1, require = 0, remap = false)
	private int optimizedmc$raiseRenderDistanceCap(int max) {
		return Math.max(max, OMConfig.get().clampedMaxRenderDistance());
	}
}
