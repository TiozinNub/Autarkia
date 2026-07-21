package dev.luizloyola.autarkia.core.inv;

/**
 * The four armor slots, head to foot. Ordinals index the {@link Inventory} armor region (HEAD
 * first), so armor is addressed by type rather than a magic slot number. Mirrors the vanilla
 * {@code EquipmentSlot} armor set; {@code compat} maps between the two.
 */
public enum ArmorType {
    HEAD,
    CHEST,
    LEGS,
    FEET
}
