package dev.luizloyola.autarkia.core.person.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.ActuatorAccess;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Converse;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.FakePercepts;
import dev.luizloyola.anima.core.brain.task.FakeSpeech;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Picker;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seven-priority ladder, one test per rung, plus a full two-party conversation driven by real
 * {@link Converse} tasks. Most rungs are exercised against a hand-built {@link Chooser.Turn} over
 * a real (otherwise unused) {@link Encounter} — the chooser reads only the turn's pre-computed
 * facts, never the encounter itself, so this pins each rung's own branching without fighting
 * {@code Picker}'s coupled applicability rules (asking and answering share one constrained set).
 */
class PersonChooserTest {

    private final PersonChooser chooser = new PersonChooser();
    private final FakeContext ctx = new FakeContext();
    private final BeingId otherId = BeingId.of(AgentId.random());

    private Encounter freshEncounter() {
        return ctx.speech.join(otherId, Speech.Opening.QUIET).orElseThrow();
    }

    // ── priority 1: something is owed ────────────────────────────────────────────────────────

    @Test
    @DisplayName("priority 1: pending with inform_name applicable — v1 always shares")
    void priority1SharesTheNameWhenAsked() {
        Utterance ask = new Utterance(otherId.asPerson(), PersonActs.ASK_IDENTITY.key(), Map.of(), 0);
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.DEFLECT, PersonActs.INFORM_NAME), Optional.of(ask), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(PersonActs.INFORM_NAME), chooser.choose(ctx, turn));
    }

    @Test
    @DisplayName("priority 1 is gated: pending with inform_name NOT applicable falls through")
    void priority1FallsThroughWhenInformNameIsNotOnOffer() {
        ctx.percepts.company.setValue(0.5); // below the content boundary — priority 6 will catch it
        Utterance ask = new Utterance(otherId.asPerson(), PersonActs.ASK_IDENTITY.key(), Map.of(), 0);
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.DEFLECT, PersonActs.SMALL_TALK), Optional.of(ask), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act());
    }

    // ── priority 2: answering a proposal to end ──────────────────────────────────────────────

    @Test
    @DisplayName("priority 2: pending request_end_chat with pressure 0.0 ends the chat, "
            + "not a reflex inform_name")
    void priority2EndsWhenCompanyNoLongerPresses() {
        ctx.percepts.company.setValue(0.6); // between "alone" and "content" — pressure is flat at 0
        Utterance ask = new Utterance(otherId.asPerson(), SpeechActs.REQUEST_END_CHAT.key(), Map.of(), 0);
        // inform_name is included deliberately: request_end_chat is unconstrained ("any reply
        // discharges"), so Picker leaves it technically applicable too — priority 1 must not grab
        // it here (that was the regression: see PersonChooser#pendingIsAskIdentity).
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.REQUEST_END_CHAT, SpeechActs.END_CHAT, PersonActs.INFORM_NAME),
                Optional.of(ask), Optional.empty(), true, Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(SpeechActs.END_CHAT), chooser.choose(ctx, turn));
    }

    @Test
    @DisplayName("priority 2: pending request_end_chat with pressure > 0.0 raises a new topic, "
            + "not a reflex inform_name")
    void priority2ChangesTheSubjectWhenCompanyStillPresses() {
        ctx.percepts.company.setValue(0.0); // desolate — pressure 0.50
        Utterance ask = new Utterance(otherId.asPerson(), SpeechActs.REQUEST_END_CHAT.key(), Map.of(), 0);
        // Same deliberate inclusion of inform_name as above — this is the exact regression the
        // ask_identity-only gate on priority 1 exists to close.
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.REQUEST_END_CHAT, SpeechActs.END_CHAT, PersonActs.SMALL_TALK,
                        PersonActs.INFORM_NAME),
                Optional.of(ask), Optional.empty(), true, Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act(),
                "the discharge is any self line — small talk legitimately answers the proposal, "
                        + "and must win over inform_name");
        assertTrue(Topics.options(ctx).contains(line.payload().get("topic")));
    }

    @Test
    @DisplayName("priority 2: pending request_end_chat with company CROWDED still ends the chat")
    void priority2EndsWhenCompanyIsAlreadyTooMuch() {
        // The far side of the V, and the deadlock this test exists for: at 1.0 the gauge presses
        // again — from the crowded end — so a chooser reading `pressure == 0.0` refused to leave.
        // Two of these proposed leaving to each other one line per tick until the world stopped.
        ctx.percepts.company.setValue(1.0);
        Utterance ask = new Utterance(otherId.asPerson(), SpeechActs.REQUEST_END_CHAT.key(), Map.of(), 0);
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.REQUEST_END_CHAT, SpeechActs.END_CHAT, PersonActs.SMALL_TALK),
                Optional.of(ask), Optional.empty(), true, Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(SpeechActs.END_CHAT), chooser.choose(ctx, turn),
                "a settler who has had too much company is the last one who wants to keep talking");
    }

    // ── priority 4: greet first ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("priority 4: not yet greeted — greets before anything else")
    void priority4GreetsFirst() {
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.GREETING, PersonActs.SMALL_TALK), Optional.empty(), Optional.empty(), false,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(SpeechActs.GREETING), chooser.choose(ctx, turn));
    }

    // ── priority 5: ask an unintroduced counterpart's name ───────────────────────────────────

    @Test
    @DisplayName("priority 5: counterpart perceived at INDIVIDUAL with an empty name — asks")
    void priority5AsksAnUnintroducedCounterpart() {
        ctx.percepts.beings = List.of(FakePercepts.personAt(otherId, new Pos(4, 64, 0), 4.0, ""));
        Encounter e = freshEncounter();
        // Our own name given away is not theirs — the transcript guard is about what THEY said.
        e.append(new Utterance(ctx.self, PersonActs.INFORM_NAME.key(), Map.of(), 0));
        Chooser.Turn turn = new Chooser.Turn(e,
                List.of(PersonActs.ASK_IDENTITY, PersonActs.SMALL_TALK), Optional.empty(), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(PersonActs.ASK_IDENTITY), chooser.choose(ctx, turn));
    }

    @Test
    @DisplayName("priority 5 is skipped once they have said their name here, however stale the percept")
    void priority5SkipsWhenTheTranscriptAlreadyHoldsTheirName() {
        ctx.percepts.company.setValue(0.5); // below content — falls to priority 6
        // The percept still reads unnamed: the sensor fills a Being's name from the contact book
        // a few ticks after the line that wrote it, and every line now waits a beat — a whole turn
        // of asking again fits in that lag.
        ctx.percepts.beings = List.of(FakePercepts.personAt(otherId, new Pos(4, 64, 0), 4.0, ""));
        Encounter e = freshEncounter();
        e.append(new Utterance(otherId.asPerson(), PersonActs.INFORM_NAME.key(), Map.of(), 0));
        Chooser.Turn turn = new Chooser.Turn(e,
                List.of(PersonActs.ASK_IDENTITY, PersonActs.SMALL_TALK), Optional.empty(), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act(),
                "they just told us — asking a second time reads as deaf");
    }

    @Test
    @DisplayName("priority 5 is skipped once the counterpart is already named")
    void priority5SkipsWhenTheCounterpartIsAlreadyNamed() {
        ctx.percepts.company.setValue(0.5); // below content — falls to priority 6
        ctx.percepts.beings = List.of(FakePercepts.personAt(otherId, new Pos(4, 64, 0), 4.0, "Bramble"));
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.ASK_IDENTITY, PersonActs.SMALL_TALK), Optional.empty(), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act(), "the name is already known — nothing to ask");
    }

    @Test
    @DisplayName("priority 5 is skipped when the counterpart is not currently perceived")
    void priority5SkipsWhenTheCounterpartIsNotPerceived() {
        ctx.percepts.company.setValue(0.5); // ctx.percepts.beings stays empty — nobody in sight
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.ASK_IDENTITY, PersonActs.SMALL_TALK), Optional.empty(), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act(), "can't ask a name of someone you can't see");
    }

    // ── priority 6: still wanting company ────────────────────────────────────────────────────

    @Test
    @DisplayName("priority 6: company value below the content boundary — makes small talk")
    void priority6MakesSmallTalkWhenCompanyWantsMore() {
        ctx.percepts.company.setValue(0.5); // below 0.85
        // Only SMALL_TALK on offer, so the 1-in-4 roll can never find a substitute — this test
        // pins the base branch regardless of which way the roll falls.
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(), List.of(PersonActs.SMALL_TALK),
                Optional.empty(), Optional.empty(), true, Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act());
        assertTrue(Topics.options(ctx).contains(line.payload().get("topic")));
    }

    @Test
    @DisplayName("priority 6's 1-in-4 roll substitutes a different applicable non-end act on a hit")
    void priority6RollSubstitutesVarietyOnAHit() {
        ctx.percepts.company.setValue(0.5);
        ctx.seed(scripted(0, 0)); // hits the 1-in-4, then picks index 0 of the one alternative
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.SMALL_TALK, PersonActs.ASK_IDENTITY), Optional.empty(), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(PersonActs.ASK_IDENTITY), chooser.choose(ctx, turn));
    }

    @Test
    @DisplayName("priority 6's roll leaves small talk alone on a miss")
    void priority6RollMissKeepsSmallTalk() {
        ctx.percepts.company.setValue(0.5);
        ctx.seed(scripted(1)); // misses the 1-in-4
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.SMALL_TALK, PersonActs.ASK_IDENTITY), Optional.empty(), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(PersonActs.SMALL_TALK, chooser.choose(ctx, turn).act());
    }

    @Test
    @DisplayName("priority 6's roll never reaches for the door or deflects nothing pending")
    void priority6RollNeverPicksRequestEndChatOrDeflect() {
        ctx.percepts.company.setValue(0.5); // below the boundary — priority 6 governs
        // A realistic mid-conversation applicable set: greeted, no pending, nothing constrained.
        List<SpeechAct> applicable = List.of(SpeechActs.GREETING, SpeechActs.DEFLECT,
                SpeechActs.REQUEST_END_CHAT, PersonActs.ASK_IDENTITY, PersonActs.INFORM_NAME,
                PersonActs.SMALL_TALK);
        Set<SpeechAct> picked = new HashSet<>();
        // Force the 1-in-4 hit and sweep every position the variety pick could land on.
        for (int index = 0; index < 3; index++) {
            ctx.seed(scripted(0, index));
            Chooser.Turn turn = new Chooser.Turn(freshEncounter(), applicable, Optional.empty(),
                    Optional.empty(), true, Optional.of(otherId.asPerson()));

            SpeechAct act = chooser.choose(ctx, turn).act();

            assertNotEquals(SpeechActs.REQUEST_END_CHAT, act,
                    "the roll only varies small talk — it must never propose leaving");
            assertNotEquals(SpeechActs.DEFLECT, act,
                    "nothing is pending — there is nothing to deflect");
            picked.add(act);
        }
        assertEquals(Set.of(SpeechActs.GREETING, PersonActs.ASK_IDENTITY, PersonActs.INFORM_NAME),
                picked, "exactly the genuine substitutes — small talk, request_end_chat and "
                        + "deflect are excluded from the pool");
    }

    @Test
    @DisplayName("priority 6 does not fire at or above the content boundary — falls to priority 7")
    void priority6DoesNotFireAtOrAboveTheBoundary() {
        ctx.percepts.company.setValue(0.85); // exactly the boundary — not below it
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.SMALL_TALK, SpeechActs.REQUEST_END_CHAT), Optional.empty(), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(SpeechActs.REQUEST_END_CHAT), chooser.choose(ctx, turn));
    }

    // ── priority 7: nothing pressing ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("priority 7: greeted, known, company more than enough — proposes leaving")
    void priority7ProposesEndingWhenCompanyIsMoreThanEnough() {
        ctx.percepts.company.setValue(1.0); // crowded
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.REQUEST_END_CHAT), Optional.empty(), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(SpeechActs.REQUEST_END_CHAT), chooser.choose(ctx, turn));
    }

    @Test
    @DisplayName("silence when nothing is applicable at all")
    void returnsNullWhenNothingIsApplicable() {
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(), List.of(), Optional.empty(),
                Optional.empty(), true, Optional.of(otherId.asPerson()));

        assertNull(chooser.choose(ctx, turn));
    }

    // ── priority 3: hold while awaiting an answer (the regression this task fixes) ──────────────

    @Test
    @DisplayName("regression: self's own undischarged request_end_chat holds the ladder silent")
    void priority3HoldsSilentWhileSelfsOwnAskIsUnanswered() {
        // Crowded, so absent the gate rung 7 would fire and re-propose leaving — the exact bug
        // observed in-world: a body proposing the same goodbye every beat.
        ctx.percepts.company.setValue(1.0);
        Encounter e = freshEncounter();
        Utterance selfAsk = new Utterance(ctx.self, SpeechActs.REQUEST_END_CHAT.key(), Map.of(), 0);
        e.append(selfAsk);
        // Everything a livelier turn could offer, all at once — a null here can only be the
        // awaiting gate, not some narrower rung missing by coincidence.
        List<SpeechAct> everything = List.of(SpeechActs.GREETING, SpeechActs.REQUEST_END_CHAT,
                SpeechActs.END_CHAT, PersonActs.ASK_IDENTITY, PersonActs.SMALL_TALK);
        Chooser.Turn stillWaiting = new Chooser.Turn(e, everything, Optional.empty(),
                Optional.of(selfAsk), true, Optional.of(otherId.asPerson()));

        assertNull(chooser.choose(ctx, stillWaiting),
                "self already asked to end the chat — the ladder must not ask it again");
    }

    @Test
    @DisplayName("regression: the counterpart's discharging reply restores initiative")
    void priority3DischargedByTheCounterpartsReplyRestoresProposing() {
        ctx.percepts.company.setValue(1.0);
        Encounter e = freshEncounter();
        e.append(new Utterance(ctx.self, SpeechActs.REQUEST_END_CHAT.key(), Map.of(), 0));
        // Any line of theirs discharges it — small talk legitimately answers request_end_chat,
        // same rule Picker.pendingOn already applies from the other direction.
        e.append(new Utterance(otherId.asPerson(), PersonActs.SMALL_TALK.key(),
                Map.of("topic", "weather"), 20));
        List<SpeechAct> everything = List.of(SpeechActs.GREETING, SpeechActs.REQUEST_END_CHAT,
                SpeechActs.END_CHAT, PersonActs.ASK_IDENTITY, PersonActs.SMALL_TALK);
        Chooser.Turn discharged = new Chooser.Turn(e, everything, Optional.empty(),
                Optional.empty(), true, Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(SpeechActs.REQUEST_END_CHAT), chooser.choose(ctx, discharged),
                "the other party's reply discharged what self was owed — initiative is self's again");
    }

    // ── explain(): the ladder read aloud ─────────────────────────────────────────────────────

    @Test
    @DisplayName("explain names the rung that fired, and it is the rung choose() actually took")
    void explainNamesTheRungChooseTook() {
        // Mid-conversation: greeted, nothing owed, company still wanting more — so rung 6 is live
        // too, and rung 5 has to be seen outranking it rather than winning by default.
        ctx.percepts.company.setValue(0.5);
        ctx.percepts.beings = List.of(FakePercepts.personAt(otherId, new Pos(4, 64, 0), 4.0, ""));
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.GREETING, SpeechActs.REQUEST_END_CHAT, PersonActs.ASK_IDENTITY,
                        PersonActs.SMALL_TALK),
                Optional.empty(), Optional.empty(), true, Optional.of(otherId.asPerson()));

        List<String> lines = chooser.explain(ctx, turn);

        assertEquals(7, lines.size(), "one line per rung, whatever happens");
        List<String> fired = lines.stream().filter(line -> line.startsWith("fired")).toList();
        assertEquals(1, fired.size(), "first match wins — exactly one rung takes the turn");
        assertTrue(fired.get(0).contains("5 ask their name"), fired.get(0));
        assertEquals(PersonActs.ASK_IDENTITY, chooser.choose(ctx, turn).act(),
                "the rung explain() marks fired is the one choose() actually took");

        // Live facts, not canned text: the pending act, rung 5's percept, rung 6's two numbers.
        assertTrue(lines.get(1).contains("pending none"), lines.get(1));
        assertTrue(lines.get(4).contains("seen at INDIVIDUAL"), lines.get(4));
        assertTrue(lines.get(5).contains("company 0.50 < content 0.85"), lines.get(5));
        // Rung 7 would have fired on its own — it still reads skipped, because rung 5 spoke.
        assertTrue(lines.get(6).startsWith("skipped"), lines.get(6));
    }

    // ── the full two-party conversation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a full two-party conversation: greets, identifies both ways, and ends")
    void fullConversationGreetsIdentifiesAndEnds() {
        FakeContext other = new FakeContext();
        // The second body's speech over the first's roster — the shared-roster constructor.
        FakeSpeech otherSpeech = new FakeSpeech(other.self, () -> other.profile,
                () -> other.percepts.time, ctx.speech.roster);
        BrainContext otherContext = new SecondSpeaker(other, otherSpeech);
        ctx.speech.chooser = chooser;
        otherSpeech.chooser = chooser;
        // Default caps throughout — no turn-cap trick needed: priority 1 only answers a pending
        // ask_identity now, so the answering side's pending request_end_chat always reaches
        // priority 2, whatever the transcript length.

        Pos here = new Pos(4, 64, 0);
        // Each side perceives the other, seen but never introduced — INDIVIDUAL tier, empty name.
        ctx.percepts.beings = List.of(FakePercepts.personAt(BeingId.of(other.self), here, 4.0, ""));
        other.percepts.beings = List.of(FakePercepts.personAt(BeingId.of(ctx.self), here, 4.0, ""));
        // At the content boundary itself: not below it (skips small talk), and its own pressure
        // anchor is 0.0 (so the eventual request to end settles the SAME turn it lands, rather
        // than opening another round of small talk).
        ctx.percepts.company.setValue(0.85);
        other.percepts.company.setValue(0.85);

        Converse taskA = new Converse(BeingId.of(other.self), Speech.Opening.I_HAILED);
        Converse taskB = new Converse(BeingId.of(ctx.self), Speech.Opening.THEY_HAILED);

        // Every line waits REPLY_GRACE_TICKS after the previous one, whoever said it and whatever
        // is owed, PLUS a jitter each side rolls for itself (Converse.JITTER_TICKS) — so a beat
        // here is the widest of those, the only step at which both bodies are eligible whatever
        // they rolled. On a beat where BOTH sides may speak, the world's own tick order decides who
        // takes it; the order below is the one that reads as a conversation, and the assertions
        // name what each body was doing.
        long beat = Picker.REPLY_GRACE_TICKS + Converse.JITTER_TICKS;
        clockTo(ctx, other, 0);
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A hails — even her own greeting waits a beat");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B waits out the same beat");

        clockTo(ctx, other, beat);
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A's beat has elapsed — she greets");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "that line restarted B's beat");

        clockTo(ctx, other, 2 * beat);
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A already spoke twice running — silent");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B greets back");

        clockTo(ctx, other, 3 * beat);
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A asks B's name");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext),
                "B owes an answer, but an obligation buys no head start on the beat");

        clockTo(ctx, other, 4 * beat);
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B's beat elapsed — shares its name");
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "that line restarted A's beat");

        // Production fills a Being's name from the sensor watching INFORM_NAME land; the fake has
        // no live sensor, so the test plays that part: A now knows B is "Bramble".
        ctx.percepts.beings = List.of(FakePercepts.personAt(BeingId.of(other.self), here, 4.0, "Bramble"));

        clockTo(ctx, other, 5 * beat);
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext),
                "B is still under its own consecutive cap — asks A's name right back");
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A's beat restarted on B's ask");

        clockTo(ctx, other, 6 * beat);
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A owes a reply — shares its name");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B's beat restarted on it");

        // Same, the other way: B now knows A is "Alder".
        other.percepts.beings = List.of(FakePercepts.personAt(BeingId.of(ctx.self), here, 4.0, "Alder"));

        clockTo(ctx, other, 7 * beat);
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx),
                "both names known, company at the boundary — A proposes ending");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B's beat restarted on the proposal");

        clockTo(ctx, other, 8 * beat);
        assertEquals(TaskStatus.SUCCESS, taskB.tick(otherContext),
                "B's pending is request_end_chat, not ask_identity — priority 2 decides: "
                        + "pressure 0.0 at the content boundary ends it");
        assertEquals(TaskStatus.SUCCESS, taskA.tick(ctx), "the shared record now reads closed");

        Encounter e = ctx.speech.roster.closed().get(0);
        List<String> acts = e.transcript().stream().map(Utterance::act).toList();
        assertEquals(List.of(SpeechActs.HAIL.key(), SpeechActs.GREETING.key(), SpeechActs.GREETING.key(),
                PersonActs.ASK_IDENTITY.key(), PersonActs.INFORM_NAME.key(),
                PersonActs.ASK_IDENTITY.key(), PersonActs.INFORM_NAME.key(),
                SpeechActs.REQUEST_END_CHAT.key(), SpeechActs.END_CHAT.key()), acts,
                "GREETING and ASK_IDENTITY/INFORM_NAME both ways, then END_CHAT");
        assertTrue(e.closed());
        // Both sides settle the conversation in a handful of lines, nowhere near their own cap.
        assertTrue(ctx.speech.saidLines.size() < ctx.speech.caps.turnCap(),
                "A's own line count stays under the turn cap");
        assertTrue(otherSpeech.saidLines.size() < otherSpeech.caps.turnCap(),
                "B's own line count stays under the turn cap");
        // Task 5's finding, still true here: closing on a shared roster notifies only the closer.
        assertTrue(ctx.speech.closedRecords.isEmpty(), "A never closed anything itself");
        assertEquals(1, otherSpeech.closedRecords.size(), "B's own engine did the closing");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** Both bodies onto the same world tick — the two-party test moves one beat at a time. */
    private static void clockTo(FakeContext a, FakeContext b, long tick) {
        a.percepts.time = tick;
        b.percepts.time = tick;
    }

    /** A generator whose {@code nextInt} answers a fixed script, repeating its last value once
     *  exhausted — deterministic control over priority 6's roll without hand-deriving a seed. */
    private static RandomGenerator scripted(int... values) {
        return new RandomGenerator() {
            private int index = 0;

            @Override
            public long nextLong() {
                throw new UnsupportedOperationException("unused by this test");
            }

            @Override
            public int nextInt(int bound) {
                int value = values[Math.min(index, values.length - 1)];
                index++;
                return value;
            }
        };
    }

    /**
     * The second party's whole {@link BrainContext}: everything but {@link #speech()} borrowed
     * from a private {@link FakeContext}, since that field is {@code final} and cannot be pointed
     * at a roster shared with a different body. Copied from {@code ConverseTest}'s own fixture.
     */
    private static final class SecondSpeaker implements BrainContext {
        private final FakeContext body;
        private final Speech speech;

        SecondSpeaker(FakeContext body, Speech speech) {
            this.body = body;
            this.speech = speech;
        }

        @Override
        public ActuatorAccess actuators() {
            return body.actuators();
        }

        @Override
        public Percepts percepts() {
            return body.percepts();
        }

        @Override
        public AgentJournal journal() {
            return body.journal();
        }

        @Override
        public Pronouns pronouns() {
            return body.pronouns();
        }

        @Override
        public AgentProfile profile() {
            return body.profile();
        }

        @Override
        public AgentKnowledge knowledge() {
            return body.knowledge();
        }

        @Override
        public Speech speech() {
            return speech;
        }

        @Override
        public double costTolerance() {
            return body.costTolerance();
        }

        @Override
        public RandomGenerator random() {
            return body.random();
        }
    }
}
