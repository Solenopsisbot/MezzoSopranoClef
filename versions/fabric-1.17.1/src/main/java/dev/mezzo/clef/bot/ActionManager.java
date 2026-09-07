package dev.mezzo.clef.bot;

import dev.mezzo.clef.MezzoClef;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Discrete and continuous world interactions. The continuous one (block breaking) is driven by
 * {@link #tick(Minecraft)} each client tick; the discrete ones are called directly from the
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

    public void stopMining(Minecraft mc) {
        mining = false;
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    public boolean isMining() {
        return mining;
    }

    /** Drives multi-tick block breaking in survival; harmless if nothing is being mined. */
    public void tick(Minecraft mc) {
        if (!mining || mc.gameMode == null || mc.player == null || mc.level == null) return;
        if (mc.level.getBlockState(miningPos).isAir()) { // broken (or already gone)
            mining = false;
            return;
        }
        if (++miningTicks > MAX_MINING_TICKS) {
            MezzoClef.LOG.warn("Mining {} timed out after {} ticks — giving up", miningPos, MAX_MINING_TICKS);
            stopMining(mc);
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

    public boolean breakInstant(Minecraft mc, BlockPos pos) {
        return mc.gameMode != null && mc.gameMode.destroyBlock(pos);
    }

    public void attackEntity(Minecraft mc, Entity target) {
        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    public void useItem(Minecraft mc, InteractionHand hand) {
        mc.gameMode.useItem(mc.player, mc.level, hand);
        mc.player.swing(hand);
    }

    /** Right-click (place/activate) against the given block face. */
    public void interactBlock(Minecraft mc, BlockPos pos, Direction face) {
        Vec3 v = face.getNormal() == null ? Vec3.atCenterOf(pos) : Vec3.atCenterOf(pos)
                .add(face.getNormal().getX() * 0.5, face.getNormal().getY() * 0.5, face.getNormal().getZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(v, face, pos, false);
        mc.gameMode.useItemOn(mc.player, mc.level, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
