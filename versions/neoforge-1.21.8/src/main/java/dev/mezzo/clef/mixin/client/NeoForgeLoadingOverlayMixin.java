package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.headless.HeadlessController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Keeps vanilla's loading overlay when booting GPU-free.
 *
 * <p>NeoForge replaces the loading overlay with its own, and {@code NeoForgeLoadingOverlay} casts
 * {@code RenderSystem.getDevice()} straight to the concrete {@code GlDevice} in its constructor. With
 * the stub device installed that is a {@link ClassCastException} before the client finishes starting,
 * which is why GPU-free booting needed more than the device itself on this loader.
 *
 * <p>Vanilla's {@code LoadingOverlay} takes exactly the same arguments and makes no such assumption,
 * so we hand that back instead. Only when GPU-free is actually active — with a real device present,
 * NeoForge's own overlay is left alone.
 */
@Mixin(ClientHooks.class)
public class NeoForgeLoadingOverlayMixin {

    @Inject(method = "createLoadingOverlay", at = @At("HEAD"), cancellable = true, remap = false)
    private static void clef$vanillaOverlayWhenNoGl(Minecraft mc, ReloadInstance reload,
                                                    Consumer<Optional<Throwable>> onFinish,
                                                    boolean fadeIn,
                                                    CallbackInfoReturnable<Overlay> cir) {
        if (HeadlessController.get().isNoGl()) {
            cir.setReturnValue(new LoadingOverlay(mc, reload, onFinish, fadeIn));
        }
    }
}
