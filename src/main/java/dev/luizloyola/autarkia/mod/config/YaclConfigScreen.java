package dev.luizloyola.autarkia.mod.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The optional YACL config screen — a category per {@link Knob#section()}, an option per knob, built
 * from the enum.
 *
 * <p><b>YACL is used here for the GUI only.</b> {@link ConfigFile} keeps ownership of
 * {@code config/autarkia.json} for three things YACL's config API does not give: an atomic
 * tmp-and-rename write, unknown-key reporting, and the regenerated {@code "// name"} doc lines.
 * Values live in the immutable {@link AutarkiaConfig} behind {@link Config}, whose volatile
 * whole-object swap makes a reload safe for the off-thread pathfinder.
 *
 * <p><b>Must not be loaded unless YACL is installed</b> — it names {@code dev.isxander.*} types
 * directly, so touching it without the library is a {@link NoClassDefFoundError}, and
 * {@link AutarkiaModMenu} is the only caller and checks first. {@code modCompileOnly}, never shipped.
 *
 * <p>Controller ranges only refuse illegal input up front; {@link AutarkiaConfig#with} clamps again
 * on the way in, as it does for a hand-edited file.
 */
final class YaclConfigScreen {

    private YaclConfigScreen() {
    }

    /** Builds the screen. Called only via {@link AutarkiaModMenu}, only with YACL present. */
    static Screen create(Screen parent) {
        AutarkiaConfig live = Config.get();

        // Staged rather than applied per-option: YACL calls the setters as the user edits, and one
        // atomic install on Save keeps a half-applied config from being observed mid-tick.
        Map<Knob, Double> staged = new EnumMap<>(Knob.class);

        // Sections in Knob declaration order, each becoming one category tab.
        Map<String, ConfigCategory.Builder> categories = new LinkedHashMap<>();
        for (Knob knob : Knob.values()) {
            categories.computeIfAbsent(knob.section(), section -> ConfigCategory.createBuilder()
                    .name(Component.translatableWithFallback(
                            "autarkia.config.category." + section, prettify(section))))
                    .option(option(knob, live, staged));
        }

        YetAnotherConfigLib.Builder builder = YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Autarkia"));
        for (ConfigCategory.Builder category : categories.values()) {
            builder.category(category.build());
        }
        return builder.save(() -> apply(staged)).build().generateScreen(parent);
    }

    private static Option<?> option(Knob knob, AutarkiaConfig live, Map<Knob, Double> staged) {
        // Labels are translation keys with the knob as the fallback, so a knob with no lang entry
        // reads sensibly and any label is overridable without touching Java. The last tooltip line
        // stays literal: the dotted key and the accepted range are what you type into /autarkia
        // config.
        Component name = Component.translatableWithFallback(
                nameKey(knob), prettify(knob.leaf()));
        OptionDescription description = OptionDescription.of(
                Component.translatableWithFallback(nameKey(knob) + ".desc", knob.doc()),
                Component.literal(""),
                Component.literal(knob.key() + " — accepts " + knob.expects()));
        switch (knob.kind()) {
            case BOOL:
                return Option.<Boolean>createBuilder()
                        .name(name)
                        .description(description)
                        .binding(knob.def() != 0.0, () -> live.b(knob),
                                value -> staged.put(knob, value ? 1.0 : 0.0))
                        .controller(TickBoxControllerBuilder::create)
                        .build();
            case INT:
                return Option.<Integer>createBuilder()
                        .name(name)
                        .description(description)
                        .binding((int) knob.def(), () -> live.i(knob),
                                value -> staged.put(knob, (double) value))
                        .controller(opt -> IntegerFieldControllerBuilder.create(opt)
                                .range((int) knob.min(), (int) knob.max()))
                        .build();
            case DOUBLE:
            default:
                return Option.<Double>createBuilder()
                        .name(name)
                        .description(description)
                        .binding(knob.def(), () -> live.d(knob),
                                value -> staged.put(knob, value))
                        .controller(opt -> DoubleFieldControllerBuilder.create(opt)
                                .range(knob.min(), knob.max()))
                        .build();
        }
    }

    /** {@code autarkia.config.option.<section>.<leaf>}; append {@code .desc} for the tooltip. */
    private static String nameKey(Knob knob) {
        return "autarkia.config.option." + knob.key();
    }

    /** {@code sense_radius} -> {@code Sense radius} — the label an untranslated knob falls back to. */
    private static String prettify(String snakeCase) {
        String spaced = snakeCase.replace('_', ' ');
        return spaced.isEmpty() ? spaced
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Install the edited values as one config, then persist — the same path {@code config set} takes. */
    private static void apply(Map<Knob, Double> staged) {
        AutarkiaConfig updated = Config.get();
        for (Map.Entry<Knob, Double> change : staged.entrySet()) {
            updated = updated.with(change.getKey(), change.getValue());
        }
        Config.install(updated);
        ConfigFile.save(updated);
    }
}
