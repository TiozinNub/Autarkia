package dev.luizloyola.autarkia.mod.command;

import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Developer/admin commands for inspecting Autarkia state.
 *
 * <p>{@code whois} prints a Person's identity (id + name), read straight from the server-side
 * {@link PersonDirectory}: the name is never synced to clients.
 */
public final class AutarkiaCommands {
    private AutarkiaCommands() {}

    private static final double NEAREST_RADIUS = 32.0;

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("autarkia")
                        .then(Commands.literal("whois")
                                .executes(ctx -> whoisNearest(ctx.getSource()))
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> whoisTargets(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "targets")))))));
    }

    private static int whoisNearest(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        AABB box = AABB.ofSize(origin, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2);
        Person nearest = level.getEntitiesOfClass(Person.class, box).stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .orElse(null);
        if (nearest == null) {
            source.sendFailure(Component.literal(
                    "No Person within " + (int) NEAREST_RADIUS + " blocks."));
            return 0;
        }
        report(source, nearest);
        return 1;
    }

    private static int whoisTargets(CommandSourceStack source,
                                    java.util.Collection<? extends Entity> targets) {
        List<Person> persons = targets.stream().filter(e -> e instanceof Person).map(e -> (Person) e).toList();
        if (persons.isEmpty()) {
            source.sendFailure(Component.literal("No Person among the selected entities."));
            return 0;
        }
        persons.forEach(person -> report(source, person));
        return persons.size();
    }

    private static void report(CommandSourceStack source, Person person) {
        PersonId id = person.getPersonId();
        if (id == null) {
            source.sendSuccess(() -> Component.literal("Person not yet identified (spawning).")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        PersonIdentity identity = PersonDirectory.get(source.getServer()).find(id).orElse(null);
        if (identity == null) {
            source.sendSuccess(() -> Component.literal(id + "  <unknown>").withStyle(ChatFormatting.GRAY), false);
            return;
        }
        Appearance appearance = identity.appearance();
        // Full tier (name) + external tier (gender, skin) + the stable id.
        ChatFormatting genderColor = appearance.gender().choose(ChatFormatting.BLUE, ChatFormatting.LIGHT_PURPLE);
        MutableComponent line = Component.literal(identity.name()).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" " + appearance.gender()).withStyle(genderColor))
                .append(Component.literal(" " + appearance.model()).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal("  " + appearance.skin()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  " + id).withStyle(ChatFormatting.DARK_GRAY));
        source.sendSuccess(() -> line, false);
    }
}
