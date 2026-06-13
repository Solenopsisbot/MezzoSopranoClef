package dev.mezzo.clef.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandDispatcherTest {

    @Test
    void dispatchesRegisteredCommand() throws Exception {
        CommandDispatcher d = new CommandDispatcher();
        d.register("echo", "returns ok", ctx -> new JsonPrimitive("ok"));
        JsonElement result = d.dispatch("echo", null); // command ignores ctx
        assertEquals("ok", result.getAsString());
        assertTrue(d.names().contains("echo"));
        assertEquals("returns ok", d.help().get("echo"));
    }

    @Test
    void unknownCommandThrows() {
        CommandDispatcher d = new CommandDispatcher();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> d.dispatch("nope", null));
        assertTrue(ex.getMessage().contains("nope"));
    }
}
