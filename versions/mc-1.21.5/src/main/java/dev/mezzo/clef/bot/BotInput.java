package dev.mezzo.clef.bot;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Replaces the player's {@link ClientInput} while the bot is driving movement. Each tick the game calls
 * {@link #tick()}; we (re)build the {@link Input} record and the raw {@code movementVector}
 * from the live {@link InputController} state, so vanilla movement code reads our intent instead
 * of the keyboard.
 */
public final class BotInput extends ClientInput {

    private final InputController controller;

    public BotInput(InputController controller) {
        this.controller = controller;
    }

    @Override
    public void tick() {
        boolean f = controller.forward, b = controller.backward;
        boolean l = controller.left, r = controller.right;
        boolean j = controller.jump, sn = controller.sneak, sp = controller.sprint;

        this.keyPresses = new Input(f, b, l, r, j, sn, sp);
        float[] v = MovementMath.vector(f, b, l, r);
        this.moveVector = new Vec2(v[0], v[1]);
    }
}
