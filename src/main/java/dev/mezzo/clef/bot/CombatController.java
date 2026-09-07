package dev.mezzo.clef.bot;

import com.google.gson.JsonObject;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The 20 Hz half of fighting: {@code shootAt} and {@code meleeWhile}.
 *
 * <h2>Why this is here and not in the agent</h2>
 * Everything else the control plane exposes is a question the client can answer in one round trip.
 * Aiming a bow is not. Doing it over the socket costs four round trips — sample the target,
 * {@code useHold}, sample again, {@code lookAt}, {@code useRelease} — about a second and a half per
 * arrow, against a target that updates twenty times a second. An ender dragon covers twenty blocks
 * in that gap, so the arrow is always loosed at where the dragon used to be. Measured on a real
 * fight: hundreds of arrows, fifty-four spent shafts on the ground at once, three points of damage
 * in ten minutes. No amount of tuning at the far end of a 1.5 s control loop fixes that; the loop
 * has to run where the world is.
 *
 * <p>So the split is the one that already works for movement. The mind decides <i>fight that</i>,
 * a reflex decides <i>it has landed, go melee</i>, and the body does the per-tick work: track the
 * target, solve the lead from its real motion and the drop from a real arrow simulation, set the
 * rotation on the release tick, loose.</p>
 *
 * <p>What this cannot fix is vanilla's own scatter: {@code BowItem} looses with a divergence of
 * 1.0, which is roughly a degree of random spread — about two thirds of a block at forty. Players
 * have exactly the same handicap. A perfect solution still misses sometimes, so judge this by
 * whether the damage moves, not by whether every arrow lands.</p>
 *
 * <h2>Threading</h2>
 * Every method except {@link #isBusy()} and {@link #activeKind()} must be called on the Minecraft
 * client thread — the command layer gets there through {@code ctx.onMain}. {@link #tick} is driven
 * once per client tick. One job runs at a time; starting a second reports the first as
 * {@code replaced}, the same contract {@link ActionManager} uses for mining.
 */
public final class CombatController {

    /**
     * Ticks between a release and the next draw. {@code Minecraft} arms a 4-tick
     * {@code itemUseCooldown} whenever a use begins, and the server needs a moment to consume the
     * arrow, so anything shorter just stalls in {@code DRAW} anyway.
     */
    private static final int SHOT_RECOVERY_TICKS = 5;

    /**
     * How long a draw may sit at zero progress before we call it blocked. The use starts one tick
     * after the key goes down, plus whatever the server adds; twenty ticks is a second of grace.
     */
    private static final int DRAW_START_SLACK_TICKS = 20;

    /** Longest we keep watching after the last arrow for it to land before reporting. */
    private static final int MAX_SETTLE_TICKS = 40;

    /** Extra ticks of watching after the final arrow's predicted flight time. */
    private static final int SETTLE_MARGIN_TICKS = 5;

    /** A whole command's worth of arrows. Bounds how long one call can hold its connection. */
    public static final int MAX_SHOTS = 64;

    /** Two minutes of swinging is already far past the point of asking the mind again. */
    public static final long MAX_MELEE_MS = 120_000L;

    /** Lead is solved against the client's view of the world, which trails the server. Cap the
     *  correction so a pathological ping can't throw the aim into orbit. */
    private static final int MAX_LATENCY_LEAD_TICKS = 10;

    /** Iterations of "where will it be when the arrow lands" vs "how long will the arrow take". */
    private static final int LEAD_ITERATIONS = 3;

    /** Arrows spawn at the shooter's eye minus this, per {@code AbstractArrow}. */
    private static final double ARROW_SPAWN_EYE_OFFSET = 0.1;

    /**
     * Hard tick budget for a shoot job: enough for every arrow to draw, loose, recover and land,
     * with slack for a slow server. It exists so a stall always ends in a result rather than a
     * caller blocked forever, and it is public so the command layer can size its own wait from the
     * same formula instead of guessing at one that drifts.
     */
    public static int shootBudgetTicks(int shots, int chargeTicks) {
        return shots * (chargeTicks + SHOT_RECOVERY_TICKS + DRAW_START_SLACK_TICKS)
                + MAX_SETTLE_TICKS + 40;
    }

    /** Hard tick budget for a melee job — {@code maxMs} plus a second of slack. */
    public static int meleeBudgetTicks(long maxMs) {
        return (int) (maxMs / 50) + 20;
    }

    /**
     * A {@code shootAt} request.
     *
     * @param entityId    who to shoot; a dragon part id is resolved to the dragon
     * @param lead        aim where the target is going rather than where it is
     * @param chargeTicks how long to draw. 20 is full power; the default leaves margin for latency,
     *                    because the server counts the draw from when <i>it</i> saw it start
     * @param shots       how many arrows to loose
     * @param maxRange    refuse to start, and stop, beyond this many blocks
     */
    public record ShootRequest(int entityId, boolean lead, int chargeTicks, int shots, double maxRange) {}

    /**
     * A {@code meleeWhile} request.
     *
     * @param entityId         who to hit; a dragon part id is resolved to the dragon
     * @param maxMs            give up after this long
     * @param reach            swing when the hitbox is this close to our eyes
     * @param stopBelowHealth  hand the body back when our own health drops below this, or null
     */
    public record MeleeRequest(int entityId, long maxMs, double reach, Double stopBelowHealth) {}

    /**
     * How a combat job ended.
     *
     * @param kind    {@code shootAt} or {@code meleeWhile}
     * @param fired   arrows loosed (always 0 for melee)
     * @param hits    for {@code meleeWhile}, swings issued while the target was in reach; for
     *                {@code shootAt}, the number of separate times the target's health dropped
     *                while we were shooting at it. Either way it counts what we could observe, not
     *                what we can prove: health can drop from anything, and a swing in reach can
     *                still be refused by the server
     * @param damage  total health the target lost over the run, same caveat
     * @param stopped {@code done | dead | gone | timeout | range | health | blocked | out_of_ammo |
     *                cancelled | replaced}
     */
    public record Result(String kind, int entityId, int fired, int hits, double damage,
                         boolean killed, String stopped, String detail, int ticks) {

        /**
         * The wire shape, shared by a waiting {@code shootAt}/{@code meleeWhile} and the
         * {@code combatDone} event so the two cannot drift.
         */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind);
            o.addProperty("entityId", entityId);
            o.addProperty("fired", fired);
            o.addProperty("hits", hits);
            o.addProperty("damage", damage);
            o.addProperty("killed", killed);
            o.addProperty("stopped", stopped);
            if (detail != null) o.addProperty("detail", detail);
            o.addProperty("ticks", ticks);
            return o;
        }
    }

    /** Called exactly once per started job, on the client thread. */
    @FunctionalInterface
    public interface DoneListener {
        void onCombatDone(Result result);
    }

    private final ActionManager actions;
    private volatile Job job;
    private volatile DoneListener listener;

    public CombatController(ActionManager actions) {
        this.actions = actions;
    }

    /** Installs the sink that receives {@code combatDone}. Replaces any previous listener. */
    public void setDoneListener(DoneListener listener) {
        this.listener = listener;
    }

    public boolean isBusy() {
        return job != null;
    }

    /** {@code shootAt} / {@code meleeWhile} while a job is running, else null. */
    public String activeKind() {
        Job current = job;
        return current == null ? null : current.kind();
    }

    // ---- starting -------------------------------------------------------------------

    /**
     * Begins loosing arrows at an entity. Client thread only.
     *
     * @return a future completed exactly once, when the job ends
     * @throws ApiException if there is no such entity, it is out of range, or there is no bow and
     *                      arrows to shoot it with
     */
    public CompletableFuture<Result> startShoot(Minecraft mc, ShootRequest request) {
        LocalPlayer player = requirePlayer(mc);
        Entity target = requireTarget(mc, request.entityId());
        double distance = Math.sqrt(target.distanceToSqr(player));
        if (distance > request.maxRange()) {
            throw ApiException.notFound("entity " + request.entityId() + " is " + Math.round(distance)
                    + " blocks away, beyond maxRange " + request.maxRange());
        }

        // Get a bow in hand before promising anything. An already-held bow may be enchanted, so
        // prefer it over swapping in whatever else the inventory has.
        if (!(player.getMainHandItem().getItem() instanceof BowItem)) {
            if (Hotbar.count(player.getInventory(), Items.BOW) == 0) {
                throw new ApiException(ErrorCode.MISSING_ITEM, "no bow in inventory");
            }
            Hotbar.select(mc, Items.BOW, null);
        }
        ItemStack bow = player.getMainHandItem();
        if (player.getProjectile(bow).isEmpty()) {
            throw new ApiException(ErrorCode.MISSING_ITEM, "no arrows for the bow");
        }

        ShootJob shoot = new ShootJob(target.getId(), request);
        return begin(mc, shoot, target);
    }

    /**
     * Begins swinging at an entity on the attack-cooldown cadence. Client thread only.
     *
     * @return a future completed exactly once, when the job ends
     * @throws ApiException if there is no such entity
     */
    public CompletableFuture<Result> startMelee(Minecraft mc, MeleeRequest request) {
        requirePlayer(mc);
        Entity target = requireTarget(mc, request.entityId());
        return begin(mc, new MeleeJob(target.getId(), request), target);
    }

    private CompletableFuture<Result> begin(Minecraft mc, Job next, Entity target) {
        finish(mc, "replaced", "superseded by a new combat request");
        next.lastHealth = healthOf(target);
        job = next;
        return next.future;
    }

    /**
     * Ends whatever is running. Safe to call with nothing in flight.
     *
     * <p>A bow that is part-way through a draw is <i>released</i>, not abandoned: the protocol has
     * no "cancel the use" message, so leaving it drawn would desync us from the server. That costs
     * one weak arrow.</p>
     *
     * @return true if something was actually stopped
     */
    public boolean cancel(String detail) {
        return finish(Minecraft.getInstance(), "cancelled", detail);
    }

    // ---- the loop -------------------------------------------------------------------

    /** Drives the running job. Called once per client tick. */
    public void tick(Minecraft mc) {
        Job current = job;
        if (current == null) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            finish(mc, "gone", "left the world mid-fight");
            return;
        }
        Entity target = findEntity(mc, current.entityId);
        if (target == null) {
            finish(mc, "gone", "the target is no longer tracked by the client");
            return;
        }

        current.ticks++;
        current.trackHealth(target);
        if (!target.isAlive()) {
            current.killed = true;
            finish(mc, "dead", null);
            return;
        }
        if (current.ticks > current.budgetTicks()) {
            finish(mc, "timeout", "ran out of its tick budget");
            return;
        }

        try {
            if (current.tick(mc, target)) finish(mc, current.stopped, current.detail);
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Combat job threw, stopping: {}", t.toString());
            finish(mc, "cancelled", "the combat loop failed: " + t);
        }
    }

    /** Completes the in-flight job, if any, exactly once. */
    private boolean finish(Minecraft mc, String stopped, String detail) {
        Job current = job;
        if (current == null) return false;
        job = null;
        try {
            current.release(mc);
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Releasing combat input failed: {}", t.toString());
        }
        Result result = new Result(current.kind(), current.entityId, current.fired, current.hits(),
                current.damage, current.killed, stopped, detail, current.ticks);
        current.future.complete(result);
        DoneListener sink = listener;
        if (sink != null) {
            try {
                sink.onCombatDone(result);
            } catch (Throwable t) {
                MezzoClef.LOG.warn("Combat listener threw: {}", t.toString());
            }
        }
        return true;
    }

    // ---- jobs -----------------------------------------------------------------------

    private abstract static class Job {
        final int entityId;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        int ticks;
        int fired;
        /** Swings we issued (melee). */
        int swings;
        /** Separate occasions the target's health went down while we were on it (shooting). */
        int healthDrops;
        double damage;
        boolean killed;
        float lastHealth = Float.NaN;
        /** Set by {@link #tick} on the tick it decides to stop. */
        String stopped;
        String detail;

        Job(int entityId) {
            this.entityId = entityId;
        }

        abstract String kind();

        /** What {@code hits} means for this kind of job. */
        abstract int hits();

        /** Hard upper bound on how long this job may run, so a stall always ends. */
        abstract int budgetTicks();

        /** @return true when the job is finished; set {@link #stopped} first. */
        abstract boolean tick(Minecraft mc, Entity target);

        /** Hands the body's input state back. */
        void release(Minecraft mc) {}

        /**
         * Counts health the target lost. Any source counts — we cannot see damage attribution from
         * here, only that it got hurt while we were attacking it.
         */
        void trackHealth(Entity target) {
            float now = healthOf(target);
            if (Float.isNaN(now)) return;
            if (!Float.isNaN(lastHealth) && now < lastHealth) {
                healthDrops++;
                damage += lastHealth - now;
            }
            lastHealth = now;
        }
    }

    /**
     * Draw, track, solve, loose. The one thing that has to happen on the release tick is the
     * rotation: the server fires the arrow along the rotation it last heard from us, so the aim is
     * pushed as an explicit look packet immediately before the release rather than waiting for the
     * next movement packet, which would land a tick too late.
     */
    private static final class ShootJob extends Job {

        private enum Phase { DRAW, RECOVER, SETTLE }

        private final ShootRequest request;
        private Phase phase = Phase.DRAW;
        private int phaseTicks;
        private int settleTicks;

        ShootJob(int entityId, ShootRequest request) {
            super(entityId);
            this.request = request;
        }

        @Override
        String kind() {
            return "shootAt";
        }

        @Override
        int hits() {
            return healthDrops;
        }

        @Override
        int budgetTicks() {
            return shootBudgetTicks(request.shots(), request.chargeTicks());
        }

        @Override
        boolean tick(Minecraft mc, Entity target) {
            LocalPlayer player = mc.player;
            phaseTicks++;
            switch (phase) {
                case DRAW -> {
                    return draw(mc, player, target);
                }
                case RECOVER -> {
                    aimIdly(mc, player, target);
                    if (phaseTicks >= SHOT_RECOVERY_TICKS) enter(Phase.DRAW);
                }
                case SETTLE -> {
                    if (phaseTicks >= settleTicks) {
                        stopped = "done";
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean draw(Minecraft mc, LocalPlayer player, Entity target) {
            double distance = Math.sqrt(target.distanceToSqr(player));
            if (distance > request.maxRange()) {
                stopped = "range";
                detail = "the target moved beyond maxRange " + request.maxRange();
                return true;
            }
            if (mc.options == null) {
                stopped = "blocked";
                detail = "the client has no options/key bindings yet";
                return true;
            }
            ItemStack bow = player.getMainHandItem();
            if (!(bow.getItem() instanceof BowItem)) {
                stopped = "blocked";
                detail = "the bow left the main hand";
                return true;
            }
            if (player.getProjectile(bow).isEmpty()) {
                stopped = "out_of_ammo";
                detail = "fired " + fired + " before running out of arrows";
                return true;
            }

            mc.options.keyUse.setDown(true);
            int drawn = player.getTicksUsingItem();
            if (drawn <= 0 && phaseTicks > DRAW_START_SLACK_TICKS) {
                // The use never started. Almost always an open screen: Minecraft skips the
                // whole input path while one is up, so the key press goes nowhere.
                stopped = "blocked";
                detail = mc.gui.screen() != null
                        ? "the draw never started — a screen is open (" + mc.gui.screen().getClass().getSimpleName() + ")"
                        : "the draw never started";
                return true;
            }

            if (drawn < request.chargeTicks()) {
                aimIdly(mc, player, target);       // track while charging, so the last turn is small
                return false;
            }

            Solution shot = solve(mc, player, target, Ballistics.bowSpeed(drawn));
            if (shot == null) {
                aimIdly(mc, player, target);       // no arc reaches it right now; keep the bow drawn
                return false;
            }
            point(mc, player, shot.aimPoint(), (float) -shot.elevationDegrees(), true);
            mc.options.keyUse.setDown(false);
            mc.gameMode.releaseUsingItem(player);
            fired++;

            if (fired >= request.shots()) {
                settleTicks = Mth.clamp((int) Math.ceil(shot.flightTicks()) + SETTLE_MARGIN_TICKS,
                        SETTLE_MARGIN_TICKS, MAX_SETTLE_TICKS);
                enter(Phase.SETTLE);
            } else {
                enter(Phase.RECOVER);
            }
            return false;
        }

        /** Keeps the head on the target between decisions, without committing to a firing solution. */
        private void aimIdly(Minecraft mc, LocalPlayer player, Entity target) {
            Solution shot = solve(mc, player, target, Ballistics.BOW_MAX_SPEED);
            if (shot != null) {
                point(mc, player, shot.aimPoint(), (float) -shot.elevationDegrees(), false);
            } else {
                lookAt(mc, player, aimPointOf(player, target), false);
            }
        }

        private void enter(Phase next) {
            phase = next;
            phaseTicks = 0;
        }

        @Override
        void release(Minecraft mc) {
            if (mc.options != null) mc.options.keyUse.setDown(false);
            if (mc.player != null && mc.gameMode != null && mc.player.isUsingItem()) {
                mc.gameMode.releaseUsingItem(mc.player);
            }
        }

        /**
         * Solves the firing solution for where the target will be when the arrow gets there, which
         * needs the flight time, which needs the solution. Two or three passes converge: the first
         * gives a flight time good to a few ticks, the second a lead good to a fraction of a block.
         */
        private Solution solve(Minecraft mc, LocalPlayer player, Entity target, double speed) {
            Vec3 origin = eyePosition(player).subtract(0, ARROW_SPAWN_EYE_OFFSET, 0);
            Vec3 here = aimPointOf(player, target);
            Vec3 motion = request.lead() ? EntityMotion.of(target) : Vec3.ZERO;
            // The client's view of the target trails the server by about half the round trip, and
            // the arrow is spawned server-side, so lead by that much on top of the flight time.
            double offsetTicks = 1 + latencyTicks(mc);

            Ballistics.Aim aim = solveTo(origin, here, speed);
            if (aim == null) return null;
            Vec3 aimPoint = here;
            for (int i = 1; i < LEAD_ITERATIONS && motion.lengthSqr() > 0; i++) {
                Vec3 candidate = here.add(motion.scale(aim.flightTicks() + offsetTicks));
                Ballistics.Aim next = solveTo(origin, candidate, speed);
                if (next == null) break;           // the lead ran out of range; keep the last good one
                aimPoint = candidate;
                aim = next;
            }
            return new Solution(aimPoint, aim.elevationDegrees(), aim.flightTicks());
        }

        private Ballistics.Aim solveTo(Vec3 origin, Vec3 point, double speed) {
            double horizontal = Math.hypot(point.x - origin.x, point.z - origin.z);
            return Ballistics.solve(horizontal, point.y - origin.y, speed);
        }
    }

    /** Where to point, and what to expect. */
    private record Solution(Vec3 aimPoint, double elevationDegrees, double flightTicks) {}

    /**
     * Swings on the attack-cooldown cadence while the target is in reach.
     *
     * <p>For a multi-part entity this resolves the <b>part</b>, because the parent ignores damage
     * outright — {@code /damage} on an {@code ender_dragon} cheerfully reports "Applied 5.0" and
     * changes nothing. Only the parts forward damage to the dragon.</p>
     */
    private final class MeleeJob extends Job {

        private final MeleeRequest request;

        MeleeJob(int entityId, MeleeRequest request) {
            super(entityId);
            this.request = request;
        }

        @Override
        String kind() {
            return "meleeWhile";
        }

        @Override
        int hits() {
            return swings;
        }

        @Override
        int budgetTicks() {
            return meleeBudgetTicks(request.maxMs());
        }

        @Override
        boolean tick(Minecraft mc, Entity target) {
            LocalPlayer player = mc.player;
            if (request.stopBelowHealth() != null && player.getHealth() < request.stopBelowHealth()) {
                stopped = "health";
                detail = "own health fell to " + player.getHealth();
                return true;
            }
            if (ticks * 50L >= request.maxMs()) {
                stopped = "timeout";
                return true;
            }

            Entity hitbox = chooseHitbox(mc, player, target, request.reach());
            lookAt(mc, player, hitbox.getBoundingBox().getCenter(), false);
            double distance = Math.sqrt(squaredDistanceToBox(eyePosition(player), hitbox.getBoundingBox()));
            if (distance <= request.reach() && player.getAttackStrengthScale(0.0f) >= 1.0f) {
                actions.attackEntity(mc, hitbox);
                swings++;
            }
            return false;
        }
    }

    // ---- target resolution ----------------------------------------------------------

    /**
     * Finds an entity by id, including ender dragon parts.
     *
     * <p>Parts are not in the client's entity lookup — the dragon hands them ids derived from its
     * own ({@code EnderDragon.recreateFromPacket} assigns {@code dragonId + i + 1}) and keeps them
     * to itself — so {@code getEntityById} alone reports a part id as gone.</p>
     */
    public static Entity findEntity(Minecraft mc, int id) {
        Entity direct = mc.level.getEntity(id);
        if (direct != null) return direct;
        // Iterated as Object on purpose: vanilla types this collection EnderDragonPart, NeoForge
        // widens it to its own PartEntity<?> so other mods can have multipart entities too. Both
        // are Entity, and this tree is compiled by both loaders, so the element type is the one
        // thing we cannot name here.
        for (Object raw : mc.level.dragonParts()) {
            if (raw instanceof Entity part && part.getId() == id) return part;
        }
        return null;
    }

    /** The whole entity behind an id: a dragon part resolves to the dragon that owns it. */
    private static Entity requireTarget(Minecraft mc, int id) {
        Entity found = findEntity(mc, id);
        if (found == null) throw ApiException.notFound("no entity with id " + id);
        return found instanceof EnderDragonPart part ? part.parentMob : found;
    }

    private static LocalPlayer requirePlayer(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            throw ApiException.notInWorld();
        }
        return mc.player;
    }

    /** The damageable hitboxes an entity presents: its parts if it has any, else itself. */
    private static List<Entity> hitboxesOf(Entity target) {
        if (target instanceof EnderDragon dragon) {
            List<Entity> parts = new ArrayList<>(dragon.getSubEntities().length);
            for (EnderDragonPart part : dragon.getSubEntities()) parts.add(part);
            return parts;
        }
        return List.of(target);
    }

    /**
     * Which hitbox to swing at: what the crosshair is already on, else the closest one in reach,
     * else the closest one at all (so we turn towards it and wait for it to come to us).
     */
    private static Entity chooseHitbox(Minecraft mc, LocalPlayer player, Entity target, double reach) {
        List<Entity> candidates = hitboxesOf(target);
        if (candidates.size() == 1) return candidates.get(0);

        if (mc.hitResult instanceof EntityHitResult hit && candidates.contains(hit.getEntity())) {
            return hit.getEntity();
        }
        Vec3 eye = eyePosition(player);
        Entity best = candidates.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : candidates) {
            double distance = squaredDistanceToBox(eye, candidate.getBoundingBox());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** Where to put an arrow: the nearest part of a multi-part target, else the middle of it. */
    private static Vec3 aimPointOf(LocalPlayer player, Entity target) {
        List<Entity> candidates = hitboxesOf(target);
        if (candidates.size() == 1) return target.getBoundingBox().getCenter();
        Vec3 eye = eyePosition(player);
        Vec3 best = target.getBoundingBox().getCenter();
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : candidates) {
            double distance = squaredDistanceToBox(eye, candidate.getBoundingBox());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.getBoundingBox().getCenter();
            }
        }
        return best;
    }

    /** Health of an entity, reading through a dragon part to the dragon. NaN if it has none. */
    private static float healthOf(Entity entity) {
        if (entity instanceof EnderDragonPart part) return part.parentMob.getHealth();
        return entity instanceof LivingEntity living ? living.getHealth() : Float.NaN;
    }

    // ---- aiming ---------------------------------------------------------------------

    /** Points the head straight at a point (no ballistic arc) — melee, and the no-solution case. */
    private static void lookAt(Minecraft mc, LocalPlayer player, Vec3 point, boolean sendNow) {
        Vec3 eye = eyePosition(player);
        double dy = point.y - eye.y;
        double horizontal = Math.hypot(point.x - eye.x, point.z - eye.z);
        point(mc, player, point, (float) -Math.toDegrees(Math.atan2(dy, horizontal)), sendNow);
    }

    /**
     * Sets yaw from the horizontal direction to {@code point} and pitch to {@code pitch}.
     *
     * @param sendNow push the rotation to the server immediately instead of letting the next
     *                movement packet carry it. Needed on the tick a bow is released: the release
     *                packet carries no rotation, so the server would fire along the previous tick's
     *                aim — which is exactly the error this whole class exists to remove
     */
    private static void point(Minecraft mc, LocalPlayer player, Vec3 point,
                              float pitch, boolean sendNow) {
        Vec3 eye = eyePosition(player);
        float yaw = Mth.wrapDegrees(
                (float) (Math.toDegrees(Math.atan2(point.z - eye.z, point.x - eye.x)) - 90.0));
        float clamped = Mth.clamp(pitch, -90.0f, 90.0f);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.setXRot(clamped);
        if (sendNow && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                    yaw, clamped, player.onGround(), player.horizontalCollision));
        }
    }

    /**
     * Squared distance from a point to the nearest face of a box — zero inside it.
     *
     * <p>Written out rather than calling {@code AABB.distanceToSqr(Vec3)}, which does not exist on
     * the older releases in the matrix. The bounds themselves are public on every one, and this is
     * the same arithmetic vanilla does.</p>
     */
    private static double squaredDistanceToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, point.x - box.maxX), 0.0);
        double dy = Math.max(Math.max(box.minY - point.y, point.y - box.maxY), 0.0);
        double dz = Math.max(Math.max(box.minZ - point.z, point.z - box.maxZ), 0.0);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Eye position, the long way round.
     *
     * <p>{@code getEyePosition()} does not exist before 1.17 and {@code getX()}/{@code getEyeY()}
     * are absent further back still, but {@code position()} and {@code getEyeHeight()} are on
     * {@code Entity} across the whole matrix — and their sum is what {@code getEyePosition()}
     * returns anyway. One expression, every release.</p>
     */
    private static Vec3 eyePosition(Entity entity) {
        return entity.position().add(0.0, entity.getEyeHeight(), 0.0);
    }

    /** One-way network delay in ticks, from the tab list's ping. Zero when it isn't known yet. */
    private static int latencyTicks(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return 0;
        var entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (entry == null) return 0;
        return Mth.clamp(entry.getLatency() / 2 / 50, 0, MAX_LATENCY_LEAD_TICKS);
    }
}
