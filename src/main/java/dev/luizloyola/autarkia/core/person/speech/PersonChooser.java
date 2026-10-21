package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.social.GroupUp;
import dev.luizloyola.anima.core.social.GroupUps;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Handover;
import dev.luizloyola.anima.core.social.speech.LineSlots;
import dev.luizloyola.anima.core.social.speech.Picker;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.autarkia.core.board.Explore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The part with the personality — a nineteen-rung ladder, first match wins, every rung gated on the
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
 *   <li>Asked for food: hold some out by {@link Sharing}'s rule — half to a colleague, a stranger
 *       only from a surplus — or say there is nothing to spare (decision: Luiz, 2026-10-02).</li>
 *   <li>Offered something: take it. Nothing is offered here unasked.</li>
 *   <li>Given something: food it asked for is thanked for; anything else is named as not wanted —
 *       not food, something besides the food, or nothing asked for at all — and the giver picks
 *       whether to take it back or leave it as a gift (decision: Luiz, 2026-10-02).</li>
 *   <li>Asked a question in small talk: answer it, on its topic ({@link Topics#reply}). A question
 *       obliges (decision: Luiz, 2026-10-02).</li>
 *   <li>Asked to join, or invited: yes while the rule still holds and the party is willing, read at
 *       this turn, else no with the strongest reason against (group-up spec).</li>
 *   <li>The leader named the spot they stand on: it will do, or what it lacks.</li>
 *   <li>The parties merged here and this body's stayed: say where home is, once.</li>
 *   <li>Already asked something of the counterpart that nothing of theirs has answered yet
 *       ({@link Turn#awaiting()}): hold — silence, not a second ask. Patience and the IGNORED
 *       snub already cover a counterpart who never replies; any discharging reply restores
 *       initiative on the very next turn.</li>
 *   <li>Say hello before anything else — unless something past the greetings has already been
 *       said, by either side: a hello after a request reads backwards (decision: Luiz, 2026-10-03).
 *       The engine folds that into {@link Turn#greeted()}.</li>
 *   <li>Talking to the player who leads its party, with a place found for it: put the place to them,
 *       once a record (group-up spec, decision 14).</li>
 *   <li>Peckish or worse, with no ready food on it: ask for some, once a record. Right after the
 *       greeting, whatever opened the chat — the hail is the same hail, and the first line after it
 *       is what says what the caller wanted (decision: Luiz, 2026-10-02).</li>
 *   <li>A counterpart seen but never introduced, who has not already given a name in this
 *       record, and whom this body has not already asked here: ask. Once per record — a
 *       deflection is an answer, and asking again after one is badgering.</li>
 *   <li>Names known both ways and the group-up rule allows it: ask to join, or invite. Once a
 *       record; a no paces the next ask through the kept records.</li>
 *   <li>Their small talk was the last thing said: reply on its topic, whatever the gauge says — a
 *       content settler that stood mute while the player chatted at it was the first client run's
 *       second finding (2026-09-13). A told day of theirs is answered with this body's own. A
 *       reply is never itself replied to: two content settlers trading answers talked out the
 *       whole turn cap (2026-09-14).</li>
 *   <li>Still wanting company: small talk, a remark or a question ({@link Topics#volunteer}) —
 *       only ever something not already brought up here, by either side ({@link Topics.Said}).</li>
 *   <li>Rung 17 would speak but has nothing new left to say: say goodbye. Running out of things to
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

        if (answersFoodAsk(turn)) {
            List<Handover.Item> items = share(ctx, turn);
            return items.isEmpty() || !turn.applicable().contains(SpeechActs.OFFER)
                    ? Line.of(SpeechActs.CANNOT_SPARE)
                    : new Line(SpeechActs.OFFER, Handover.payload(items));
        }

        if (takesOffer(turn)) {
            return Line.of(SpeechActs.ACCEPT_OFFER);
        }

        if (answersGift(turn)) {
            return judgeGift(ctx, turn);
        }

        if (answersQuestion(turn)) {
            return new Line(PersonActs.SMALL_TALK_REPLY, Topics.reply(ctx,
                    counterpartSeen(ctx, turn), saidHere(turn), turn.pending().orElseThrow()));
        }

        if (answersJoinAsk(turn)) {
            return answerJoin(ctx, turn);
        }

        if (judgesTheSpot(turn)) {
            return judgeTheSpot(ctx, turn);
        }

        if (tellsWhereHomeIs(ctx, turn)) {
            return whereHomeIs(ctx, turn);
        }

        if (isWaiting(turn)) {
            return null;    // already asked something — wait for the counterpart to answer it
        }

        if (greets(turn)) {
            return Line.of(SpeechActs.GREETING);
        }

        if (suggestsHome(ctx, turn)) {
            return whereThePlaceIs(ctx, turn);
        }

        if (asksForFood(ctx, turn)) {
            return Line.of(SpeechActs.ASK_FOOD);
        }

        if (asksTheirName(ctx, turn)) {
            return Line.of(PersonActs.ASK_IDENTITY);
        }

        if (asksToGroupUp(ctx, turn)) {
            return Line.of(groupUpAsk(ctx, turn).orElseThrow() == GroupUp.Ask.JOIN
                    ? SpeechActs.ASK_JOIN : SpeechActs.INVITE);
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
                new Rung(answersFoodAsk(turn), "3 answer a request for food",
                        "pending " + pendingKey(turn) + ", " + shareFacts(ctx, turn)),
                new Rung(takesOffer(turn), "4 take what is offered",
                        "pending " + pendingKey(turn) + ", accept_offer "
                                + offer(turn, SpeechActs.ACCEPT_OFFER)),
                new Rung(answersGift(turn), "5 judge a gift",
                        "pending " + pendingKey(turn) + ", " + giftFacts(ctx, turn)),
                new Rung(answersQuestion(turn), "6 answer a question",
                        "pending " + pendingKey(turn) + ", small_talk_reply "
                                + offer(turn, PersonActs.SMALL_TALK_REPLY)),
                new Rung(answersJoinAsk(turn), "7 answer an ask to group up",
                        "pending " + pendingKey(turn) + ", accept_join " + offer(turn, SpeechActs.ACCEPT_JOIN)),
                new Rung(judgesTheSpot(turn), "8 judge the spot the leader names",
                        "pending " + pendingKey(turn)),
                new Rung(tellsWhereHomeIs(ctx, turn), "9 say where home is", homeFacts(ctx, turn)),
                new Rung(isWaiting(turn), "10 hold while awaiting an answer", waitingFacts(turn)),
                new Rung(greets(turn), "11 greet",
                        (turn.greeted() ? "greeted, or past the greetings" : "not greeted yet")
                                + ", greeting " + offer(turn, SpeechActs.GREETING)),
                new Rung(suggestsHome(ctx, turn), "12 put a found place to the leader",
                        homeSuggestion(ctx, turn).map(plot -> "found " + plot.x() + ", " + plot.z())
                                .orElse("nothing to put to them")),
                new Rung(asksForFood(ctx, turn), "13 ask for food", foodFacts(ctx, turn)),
                new Rung(asksTheirName(ctx, turn), "14 ask their name", nameFacts(ctx, turn)
                        + (alreadyAsked(turn) ? ", already asked here" : ", not asked here yet")
                        + ", ask_identity " + offer(turn, PersonActs.ASK_IDENTITY)),
                new Rung(asksToGroupUp(ctx, turn), "15 ask to group up",
                        groupUpAsk(ctx, turn).map(ask -> "would " + (ask == GroupUp.Ask.JOIN ? "ask_join" : "invite"))
                                .orElse("no ask the rule allows")
                                + (said(turn, SpeechActs.ASK_JOIN) || said(turn, SpeechActs.INVITE)
                                        ? ", already asked here" : "")),
                new Rung(repliesToSmallTalk(turn), "16 reply to their small talk",
                        "last said " + Picker.lastSpoken(turn.encounter())
                                .map(line -> (theirs(turn, line) ? "theirs " : "ours ") + line.act())
                                .orElse("nothing")
                                + ", small_talk_reply " + offer(turn, PersonActs.SMALL_TALK_REPLY)),
                new Rung(makesSmallTalk(ctx, turn), "17 small talk", "company " + number(company)
                        + (company < boundary ? " < " : " ≥ ") + "content " + number(boundary)
                        + ", small_talk " + offer(turn, PersonActs.SMALL_TALK)),
                new Rung(runsOutOfThingsToSay(ctx, turn), "18 out of things to say",
                        saidFacts(ctx, turn) + ", end_chat " + offer(turn, SpeechActs.END_CHAT)),
                new Rung(saysGoodbye(ctx, turn), "19 say goodbye",
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

    /** Rung 3: they asked for food. Turning it down is always on offer; holding some out may not be. */
    private static boolean answersFoodAsk(Turn turn) {
        return pendingIs(turn, SpeechActs.ASK_FOOD)
                && turn.applicable().contains(SpeechActs.CANNOT_SPARE);
    }

    /** Rung 4: something is held out to this body. */
    private static boolean takesOffer(Turn turn) {
        return pendingIs(turn, SpeechActs.OFFER) && turn.applicable().contains(SpeechActs.ACCEPT_OFFER);
    }

    /** Rung 5: something was handed over to this body, and is owed a verdict. */
    private static boolean answersGift(Turn turn) {
        return pendingIs(turn, SpeechActs.GIVE) && turn.applicable().contains(SpeechActs.ACCEPT_OFFER)
                && turn.applicable().contains(SpeechActs.NOT_WANTED);
    }

    /** What was not wanted — the {@link Gift} the given line comes to — or a thank-you. */
    private static Line judgeGift(BrainContext ctx, Turn turn) {
        Gift gift = gift(ctx, turn);
        if (gift.unwanted().isEmpty()) {
            return Line.of(SpeechActs.ACCEPT_OFFER);
        }
        Map<String, String> payload = new LinkedHashMap<>(Handover.payload(gift.unwanted()));
        payload.put(Utterance.TOPIC, gift.why());
        return new Line(SpeechActs.NOT_WANTED, payload);
    }

    /** A gift as this body sees it: what it does not want, and why — {@code not_food}, {@code mixed}, {@code unasked}. */
    private record Gift(List<Handover.Item> unwanted, String why) {
    }

    private static Gift gift(BrainContext ctx, Turn turn) {
        List<Handover.Item> items = turn.pending().map(u -> Handover.read(u.payload())).orElse(List.of());
        if (!said(turn, SpeechActs.ASK_FOOD)) {
            return new Gift(items, "unasked");
        }
        FoodLookup foods = ctx.percepts().foods();
        List<Handover.Item> notFood = new ArrayList<>();
        for (Handover.Item item : items) {
            if (foods.of(ItemStack.of(item.id(), 1, 64)).isEmpty()) {
                notFood.add(item);
            }
        }
        return new Gift(List.copyOf(notFood), notFood.size() == items.size() ? "not_food" : "mixed");
    }

    /** What rung 5 tells the readout. */
    private static String giftFacts(BrainContext ctx, Turn turn) {
        Gift gift = gift(ctx, turn);
        return gift.unwanted().isEmpty() ? "all wanted" : gift.why() + " " + gift.unwanted();
    }

    /** Rung 13: greeted, peckish or worse, nothing ready to eat on it, and not yet asked here. */
    private static boolean asksForFood(BrainContext ctx, Turn turn) {
        return turn.greeted() && turn.applicable().contains(SpeechActs.ASK_FOOD)
                && peckish(ctx) && carried(ctx).isEmpty() && !said(turn, SpeechActs.ASK_FOOD);
    }

    /** At or under {@code peckish}'s anchor — the line a player's own button is offered at too. */
    private static boolean peckish(BrainContext ctx) {
        return ctx.percepts().needs().value(NeedKind.HUNGER)
                <= NeedKind.HUNGER.level("peckish").orElseThrow().value(ctx.profile());
    }

    /** What to hold out to whoever asked, by {@link Sharing}'s rule. */
    private static List<Handover.Item> share(BrainContext ctx, Turn turn) {
        boolean colleague = turn.counterpart().map(ctx::colleague).orElse(false);
        return Sharing.share(carried(ctx), colleague,
                ctx.profile().d(ProfileAspect.SOCIAL_SHARE_FRACTION));
    }

    /** The ready food in this body's storage, by kind. */
    private static List<Sharing.Food> carried(BrainContext ctx) {
        FoodLookup foods = ctx.percepts().foods();
        Inventory pack = ctx.percepts().inventory();
        Map<String, Sharing.Food> byId = new LinkedHashMap<>();
        for (int slot = Inventory.HOTBAR_START; slot < Inventory.ARMOR_START; slot++) {
            ItemStack stack = pack.get(slot);
            if (stack.isEmpty() || !ReadyFood.isReady(foods, stack)) {
                continue;
            }
            int nutrition = foods.of(stack).map(FoodValue::nutrition).orElse(0);
            byId.merge(stack.id(), new Sharing.Food(stack.id(), stack.count(), nutrition),
                    (a, b) -> new Sharing.Food(a.id(), a.count() + b.count(), a.nutrition()));
        }
        return List.copyOf(byId.values());
    }

    /** Whether this body has said {@code act} in this record. */
    private static boolean said(Turn turn, SpeechAct act) {
        AgentId counterpart = turn.counterpart().orElse(null);
        for (Utterance line : turn.encounter().transcript()) {
            if (!line.system() && !line.author().equals(counterpart) && line.act().equals(act.key())) {
                return true;
            }
        }
        return false;
    }

    /** Rung 6: a question of theirs is pending, and a reply is on offer. */
    private static boolean answersQuestion(Turn turn) {
        return pendingIs(turn, PersonActs.SMALL_TALK_QUESTION)
                && turn.applicable().contains(PersonActs.SMALL_TALK_REPLY);
    }

    /**
     * Rung 16: the last thing said was a remark of theirs — a question is rung 6's, and a reply is
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

    /** Rung 18: rung 17's reason to speak holds, and everything this body could say has been said. */
    private static boolean runsOutOfThingsToSay(BrainContext ctx, Turn turn) {
        return wouldChat(ctx, turn)
                && !Topics.anythingLeft(ctx, counterpartSeen(ctx, turn), saidHere(turn))
                && turn.applicable().contains(SpeechActs.END_CHAT);
    }

    /** Rung 17's reason to speak, before asking whether there is anything new to say. */
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

    /** The act owed by this body, or {@code none} — the fact rungs 1 to 6 turn on. */
    private static String pendingKey(Turn turn) {
        return turn.pending().map(Utterance::act).orElse("none");
    }

    /** What rung 3 tells the readout: what would be held out, and to whom. */
    private static String shareFacts(BrainContext ctx, Turn turn) {
        boolean colleague = turn.counterpart().map(ctx::colleague).orElse(false);
        List<Handover.Item> items = share(ctx, turn);
        return (colleague ? "a colleague" : "a stranger") + ", would give "
                + (items.isEmpty() ? "nothing" : items.toString());
    }

    /** What rung 13 tells the readout. */
    private static String foodFacts(BrainContext ctx, Turn turn) {
        return "hunger " + number(ctx.percepts().needs().value(NeedKind.HUNGER))
                + (peckish(ctx) ? " peckish" : " fed") + ", " + carried(ctx).size()
                + " ready kind(s) on it" + (said(turn, SpeechActs.ASK_FOOD) ? ", already asked here" : "")
                + ", ask_food " + offer(turn, SpeechActs.ASK_FOOD);
    }

    /** What rung 10 tells the readout — the act SELF is still owed no answer to, if any. */
    private static String waitingFacts(Turn turn) {
        return turn.awaiting()
                .map(asked -> "waiting: " + asked.act() + ", ball is theirs")
                .orElse("nothing outstanding");
    }

    /** What rung 18 tells the readout: how much has been said here, and whether anything is left. */
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
     * read, spelled out — which of them refused rung 14 is the whole question when a settler will
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
     * what was asked: rung 1 answers a question about its name, rung 2 a goodbye, rungs 3 to 6 a
     * request, an offer, a gift and a question, and none may grab an obligation that happens to leave its line technically
     * applicable.
     */
    private static boolean pendingIsAskIdentity(Turn turn) {
        return pendingIs(turn, PersonActs.ASK_IDENTITY);
    }

    // ── grouping up (2026-10-21-group-up-design.md) ──────────────────────────────────────────

    /** Rung 7: asked to join, or invited. A question obliges. */
    private static boolean answersJoinAsk(Turn turn) {
        return (pendingIs(turn, SpeechActs.ASK_JOIN) || pendingIs(turn, SpeechActs.INVITE))
                && turn.applicable().contains(SpeechActs.REFUSE_JOIN);
    }

    /**
     * Yes when the rule still holds and the party is willing, read at this turn — a party that has
     * settled or filled up since the ask refuses — else no, naming the strongest reason against.
     */
    private static Line answerJoin(BrainContext ctx, Turn turn) {
        Utterance ask = turn.pending().orElseThrow();
        GroupUp.Ask kind = ask.act().equals(SpeechActs.ASK_JOIN.key()) ? GroupUp.Ask.JOIN : GroupUp.Ask.INVITE;
        AgentId self = ctx.self();
        Optional<String> refusal = self == null || ask.author() == null ? Optional.of("not_now")
                : GroupUps.get().refusal(self, ask.author(), kind);
        if (refusal.isEmpty() && turn.applicable().contains(SpeechActs.ACCEPT_JOIN)) {
            return Line.of(SpeechActs.ACCEPT_JOIN);
        }
        return new Line(SpeechActs.REFUSE_JOIN, Map.of(Utterance.TOPIC, refusal.orElse("not_now")));
    }

    /** Rung 8: the leader said "Ok, here" — the spot they stand on is judged as a plot would be. */
    private static boolean judgesTheSpot(Turn turn) {
        return pendingIs(turn, SpeechActs.HERE) && turn.applicable().contains(SpeechActs.NOT_HERE);
    }

    private static Line judgeTheSpot(BrainContext ctx, Turn turn) {
        AgentId self = ctx.self();
        Pos at = counterpartSeen(ctx, turn).map(Being::pos).orElse(ctx.percepts().position());
        Optional<String> why = self == null ? Optional.of("poor") : Explore.judgeHere(self, at, ctx.knowledge());
        if (why.isEmpty() && turn.applicable().contains(SpeechActs.HERE_WILL_DO)) {
            return Line.of(SpeechActs.HERE_WILL_DO);
        }
        return new Line(SpeechActs.NOT_HERE, Map.of(Utterance.TOPIC, why.orElse("poor")));
    }

    /**
     * Rung 9: the parties merged here, and this body's is the one that stayed — it says where home
     * is, once (decision 20). The mover is whoever an ask to join came from, or whoever was invited.
     */
    private static boolean tellsWhereHomeIs(BrainContext ctx, Turn turn) {
        return turn.applicable().contains(SpeechActs.INFORM_HOME) && !said(turn, SpeechActs.INFORM_HOME)
                && ctx.depot().isPresent() && turn.counterpart().filter(ctx::colleague).isPresent()
                && mover(turn).equals(turn.counterpart());
    }

    /** Who moved in the merge this record made, if it made one. */
    private static Optional<AgentId> mover(Turn turn) {
        List<Utterance> lines = turn.encounter().transcript();
        Utterance ask = null;
        for (Utterance line : lines) {
            if (line.system()) {
                continue;
            }
            if (line.act().equals(SpeechActs.ASK_JOIN.key()) || line.act().equals(SpeechActs.INVITE.key())) {
                ask = line;
            } else if (line.act().equals(SpeechActs.ACCEPT_JOIN.key()) && ask != null) {
                return Optional.of(ask.act().equals(SpeechActs.ASK_JOIN.key()) ? ask.author() : line.author());
            }
        }
        return Optional.empty();
    }

    /** Where home lies from the newcomer, by compass point and blocks. */
    private static Line whereHomeIs(BrainContext ctx, Turn turn) {
        Pos home = ctx.depot().orElseThrow().hint();
        Pos from = counterpartSeen(ctx, turn).map(Being::pos).orElse(ctx.percepts().position());
        int dx = home.x() - from.x();
        int dz = home.z() - from.z();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        return new Line(SpeechActs.INFORM_HOME, LineSlots.with(Map.of(), List.of(
                Slot.lang("anima.compass." + compass(dx, dz)), Slot.name(Integer.toString(distance)))));
    }

    /** The eighth of the compass {@code (dx, dz)} points into; north is −z. */
    static String compass(int dx, int dz) {
        String[] points = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        double heading = Math.toDegrees(Math.atan2(dx, -dz));
        return points[Math.floorMod(Math.round(heading / 45.0), 8)];
    }

    /**
     * Rung 12: talking to the player who leads this body's party, with a place found for it — put it
     * to them, once a record, after the greeting (decision 14). They settle it or say keep looking.
     */
    private static boolean suggestsHome(BrainContext ctx, Turn turn) {
        return turn.greeted() && turn.applicable().contains(SpeechActs.SUGGEST_HOME)
                && !said(turn, SpeechActs.SUGGEST_HOME) && homeSuggestion(ctx, turn).isPresent();
    }

    private static Optional<Pos> homeSuggestion(BrainContext ctx, Turn turn) {
        AgentId self = ctx.self();
        return self == null ? Optional.empty()
                : turn.counterpart().flatMap(leader -> Explore.suggestionFor(self, leader));
    }

    /** The place, from where the leader stands, by compass point and blocks. */
    private static Line whereThePlaceIs(BrainContext ctx, Turn turn) {
        Pos plot = homeSuggestion(ctx, turn).orElseThrow();
        Pos from = counterpartSeen(ctx, turn).map(Being::pos).orElse(ctx.percepts().position());
        int dx = plot.x() - from.x();
        int dz = plot.z() - from.z();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        return new Line(SpeechActs.SUGGEST_HOME, LineSlots.with(Map.of(), List.of(
                Slot.lang("anima.compass." + compass(dx, dz)), Slot.name(Integer.toString(distance)))));
    }

    /** Rung 15: names known, greeted, not yet asked here, and the rule allows an ask. */
    private static boolean asksToGroupUp(BrainContext ctx, Turn turn) {
        return turn.greeted() && !said(turn, SpeechActs.ASK_JOIN) && !said(turn, SpeechActs.INVITE)
                && groupUpAsk(ctx, turn).filter(ask -> turn.applicable().contains(
                        ask == GroupUp.Ask.JOIN ? SpeechActs.ASK_JOIN : SpeechActs.INVITE)).isPresent();
    }

    private static Optional<GroupUp.Ask> groupUpAsk(BrainContext ctx, Turn turn) {
        AgentId self = ctx.self();
        return self == null ? Optional.empty()
                : turn.counterpart().flatMap(other -> GroupUps.get().ask(self, other));
    }

    private static String homeFacts(BrainContext ctx, Turn turn) {
        return (ctx.depot().isPresent() ? "home known" : "no home") + ", merged here "
                + mover(turn).map(who -> who.equals(turn.counterpart().orElse(null)) ? "(they moved)" : "(we moved)")
                        .orElse("no") + (said(turn, SpeechActs.INFORM_HOME) ? ", already told" : "");
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
        return said(turn, PersonActs.ASK_IDENTITY);
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
