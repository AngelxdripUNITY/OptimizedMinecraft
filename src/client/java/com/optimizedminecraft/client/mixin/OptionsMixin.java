package com.optimizedminecraft.client.mixin;

import com.optimizedminecraft.OMConfig;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(Options.class)
public abstract class OptionsMixin {
	/** Raises the upper end of the render distance slider (vanilla: 32, or 16 with little memory). */
	@ModifyArg(
			method = "<init>",
			slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance")),
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance$IntRange;<init>(IIZ)V", ordinal = 0),
			index = 1
	)
	private int optimizedmc$uncapRenderDistance(int vanillaMax) {
		return Math.max(vanillaMax, OMConfig.get().clampedMaxRenderDistance());
	}
}
