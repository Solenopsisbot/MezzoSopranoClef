package dev.mezzo.clef.api;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Argument parsing: required readers must reject missing/mistyped args with a {@code BAD_ARGS} code. */
class CommandContextTest {

    private static CommandContext ctx(JsonObject args) {
        return new CommandContext(null, null, args); // require* readers only touch args
    }

    @Test
    void requireIntReturnsValueWhenPresent() {
        JsonObject a = new JsonObject();
        a.addProperty("x", 42);
        assertEquals(42, ctx(a).requireInt("x"));
    }

    @Test
    void requireIntThrowsBadArgsWhenMissing() {
        ApiException ex = assertThrows(ApiException.class, () -> ctx(new JsonObject()).requireInt("x"));
        assertEquals(ErrorCode.BAD_ARGS, ex.code);
        assertTrue(ex.getMessage().contains("x"));
    }

    @Test
    void requireIntThrowsBadArgsWhenNotNumeric() {
        JsonObject a = new JsonObject();
        a.addProperty("x", "not-a-number");
        ApiException ex = assertThrows(ApiException.class, () -> ctx(a).requireInt("x"));
        assertEquals(ErrorCode.BAD_ARGS, ex.code);
    }

    @Test
    void requireDoubleAndFloatParse() {
        JsonObject a = new JsonObject();
        a.addProperty("d", 1.5);
        a.addProperty("f", 2.5f);
        assertEquals(1.5, ctx(a).requireDouble("d"));
        assertEquals(2.5f, ctx(a).requireFloat("f"));
    }

    @Test
    void requireStrThrowsBadArgsWhenMissing() {
        ApiException ex = assertThrows(ApiException.class, () -> ctx(new JsonObject()).requireStr("name"));
        assertEquals(ErrorCode.BAD_ARGS, ex.code);
    }

    @Test
    void optionalReadersFallBackToDefaults() {
        CommandContext c = ctx(new JsonObject());
        assertEquals(7, c.i("missing", 7));
        assertEquals("def", c.str("missing", "def"));
        assertFalse(c.has("missing"));
    }
}
