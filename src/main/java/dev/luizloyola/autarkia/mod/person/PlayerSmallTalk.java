package dev.luizloyola.autarkia.mod.person;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Picker;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.brain.SurroundingsReader;
import dev.luizloyola.anima.mod.net.ContactsSync;
import dev.luizloyola.anima.mod.social.PlayerTopics;
import dev.luizloyola.autarkia.core.person.speech.PersonActs;
import dev.luizloyola.autarkia.core.person.speech.Topics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

/**
 * A player's small talk, read off the player the way a settler's is read off its percepts
 * (decision: Luiz, 2026-09-24): the sky over them, their pack, their hunger, health and breath,
 * and the settler in front of them — never what either side already brought up in this chat, and
 * an answer on the topic of whatever the settler last said (2026-10-02).
 */
public final class PlayerSmallTalk implements PlayerTopics.Source {

    /** The hotbar and the main pack — the storage a settler's pack is measured over too. */
    private static final int STORAGE = 36;

    @Override
    public Optional<Map<String, String>> pick(MinecraftServer server, ServerPlayer player,
            Encounter e, SpeechAct act) {
        Topics.Speaker speaker = speaker(server, player, e);
        Topics.Said said = said(player, e);
        if (act == PersonActs.SMALL_TALK_REPLY) {
            return toAnswer(player, e).map(line -> Topics.reply(speaker, said, line));
        }
        // The JDK's shared generator: a player has no stream of chance of their own.
        return act == PersonActs.SMALL_TALK_QUESTION
                ? Topics.question(speaker, said, RandomGenerator.getDefault())
                : Topics.pick(speaker, said, RandomGenerator.getDefault());
    }

    @Override
    public boolean anythingLeft(MinecraftServer server, ServerPlayer player, Encounter e,
            SpeechAct act) {
        if (act == PersonActs.SMALL_TALK_REPLY) {
            return toAnswer(player, e).isPresent();
        }
        Topics.Speaker speaker = speaker(server, player, e);
        return act == PersonActs.SMALL_TALK_QUESTION
                ? Topics.anythingToAsk(speaker, said(player, e))
                : Topics.anythingLeft(speaker, said(player, e));
    }

    /**
     * The settler's small talk the player has yet to answer — the last line said, when it is a remark
     * or a question of theirs. A reply is never answered, and neither is the player's own line.
     */
    private static Optional<Utterance> toAnswer(ServerPlayer player, Encounter e) {
        AgentId self = ContactsSync.idOf(player);
        return Picker.lastSpoken(e).filter(line -> !self.equals(line.author())
                && (line.act().equals(PersonActs.SMALL_TALK.key())
                        || line.act().equals(PersonActs.SMALL_TALK_QUESTION.key())));
    }

    private static Topics.Said said(ServerPlayer player, Encounter e) {
        AgentId self = ContactsSync.idOf(player);
        return Topics.Said.in(e.transcript(), line -> self.equals(line.author()));
    }

    /** The player as a speaker. No deeds: a player's day is theirs to tell, not the seat's. */
    private static Topics.Speaker speaker(MinecraftServer server, ServerPlayer player,
            Encounter e) {
        Optional<Topics.Them> them = e.other(ContactsSync.idOf(player))
                .map(other -> AgentBodies.findLoaded(server, other))
                .map(AgentBody::entity)
                .map(PlayerSmallTalk::seen);
        return new Topics.Speaker(Optional.of(SurroundingsReader.of(player)), them, pack(player),
                Topics.needsShown(player.getFoodData().getFoodLevel(), player.getHealth(),
                        player.getMaxHealth(), player.getAirSupply(), player.getMaxAirSupply()),
                List.of(), server.overworld().getGameTime());
    }

    /** The settler as the player sees it — what is in its hand, its posture, whether it is eating. */
    private static Topics.Them seen(LivingEntity body) {
        ItemStack held = body.getMainHandItem();
        boolean eating = body.isUsingItem()
                && body.getUseItem().getUseAnimation() == ItemUseAnimation.EAT;
        return new Topics.Them(held.isEmpty() ? "" : idOf(held), body.isCrouching(), eating);
    }

    private static Topics.Pack pack(ServerPlayer player) {
        return new Topics.Pack() {
            @Override
            public int slotsUsed() {
                int used = 0;
                for (int slot = 0; slot < STORAGE; slot++) {
                    if (!player.getInventory().getItem(slot).isEmpty()) {
                        used++;
                    }
                }
                return used;
            }

            @Override
            public int count(Predicate<String> ids) {
                int total = 0;
                for (int slot = 0; slot < STORAGE; slot++) {
                    ItemStack stack = player.getInventory().getItem(slot);
                    if (!stack.isEmpty() && ids.test(idOf(stack))) {
                        total += stack.getCount();
                    }
                }
                return total;
            }
        };
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
