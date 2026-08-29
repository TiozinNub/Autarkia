package dev.luizloyola.autarkia.core.person.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @DisplayName("every act's lang key is declared, not derived")
    void langKeysDeclared() {
        assertEquals("autarkia.speech.ask_identity", PersonActs.ASK_IDENTITY.langKey());
        assertEquals("autarkia.speech.inform_name", PersonActs.INFORM_NAME.langKey());
        assertEquals("autarkia.speech.small_talk", PersonActs.SMALL_TALK.langKey());
    }
}
