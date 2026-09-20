// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Player-facing chat text translation (docs/private/prompts/step8i-prompt.md): loads {@code
 * lang/en.yml} and {@code lang/zh_CN.yml} from the jar (or the test classpath - both are ordinary
 * {@code src/main/resources} files, so plain JUnit sees them the same way the running plugin
 * does) and looks keys up with a small {@code {0}}/{@code {1}} placeholder substitution done by
 * plain {@link String#replace}, not {@link java.text.MessageFormat} (which mangles apostrophes and
 * literal braces - several of these messages contain both).
 *
 * <p>Deliberately does not use Bukkit's {@code YamlConfiguration}: {@code paper-api} is {@code
 * compileOnly} (build.gradle.kts) and is not on the unit test runtime classpath, but this class -
 * unlike {@code AshlarCommand}/{@code AshlarPlugin} - is exercised directly by plain-JUnit tests
 * ({@code MessagesTest}) and, through the small language-aware overloads on {@code AdminActions}/
 * {@code AgentService}/{@code UsageStore}/{@code AshlarArgs}, indirectly by their existing tests
 * too. The language files are a deliberately restricted subset of YAML (flat {@code key: "text"}
 * lines, keys dotted strings, values always double-quoted), so a few dozen lines of hand-rolled
 * parsing cover them exactly, with no new dependency and no Bukkit at startup-load time. {@link
 * #forPlayer} takes a {@link Locale} rather than a {@code Player} for the same reason: resolving
 * "auto" from a real online player's client locale is the caller's job (on the main thread, where
 * a {@code Player} is already in hand), one line: {@code Messages.forPlayer(language,
 * player.locale())}.
 */
public final class Messages {

    public static final String DEFAULT_LANGUAGE = "en";
    public static final String CHINESE = "zh_CN";
    public static final String AUTO = "auto";

    private static final Logger LOGGER = Logger.getLogger("Ashlar");
    private static volatile Messages instance;

    private final Map<String, Map<String, String>> byLanguage;
    private final Set<String> warnedMissingKeys = ConcurrentHashMap.newKeySet();

    private Messages(Map<String, Map<String, String>> byLanguage) {
        this.byLanguage = byLanguage;
    }

    /** The process-wide instance, lazily loaded once from the two bundled language files. */
    public static Messages instance() {
        Messages result = instance;
        if (result == null) {
            synchronized (Messages.class) {
                result = instance;
                if (result == null) {
                    result = load();
                    instance = result;
                }
            }
        }
        return result;
    }

    /** Loads a fresh instance from the classpath; {@code instance()} is the one callers normally want. */
    public static Messages load() {
        Map<String, Map<String, String>> byLanguage = new ConcurrentHashMap<>();
        byLanguage.put(DEFAULT_LANGUAGE, loadResource("/lang/en.yml"));
        byLanguage.put(CHINESE, loadResource("/lang/zh_CN.yml"));
        return new Messages(byLanguage);
    }

    private static Map<String, String> loadResource(String path) {
        Map<String, String> map = new ConcurrentHashMap<>();
        try (InputStream in = Messages.class.getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.severe("[i18n] missing language resource: " + path);
                return map;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseLine(line, map);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e, () -> "[i18n] could not load " + path);
        }
        return map;
    }

    /** One {@code key: "value"} line of the restricted flat-YAML subset these files use; blank/comment lines are skipped. */
    private static void parseLine(String line, Map<String, String> out) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return;
        }
        String key = trimmed.substring(0, colon).strip();
        String rawValue = trimmed.substring(colon + 1).strip();
        out.put(key, unquote(rawValue));
    }

    private static String unquote(String raw) {
        if (raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
            return raw;
        }
        String inner = raw.substring(1, raw.length() - 1);
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                char next = inner.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Looks up {@code key} for {@code language}, substituting {@code args[i]} for every {@code
     * {i}} placeholder (plain {@link String#replace}). Falls back to {@link #DEFAULT_LANGUAGE} when
     * {@code language} has no translation for {@code key}; if {@code en} itself has none either,
     * logs a warning once per key and returns the key itself, so a missing translation is visible
     * in chat instead of throwing.
     */
    public String get(String language, String key, Object... args) {
        String template = lookup(language, key);
        if (template == null) {
            if (warnedMissingKeys.add(key)) {
                LOGGER.warning("[i18n] missing message key (not even in " + DEFAULT_LANGUAGE + "): " + key);
            }
            return key;
        }
        return substitute(template, args);
    }

    private String lookup(String language, String key) {
        Map<String, String> table = byLanguage.get(language);
        String value = table != null ? table.get(key) : null;
        if (value != null) {
            return value;
        }
        if (DEFAULT_LANGUAGE.equals(language)) {
            return null;
        }
        Map<String, String> en = byLanguage.get(DEFAULT_LANGUAGE);
        return en != null ? en.get(key) : null;
    }

    private static String substitute(String template, Object[] args) {
        if (args == null || args.length == 0) {
            return template;
        }
        String result = template;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }

    // -- language resolution (docs/private/prompts/step8i-prompt.md ---B) --

    /**
     * {@code auto} resolves to a real online player's own client language, folding every Chinese
     * variant (zh_CN/zh_TW/zh_HK/...) to {@link #CHINESE} and everything else (including a locale
     * this plugin ships no translation for) to {@link #DEFAULT_LANGUAGE}; any other configured
     * language is used as-is, ignoring the player's locale entirely.
     */
    public static String forPlayer(String configuredLanguage, Locale playerLocale) {
        return AUTO.equals(configuredLanguage) ? normalizeAuto(playerLocale) : configuredLanguage;
    }

    /** The console has no client locale, so {@code auto} always means {@link #DEFAULT_LANGUAGE} there. */
    public static String forConsole(String configuredLanguage) {
        return AUTO.equals(configuredLanguage) ? DEFAULT_LANGUAGE : configuredLanguage;
    }

    /** Pure {@code auto} resolution table, directly unit-testable: only the language subtag matters. */
    public static String normalizeAuto(Locale locale) {
        return locale != null && "zh".equalsIgnoreCase(locale.getLanguage()) ? CHINESE : DEFAULT_LANGUAGE;
    }

    /** Package-visible for {@code MessagesTest}: every key loaded for {@code language}. */
    Set<String> keysFor(String language) {
        Map<String, String> table = byLanguage.get(language);
        return table != null ? Set.copyOf(table.keySet()) : Set.of();
    }
}
