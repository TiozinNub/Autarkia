package dev.luizloyola.autarkia.core.board;

import java.util.function.Predicate;

/**
 * Which blocks are the ground's plants — grass, flowers, leaf litter, saplings — by the id the probe
 * reads. Installed by the mod from vanilla tags, so a modpack's plants are cleared too; nothing is a
 * plant until it is.
 */
public final class Plants {

    private static volatile Predicate<String> plant = id -> false;

    private Plants() {
    }

    public static void install(Predicate<String> plants) {
        plant = plants;
    }

    public static boolean is(String id) {
        return !id.isEmpty() && plant.test(id);
    }
}
