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
    void unknownCommandThrowsCodedException() {
        CommandDispatcher d = new CommandDispatcher();
        ApiException ex = assertThrows(ApiException.class, () -> d.dispatch("nope", null));
        assertEquals(ErrorCode.UNKNOWN_COMMAND, ex.code);
        assertTrue(ex.getMessage().contains("nope"));
    }
}
