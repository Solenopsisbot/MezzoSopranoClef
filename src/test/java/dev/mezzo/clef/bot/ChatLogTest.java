package dev.mezzo.clef.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The catch-up buffer behind {@code chatHistory}: bounded, oldest-first, never blows up. */
class ChatLogTest {

    @Test
    void keepsOnlyTheMostRecentLines() {
        ChatLog log = new ChatLog(3);
        for (int i = 0; i < 10; i++) log.add("chat", "someone", "line " + i);

        assertEquals(3, log.size());
        var lines = log.recent(10);
        assertEquals(3, lines.size(), "asking for more than we hold returns what we hold");
        assertEquals("line 7", lines.get(0).getAsJsonObject().get("text").getAsString(), "oldest first");
        assertEquals("line 9", lines.get(2).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void limitTakesTheNewestNotTheOldest() {
        ChatLog log = new ChatLog(100);
        for (int i = 0; i < 5; i++) log.add("system", null, "line " + i);

        var lines = log.recent(2);
        assertEquals(2, lines.size());
        assertEquals("line 3", lines.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("line 4", lines.get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void omitsSenderWhenThereIsNone() {
        ChatLog log = new ChatLog(4);
        log.add("system", null, "server restarting");
        var line = log.recent(1).get(0).getAsJsonObject();
        assertFalse(line.has("sender"), "a system message has no sender field at all");
        assertEquals("system", line.get("kind").getAsString());
    }

    @Test
    void survivesAZeroCapacityRequest() {
        ChatLog log = new ChatLog(0);   // clamped to 1 rather than dividing by zero later
        log.add("chat", "a", "hi");
        assertEquals(1, log.size());
        assertEquals(1, log.recent(1).size());
    }

    @Test
    void emptyLogReturnsNothingRatherThanThrowing() {
        assertEquals(0, new ChatLog(10).recent(5).size());
    }
}
