package dev.mezzo.clef.api.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guard in front of the {@code baritone} passthrough.
 *
 * <p>Getting this wrong is not symmetric. A false positive costs a few milliseconds waiting on a
 * warm-up that has almost certainly already finished. A false negative lets a command naming a
 * block reach {@code BlockOptionalMeta} on the client thread, which parks it permanently and takes
 * the whole bot down until the JVM is killed. So the classifier is deliberately biased towards
 * "this might name a block", and these cases pin that bias in place.</p>
 */
class BaritoneArgumentTest {

    @Test
    void coordinateArgumentsAreSafe() {
        assertFalse(CoreCommands.namesABlock("goto 106 89 120"));
        assertFalse(CoreCommands.namesABlock("goto -40 64 -1200"));
        assertFalse(CoreCommands.namesABlock("goto ~ ~ ~10"));
        assertFalse(CoreCommands.namesABlock("goto +5 64 +5"));
        assertFalse(CoreCommands.namesABlock("thisway 100"));
    }

    @Test
    void blockNamesAreNotSafe() {
        assertTrue(CoreCommands.namesABlock("goto jungle_log"));
        assertTrue(CoreCommands.namesABlock("goto minecraft:diamond_ore"));
        assertTrue(CoreCommands.namesABlock("mine diamond_ore"));
        assertTrue(CoreCommands.namesABlock("sel fill stone"));
        assertTrue(CoreCommands.namesABlock("find oak_log"));
    }

    @Test
    void aCountBeforeTheBlockStillCountsAsNamingABlock() {
        // `mine 3 diamond_ore` starts with a number but the block is still in there — treating this
        // as a coordinate command would be exactly the false negative that deadlocks the client.
        assertTrue(CoreCommands.namesABlock("mine 3 diamond_ore"));
    }

    @Test
    void bareCommandsWithNoArgumentAreSafe() {
        assertFalse(CoreCommands.namesABlock("cancel"));
        assertFalse(CoreCommands.namesABlock("pause"));
        assertFalse(CoreCommands.namesABlock("  version  "));
        assertFalse(CoreCommands.namesABlock(""));
    }
}
