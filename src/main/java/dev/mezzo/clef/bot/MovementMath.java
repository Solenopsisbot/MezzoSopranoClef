package dev.mezzo.clef.bot;

/**
 * Pure derivation of the raw movement vector from directional intent — extracted from
 * {@link BotInput} so it can be unit-tested without a Minecraft client.
 */
public final class MovementMath {

    /**
     * @return {@code {sideways, forward}} each in [-1, 1]. Forward is +Z-ish, sideways is the
     *         strafe axis; opposing keys cancel.
     */
    public static float[] vector(boolean forward, boolean backward, boolean left, boolean right) {
        float fwd = (forward ? 1f : 0f) - (backward ? 1f : 0f);
        float side = (left ? 1f : 0f) - (right ? 1f : 0f);
        return new float[] { side, fwd };
    }

    private MovementMath() {}
}
