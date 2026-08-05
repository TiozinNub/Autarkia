package dev.luizloyola.autarkia.mod.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.Gender;
import dev.luizloyola.autarkia.core.person.Look;
import dev.luizloyola.autarkia.core.person.ModelType;
import org.junit.jupiter.api.Test;

/**
 * A world written before this slice holds appearances as {@code {gender, skin, model}} compounds.
 * If the codec that replaced them cannot read one the failure is <b>not</b> an error: vanilla
 * parses saved data with {@code resultOrPartial}, so the row is dropped and the world loads a
 * person short, quietly. Hence the real codec, and hence {@link AppearanceCodecs} being split out
 * of {@link PersonDirectory}, which names {@code SavedData} and cannot load headless.
 *
 * <p>{@link JsonOps} rather than NBT: a codec that reads a JSON object reads an NBT compound, so
 * no Minecraft bootstrap is needed.
 */
class AppearanceCodecsTest {

    private static final String ALEX = "minecraft:entity/player/slim/alex";

    /** An appearance exactly as schema 1 wrote it. */
    private static JsonObject legacyRow(String gender, String skin, String model) {
        JsonObject row = new JsonObject();
        row.add("gender", new JsonPrimitive(gender));
        row.add("skin", new JsonPrimitive(skin));
        if (model != null) {
            row.add("model", new JsonPrimitive(model));
        }
        return row;
    }

    private static Appearance decode(JsonElement json) {
        DataResult<com.mojang.datafixers.util.Pair<Appearance, JsonElement>> result =
                AppearanceCodecs.CODEC.decode(JsonOps.INSTANCE, json);
        assertTrue(result.result().isPresent(),
                () -> "a row that will not decode is a row that vanishes: " + result.error());
        return result.result().get().getFirst();
    }

    @Test
    void readsASchemaOneCompound() {
        assertEquals(new Appearance(Gender.FEMALE, ModelType.SLIM, new Look.Skin(ALEX)),
                decode(legacyRow("FEMALE", ALEX, "SLIM")));
    }

    /** {@code model} postdated the other two, so a row without it must still load. */
    @Test
    void readsASchemaOneCompoundFromBeforeThereWasAModel() {
        Appearance decoded = decode(legacyRow("MALE", ALEX, null));
        assertEquals(ModelType.WIDE, decoded.model());
        assertEquals(new Look.Skin(ALEX), decoded.look());
    }

    @Test
    void readsTheCurrentStringForm() {
        Appearance appearance = new Appearance(Gender.FEMALE, ModelType.SLIM, new Look.Skin(ALEX));
        assertEquals(appearance, decode(new JsonPrimitive(appearance.encode())));
    }

    /**
     * Whatever it was read from, an appearance is written back in the current form, so a world
     * upgrades itself on its next save — no migration pass to run or to forget.
     */
    @Test
    void alwaysWritesTheCurrentForm() {
        Appearance appearance = new Appearance(Gender.FEMALE, ModelType.SLIM, new Look.Skin(ALEX));
        JsonElement written = AppearanceCodecs.CODEC
                .encodeStart(JsonOps.INSTANCE, appearance).result().orElseThrow();
        assertEquals(new JsonPrimitive("gender=FEMALE;model=SLIM;skin=" + ALEX), written);
    }

    /** Read a schema-1 row, write it back, read it again — the round trip an upgrading world takes. */
    @Test
    void aSchemaOneRowSurvivesBeingRewritten() {
        Appearance once = decode(legacyRow("FEMALE", ALEX, "SLIM"));
        JsonElement rewritten = AppearanceCodecs.CODEC
                .encodeStart(JsonOps.INSTANCE, once).result().orElseThrow();
        assertEquals(once, decode(rewritten));
    }
}
