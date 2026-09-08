package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.replay.ReplayRecorder;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs the replay tap on every connection the client opens.
 *
 * <p>{@code channelActive} is the right moment for two reasons. It is late enough that the whole
 * serialization pipeline exists — including ViaFabricPlus's translation handlers, which are added
 * while the channel is being initialized — so inserting before {@code decoder} here puts the tap
 * downstream of the translation and captures this client's native packets rather than the old
 * server's. And it is early enough to be well ahead of login success, which is where a recording
 * actually begins.</p>
 *
 * <p>The recorder decides whether to install anything at all; when replay capture is disarmed this
 * costs one volatile read per connection.</p>
 */
@Mixin(Connection.class)
public abstract class ConnectionReplayMixin {

    @Inject(method = "channelActive", at = @At("TAIL"))
    private void clef$installReplayTap(ChannelHandlerContext ctx, CallbackInfo ci) {
        ReplayRecorder.get().install(ctx.pipeline());
    }
}
