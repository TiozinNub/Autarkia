package dev.luizloyola.autarkia.core.person;

/**
 * The player body/arm model a person renders with — vanilla <em>wide</em> (Steve, 4px arms) vs
 * <em>slim</em> (Alex, 3px arms). It is a property of the skin's authored geometry: a skin drawn
 * for slim arms must render slim, and vice versa. Part of a person's external {@link Appearance}.
 */
public enum ModelType {
    WIDE,
    SLIM
}
