package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.Ramp;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The part with the personality — v1's seven-rung ladder, first match wins, every rung gated on
 * the act being in {@link Turn#applicable()}: the picker filters, this only wants.
 *
 * <ol>
 *   <li>Asked its identity — v1 never deflects, it always shares its name. Gated on the pending
 *       act specifically being {@code ask_identity}, not just any obligation: {@code
 *       request_end_chat} leaves {@code inform_name} technically applicable too (its responses
 *       are unconstrained), and priority 2 needs the chance to actually answer it.</li>
 *   <li>Answering a proposal to end: leave unless company is still asking for MORE (see
 *       {@link #stillLonely}), otherwise raise a new topic — any self line discharges the
 *       obligation, so small talk legitimately answers it.</li>
 *   <li>Already asked something of the counterpart that nothing of theirs has answered yet
 *       ({@link Turn#awaiting()}): hold — silence, not a second ask. Patience and the IGNORED
 *       snub already cover a counterpart who never replies; any discharging reply restores
 *       initiative on the very next turn.</li>
 *   <li>Say hello before anything else.</li>
 *   <li>A counterpart seen but never introduced, and who has not already given a name in this
 *       record: ask.</li>
 *   <li>Still wanting company: small talk — the topic varies (see {@link Topics}), the act
 *       never does; see {@link #choose}'s own rung 6 for why.</li>
 *   <li>Nothing pressing: propose leaving.</li>
 * </ol>
 */
public final class PersonChooser implements Chooser {

    @Override
    public @Nullable Line choose(BrainContext ctx, Turn turn) {
        if (answersIdentity(turn)) {
            return Line.of(PersonActs.INFORM_NAME);
        }

        SpeechAct answer = endChatAnswer(ctx, turn);
        if (answer == PersonActs.SMALL_TALK) {
            return new Line(PersonActs.SMALL_TALK, Topics.pick(ctx));
        }
        if (answer != null) {
            return Line.of(answer);
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

        if (makesSmallTalk(ctx, turn)) {
            // No act-level variety roll here: rungs 4 and 5 already claim any card such a roll
            // could legally deal, so act variety is structurally impossible at this rung —
            // conversational variety comes from the topic draw below and the reply beat's jitter.
            return new Line(PersonActs.SMALL_TALK, Topics.pick(ctx));
        }

        if (proposesLeaving(turn)) {
            return Line.of(SpeechActs.REQUEST_END_CHAT);
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
        SpeechAct answer = endChatAnswer(ctx, turn);
        List<Rung> ladder = List.of(
                new Rung(answersIdentity(turn), "1 answer an ask_identity",
                        "pending " + pendingKey(turn) + ", inform_name "
                                + offer(turn, PersonActs.INFORM_NAME)),
                new Rung(answer != null, "2 answer a request_end_chat",
                        "pending " + pendingKey(turn) + (pendingIsRequestEndChat(turn)
                                ? ", company " + number(company)
                                        + (stillLonely(ctx) ? " still on the lonely side"
                                                : " past lonely")
                                        + " → " + (answer == null ? "neither line on offer"
                                                : answer.key())
                                : "")),
                new Rung(isWaiting(turn), "3 hold while awaiting an answer", waitingFacts(turn)),
                new Rung(greets(turn), "4 greet",
                        (turn.greeted() ? "already greeted here" : "not greeted yet")
                                + ", greeting " + offer(turn, SpeechActs.GREETING)),
                new Rung(asksTheirName(ctx, turn), "5 ask their name", nameFacts(ctx, turn)
                        + ", ask_identity " + offer(turn, PersonActs.ASK_IDENTITY)),
                new Rung(makesSmallTalk(ctx, turn), "6 small talk", "company " + number(company)
                        + (company < boundary ? " < " : " ≥ ") + "content " + number(boundary)
                        + ", small_talk " + offer(turn, PersonActs.SMALL_TALK)),
                new Rung(proposesLeaving(turn), "7 propose leaving",
                        "request_end_chat " + offer(turn, SpeechActs.REQUEST_END_CHAT)));

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

    /**
     * Rung 2's answer to a proposal to end — {@code null} when nothing is pending, or when neither
     * line it would reach for is on offer and the ladder must fall through.
     */
    private static @Nullable SpeechAct endChatAnswer(BrainContext ctx, Turn turn) {
        if (!pendingIsRequestEndChat(turn)) {
            return null;
        }
        if (!stillLonely(ctx) && turn.applicable().contains(SpeechActs.END_CHAT)) {
            return SpeechActs.END_CHAT;
        }
        return turn.applicable().contains(PersonActs.SMALL_TALK) ? PersonActs.SMALL_TALK : null;
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
                && !counterpartAlreadySaidItsName(turn);
    }

    private static boolean makesSmallTalk(BrainContext ctx, Turn turn) {
        return ctx.percepts().needs().value(NeedKind.COMPANY) < contentBoundary(ctx)
                && turn.applicable().contains(PersonActs.SMALL_TALK);
    }

    private static boolean proposesLeaving(Turn turn) {
        return turn.applicable().contains(SpeechActs.REQUEST_END_CHAT);
    }

    // ── what the readout says about them ─────────────────────────────────────────────────────

    /** The act owed by this body, or {@code none} — the fact rungs 1 and 2 both turn on. */
    private static String pendingKey(Turn turn) {
        return turn.pending().map(Utterance::act).orElse("none");
    }

    /** What rung 3 tells the readout — the act SELF is still owed no answer to, if any. */
    private static String waitingFacts(Turn turn) {
        return turn.awaiting()
                .map(asked -> "waiting: " + asked.act() + ", ball is theirs")
                .orElse("nothing outstanding");
    }

    private static String offer(Turn turn, SpeechAct act) {
        return turn.applicable().contains(act) ? "on offer" : "not on offer";
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * The three facts {@link #isUnintroducedCounterpart} and {@link #counterpartAlreadySaidItsName}
     * read, spelled out — which of them refused rung 4 is the whole question when a settler will
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
     * Whether specifically an {@code ask_identity} is owed — not just any pending obligation.
     * {@code request_end_chat} declares no constrained responses ("any reply discharges"), so
     * {@code inform_name} reads as technically applicable there too; without this check, priority
     * 1 would answer a proposal to end the chat by reintroducing itself instead of ever letting
     * priority 2 decide.
     */
    private static boolean pendingIsAskIdentity(Turn turn) {
        return turn.pending().map(u -> u.act().equals(PersonActs.ASK_IDENTITY.key())).orElse(false);
    }

    private static boolean pendingIsRequestEndChat(Turn turn) {
        return turn.pending().map(u -> u.act().equals(SpeechActs.REQUEST_END_CHAT.key()))
                .orElse(false);
    }

    /**
     * Whether company is pressing from its LONELY side — the only half of the gauge that is a
     * reason to keep somebody talking.
     *
     * <p>Non-zero pressure alone is not that question, and asking it that way was a deadlock. The
     * gauge is a V: a settler the conversation has just FILLED past {@code content} reads as
     * pressed again, from the crowded end, and so refused every proposal to leave. Two of them
     * proposed leaving to each other one line per tick, for as long as the world ran, into a store
     * that persists every line — 24 KB of one conversation in four minutes, and no way out of it.
     * Found in world by this branch's smoke, 2026-08-28.
     *
     * <p>{@link Ramp#side} is the library's own answer to that exact trap, which is why this asks
     * it rather than comparing against a level by hand.
     */
    private static boolean stillLonely(BrainContext ctx) {
        Ramp ramp = NeedKind.COMPANY.ramp();
        return ramp != null
                && ramp.side(ctx.profile(), ctx.percepts().needs().value(NeedKind.COMPANY)) < 0;
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

    private static Optional<Being> findBeing(BrainContext ctx, BeingId id) {
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(id)) {
                return Optional.of(being);
            }
        }
        return Optional.empty();
    }
}
