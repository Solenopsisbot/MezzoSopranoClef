package dev.mezzo.clef.api;

import dev.mezzo.clef.bot.ActionManager;
import dev.mezzo.clef.bot.ChatLog;
import dev.mezzo.clef.bot.CraftManager;
import dev.mezzo.clef.bot.InputController;
import dev.mezzo.clef.bot.UseController;
import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.nav.BaritoneNavigator;
import dev.mezzo.clef.nav.Navigator;
import dev.mezzo.clef.screenshot.ScreenshotService;

/** Shared subsystem handles reachable from any command via {@code ctx.server.services}. */
public final class ClefServices {

    public final ScreenshotService screenshots;
    public final Navigator navigator;
    public final InputController input;
    public final ActionManager actions;
    public final UseController use;
    public final CraftManager craft;
    public final ChatLog chatLog;

    public ClefServices(ScreenshotService screenshots, Navigator navigator, InputController input,
                        ActionManager actions, UseController use, CraftManager craft, ChatLog chatLog) {
        this.screenshots = screenshots;
        this.navigator = navigator;
        this.input = input;
        this.actions = actions;
        this.use = use;
        this.craft = craft;
        this.chatLog = chatLog;
    }

    /**
     * Builds the standard set of subsystems from config. Both the client entrypoint and the tests
     * go through this, so adding a subsystem doesn't mean editing a dozen construction sites.
     */
    public static ClefServices standard(ClefConfig config) {
        return new ClefServices(new ScreenshotService(config), new BaritoneNavigator(),
                new InputController(), new ActionManager(), new UseController(),
                new CraftManager(), new ChatLog(config.events.chatHistory));
    }
}
