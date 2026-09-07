package dev.mezzo.clef.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.headless.nogl.NoGlDeviceBackend;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Boots Minecraft with <b>no OpenGL/Vulkan context</b>.
 *
 * <p>The client constructor creates its window, then asks the chosen {@link GpuBackend}
 * ({@code GlBackend} or, since 26.2, {@code VulkanBackend}) to {@code createDevice(...)}. That
 * single call is where the real driver gets touched: for OpenGL it does
 * {@code glfwMakeContextCurrent} + {@code GL.createCapabilities()} inside the {@code GlDevice}
 * constructor. We wrap that call and, in no-GL mode, hand back a {@link GpuDevice} built on
 * {@link NoGlDeviceBackend} instead — so the GL backend is never constructed and not a single
 * GL call is ever made. Everything downstream ({@code RenderSystem.initRenderer}, the sampler
 * cache, render targets, the loading screen) runs unmodified against the stub device.
 *
 * <p>Gated on {@link HeadlessController#isNoGl()}; with it off the original call runs untouched.
 * Verified against 26.2: {@code Minecraft.<init>} → {@code GpuBackend.createDevice(long window,
 * ShaderSource, GpuDebugOptions, Runnable criticalShaderLoader)}.
 */
@Mixin(Minecraft.class)
public class MinecraftClientNoGlMixin {

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuBackend;createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;"
                            + "Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;"))
    private GpuDevice clef$installNoGlDevice(GpuBackend backend, long window, ShaderSource shaders,
                                             GpuDebugOptions debugOptions, Runnable criticalShaderLoader,
                                             Operation<GpuDevice> original) {
        if (!HeadlessController.get().isNoGl()) {
            return original.call(backend, window, shaders, debugOptions, criticalShaderLoader);
        }
        MezzoClef.LOG.info("[no-gl] Installed GPU-free render device in place of the {} backend — "
                + "no GL/Vulkan context will ever be created.", backend.getName());
        return NoGlDeviceBackend.createDevice(criticalShaderLoader);
    }
}
