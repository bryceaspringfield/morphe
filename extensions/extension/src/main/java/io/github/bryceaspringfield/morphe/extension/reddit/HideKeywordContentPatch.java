package io.github.bryceaspringfield.morphe.extension.reddit;

import static io.github.bryceaspringfield.morphe.extension.reddit.FilterSettings.*;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The injection points of the Hide keyword content patch, and its section of Morphe's Reddit settings screen.
 * This bundle uses no class of Morphe Patches or of Reddit at compile time, since it is selected beside
 * Morphe Patches; Reddit's classes are read by reflection and by their toString().
 */
@SuppressWarnings({"unused", "deprecation"})
public final class HideKeywordContentPatch {

    private static final String RULE_HELP = "One rule a line:\n"
            + "  r/name  hides a community\n"
            + "  u/name  hides an account\n"
            + "  flair:Meme  hides posts with that flair\n"
            + "  !flair:Meme  hides posts without it\n"
            + "  \"phrase\"  matches whole words\n"
            + "  any text  matches anywhere\n"
            + "Start a rule with communities and a colon to limit it to them, for example\n"
            + "  r/pics: cat\n"
            + "  r/news, r/worldnews: !flair:Politics";

    // region Feed hooks

    /**
     * The FeedType field of each feed data source class.
     */
    private static final Map<Class<?>, Field> FEED_TYPE_FIELDS = new ConcurrentHashMap<>();

    private static String feedType(Object source) {
        if (source == null) return null;
        Class<?> type = source.getClass();
        Field field = FEED_TYPE_FIELDS.get(type);
        if (field == null && !FEED_TYPE_FIELDS.containsKey(type)) {
            for (Class<?> current = type; current != null && field == null; current = current.getSuperclass()) {
                for (Field candidate : current.getDeclaredFields()) {
                    if (candidate.getType().isEnum() && candidate.getType().getSimpleName().equals("FeedType")) {
                        candidate.setAccessible(true);
                        field = candidate;
                        break;
                    }
                }
            }
            if (field != null) FEED_TYPE_FIELDS.put(type, field);
        }
        if (field == null) return null;
        try {
            Object value = field.get(source);
            return value == null ? null : ((Enum<?>) value).name();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * The community a community feed's data source loads: the text field beside its community ID (t5_...).
     */
    private static String sourceCommunity(Object source) {
        if (source == null) return null;
        List<String> values = new ArrayList<>();
        boolean hasId = false;
        for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(source);
                    if (value == null) continue;
                    if (((String) value).startsWith("t5_")) hasId = true;
                    else values.add((String) value);
                } catch (Exception ignored) {
                }
            }
        }
        if (!hasId) return null;
        for (String value : values) {
            if (value.matches("(?:r/)?[A-Za-z0-9_]{2,21}")) return value;
        }
        return null;
    }

    /**
     * The text fields of a feed's data source, for debug logging.
     */
    private static String describeSource(Object source) {
        if (source == null) return "null";
        StringBuilder text = new StringBuilder(source.getClass().getName());
        for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    text.append(' ').append(type.getSimpleName()).append('.').append(field.getName()).append('=')
                            .append(field.get(source));
                } catch (Exception ignored) {
                }
            }
        }
        return text.toString();
    }

    /**
     * Injection point. Filters each page of a feed (Home, Popular, News, a community, search, a profile).
     *
     * @param source The feed's data source, which names its feed type.
     */
    public static List<?> filterFeedElements(List<?> list, Object source) {
        try {
            if (list == null || list.isEmpty() || !Filter.anyPostFilter()) return list;
            String type = feedType(source);
            String place = Filter.placeOf(type);
            if (place == null) return list;

            boolean debug = getBoolean(DEBUG);
            if (debug) Log.d(TAG, "source " + type + " " + describeSource(source));
            String community = PLACE_COMMUNITY.equals(place) ? sourceCommunity(source) : null;
            List<Object> filtered = new ArrayList<>(list.size());
            for (Object element : list) {
                PostInfo post = PostInfo.fromElement(element);
                post.defaultCommunity(community);
                if (debug && !post.isPost) {
                    String text = String.valueOf(element);
                    Log.d(TAG, "not a post: " + text.substring(0, Math.min(600, text.length())));
                }
                String reason = Filter.hidePost(post, place);
                if (debug) Log.d(TAG, (reason == null ? "show " : "hide (" + reason + ") ") + type + " " + post);
                if (reason == null) {
                    filtered.add(element);
                    if (post.isPost && !post.id.isEmpty()) SHOWN_PLACES.put(post.id, new String[]{place, type, community});
                }
            }
            Log.i(TAG, "filterFeedElements " + type + ": hid " + (list.size() - filtered.size()) + " of " + list.size());
            return filtered.size() == list.size() ? list : filtered;
        } catch (Exception ex) {
            Log.e(TAG, "filterFeedElements failure", ex);
            return list;
        }
    }

    /**
     * The place, feed type, and community of each post a feed showed, by post ID, for filtering the feed again after
     * a translation rewrites its posts.
     */
    private static final Map<String, String[]> SHOWN_PLACES = Collections.synchronizedMap(
            new LinkedHashMap<String, String[]>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
                    return size() > 4000;
                }
            });

    /**
     * Injection point. Filters a feed again after a translation modification rewrote its posts (an automatic or
     * requested translation, or a repost's original), since the page filter saw only the original text.
     *
     * @param result       The modified feed, or another object while the modification is suspended.
     * @param modification The feed modification.
     * @return The result, or the posts to keep as the app's immutable list.
     */
    public static Object filterModifiedFeed(Object result, Object modification) {
        try {
            if (!(result instanceof List) || modification == null) return result;
            String kind = modification.getClass().getName();
            boolean debug = getBoolean(DEBUG);
            if (debug) Log.d(TAG, "feed modification " + kind);
            if (!kind.contains(".translation.") || !Filter.anyPostFilter()) return result;
            List<?> list = (List<?>) result;
            List<Object> kept = new ArrayList<>(list.size());
            for (Object element : list) {
                if (hideAfterTranslation(element, debug) == null) kept.add(element);
            }
            if (kept.size() == list.size()) return result;
            Log.i(TAG, "filterModifiedFeed: hid " + (list.size() - kept.size()) + " of " + list.size());
            return immutableList(kept);
        } catch (Exception ex) {
            Log.e(TAG, "filterModifiedFeed failure", ex);
            return result;
        }
    }

    /**
     * Why a post a feed showed is hidden now that a translation rewrote it, or null if it stays: the rules applied to
     * the post at the place the feed showed it.
     */
    private static String hideAfterTranslation(Object element, boolean debug) {
        PostInfo post = PostInfo.fromElement(element);
        String[] where = post.isPost ? SHOWN_PLACES.get(post.id) : null;
        if (where == null) return null;
        post.defaultCommunity(where[2]);
        String reason = Filter.hidePost(post, where[0]);
        if (debug && reason != null) Log.d(TAG, "hide after translation (" + reason + ") " + where[1] + " " + post);
        return reason;
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Injection point, at the start of a pager's update of one post (a requested translation, a vote, a read mark).
     * After a translation, filters the pager's posts again once the update is written, since a translation of one
     * post changes the post in place and runs no feed modification.
     *
     * @param pager  The feed's pager.
     * @param events The update's events.
     */
    public static void onElementEvents(Object pager, List<?> events) {
        try {
            if (pager == null || events == null || !Filter.anyPostFilter()) return;
            for (Object event : events) {
                if (event == null || !event.getClass().getName().endsWith(".OnTranslationSuccess")) continue;
                MAIN.post(() -> {
                    try {
                        boolean hid = refilterPagerState(pager);
                        if (getBoolean(DEBUG)) Log.d(TAG, "pager state after translation: " + (hid ? "posts hidden" : "unchanged"));
                    } catch (Throwable ex) {
                        Log.e(TAG, "refilterPagerState failure", ex);
                    }
                });
                return;
            }
        } catch (Exception ex) {
            Log.e(TAG, "onElementEvents failure", ex);
        }
    }

    /**
     * Called by {@link #refilterPagerState(Object)} with the pager state's posts and their parallel display models.
     *
     * @return The two lists without the posts the rules hide after translation, as the app's immutable lists, or
     * null if every post stays.
     */
    public static Object[] keptLists(List<?> elements, List<?> models) {
        try {
            if (elements == null || models == null || elements.size() != models.size()) return null;
            boolean debug = getBoolean(DEBUG);
            List<Object> keptElements = new ArrayList<>(elements.size());
            List<Object> keptModels = new ArrayList<>(models.size());
            for (int i = 0; i < elements.size(); i++) {
                if (hideAfterTranslation(elements.get(i), debug) != null) continue;
                keptElements.add(elements.get(i));
                keptModels.add(models.get(i));
            }
            if (keptElements.size() == elements.size()) return null;
            Log.i(TAG, "filterPagerState: hid " + (elements.size() - keptElements.size()) + " of " + elements.size());
            return new Object[]{immutableList(keptElements), immutableList(keptModels)};
        } catch (Exception ex) {
            Log.e(TAG, "keptLists failure", ex);
            return null;
        }
    }

    /**
     * Removes the posts {@link #keptLists(List, List)} drops from a pager's state, with the app's own state copy and
     * compare-and-set; the patch replaces this method.
     *
     * @return Whether posts were removed.
     */
    public static boolean refilterPagerState(Object pager) {
        return false;
    }

    /**
     * The app's immutable copy of a list; the patch writes this method's body.
     */
    public static Object immutableList(List<?> list) {
        return list;
    }

    /**
     * The data source of the page being filtered, set just before {@link #filterFeedElements(List)} on the same thread.
     */
    private static final ThreadLocal<Object> FEED_SOURCE = new ThreadLocal<>();

    /**
     * Injection point. Saves the data source of the page the next call filters.
     */
    public static void setFeedSource(Object source) {
        FEED_SOURCE.set(source);
    }

    /**
     * Injection point. Filters a feed page, of the data source saved by {@link #setFeedSource(Object)}.
     */
    public static List<?> filterFeedElements(List<?> list) {
        Object source = FEED_SOURCE.get();
        FEED_SOURCE.remove();
        return filterFeedElements(list, source);
    }

    /**
     * Injection point. Filters the older post listings, which hold com.reddit.domain.model.Link items.
     */
    public static List<?> filterLinks(List<?> list) {
        try {
            if (list == null || list.isEmpty() || !Filter.anyPostFilter()) return list;
            boolean debug = getBoolean(DEBUG);
            List<Object> filtered = new ArrayList<>(list.size());
            for (Object item : list) {
                String reason = null;
                if (PostInfo.isLink(item)) {
                    PostInfo post = PostInfo.fromLink(item);
                    reason = Filter.hidePost(post, PLACE_OTHER);
                    if (debug) Log.d(TAG, (reason == null ? "show " : "hide (" + reason + ") ") + "listing " + post);
                }
                if (reason == null) filtered.add(item);
            }
            Log.i(TAG, "filterLinks: hid " + (list.size() - filtered.size()) + " of " + list.size());
            return filtered.size() == list.size() ? list : filtered;
        } catch (Exception ex) {
            Log.e(TAG, "filterLinks failure", ex);
            return list;
        }
    }

    /**
     * Injection point. Filters a post's comments; a hidden comment's replies follow it out.
     */
    public static List<?> filterComments(List<?> list) {
        try {
            if (list == null || list.isEmpty() || !Filter.anyCommentFilter()) return list;
            boolean debug = getBoolean(DEBUG);
            List<Object> filtered = new ArrayList<>(list.size());
            int hiddenDepth = -1;
            for (Object item : list) {
                int depth = Filter.depthOf(item);
                if (hiddenDepth >= 0) {
                    if (depth > hiddenDepth) continue;
                    hiddenDepth = -1;
                }
                if (Filter.isComment(item)) {
                    String reason = Filter.hideComment(item);
                    if (reason != null) {
                        if (debug) Log.d(TAG, "hide comment (" + reason + ")");
                        hiddenDepth = Math.max(depth, 0);
                        continue;
                    }
                }
                filtered.add(item);
            }
            Log.i(TAG, "filterComments: hid " + (list.size() - filtered.size()) + " of " + list.size());
            return filtered.size() == list.size() ? list : filtered;
        } catch (Exception ex) {
            Log.e(TAG, "filterComments failure", ex);
            return list;
        }
    }

    // endregion

    // region Navigation hooks

    /**
     * Injection point. Returns null for a bottom bar tab to hide, and the tab otherwise.
     */
    public static Enum<?> navigationTab(Enum<?> tab) {
        try {
            if (tab != null && getBoolean(HIDE_INBOX_TAB)) {
                String name = tab.name();
                if (name.equals("Inbox") || name.equals("UnifiedInbox")) return null;
            }
        } catch (Exception ex) {
            Log.e(TAG, "navigationTab failure", ex);
        }
        return tab;
    }

    /**
     * Injection point. Whether to hide the badge (orange dot) on the sidebar button.
     */
    public static boolean hideMenuBadge() {
        try {
            return getBoolean(HIDE_MENU_BADGE);
        } catch (Exception ex) {
            return false;
        }
    }

    private static final Map<Class<?>, Field[]> ROW_FIELDS = new ConcurrentHashMap<>();

    private static Field[] rowFields(Class<?> type) {
        Field[] fields = ROW_FIELDS.get(type);
        if (fields != null) return fields;
        List<Field> found = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() == int.class || field.getType().isEnum()) {
                field.setAccessible(true);
                found.add(field);
            }
        }
        fields = found.toArray(new Field[0]);
        ROW_FIELDS.put(type, fields);
        return fields;
    }

    /**
     * The sidebar section header an item opens (its HeaderItem enum's name), or null when the item is no header.
     */
    private static String headerOf(Object item) {
        for (Field field : rowFields(item.getClass())) {
            if (field.getType().isEnum() && field.getType().getSimpleName().equals("HeaderItem")) {
                try {
                    Object value = field.get(item);
                    return value == null ? "" : ((Enum<?>) value).name();
                } catch (Exception ex) {
                    return "";
                }
            }
        }
        return null;
    }

    /**
     * Injection point. Removes the sidebar rows the user hid; a hidden section loses its header and its rows.
     */
    public static List<?> filterSidebar(List<?> list) {
        try {
            if (list == null || list.isEmpty()) return list;
            Set<String> hidden = getSet(SIDEBAR_HIDDEN, Collections.emptySet());
            if (hidden.isEmpty()) return list;
            Context context = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            Set<Integer> titles = new HashSet<>();
            for (String name : hidden) {
                int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
                if (id != 0) titles.add(id);
            }

            List<Object> filtered = new ArrayList<>(list.size());
            boolean inHiddenSection = false;
            for (Object item : list) {
                if (item == null) {
                    filtered.add(null);
                    continue;
                }
                String header = headerOf(item);
                if (header != null) inHiddenSection = hidden.contains(header);
                if (inHiddenSection) continue;
                if (header == null && hasTitle(item, titles)) continue;
                filtered.add(item);
            }
            return filtered.size() == list.size() ? list : filtered;
        } catch (Exception ex) {
            Log.e(TAG, "filterSidebar failure", ex);
            return list;
        }
    }

    private static boolean hasTitle(Object item, Set<Integer> titles) {
        if (titles.isEmpty()) return false;
        for (Field field : rowFields(item.getClass())) {
            if (field.getType() != int.class) continue;
            try {
                if (titles.contains(field.getInt(item))) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    // endregion

    // region Settings screen

    /**
     * The settings screen being built, saved when its initialize() starts.
     */
    private static WeakReference<Object> settingsFragment = new WeakReference<>(null);

    /**
     * Injection point. Saves Morphe Patches' Reddit settings screen when it starts building.
     */
    public static void setSettingsFragment(Object fragment) {
        settingsFragment = new WeakReference<>(fragment);
    }

    /**
     * Injection point. Adds this bundle's sections to the settings screen saved by
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

            PreferenceCategory posts = category(context, screen, "Keyword filter: posts");
            item(context, posts, "Keywords to hide", () -> rulesSummary(getString(POST_RULES, "")),
                    p -> editRules(context, p, POST_RULES, "Keywords to hide"));
            item(context, posts, "Keywords apply to", HideKeywordContentPatch::fieldsSummary,
                    p -> chooseSet(context, p, "Keywords apply to", FIELDS, FIELD_KEYS, FIELD_LABELS, fields(),
                            HideKeywordContentPatch::fieldsSummary));
            for (int i = 0; i < TYPE_KEYS.length; i++) {
                String key = TYPE_KEYS[i];
                String label = TYPE_LABELS[i];
                item(context, posts, label, () -> modeLabel(mode(key)), p -> chooseMode(context, p, key, label));
            }
            item(context, posts, "Score (upvotes)", () -> rangeSummary(POST_SCORE_MIN, POST_SCORE_MAX),
                    p -> editRange(context, p, "Score (upvotes)", POST_SCORE_MIN, POST_SCORE_MAX));
            item(context, posts, "Number of comments", () -> rangeSummary(POST_COMMENTS_MIN, POST_COMMENTS_MAX),
                    p -> editRange(context, p, "Number of comments", POST_COMMENTS_MIN, POST_COMMENTS_MAX));
            item(context, posts, "Hide communities by topic", HideKeywordContentPatch::topicsSummary,
                    p -> chooseTopics(context, p));
            item(context, posts, "Where post filters apply", HideKeywordContentPatch::placesSummary,
                    p -> chooseSet(context, p, "Where post filters apply", PLACES, PLACE_KEYS, PLACE_LABELS, places(),
                            HideKeywordContentPatch::placesSummary));

            PreferenceCategory comments = category(context, screen, "Keyword filter: comments");
            item(context, comments, "Comment keywords to hide", () -> rulesSummary(getString(COMMENT_RULES, "")),
                    p -> editRules(context, p, COMMENT_RULES, "Comment keywords to hide"));
            item(context, comments, "Comment score (upvotes)", () -> rangeSummary(COMMENT_SCORE_MIN, COMMENT_SCORE_MAX),
                    p -> editRange(context, p, "Comment score (upvotes)", COMMENT_SCORE_MIN, COMMENT_SCORE_MAX));

            PreferenceCategory navigation = category(context, screen, "Keyword filter: navigation");
            item(context, navigation, "Hide the Inbox tab", () -> onOff(HIDE_INBOX_TAB,
                            "On: the bottom bar's Inbox (notifications and messages) tab is hidden; restart the app"),
                    p -> toggle(p, HIDE_INBOX_TAB,
                            "On: the bottom bar's Inbox (notifications and messages) tab is hidden; restart the app"));
            item(context, navigation, "Hide sidebar rows", HideKeywordContentPatch::sidebarSummary,
                    p -> chooseSet(context, p, "Hide sidebar rows", SIDEBAR_HIDDEN, SIDEBAR_KEYS, SIDEBAR_LABELS,
                            getSet(SIDEBAR_HIDDEN, Collections.emptySet()), HideKeywordContentPatch::sidebarSummary));
            item(context, navigation, "Hide the sidebar button's badge", () -> onOff(HIDE_MENU_BADGE,
                            "On: no orange dot on the sidebar (three-line) button; restart the app"),
                    p -> toggle(p, HIDE_MENU_BADGE, "On: no orange dot on the sidebar (three-line) button; restart the app"));

            PreferenceCategory other = category(context, screen, "Keyword filter: other");
            item(context, other, "Debug logging", () -> getBoolean(DEBUG)
                            ? "On: logs each post read and why it is hidden (tag RedditKeywordFilter)" : "Off",
                    p -> {
                        putBoolean(DEBUG, !getBoolean(DEBUG));
                        p.setSummary(getBoolean(DEBUG)
                                ? "On: logs each post read and why it is hidden (tag RedditKeywordFilter)" : "Off");
                    });

            Log.i(TAG, "addSettings: added the Keyword filter sections");
        } catch (Exception ex) {
            Log.e(TAG, "addSettings failure", ex);
        }
    }

    private interface Summary {
        String get();
    }

    private interface Click {
        void on(Preference preference);
    }

    private static PreferenceCategory category(Context context, PreferenceScreen screen, String title) {
        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    private static void item(Context context, PreferenceCategory category, String title, Summary summary, Click click) {
        Preference preference = new Preference(context);
        preference.setTitle(title);
        preference.setSummary(summary.get());
        preference.setOnPreferenceClickListener(p -> {
            try {
                click.on(p);
            } catch (Exception ex) {
                Log.e(TAG, "Settings dialog failure", ex);
            }
            return true;
        });
        category.addPreference(preference);
    }

    private static String onOff(String key, String on) {
        return getBoolean(key) ? on : "Off";
    }

    private static void toggle(Preference preference, String key, String on) {
        putBoolean(key, !getBoolean(key));
        preference.setSummary(onOff(key, on));
    }

    private static String sidebarSummary() {
        return labelsOf(getSet(SIDEBAR_HIDDEN, Collections.emptySet()), SIDEBAR_KEYS, SIDEBAR_LABELS, "None hidden");
    }

    private static String rulesSummary(String text) {
        int count = new Rules(text).size();
        return count == 0 ? "No rules" : count + (count == 1 ? " rule" : " rules");
    }

    private static String fieldsSummary() {
        return labelsOf(fields(), FIELD_KEYS, FIELD_LABELS, "Nothing (keyword rules match no post)");
    }

    private static String placesSummary() {
        return labelsOf(places(), PLACE_KEYS, PLACE_LABELS, "Nowhere (post filters are off)");
    }

    private static String topicsSummary() {
        Set<String> topics = getSet(TOPICS, Collections.emptySet());
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Topics.KEYS.length; i++) {
            if (topics.contains(Topics.KEYS[i])) names.add(Topics.LABELS[i]);
        }
        int extra = Filter.topicExtra().size();
        if (extra > 0) names.add(extra + (extra == 1 ? " community" : " communities") + " of your own list");
        return names.isEmpty() ? "Off" : String.join(", ", names);
    }

    private static String labelsOf(Set<String> chosen, String[] keys, String[] labels, String none) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            if (chosen.contains(keys[i])) names.add(labels[i]);
        }
        return names.isEmpty() ? none : String.join(", ", names);
    }

    private static String rangeSummary(String minKey, String maxKey) {
        Long min = bound(minKey);
        Long max = bound(maxKey);
        if (min == null && max == null) return "Any (no filter)";
        if (max == null) return "Hide below " + min;
        if (min == null) return "Hide above " + max;
        return "Show " + min + " to " + max + " only";
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    private static void editRules(Context context, Preference preference, String key, String title) {
        EditText editor = new EditText(context);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setSingleLine(false);
        editor.setMinLines(4);
        editor.setText(getString(key, ""));
        editor.setHint("r/community\nu/account\nflair:Meme\n\"whole phrase\"\nany text");

        TextView help = new TextView(context);
        help.setText(key.equals(COMMENT_RULES)
                ? "One rule a line: u/name hides an account's comments, \"phrase\" matches whole words, and any "
                + "other text matches anywhere in a comment. Start a rule with r/name: to limit it to a community."
                : RULE_HELP);
        help.setTextIsSelectable(true);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0);
        layout.addView(help);
        layout.addView(editor);

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = editor.getText().toString().trim();
                    putString(key, text);
                    preference.setSummary(rulesSummary(text));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void chooseSet(Context context, Preference preference, String title, String key, String[] keys,
                                  String[] labels, Set<String> current, Summary summary) {
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = current.contains(keys[i]);
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Set<String> chosen = new HashSet<>();
                    for (int i = 0; i < keys.length; i++) if (checked[i]) chosen.add(keys[i]);
                    putSet(key, chosen);
                    preference.setSummary(summary.get());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void chooseMode(Context context, Preference preference, String key, String title) {
        int current = 0;
        for (int i = 0; i < MODE_KEYS.length; i++) if (MODE_KEYS[i].equals(mode(key))) current = i;
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setSingleChoiceItems(MODE_LABELS, current, (dialog, which) -> {
                    putString(key, MODE_KEYS[which]);
                    preference.setSummary(MODE_LABELS[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void editRange(Context context, Preference preference, String title, String minKey, String maxKey) {
        EditText min = new EditText(context);
        min.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        min.setHint("Lowest shown (blank: no lower bound)");
        min.setText(getString(minKey, ""));
        EditText max = new EditText(context);
        max.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        max.setHint("Highest shown (blank: no upper bound)");
        max.setText(getString(maxKey, ""));

        TextView help = new TextView(context);
        help.setText("Posts outside this range are hidden. Leave a box blank for no bound. A count the app "
                + "shows rounded (for example 1.2k) is compared as 1,200.");

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0);
        layout.addView(help);
        layout.addView(min);
        layout.addView(max);

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    putString(minKey, min.getText().toString().trim());
                    putString(maxKey, max.getText().toString().trim());
                    preference.setSummary(rangeSummary(minKey, maxKey));
                })
                .setNeutralButton("Clear", (dialog, which) -> {
                    putString(minKey, "");
                    putString(maxKey, "");
                    preference.setSummary(rangeSummary(minKey, maxKey));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void chooseTopics(Context context, Preference preference) {
        Set<String> current = getSet(TOPICS, Collections.emptySet());
        boolean[] checked = new boolean[Topics.KEYS.length];
        String[] labels = new String[Topics.KEYS.length];
        for (int i = 0; i < Topics.KEYS.length; i++) {
            checked[i] = current.contains(Topics.KEYS[i]);
            labels[i] = Topics.LABELS[i] + "  (names containing " + Topics.patterns(i) + ")";
        }
        new AlertDialog.Builder(context)
                .setTitle("Hide communities by topic")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Set<String> chosen = new HashSet<>();
                    for (int i = 0; i < Topics.KEYS.length; i++) if (checked[i]) chosen.add(Topics.KEYS[i]);
                    putSet(TOPICS, chosen);
                    preference.setSummary(topicsSummary());
                })
                .setNeutralButton("Your own list", (dialog, which) -> editTopicExtra(context, preference))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void editTopicExtra(Context context, Preference preference) {
        EditText editor = new EditText(context);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setSingleLine(false);
        editor.setMinLines(4);
        editor.setText(getString(TOPIC_EXTRA, ""));
        editor.setHint("r/community");

        TextView help = new TextView(context);
        help.setText("Communities to hide, one a line, in addition to the topics chosen. Names containing "
                + Topics.exceptions() + " are never hidden by a topic, only by this list.");

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0);
        layout.addView(help);
        layout.addView(editor);

        new AlertDialog.Builder(context)
                .setTitle("Your own list")
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    putString(TOPIC_EXTRA, editor.getText().toString().trim());
                    preference.setSummary(topicsSummary());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // endregion
}
