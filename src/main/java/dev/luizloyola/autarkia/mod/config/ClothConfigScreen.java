package dev.luizloyola.autarkia.mod.config;

import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.EnumMap;
import java.util.Map;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The optional Cloth Config screen — a tab per {@link Knob#section()}, a field per knob, built from
 * the enum. Saving takes the {@code /autarkia config set} path: install the new
 * {@link AutarkiaConfig}, then write {@code config/autarkia.json}.
 *
 * <p><b>Must not be loaded unless Cloth Config is installed</b> — it names {@code me.shedaniel.*}
 * types directly, so touching it without the library is a {@link NoClassDefFoundError}, and
 * {@link AutarkiaModMenu} is the only caller and checks first. {@code modCompileOnly}, never shipped.
 */
final class ClothConfigScreen {

    private ClothConfigScreen() {
    }

    /** Builds the screen. Called only via {@link AutarkiaModMenu}, only with Cloth present. */
    static Screen create(Screen parent) {
        AutarkiaConfig live = Config.get();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Autarkia"));
        ConfigEntryBuilder entries = builder.entryBuilder();

        // Cloth fires every entry's save consumer on save, so this ends up holding the whole screen
        // state, not just the edited fields.
        Map<Knob, Double> staged = new EnumMap<>(Knob.class);

        for (Knob knob : Knob.values()) {
            ConfigCategory category = builder.getOrCreateCategory(Component.literal(knob.section()));
            Component label = Component.literal(knob.leaf());
            Component tooltip = Component.literal(knob.doc() + "\n\n" + knob.key()
                    + " — accepts " + knob.expects());
            switch (knob.kind()) {
                case BOOL -> category.addEntry(entries
                        .startBooleanToggle(label, live.b(knob))
                        .setDefaultValue(knob.def() != 0.0)
                        .setTooltip(tooltip)
                        .setSaveConsumer(value -> staged.put(knob, value ? 1.0 : 0.0))
                        .build());
                case INT -> category.addEntry(entries
                        .startIntField(label, live.i(knob))
                        .setMin((int) knob.min())
                        .setMax((int) knob.max())
                        .setDefaultValue((int) knob.def())
                        .setTooltip(tooltip)
                        .setSaveConsumer(value -> staged.put(knob, (double) value))
                        .build());
                case DOUBLE -> category.addEntry(entries
                        .startDoubleField(label, live.d(knob))
                        .setMin(knob.min())
                        .setMax(knob.max())
                        .setDefaultValue(knob.def())
                        .setTooltip(tooltip)
                        .setSaveConsumer(value -> staged.put(knob, value))
                        .build());
            }
        }

        builder.setSavingRunnable(() -> {
            AutarkiaConfig updated = Config.get();
            for (Map.Entry<Knob, Double> change : staged.entrySet()) {
                updated = updated.with(change.getKey(), change.getValue());
            }
            Config.install(updated);
            ConfigFile.save(updated);
        });
        return builder.build();
    }
}
