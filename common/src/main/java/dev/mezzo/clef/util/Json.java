package dev.mezzo.clef.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * JSON parsing that compiles against every Gson on the matrix.
 *
 * <p>Gson comes from Minecraft, not from us, and the version moves with the release: 1.17.1 and
 * older ship Gson 2.8.0, which has no static {@code JsonParser.parseString}. That method only
 * arrives in Gson 2.8.6. The instance method used here is deprecated in modern Gson but has never
 * been removed, so it is the one call that works from 1.14 through 26.x — which keeps
 * {@code common/} genuinely shared instead of forking per release over a library detail.
 */
public final class Json {

    private Json() {
    }

    /** Parses {@code text} into a Gson tree. Throws {@link com.google.gson.JsonSyntaxException}. */
    @SuppressWarnings("deprecation")
    public static JsonElement parse(String text) {
        return new JsonParser().parse(text);
    }
}
