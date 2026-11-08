package net.minecraft.client.resources;

import java.util.Map;

/** Minimal headless localization scope; does not create Minecraft, resources, FML or registries. */
public final class JeiTestLocale {
    private JeiTestLocale() { }

    @SuppressWarnings("DataFlowIssue") // Restore the absent locale of the headless test process after this scope.
    public static void withTranslations(Map<String, String> translations, Runnable assertions) {
        var locale = new Locale();
        locale.properties.putAll(translations);
        I18n.setLocale(locale);
        try {
            assertions.run();
        } finally {
            I18n.setLocale(null);
        }
    }
}
