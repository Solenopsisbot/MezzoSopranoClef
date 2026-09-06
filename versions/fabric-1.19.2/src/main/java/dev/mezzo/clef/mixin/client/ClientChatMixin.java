package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.event.ChatSink;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sources the {@code chat} event on releases with no {@code ClientReceiveMessageEvents}.
 *
 * <p>Fabric API's client message events arrived with 1.19.3; on 1.19.2 and older the only way to
 * observe incoming chat is to read the packets. Both handlers below begin with vanilla's
 * {@code PacketUtils.ensureRunningOnSameThread}, which throws on the netty thread to reschedule
 * the call onto the client thread — so injecting at TAIL (rather than HEAD) is what makes this
 * fire exactly once, on the right thread. Injecting at HEAD would fire twice per message.
 *
 * <p>The two shapes map onto the same wire event the newer targets emit: {@code kind=game} for
 * system messages (server broadcasts, {@code /say}, join notices) and {@code kind=chat} for
 * player messages, with the sender's display name attached.
 */
@Mixin(ClientPacketListener.class)
public class ClientChatMixin {

    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void clef$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        ChatSink.emit(packet.content().getString(), null, "game", packet.overlay());
    }

    @Inject(method = "handlePlayerChat", at = @At("TAIL"))
    private void clef$onPlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
        // serverContent() is the content the client actually displays: the server's unsigned
        // override when it decorated the message, otherwise the signed text.
        String text = packet.message().serverContent().getString();
        // BoundNetwork carries the resolved sender display name, so we don't need a registry
        // lookup or a tab-list cross-reference to name the speaker.
        String sender = packet.chatType().name().getString();
        ChatSink.emit(text, sender, "chat", false);
    }
}
