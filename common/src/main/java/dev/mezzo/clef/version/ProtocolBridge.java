package dev.mezzo.clef.version;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.MezzoClef;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reflective bridge to ViaFabricPlus — the client-side ViaVersion that lets this single, newest
 * Minecraft client join servers running <b>any</b> release back to 1.7.2. This is how the bot
 * supports every server from 1.12.2 through the current release with one jar: the client always
 * speaks the newest protocol natively, and ViaFabricPlus translates on the wire.
 *
 * <p>We deliberately have no compile-time dependency on ViaFabricPlus (same pattern as
 * {@code BaritoneNavigator}): it is GPL-3.0 while we are MIT, and its build for a brand-new
 * Minecraft release can lag ours. If the mod isn't installed, everything here reports
 * "unavailable" and the bot simply behaves as a plain native-version client.
 *
 * <p>API surface used (stable across ViaFabricPlus 4.x):
 * <pre>
 *   com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator
 *       .getTargetVersion() / .setTargetVersion(ProtocolVersion, boolean revertOnDisconnect)
 *       .NATIVE_VERSION / .AUTO_DETECT_PROTOCOL
 *   com.viaversion.viaversion.api.protocol.version.ProtocolVersion
 *       .getClosest(String) / .getProtocols() / .getName() / .getVersion() / .isKnown()
 *       / .getIncludedVersions()
 * </pre>
 *
 * <p>Version selection semantics (see {@code connection.serverVersion} in the config and the
 * {@code version} argument of the {@code connect} command):
 * <ul>
 *   <li>{@code "auto"} (default) — ViaFabricPlus pings the server first and picks its version.</li>
 *   <li>{@code "native"} — no translation; connect as the client's own version.</li>
 *   <li>a release name such as {@code "1.12.2"} or {@code "1.20.4"} — force that protocol.</li>
 * </ul>
 */
public final class ProtocolBridge {

    public static final String MOD_ID = "viafabricplus";
    public static final String AUTO = "auto";
    public static final String NATIVE = "native";

    private static final ProtocolBridge INSTANCE = new ProtocolBridge();

    private boolean resolved;
    private boolean available;
    private String failure;

    private Method getTargetVersion;      // ProtocolTranslator#getTargetVersion()
    private Method setTargetVersion;      // ProtocolTranslator#setTargetVersion(ProtocolVersion, boolean)
    private Object nativeVersion;         // ProtocolTranslator.NATIVE_VERSION
    private Object autoDetect;            // ProtocolTranslator.AUTO_DETECT_PROTOCOL
    private Method getClosest;            // ProtocolVersion.getClosest(String)
    private Method getProtocols;          // ProtocolVersion.getProtocols()
    private Method pvGetName;             // ProtocolVersion#getName()
    private Method pvGetVersion;          // ProtocolVersion#getVersion()
    private Method pvIsKnown;             // ProtocolVersion#isKnown()
    private Method pvIncluded;            // ProtocolVersion#getIncludedVersions() -> Set<String>

    public static ProtocolBridge get() {
        return INSTANCE;
    }

    /** True when ViaFabricPlus is loaded and its API resolved. */
    public synchronized boolean isAvailable() {
        resolve();
        return available;
    }

    /** Why the bridge is unavailable (null when it is available). */
    public synchronized String unavailableReason() {
        resolve();
        return available ? null : failure;
    }

    private void resolve() {
        if (resolved) return;
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            failure = "ViaFabricPlus is not installed — only servers on the client's own version ("
                    + nativeMinecraftVersion() + ") can be joined";
            MezzoClef.LOG.info("ViaFabricPlus not present; multi-version server support disabled.");
            return;
        }
        try {
            Class<?> translator = Class.forName("com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator");
            Class<?> protocolVersion = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            getTargetVersion = translator.getMethod("getTargetVersion");
            setTargetVersion = translator.getMethod("setTargetVersion", protocolVersion, boolean.class);
            nativeVersion = staticField(translator, "NATIVE_VERSION");
            autoDetect = staticField(translator, "AUTO_DETECT_PROTOCOL");
            getClosest = protocolVersion.getMethod("getClosest", String.class);
            getProtocols = protocolVersion.getMethod("getProtocols");
            pvGetName = protocolVersion.getMethod("getName");
            pvGetVersion = protocolVersion.getMethod("getVersion");
            pvIsKnown = protocolVersion.getMethod("isKnown");
            pvIncluded = protocolVersion.getMethod("getIncludedVersions");
            available = true;
            MezzoClef.LOG.info("ViaFabricPlus detected — servers from 1.7.2 through {} can be joined.",
                    pvGetName.invoke(nativeVersion));
        } catch (Throwable t) {
            failure = "ViaFabricPlus present but its API didn't match expectations: " + t;
            MezzoClef.LOG.warn(failure);
            available = false;
        }
    }

    private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getField(name);
        return f.get(null);
    }

    /** The Minecraft version this client was built for (what "native" means). */
    public static String nativeMinecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /**
     * Applies a version selection before a connection attempt. Returns a human-readable name of
     * what was selected. Throws {@link IllegalArgumentException} for an unknown version string and
     * {@link IllegalStateException} when translation was requested but ViaFabricPlus is missing.
     * Must be called on the client thread.
     */
    public synchronized String select(String requested) {
        String want = requested == null || requested.isBlank() ? AUTO : requested.trim().toLowerCase(Locale.ROOT);
        if (!isAvailable()) {
            // Without the translator the only honest choices are "native", "auto" (which can only
            // ever resolve to native), and naming this client's own release — none of those need
            // any translation. Anything else would silently connect as the wrong version.
            if (want.equals(NATIVE) || want.equals(AUTO)
                    || want.equals(nativeMinecraftVersion().toLowerCase(Locale.ROOT))) {
                return NATIVE;
            }
            throw new IllegalStateException(failure);
        }
        try {
            Object target;
            if (want.equals(AUTO)) {
                target = autoDetect;
            } else if (want.equals(NATIVE)) {
                target = nativeVersion;
            } else {
                target = getClosest.invoke(null, requested.trim());
                if (target == null || !(Boolean) pvIsKnown.invoke(target)) {
                    throw new IllegalArgumentException("unknown Minecraft version '" + requested
                            + "' — use auto, native, or a release such as 1.12.2 (see the protocol command)");
                }
            }
            // `false` = keep this selection after disconnect, so a reconnect to the same server
            // doesn't silently fall back. Every connect goes through here anyway.
            setTargetVersion.invoke(null, target, false);
            return describe(target);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("ViaFabricPlus version selection failed: " + t, t);
        }
    }

    /** The currently selected target ("auto", "native", or a release name). */
    public synchronized String currentTarget() {
        if (!isAvailable()) return NATIVE;
        try {
            return describe(getTargetVersion.invoke(null));
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private String describe(Object protocolVersion) throws ReflectiveOperationException {
        if (protocolVersion == null) return "unknown";
        if (protocolVersion.equals(autoDetect)) return AUTO;
        if (protocolVersion.equals(nativeVersion)) return NATIVE + " (" + pvGetName.invoke(nativeVersion) + ")";
        return String.valueOf(pvGetName.invoke(protocolVersion));
    }

    /**
     * Every release ViaFabricPlus can translate to, oldest first, as {name, protocol} pairs. Releases
     * that share a protocol are one ViaVersion entry (e.g. "1.16.4/5" covers 1.16.4 and 1.16.5), so
     * each entry is expanded to one row per included release name — the list is meant to be matched
     * against plain Mojang release ids.
     */
    public synchronized JsonArray supportedVersions() {
        JsonArray out = new JsonArray();
        if (!isAvailable()) {
            JsonObject only = new JsonObject();
            only.addProperty("name", nativeMinecraftVersion());
            out.add(only);
            return out;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object> versions = new ArrayList<>((List<Object>) getProtocols.invoke(null));
            for (Object v : versions) {
                if (!(Boolean) pvIsKnown.invoke(v)) continue;
                int protocol = (Integer) pvGetVersion.invoke(v);
                String label = String.valueOf(pvGetName.invoke(v));
                @SuppressWarnings("unchecked")
                java.util.Collection<String> included = (java.util.Collection<String>) pvIncluded.invoke(v);
                java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
                if (included != null) names.addAll(included);
                if (names.isEmpty()) names.add(label);
                for (String name : names) {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", name);
                    o.addProperty("protocol", protocol);
                    if (!name.equals(label)) o.addProperty("group", label);
                    out.add(o);
                }
            }
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Could not enumerate ViaFabricPlus versions: {}", t.toString());
        }
        return out;
    }

    /** Status block merged into the {@code status} and {@code protocol} command results. */
    public JsonObject describeJson() {
        JsonObject o = new JsonObject();
        o.addProperty("available", isAvailable());
        o.addProperty("native", nativeMinecraftVersion());
        o.addProperty("target", currentTarget());
        if (!isAvailable()) o.addProperty("reason", unavailableReason());
        return o;
    }

    private ProtocolBridge() {}
}
