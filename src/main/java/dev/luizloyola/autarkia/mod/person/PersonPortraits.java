package dev.luizloyola.autarkia.mod.person;

import dev.luizloyola.anima.compat.Chats;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.appearance.BakedIds;
import dev.luizloyola.anima.mod.social.Portraits;
import dev.luizloyola.autarkia.core.person.AppearanceComposer;
import dev.luizloyola.autarkia.core.person.ModelType;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * The face beside a Person's spoken line. Anima owns the chat choke point and draws nothing; what a
 * settler LOOKS like is ours, so this is the provider it asks.
 *
 * <h2>Why the live hash and not the static base</h2>
 * {@code Recipe} offers both, and {@code staticHash()} reads like the portrait's hash — its javadoc
 * calls the static base "the neutral texture a portrait uses". <b>But no texture is ever registered
 * under it.</b> {@code BakedTextures} keys everything it bakes by {@code Recipe#hash()} and hands
 * that hash, and only that hash, to {@link BakedIds}; the static base exists inside a bake, never as
 * a name a client could resolve. A portrait spelled from {@code staticHash()} would name nothing and
 * render as the missing texture.
 *
 * <p>The three-argument {@code compose} composes at {@link AppearanceComposer#RESTING_STATE} —
 * exactly the state a client composes at when the eyes are open — so the id names the texture every
 * client rendering that settler has already baked. Blinking swaps to a second texture without
 * dropping the first: an unheld bake is parked rather than freed, so the eyes-open glyph stays
 * registered for as long as the settler is on screen.
 *
 * <h2>Known risk</h2>
 * The glyph resolves only on a client that has baked this recipe — true of anyone inside chat range,
 * but not guaranteed. If it renders as garbage rather than degrading, delete the
 * {@code PersonPortraits.install()} line in {@code AutarkiaMod}: the choke point renders text-only
 * without a provider, so that one line is the whole disconnect.
 */
public final class PersonPortraits {
    private PersonPortraits() {}

    /** Teaches Anima what a Person's head looks like. Call during mod initialization. */
    public static void install() {
        Portraits.provide(PersonPortraits::portraitOf);
    }

    /**
     * Composed per spoken line rather than cached: Anima asks once per line for the whole audience,
     * not once per listener, and a recipe is a handful of map lookups.
     */
    private static Optional<Component> portraitOf(MinecraftServer server, AgentId id) {
        return PersonDirectory.get(server).find(id).map(identity -> {
            var recipe = AppearanceComposer.compose(identity.appearance(),
                    PersonAppearance.catalog(), PersonAppearance::has);
            return Chats.head(BakedIds.of(recipe.hash()),
                    identity.appearance().model() == ModelType.SLIM);
        });
    }
}
