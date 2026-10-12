package com.optimizedminecraft.mixin;

import com.optimizedminecraft.OMConfig;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	/** Vanilla clamps the server view distance to 2..32 here. */
	@ModifyArg(
			method = "setServerViewDistance",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I"),
			index = 2
	)
	private int optimizedmc$uncapViewDistance(int vanillaMax) {
		return Math.max(vanillaMax, OMConfig.get().maxServerViewDistance());
	}
}
