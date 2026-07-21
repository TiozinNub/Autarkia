package dev.luizloyola.autarkia.core.person;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Given names for new persons, deterministic for a given {@link RandomGenerator} so assignment is
 * unit-testable. A placeholder starter set, kept separate from skin choice (the old prototype drew
 * names from its skin filenames). Surnames, families and biome/culture variation come later.
 */
public final class PersonNames {
    private PersonNames() {}

    static final List<String> GIVEN_NAMES = List.of(
            "Kai", "Noor", "Sunny", "Zuri", "Ari", "Efe", "Makena", "Ada", "Bo", "Cass",
            "Dara", "Enzo", "Faye", "Gil", "Hana", "Ivo", "Juno", "Lex", "Mira", "Nael",
            "Oona", "Pax", "Remy", "Sol", "Tam", "Uma", "Vale", "Wren", "Yara", "Zeph");

    public static String random(RandomGenerator random) {
        return GIVEN_NAMES.get(random.nextInt(GIVEN_NAMES.size()));
    }
}
