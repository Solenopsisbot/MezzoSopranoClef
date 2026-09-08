package dev.mezzo.clef.bot;

import dev.mezzo.clef.headless.HeadlessController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;

/**
 * Holds the bot's desired movement state and swaps the player's {@link net.minecraft.client.player.ClientInput}
 * for a {@link BotInput} while active (restoring a normal {@link KeyboardInput} when released).
 * {@link #tick(Minecraft)} must be called every client tick on the client thread.
 */
public final class InputController {

    public volatile boolean forward, backward, left, right, jump, sneak, sprint;
    private volatile boolean active;
    private volatile long clearAtMs;
    /** Set once, the first tick the options file is loaded — see {@link #pinPauseOnLostFocus}. */
    private boolean pausePinned;

    public synchronized void set(boolean f, boolean b, boolean l, boolean r,
                                 boolean j, boolean sn, boolean sp, long durationMs) {
        forward = f; backward = b; left = l; right = r; jump = j; sneak = sn; sprint = sp;
        active = f || b || l || r || j || sn || sp;
        clearAtMs = (active && durationMs > 0) ? System.currentTimeMillis() + durationMs : 0;
    }

    public synchronized void clear() {
        forward = backward = left = right = jump = sneak = sprint = false;
        active = false;
        clearAtMs = 0;
    }

    public boolean isActive() {
        return active;
    }

    public void tick(Minecraft mc) {
        pinPauseOnLostFocus(mc);
        if (clearAtMs > 0 && System.currentTimeMillis() >= clearAtMs) {
            clear();
        }
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (active) {
            if (!(p.input instanceof BotInput)) p.input = new BotInput(this);
        } else if (p.input instanceof BotInput) {
            p.input = new KeyboardInput(mc.options); // hand control back to vanilla
        }
    }

    /**
     * Turns off "pause the game when the window loses focus", which a headless client can never
     * satisfy and must never be subject to.
     *
     * <p>The check vanilla runs is {@code if (!window.isFocused() && options.pauseOnLostFocus)},
     * with half a second of grace. A bot has no window to focus — under {@code noWindow} there is
     * not even a display server — so the answer can only ever be "unfocused". Once it fires it
     * opens the pause screen, and it fires again on the very next frame, so nothing can outrun it:
     * {@code closeScreen} reports success and the screen is back before the reply is written.
     * Minecraft skips the whole input path while a screen is up, so the body stops moving, mining
     * and drawing its bow, and every command that needs input starts failing at once — with no
     * error that points anywhere near the actual cause.</p>
     *
     * <p>How close that is to firing today depends on the release, which is why this pins the
     * option rather than trusting either answer:</p>
     * <ul>
     *   <li><b>26.2</b> — the check moved into {@code Minecraft.pauseIfInactive()}, called from
     *       {@code renderFrame}, which headless still runs (only the {@code extract}/{@code render}
     *       calls inside it are skipped). It is held off by one accident: {@code Window.focused}
     *       is initialised {@code true} in the constructor and is only ever cleared by a GLFW
     *       focus callback, which a hidden or null-platform window does not receive. One real
     *       focus-out event on a merely-hidden window is all it takes.</li>
     *   <li><b>1.14.4 – 1.21.11</b> — the check lives in {@code GameRenderer.render}, which the
     *       headless mixin skips outright, so it cannot fire at all. Pinned anyway, so the
     *       behaviour is the same across the matrix and stays that way when the mixin moves.</li>
     * </ul>
     *
     * <p>Only while headless: a windowed dev run is a real client, and alt-tabbing it should
     * still pause the way the person driving it expects.</p>
     */
    private void pinPauseOnLostFocus(Minecraft mc) {
        // Options exist long before the player does, so this is checked ahead of the world guard.
        if (pausePinned || mc.options == null || !HeadlessController.get().isHeadless()) return;
        mc.options.pauseOnLostFocus = false;
        pausePinned = true;
    }
}
