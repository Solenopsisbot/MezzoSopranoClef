package dev.mezzo.clef.headless.nogl;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;

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
 * Headless, GPU-free {@link GpuDevice} for the <b>1.21.5–1.21.8</b> Blaze3D generation.
 *
 * <p>This era differs from 1.21.9+ in several ways that matter here: samplers are bound straight
 * onto a render pass ({@code bindSampler}) rather than being first-class {@code GpuSampler}
 * objects, buffer sizes and copy offsets are {@code int} rather than {@code long}, there are no
 * timer queries, and shader sources arrive as a {@code BiFunction} instead of a {@code
 * ShaderSource}. Everything else matches the sibling implementations: no VRAM, no driver, uploads
 * dropped, render passes record nothing, fences complete instantly, pipelines report valid.
 */
public final class NoGlDevice implements GpuDevice {

    private static final int MAX_TEXTURE_SIZE = 16384;
    /** A safe std140 UBO alignment; the dynamic-uniform storage rounds entry sizes up to this. */
    private static final int UNIFORM_OFFSET_ALIGNMENT = 256;

    private final CommandEncoder encoder = new NoGlCommandEncoder();

    @Override public CommandEncoder createCommandEncoder() { return encoder; }

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

    @Override public GpuTextureView createTextureView(GpuTexture t) { return new NoGlTextureView(t, 0, t.getMipLevels()); }
    @Override public GpuTextureView createTextureView(GpuTexture t, int base, int mips) { return new NoGlTextureView(t, base, mips); }

    @Override public GpuBuffer createBuffer(Supplier<String> labelGetter, int usage, int size) { return new NoGlBuffer(usage, size); }

    @Override
    public GpuBuffer createBuffer(Supplier<String> labelGetter, int usage, ByteBuffer data) {
        NoGlBuffer buffer = new NoGlBuffer(usage, data != null ? data.remaining() : 0);
        if (data != null && data.remaining() > 0) buffer.backing().put(data.duplicate()).rewind();
        return buffer;
    }

    @Override public String getImplementationInformation() { return "MezzoSopranoClef NoGL device (headless, GPU-free)"; }
    @Override public List<String> getLastDebugMessages() { return List.of(); }
    @Override public boolean isDebuggingEnabled() { return false; }
    @Override public String getVendor() { return "MezzoSopranoClef"; }
    @Override public String getBackendName() { return "NoGL"; }
    @Override public String getVersion() { return "headless"; }
    @Override public String getRenderer() { return "CPU (no rendering)"; }
    @Override public int getMaxTextureSize() { return MAX_TEXTURE_SIZE; }
    @Override public int getUniformOffsetAlignment() { return UNIFORM_OFFSET_ALIGNMENT; }

    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline,
                                                     BiFunction<ResourceLocation, ShaderType, String> shaderSource) {
        return NoGlCompiledPipeline.INSTANCE;
    }

    @Override public void clearPipelineCache() {}
    @Override public List<String> getEnabledExtensions() { return List.of(); }
    @Override public void close() {}

    // ===== stub resources =============================================================

    static final class NoGlCompiledPipeline implements CompiledRenderPipeline {
        static final NoGlCompiledPipeline INSTANCE = new NoGlCompiledPipeline();
        @Override public boolean isValid() { return true; }
    }

    /** Texture metadata only — no GL handle, no pixel storage. */
    static final class NoGlTexture extends GpuTexture {
        private boolean closed;
        NoGlTexture(int usage, String label, TextureFormat format, int w, int h, int layers, int mips) {
            super(usage, label, format, w, h, layers, mips);
        }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }

    static final class NoGlTextureView extends GpuTextureView {
        private boolean closed;
        NoGlTextureView(GpuTexture t, int base, int mips) { super(t, base, mips); }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }

    /**
     * Owns plain off-heap memory only if something actually maps or writes it. Nothing ever reaches
     * a GPU; the backing exists so writers (loading-screen uniforms) have somewhere valid to put bytes.
     */
    static final class NoGlBuffer extends GpuBuffer {
        private ByteBuffer backing;
        private boolean closed;

        NoGlBuffer(int usage, int size) { super(usage, size); }

        ByteBuffer backing() {
            if (backing == null) {
                backing = ByteBuffer.allocateDirect(Math.max(1, size())).order(ByteOrder.nativeOrder());
            }
            return backing;
        }

        ByteBuffer window(int offset, int length) {
            ByteBuffer full = backing().duplicate().order(ByteOrder.nativeOrder());
            int end = Math.min(offset + length, full.capacity());
            full.clear().position(offset).limit(Math.max(offset, end));
            return full.slice().order(ByteOrder.nativeOrder());
        }

        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; backing = null; }
    }

    record NoGlMappedView(ByteBuffer data) implements GpuBuffer.MappedView {
        @Override public void close() {}
    }

    static final class NoGlFence implements GpuFence {
        @Override public boolean awaitCompletion(long timeoutNanos) { return true; }
        @Override public void close() {}
    }

    /** A render pass that records nothing. */
    static final class NoGlRenderPass implements RenderPass {
        @Override public void pushDebugGroup(Supplier<String> label) {}
        @Override public void popDebugGroup() {}
        @Override public void setPipeline(RenderPipeline pipeline) {}
        @Override public void bindSampler(String name, GpuTextureView view) {}
        @Override public void setUniform(String name, GpuBuffer buffer) {}
        @Override public void setUniform(String name, GpuBufferSlice slice) {}
        @Override public void enableScissor(int x, int y, int width, int height) {}
        @Override public void disableScissor() {}
        @Override public void setVertexBuffer(int slot, GpuBuffer buffer) {}
        @Override public void setIndexBuffer(GpuBuffer buffer, VertexFormat.IndexType type) {}
        @Override public void drawIndexed(int a, int b, int c, int d) {}
        @Override public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer indexBuffer,
                                                      VertexFormat.IndexType type, Collection<String> uniforms, T userData) {}
        @Override public void draw(int first, int count) {}
        @Override public void close() {}
    }

    /** A command encoder that drops every command (buffer writes are kept so map-reads stay sane). */
    static final class NoGlCommandEncoder implements CommandEncoder {
        @Override public RenderPass createRenderPass(Supplier<String> l, GpuTextureView c, OptionalInt clear) { return new NoGlRenderPass(); }
        @Override public RenderPass createRenderPass(Supplier<String> l, GpuTextureView c, OptionalInt clear,
                                                     GpuTextureView d, OptionalDouble clearDepth) { return new NoGlRenderPass(); }
        @Override public void clearColorTexture(GpuTexture t, int color) {}
        @Override public void clearColorAndDepthTextures(GpuTexture c, int color, GpuTexture d, double depth) {}
        @Override public void clearColorAndDepthTextures(GpuTexture c, int color, GpuTexture d, double depth,
                                                         int x, int y, int w, int h) {}
        @Override public void clearDepthTexture(GpuTexture t, double depth) {}

        @Override
        public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
            if (slice.buffer() instanceof NoGlBuffer b && data != null) {
                ByteBuffer dst = b.window(slice.offset(), slice.length());
                ByteBuffer src = data.duplicate();
                if (src.remaining() > dst.remaining()) src.limit(src.position() + dst.remaining());
                dst.put(src);
            }
        }

        @Override
        public GpuBuffer.MappedView mapBuffer(GpuBuffer buffer, boolean read, boolean write) {
            ByteBuffer bb = ((NoGlBuffer) buffer).backing().duplicate().order(ByteOrder.nativeOrder());
            bb.clear();
            return new NoGlMappedView(bb);
        }

        @Override
        public GpuBuffer.MappedView mapBuffer(GpuBufferSlice slice, boolean read, boolean write) {
            return new NoGlMappedView(((NoGlBuffer) slice.buffer()).window(slice.offset(), slice.length()));
        }

        @Override public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {}
        @Override public void writeToTexture(GpuTexture t, NativeImage image) {}
        @Override public void writeToTexture(GpuTexture t, NativeImage image, int a, int b, int c, int d, int e, int f, int g, int h) {}
        @Override public void writeToTexture(GpuTexture t, IntBuffer pixels, NativeImage.Format fmt,
                                             int a, int b, int c, int d, int e, int f) {}

        @Override
        public void copyTextureToBuffer(GpuTexture t, GpuBuffer buffer, int offset, Runnable onComplete, int mip) {
            if (onComplete != null) onComplete.run();
        }

        @Override
        public void copyTextureToBuffer(GpuTexture t, GpuBuffer buffer, int offset, Runnable onComplete, int mip,
                                        int x, int y, int w, int h) {
            if (onComplete != null) onComplete.run();
        }

        @Override public void copyTextureToTexture(GpuTexture s, GpuTexture t, int mip, int dx, int dy, int sx, int sy, int w, int h) {}
        @Override public void presentTexture(GpuTextureView view) {}
        @Override public GpuFence createFence() { return new NoGlFence(); }
    }
}
