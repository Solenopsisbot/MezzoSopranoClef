package dev.mezzo.clef.headless.nogl;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A headless, GPU-free implementation of Minecraft's {@link GpuDevice}.
 *
 * <p>It is installed in place of {@code net.minecraft.client.gl.GlBackend} (see
 * {@code RenderSystemNoGlMixin}) so the client can boot and run <b>without ever creating or
 * touching an OpenGL context</b>. Every operation is a no-op: textures and buffers are pure
 * metadata holders (no VRAM, no driver), uploads are dropped, render passes draw nothing,
 * fences complete instantly, and pipelines report valid.
 *
 * <p>This is what lets the bot run on any host with no GPU and (combined with the GLFW null
 * platform) no display server at all. Screenshots are produced by the CPU voxel raycaster
 * ({@code SoftwareRaycaster}), which never goes through this device.
 *
 * <p><b>Why so many no-ops are safe:</b> in headless mode the per-frame world/GUI draw is
 * skipped entirely, so the only code that reaches this device is boot-time setup (framebuffer
 * allocation, shader/pipeline preload, atlas/texture "uploads") and the brief loading-screen
 * render. None of it reads pixels back. Buffers only allocate backing memory if something maps
 * them for writing (e.g. the loading screen), and that memory is heap-cheap and transient.
 */
public final class NoGlDevice implements GpuDevice {

    private static final int MAX_TEXTURE_SIZE = 16384;
    // A safe std140 UBO alignment; DynamicUniformStorage rounds entry sizes up to this.
    private static final int UNIFORM_OFFSET_ALIGNMENT = 256;

    private final CommandEncoder encoder = new NoGlCommandEncoder();

    @Override
    public CommandEncoder createCommandEncoder() {
        return encoder;
    }

    @Override
    public GpuTexture createTexture(Supplier<String> labelGetter, int usage, TextureFormat format,
                                    int width, int height, int depthOrLayers, int mipLevels) {
        return new NoGlTexture(usage, labelGetter != null ? labelGetter.get() : "clef-texture",
                format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(String label, int usage, TextureFormat format,
                                    int width, int height, int depthOrLayers, int mipLevels) {
        return new NoGlTexture(usage, label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        return new NoGlTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        return new NoGlTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> labelGetter, int usage, int size) {
        return new NoGlBuffer(usage, size);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> labelGetter, int usage, ByteBuffer data) {
        NoGlBuffer buffer = new NoGlBuffer(usage, data != null ? data.remaining() : 0);
        if (data != null && data.remaining() > 0) {
            // Keep the initial contents in case something maps it for read later (rare on boot).
            buffer.backing().put(data.duplicate()).rewind();
        }
        return buffer;
    }

    @Override
    public String getImplementationInformation() {
        return "MezzoSopranoClef NoGL device (headless, GPU-free)";
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public String getVendor() {
        return "MezzoSopranoClef";
    }

    @Override
    public String getBackendName() {
        return "NoGL";
    }

    @Override
    public String getVersion() {
        return "headless";
    }

    @Override
    public String getRenderer() {
        return "CPU (no rendering)";
    }

    @Override
    public int getMaxTextureSize() {
        return MAX_TEXTURE_SIZE;
    }

    @Override
    public int getUniformOffsetAlignment() {
        return UNIFORM_OFFSET_ALIGNMENT;
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline) {
        return NoGlCompiledPipeline.INSTANCE;
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline,
                                                     BiFunction<Identifier, ShaderType, String> shaderSourceGetter) {
        return NoGlCompiledPipeline.INSTANCE;
    }

    @Override
    public void clearPipelineCache() {
    }

    @Override
    public List<String> getEnabledExtensions() {
        return List.of();
    }

    @Override
    public void close() {
    }

    // ===== stub resources =============================================================

    /** A pipeline that always reports valid so callers never bail on "compile failed". */
    static final class NoGlCompiledPipeline implements CompiledRenderPipeline {
        static final NoGlCompiledPipeline INSTANCE = new NoGlCompiledPipeline();

        @Override
        public boolean isValid() {
            return true;
        }
    }

    /** Texture metadata only — no GL handle, no pixel storage. */
    static final class NoGlTexture extends GpuTexture {
        private boolean closed;

        NoGlTexture(int usage, String label, TextureFormat format,
                    int width, int height, int depthOrLayers, int mipLevels) {
            super(usage, label, format, width, height, depthOrLayers, mipLevels);
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    static final class NoGlTextureView extends GpuTextureView {
        private boolean closed;

        NoGlTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
            super(texture, baseMipLevel, mipLevels);
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    /**
     * A buffer that owns plain heap-adjacent memory only if it actually gets mapped. Nothing is
     * ever sent to a GPU; the backing exists purely so writers (e.g. the loading-screen uniform
     * uploads) have somewhere valid to put bytes.
     */
    static final class NoGlBuffer extends GpuBuffer {
        private ByteBuffer backing;
        private boolean closed;

        NoGlBuffer(int usage, int size) {
            super(usage, size);
        }

        ByteBuffer backing() {
            if (backing == null) {
                backing = ByteBuffer.allocateDirect(Math.max(1, this.size)).order(ByteOrder.nativeOrder());
            }
            return backing;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
            backing = null;
        }
    }

    /** A mapped view over a buffer's (throwaway) backing memory. */
    static final class NoGlMappedView implements GpuBuffer.MappedView {
        private final ByteBuffer data;

        NoGlMappedView(ByteBuffer data) {
            this.data = data;
        }

        @Override
        public ByteBuffer data() {
            return data;
        }

        @Override
        public void close() {
        }
    }

    /** A fence that is always already signalled. */
    static final class NoGlFence implements GpuFence {
        @Override
        public boolean awaitCompletion(long timeoutNanos) {
            return true;
        }

        @Override
        public void close() {
        }
    }

    /** A render pass that records nothing. */
    static final class NoGlRenderPass implements RenderPass {
        @Override
        public void pushDebugGroup(Supplier<String> label) {
        }

        @Override
        public void popDebugGroup() {
        }

        @Override
        public void setPipeline(RenderPipeline pipeline) {
        }

        @Override
        public void bindSampler(String name, GpuTextureView texture) {
        }

        @Override
        public void setUniform(String name, GpuBuffer buffer) {
        }

        @Override
        public void setUniform(String name, GpuBufferSlice buffer) {
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
        }

        @Override
        public void disableScissor() {
        }

        @Override
        public void setVertexBuffer(int slot, GpuBuffer buffer) {
        }

        @Override
        public void setIndexBuffer(GpuBuffer buffer, VertexFormat.IndexType indexType) {
        }

        @Override
        public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        }

        @Override
        public <T> void drawMultipleIndexed(Collection<RenderPass.RenderObject<T>> objects, GpuBuffer indexBuffer,
                                            VertexFormat.IndexType indexType, Collection<String> dynamicUniforms, T userData) {
        }

        @Override
        public void draw(int first, int count) {
        }

        @Override
        public void close() {
        }
    }

    /** A command encoder that drops every command. */
    static final class NoGlCommandEncoder implements CommandEncoder {
        @Override
        public RenderPass createRenderPass(Supplier<String> label, GpuTextureView colorAttachment, OptionalInt clearColor) {
            return new NoGlRenderPass();
        }

        @Override
        public RenderPass createRenderPass(Supplier<String> label, GpuTextureView colorAttachment, OptionalInt clearColor,
                                           GpuTextureView depthAttachment, OptionalDouble clearDepth) {
            return new NoGlRenderPass();
        }

        @Override
        public void clearColorTexture(GpuTexture texture, int color) {
        }

        @Override
        public void clearColorAndDepthTextures(GpuTexture colorTexture, int color, GpuTexture depthTexture, double depth) {
        }

        @Override
        public void clearColorAndDepthTextures(GpuTexture colorTexture, int color, GpuTexture depthTexture, double depth,
                                               int scissorX, int scissorY, int scissorWidth, int scissorHeight) {
        }

        @Override
        public void clearDepthTexture(GpuTexture texture, double depth) {
        }

        @Override
        public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        }

        @Override
        public GpuBuffer.MappedView mapBuffer(GpuBuffer buffer, boolean read, boolean write) {
            ByteBuffer bb = ((NoGlBuffer) buffer).backing().duplicate().order(ByteOrder.nativeOrder());
            bb.clear();
            return new NoGlMappedView(bb);
        }

        @Override
        public GpuBuffer.MappedView mapBuffer(GpuBufferSlice slice, boolean read, boolean write) {
            ByteBuffer full = ((NoGlBuffer) slice.buffer()).backing().duplicate().order(ByteOrder.nativeOrder());
            full.position(slice.offset()).limit(slice.offset() + slice.length());
            return new NoGlMappedView(full.slice().order(ByteOrder.nativeOrder()));
        }

        @Override
        public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        }

        @Override
        public void writeToTexture(GpuTexture texture, NativeImage image) {
        }

        @Override
        public void writeToTexture(GpuTexture texture, NativeImage image, int mipLevel, int depthOrLayer,
                                   int x, int y, int width, int height, int sourceX, int sourceY) {
        }

        @Override
        public void writeToTexture(GpuTexture texture, IntBuffer pixels, NativeImage.Format format,
                                   int mipLevel, int depthOrLayer, int x, int y, int width, int height) {
        }

        @Override
        public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, int offset, Runnable onComplete, int mipLevel) {
            if (onComplete != null) {
                onComplete.run();
            }
        }

        @Override
        public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, int offset, Runnable onComplete, int mipLevel,
                                        int x, int y, int width, int height) {
            if (onComplete != null) {
                onComplete.run();
            }
        }

        @Override
        public void copyTextureToTexture(GpuTexture source, GpuTexture target, int mipLevel,
                                         int destX, int destY, int sourceX, int sourceY, int width, int height) {
        }

        @Override
        public void presentTexture(GpuTextureView texture) {
        }

        @Override
        public GpuFence createFence() {
            return new NoGlFence();
        }
    }
}
