package dev.luizloyola.autarkia.core.person.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.social.speech.SpeechActs;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The person's own vocabulary, registered alongside Anima's — see {@code SpeechActsTest}. */
class PersonActsTest {

    @Test
    @DisplayName("the person vocabulary lands in Anima's open registry")
    void registered() {
        assertSame(PersonActs.ASK_IDENTITY, SpeechActs.byKey("ask_identity").orElseThrow());
        assertSame(PersonActs.INFORM_NAME, SpeechActs.byKey("inform_name").orElseThrow());
        assertSame(PersonActs.SMALL_TALK, SpeechActs.byKey("small_talk").orElseThrow());
    }

    @Test
    @DisplayName("ask_identity obliges an answer, and takes inform_name or deflect")
    void askIdentityFlags() {
        assertTrue(PersonActs.ASK_IDENTITY.obliges(), "a question left hanging is a snub");
        assertFalse(PersonActs.ASK_IDENTITY.introduces());
        assertFalse(PersonActs.ASK_IDENTITY.ends());
        assertEquals(List.of("inform_name", "deflect"), PersonActs.ASK_IDENTITY.responses());
    }

    @Test
    @DisplayName("inform_name gives the speaker's name away and obliges nothing back")
    void informNameFlags() {
        assertTrue(PersonActs.INFORM_NAME.introduces());
        assertFalse(PersonActs.INFORM_NAME.obliges());
        assertFalse(PersonActs.INFORM_NAME.ends());
    }

    @Test
    @DisplayName("small_talk is a two-line act nobody is obliged to answer")
    void smallTalkFlags() {
        assertEquals(2, PersonActs.SMALL_TALK.variants());
        assertFalse(PersonActs.SMALL_TALK.obliges());
        assertFalse(PersonActs.SMALL_TALK.introduces());
        assertFalse(PersonActs.SMALL_TALK.ends());
    }

    /**
     * The only act of ours with a topic, and it declares the FLAVOUR half alone — what a body
     * feels is read off its own gauges at the moment of speaking, and a speaker choosing from a
     * menu has none to read. {@link Topics} owns the list; this pins that the act got it.
     */
    @Test
    @DisplayName("small_talk declares the flavour topics, and nothing else does")
    void smallTalkTopics() {
        assertEquals(Topics.FLAVOURS, PersonActs.SMALL_TALK.topics());
        assertTrue(PersonActs.ASK_IDENTITY.topics().isEmpty());
        assertTrue(PersonActs.INFORM_NAME.topics().isEmpty());
    }

    /**
     * A menu renders each of these as a button, labelled {@code <langKey>.button} — Anima's
     * {@code SpeechActsTest} holds its own acts to the same rule. A missing label reaches a
     * player's chat as a raw lang key, and nothing at runtime would say so.
     */
    @Test
    @DisplayName("every person act has a button label in en_us")
    void buttonLabels() {
        String lang = langSource();
        for (var act : List.of(PersonActs.ASK_IDENTITY, PersonActs.INFORM_NAME,
                PersonActs.SMALL_TALK)) {
            assertTrue(lang.contains("\"" + act.langKey() + ".button\""),
                    act.key() + " has no " + act.langKey() + ".button label");
        }
    }

    private static String langSource() {
        String path = "/assets/autarkia/lang/en_us.json";
        try (java.io.InputStream in = PersonActsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    @Test
    @DisplayName("every act's lang key is declared, not derived")
    void langKeysDeclared() {
        assertEquals("autarkia.speech.ask_identity", PersonActs.ASK_IDENTITY.langKey());
        assertEquals("autarkia.speech.inform_name", PersonActs.INFORM_NAME.langKey());
        assertEquals("autarkia.speech.small_talk", PersonActs.SMALL_TALK.langKey());
    }
}
