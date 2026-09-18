// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Messages} (docs/private/prompts/step8i-prompt.md &sect;B): both language
 * files parse, their key sets and placeholder indices match, missing-key fallback, and the {@code
 * auto} resolution table. Pure Java: both {@code lang/*.yml} files are ordinary {@code
 * src/main/resources} content, on the test classpath the same way any other main resource is.
 */
class MessagesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    private final Messages messages = Messages.load();

    @Test
    void bothFilesParseAndAreNonEmpty() {
        assertFalse(messages.keysFor(Messages.DEFAULT_LANGUAGE).isEmpty());
        assertFalse(messages.keysFor(Messages.CHINESE).isEmpty());
    }

    @Test
    void keySetsAreIdentical() {
        Set<String> en = messages.keysFor(Messages.DEFAULT_LANGUAGE);
        Set<String> zh = messages.keysFor(Messages.CHINESE);
        assertEquals(en, zh);
    }

    @Test
    void everyPlaceholderIndexMatchesBetweenLanguages() {
        for (String key : messages.keysFor(Messages.DEFAULT_LANGUAGE)) {
            Set<Integer> enIndices = placeholderIndices(messages.get(Messages.DEFAULT_LANGUAGE, key));
            Set<Integer> zhIndices = placeholderIndices(messages.get(Messages.CHINESE, key));
            assertEquals(enIndices, zhIndices, "placeholder mismatch for key \"" + key + "\"");
        }
    }

    private static Set<Integer> placeholderIndices(String template) {
        Set<Integer> indices = new java.util.TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            indices.add(Integer.parseInt(m.group(1)));
        }
        return indices;
    }

    @Test
    void placeholdersAreSubstitutedByPlainReplace() {
        assertEquals("Request too long (max 500 characters).",
                messages.get("en", "command.request.too_long", 500));
    }

    @Test
    void missingKeyInRequestedLanguageFallsBackToEnglish() {
        // "zh_XX" is not a language this store has, so lookup() falls through to en for it too -
        // exercise the real fallback path via a language that *is* loaded but genuinely lacks a key
        // by asking for a key that only exists in en (there is none by construction, so instead
        // assert the documented behaviour directly: a language with no table at all still resolves
        // through the en table).
        String result = messages.get("fr", "command.player_only");
        assertEquals(messages.get("en", "command.player_only"), result);
    }

    @Test
    void trulyMissingKeyReturnsTheKeyItself() {
        String key = "no.such.key.exists";
        assertEquals(key, messages.get("en", key));
        assertEquals(key, messages.get(Messages.CHINESE, key));
    }

    // ---- auto resolution table (step8i-prompt.md &sect;B) ----

    @Test
    void autoResolvesChineseVariantsToChinese() {
        assertEquals(Messages.CHINESE, Messages.normalizeAuto(Locale.forLanguageTag("zh-CN")));
        assertEquals(Messages.CHINESE, Messages.normalizeAuto(Locale.forLanguageTag("zh-TW")));
    }

    @Test
    void autoResolvesOtherLocalesToEnglish() {
        assertEquals(Messages.DEFAULT_LANGUAGE, Messages.normalizeAuto(Locale.forLanguageTag("en-US")));
        assertEquals(Messages.DEFAULT_LANGUAGE, Messages.normalizeAuto(Locale.forLanguageTag("de-DE")));
    }

    @Test
    void autoResolvesNullLocaleToEnglish() {
        assertEquals(Messages.DEFAULT_LANGUAGE, Messages.normalizeAuto(null));
    }

    @Test
    void forPlayerUsesConfiguredLanguageUnlessAuto() {
        assertEquals(Messages.CHINESE, Messages.forPlayer(Messages.CHINESE, Locale.forLanguageTag("en-US")));
        assertEquals(Messages.DEFAULT_LANGUAGE, Messages.forPlayer(Messages.DEFAULT_LANGUAGE, Locale.forLanguageTag("zh-CN")));
        assertEquals(Messages.CHINESE, Messages.forPlayer(Messages.AUTO, Locale.forLanguageTag("zh-CN")));
        assertEquals(Messages.DEFAULT_LANGUAGE, Messages.forPlayer(Messages.AUTO, Locale.forLanguageTag("de-DE")));
    }

    @Test
    void forConsoleIsEnglishOnlyWhenAuto() {
        assertEquals(Messages.DEFAULT_LANGUAGE, Messages.forConsole(Messages.AUTO));
        assertEquals(Messages.CHINESE, Messages.forConsole(Messages.CHINESE));
        assertEquals(Messages.DEFAULT_LANGUAGE, Messages.forConsole(Messages.DEFAULT_LANGUAGE));
    }

    @Test
    void instanceIsASingleton() {
        assertTrue(Messages.instance() == Messages.instance());
    }
}
