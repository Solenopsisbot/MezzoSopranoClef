package dev.mezzo.clef.api.commands;

import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.bot.CombatController;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Combat loops that have to run at 20 Hz, on the body.
 *
 * <p>These are the only commands here that deliberately <b>block for seconds</b>. That is the
 * point: the alternative is the caller running the loop over the socket, which is what made bow
 * fire useless against anything that moves. Each call returns when the fight step is over, with
 * what happened. Pass {@code wait:false} to get control back immediately and take the outcome from
 * the {@code combatDone} event instead — which is also how a reflex on a <i>second</i> connection
 * can watch a fight the mind started.</p>
 *
 * <p>Whichever mode is used, {@code combat.stop} on any connection takes the body back.</p>
 *
 * @see CombatController
 */
public final class CombatCommands {

    /** Slack on top of a job's own tick budget, so the job always finishes before the wire gives up. */
    private static final long WAIT_MARGIN_MS = 5_000L;

    public static void registerAll(CommandDispatcher d) {

        d.register("shootAt",
                "loose arrows at an entity, tracking it every tick — solves lead and arrow drop, "
                        + "aims on the release tick {entityId, lead?=true, charge?=25, shots?=1, "
                        + "maxRange?=64, wait?=true}",
                ctx -> {
                    int entityId = ctx.requireInt("entityId");
                    boolean lead = ctx.bool("lead", true);
                    int charge = ctx.i("charge", 25);
                    int shots = ctx.i("shots", 1);
                    double maxRange = ctx.d("maxRange", 64);
                    boolean wait = ctx.bool("wait", true);
                    if (charge < 1) throw ApiException.badArgs("charge must be at least 1 tick");
                    if (shots < 1 || shots > CombatController.MAX_SHOTS) {
                        throw ApiException.badArgs("shots must be 1-" + CombatController.MAX_SHOTS);
                    }
                    if (!(maxRange > 0)) throw ApiException.badArgs("maxRange must be positive");

                    CombatController.ShootRequest request =
                            new CombatController.ShootRequest(entityId, lead, charge, shots, maxRange);
                    CompletableFuture<CombatController.Result> done = ctx.onMain(() ->
                            ctx.server.services.combat.startShoot(MinecraftClient.getInstance(), request));
                    if (!wait) return started("shootAt", entityId);
                    return done.get(waitMs(CombatController.shootBudgetTicks(shots, charge)),
                            TimeUnit.MILLISECONDS).toJson();
                });

        d.register("meleeWhile",
                "swing at an entity on the attack-cooldown cadence while it is in reach — resolves "
                        + "the damageable part of a multi-part entity {entityId, maxMs?=5000, "
                        + "reach?=3.5, stopBelowHealth?, wait?=true}",
                ctx -> {
                    int entityId = ctx.requireInt("entityId");
                    long maxMs = ctx.i("maxMs", 5000);
                    double reach = ctx.d("reach", 3.5);
                    Double stopBelowHealth = ctx.has("stopBelowHealth") ? ctx.d("stopBelowHealth", 0) : null;
                    boolean wait = ctx.bool("wait", true);
                    if (maxMs < 50 || maxMs > CombatController.MAX_MELEE_MS) {
                        throw ApiException.badArgs("maxMs must be 50-" + CombatController.MAX_MELEE_MS);
                    }
                    if (!(reach > 0)) throw ApiException.badArgs("reach must be positive");

                    CombatController.MeleeRequest request =
                            new CombatController.MeleeRequest(entityId, maxMs, reach, stopBelowHealth);
                    CompletableFuture<CombatController.Result> done = ctx.onMain(() ->
                            ctx.server.services.combat.startMelee(MinecraftClient.getInstance(), request));
                    if (!wait) return started("meleeWhile", entityId);
                    return done.get(waitMs(CombatController.meleeBudgetTicks(maxMs)),
                            TimeUnit.MILLISECONDS).toJson();
                });

        d.register("combat.stop",
                "stop a running shootAt/meleeWhile — a part-drawn bow is released, not abandoned, "
                        + "because the protocol has no way to cancel a use",
                ctx -> ctx.onMain(() -> {
                    String kind = ctx.server.services.combat.activeKind();
                    boolean stopped = ctx.server.services.combat.cancel("combat.stop");
                    JsonObject o = new JsonObject();
                    o.addProperty("stopped", stopped);
                    if (kind != null) o.addProperty("kind", kind);
                    return o;
                }));

        d.register("combat.status", "whether a combat loop currently owns the body", ctx -> {
            JsonObject o = new JsonObject();
            String kind = ctx.server.services.combat.activeKind();
            o.addProperty("busy", kind != null);
            if (kind != null) o.addProperty("kind", kind);
            return o;
        });
    }

    /** Longest the wire waits: the job's own budget plus slack, so the job's result always wins. */
    private static long waitMs(int budgetTicks) {
        return budgetTicks * 50L + WAIT_MARGIN_MS;
    }

    private static JsonObject started(String kind, int entityId) {
        JsonObject o = new JsonObject();
        o.addProperty("started", true);
        o.addProperty("kind", kind);
        o.addProperty("entityId", entityId);
        return o;
    }

    private CombatCommands() {}
}
