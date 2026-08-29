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
 * The six-priority ladder, one test per rung, plus a full two-party conversation driven by real
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
        return ctx.speech.join(otherId, Speech.Opening.QUIET);
    }

    // ── priority 1: something is owed ────────────────────────────────────────────────────────

    @Test
    @DisplayName("priority 1: pending with inform_name applicable — v1 always shares")
    void priority1SharesTheNameWhenAsked() {
        Utterance ask = new Utterance(otherId.asPerson(), PersonActs.ASK_IDENTITY.key(), Map.of(), 0);
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.DEFLECT, PersonActs.INFORM_NAME), Optional.of(ask), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(PersonActs.INFORM_NAME), chooser.choose(ctx, turn));
    }

    @Test
    @DisplayName("priority 1 is gated: pending with inform_name NOT applicable falls through")
    void priority1FallsThroughWhenInformNameIsNotOnOffer() {
        ctx.percepts.company.setValue(0.5); // below the content boundary — priority 5 will catch it
        Utterance ask = new Utterance(otherId.asPerson(), PersonActs.ASK_IDENTITY.key(), Map.of(), 0);
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.DEFLECT, PersonActs.SMALL_TALK), Optional.of(ask), true,
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
                Optional.of(ask), true, Optional.of(otherId.asPerson()));

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
                Optional.of(ask), true, Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act(),
                "the discharge is any self line — small talk legitimately answers the proposal, "
                        + "and must win over inform_name");
        assertTrue(Topics.options(ctx).contains(line.payload().get("topic")));
    }

    // ── priority 3: greet first ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("priority 3: not yet greeted — greets before anything else")
    void priority3GreetsFirst() {
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.GREETING, PersonActs.SMALL_TALK), Optional.empty(), false,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(SpeechActs.GREETING), chooser.choose(ctx, turn));
    }

    // ── priority 4: ask an unintroduced counterpart's name ───────────────────────────────────

    @Test
    @DisplayName("priority 4: counterpart perceived at INDIVIDUAL with an empty name — asks")
    void priority4AsksAnUnintroducedCounterpart() {
        ctx.percepts.beings = List.of(FakePercepts.personAt(otherId, new Pos(4, 64, 0), 4.0, ""));
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.ASK_IDENTITY, PersonActs.SMALL_TALK), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(PersonActs.ASK_IDENTITY), chooser.choose(ctx, turn));
    }

    @Test
    @DisplayName("priority 4 is skipped once the counterpart is already named")
    void priority4SkipsWhenTheCounterpartIsAlreadyNamed() {
        ctx.percepts.company.setValue(0.5); // below content — falls to priority 5
        ctx.percepts.beings = List.of(FakePercepts.personAt(otherId, new Pos(4, 64, 0), 4.0, "Bramble"));
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.ASK_IDENTITY, PersonActs.SMALL_TALK), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act(), "the name is already known — nothing to ask");
    }

    @Test
    @DisplayName("priority 4 is skipped when the counterpart is not currently perceived")
    void priority4SkipsWhenTheCounterpartIsNotPerceived() {
        ctx.percepts.company.setValue(0.5); // ctx.percepts.beings stays empty — nobody in sight
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.ASK_IDENTITY, PersonActs.SMALL_TALK), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act(), "can't ask a name of someone you can't see");
    }

    // ── priority 5: still wanting company ────────────────────────────────────────────────────

    @Test
    @DisplayName("priority 5: company value below the content boundary — makes small talk")
    void priority5MakesSmallTalkWhenCompanyWantsMore() {
        ctx.percepts.company.setValue(0.5); // below 0.85
        // Only SMALL_TALK on offer, so the 1-in-4 roll can never find a substitute — this test
        // pins the base branch regardless of which way the roll falls.
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(), List.of(PersonActs.SMALL_TALK),
                Optional.empty(), true, Optional.of(otherId.asPerson()));

        Chooser.Line line = chooser.choose(ctx, turn);

        assertEquals(PersonActs.SMALL_TALK, line.act());
        assertTrue(Topics.options(ctx).contains(line.payload().get("topic")));
    }

    @Test
    @DisplayName("priority 5's 1-in-4 roll substitutes a different applicable non-end act on a hit")
    void priority5RollSubstitutesVarietyOnAHit() {
        ctx.percepts.company.setValue(0.5);
        ctx.seed(scripted(0, 0)); // hits the 1-in-4, then picks index 0 of the one alternative
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.SMALL_TALK, PersonActs.ASK_IDENTITY), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(PersonActs.ASK_IDENTITY), chooser.choose(ctx, turn));
    }

    @Test
    @DisplayName("priority 5's roll leaves small talk alone on a miss")
    void priority5RollMissKeepsSmallTalk() {
        ctx.percepts.company.setValue(0.5);
        ctx.seed(scripted(1)); // misses the 1-in-4
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.SMALL_TALK, PersonActs.ASK_IDENTITY), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(PersonActs.SMALL_TALK, chooser.choose(ctx, turn).act());
    }

    @Test
    @DisplayName("priority 5's roll never reaches for the door or deflects nothing pending")
    void priority5RollNeverPicksRequestEndChatOrDeflect() {
        ctx.percepts.company.setValue(0.5); // below the boundary — priority 5 governs
        // A realistic mid-conversation applicable set: greeted, no pending, nothing constrained.
        List<SpeechAct> applicable = List.of(SpeechActs.GREETING, SpeechActs.DEFLECT,
                SpeechActs.REQUEST_END_CHAT, PersonActs.ASK_IDENTITY, PersonActs.INFORM_NAME,
                PersonActs.SMALL_TALK);
        Set<SpeechAct> picked = new HashSet<>();
        // Force the 1-in-4 hit and sweep every position the variety pick could land on.
        for (int index = 0; index < 3; index++) {
            ctx.seed(scripted(0, index));
            Chooser.Turn turn = new Chooser.Turn(freshEncounter(), applicable, Optional.empty(),
                    true, Optional.of(otherId.asPerson()));

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
    @DisplayName("priority 5 does not fire at or above the content boundary — falls to priority 6")
    void priority5DoesNotFireAtOrAboveTheBoundary() {
        ctx.percepts.company.setValue(0.85); // exactly the boundary — not below it
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(PersonActs.SMALL_TALK, SpeechActs.REQUEST_END_CHAT), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(SpeechActs.REQUEST_END_CHAT), chooser.choose(ctx, turn));
    }

    // ── priority 6: nothing pressing ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("priority 6: greeted, known, company more than enough — proposes leaving")
    void priority6ProposesEndingWhenCompanyIsMoreThanEnough() {
        ctx.percepts.company.setValue(1.0); // crowded
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(),
                List.of(SpeechActs.REQUEST_END_CHAT), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertEquals(Chooser.Line.of(SpeechActs.REQUEST_END_CHAT), chooser.choose(ctx, turn));
    }

    @Test
    @DisplayName("silence when nothing is applicable at all")
    void returnsNullWhenNothingIsApplicable() {
        Chooser.Turn turn = new Chooser.Turn(freshEncounter(), List.of(), Optional.empty(), true,
                Optional.of(otherId.asPerson()));

        assertNull(chooser.choose(ctx, turn));
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

        ctx.percepts.time = 0;
        other.percepts.time = 0;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A hails and greets in the same tick");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B waits out the reply grace");

        ctx.percepts.time = 20;
        other.percepts.time = 20;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A already spoke twice running — silent");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B's grace has elapsed — greets back");

        ctx.percepts.time = 40;
        other.percepts.time = 40;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A's grace has elapsed — asks B's name");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B owes a reply — shares its name");

        // Production fills a Being's name from the sensor watching INFORM_NAME land; the fake has
        // no live sensor, so the test plays that part: A now knows B is "Bramble".
        ctx.percepts.beings = List.of(FakePercepts.personAt(BeingId.of(other.self), here, 4.0, "Bramble"));

        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext),
                "B is still under its own consecutive cap — asks A's name right back");
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A owes a reply — shares its name");

        // Same, the other way: B now knows A is "Alder".
        other.percepts.beings = List.of(FakePercepts.personAt(BeingId.of(ctx.self), here, 4.0, "Alder"));

        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx),
                "both names known, company at the boundary — A proposes ending");
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

    /** A generator whose {@code nextInt} answers a fixed script, repeating its last value once
     *  exhausted — deterministic control over priority 5's roll without hand-deriving a seed. */
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
