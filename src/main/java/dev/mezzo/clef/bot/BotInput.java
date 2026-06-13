package dev.mezzo.clef.bot;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/**
 * Replaces the player's {@link Input} while the bot is driving movement. Each tick the game calls
 * {@link #tick()}; we (re)build the {@link PlayerInput} record and the raw {@code movementVector}
 * from the live {@link InputController} state, so vanilla movement code reads our intent instead
 * of the keyboard.
 */
public final class BotInput extends Input {

    private final InputController controller;

    public BotInput(InputController controller) {
        this.controller = controller;
    }

    @Override
    public void tick() {
        boolean f = controller.forward, b = controller.backward;
        boolean l = controller.left, r = controller.right;
        boolean j = controller.jump, sn = controller.sneak, sp = controller.sprint;

        this.playerInput = new PlayerInput(f, b, l, r, j, sn, sp);
        float[] v = MovementMath.vector(f, b, l, r);
        this.movementVector = new Vec2f(v[0], v[1]);
    }
}
