package dev.luizloyola.autarkia.mod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.luizloyola.autarkia.mod.client.entity.ClientPerson;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;

/**
 * Renders a {@link Person} with the vanilla {@link PlayerModel}, using the entity's chosen
 * skin file. Typed to {@link Person} so it registers against {@code EntityType<Person>}; at
 * runtime client levels only ever hold {@link ClientPerson} (guaranteed by the factory swap),
 * which is where the skin is resolved. The {@link #getTextureLocation} override is what makes
 * the Person wear any texture we point it at.
 */
@Environment(EnvType.CLIENT)
public class PersonRenderer extends LivingEntityRenderer<Person, AvatarRenderState, PlayerModel> {
    private final PlayerModel wideModel;
    private final PlayerModel slimModel;

    public PersonRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wideModel = this.model;
        this.slimModel = new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new PlayerItemInHandLayer<>(this));
        // Draw worn armor: the mirror equips it onto the entity's real equipment slots,
        // extractRenderState pulls them into head/chest/legs/feetEquipment, and this layer renders
        // them off the vanilla player-armor models — vanilla items bring their own textures.
        addLayer(new HumanoidArmorLayer<>(
                this,
                ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, ctx.getModelSet(),
                        (ModelPart part) -> new HumanoidModel<AvatarRenderState>(part)),
                ctx.getEquipmentRenderer()));
    }

    @Override
    public void submit(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        // Per-Person wide/slim dispatch. The skin carries its own model type; submit() renders
        // synchronously with this.model, so selecting it here is safe under the batched pipeline
        // (unlike extractRenderState, whose writes would be overwritten before rendering).
        this.model = state.skin.model() == PlayerModelType.SLIM ? this.slimModel : this.wideModel;
        super.submit(state, poseStack, collector, camera);
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
