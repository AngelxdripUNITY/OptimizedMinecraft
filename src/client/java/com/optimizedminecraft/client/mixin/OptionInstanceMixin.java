package com.optimizedminecraft.client.mixin;

import java.util.function.Consumer;

import com.optimizedminecraft.client.gui.NumberFieldWidget;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OptionInstance.class)
public abstract class OptionInstanceMixin {
	/** Render distance gets a type-in number box instead of a slider. */
	@Inject(method = "createButton(Lnet/minecraft/client/Options;IIILjava/util/function/Consumer;)Lnet/minecraft/client/gui/components/AbstractWidget;",
			at = @At("HEAD"), cancellable = true)
	private void optimizedmc$numberField(Options options, int x, int y, int width, Consumer<?> onValueChanged, CallbackInfoReturnable<AbstractWidget> cir) {
		if ((Object) this == options.renderDistance()) {
			cir.setReturnValue(NumberFieldWidget.renderDistance(options, x, y, width));
		}
	}
}
