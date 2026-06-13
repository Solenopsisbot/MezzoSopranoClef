package dev.mezzo.clef.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import dev.mezzo.clef.MezzoClef;

/**
 * Discrete and continuous world interactions. The continuous one (block breaking) is driven by
 * {@link #tick(MinecraftClient)} each client tick; the discrete ones are called directly from the
 * command layer (already on the client thread via {@code onMain}).
 */
public final class ActionManager {

    /** Safety cap so a mine that never completes (bedrock, wrong face, unreachable) auto-stops. */
    private static final int MAX_MINING_TICKS = 200; // ~10s at 20 TPS

    private volatile BlockPos miningPos;
    private volatile Direction miningFace = Direction.UP;
    private volatile boolean mining;
    private boolean miningStarted;
    private int miningTicks;

    public void startMining(BlockPos pos, Direction face) {
        this.miningPos = pos;
        this.miningFace = face;
        this.mining = true;
        this.miningStarted = false;
        this.miningTicks = 0;
    }

    public void stopMining(MinecraftClient mc) {
        mining = false;
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
    }

    public boolean isMining() {
        return mining;
    }

    /** Drives multi-tick block breaking in survival; harmless if nothing is being mined. */
    public void tick(MinecraftClient mc) {
        if (!mining || mc.interactionManager == null || mc.player == null || mc.world == null) return;
        if (mc.world.getBlockState(miningPos).isAir()) { // broken (or already gone)
            mining = false;
            return;
        }
        if (++miningTicks > MAX_MINING_TICKS) {
            MezzoClef.LOG.warn("Mining {} timed out after {} ticks — giving up", miningPos, MAX_MINING_TICKS);
            stopMining(mc);
            return;
        }
        if (!miningStarted) {
            mc.interactionManager.attackBlock(miningPos, miningFace);
            miningStarted = true;
        } else {
            mc.interactionManager.updateBlockBreakingProgress(miningPos, miningFace);
        }
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    public boolean breakInstant(MinecraftClient mc, BlockPos pos) {
        return mc.interactionManager != null && mc.interactionManager.breakBlock(pos);
    }

    public void attackEntity(MinecraftClient mc, Entity target) {
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    public void useItem(MinecraftClient mc, Hand hand) {
        mc.interactionManager.interactItem(mc.player, hand);
        mc.player.swingHand(hand);
    }

    /** Right-click (place/activate) against the given block face. */
    public void interactBlock(MinecraftClient mc, BlockPos pos, Direction face) {
        Vec3d v = face.getVector() == null ? Vec3d.ofCenter(pos) : Vec3d.ofCenter(pos)
                .add(face.getVector().getX() * 0.5, face.getVector().getY() * 0.5, face.getVector().getZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(v, face, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}
