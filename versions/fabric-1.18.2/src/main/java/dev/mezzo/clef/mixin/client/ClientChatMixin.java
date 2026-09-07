package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.event.ChatSink;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.protocol.game.ClientboundChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Sources the {@code chat} event on releases with no {@code ClientReceiveMessageEvents}.
 *
 * <p>1.18.2 predates the 1.19 chat-signing split, so there is a single {@code ClientboundChatPacket}
 * carrying a {@link ChatType} discriminator rather than the separate player/system packets 1.19.x
 * uses. That enum is what we map onto the shared wire event: {@code CHAT} becomes {@code kind=chat},
 * while {@code SYSTEM} and {@code GAME_INFO} both become {@code kind=game} — {@code GAME_INFO} being
 * the action-bar slot, which is what {@code overlay} means on the newer targets.
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
        String sender = player ? clef$senderName(packet.getSender()) : null;
        ChatSink.emit(packet.getMessage().getString(), sender,
                player ? "chat" : "game", type == ChatType.GAME_INFO);
    }

    /**
     * Names the speaker. The packet only carries a UUID here (1.19+ ships the display name inline),
     * so we resolve it against the tab list; an unknown or nil sender simply goes unnamed.
     */
    private String clef$senderName(UUID id) {
        if (id == null) return null;
        PlayerInfo info = ((ClientPacketListener) (Object) this).getPlayerInfo(id);
        return info != null ? info.getProfile().getName() : null;
    }
}
