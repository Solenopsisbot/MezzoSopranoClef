package dev.mezzo.clef.headless.nogl;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A headless, GPU-free Blaze3D <b>backend</b>: the thing a real OpenGL/Vulkan device would talk to.
 *
 * <p>Since Minecraft 26.2 the public {@link GpuDevice} is a concrete wrapper that validates
 * arguments and profiles, and delegates all real work to a {@link GpuDeviceBackend}. Vanilla
 * plugs in {@code GlDevice} or {@code VulkanDevice} there; we plug in this class instead (see
 * {@code MinecraftClientNoGlMixin}) so the client can boot and run <b>without ever creating or
 * touching a GL/Vulkan context</b>. Every operation is a no-op: textures and buffers are pure
 * metadata holders (no VRAM, no driver), uploads are dropped, render passes draw nothing, the
 * window "surface" never presents, fences complete instantly, and pipelines report valid.
 *
 * <p>This is what lets the bot run on any host with no GPU and (combined with the GLFW null
 * platform) no display server at all. Screenshots come from the CPU voxel raycaster
 * ({@code SoftwareRaycaster}), which never goes through this device.
 *
 * <p><b>Why so many no-ops are safe:</b> in headless mode the per-frame world/GUI draw is
 * skipped entirely, so the only code that reaches this backend is boot-time setup (render
 * target allocation, shader/pipeline preload, atlas/texture "uploads", sampler cache) and the
 * brief loading-screen render. None of it reads pixels back. Buffers only allocate backing
 * memory if something actually maps or writes them, and that memory is heap-cheap and transient.
 */
public final class NoGlDeviceBackend implements GpuDeviceBackend {

    private static final int MAX_TEXTURE_SIZE = 16384;
    // A safe std140 UBO alignment; DynamicUniformStorage rounds entry sizes up to this.
    private static final int UNIFORM_OFFSET_ALIGNMENT = 256;

    /**
     * Describes us to the engine. Everything that could steer the renderer toward fancier code
     * paths (multi-draw, indirect draws, persistent mapping) is reported as unsupported so the
     * game takes its simplest path, which is also the one that reaches the fewest stubs.
     */
    private static final DeviceInfo INFO = new DeviceInfo(
            "MezzoSopranoClef NoGL device",         // name
            "MezzoSopranoClef",                     // vendorName
            "headless (no GPU, no GL/Vulkan)",      // driverInfo
            false,                                  // isZZeroToOne (GL convention)
            "NoGL",                                 // backendName
            1.0f,                                   // timestampPeriod
            new DeviceLimits(
                    1,                              // maxAnisotropy
                    UNIFORM_OFFSET_ALIGNMENT,       // minUniformOffsetAlignment
                    MAX_TEXTURE_SIZE,               // maxTextureSize
                    Integer.MAX_VALUE,              // maxMemoryAllocationSize
                    0,                              // maxMultiDrawDirectInterleavedDrawCount
                    8),                             // maxColorAttachments
            new DeviceFeatures(false, false, false, false, false, false, false),
            Set.of(),
            new HintsAndWorkarounds(false, false),
            DeviceType.CPU);

    /** Wraps this backend in the vanilla {@link GpuDevice} shell, exactly like {@code GlBackend} does. */
    public static GpuDevice createDevice(Runnable criticalShaderLoader) {
        return new GpuDevice(new NoGlDeviceBackend(), criticalShaderLoader);
    }

    // ===== GpuDeviceBackend ===========================================================

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle) {
        return new NoGlSurface();
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return new NoGlCommandEncoder();
    }

    @Override
    public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag,
                                    int maxAnisotropy, OptionalDouble maxLod) {
        return new NoGlSampler(u, v, min, mag, Math.max(1, maxAnisotropy), maxLod == null ? OptionalDouble.empty() : maxLod);
    }

    @Override
    public GpuTexture createTexture(Supplier<String> labelGetter, int usage, GpuFormat format,
                                    int width, int height, int depthOrLayers, int mipLevels) {
        return new NoGlTexture(usage, labelGetter != null ? labelGetter.get() : "clef-texture",
                format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(String label, int usage, GpuFormat format,
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
    public GpuBuffer createBuffer(Supplier<String> labelGetter, int usage, long size) {
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
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, ShaderSource shaderSource) {
        return NoGlCompiledPipeline.INSTANCE;
    }

    @Override
    public void clearPipelineCache() {
    }

    @Override
    public void close() {
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new NoGlQueryPool(size);
    }

    @Override
    public long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return INFO;
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

    /** Texture metadata only — no GPU handle, no pixel storage. */
    static final class NoGlTexture extends GpuTexture {
        private boolean closed;

        NoGlTexture(int usage, String label, GpuFormat format,
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

    /** Sampler metadata only; the {@code SamplerCache} keeps a handful of these around. */
    static final class NoGlSampler extends GpuSampler {
        private final AddressMode u, v;
        private final FilterMode min, mag;
        private final int anisotropy;
        private final OptionalDouble maxLod;

        NoGlSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
            this.u = u;
            this.v = v;
            this.min = min;
            this.mag = mag;
            this.anisotropy = anisotropy;
            this.maxLod = maxLod;
        }

        @Override public AddressMode getAddressModeU() { return u; }
        @Override public AddressMode getAddressModeV() { return v; }
        @Override public FilterMode getMinFilter() { return min; }
        @Override public FilterMode getMagFilter() { return mag; }
        @Override public int getMaxAnisotropy() { return anisotropy; }
        @Override public OptionalDouble getMaxLod() { return maxLod; }
        @Override public void close() {}
    }

    /**
     * A buffer that owns plain off-heap memory only if it actually gets mapped or written.
     * Nothing is ever sent to a GPU; the backing exists purely so writers (e.g. the
     * loading-screen uniform uploads) have somewhere valid to put bytes.
     */
    static final class NoGlBuffer extends GpuBuffer {
        private ByteBuffer backing;
        private boolean closed;

        NoGlBuffer(int usage, long size) {
            super(usage, size);
        }

        ByteBuffer backing() {
            if (backing == null) {
                backing = ByteBuffer.allocateDirect((int) Math.max(1, Math.min(size(), Integer.MAX_VALUE)))
                        .order(ByteOrder.nativeOrder());
            }
            return backing;
        }

        /** A view over [offset, offset+length) of the backing memory, positioned at 0. */
        ByteBuffer window(long offset, long length) {
            ByteBuffer full = backing().duplicate().order(ByteOrder.nativeOrder());
            int off = (int) offset;
            int end = (int) Math.min(offset + length, full.capacity());
            full.clear().position(off).limit(Math.max(off, end));
            return full.slice().order(ByteOrder.nativeOrder());
        }

        @Override
        public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            return new GpuBufferSlice.MappedView(slice(offset, length), window(offset, length), () -> {});
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

    /** A timestamp query pool with no answers (profilers show nothing, which is honest). */
    static final class NoGlQueryPool implements GpuQueryPool {
        private final int size;

        NoGlQueryPool(int size) {
            this.size = size;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public OptionalLong getValue(int index) {
            return OptionalLong.empty();
        }

        @Override
        public OptionalLong[] getValues(int first, int count) {
            OptionalLong[] out = new OptionalLong[Math.max(0, count)];
            java.util.Arrays.fill(out, OptionalLong.empty());
            return out;
        }

        @Override
        public void close() {
        }
    }

    /**
     * The window "swapchain". Vanilla acquires a texture from it every frame, blits the main
     * render target into it and presents. We accept every call and show nothing — there is no
     * GL context (and under the null platform not even a window) to present to.
     */
    static final class NoGlSurface implements GpuSurfaceBackend {
        @Override
        public void configure(GpuSurface.Configuration configuration) {
        }

        @Override
        public boolean isSuboptimal() {
            return false;
        }

        @Override
        public void acquireNextTexture() {
        }

        @Override
        public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView texture) {
        }

        @Override
        public void present() {
        }

        @Override
        public void close() {
        }

        @Override
        public Collection<GpuSurface.PresentMode> supportedPresentModes() {
            return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
        }
    }

    /**
     * Per-frame scratch allocator. The GUI/loading-screen renderer streams its vertex and
     * uniform data through here, so this has to hand out <i>real</i> writable memory — just
     * memory that never goes anywhere. Each allocation gets its own throwaway buffer.
     */
    static final class NoGlTransientMemory implements TransientMemory {
        private static NoGlBuffer fresh(long size, int usage) {
            return new NoGlBuffer(usage, Math.max(1, size));
        }

        @Override
        public ByteBuffer allocateCpu(long size, long alignment, long a, long b) {
            return ByteBuffer.allocateDirect((int) Math.max(1, size)).order(ByteOrder.nativeOrder());
        }

        @Override
        public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long a, long b) {
            return fresh(size, usage).map(0, Math.max(1, size), false, true);
        }

        @Override
        public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long a, long b) {
            return fresh(size, usage).slice(0, Math.max(1, size));
        }

        @Override
        public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long a, long b) {
            return fresh(size, usage).map(0, Math.max(1, size), false, true);
        }

        @Override
        public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long a, long b) {
            return uploadGpu(data, alignment, usage, a, b);
        }

        @Override
        public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long a, long b) {
            long total = 0;
            for (ByteBuffer bb : data) total += bb.remaining();
            NoGlBuffer buffer = fresh(total, usage);
            ByteBuffer dst = buffer.backing().duplicate().order(ByteOrder.nativeOrder());
            dst.clear();
            for (ByteBuffer bb : data) dst.put(bb.duplicate());
            return buffer.slice(0, Math.max(1, total));
        }

        @Override
        public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
            return multiUploadGpu(data, alignment, usage);
        }

        @Override
        public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
            List<GpuBufferSlice> out = new ArrayList<>(data.size());
            for (ByteBuffer bb : data) out.add(uploadGpu(List.of(bb), alignment, usage, 0, 0));
            return out;
        }
    }

    /** A render pass that records nothing. */
    static final class NoGlRenderPass implements RenderPassBackend {
        @Override public void pushDebugGroup(Supplier<String> label) {}
        @Override public void popDebugGroup() {}
        @Override public void setPipeline(RenderPipeline pipeline) {}
        @Override public void bindTexture(String name, GpuTextureView texture, GpuSampler sampler) {}
        @Override public void setUniform(String name, GpuBuffer buffer) {}
        @Override public void setUniform(String name, GpuBufferSlice slice) {}
        @Override public void enableScissor(int x, int y, int width, int height) {}
        @Override public void disableScissor() {}
        @Override public void setVertexBuffer(int slot, GpuBufferSlice buffer) {}
        @Override public void setIndexBuffer(GpuBuffer buffer, IndexType indexType) {}
        @Override public void drawIndexed(int a, int b, int c, int d, int e) {}
        @Override public void multiDrawIndexed(IntBuffer a, int b, int c, int d) {}
        @Override public void multiDrawIndexed(PointerBuffer a, IntBuffer b, IntBuffer c, int d) {}
        @Override public void drawIndexedIndirect(GpuBufferSlice params, int count) {}
        @Override public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer indexBuffer,
                                                      IndexType indexType, Collection<String> dynamicUniforms, T userData) {}
        @Override public void draw(int a, int b, int c, int d) {}
        @Override public void multiDraw(IntBuffer a, int b, int c, int d) {}
        @Override public void multiDraw(IntBuffer a, IntBuffer b, int c) {}
        @Override public void drawIndirect(GpuBufferSlice params, int count) {}
        @Override public void writeTimestamp(GpuQueryPool pool, int index) {}
    }

    /** A command encoder that drops every command (but keeps buffer writes so map-reads stay sane). */
    static final class NoGlCommandEncoder implements CommandEncoderBackend {
        private final NoGlTransientMemory transientMemory = new NoGlTransientMemory();

        @Override
        public void submit() {
        }

        @Override
        public TransientMemory transientMemory() {
            return transientMemory;
        }

        @Override
        public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
            return new NoGlRenderPass();
        }

        @Override
        public void submitRenderPass() {
        }

        @Override public void clearColorTexture(GpuTexture texture, Vector4fc color) {}
        @Override public void clearColorAndDepthTextures(GpuTexture color, Vector4fc c, GpuTexture depth, double d) {}
        @Override public void clearColorAndDepthTextures(GpuTexture color, Vector4fc c, GpuTexture depth, double d,
                                                         int x, int y, int w, int h) {}
        @Override public void clearDepthTexture(GpuTexture texture, double depth) {}

        @Override
        public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
            if (slice.buffer() instanceof NoGlBuffer b && data != null) {
                ByteBuffer dst = b.window(slice.offset(), slice.length());
                ByteBuffer src = data.duplicate();
                if (src.remaining() > dst.remaining()) src.limit(src.position() + dst.remaining());
                dst.put(src);
            }
        }

        @Override public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {}
        @Override public void writeToTexture(GpuTexture texture, ByteBuffer pixels, int mipLevel, int depthOrLayer,
                                             int x, int y, int width, int height) {}
        @Override public void copyBufferToTexture(GpuBufferSlice source, int a, int b, int c, int d, GpuTexture target,
                                                  int e, int f, int g, int h, int i, int j) {}

        @Override
        public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable onComplete, int mipLevel) {
            if (onComplete != null) onComplete.run();
        }

        @Override
        public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable onComplete, int mipLevel,
                                        int x, int y, int width, int height) {
            if (onComplete != null) onComplete.run();
        }

        @Override public void copyTextureToTexture(GpuTexture source, GpuTexture target, int mipLevel,
                                                   int dstX, int dstY, int srcX, int srcY, int width, int height) {}

        @Override
        public GpuFence createFence() {
            return new NoGlFence();
        }

        @Override public void writeTimestamp(GpuQueryPool pool, int index) {}
    }
}
