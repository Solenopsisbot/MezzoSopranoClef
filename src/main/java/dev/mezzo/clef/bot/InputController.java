package dev.mezzo.clef.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Holds the bot's desired movement state and swaps the player's {@link net.minecraft.client.input.Input}
 * for a {@link BotInput} while active (restoring a normal {@link KeyboardInput} when released).
 * {@link #tick(MinecraftClient)} must be called every client tick on the client thread.
 */
public final class InputController {

    public volatile boolean forward, backward, left, right, jump, sneak, sprint;
    private volatile boolean active;
    private volatile long clearAtMs;

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

    public void tick(MinecraftClient mc) {
        if (clearAtMs > 0 && System.currentTimeMillis() >= clearAtMs) {
            clear();
        }
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        if (active) {
            if (!(p.input instanceof BotInput)) p.input = new BotInput(this);
        } else if (p.input instanceof BotInput) {
            p.input = new KeyboardInput(mc.options); // hand control back to vanilla
        }
    }
}
