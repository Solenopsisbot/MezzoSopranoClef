package dev.mezzo.clef.mixin.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.api.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns the handful of server packets the bot can't see any other way into control-plane events:
 * {@code blockUpdate}, {@code itemPickup}, {@code entityHurt} and {@code explosion}.
 *
 * <p>Everything else in the event stream is derived by polling in {@code EventEmitter}, which is
 * simpler and version-proof. These four aren't derivable: a block change is indistinguishable from
 * a chunk reload by the time it lands in the world, an item pickup leaves no trace but a stack
 * count, and damage to <i>other</i> entities never touches our player at all.</p>
 *
 * <h2>Cost</h2>
 * All four inject at HEAD and bail immediately unless somebody has subscribed, so an unsubscribed
 * bot pays one boolean read per packet. {@code blockUpdate} additionally injects at HEAD
 * specifically so it can read the <i>outgoing</i> block state before the packet overwrites it —
 * that's what makes the {@code from} field possible.
 */
// No `require = 0` on these injections. Every handler below exists on every release that
// carries this mixin (verified with scripts/mcjavap.sh across 1.20.1 .. 26.2), and `require = 0`
// means a target that stops matching is silently skipped — subscribe still succeeds and no event
// ever arrives. Letting mixin fail loudly is the whole point of defaultRequire.
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerEventsMixin {

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void clef$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (!Events.wants("blockUpdate")) return;
        clef$emitBlockUpdate(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void clef$onChunkDeltaUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (!Events.wants("blockUpdate")) return;
        packet.runUpdates(this::clef$emitBlockUpdate);
    }

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void clef$onItemPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        if (!Events.wants("itemPickup")) return;
        Minecraft mc = Minecraft.getInstance();
        // The packet fires for every player's pickups; only ours is interesting.
        if (mc.player == null || mc.level == null || packet.getPlayerId() != mc.player.getId()) return;
        Entity item = mc.level.getEntity(packet.getItemId());
        JsonObject data = new JsonObject();
        data.addProperty("item", item instanceof net.minecraft.world.entity.item.ItemEntity e
                ? BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString() : "unknown");
        data.addProperty("count", packet.getAmount());
        Events.emit("itemPickup", data);
    }

    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void clef$onEntityDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        if (!Events.wants("entityHurt")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Entity victim = mc.level.getEntity(packet.entityId());
        if (victim == null) return;
        double radius = MezzoClef.config().events.packetRadius;
        if (victim.distanceToSqr(mc.player) > radius * radius) return;
        JsonObject data = new JsonObject();
        data.addProperty("id", packet.entityId());
        data.addProperty("type", net.minecraft.world.entity.EntityType.getKey(victim.getType()).toString());
        data.addProperty("self", victim == mc.player);
        data.addProperty("health", victim instanceof net.minecraft.world.entity.LivingEntity living
                ? living.getHealth() : Float.NaN);
        packet.sourceType().unwrapKey().ifPresent(key -> data.addProperty("source", key.location().toString()));
        // sourceCauseId is 1-based on the wire (0 = "no attacker") — this is what self-defence needs.
        if (packet.sourceCauseId() > 0) {
            int attackerId = packet.sourceCauseId() - 1;
            data.addProperty("attacker", attackerId);
            Entity attacker = mc.level.getEntity(attackerId);
            if (attacker != null) {
                data.addProperty("attackerType", net.minecraft.world.entity.EntityType.getKey(attacker.getType()).toString());
            }
        }
        Events.emit("entityHurt", data);
    }

    @Inject(method = "handleExplosion", at = @At("HEAD"))
    private void clef$onExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        if (!Events.wants("explosion")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // The explosion packet still carries plain coordinates here; it is reduced to a Vec3
        // centre (and loses blast power) in 1.21.2.
        Vec3 center = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        double radius = MezzoClef.config().events.packetRadius;
        if (mc.player.distanceToSqr(center) > radius * radius) return;
        JsonObject data = new JsonObject();
        data.addProperty("x", center.x);
        data.addProperty("y", center.y);
        data.addProperty("z", center.z);
        data.addProperty("distance", Math.sqrt(mc.player.distanceToSqr(center)));
        // Knockback is three plain floats here, always present, and this era still carries the
        // blast power that 1.21.2 dropped (that release reduced the packet to presentation data),
        // so report both rather than only the knockback the newer targets are limited to.
        data.addProperty("power", packet.getPower());
        JsonArray knockback = new JsonArray();
        knockback.add(packet.getKnockbackX());
        knockback.add(packet.getKnockbackY());
        knockback.add(packet.getKnockbackZ());
        data.add("knockback", knockback);
        Events.emit("explosion", data);
    }

    /** Shared by the single-block and chunk-delta paths. Runs before the world applies the change. */
    private void clef$emitBlockUpdate(BlockPos pos, BlockState to) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        if (world == null || mc.player == null) return;
        int radius = MezzoClef.config().events.blockUpdateRadius;
        if (mc.player.blockPosition().distSqr(pos) > (double) radius * radius) return;
        BlockState from = world.getBlockState(pos);
        if (from == to) return;
        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("from", BuiltInRegistries.BLOCK.getKey(from.getBlock()).toString());
        data.addProperty("to", BuiltInRegistries.BLOCK.getKey(to.getBlock()).toString());
        Events.emit("blockUpdate", data);
    }
}
