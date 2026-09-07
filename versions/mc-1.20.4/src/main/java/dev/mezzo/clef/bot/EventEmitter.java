package dev.mezzo.clef.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.api.ClefServices;
import dev.mezzo.clef.api.ControlServer;
import dev.mezzo.clef.api.ScreenNames;
import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.mixin.client.InGameHudAccessor;
import dev.mezzo.clef.nav.Navigator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

/**
 * Owns the pushed event stream: everything the bot notices without being asked.
 *
 * <p>Split out of {@code ClefClient} because it grew past the point where "the mod entrypoint" was
 * an honest description of it. {@code ClefClient} still wires the sources; this decides what is
 * worth telling anybody about.</p>
 *
 * <h2>Two kinds of source</h2>
 * <ul>
 *   <li><b>Polled</b> (this class): health, weather, time of day, titles, inventory, sleep, nearby
 *       entities. Anything the world model already holds, diffed once per tick. No mixins, nothing
 *       to break on a Minecraft update.</li>
 *   <li><b>Pushed</b> ({@code ClientPlayNetworkHandlerEventsMixin} via {@code Events}): block
 *       updates, item pickups, entity damage, explosions — facts that only exist on the wire.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * Every non-trivial diff is behind {@code hasSubscribers}, so a bot nobody is watching does almost
 * no work here. The cheap always-on ones (health, death) stay unconditional because they're a
 * handful of field reads and the auto-respawn behaviour depends on them.
 */
public final class EventEmitter {

    /** "tick" state event cadence (~1s). */
    private static final int TICK_EVENT_INTERVAL = 20;
    /** entity spawn/remove diff cadence. */
    private static final int ENTITY_EVENT_INTERVAL = 10;
    private static final double ENTITY_EVENT_RADIUS = 24.0;
    /** Inventory diffs are cheap but not free; twice a second is plenty for a bot. */
    private static final int INVENTORY_EVENT_INTERVAL = 10;

    private final ControlServer control;
    private final ClefServices services;
    private final ClefConfig config;

    private float lastHealth = Float.NaN;
    private int lastFood = -1;
    private boolean wasDead;
    private Set<String> lastPlayers;
    private String lastScreen = "none";
    private Set<Integer> lastEntities;
    private int eventTickCounter;
    private String lastWeather;
    private String lastTimePhase;
    private String lastTitle;
    private String lastSubtitle;
    private String lastActionBar;
    private String[] lastInventory;
    private boolean wasSleeping;

    public EventEmitter(ControlServer control, ClefServices services, ClefConfig config) {
        this.control = control;
        this.services = services;
        this.config = config;
        services.navigator.setListener(new NavBridge());
        services.actions.setMineListener(this::onMineDone);
        services.combat.setDoneListener(this::onCombatDone);
    }

    /** Forgets all diff state, so a rejoin doesn't emit a flood of spurious "changed" events. */
    public void reset() {
        lastHealth = Float.NaN;
        lastFood = -1;
        lastPlayers = null;
        lastEntities = null;
        lastScreen = "none";
        lastWeather = null;
        lastTimePhase = null;
        lastTitle = null;
        lastSubtitle = null;
        lastActionBar = null;
        lastInventory = null;
        wasSleeping = false;
    }

    // ---- chat -----------------------------------------------------------------------

    /**
     * Records and publishes an incoming message. {@code kind} is the classified
     * {@code chat|system|whisper|team|actionbar}; {@code raw} is the message's JSON component, so a
     * client can read colours, click events and translation keys instead of a flattened string.
     */
    public void onMessage(String kind, String sender, Component message) {
        String text = message == null ? "" : message.getString();
        services.chatLog.add(kind, sender, text);
        if (!control.hasSubscribers("chat")) return;
        JsonObject data = new JsonObject();
        data.addProperty("text", text);
        if (sender != null) data.addProperty("sender", sender);
        data.addProperty("kind", kind);
        JsonElement raw = rawText(message);
        if (raw != null) data.add("raw", raw);
        control.emitEvent("chat", data);
    }

    /** Classifies a signed player message from the message type the server tagged it with. */
    public static String kindOf(ChatType.Bound params) {
        if (params == null) return "chat";
        // ChatType.Bound carries the ChatType itself here rather than a Holder, so there is no
        // ResourceKey to compare and no registry access in a static helper. Whisper/team
        // classification is unavailable on this release; player-sent messages report as "chat".
        return "chat";
    }

    /**
     * Classifies an unsigned game/system message. Whispers and team chat also arrive this way on
     * servers that use {@code /tellraw}-style output, so we look at the translation key — those are
     * stable identifiers, unlike the rendered text, which is localised.
     */
    public static String kindOfSystem(Component message, boolean overlay) {
        if (overlay) return "actionbar";
        String key = translationKey(message);
        if (key == null) return "system";
        if (key.startsWith("commands.message.display")) return "whisper";
        if (key.startsWith("chat.type.team")) return "team";
        if (key.startsWith("chat.type.text") || key.startsWith("chat.type.announcement")
                || key.startsWith("chat.type.emote")) {
            return "chat";
        }
        return "system";
    }

    // ---- per-tick polling -------------------------------------------------------------

    public void tick(Minecraft mc) {
        emitScreenChange(mc);
        if (mc.player == null || mc.level == null) return;
        eventTickCounter++;

        emitVitals(mc);
        emitTickSnapshot(mc);
        emitPlayerListDiff(mc);
        emitEntityDiff(mc);
        emitWeather(mc);
        emitTimePhase(mc);
        emitTitles(mc);
        emitInventoryDiff(mc);
        emitSleep(mc);

        services.navigator.tick(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    /** screen open/close — handy for UI automation (react to a chest/furnace/trade opening). */
    private void emitScreenChange(Minecraft mc) {
        String screen = ScreenNames.of(mc.screen);
        String raw = ScreenNames.rawOf(mc.screen);
        if (raw.equals(lastScreen)) return;   // diff on the raw name: two chests are the same "container"
        JsonObject data = new JsonObject();
        data.addProperty("screen", screen);
        data.addProperty("screenClass", raw);
        if (!ScreenNames.NONE.equals(screen) && mc.screen instanceof AbstractContainerScreen) {
            control.emitEvent("screenOpen", data);
        } else if (ScreenNames.NONE.equals(screen)) {
            control.emitEvent("screenClose", data);
        }
        lastScreen = raw;
    }

    /** health / damage / death / respawn. Unconditional: auto-respawn depends on the death edge. */
    private void emitVitals(Minecraft mc) {
        float hp = mc.player.getHealth();
        int food = mc.player.getFoodData().getFoodLevel();
        float prevHp = lastHealth;
        if (hp != lastHealth || food != lastFood) {
            JsonObject data = new JsonObject();
            data.addProperty("health", hp);
            data.addProperty("food", food);
            control.emitEvent("health", data);
        }
        if (!Float.isNaN(prevHp) && hp < prevHp) {
            JsonObject data = new JsonObject();
            data.addProperty("amount", prevHp - hp);
            data.addProperty("health", hp);
            control.emitEvent("damage", data);
        }
        lastHealth = hp;
        lastFood = food;

        boolean dead = hp <= 0f;
        if (dead && !wasDead) {
            wasDead = true;
            control.emitEvent("death", new JsonObject());
            if (config.connection.autoRespawn) {
                try {
                    mc.player.respawn();
                } catch (Throwable t) {
                    MezzoClef.LOG.warn("Auto-respawn failed: {}", t.toString());
                }
            }
        } else if (!dead) {
            if (wasDead) control.emitEvent("respawn", new JsonObject());
            wasDead = false;
        }
    }

    private void emitTickSnapshot(Minecraft mc) {
        if (eventTickCounter % TICK_EVENT_INTERVAL != 0 || !control.hasSubscribers("tick")) return;
        JsonObject data = new JsonObject();
        data.addProperty("x", mc.player.getX());
        data.addProperty("y", mc.player.getY());
        data.addProperty("z", mc.player.getZ());
        data.addProperty("yaw", mc.player.getYRot());
        data.addProperty("pitch", mc.player.getXRot());
        data.addProperty("health", mc.player.getHealth());
        data.addProperty("food", mc.player.getFoodData().getFoodLevel());
        data.addProperty("dimension", mc.level.dimension().location().toString());
        control.emitEvent("tick", data);
    }

    private void emitPlayerListDiff(Minecraft mc) {
        if (eventTickCounter % 10 != 0 || mc.getConnection() == null) return;
        Set<String> current = new HashSet<>();
        for (PlayerInfo e : mc.getConnection().getOnlinePlayers()) {
            current.add(e.getProfile().getName());
        }
        if (lastPlayers != null) {
            for (String name : current) {
                if (!lastPlayers.contains(name)) emitNamed("join", name);
            }
            for (String name : lastPlayers) {
                if (!current.contains(name)) emitNamed("leave", name);
            }
        }
        lastPlayers = current;
    }

    private void emitNamed(String event, String name) {
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        control.emitEvent(event, data);
    }

    private void emitEntityDiff(Minecraft mc) {
        if (eventTickCounter % ENTITY_EVENT_INTERVAL != 0) return;
        if (!control.hasSubscribers("entitySpawn") && !control.hasSubscribers("entityRemove")) return;
        Set<Integer> current = new HashSet<>();
        Map<Integer, Entity> byId = new HashMap<>();
        double r2 = ENTITY_EVENT_RADIUS * ENTITY_EVENT_RADIUS;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e.distanceToSqr(mc.player) > r2) continue;
            current.add(e.getId());
            byId.put(e.getId(), e);
        }
        if (lastEntities != null) {
            for (Integer id : current) {
                if (lastEntities.contains(id)) continue;
                Entity e = byId.get(id);
                JsonObject data = new JsonObject();
                data.addProperty("id", id);
                data.addProperty("type", EntityType.getKey(e.getType()).toString());
                data.addProperty("x", e.getX());
                data.addProperty("y", e.getY());
                data.addProperty("z", e.getZ());
                control.emitEvent("entitySpawn", data);
            }
            for (Integer id : lastEntities) {
                if (current.contains(id)) continue;
                JsonObject data = new JsonObject();
                data.addProperty("id", id);
                control.emitEvent("entityRemove", data);
            }
        }
        lastEntities = current;
    }

    private void emitWeather(Minecraft mc) {
        String kind = mc.level.isThundering() ? "thunder" : mc.level.isRaining() ? "rain" : "clear";
        if (kind.equals(lastWeather)) return;
        boolean first = lastWeather == null;
        lastWeather = kind;
        if (first || !control.hasSubscribers("weather")) return;   // don't announce the state we joined into
        JsonObject data = new JsonObject();
        data.addProperty("kind", kind);
        control.emitEvent("weather", data);
    }

    private void emitTimePhase(Minecraft mc) {
        String phase = phaseOf(mc.level.getDayTime());
        if (phase.equals(lastTimePhase)) return;
        boolean first = lastTimePhase == null;
        lastTimePhase = phase;
        if (first || !control.hasSubscribers("time")) return;
        JsonObject data = new JsonObject();
        data.addProperty("phase", phase);
        data.addProperty("time", mc.level.getDayTime() % 24000L);
        data.addProperty("day", mc.level.getDayTime() / 24000L);
        control.emitEvent("time", data);
    }

    /** Vanilla day cycle: 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight. */
    public static String phaseOf(long timeOfDay) {
        long t = Math.floorMod(timeOfDay, 24000L);
        if (t < 1000) return "dawn";
        if (t < 12000) return "day";
        if (t < 13000) return "dusk";
        if (t < 23000) return "night";
        return "dawn";
    }

    private void emitTitles(Minecraft mc) {
        if (!control.hasSubscribers("title")) return;
        String title, subtitle, actionBar;
        try {
            InGameHudAccessor hud = (InGameHudAccessor) (Object) mc.gui;
            title = flat(hud.clef$getTitle());
            subtitle = flat(hud.clef$getSubtitle());
            actionBar = flat(hud.clef$getOverlayMessage());
        } catch (Throwable ignored) {
            return;
        }
        if (title.equals(lastTitle) && subtitle.equals(lastSubtitle) && actionBar.equals(lastActionBar)) {
            return;
        }
        boolean first = lastTitle == null;
        lastTitle = title;
        lastSubtitle = subtitle;
        lastActionBar = actionBar;
        if (first) return;
        JsonObject data = new JsonObject();
        data.addProperty("title", title);
        data.addProperty("subtitle", subtitle);
        data.addProperty("actionBar", actionBar);
        control.emitEvent("title", data);
    }

    /**
     * Emits the set of inventory slots whose contents changed, coalesced into one event. A bot that
     * wants the values follows up with {@code inventory}; sending them here would mean pushing the
     * whole inventory several times a second.
     */
    private void emitInventoryDiff(Minecraft mc) {
        if (eventTickCounter % INVENTORY_EVENT_INTERVAL != 0) return;
        if (!control.hasSubscribers("inventory")) {
            lastInventory = null;   // resync from scratch next time somebody subscribes
            return;
        }
        var inv = mc.player.getInventory();
        String[] current = new String[inv.getContainerSize()];
        for (int i = 0; i < current.length; i++) {
            ItemStack stack = inv.getItem(i);
            current[i] = stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount();
        }
        if (lastInventory != null && lastInventory.length == current.length) {
            JsonArray changed = new JsonArray();
            for (int i = 0; i < current.length; i++) {
                if (!current[i].equals(lastInventory[i])) changed.add(i);
            }
            if (!changed.isEmpty()) {
                JsonObject data = new JsonObject();
                data.add("changed", changed);
                control.emitEvent("inventory", data);
            }
        }
        lastInventory = current;
    }

    private void emitSleep(Minecraft mc) {
        boolean sleeping = mc.player.isSleeping();
        if (sleeping == wasSleeping) return;
        wasSleeping = sleeping;
        if (!sleeping || !control.hasSubscribers("sleep")) return;
        JsonObject data = new JsonObject();
        data.addProperty("ok", true);
        mc.player.getSleepingPos().ifPresent(pos -> {
            data.addProperty("x", pos.getX());
            data.addProperty("y", pos.getY());
            data.addProperty("z", pos.getZ());
        });
        control.emitEvent("sleep", data);
    }

    /**
     * The server reports a refused bed as a translated system message, so that's where a sleep
     * failure has to be read from. The keys are stable identifiers; the rendered text isn't.
     */
    public void onSystemMessageForSleep(Component message) {
        if (!control.hasSubscribers("sleep")) return;
        String key = translationKey(message);
        if (key == null || !key.startsWith("block.minecraft.bed.")) return;
        JsonObject data = new JsonObject();
        data.addProperty("ok", false);
        data.addProperty("reason", key.substring("block.minecraft.bed.".length()));
        data.addProperty("key", key);
        control.emitEvent("sleep", data);
    }

    // ---- callbacks from the action/navigation subsystems --------------------------------

    private void onMineDone(ActionManager.MineResult result) {
        if (!control.hasSubscribers("mineDone")) return;
        JsonObject data = new JsonObject();
        data.addProperty("x", result.pos().getX());
        data.addProperty("y", result.pos().getY());
        data.addProperty("z", result.pos().getZ());
        data.addProperty("broken", result.broken());
        if (result.reason() != null) data.addProperty("reason", result.reason());
        if (result.detail() != null) data.addProperty("detail", result.detail());
        data.addProperty("ticks", result.ticks());
        control.emitEvent("mineDone", data);
    }

    private void onCombatDone(CombatController.Result result) {
        if (!control.hasSubscribers("combatDone")) return;
        control.emitEvent("combatDone", result.toJson());
    }

    /** Turns navigator completions into {@code nav.done} / {@code nav.failed}. */
    private final class NavBridge implements Navigator.Listener {
        @Override
        public void onArrived(Navigator.Goal goal) {
            JsonObject data = goalJson(goal);
            control.emitEvent("nav.done", data);
        }

        @Override
        public void onFailed(Navigator.Goal goal, String reason) {
            JsonObject data = goalJson(goal);
            data.addProperty("reason", reason);
            // Always true today, and stated explicitly so a client never has to guess whether a
            // nav.failed it just received might still be followed by a nav.done for the same goal.
            data.addProperty("terminal", true);
            control.emitEvent("nav.failed", data);
        }

        @Override
        public void onProgress(Navigator.Goal goal, String event) {
            if (!control.hasSubscribers("nav.progress")) return;
            JsonObject data = goalJson(goal);
            data.addProperty("event", event);
            control.emitEvent("nav.progress", data);
        }

        private JsonObject goalJson(Navigator.Goal goal) {
            JsonObject data = new JsonObject();
            data.addProperty("x", goal.x());
            if (goal.y() != null) data.addProperty("y", goal.y());
            data.addProperty("z", goal.z());
            data.addProperty("reach", goal.reach());
            return data;
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    private static String flat(Component text) {
        return text == null ? "" : text.getString();
    }

    private static String translationKey(Component message) {
        return message != null && message.getContents() instanceof TranslatableContents t ? t.getKey() : null;
    }

    /**
     * Serializes a {@link Component} to its JSON component form. Needs the world's dynamic registries
     * (a component can reference an item or an entity type), so it degrades to null off-world
     * rather than guessing.
     */
    private static JsonElement rawText(Component message) {
        if (message == null) return null;
        try {
            Minecraft mc = Minecraft.getInstance();
            var ops = mc.level != null
                    ? RegistryOps.create(JsonOps.INSTANCE, mc.level.registryAccess())
                    : JsonOps.INSTANCE;
            return ComponentSerialization.CODEC.encodeStart(ops, message).result().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
