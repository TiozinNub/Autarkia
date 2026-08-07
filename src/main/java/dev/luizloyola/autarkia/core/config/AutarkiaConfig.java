package dev.luizloyola.autarkia.core.config;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.config.ConfigStore;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.KnobSet;
import dev.luizloyola.autarkia.core.person.PersonSpecies;

/**
 * Autarkia's own live configuration — {@code config/autarkia.toml}, edited with
 * {@code /autarkia config}.
 *
 * <p>No tunables of its own yet: everything in the file describes a {@link PersonSpecies Person},
 * generated from that declaration by Anima.
 *
 * <p>The store is read through on every use rather than cached into fields. That is what makes
 * {@code /autarkia config reload} retune Persons already walking around.
 */
public final class AutarkiaConfig {

    /** Autarkia's knob set: today, the {@code person} species family. */
    public static final KnobSet SET = KnobSet.of("autarkia", "Autarkia", PersonSpecies.KNOBS.knobs());

    private static final ConfigStore STORE = new ConfigStore(SET);

    /**
     * What a Person is like, live. Every organ of every Person reads through this one object, so a
     * reload reaches an agent mid-stride.
     */
    public static final AgentProfile PERSON = PersonSpecies.KNOBS.profile(STORE);

    private AutarkiaConfig() {
    }

    /** The store itself, for the file and command layers. */
    public static ConfigStore store() {
        return STORE;
    }

    /** The configuration in force right now. Never null. */
    public static ConfigValues get() {
        return STORE.get();
    }

    /** Swaps in a new configuration; every subsequent read sees it whole. */
    public static void install(ConfigValues config) {
        STORE.install(config);
    }

    /** Back to what {@link PersonSpecies} declared. */
    public static void reset() {
        STORE.reset();
    }
}
