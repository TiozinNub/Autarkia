package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import java.util.List;

/**
 * The person's own words — Anima ships only what a wolf could say (hail, greet, end); asking a
 * name and making small talk are what turns an encounter into meeting somebody.
 *
 * <p>Registered the moment this class loads, {@link SpeechActs}' own pattern — see
 * {@code AutarkiaMod} for the touch that forces it.
 */
public final class PersonActs {

    public static final SpeechAct ASK_IDENTITY = SpeechActs.register(new SpeechAct(
            "ask_identity", "autarkia.speech.ask_identity", 2, true, true, false, false,
            List.of("inform_name", "deflect")));
    public static final SpeechAct INFORM_NAME = SpeechActs.register(new SpeechAct(
            "inform_name", "autarkia.speech.inform_name", 2, true, false, true, false, List.of()));
    /**
     * The declared topics are the FLAVOUR half only — weather, work, mood. What a body feels
     * ({@code need.*}) is sourced live from its own gauges by {@link Topics}, and a speaker with no
     * gauges to read (a player, off a menu) gets the flavours. Declaring the need topics here would
     * offer a player "I'm hungry" as a thing to say about somebody else's hunger.
     */
    public static final SpeechAct SMALL_TALK = SpeechActs.register(new SpeechAct(
            "small_talk", "autarkia.speech.small_talk", 2, true, false, false, false, List.of(),
            Topics.FLAVOURS));

    private PersonActs() {
    }
}
