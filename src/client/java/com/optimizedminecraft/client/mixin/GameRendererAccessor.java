package com.optimizedminecraft.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	/** The field of view the world is actually rendered with (FOV setting, sprint/zoom effects). */
	@Invoker("getFov")
	float optimizedmc$getFov(Camera camera, float partialTick, boolean useFovSetting);
}
