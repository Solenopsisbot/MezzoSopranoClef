package dev.mezzo.clef.bot;

import net.minecraft.client.MinecraftClient;

/**
 * Drives "hold right-click" item use over time — the mechanism behind eating, drinking, charging
 * a bow/crossbow, blocking with a shield, fishing, spyglass, etc. A single right-click can't do
 * these; they need the use key <i>held</i>. We hold {@code GameOptions.useKey} pressed for a set
 * number of ticks (Minecraft's own input/tick logic then starts and continues the use), then
 * release it (which fires a charged bow / stops blocking). {@link #tick(MinecraftClient)} runs
 * every client tick.
 */
public final class UseController {

    private volatile int heldTicks;
    private volatile boolean releaseNow;

    /** Hold the use key for {@code ticks} ticks (eating/drinking ≈ 40; bow charge ≈ 20+). */
    public synchronized void hold(int ticks) {
        heldTicks = Math.max(1, ticks);
        releaseNow = false;
    }

    /** Release immediately (e.g. fire a charged bow). */
    public synchronized void release() {
        heldTicks = 0;
        releaseNow = true;
    }

    public boolean isHolding() {
        return heldTicks > 0;
    }

    public void tick(MinecraftClient mc) {
        if (mc.player == null || mc.options == null) return;
        if (heldTicks > 0) {
            mc.options.useKey.setPressed(true);
            if (--heldTicks == 0) {
                mc.options.useKey.setPressed(false); // release => bow fires / use stops
            }
        } else if (releaseNow) {
            releaseNow = false;
            mc.options.useKey.setPressed(false);
            if (mc.player.isUsingItem()) mc.player.stopUsingItem();
        }
    }
}
