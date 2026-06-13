package dev.mezzo.clef.api;

import com.google.gson.JsonElement;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/** Name -> {@link Command} registry. */
public final class CommandDispatcher {

    private final Map<String, Command> commands = new ConcurrentSkipListMap<>();
    private final Map<String, String> help = new TreeMap<>();

    public void register(String name, String description, Command command) {
        commands.put(name, command);
        help.put(name, description == null ? "" : description);
    }

    public JsonElement dispatch(String name, CommandContext ctx) throws Exception {
        Command c = commands.get(name);
        if (c == null) {
            throw new IllegalArgumentException("unknown command: '" + name + "' (try 'help')");
        }
        return c.run(ctx);
    }

    public Set<String> names() {
        return commands.keySet();
    }

    public Map<String, String> help() {
        return help;
    }
}
