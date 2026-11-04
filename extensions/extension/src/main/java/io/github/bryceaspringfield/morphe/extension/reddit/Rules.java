package io.github.bryceaspringfield.morphe.extension.reddit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A keyword list, one rule a line:
 * <ul>
 *     <li>{@code r/name}: posts in the community {@code name}.</li>
 *     <li>{@code u/name}: posts or comments by the account {@code name}.</li>
 *     <li>{@code flair:Text}: posts whose flair is {@code Text}; {@code !flair:Text}: posts without it.</li>
 *     <li>{@code "phrase"}: the phrase as whole words.</li>
 *     <li>Any other text: the text anywhere, case-insensitively.</li>
 * </ul>
 * Any rule may start with communities and a colon ({@code r/pics: cat} or {@code r/pics, r/aww: flair:Meme}),
 * which limits it to posts in those communities; a rule without one applies everywhere.
 */
final class Rules {

    enum Kind {COMMUNITY, AUTHOR, FLAIR, NOT_FLAIR, PHRASE, TEXT}

    static final class Rule {
        final Kind kind;
        final String value;
        final Pattern pattern;
        final Set<String> scope;

        Rule(Kind kind, String value, Set<String> scope) {
            this.kind = kind;
            this.value = value;
            this.scope = scope;
            this.pattern = kind == Kind.PHRASE
                    ? Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(value) + "(?![\\p{L}\\p{N}_])")
                    : null;
        }

        boolean inScope(String community) {
            return scope.isEmpty() || scope.contains(community);
        }

        boolean matchesText(String text) {
            if (text == null || text.isEmpty()) return false;
            return kind == Kind.PHRASE ? pattern.matcher(text).find() : text.contains(value);
        }
    }

    final String source;
    final List<Rule> rules = new ArrayList<>();

    Rules(String source) {
        this.source = source == null ? "" : source;
        for (String line : this.source.split("\n")) {
            Rule rule = parse(line.trim());
            if (rule != null) rules.add(rule);
        }
    }

    private static Rule parse(String line) {
        if (line.isEmpty() || line.startsWith("#")) return null;
        String lower = line.toLowerCase(Locale.ROOT);
        Set<String> scope = new HashSet<>();

        // Communities before a colon limit the rule to them.
        int colon = lower.indexOf(':');
        if (colon > 2 && lower.startsWith("r/") && !lower.substring(0, colon).endsWith("flair")) {
            boolean allCommunities = true;
            Set<String> names = new HashSet<>();
            for (String part : lower.substring(0, colon).split(",")) {
                String name = part.trim();
                if (name.length() > 2 && name.startsWith("r/") && !name.contains(" ")) {
                    names.add(community(name.substring(2)));
                } else {
                    allCommunities = false;
                }
            }
            if (allCommunities && !names.isEmpty()) {
                scope = names;
                line = line.substring(colon + 1).trim();
                lower = lower.substring(colon + 1).trim();
                if (lower.isEmpty()) return new Rule(Kind.COMMUNITY, "", scope);
            }
        }

        if (lower.startsWith("!flair:") && lower.length() > 7) {
            return new Rule(Kind.NOT_FLAIR, unquote(lower.substring(7)), scope);
        }
        if (lower.startsWith("flair:") && lower.length() > 6) {
            return new Rule(Kind.FLAIR, unquote(lower.substring(6)), scope);
        }
        if (lower.length() > 2 && lower.startsWith("r/") && !lower.contains(" ")) {
            return new Rule(Kind.COMMUNITY, community(lower.substring(2)), scope);
        }
        if (lower.length() > 2 && lower.startsWith("u/") && !lower.contains(" ")) {
            return new Rule(Kind.AUTHOR, lower.substring(2), scope);
        }
        if (lower.length() > 2 && lower.startsWith("\"") && lower.endsWith("\"")) {
            return new Rule(Kind.PHRASE, lower.substring(1, lower.length() - 1), scope);
        }
        return new Rule(Kind.TEXT, lower, scope);
    }

    private static String unquote(String text) {
        text = text.trim();
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.trim();
    }

    /**
     * A community name as compared: lowercase, without the r/ prefix.
     */
    static String community(String name) {
        name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return name.startsWith("r/") ? name.substring(2) : name;
    }

    boolean isEmpty() {
        return rules.isEmpty();
    }

    int size() {
        return rules.size();
    }
}
