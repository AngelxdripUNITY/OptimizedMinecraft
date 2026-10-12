package com.optimizedminecraft.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.optimizedminecraft.client.FogTweaks;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	/** Runs right after vanilla has filled in all fog distances, before they are uploaded. */
	@Inject(method = "setupFog", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F",
			opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void optimizedmc$adjustFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darken, ClientLevel level,
			CallbackInfoReturnable<Vector4f> cir, @Local FogData fogData) {
		FogTweaks.adjust(fogData);
	}
}
