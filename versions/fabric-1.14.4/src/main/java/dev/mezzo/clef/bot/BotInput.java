package dev.mezzo.clef.bot;

import net.minecraft.client.player.Input;

/**
 * Replaces the player's {@link Input} while the bot is driving movement. Each tick the game calls
 * {@link #tick(boolean, boolean)}; we write the directional flags and movement impulses from the live
 * {@link InputController} state, so vanilla movement code reads our intent instead of the keyboard.
 *
 * <p>On this release {@code Input} is a mutable class of public fields. Later releases replaced it
 * with a {@code ClientInput} holding an immutable {@code Input} record, which the newer targets use.
 */
public final class BotInput extends Input {

    private final InputController controller;

    public BotInput(InputController controller) {
        this.controller = controller;
    }

    @Override
    public void tick(boolean slowDown, boolean isSneaking) {
        boolean f = controller.forward, b = controller.backward;
        boolean l = controller.left, r = controller.right;

        this.up = f;
        this.down = b;
        this.left = l;
        this.right = r;
        this.jumping = controller.jump;
        this.sneakKeyDown = controller.sneak;   // renamed to shiftKeyDown in 1.15

        float[] v = MovementMath.vector(f, b, l, r);
        this.leftImpulse = v[0];
        this.forwardImpulse = v[1];
    }
}
