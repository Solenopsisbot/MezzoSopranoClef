package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.event.ChatSink;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.protocol.game.ClientboundChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sources the {@code chat} event on releases with no {@code ClientReceiveMessageEvents}.
 *
 * <p>1.15.2's chat packet carries only a message and a {@link ChatType} — the sender UUID is a 1.16
 * addition, and the signed-message split is 1.19. So {@code sender} is left null here; the speaker's
 * name is still visible to callers because the server sends the message already decorated
 * (<code>&lt;Name&gt; text</code>), which is what {@code text} contains.
 *
 * <p>Injecting at TAIL (not HEAD) matters: vanilla's handler starts with
 * {@code PacketUtils.ensureRunningOnSameThread}, which throws on the netty thread to reschedule onto
 * the client thread, so only TAIL runs exactly once and on the right thread.
 */
@Mixin(ClientPacketListener.class)
public class ClientChatMixin {

    @Inject(method = "handleChat", at = @At("TAIL"))
    private void clef$onChat(ClientboundChatPacket packet, CallbackInfo ci) {
        ChatType type = packet.getType();
        boolean player = type == ChatType.CHAT;
        ChatSink.emit(packet.getMessage().getString(), null,
                player ? "chat" : "game", type == ChatType.GAME_INFO);
    }
}
