package io.github.bryceaspringfield.morphe.extension.reddit;

import java.util.Locale;
import java.util.Set;

/**
 * Communities hidden by topic. A community is hidden when its name contains one of the topic's patterns and
 * none of its exceptions (communities opposing the topic, or names sharing the letters by chance). Names are
 * compared lowercase, without underscores. The user's own list ({@link FilterSettings#TOPIC_EXTRA}) adds names.
 */
final class Topics {

    static final String[] KEYS = {"zionism", "nazism", "fascism", "incel"};
    static final String[] LABELS = {"Zionism", "Nazism and white supremacy", "Fascism", "Incels"};

    private static final String[][] PATTERNS = {
            {"zionis"},
            {"nazi", "hitler", "1488", "14words", "fourteenwords", "aryan", "thirdreich", "fourthreich",
                    "whitenationalis", "whitesupremac", "whitepride", "whitegenocide", "greatreplacement", "racerealis"},
            {"fascis", "fascism"},
            {"incel", "braincel", "truecel", "blackpill", "foreveraloneinc"},
    };

    private static final String[] EXCEPTIONS = {"anti", "against", "inceltear", "incelexit", "grammarnazi",
            "soupnazi", "feminazi", "punchanazi", "nazihunt", "debunk", "watch", "exit", "recover", "former",
            "history", "askhistorian"};

    private Topics() {
    }

    static String label(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return LABELS[i];
        }
        return key;
    }

    /**
     * The patterns of a topic, for the settings screen.
     */
    static String patterns(int index) {
        return String.join(", ", PATTERNS[index]);
    }

    static String exceptions() {
        return String.join(", ", EXCEPTIONS);
    }

    /**
     * Whether the community belongs to a chosen topic or to the user's own list.
     */
    static boolean hides(String community, Set<String> topics, Set<String> extra) {
        if (community == null || community.isEmpty()) return false;
        String name = community.toLowerCase(Locale.ROOT).replace("_", "");
        if (extra.contains(name)) return true;
        if (topics.isEmpty()) return false;

        for (String exception : EXCEPTIONS) {
            if (name.contains(exception)) return false;
        }
        for (int i = 0; i < KEYS.length; i++) {
            if (!topics.contains(KEYS[i])) continue;
            for (String pattern : PATTERNS[i]) {
                if (name.contains(pattern)) return true;
            }
        }
        return false;
    }
}
