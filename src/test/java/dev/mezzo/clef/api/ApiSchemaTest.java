package dev.mezzo.clef.api;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mezzo.clef.api.commands.ActionCommands;
import dev.mezzo.clef.api.commands.CoreCommands;
import dev.mezzo.clef.api.commands.UiCommands;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the machine-readable contract. The most important check is {@link #schemaMatchesRegisteredCommands()}:
 * it fails the build if {@link ApiSchema} and the actual command registrations ever drift, so the
 * published schema can never lie about what the bot accepts.
 */
class ApiSchemaTest {

    private static CommandDispatcher registerAll() {
        CommandDispatcher d = new CommandDispatcher();
        CoreCommands.registerAll(d);
        ActionCommands.registerAll(d);
        UiCommands.registerAll(d);
        return d;
    }

    @Test
    void schemaMatchesRegisteredCommands() {
        CommandDispatcher d = registerAll();
        TreeSet<String> registered = new TreeSet<>(d.names());
        TreeSet<String> declared = ApiSchema.commandNames();
        assertEquals(registered, declared,
                "ApiSchema drifted from the dispatcher. Registered but undocumented: "
                        + minus(registered, declared) + "; documented but not registered: "
                        + minus(declared, registered));
    }

    @Test
    void protocolVersionIsPositive() {
        assertTrue(ApiSchema.PROTOCOL_VERSION > 0);
    }

    @Test
    void everyCommandHasSummaryAndUniqueArgs() {
        CommandDispatcher d = registerAll();
        JsonObject schema = ApiSchema.toJson(d.help());
        assertEquals(ApiSchema.PROTOCOL_VERSION, schema.get("protocol").getAsInt());

        JsonArray commands = schema.getAsJsonArray("commands");
        assertFalse(commands.isEmpty());
        for (JsonElement el : commands) {
            JsonObject c = el.getAsJsonObject();
            String name = c.get("name").getAsString();
            assertFalse(c.get("summary").getAsString().isBlank(), name + " has a blank summary");
            Set<String> argNames = new HashSet<>();
            for (JsonElement ae : c.getAsJsonArray("args")) {
                JsonObject arg = ae.getAsJsonObject();
                assertTrue(arg.has("name") && arg.has("type") && arg.has("required"),
                        name + " arg is missing name/type/required");
                assertTrue(argNames.add(arg.get("name").getAsString()),
                        name + " has a duplicate arg: " + arg.get("name").getAsString());
            }
        }
    }

    /**
     * The committed {@code clients/schema.json} (the canonical contract artifact other languages
     * codegen from) must match the live schema. Regenerate it with:
     * {@code ./gradlew test -Dclef.schema.write=true}.
     */
    @Test
    void canonicalSchemaJsonStaysInSync() throws Exception {
        JsonObject fresh = ApiSchema.toJson(registerAll().help());
        Path path = Path.of("clients", "schema.json");
        if (Boolean.getBoolean("clef.schema.write") || Files.notExists(path)) {
            Files.createDirectories(path.getParent());
            String pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(fresh) + "\n";
            Files.writeString(path, pretty);
        }
        JsonObject onDisk = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals(fresh, onDisk,
                "clients/schema.json is stale — regenerate with: ./gradlew test -Dclef.schema.write=true");
    }

    @Test
    void exposesEventsAndAllErrorCodes() {
        JsonObject schema = ApiSchema.toJson(registerAll().help());
        assertFalse(schema.getAsJsonArray("events").isEmpty(), "schema should list events");
        assertEquals(ErrorCode.values().length, schema.getAsJsonArray("errors").size(),
                "schema should expose every ErrorCode");
    }

    @Test
    void validatesCommandArgumentsFromSchema() {
        JsonObject ok = new JsonObject();
        ok.addProperty("x", 1);
        ok.addProperty("z", 2);
        ApiSchema.validateArgs("goto", ok);

        JsonObject missing = new JsonObject();
        missing.addProperty("x", 1);
        ApiException ex = assertThrows(ApiException.class, () -> ApiSchema.validateArgs("goto", missing));
        assertEquals(ErrorCode.BAD_ARGS, ex.code);

        JsonObject wrong = new JsonObject();
        wrong.addProperty("events", "chat");
        assertEquals(ErrorCode.BAD_ARGS,
                assertThrows(ApiException.class, () -> ApiSchema.validateArgs("subscribe", wrong)).code);

        assertDoesNotThrow(() -> ApiSchema.validateArgs("not-registered", new JsonObject()));
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> s = new TreeSet<>(a);
        s.removeAll(b);
        return s;
    }
}
