package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Picker;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The part with the personality — a ten-rung ladder, first match wins, every rung gated on the
 * act being in {@link Turn#applicable()}: the picker filters, this only wants.
 *
 * <ol>
 *   <li>Asked its identity — v1 never deflects, it always shares its name. Gated on the pending
 *       act specifically being {@code ask_identity}, not just any obligation: an answer is to
 *       what was asked.</li>
 *   <li>Said goodbye to: say it back. The acknowledgement is what closes the record, and a
 *       goodbye is not a proposal anybody gets to refuse (decision: Luiz, 2026-09-13) — the
 *       negotiated ending it replaced let two bodies decline each other's proposals one line
 *       per beat.</li>
 *   <li>Asked a question in small talk: answer it, on its topic ({@link Topics#reply}). A question
 *       obliges (decision: Luiz, 2026-10-02).</li>
 *   <li>Already asked something of the counterpart that nothing of theirs has answered yet
 *       ({@link Turn#awaiting()}): hold — silence, not a second ask. Patience and the IGNORED
 *       snub already cover a counterpart who never replies; any discharging reply restores
 *       initiative on the very next turn.</li>
 *   <li>Say hello before anything else.</li>
 *   <li>A counterpart seen but never introduced, who has not already given a name in this
 *       record, and whom this body has not already asked here: ask. Once per record — a
 *       deflection is an answer, and asking again after one is badgering.</li>
 *   <li>Their small talk was the last thing said: reply on its topic, whatever the gauge says — a
 *       content settler that stood mute while the player chatted at it was the first client run's
 *       second finding (2026-09-13). A told day of theirs is answered with this body's own. A
 *       reply is never itself replied to: two content settlers trading answers talked out the
 *       whole turn cap (2026-09-14).</li>
 *   <li>Still wanting company: small talk, a remark or a question ({@link Topics#volunteer}) —
 *       only ever something not already brought up here, by either side ({@link Topics.Said}).</li>
 *   <li>Rung 8 would speak but has nothing new left to say: say goodbye. Running out of things to
 *       say is how a chat ends, not a reason to say them again (decision: Luiz, 2026-09-23).</li>
 *   <li>Nothing pressing, and nobody has spoken for this body's patience: say goodbye. Leaving
 *       the moment nothing is pressing reads as bolting — two seconds after learning a name, on
 *       the first client run (decision: Luiz, 2026-09-13).</li>
 * </ol>
 */
public final class PersonChooser implements Chooser {

    @Override
    public @Nullable Line choose(BrainContext ctx, Turn turn) {
        if (answersIdentity(turn)) {
            return Line.of(PersonActs.INFORM_NAME);
        }

        if (acknowledgesGoodbye(turn)) {
            return Line.of(SpeechActs.END_CHAT);
        }

        if (answersQuestion(turn)) {
            return new Line(PersonActs.SMALL_TALK_REPLY, Topics.reply(ctx,
                    counterpartSeen(ctx, turn), saidHere(turn), turn.pending().orElseThrow()));
        }

        if (isWaiting(turn)) {
            return null;    // already asked something — wait for the counterpart to answer it
        }

        if (greets(turn)) {
            return Line.of(SpeechActs.GREETING);
        }

        if (asksTheirName(ctx, turn)) {
            return Line.of(PersonActs.ASK_IDENTITY);
        }

        if (repliesToSmallTalk(turn)) {
            return new Line(PersonActs.SMALL_TALK_REPLY, Topics.reply(ctx,
                    counterpartSeen(ctx, turn), saidHere(turn),
                    Picker.lastSpoken(turn.encounter()).orElseThrow()));
        }

        if (makesSmallTalk(ctx, turn)) {
            // orElseThrow is safe: the rung's own predicate checked something new is left.
            Line line = Topics.volunteer(ctx, counterpartSeen(ctx, turn), saidHere(turn)).orElseThrow();
            return turn.applicable().contains(line.act()) ? line
                    : new Line(PersonActs.SMALL_TALK, line.payload());
        }

        if (runsOutOfThingsToSay(ctx, turn)) {
            return Line.of(SpeechActs.END_CHAT);
        }

        if (saysGoodbye(ctx, turn)) {
            return Line.of(SpeechActs.END_CHAT);
        }
        return null;
    }

    /**
     * The ladder read aloud — one line per rung, in order, each saying whether it would fire and
     * on what. First match wins, so at most one line reads {@code fired}; a later rung whose own
     * condition also holds still reads {@code skipped}, and its reason says why it would have.
     *
     * <p>Every verdict comes from the same predicate {@link #choose} branches on, so the account
     * cannot describe a turn this chooser would have played differently. The reasons around them
     * are the live numbers and percepts those predicates read.
     */
    @Override
    public List<String> explain(BrainContext ctx, Turn turn) {
        double company = ctx.percepts().needs().value(NeedKind.COMPANY);
        double boundary = contentBoundary(ctx);
        List<Rung> ladder = List.of(
                new Rung(answersIdentity(turn), "1 answer an ask_identity",
                        "pending " + pendingKey(turn) + ", inform_name "
                                + offer(turn, PersonActs.INFORM_NAME)),
                new Rung(acknowledgesGoodbye(turn), "2 acknowledge a goodbye",
                        "pending " + pendingKey(turn) + ", end_chat "
                                + offer(turn, SpeechActs.END_CHAT)),
                new Rung(answersQuestion(turn), "3 answer a question",
                        "pending " + pendingKey(turn) + ", small_talk_reply "
                                + offer(turn, PersonActs.SMALL_TALK_REPLY)),
                new Rung(isWaiting(turn), "4 hold while awaiting an answer", waitingFacts(turn)),
                new Rung(greets(turn), "5 greet",
                        (turn.greeted() ? "already greeted here" : "not greeted yet")
                                + ", greeting " + offer(turn, SpeechActs.GREETING)),
                new Rung(asksTheirName(ctx, turn), "6 ask their name", nameFacts(ctx, turn)
                        + (alreadyAsked(turn) ? ", already asked here" : ", not asked here yet")
                        + ", ask_identity " + offer(turn, PersonActs.ASK_IDENTITY)),
                new Rung(repliesToSmallTalk(turn), "7 reply to their small talk",
                        "last said " + Picker.lastSpoken(turn.encounter())
                                .map(line -> (theirs(turn, line) ? "theirs " : "ours ") + line.act())
                                .orElse("nothing")
                                + ", small_talk_reply " + offer(turn, PersonActs.SMALL_TALK_REPLY)),
                new Rung(makesSmallTalk(ctx, turn), "8 small talk", "company " + number(company)
                        + (company < boundary ? " < " : " ≥ ") + "content " + number(boundary)
                        + ", small_talk " + offer(turn, PersonActs.SMALL_TALK)),
                new Rung(runsOutOfThingsToSay(ctx, turn), "9 out of things to say",
                        saidFacts(ctx, turn) + ", end_chat " + offer(turn, SpeechActs.END_CHAT)),
                new Rung(saysGoodbye(ctx, turn), "10 say goodbye",
                        "silent " + silence(ctx, turn) + " of " + patience(ctx) + " ticks, end_chat "
                                + offer(turn, SpeechActs.END_CHAT)));

        List<String> out = new ArrayList<>();
        boolean spoken = false;
        for (Rung rung : ladder) {
            out.add((rung.fires() && !spoken ? "fired:   " : "skipped: ")
                    + rung.rule() + " — " + rung.why());
            spoken |= rung.fires();
        }
        return out;
    }

    /** One rung as the readout sees it: whether it would take the turn, and on what. */
    private record Rung(boolean fires, String rule, String why) {
    }

    // ── the rungs, as predicates both choose() and explain() ask ─────────────────────────────

    private static boolean answersIdentity(Turn turn) {
        return pendingIsAskIdentity(turn) && turn.applicable().contains(PersonActs.INFORM_NAME);
    }

    /** Rung 2: a goodbye is pending, and the only answer to a goodbye is a goodbye. */
    private static boolean acknowledgesGoodbye(Turn turn) {
        return pendingIs(turn, SpeechActs.END_CHAT) && turn.applicable().contains(SpeechActs.END_CHAT);
    }

    /** Rung 3: a question of theirs is pending, and a reply is on offer. */
    private static boolean answersQuestion(Turn turn) {
        return pendingIs(turn, PersonActs.SMALL_TALK_QUESTION)
                && turn.applicable().contains(PersonActs.SMALL_TALK_REPLY);
    }

    /**
     * Rung 7: the last thing said was a remark of theirs — a question is rung 3's, and a reply is
     * never replied to.
     */
    private static boolean repliesToSmallTalk(Turn turn) {
        return turn.applicable().contains(PersonActs.SMALL_TALK_REPLY)
                && Picker.lastSpoken(turn.encounter())
                        .filter(line -> theirs(turn, line)
                                && line.act().equals(PersonActs.SMALL_TALK.key()))
                        .isPresent();
    }

    private static boolean theirs(Turn turn, Utterance line) {
        return turn.counterpart().map(them -> them.equals(line.author())).orElse(false);
    }

    /** Whether SELF has already asked the counterpart something nothing of theirs has answered. */
    private static boolean isWaiting(Turn turn) {
        return turn.awaiting().isPresent();
    }

    private static boolean greets(Turn turn) {
        return !turn.greeted() && turn.applicable().contains(SpeechActs.GREETING);
    }

    private static boolean asksTheirName(BrainContext ctx, Turn turn) {
        return turn.applicable().contains(PersonActs.ASK_IDENTITY)
                && isUnintroducedCounterpart(ctx, turn)
                && !counterpartAlreadySaidItsName(turn)
                && !alreadyAsked(turn);
    }

    private static boolean makesSmallTalk(BrainContext ctx, Turn turn) {
        return wouldChat(ctx, turn)
                && Topics.anythingLeft(ctx, counterpartSeen(ctx, turn), saidHere(turn));
    }

    /** Rung 9: rung 8's reason to speak holds, and everything this body could say has been said. */
    private static boolean runsOutOfThingsToSay(BrainContext ctx, Turn turn) {
        return wouldChat(ctx, turn)
                && !Topics.anythingLeft(ctx, counterpartSeen(ctx, turn), saidHere(turn))
                && turn.applicable().contains(SpeechActs.END_CHAT);
    }

    /** Rung 8's reason to speak, before asking whether there is anything new to say. */
    private static boolean wouldChat(BrainContext ctx, Turn turn) {
        return turn.applicable().contains(PersonActs.SMALL_TALK) && wantsCompany(ctx);
    }

    /**
     * What has already been brought up in THIS record — topics by either side, deeds by this body,
     * read the way {@link #alreadyAsked} reads it: in a two-party record a line the counterpart did
     * not say is this body's own.
     */
    private static Topics.Said saidHere(Turn turn) {
        AgentId counterpart = turn.counterpart().orElse(null);
        return Topics.Said.in(turn.encounter().transcript(),
                line -> !line.system() && !line.author().equals(counterpart));
    }

    private static boolean wantsCompany(BrainContext ctx) {
        return ctx.percepts().needs().value(NeedKind.COMPANY) < contentBoundary(ctx);
    }

    private static boolean saysGoodbye(BrainContext ctx, Turn turn) {
        return turn.applicable().contains(SpeechActs.END_CHAT)
                && silence(ctx, turn) > patience(ctx);
    }

    private static long silence(BrainContext ctx, Turn turn) {
        return Picker.silence(turn.encounter(), ctx.percepts().time());
    }

    private static int patience(BrainContext ctx) {
        return ctx.profile().i(ProfileAspect.SOCIAL_PATIENCE_TICKS);
    }

    // ── what the readout says about them ─────────────────────────────────────────────────────

    /** The act owed by this body, or {@code none} — the fact rungs 1 to 3 turn on. */
    private static String pendingKey(Turn turn) {
        return turn.pending().map(Utterance::act).orElse("none");
    }

    /** What rung 4 tells the readout — the act SELF is still owed no answer to, if any. */
    private static String waitingFacts(Turn turn) {
        return turn.awaiting()
                .map(asked -> "waiting: " + asked.act() + ", ball is theirs")
                .orElse("nothing outstanding");
    }

    /** What rung 9 tells the readout: how much has been said here, and whether anything is left. */
    private static String saidFacts(BrainContext ctx, Turn turn) {
        Topics.Said said = saidHere(turn);
        return "said " + said.topics().size() + " topic(s) and " + said.deeds().size()
                + " deed(s) here, "
                + (Topics.anythingLeft(ctx, counterpartSeen(ctx, turn), said) ? "something new left"
                        : "nothing new left");
    }

    private static String offer(Turn turn, SpeechAct act) {
        return turn.applicable().contains(act) ? "on offer" : "not on offer";
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * The three facts {@link #isUnintroducedCounterpart} and {@link #counterpartAlreadySaidItsName}
     * read, spelled out — which of them refused rung 6 is the whole question when a settler will
     * not ask a stranger's name.
     */
    private static String nameFacts(BrainContext ctx, Turn turn) {
        Optional<Being> seen = turn.counterpart().map(BeingId::of).flatMap(id -> findBeing(ctx, id));
        if (seen.isEmpty()) {
            return "counterpart not perceived";
        }
        Being being = seen.get();
        return "seen at " + being.identified()
                + (being.name().isEmpty() ? ", unnamed" : ", named " + being.name())
                + (counterpartAlreadySaidItsName(turn) ? ", already given in this record"
                        : ", not given here");
    }

    /**
     * Whether specifically {@code act} is owed — not just any pending obligation. An answer is to
     * what was asked: rung 1 answers a question about its name, rung 2 a goodbye, rung 3 a question
     * in small talk, and none may grab an obligation that happens to leave its line technically
     * applicable.
     */
    private static boolean pendingIsAskIdentity(Turn turn) {
        return pendingIs(turn, PersonActs.ASK_IDENTITY);
    }

    private static boolean pendingIs(Turn turn, SpeechAct act) {
        return turn.pending().map(u -> u.act().equals(act.key())).orElse(false);
    }

    /**
     * Whether this body has already asked the counterpart's name in THIS record. Read the way the
     * engine reads {@link Turn#awaiting()}: a chooser has no self-id, and in a two-party record a
     * line the counterpart did not say is this body's own. Asked and deflected is asked; asking
     * again on the next beat is what the deflection was declining.
     */
    private static boolean alreadyAsked(Turn turn) {
        AgentId counterpart = turn.counterpart().orElse(null);
        for (Utterance line : turn.encounter().transcript()) {
            if (!line.system() && !line.author().equals(counterpart)
                    && line.act().equals(PersonActs.ASK_IDENTITY.key())) {
                return true;
            }
        }
        return false;
    }

    /** The value at which company stops asking for more — {@code content}'s own anchor. */
    private static double contentBoundary(BrainContext ctx) {
        return NeedKind.COMPANY.level("content").orElseThrow().value(ctx.profile());
    }

    /**
     * Whether the counterpart is somebody we can currently see, made out as an individual, and
     * have never been told the name of — the sensor fills {@link Being#name()} from the
     * observer's own contact book, so an empty name at {@link Being.Identified#INDIVIDUAL} means
     * seen but unmet. Empty (skip asking) for a counterpart not currently perceived at all —
     * there is no name to ask of someone out of sight.
     */
    private static boolean isUnintroducedCounterpart(BrainContext ctx, Turn turn) {
        return turn.counterpart()
                .map(BeingId::of)
                .flatMap(id -> findBeing(ctx, id))
                .filter(being -> being.identified() == Being.Identified.INDIVIDUAL
                        && being.name().isEmpty())
                .isPresent();
    }

    /**
     * Whether the counterpart has already said its name in THIS encounter. The percept's name is
     * filled from the contact book by the sensor, a few ticks behind the line that wrote it — long
     * enough, now that every line waits a beat, for {@link #isUnintroducedCounterpart} to still
     * read empty on the next turn. Asking somebody the name they just gave reads as deaf; the
     * transcript is the record that never lags.
     */
    private static boolean counterpartAlreadySaidItsName(Turn turn) {
        AgentId counterpart = turn.counterpart().orElse(null);
        if (counterpart == null) {
            return false;
        }
        for (Utterance line : turn.encounter().transcript()) {
            if (line.system()) {
                // What the world wrote about somebody is not that body speaking, and
                // Speech.system takes ANY act — Picker's own scans skip these first too.
                continue;
            }
            if (counterpart.equals(line.author()) && line.act().equals(PersonActs.INFORM_NAME.key())) {
                return true;
            }
        }
        return false;
    }

    /** The counterpart as this body perceives them right now — empty when out of its senses. */
    private static Optional<Being> counterpartSeen(BrainContext ctx, Turn turn) {
        return turn.counterpart().map(BeingId::of).flatMap(id -> findBeing(ctx, id));
    }

    private static Optional<Being> findBeing(BrainContext ctx, BeingId id) {
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(id)) {
                return Optional.of(being);
            }
        }
        return Optional.empty();
    }
}
