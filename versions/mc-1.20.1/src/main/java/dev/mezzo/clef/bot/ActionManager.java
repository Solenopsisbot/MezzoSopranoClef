package dev.mezzo.clef.bot;

import dev.mezzo.clef.MezzoClef;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Discrete and continuous world interactions. The continuous ones (block breaking, place
 * confirmation) are driven by {@link #tick(Minecraft)} each client tick; the discrete ones
 * are called directly from the command layer (already on the client thread via {@code onMain}).
 *
 * <h2>Why breaking and placing report back</h2>
 * Both are requests to a server that may refuse them, and neither is instant. A script that fires
 * {@code mine} and then polls {@code blockAt} is guessing at a round-trip it can't see. So mining
 * ends in exactly one {@link MineListener} callback — success or a reason — and placing returns a
 * future that resolves once the world actually changed (or provably didn't).
 */
public final class ActionManager {

    /** Safety cap so a mine that never completes (wrong tool, unreachable, server refusing) auto-stops. */
    private static final int MAX_MINING_TICKS = 200; // ~10s at 20 TPS

    /**
     * Outcome of one {@code startMining} request.
     *
     * @param broken true if the block is gone
     * @param reason {@code null} on success, else {@code cant_break} / {@code cancelled} /
     *               {@code replaced} / {@code not_in_world}
     * @param detail human-readable specifics (which block was unbreakable, why we gave up, ...)
     */
    public record MineResult(BlockPos pos, boolean broken, String reason, String detail, int ticks) {}

    /** Called exactly once per {@code startMining} request, on the client thread. */
    @FunctionalInterface
    public interface MineListener {
        void onMineDone(MineResult result);
    }

    /**
     * Outcome of a confirmed {@code place}.
     *
     * @param placed  true if a block state at or against the clicked position actually changed
     * @param at      where the change landed ({@code null} if nothing changed)
     * @param block   the block now at {@code at} ({@code null} if nothing changed)
     * @param ticks   client ticks spent waiting for the server to confirm
     */
    public record PlaceOutcome(boolean placed, BlockPos at, String block, int ticks) {}

    private volatile BlockPos miningPos;
    private volatile Direction miningFace = Direction.UP;
    private volatile boolean mining;
    private boolean miningStarted;
    private int miningTicks;
    private volatile MineListener mineListener;
    /** Completed by {@link #reportOnce} so a caller can block on the outcome instead of polling. */
    private CompletableFuture<MineResult> minePending;

    private final List<PendingPlace> pendingPlaces = new ArrayList<>();

    /** Installs the sink that receives {@code mineDone}. Replaces any previous listener. */
    public void setMineListener(MineListener listener) {
        this.mineListener = listener;
    }

    /**
     * Begins breaking {@code pos}. Any mine already in flight is reported as {@code replaced} first,
     * so every request gets exactly one completion.
     *
     * <p>Must be called on the client thread. The returned future completes exactly once — possibly
     * before this method returns, when the request can be answered without asking the server (the
     * block is already gone, or it's bedrock). Callers that don't want to wait can ignore it; the
     * {@link MineListener} fires either way.</p>
     */
    public CompletableFuture<MineResult> startMining(Minecraft mc, BlockPos pos, Direction face) {
        finish(false, "replaced", "superseded by a new mine request");
        CompletableFuture<MineResult> result = new CompletableFuture<>();
        minePending = result;
        this.miningPos = pos;
        this.miningTicks = 0;

        if (mc.level == null || mc.player == null) {
            reportOnce(pos, false, "not_in_world", "no world or player", 0);
            return result;
        }
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) {                       // already gone; report success immediately
            reportOnce(pos, true, null, "already air", 0);
            return result;
        }
        // Hardness < 0 is vanilla's "indestructible" marker (bedrock, barrier, end portal frame).
        // The server would simply ignore us forever, so refuse now rather than after 10 seconds.
        if (state.getDestroySpeed(mc.level, pos) < 0) {
            reportOnce(pos, false, "cant_break", "unbreakable block: "
                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), 0);
            return result;
        }

        this.miningFace = face;
        this.mining = true;
        this.miningStarted = false;
        return result;
    }

    public void stopMining(Minecraft mc) {
        boolean wasMining = mining;
        mining = false;
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        if (wasMining) finish(false, "cancelled", "stopMine");
    }

    public boolean isMining() {
        return mining;
    }

    /** The block currently being broken, or null. */
    public BlockPos miningPos() {
        return mining ? miningPos : null;
    }

    /** Drives multi-tick block breaking and pending place confirmations. */
    public void tick(Minecraft mc) {
        tickMining(mc);
        tickPlaces(mc);
    }

    private void tickMining(Minecraft mc) {
        if (!mining) return;
        if (mc.gameMode == null || mc.player == null || mc.level == null) {
            mining = false;
            finish(false, "not_in_world", "world or player went away mid-mine");
            return;
        }
        if (mc.level.getBlockState(miningPos).isAir()) { // broken (or already gone)
            mining = false;
            finish(true, null, null);
            return;
        }
        if (++miningTicks > MAX_MINING_TICKS) {
            MezzoClef.LOG.warn("Mining {} timed out after {} ticks — giving up", miningPos, MAX_MINING_TICKS);
            mining = false;
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            finish(false, "cant_break", "gave up after " + MAX_MINING_TICKS
                    + " ticks (wrong tool, out of reach, or the server refused)");
            return;
        }
        if (!miningStarted) {
            mc.gameMode.startDestroyBlock(miningPos, miningFace);
            miningStarted = true;
        } else {
            mc.gameMode.continueDestroyBlock(miningPos, miningFace);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /** Emits the completion for the in-flight mine, if there is one. */
    private void finish(boolean broken, String reason, String detail) {
        BlockPos pos = miningPos;
        if (pos == null) return;
        miningPos = null;
        reportOnce(pos, broken, reason, detail, miningTicks);
    }

    private void reportOnce(BlockPos pos, boolean broken, String reason, String detail, int ticks) {
        MineResult result = new MineResult(pos, broken, reason, detail, ticks);
        CompletableFuture<MineResult> waiter = minePending;
        minePending = null;
        if (waiter != null) waiter.complete(result);
        MineListener sink = mineListener;
        if (sink == null) return;
        try {
            sink.onMineDone(result);
        } catch (Throwable t) {
            MezzoClef.LOG.warn("Mine listener threw: {}", t.toString());
        }
    }

    public boolean breakInstant(Minecraft mc, BlockPos pos) {
        return mc.gameMode != null && mc.gameMode.destroyBlock(pos);
    }

    public void attackEntity(Minecraft mc, Entity target) {
        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    public void useItem(Minecraft mc, InteractionHand hand) {
        mc.gameMode.useItem(mc.player, hand);
        mc.player.swing(hand);
    }

    /** Right-click (place/activate) against the given block face. */
    public net.minecraft.world.InteractionResult interactBlock(Minecraft mc, BlockPos pos, Direction face) {
        Vec3 v = face.getNormal() == null ? Vec3.atCenterOf(pos) : Vec3.atCenterOf(pos)
                .add(face.getNormal().getX() * 0.5, face.getNormal().getY() * 0.5, face.getNormal().getZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(v, face, pos, false);
        net.minecraft.world.InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return result;
    }

    // ---- place confirmation ----------------------------------------------------------

    /**
     * Watches for the world to change after a right-click, since the change only exists once the
     * server says so. Two positions are watched: the face we clicked against (a block replaced in
     * place, e.g. a slab completing) and the neighbour the new block would occupy.
     *
     * @param timeoutTicks give up after this many client ticks and report {@code placed:false}
     */
    public CompletableFuture<PlaceOutcome> confirmPlace(Minecraft mc, BlockPos clicked,
                                                        Direction face, int timeoutTicks) {
        CompletableFuture<PlaceOutcome> future = new CompletableFuture<>();
        if (mc.level == null) {
            future.complete(new PlaceOutcome(false, null, null, 0));
            return future;
        }
        BlockPos neighbour = clicked.relative(face);
        pendingPlaces.add(new PendingPlace(clicked, mc.level.getBlockState(clicked),
                neighbour, mc.level.getBlockState(neighbour), Math.max(1, timeoutTicks), future));
        return future;
    }

    private void tickPlaces(Minecraft mc) {
        if (pendingPlaces.isEmpty()) return;
        pendingPlaces.removeIf(p -> p.poll(mc));
    }

    private static final class PendingPlace {
        private final BlockPos clicked;
        private final BlockState clickedBefore;
        private final BlockPos neighbour;
        private final BlockState neighbourBefore;
        private final int timeoutTicks;
        private final CompletableFuture<PlaceOutcome> future;
        private int ticks;

        PendingPlace(BlockPos clicked, BlockState clickedBefore, BlockPos neighbour,
                     BlockState neighbourBefore, int timeoutTicks, CompletableFuture<PlaceOutcome> future) {
            this.clicked = clicked;
            this.clickedBefore = clickedBefore;
            this.neighbour = neighbour;
            this.neighbourBefore = neighbourBefore;
            this.timeoutTicks = timeoutTicks;
            this.future = future;
        }

        /** @return true when this confirmation is finished and should be dropped. */
        boolean poll(Minecraft mc) {
            if (future.isDone()) return true;
            if (mc.level == null) {
                future.complete(new PlaceOutcome(false, null, null, ticks));
                return true;
            }
            BlockState now = mc.level.getBlockState(neighbour);
            if (now != neighbourBefore) {
                future.complete(new PlaceOutcome(true, neighbour,
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString(), ticks));
                return true;
            }
            BlockState clickedNow = mc.level.getBlockState(clicked);
            if (clickedNow != clickedBefore) {
                future.complete(new PlaceOutcome(true, clicked,
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(clickedNow.getBlock()).toString(), ticks));
                return true;
            }
            if (++ticks >= timeoutTicks) {
                future.complete(new PlaceOutcome(false, null, null, ticks));
                return true;
            }
            return false;
        }
    }
}
