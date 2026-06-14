package dev.mezzo.clef.mixin.client;

import dev.mezzo.clef.headless.HeadlessController;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.client.sound.TickableSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Short-circuits the sound engine when audio is muted (the headless default). The bot otherwise
 * sets master volume to 0 — silent, but the {@code SoundSystem} still spins up an OpenAL source /
 * streaming buffer for each "played" sound (mob ambients, footsteps, music cues) and reaps them on
 * its per-tick pump. Blocking that is a <b>footprint-hygiene</b> measure, not a CPU optimization:
 * an e2e A/B (48 mobs, ~42 sound-plays/s suppressed) showed the CPU difference sits below the
 * run-to-run noise floor. What it does buy a long-lived fleet is fewer OpenAL source/handle churns
 * and per-play allocations over weeks of uptime. Kept for that reason; expect no measurable MSPT win.
 *
 * <p>We block all three start paths and skip the per-tick pump:
 * <ul>
 *   <li>{@code play(SoundInstance)} returns {@link SoundSystem.PlayResult#STARTED_SILENTLY} — the
 *       honest "accepted but inaudible" result, so callers like {@code MusicTracker} treat the cue
 *       as started and don't retry every tick.</li>
 *   <li>{@code play(SoundInstance,int)} and {@code playNextTick(...)} are cancelled.</li>
 *   <li>{@code tick(boolean)} is cancelled. Safe from leaks because nothing is ever started, so
 *       there's nothing for the tick to have to reap.</li>
 * </ul>
 *
 * <p>Gated on {@link HeadlessController#isMuteAudio()} so a non-muted run is untouched; flip off at
 * runtime with the {@code optimize} control command or {@code -Dmezzoclef.disable.sound=false}.
 */
@Mixin(SoundManager.class)
public class SoundDisableMixin {

    @Inject(method = "tick(Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void clef$skipSoundTick(boolean paused, CallbackInfo ci) {
        HeadlessController hc = HeadlessController.get();
        if (hc.isMuteAudio() && hc.isDisableSound()) ci.cancel();
    }

    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void clef$skipPlay(SoundInstance sound, CallbackInfoReturnable<SoundSystem.PlayResult> cir) {
        HeadlessController hc = HeadlessController.get();
        if (hc.isMuteAudio() && hc.isDisableSound()) {
            hc.noteSoundSuppressed();
            cir.setReturnValue(SoundSystem.PlayResult.STARTED_SILENTLY);
        }
    }

    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;I)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void clef$skipPlayDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        HeadlessController hc = HeadlessController.get();
        if (hc.isMuteAudio() && hc.isDisableSound()) {
            hc.noteSoundSuppressed();
            ci.cancel();
        }
    }

    @Inject(method = "playNextTick(Lnet/minecraft/client/sound/TickableSoundInstance;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void clef$skipPlayNextTick(TickableSoundInstance sound, CallbackInfo ci) {
        HeadlessController hc = HeadlessController.get();
        if (hc.isMuteAudio() && hc.isDisableSound()) {
            hc.noteSoundSuppressed();
            ci.cancel();
        }
    }
}
