package dev.luizloyola.autarkia.mixin;

import net.minecraft.world.entity.LivingEntity;
//? if >=26.2 {
/*import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
*///?} else {
import net.minecraft.world.entity.monster.Slime;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A slime's or magma cube's contact damage, for a Person's touch pass. Connector-safe. */
//? if >=26.2 {
/*@Mixin(AbstractCubeMob.class)
*///?} else {
@Mixin(Slime.class)
//?}
public interface CubeMobTouchInvoker {

    @Invoker("dealDamage")
    void autarkia$dealDamage(LivingEntity target);

    //? if >=26.3 {
    /*@Invoker("canDealDamage")
    *///?} else {
    @Invoker("isDealsDamage")
    //?}
    boolean autarkia$dealsDamage();
}
