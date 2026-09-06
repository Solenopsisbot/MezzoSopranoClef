#!/usr/bin/env python3
"""
Add a NeoForge target for a Minecraft release that already has a working Fabric one.

Two steps, both mechanical:

1. Split the existing Fabric module. Most of it is plain Minecraft glue that both loaders compile,
   so it moves to `versions/mc-<mc>/`, including the bulk of ClefClient which becomes the
   loader-neutral `ClefBotCore`. What stays behind is the Fabric event wiring and the GPU-free
   machinery — NeoForge patches Blaze3D with its own extension interfaces, so a stub device written
   against vanilla's does not satisfy them and cannot be shared.

2. Scaffold `versions/neoforge-<mc>/` from the 1.21.8 donor, retargeted.

The compile loop then shows what NeoForge itself changed between releases.

Usage: new_neoforge_version.py <mc> <neoForgeVersion> <javaMajor>
   e.g. new_neoforge_version.py 1.21.1 21.1.249 21
"""
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DONOR = "1.21.8"

# Stays in the Fabric module: loader wiring, plus the GPU-free stack (per-loader, not per-release).
FABRIC_ONLY = {
    "ClefClient.java",
    "version/VersionCapabilities.java",
    "mixin/client/RenderSystemNoGlMixin.java",
}
FABRIC_ONLY_DIRS = {"headless/nogl"}


def git(*args):
    subprocess.run(["git", *args], cwd=ROOT, check=True)


def split_fabric_module(mc):
    """Move the loader-neutral part of versions/fabric-<mc> into versions/mc-<mc>."""
    fab = ROOT / "versions" / f"fabric-{mc}" / "src/main/java/dev/mezzo/clef"
    shared = ROOT / "versions" / f"mc-{mc}" / "src/main/java/dev/mezzo/clef"
    if shared.exists():
        print(f"versions/mc-{mc} already exists — skipping split")
        return
    shared.mkdir(parents=True)
    for src in sorted(fab.rglob("*.java")):
        rel = src.relative_to(fab).as_posix()
        if rel in FABRIC_ONLY or any(rel.startswith(d + "/") for d in FABRIC_ONLY_DIRS):
            continue
        dst = shared / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        git("mv", str(src), str(dst))
    # ClefClient is moved separately: it becomes the shared core.
    core_src = fab / "ClefClient.java"
    core_dst = shared / "ClefBotCore.java"
    git("mv", str(core_src), str(core_dst))
    uses_record_accessor = to_core(core_dst)
    write_fabric_client(fab / "ClefClient.java", uses_record_accessor)
    add_srcdir(ROOT / "versions" / f"fabric-{mc}" / "build.gradle", mc)
    for d in sorted(fab.rglob("*"), reverse=True):
        if d.is_dir() and not any(d.iterdir()):
            d.rmdir()


def to_core(path):
    """Rewrite the moved ClefClient into the loader-neutral ClefBotCore. Returns the sender style."""
    s = path.read_text()
    uses_record_accessor = "sender.name()" in s
    s = re.sub(r"import net\.fabricmc\.[^\n]*\n", "", s)
    s = s.replace("public final class ClefClient implements ClientModInitializer {",
                  "public final class ClefBotCore {")
    s = s.replace(" * Client entrypoint — assembles the bot:",
                  " * Loader-neutral bot core — assembles the bot:")
    s = s.replace("""    @Override
    public void onInitializeClient() {""",
                  """    /** Builds the services and starts the control plane. Called by the loader's entrypoint. */
    public void start() {""")
    s = s.replace("""        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            if (control != null) control.stop();
            if (tokenRefresher != null) tokenRefresher.shutdownNow();
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        registerEventSources();

        Thread auth""", "        Thread auth")
    start = s.index("    private void registerEventSources() {")
    end = s.index("    /** Per-tick driver:")
    s = s[:start] + HOOKS + s[end:]
    s = s.replace("    private void onClientTick(Minecraft mc) {",
                  "    public void onClientTick(Minecraft mc) {")
    path.write_text(s)
    return uses_record_accessor


HOOKS = '''    /**
     * A received chat line. Each loader adapts its own chat event onto this; the shape matches
     * {@code ChatSink.Listener}, which is what the pre-1.19.3 targets feed from a packet mixin, so
     * there is one wire contract across every loader and release.
     *
     * @param kind    {@code "chat"} for player messages, {@code "game"} for system messages
     * @param overlay true when the server asked for the action-bar slot (game messages only)
     */
    public void onChatReceived(String text, String sender, String kind, boolean overlay) {
        if (control == null) return;
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        if (sender != null) d.addProperty("sender", sender);
        d.addProperty("kind", kind);
        if ("game".equals(kind)) d.addProperty("overlay", overlay);
        control.emitEvent("chat", d);
    }

    /** The client finished joining a world. */
    public void onConnected() {
        if (control != null) control.emitEvent("connected", new JsonObject());
    }

    /** The client left the world; forget the per-connection diff state. */
    public void onDisconnected() {
        if (control != null) control.emitEvent("disconnected", new JsonObject());
        lastHealth = Float.NaN;
        lastFood = -1;
        lastPlayers = null;
        lastEntities = null;
        lastScreen = "none";
    }

    /** The game is shutting down. */
    public void onStopping() {
        if (control != null) control.stop();
        if (tokenRefresher != null) tokenRefresher.shutdownNow();
    }

'''

FABRIC_CLIENT = '''package dev.mezzo.clef;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Fabric's client entrypoint: adapts Fabric API events onto {@link ClefBotCore}.
 *
 * <p>Everything the bot actually does lives in the core, which names no loader types and is shared
 * with this release's NeoForge target. This class is only the wiring.
 */
public final class ClefClient implements ClientModInitializer {

    private final ClefBotCore core = new ClefBotCore();

    @Override
    public void onInitializeClient() {
        core.start();

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> core.onStopping());
        ClientTickEvents.END_CLIENT_TICK.register(core::onClientTick);

        // System/game messages (server broadcasts, /say, join messages, ...).
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> core.onChatReceived(message.getString(), null, "game", overlay));
        // Player chat (this is how other players' — and our own echoed — messages arrive).
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) ->
                core.onChatReceived(message.getString(), sender != null ? sender.%s : null, "chat", false));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> core.onConnected());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> core.onDisconnected());
    }
}
'''


def write_fabric_client(path, uses_record_accessor):
    # GameProfile switched to record-style accessors in 1.21.9.
    path.write_text(FABRIC_CLIENT % ("name()" if uses_record_accessor else "getName()"))


def add_srcdir(gradle_file, mc):
    s = gradle_file.read_text()
    old = "java.srcDirs = ['../../common/src/main/java', '../../fabric-common/src/main/java', 'src/main/java']"
    new = ("java.srcDirs = ['../../common/src/main/java', '../../fabric-common/src/main/java',\n"
           f"                        '../mc-{mc}/src/main/java', 'src/main/java']")
    assert old in s, f"unexpected srcDirs in {gradle_file}"
    gradle_file.write_text(s.replace(old, new, 1))


def scaffold_neoforge(mc, nf_version, java_major):
    src = ROOT / "versions" / f"neoforge-{DONOR}"
    dst = ROOT / "versions" / f"neoforge-{mc}"
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src / "src", dst / "src")
    g = (src / "build.gradle").read_text()
    g = g.replace(f"Minecraft {DONOR}", f"Minecraft {mc}")
    g = g.replace(f"../mc-{DONOR}/", f"../mc-{mc}/")
    g = g.replace(f"mcVersion = '{DONOR}'", f"mcVersion = '{mc}'")
    g = g.replace("neoForgeVersion = '21.8.54'", f"neoForgeVersion = '{nf_version}'")
    g = g.replace("javaMajor = 21", f"javaMajor = {java_major}")
    g = g.replace("second loader in the matrix",
                  "NeoForge target for this release" if mc != DONOR else "second loader in the matrix")
    (dst / "build.gradle").write_text(g)

    # This release's capabilities, with GPU-free booting off (see the module comment).
    vc = dst / "src/main/java/dev/mezzo/clef/version/VersionCapabilities.java"
    vc.write_text(vc.read_text().replace(f'MINECRAFT = "{DONOR}"', f'MINECRAFT = "{mc}"'))

    # Take the mixin list from THIS release's Fabric module rather than the donor's — the set
    # differs by release — minus the no-gl mixin, whose device stub is Fabric-only.
    import json
    fab_mixins = json.loads((ROOT / "versions" / f"fabric-{mc}"
                             / "src/main/resources/mezzoclef.mixins.json").read_text())
    fab_mixins["client"] = [m for m in fab_mixins.get("client", [])
                            if m != "client.RenderSystemNoGlMixin"]
    fab_mixins["compatibilityLevel"] = f"JAVA_{java_major}"
    (dst / "src/main/resources/mezzoclef.mixins.json").write_text(
        json.dumps(fab_mixins, indent=2) + "\n")

    settings = ROOT / "settings.gradle"
    text = settings.read_text()
    line = f"include 'versions:neoforge-{mc}'"
    if line not in text:
        settings.write_text(text.rstrip() + "\n" + line + "\n")


def main():
    if len(sys.argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    mc, nf, java_major = sys.argv[1], sys.argv[2], int(sys.argv[3])
    split_fabric_module(mc)
    scaffold_neoforge(mc, nf, java_major)
    print(f"created versions/neoforge-{mc} (neoforge {nf}, java {java_major}); "
          f"shared glue in versions/mc-{mc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
