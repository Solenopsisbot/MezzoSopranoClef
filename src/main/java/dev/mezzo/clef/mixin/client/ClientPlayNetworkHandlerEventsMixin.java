package dev.mezzo.clef.mixin.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.api.Events;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerEventsMixin {

    @Inject(method = "onBlockUpdate", at = @At("HEAD"), require = 0)
    private void clef$onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        if (!Events.wants("blockUpdate")) return;
        clef$emitBlockUpdate(packet.getPos(), packet.getState());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("HEAD"), require = 0)
    private void clef$onChunkDeltaUpdate(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        if (!Events.wants("blockUpdate")) return;
        packet.visitUpdates(this::clef$emitBlockUpdate);
    }

    @Inject(method = "onItemPickupAnimation", at = @At("HEAD"), require = 0)
    private void clef$onItemPickup(ItemPickupAnimationS2CPacket packet, CallbackInfo ci) {
        if (!Events.wants("itemPickup")) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        // The packet fires for every player's pickups; only ours is interesting.
        if (mc.player == null || mc.world == null || packet.getCollectorEntityId() != mc.player.getId()) return;
        Entity item = mc.world.getEntityById(packet.getEntityId());
        JsonObject data = new JsonObject();
        data.addProperty("item", item instanceof net.minecraft.entity.ItemEntity e
                ? Registries.ITEM.getId(e.getStack().getItem()).toString() : "unknown");
        data.addProperty("count", packet.getStackAmount());
        Events.emit("itemPickup", data);
    }

    @Inject(method = "onEntityDamage", at = @At("HEAD"), require = 0)
    private void clef$onEntityDamage(EntityDamageS2CPacket packet, CallbackInfo ci) {
        if (!Events.wants("entityHurt")) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        Entity victim = mc.world.getEntityById(packet.entityId());
        if (victim == null) return;
        double radius = MezzoClef.config().events.packetRadius;
        if (victim.squaredDistanceTo(mc.player) > radius * radius) return;
        JsonObject data = new JsonObject();
        data.addProperty("id", packet.entityId());
        data.addProperty("type", net.minecraft.entity.EntityType.getId(victim.getType()).toString());
        data.addProperty("self", victim == mc.player);
        data.addProperty("health", victim instanceof net.minecraft.entity.LivingEntity living
                ? living.getHealth() : Float.NaN);
        packet.sourceType().getKey().ifPresent(key -> data.addProperty("source", key.getValue().toString()));
        // sourceCauseId is 1-based on the wire (0 = "no attacker") — this is what self-defence needs.
        if (packet.sourceCauseId() > 0) {
            int attackerId = packet.sourceCauseId() - 1;
            data.addProperty("attacker", attackerId);
            Entity attacker = mc.world.getEntityById(attackerId);
            if (attacker != null) {
                data.addProperty("attackerType", net.minecraft.entity.EntityType.getId(attacker.getType()).toString());
            }
        }
        Events.emit("entityHurt", data);
    }

    @Inject(method = "onExplosion", at = @At("HEAD"), require = 0)
    private void clef$onExplosion(ExplosionS2CPacket packet, CallbackInfo ci) {
        if (!Events.wants("explosion")) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        Vec3d center = packet.center();
        double radius = MezzoClef.config().events.packetRadius;
        if (mc.player.squaredDistanceTo(center) > radius * radius) return;
        JsonObject data = new JsonObject();
        data.addProperty("x", center.x);
        data.addProperty("y", center.y);
        data.addProperty("z", center.z);
        data.addProperty("distance", Math.sqrt(mc.player.squaredDistanceTo(center)));
        // 1.21.8's explosion packet carries no blast power — it was reduced to presentation data
        // (particle + sound) when block/entity effects moved fully server-side. The knockback the
        // server applied to us is the only magnitude left, so report that instead of inventing one.
        packet.playerKnockback().ifPresent(knockback -> {
            JsonArray v = new JsonArray();
            v.add(knockback.x);
            v.add(knockback.y);
            v.add(knockback.z);
            data.add("knockback", v);
        });
        Events.emit("explosion", data);
    }

    /** Shared by the single-block and chunk-delta paths. Runs before the world applies the change. */
    private void clef$emitBlockUpdate(BlockPos pos, BlockState to) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;
        int radius = MezzoClef.config().events.blockUpdateRadius;
        if (mc.player.getBlockPos().getSquaredDistance(pos) > (double) radius * radius) return;
        BlockState from = world.getBlockState(pos);
        if (from == to) return;
        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("from", Registries.BLOCK.getId(from.getBlock()).toString());
        data.addProperty("to", Registries.BLOCK.getId(to.getBlock()).toString());
        Events.emit("blockUpdate", data);
    }
}
