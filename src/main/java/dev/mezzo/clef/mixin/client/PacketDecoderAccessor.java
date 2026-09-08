package dev.mezzo.clef.mixin.client;

import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the {@link ProtocolInfo} the connection's decoder is currently bound to.
 *
 * <p>Replay recording needs to write packets the server never sent — the bot's own spawn and
 * movement, which no server echoes back to the player they describe. Encoding those means having
 * the clientbound play codec, complete with the registry context this connection negotiated.
 * Building a second one by hand would work until the day it disagreed with the real one by a byte;
 * borrowing the decoder's is exact by construction, and it is one field.</p>
 *
 * <p>The instance we borrow from is the handler in the {@code decoder} slot — the one immediately
 * after the replay tap, so what we encode is in precisely the same shape as what we capture.</p>
 */
@Mixin(PacketDecoder.class)
public interface PacketDecoderAccessor {

    @Accessor("protocolInfo")
    ProtocolInfo<?> clef$protocolInfo();
}
