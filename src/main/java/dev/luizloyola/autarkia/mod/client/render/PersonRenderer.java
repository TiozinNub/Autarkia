package dev.luizloyola.autarkia.mod.client.render;

import dev.luizloyola.autarkia.mod.client.entity.ClientPerson;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

/**
 * Renders a {@link Person} with the vanilla {@link PlayerModel}, using the entity's chosen
 * skin file. Typed to {@link Person} so it registers against {@code EntityType<Person>}; at
 * runtime client levels only ever hold {@link ClientPerson} (guaranteed by the factory swap),
 * which is where the skin is resolved. The {@link #getTextureLocation} override is what makes
 * the Person wear any texture we point it at.
 */
@Environment(EnvType.CLIENT)
public class PersonRenderer extends LivingEntityRenderer<Person, AvatarRenderState, PlayerModel> {
    public PersonRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        addLayer(new PlayerItemInHandLayer<>(this));
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(Person person, AvatarRenderState state, float partialTick) {
        super.extractRenderState(person, state, partialTick);
        HumanoidMobRenderer.extractHumanoidRenderState(person, state, partialTick, this.itemModelResolver);
        state.skin = ((ClientPerson) person).getSkin();
        state.showHat = true;
        state.showJacket = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        state.showCape = false;
        state.id = person.getId();
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }
}
