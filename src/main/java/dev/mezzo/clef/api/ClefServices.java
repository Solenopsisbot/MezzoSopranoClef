package dev.mezzo.clef.api;

import dev.mezzo.clef.bot.ActionManager;
import dev.mezzo.clef.bot.InputController;
import dev.mezzo.clef.bot.UseController;
import dev.mezzo.clef.nav.Navigator;
import dev.mezzo.clef.screenshot.ScreenshotService;

/** Shared subsystem handles reachable from any command via {@code ctx.server.services}. */
public final class ClefServices {

    public final ScreenshotService screenshots;
    public final Navigator navigator;
    public final InputController input;
    public final ActionManager actions;
    public final UseController use;

    public ClefServices(ScreenshotService screenshots, Navigator navigator,
                        InputController input, ActionManager actions, UseController use) {
        this.screenshots = screenshots;
        this.navigator = navigator;
        this.input = input;
        this.actions = actions;
        this.use = use;
    }
}
