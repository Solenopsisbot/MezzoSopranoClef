package dev.mezzo.clef.forge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import org.spongepowered.asm.mixin.Mixins;

import java.util.List;
import java.util.Set;

/**
 * Registers this mod's mixin config on Forge.
 *
 * <p>Forge 1.20.1 has no {@code [[mixins]]} key in mods.toml — that is a NeoForge feature, and the
 * string does not appear anywhere in fmlloader 47.x. The classic route is the {@code MixinConfigs}
 * manifest attribute, which Forge reads from real jars but not from the exploded classes/resources
 * directories a Gradle dev run uses. ModDevGradle's legacy-Forge flavour handles SRG reobfuscation
 * but wires nothing for mixins.
 *
 * <p>So the config is added directly, from a ModLauncher transformation service — the same
 * mechanism MixinBootstrap itself uses, and early enough that it runs before any game class is
 * transformed.
 */
public final class ClefMixinBootstrap implements ITransformationService {

    @Override
    public String name() {
        return "mezzoclef";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        Mixins.addConfiguration("mezzoclef.mixins.json");
    }

    // Raw List<ITransformer> on the ModLauncher this release compiles against (10.0.9); it gains
    // wildcards in 11.x, but erasure makes the two binary-compatible.
    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        return List.of();
    }
}
