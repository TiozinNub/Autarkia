package dev.luizloyola.autarkia.core.person;

/**
 * What eating one of an item does to the body — vanilla {@code FoodProperties}' exact fields, as
 * pure core data. Instances come from {@code compat}, read off the real item at lookup time (the
 * brain's {@code FoodLookup} sense); core never hardcodes a food table, so vanilla and modded foods
 * alike arrive through the registry.
 *
 * <p>These are the numbers {@link Needs#eat(int, float)} takes, so it lives beside
 * {@link Needs}. Saturation is the PRECOMPUTED value {@code FoodProperties.saturation()} reports,
 * not the legacy modifier — see {@link Needs#saturationByModifier(int, float)} for that formula.
 */
public record FoodValue(int nutrition, float saturation, boolean canAlwaysEat) {
}
