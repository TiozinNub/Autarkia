package dev.luizloyola.autarkia.mod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.luizloyola.autarkia.mod.client.PersonContactsClient;
import dev.luizloyola.autarkia.mod.client.anim.NeaBridge;
import dev.luizloyola.autarkia.mod.client.entity.ClientPerson;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
import org.jspecify.annotations.Nullable;

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
        // Per-Person wide/slim dispatch, kept for correctness but measured not to run:
        // getRenderer(renderState) picks the submitting renderer by STATE type, so every
        // AvatarRenderState goes to vanilla's AvatarRenderer, which makes the same pick itself.
        // Verified in-game: a probe here never fired, one in extractRenderState did.
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
        state.rightArmPose = armPose(person, HumanoidArm.RIGHT);
        state.leftArmPose = armPose(person, HumanoidArm.LEFT);
        state.skin = ((ClientPerson) person).getSkin();
        state.showHat = true;
        state.showJacket = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        state.showCape = false;
        state.id = person.getId();
        // last, and only here. NEA records the entity on this state from inside the super call
        // above, so swapping in the shadow now is the whole integration. Not in submit():
        // dispatch is by STATE type and an AvatarRenderState always routes to vanilla's
        // AvatarRenderer, so this renderer's submit() never runs for a Person.
        if (NeaBridge.available() && person instanceof ClientPerson clientPerson) {
            AbstractClientPlayer shadow = clientPerson.shadow().synced();
            if (shadow != null) {
                NeaBridge.retarget(state, shadow);
            }
        }
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }

    /**
     * The nameplate gate: no introduction, no name over their head (decision: Luiz). An unmet Person
     * still renders completely — they have no label.
     *
     * <p>{@code super} keeps vanilla's own reasons to hide a plate, and {@code Person} answers the
     * custom-name half with a flat yes, so this check is the only one that matters.
     */
    @Override
    protected boolean shouldShowName(Person person, double distanceSq) {
        return PersonContactsClient.knows(person.getPersonId())
                && super.shouldShowName(person, distanceSq);
    }

    /**
     * What the plate says — this client's own contact book, the only place a client can learn a name
     * now that it left entity data. Never reached without {@link #shouldShowName} passing first, so
     * the fallback is unreachable belt-and-braces.
     */
    @Override
    protected Component getNameTag(Person person) {
        String known = PersonContactsClient.nameOf(person.getPersonId());
        return known == null ? super.getNameTag(person) : Component.literal(known);
    }

    /**
     * What an arm is DOING with what it holds — the half of the humanoid render state that
     * {@link HumanoidMobRenderer#extractHumanoidRenderState} leaves alone, because vanilla fills it
     * per-renderer. Both poses default to {@link HumanoidModel.ArmPose#EMPTY}, so without this a
     * Person eats, drinks and draws a bow with arms swinging idly at their sides.
     *
     * <p>Mirrors {@code AvatarRenderer.getArmPose} (26.1.2 bytecode) rather than calling it: it is
     * private static and runs in that renderer's own extraction, which a Person never reaches, since
     * extraction is dispatched by entity type. Submission does go through {@code AvatarRenderer},
     * which is why filling these two fields is enough. A two-handed main hand demotes the off hand
     * to a plain hold.
     */
    private static HumanoidModel.ArmPose armPose(Person person, HumanoidArm arm) {
        ItemStack offHand = person.getItemInHand(InteractionHand.OFF_HAND);
        HumanoidModel.ArmPose mainPose = armPose(
                person, person.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
        HumanoidModel.ArmPose offPose = armPose(person, offHand, InteractionHand.OFF_HAND);
        if (mainPose.isTwoHanded()) {
            offPose = offHand.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        return person.getMainArm() == arm ? mainPose : offPose;
    }

    /**
     * One hand's pose. Eating and drinking fall through to {@link HumanoidModel.ArmPose#ITEM} — that
     * Is vanilla's eat pose, there is no separate chew: the bite reads as the raised item plus the
     * food particles and eating sounds the item's {@code Consumable} emits every few ticks of the
     * use, which {@code PersonItemConsumer} already drives through vanilla's own item-use pipeline.
     */
    private static HumanoidModel.ArmPose armPose(Person person, ItemStack stack, InteractionHand hand) {
        if (stack.isEmpty()) return HumanoidModel.ArmPose.EMPTY;
        if (!person.swinging && stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        if (person.getUsedItemHand() == hand && person.getUseItemRemainingTicks() > 0) {
            ItemUseAnimation animation = stack.getUseAnimation();
            if (animation == ItemUseAnimation.BLOCK) return HumanoidModel.ArmPose.BLOCK;
            if (animation == ItemUseAnimation.BOW) return HumanoidModel.ArmPose.BOW_AND_ARROW;
            if (animation == ItemUseAnimation.TRIDENT) return HumanoidModel.ArmPose.THROW_TRIDENT;
            if (animation == ItemUseAnimation.CROSSBOW) return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            if (animation == ItemUseAnimation.SPYGLASS) return HumanoidModel.ArmPose.SPYGLASS;
            if (animation == ItemUseAnimation.TOOT_HORN) return HumanoidModel.ArmPose.TOOT_HORN;
            if (animation == ItemUseAnimation.BRUSH) return HumanoidModel.ArmPose.BRUSH;
            if (animation == ItemUseAnimation.SPEAR) return HumanoidModel.ArmPose.SPEAR;
        }
        SwingAnimation swing = stack.get(DataComponents.SWING_ANIMATION);
        if (swing != null && swing.type() == SwingAnimationType.STAB && person.swinging) {
            return HumanoidModel.ArmPose.SPEAR;
        }
        return stack.is(ItemTags.SPEARS) ? HumanoidModel.ArmPose.SPEAR : HumanoidModel.ArmPose.ITEM;
    }
}
