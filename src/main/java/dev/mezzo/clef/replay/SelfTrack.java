package dev.mezzo.clef.replay;

import com.mojang.datafixers.util.Pair;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.mixin.client.PacketDecoderAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Puts the bot's own body into the replay.
 *
 * <h2>Why this has to exist</h2>
 * A server never tells you about yourself. It sends no spawn packet for your own player, and no
 * movement packets either — your client already knows where it put you, and the only thing that
 * travels is your <i>outbound</i> position report. So an honest capture of the inbound wire, which
 * is what {@link ReplayTap} makes, contains a world with a hole in it exactly where the recorder
 * was standing. ReplayMod solves this by synthesizing the missing packets while it records; so do
 * we, from the client thread, once per tick.
 *
 * <p>Everything written here is a real clientbound packet, encoded with the <i>connection's own</i>
 * codec (see {@link PacketDecoderAccessor}) rather than a hand-rolled second implementation, so a
 * synthetic packet is indistinguishable from a real one by the time it reaches the file.</p>
 *
 * <h2>What gets written</h2>
 * <ul>
 *   <li><b>Spawn</b> — an add-entity, the non-default entity data, and the full equipment set. Sent
 *       once per world: a respawn or a dimension change makes the viewer drop every entity, so the
 *       body has to be re-announced or it silently vanishes for the rest of the replay.</li>
 *   <li><b>Movement</b> — a position sync whenever the bot actually moved, plus a head rotation
 *       when the head turned far enough to matter at the byte precision the packet has. Idle ticks
 *       cost nothing; a keyframe every second covers a viewer that joined the timeline late.</li>
 *   <li><b>Equipment and swings</b> — on change, so a fight looks like a fight rather than a statue
 *       gliding at things.</li>
 * </ul>
 *
 * <p>Client thread only, and all state is per-recording: {@link ReplayRecorder#sessionId()} changing
 * resets it, so a second connection starts with a fresh spawn rather than a stale diff.</p>
 */
public final class SelfTrack {

    /** Re-send position and entity data at least this often, even standing perfectly still. */
    private static final int KEYFRAME_TICKS = 20;
    /** Squared blocks. Below this the bot has not really moved and the packet is noise. */
    private static final double MOVE_EPSILON_SQ = 1.0e-6;
    /** Degrees. The wire rounds rotation to 1/256 of a turn, so anything finer cannot be seen. */
    private static final float ROT_EPSILON = 0.05f;

    private static long session;
    private static ClientLevel level;
    private static int entityId = -1;
    private static boolean broken;
    /** False until one synthetic packet has survived an encode/decode round trip this session. */
    private static boolean verified;

    private static double x, y, z;
    private static float yRot, xRot;
    private static byte headRot;
    private static boolean swinging;
    private static int ticksSinceKeyframe;
    private static final List<ItemStack> equipment = new ArrayList<>();

    /** Called from the client tick. Cheap no-op unless a recording is actually running. */
    public static void tick(Minecraft mc) {
        ReplayRecorder recorder = ReplayRecorder.get();
        long current = recorder.sessionId();
        if (current != session) {
            session = current;
            reset();
        }
        if (current == 0 || broken) return;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        ProtocolInfo<ClientGamePacketListener> protocol = gameProtocol(recorder.activePipeline());
        // Null while the connection is still in the configuration phase, which is exactly when
        // there is no world to put a body in anyway.
        if (protocol == null) return;

        if (mc.level != level || player.getId() != entityId) {
            spawn(recorder, protocol, mc.level, player);
            return;
        }
        move(recorder, protocol, player);
        equip(recorder, protocol, player);
        swing(recorder, protocol, player);
    }

    // ---- the three writers ----------------------------------------------------------

    private static void spawn(ReplayRecorder recorder, ProtocolInfo<ClientGamePacketListener> protocol,
                              ClientLevel world, LocalPlayer player) {
        emit(recorder, protocol, new ClientboundAddEntityPacket(
                player.getId(), player.getUUID(), player.getX(), player.getY(), player.getZ(),
                player.getXRot(), player.getYRot(), player.getType(), 0, Vec3.ZERO,
                player.getYHeadRot()));
        emitEntityData(recorder, protocol, player);
        emitEquipment(recorder, protocol, player, EquipmentSlot.VALUES);

        level = world;
        entityId = player.getId();
        remember(player);
        equipment.clear();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) equipment.add(player.getItemBySlot(slot).copy());
        swinging = player.swinging;
        ticksSinceKeyframe = 0;
    }

    private static void move(ReplayRecorder recorder, ProtocolInfo<ClientGamePacketListener> protocol,
                             LocalPlayer player) {
        boolean moved = player.distanceToSqr(x, y, z) > MOVE_EPSILON_SQ
                || Math.abs(player.getYRot() - yRot) > ROT_EPSILON
                || Math.abs(player.getXRot() - xRot) > ROT_EPSILON;
        // Counted unconditionally, and reset only when the keyframe fires. Folding it into the
        // `moved ||` short-circuit meant a bot that never stopped moving — pathing, or strafing in
        // a fight — never evaluated it and so never re-sent its entity data at all, which is the
        // only thing here that carries sneaking, sprinting, swimming, fire and invisibility.
        boolean keyframe = ++ticksSinceKeyframe >= KEYFRAME_TICKS;
        if (moved || keyframe) {
            emit(recorder, protocol, ClientboundEntityPositionSyncPacket.of(player));
            remember(player);
        }
        if (keyframe) {
            // Re-reading the non-default values is side-effect free — unlike packDirty(), which
            // consumes the dirty flags the client is entitled to keep.
            emitEntityData(recorder, protocol, player);
            ticksSinceKeyframe = 0;
        }

        byte head = (byte) Math.floor(player.getYHeadRot() * 256.0f / 360.0f);
        if (head != headRot) {
            emit(recorder, protocol, new ClientboundRotateHeadPacket(player, head));
            headRot = head;
        }
    }

    private static void equip(ReplayRecorder recorder, ProtocolInfo<ClientGamePacketListener> protocol,
                              LocalPlayer player) {
        List<EquipmentSlot> changed = null;
        for (int i = 0; i < EquipmentSlot.VALUES.size(); i++) {
            EquipmentSlot slot = EquipmentSlot.VALUES.get(i);
            ItemStack now = player.getItemBySlot(slot);
            if (ItemStack.matches(equipment.get(i), now)) continue;
            equipment.set(i, now.copy());
            if (changed == null) changed = new ArrayList<>(2);
            changed.add(slot);
        }
        if (changed != null) emitEquipment(recorder, protocol, player, changed);
    }

    private static void swing(ReplayRecorder recorder, ProtocolInfo<ClientGamePacketListener> protocol,
                              LocalPlayer player) {
        // Rising edge only: `swinging` stays true for the length of the animation, and the viewer
        // wants the start of each swing, not one packet per tick of it.
        if (player.swinging && !swinging) {
            emit(recorder, protocol, new ClientboundAnimatePacket(player,
                    player.swingingArm == InteractionHand.OFF_HAND
                            ? ClientboundAnimatePacket.SWING_OFF_HAND
                            : ClientboundAnimatePacket.SWING_MAIN_HAND));
        }
        swinging = player.swinging;
    }

    private static void emitEntityData(ReplayRecorder recorder,
                                       ProtocolInfo<ClientGamePacketListener> protocol,
                                       LocalPlayer player) {
        List<SynchedEntityData.DataValue<?>> values = player.getEntityData().getNonDefaultValues();
        if (values == null || values.isEmpty()) return;
        emit(recorder, protocol, new ClientboundSetEntityDataPacket(player.getId(), values));
    }

    private static void emitEquipment(ReplayRecorder recorder,
                                      ProtocolInfo<ClientGamePacketListener> protocol,
                                      LocalPlayer player, List<EquipmentSlot> slots) {
        List<Pair<EquipmentSlot, ItemStack>> payload = new ArrayList<>(slots.size());
        for (EquipmentSlot slot : slots) payload.add(Pair.of(slot, player.getItemBySlot(slot)));
        if (payload.isEmpty()) return;
        emit(recorder, protocol, new ClientboundSetEquipmentPacket(player.getId(), payload));
    }

    // ---- encoding -------------------------------------------------------------------

    /**
     * Serializes one packet with the connection's own codec and hands the bytes to the recorder.
     *
     * <p>A failure here is not survivable per-packet: a half-encoded packet in the file makes every
     * byte after it unreadable, so the first error stops self-tracking for the whole recording. The
     * captured stream is unaffected and the replay still opens — it just goes back to having no
     * body in it, which is the state this class exists to improve on and a strictly better outcome
     * than a corrupt file.</p>
     */
    private static void emit(ReplayRecorder recorder, ProtocolInfo<ClientGamePacketListener> protocol,
                             Packet<? super ClientGamePacketListener> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            protocol.codec().encode(buf, packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            if (!verified) verify(recorder, protocol, packet, bytes);
            if (!broken) recorder.inject(bytes);
        } catch (Throwable t) {
            fail(recorder, packet, t);
        } finally {
            buf.release();
        }
    }

    /**
     * Once per recording, decode the first synthetic packet straight back with the same codec and
     * check it round-trips to the type we asked for.
     *
     * <p>Encoding cannot be assumed to work just because it did not throw: a codec bound to the
     * wrong registry context, or a buffer the codec silently under-fills, produces bytes that are
     * accepted here and rejected — or worse, misread — by whatever opens the replay. This is the
     * one cheap moment where the answer is knowable, and it is what {@code replay.status} reports
     * as {@code selfTrack: verified} rather than leaving the claim to a human with ReplayMod open.</p>
     */
    private static void verify(ReplayRecorder recorder, ProtocolInfo<ClientGamePacketListener> protocol,
                               Packet<? super ClientGamePacketListener> packet, byte[] bytes) {
        ByteBuf back = Unpooled.wrappedBuffer(bytes);
        try {
            Packet<?> decoded = protocol.codec().decode(back);
            if (decoded == null || !decoded.getClass().equals(packet.getClass())) {
                fail(recorder, packet, new IllegalStateException(
                        "round-tripped to " + (decoded == null ? "null" : decoded.getClass().getName())));
                return;
            }
            if (back.isReadable()) {
                fail(recorder, packet, new IllegalStateException(
                        back.readableBytes() + " bytes left over after decoding"));
                return;
            }
            verified = true;
            recorder.reportSelfTrack("verified", null);
            MezzoClef.LOG.info("Replay self-tracking verified ({} round-trips cleanly)",
                    packet.getClass().getSimpleName());
        } finally {
            back.release();
        }
    }

    private static void fail(ReplayRecorder recorder, Packet<?> packet, Throwable cause) {
        broken = true;
        recorder.reportSelfTrack("failed",
                packet.getClass().getSimpleName() + ": " + cause);
        MezzoClef.LOG.error("Replay self-tracking stopped: could not encode {}",
                packet.getClass().getSimpleName(), cause);
    }

    /**
     * The clientbound play codec this connection is using, or null if there isn't one yet.
     *
     * <p>Read out of the {@code decoder} handler rather than rebuilt, so it carries the exact
     * registry context the server sent us — the thing that makes item stacks and entity data
     * encode to the same bytes the server would have used.</p>
     */
    @SuppressWarnings("unchecked")
    private static ProtocolInfo<ClientGamePacketListener> gameProtocol(ChannelPipeline pipeline) {
        if (pipeline == null) return null;
        ChannelHandler handler = pipeline.get("decoder");
        if (!(handler instanceof PacketDecoder<?>)) return null;
        ProtocolInfo<?> info = ((PacketDecoderAccessor) handler).clef$protocolInfo();
        if (info == null || info.id() != ConnectionProtocol.PLAY) return null;
        return (ProtocolInfo<ClientGamePacketListener>) info;
    }

    private static void remember(LocalPlayer player) {
        x = player.getX();
        y = player.getY();
        z = player.getZ();
        yRot = player.getYRot();
        xRot = player.getXRot();
    }

    private static void reset() {
        level = null;
        entityId = -1;
        broken = false;
        verified = false;
        ticksSinceKeyframe = 0;
        swinging = false;
        headRot = 0;
        equipment.clear();
    }

    private SelfTrack() {}
}
