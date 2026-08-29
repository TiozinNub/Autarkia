package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The part with the personality — v1's six-rung ladder, first match wins, every rung gated on
 * the act being in {@link Turn#applicable()}: the picker filters, this only wants.
 *
 * <ol>
 *   <li>Asked its identity — v1 never deflects, it always shares its name. Gated on the pending
 *       act specifically being {@code ask_identity}, not just any obligation: {@code
 *       request_end_chat} leaves {@code inform_name} technically applicable too (its responses
 *       are unconstrained), and priority 2 needs the chance to actually answer it.</li>
 *   <li>Answering a proposal to end: leave once company stops pressing, otherwise raise a new
 *       topic — any self line discharges the obligation, so small talk legitimately answers it.</li>
 *   <li>Say hello before anything else.</li>
 *   <li>A counterpart seen but never introduced: ask.</li>
 *   <li>Still wanting company: small talk, usually — a 1-in-4 roll varies the line.</li>
 *   <li>Nothing pressing: propose leaving.</li>
 * </ol>
 */
public final class PersonChooser implements Chooser {

    @Override
    public @Nullable Line choose(BrainContext ctx, Turn turn) {
        List<SpeechAct> applicable = turn.applicable();

        if (pendingIsAskIdentity(turn) && applicable.contains(PersonActs.INFORM_NAME)) {
            return Line.of(PersonActs.INFORM_NAME);
        }

        if (pendingIsRequestEndChat(turn)) {
            if (ctx.percepts().needs().pressure(NeedKind.COMPANY) == 0.0
                    && applicable.contains(SpeechActs.END_CHAT)) {
                return Line.of(SpeechActs.END_CHAT);
            }
            if (applicable.contains(PersonActs.SMALL_TALK)) {
                return new Line(PersonActs.SMALL_TALK, Topics.pick(ctx));
            }
        }

        if (!turn.greeted() && applicable.contains(SpeechActs.GREETING)) {
            return Line.of(SpeechActs.GREETING);
        }

        if (applicable.contains(PersonActs.ASK_IDENTITY) && isUnintroducedCounterpart(ctx, turn)) {
            return Line.of(PersonActs.ASK_IDENTITY);
        }

        double boundary = NeedKind.COMPANY.level("content").orElseThrow().value(ctx.profile());
        if (ctx.percepts().needs().value(NeedKind.COMPANY) < boundary
                && applicable.contains(PersonActs.SMALL_TALK)) {
            // 1-in-4: a settler that only ever says the same thing reads as scripted, not alive.
            if (ctx.random().nextInt(4) == 0) {
                List<SpeechAct> variety = varietyOf(applicable);
                if (!variety.isEmpty()) {
                    return Line.of(variety.get(ctx.random().nextInt(variety.size())));
                }
            }
            return new Line(PersonActs.SMALL_TALK, Topics.pick(ctx));
        }

        if (applicable.contains(SpeechActs.REQUEST_END_CHAT)) {
            return Line.of(SpeechActs.REQUEST_END_CHAT);
        }
        return null;
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

    private static Optional<Being> findBeing(BrainContext ctx, BeingId id) {
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(id)) {
                return Optional.of(being);
            }
        }
        return Optional.empty();
    }

    /** Every applicable act that could stand in for small talk — never one that ends the chat. */
    private static List<SpeechAct> varietyOf(List<SpeechAct> applicable) {
        List<SpeechAct> options = new ArrayList<>();
        for (SpeechAct act : applicable) {
            if (act != PersonActs.SMALL_TALK && !act.ends()) {
                options.add(act);
            }
        }
        return options;
    }
}
