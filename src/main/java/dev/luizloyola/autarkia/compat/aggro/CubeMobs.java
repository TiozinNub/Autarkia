package dev.luizloyola.autarkia.compat.aggro;

import dev.luizloyola.autarkia.mixin.CubeMobTouchInvoker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
//? if >=26.2 {
/*import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
*///?} else {
import net.minecraft.world.entity.monster.Slime;
//?}

/**
 * A slime or magma cube hurts a Person it touches, as it hurts a player. Vanilla's contact damage
 * runs from {@code Player.aiStep}: everything within its box grown by one block across and half a
 * block up gets a {@code playerTouch}. A Person runs the same pass through here; the cube's own gate
 * — not tiny, not a sulfur cube — still decides.
 */
public final class CubeMobs {

    private CubeMobs() {
    }

    /** Once per tick, from the body's own tick. */
    public static void touch(LivingEntity body) {
        if (!body.isAlive() || body.isSpectator()) {
            return;
        }
        for (Entity entity : body.level().getEntities(body, body.getBoundingBox().inflate(1.0, 0.5, 1.0))) {
            //? if >=26.2 {
            /*if (entity instanceof AbstractCubeMob cube) {
            *///?} else {
            if (entity instanceof Slime cube) {
            //?}
                CubeMobTouchInvoker touched = (CubeMobTouchInvoker) cube;
                if (touched.autarkia$dealsDamage()) {
                    touched.autarkia$dealDamage(body);
                }
            }
        }
    }
}
