package dev.luizloyola.autarkia.core.bp;

/** The two ways a slot's domain collapses — the only two, and those are their names (Luiz). */
public enum Binding {
    /** One choice for the whole structure. */
    OPTION,
    /** One choice per cell. */
    MIX;

    public String word() {
        return this == OPTION ? "option" : "mix";
    }
}
