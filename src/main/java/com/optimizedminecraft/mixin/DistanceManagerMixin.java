package com.optimizedminecraft.mixin;

import com.optimizedminecraft.OMConfig;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
	/**
	 * The player ticket tracker only tracks distances up to the number it's built with
	 * ({@code new PlayerTicketTracker(32)}), so chunks further out would never get a
	 * loading ticket even with a larger view distance.
	 */
	@ModifyArg(
			method = "<init>",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$PlayerTicketTracker;<init>(Lnet/minecraft/server/level/DistanceManager;I)V"),
			index = 1
	)
	private int optimizedmc$growPlayerTicketTracker(int vanillaMax) {
		return Math.max(vanillaMax, OMConfig.get().maxServerViewDistance());
	}
}
