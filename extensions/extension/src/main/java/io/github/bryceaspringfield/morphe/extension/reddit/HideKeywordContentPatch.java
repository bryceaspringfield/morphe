package io.github.bryceaspringfield.morphe.extension.reddit;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides posts whose title, body text, community, or author matches a keyword.
 * <p>
 * Each line of the keyword list is one rule:
 * <ul>
 *     <li>{@code r/name} hides posts in the community {@code name}.</li>
 *     <li>{@code u/name} hides posts by the account {@code name}.</li>
 *     <li>{@code "phrase"} hides posts where the phrase appears as whole words.</li>
 *     <li>Any other text hides posts where it appears anywhere, case-insensitively.</li>
 * </ul>
 * Plain and quoted rules are matched against the title, the body text, the community name,
 * and the author name.
 * <p>
 * The feed elements are Kotlin data classes with obfuscated field names. Their toString() keeps the
 * source property names as labels (for example {@code ", title="}), so the values are read from it.
 * This class uses no class of Morphe Patches or of Reddit at compile time, since its bundle is
 * selected beside Morphe Patches.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class HideKeywordContentPatch {

    private static final String TAG = "RedditKeywordFilter";
    private static final String PREFERENCES = "reddit_keyword_filter";
    private static final String KEY_RULES = "rules";

    /**
     * Added during patching to the feed's post element, which holds the post's parts in a list.
     */
    public interface PostContainer {
    }

    /**
     * Added during patching to the post title element.
     */
    public interface TitleElement {
    }

    /**
     * Added during patching to the post body preview element.
     */
    public interface TextElement {
    }

    /**
     * Added during patching to the post header element, which names the community and the author.
     */
    public interface HeaderElement {
    }

    /**
     * Added during patching to the classic view's post element.
     */
    public interface ClassicPost {
    }

    private static final class Rules {
        final String source;
        final List<String> communities = new ArrayList<>();
        final List<String> authors = new ArrayList<>();
        final List<String> phrases = new ArrayList<>();
        final List<Pattern> wholeWords = new ArrayList<>();

        Rules(String source) {
            this.source = source;

            for (String line : source.split("\n")) {
                String rule = line.trim();
                if (rule.isEmpty()) continue;
                String lower = rule.toLowerCase(Locale.ROOT);

                if (lower.length() > 2 && (lower.startsWith("r/") || lower.startsWith("u/"))) {
                    (lower.charAt(0) == 'r' ? communities : authors).add(lower.substring(2));
                } else if (rule.length() > 2 && rule.startsWith("\"") && rule.endsWith("\"")) {
                    wholeWords.add(Pattern.compile(
                            "(?<![\\p{L}\\p{N}_])" + Pattern.quote(lower.substring(1, lower.length() - 1))
                                    + "(?![\\p{L}\\p{N}_])"));
                } else {
                    phrases.add(lower);
                }
            }
        }

        boolean isEmpty() {
            return communities.isEmpty() && authors.isEmpty() && phrases.isEmpty() && wholeWords.isEmpty();
        }

        int size() {
            return communities.size() + authors.size() + phrases.size() + wholeWords.size();
        }
    }

    /**
     * The start of the next label in a data class's toString().
     */
    private static final Pattern NEXT_LABEL = Pattern.compile(", [a-zA-Z]+=");

    /**
     * The fields of each post container class that may hold its parts.
     */
    private static final Map<Class<?>, List<Field>> PART_FIELDS = new ConcurrentHashMap<>();

    /**
     * The getters of each listing item class, in the order title, body text, community, author.
     */
    private static final Map<Class<?>, Method[]> LINK_GETTERS = new ConcurrentHashMap<>();

    private static final String[] LINK_GETTER_NAMES = {"getTitle", "getSelftext", "getSubreddit", "getAuthor"};

    private static volatile Rules rules = new Rules("");

    @SuppressLint("StaticFieldLeak")
    private static volatile SharedPreferences preferences;

    // region Settings

    @SuppressLint("PrivateApi")
    private static SharedPreferences getPreferences() {
        SharedPreferences current = preferences;
        if (current != null) return current;
        try {
            Context context = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (context == null) return null;
            current = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            preferences = current;
            return current;
        } catch (Exception ex) {
            Log.e(TAG, "Could not open the keyword list", ex);
            return null;
        }
    }

    private static String getRulesText() {
        SharedPreferences current = getPreferences();
        return current == null ? "" : current.getString(KEY_RULES, "");
    }

    /**
     * Rereads the keyword list only when its text changes, so edits apply without a restart.
     */
    private static Rules getRules() {
        String source = getRulesText();
        Rules current = rules;
        if (!current.source.equals(source)) {
            current = new Rules(source);
            rules = current;
        }
        return current;
    }

    private static String summary(String text) {
        int count = new Rules(text).size();
        if (count == 0) {
            return "No rules. One rule a line: r/community, u/account, \"whole phrase\", or any text.";
        }
        return count + (count == 1 ? " rule" : " rules")
                + ". One rule a line: r/community, u/account, \"whole phrase\", or any text.";
    }

    /**
     * The settings screen being built, saved when its initialize() starts.
     */
    private static WeakReference<Object> settingsFragment = new WeakReference<>(null);

    /**
     * Injection point. Saves Morphe Patches' Reddit settings screen when it starts building.
     *
     * @param fragment The settings screen, a {@link PreferenceFragment}.
     */
    public static void setSettingsFragment(Object fragment) {
        settingsFragment = new WeakReference<>(fragment);
    }

    /**
     * Injection point. Adds the Keyword filter section to the settings screen saved by
     * {@link #setSettingsFragment(Object)}, once the screen's own sections are built.
     */
    public static void addSettings() {
        try {
            Object fragment = settingsFragment.get();
            if (!(fragment instanceof PreferenceFragment)) {
                Log.e(TAG, "addSettings: no settings screen saved, found " + fragment);
                return;
            }
            PreferenceFragment preferenceFragment = (PreferenceFragment) fragment;
            PreferenceScreen screen = preferenceFragment.getPreferenceScreen();
            Context context = preferenceFragment.getActivity();
            if (screen == null || context == null) return;

            PreferenceCategory category = new PreferenceCategory(context);
            category.setTitle("Keyword filter");
            screen.addPreference(category);

            Preference keywords = new Preference(context);
            keywords.setTitle("Keywords to hide");
            keywords.setSummary(summary(getRulesText()));
            keywords.setOnPreferenceClickListener(preference -> {
                showEditor(context, preference);
                return true;
            });
            category.addPreference(keywords);
            Log.i(TAG, "addSettings: added the Keyword filter section");
        } catch (Exception ex) {
            Log.e(TAG, "addSettings failure", ex);
        }
    }

    private static void showEditor(Context context, Preference preference) {
        EditText editor = new EditText(context);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setSingleLine(false);
        editor.setMinLines(4);
        editor.setText(getRulesText());
        editor.setHint("r/community\nu/account\n\"whole phrase\"\nany text");

        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(padding, padding / 2, padding, 0);
        frame.addView(editor);

        new AlertDialog.Builder(context)
                .setTitle("Keywords to hide")
                .setMessage("One rule a line. r/name hides a community, u/name an account, "
                        + "\"phrase\" whole words, and any other text hides posts containing it.")
                .setView(frame)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = editor.getText().toString().trim();
                    SharedPreferences current = getPreferences();
                    if (current != null) current.edit().putString(KEY_RULES, text).apply();
                    preference.setSummary(summary(text));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // endregion

    // region Matching

    /**
     * The value printed after {@code label} in a data class's toString(), lowercased, with {@code prefix} removed.
     */
    static String valueOf(String text, String label, String prefix) {
        String key = ", " + label + "=";
        int start = text.indexOf(key);
        if (start < 0) {
            key = "(" + label + "=";
            start = text.indexOf(key);
            if (start < 0) return "";
        }
        start += key.length();

        Matcher next = NEXT_LABEL.matcher(text);
        int end = next.find(start) ? next.start() : text.lastIndexOf(')');
        if (end < start) end = text.length();

        String value = text.substring(start, end).trim().toLowerCase(Locale.ROOT);
        if (value.equals("null")) return "";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static boolean matches(Rules rules, String text, String community, String author) {
        if (!community.isEmpty() && rules.communities.contains(community)) return true;
        if (!author.isEmpty() && rules.authors.contains(author)) return true;

        for (String value : new String[]{text, community, author}) {
            if (value.isEmpty()) continue;
            for (String phrase : rules.phrases) {
                if (value.contains(phrase)) return true;
            }
            for (Pattern pattern : rules.wholeWords) {
                if (pattern.matcher(value).find()) return true;
            }
        }
        return false;
    }

    /**
     * The instance fields of a post container class whose type may hold a list of parts.
     */
    private static List<Field> partFields(Class<?> type) {
        List<Field> fields = PART_FIELDS.get(type);
        if (fields != null) return fields;

        fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            if (Modifier.isStatic(field.getModifiers()) || fieldType.isPrimitive()
                    || fieldType == String.class || fieldType.isEnum()) continue;
            field.setAccessible(true);
            fields.add(field);
        }
        PART_FIELDS.put(type, fields);
        return fields;
    }

    private static boolean shouldHide(Rules rules, Object element) throws IllegalAccessException {
        if (element instanceof ClassicPost) {
            String text = element.toString();
            return matches(rules, valueOf(text, "title", ""), valueOf(text, "subredditName", "r/"), "");
        }

        if (!(element instanceof PostContainer)) return false;

        StringBuilder text = new StringBuilder();
        String community = "";
        String author = "";

        for (Field field : partFields(element.getClass())) {
            Object value = field.get(element);
            if (!(value instanceof Iterable)) continue;

            for (Object part : (Iterable<?>) value) {
                if (part instanceof TitleElement) {
                    text.append(valueOf(part.toString(), "title", "")).append('\n');
                } else if (part instanceof TextElement) {
                    text.append(valueOf(part.toString(), "text", "")).append('\n');
                } else if (part instanceof HeaderElement) {
                    String header = part.toString();
                    author = valueOf(header, "authorNameWithPrefix", "u/");
                    // The details name the community in feeds, and the author on a community's own page.
                    String details = valueOf(header, "details", "");
                    if (details.startsWith("r/")) community = details.substring(2);
                    else if (author.isEmpty() && details.startsWith("u/")) author = details.substring(2);
                } else if (part instanceof PostContainer || part instanceof ClassicPost) {
                    // A crosspost nests the original post.
                    if (shouldHide(rules, part)) return true;
                }
            }
        }

        return matches(rules, text.toString().trim(), community, author);
    }

    /**
     * Injection point. Filters each page of a feed (Home, Popular, Latest, a community, a custom feed).
     */
    public static List<?> filterFeedElements(List<?> list) {
        try {
            Rules rules = getRules();
            if (list == null || rules.isEmpty()) return list;

            List<Object> filtered = new ArrayList<>(list.size());
            for (Object element : list) {
                if (!shouldHide(rules, element)) filtered.add(element);
            }
            Log.i(TAG, "filterFeedElements: hid " + (list.size() - filtered.size()) + " of " + list.size());
            return filtered.size() == list.size() ? list : filtered;
        } catch (Exception ex) {
            Log.e(TAG, "filterFeedElements failure", ex);
            return list;
        }
    }

    private static String linkValue(Method getter, Object link, String prefix) throws Exception {
        if (getter == null) return "";
        Object value = getter.invoke(link);
        if (value == null) return "";
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }

    private static Method[] linkGetters(Class<?> type) {
        Method[] getters = LINK_GETTERS.get(type);
        if (getters != null) return getters;

        getters = new Method[LINK_GETTER_NAMES.length];
        for (int i = 0; i < getters.length; i++) {
            try {
                getters[i] = type.getMethod(LINK_GETTER_NAMES[i]);
            } catch (NoSuchMethodException ignored) {
                // The item has no such value.
            }
        }
        LINK_GETTERS.put(type, getters);
        return getters;
    }

    /**
     * Injection point. Filters the older post listings (search results, profiles).
     */
    public static List<?> filterLinks(List<?> list) {
        try {
            Rules rules = getRules();
            if (list == null || rules.isEmpty()) return list;

            List<Object> filtered = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null && item.getClass().getName().equals("com.reddit.domain.model.Link")) {
                    Method[] getters = linkGetters(item.getClass());
                    if (matches(rules,
                            linkValue(getters[0], item, "") + "\n" + linkValue(getters[1], item, ""),
                            linkValue(getters[2], item, "r/"),
                            linkValue(getters[3], item, "u/"))) {
                        continue;
                    }
                }
                filtered.add(item);
            }
            Log.i(TAG, "filterLinks: hid " + (list.size() - filtered.size()) + " of " + list.size());
            return filtered.size() == list.size() ? list : filtered;
        } catch (Exception ex) {
            Log.e(TAG, "filterLinks failure", ex);
            return list;
        }
    }

    // endregion
}
