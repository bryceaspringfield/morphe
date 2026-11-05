package io.github.bryceaspringfield.morphe.extension.reddit;

import static io.github.bryceaspringfield.morphe.extension.reddit.FilterSettings.*;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This bundle's rows in Settings > Morphe: the Keyword filter category after Ads, with screens for topics,
 * subreddits, and profiles, and switches added to Morphe's own Navigation bar and Sidebar categories.
 */
final class SettingsUi {

    private static final String RULE_LINES = "One rule a line:\n"
            + "  r/name  hides a community\n"
            + "  u/name  hides an account\n"
            + "  flair:Meme  hides posts with that flair\n"
            + "  !flair:Meme  hides posts without it\n"
            + "  \"phrase\"  matches whole words\n"
            + "  any text  matches anywhere";
    private static final String RULE_HELP = RULE_LINES + "\n"
            + "A rule starting with r/name: or u/name: (for example r/pics: cat) moves to that subreddit's or "
            + "profile's filters.";
    private static final String COMMENT_HELP = "One rule a line: u/name hides an account's comments, \"phrase\" "
            + "matches whole words, and any other text matches anywhere in a comment.";

    private static final String[] FLAIR_MODES = {MODE_HIDE, MODE_SHOW, MODE_ONLY};
    private static final String[] FLAIR_LABELS = {"Off: hidden", "On: shown",
            "Only: shown, and posts without an Only flair hidden"};

    private interface Summary {
        String get();
    }

    private interface Click {
        void on(Preference preference);
    }

    private interface Getter {
        boolean get();
    }

    private interface Setter {
        void set(boolean on);
    }

    private interface Text {
        void on(String text);
    }

    /**
     * Stored post-type modes and range bounds, global or of one scope.
     */
    private interface Store {
        String get(String key);

        void set(String key, String value);
    }

    private static final Store GLOBAL_MODES = new Store() {
        public String get(String key) {
            return mode(key);
        }

        public void set(String key, String value) {
            putString(key, value);
        }
    };

    private static final Store GLOBAL_BOUNDS = new Store() {
        public String get(String key) {
            return getString(key, "");
        }

        public void set(String key, String value) {
            putString(key, value);
        }
    };

    private static Store scopeModes(String scope) {
        return new Store() {
            public String get(String key) {
                Scopes.Entry entry = Scopes.all().get(scope);
                return entry == null ? MODE_SHOW : entry.mode(key);
            }

            public void set(String key, String value) {
                Scopes.Entry entry = Scopes.edit(scope);
                Scopes.Entry.setMode(entry.types, key, value);
                Scopes.save(scope, entry);
            }
        };
    }

    private static Store scopeBounds(String scope) {
        return new Store() {
            public String get(String key) {
                Scopes.Entry entry = Scopes.all().get(scope);
                String value = entry == null ? null : entry.bounds.get(key);
                return value == null ? "" : value;
            }

            public void set(String key, String value) {
                Scopes.Entry entry = Scopes.edit(scope);
                entry.setBound(key, value);
                Scopes.save(scope, entry);
            }
        };
    }

    private SettingsUi() {
    }

    static void add(PreferenceFragment fragment) {
        PreferenceScreen screen = fragment.getPreferenceScreen();
        Context context = fragment.getActivity();
        PreferenceManager manager = fragment.getPreferenceManager();
        if (screen == null || context == null || manager == null) return;

        PreferenceCategory filter = newCategory(context, screen, "Keyword filter");
        placeAfter(screen, filter, categoryHolding(screen, "morphe_hide_post_ads"));
        item(context, filter, "Keywords to hide", () -> rulesSummary(Scopes.rulesOf(POST_RULES, "")),
                p -> editRules(context, p, POST_RULES, "", "Keywords to hide", RULE_HELP, null));
        item(context, filter, "Keywords apply to", SettingsUi::fieldsSummary,
                p -> chooseSet(context, p, "Keywords apply to", FIELDS, FIELD_KEYS, FIELD_LABELS, fields(),
                        SettingsUi::fieldsSummary));
        addTypes(context, filter, GLOBAL_MODES, null);
        addRanges(context, filter, GLOBAL_BOUNDS, null);
        addTopics(context, manager, filter);
        addScopeList(context, manager, filter, "r/");
        addScopeList(context, manager, filter, "u/");
        item(context, filter, "Where post filters apply", SettingsUi::placesSummary,
                p -> chooseSet(context, p, "Where post filters apply", PLACES, PLACE_KEYS, PLACE_LABELS, places(),
                        SettingsUi::placesSummary));
        item(context, filter, "Comment keywords to hide", () -> rulesSummary(Scopes.rulesOf(COMMENT_RULES, "")),
                p -> editRules(context, p, COMMENT_RULES, "", "Comment keywords to hide", COMMENT_HELP, null));
        item(context, filter, "Comment score (upvotes)",
                () -> rangeSummary(GLOBAL_BOUNDS, COMMENT_SCORE_MIN, COMMENT_SCORE_MAX),
                p -> editRange(context, p, "Comment score (upvotes)", GLOBAL_BOUNDS, COMMENT_SCORE_MIN,
                        COMMENT_SCORE_MAX, null));

        PreferenceCategory navigation = morpheCategory(context, screen, "morphe_hide_chat_button",
                "morphe_screen_navigation_bar_title", "Navigation bar");
        toggle(context, navigation, "Hide Inbox button", "Restart the app to apply",
                () -> getBoolean(HIDE_INBOX_TAB), on -> putBoolean(HIDE_INBOX_TAB, on));

        PreferenceCategory sidebar = morpheCategory(context, screen, "morphe_hide_recently_visited_shelf",
                "morphe_screen_sidebar_title", "Sidebar");
        // Morphe's own Recently Visited switch replaces ours; a row hidden with ours stays hidden through it.
        Preference morpheRecent = screen.findPreference("morphe_hide_recently_visited_shelf");
        Set<String> hiddenRows = getSet(SIDEBAR_HIDDEN, Collections.emptySet());
        if (morpheRecent instanceof SwitchPreference && hiddenRows.contains(SIDEBAR_RECENT)) {
            ((SwitchPreference) morpheRecent).setChecked(true);
            Set<String> rest = new HashSet<>(hiddenRows);
            rest.remove(SIDEBAR_RECENT);
            putSet(SIDEBAR_HIDDEN, rest);
        }
        for (int i = 0; i < SIDEBAR_KEYS.length; i++) {
            String row = SIDEBAR_KEYS[i];
            if (row.equals(SIDEBAR_RECENT) && morpheRecent != null) continue;
            toggle(context, sidebar, SIDEBAR_SWITCH_TITLES[i], null,
                    () -> getSet(SIDEBAR_HIDDEN, Collections.emptySet()).contains(row),
                    on -> {
                        Set<String> rows = new HashSet<>(getSet(SIDEBAR_HIDDEN, Collections.emptySet()));
                        if (on) rows.add(row);
                        else rows.remove(row);
                        putSet(SIDEBAR_HIDDEN, rows);
                    });
        }
        toggle(context, sidebar, "Hide sidebar button badge",
                "Hides the orange dot on the three-line button. Restart the app to apply",
                () -> getBoolean(HIDE_MENU_BADGE), on -> putBoolean(HIDE_MENU_BADGE, on));
    }

    // region Groups of rows

    private static void addTypes(Context context, PreferenceGroup group, Store modes, Runnable changed) {
        SwitchPreference[] switches = new SwitchPreference[TYPE_KEYS.length];
        Preference[] only = new Preference[1];
        for (int i = 0; i < TYPE_KEYS.length; i++) {
            String key = TYPE_KEYS[i];
            switches[i] = toggle(context, group, TYPE_SWITCH_TITLES[i], null,
                    () -> !MODE_HIDE.equals(modes.get(key)),
                    on -> {
                        modes.set(key, on ? MODE_SHOW : MODE_HIDE);
                        if (only[0] != null) only[0].setSummary(onlySummary(modes));
                        run(changed);
                    });
        }
        only[0] = item(context, group, "Show only these post types", () -> onlySummary(modes),
                p -> chooseOnly(context, p, modes, switches, changed));
    }

    private static void addRanges(Context context, PreferenceGroup group, Store bounds, Runnable changed) {
        item(context, group, "Score (upvotes)", () -> rangeSummary(bounds, POST_SCORE_MIN, POST_SCORE_MAX),
                p -> editRange(context, p, "Score (upvotes)", bounds, POST_SCORE_MIN, POST_SCORE_MAX, changed));
        item(context, group, "Number of comments",
                () -> rangeSummary(bounds, POST_COMMENTS_MIN, POST_COMMENTS_MAX),
                p -> editRange(context, p, "Number of comments", bounds, POST_COMMENTS_MIN, POST_COMMENTS_MAX,
                        changed));
    }

    private static void addTopics(Context context, PreferenceManager manager, PreferenceGroup parent) {
        PreferenceScreen topics = manager.createPreferenceScreen(context);
        topics.setTitle("Hide communities by topic");
        parent.addPreference(topics);
        topics.setSummary(topicsSummary());
        Runnable changed = () -> topics.setSummary(topicsSummary());

        PreferenceCategory lists = newCategory(context, topics, "Built-in lists");
        for (int i = 0; i < Topics.KEYS.length; i++) {
            String key = Topics.KEYS[i];
            CheckBoxPreference box = new CheckBoxPreference(context);
            box.setPersistent(false);
            box.setTitle(Topics.LABELS[i]);
            box.setSummary("Hides communities whose names contain " + Topics.patterns(i));
            lists.addPreference(box);
            box.setChecked(Topics.chosen(getSet(TOPICS, Collections.emptySet()), key));
            box.setOnPreferenceChangeListener((p, value) -> {
                Set<String> chosen = new HashSet<>(getSet(TOPICS, Collections.emptySet()));
                chosen.remove(key);
                if (key.equals("fascism")) chosen.remove(Topics.LEGACY_NAZISM);
                if ((Boolean) value) chosen.add(key);
                putSet(TOPICS, chosen);
                changed.run();
                return true;
            });
        }
        Preference note = new Preference(context);
        note.setSelectable(false);
        note.setSummary("A name containing " + Topics.exceptions() + " is never hidden by a built-in list.");
        lists.addPreference(note);

        PreferenceCategory own = newCategory(context, topics, "Your own list");
        item(context, own, "Communities to hide", SettingsUi::topicExtraSummary,
                p -> editTopicExtra(context, p, changed));
    }

    /**
     * The screen listing every subreddit ("r/") or profile ("u/") with filters, each opening its own screen.
     */
    private static void addScopeList(Context context, PreferenceManager manager, PreferenceGroup parent, String kind) {
        boolean community = kind.equals("r/");
        PreferenceScreen list = manager.createPreferenceScreen(context);
        list.setTitle(community ? "Subreddit filters" : "Profile filters");
        parent.addPreference(list);
        list.setSummary(listSummary(kind));

        PreferenceCategory scopes = newCategory(context, list,
                community ? "Subreddits with filters" : "Profiles with filters");
        Runnable listChanged = () -> list.setSummary(listSummary(kind));
        Preference add = new Preference(context);
        add.setTitle(community ? "Add a subreddit" : "Add a profile");
        add.setSummary(community
                ? "Filters for one subreddit's posts, wherever they appear"
                : "Filters for one account's posts, wherever they appear");
        add.setOrder(-1);
        list.addPreference(add);
        add.setOnPreferenceClickListener(p -> {
            askText(context, add.getTitle().toString(), community ? "r/name" : "u/name", text -> {
                String name = text.trim().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith(kind)) name = name.substring(2);
                if (name.isEmpty() || name.contains(" ") || name.contains("/")) return;
                String scope = Rules.scopeKey(kind + name);
                if (scopes.findPreference(scope) != null) return;
                Scopes.save(scope, Scopes.edit(scope));
                addScope(context, manager, scopes, scope, listChanged);
                listChanged.run();
            });
            return true;
        });
        for (String scope : Scopes.scopes(kind)) addScope(context, manager, scopes, scope, listChanged);
    }

    private static void addScope(Context context, PreferenceManager manager, PreferenceCategory parent, String scope,
                                 Runnable listChanged) {
        boolean community = scope.startsWith("r/");
        PreferenceScreen screen = manager.createPreferenceScreen(context);
        screen.setKey(scope);
        screen.setTitle(scope);
        screen.setPersistent(false);
        parent.addPreference(screen);
        screen.setSummary(scopeSummary(scope));
        Runnable changed = () -> {
            screen.setSummary(scopeSummary(scope));
            listChanged.run();
        };

        toggle(context, screen, community ? "Hide all posts from " + scope : "Hide all posts and comments by " + scope,
                null, () -> Scopes.hiddenWhole(scope), on -> {
                    Scopes.setHiddenWhole(scope, on);
                    changed.run();
                });
        String where = community ? "in " + scope : "by " + scope;
        item(context, screen, "Keywords to hide", () -> rulesSummary(Scopes.rulesOf(POST_RULES, scope)),
                p -> editRules(context, p, POST_RULES, scope, "Keywords to hide",
                        "Rules for posts " + where + ". " + RULE_LINES, changed));
        item(context, screen, "Comment keywords to hide", () -> rulesSummary(Scopes.rulesOf(COMMENT_RULES, scope)),
                p -> editRules(context, p, COMMENT_RULES, scope, "Comment keywords to hide",
                        "Rules for comments " + where + ". " + COMMENT_HELP, changed));

        PreferenceCategory types = newCategory(context, screen, "Post types");
        addTypes(context, types, scopeModes(scope), changed);

        if (community) {
            String name = scope.substring(2);
            PreferenceCategory flairs = newCategory(context, screen, "Flairs");
            List<String> known = Scopes.flairsOf(name, Scopes.edit(scope));
            if (known.isEmpty()) {
                Preference none = new Preference(context);
                none.setSelectable(false);
                none.setSummary("No flairs seen in " + scope + " yet. Its flairs are listed here once its posts "
                        + "load, or add one by name.");
                flairs.addPreference(none);
            }
            for (String flair : known) addFlair(context, flairs, scope, flair, changed);
            Preference addFlair = new Preference(context);
            addFlair.setTitle("Add a flair");
            flairs.addPreference(addFlair);
            addFlair.setOnPreferenceClickListener(p -> {
                askText(context, "Add a flair", "Flair", text -> {
                    String flair = text.trim().toLowerCase(java.util.Locale.ROOT);
                    if (flair.isEmpty() || flairs.findPreference("flair:" + flair) != null) return;
                    Preference row = addFlair(context, flairs, scope, flair, changed);
                    chooseFlair(context, row, scope, flair, changed);
                });
                return true;
            });
        }

        PreferenceCategory ranges = newCategory(context, screen, "Ranges");
        addRanges(context, ranges, scopeBounds(scope), changed);

        Preference remove = new Preference(context);
        remove.setTitle("Remove all filters for " + scope);
        screen.addPreference(remove);
        remove.setOnPreferenceClickListener(p -> {
            new AlertDialog.Builder(context)
                    .setTitle(remove.getTitle())
                    .setMessage("Every filter of " + scope + " is deleted.")
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        Scopes.clear(scope);
                        Dialog open = screen.getDialog();
                        if (open != null) open.dismiss();
                        parent.removePreference(screen);
                        listChanged.run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
    }

    private static Preference addFlair(Context context, PreferenceGroup group, String scope, String flair,
                                       Runnable changed) {
        Preference row = new Preference(context);
        row.setKey("flair:" + flair);
        row.setPersistent(false);
        row.setTitle(flair);
        group.addPreference(row);
        row.setSummary(flairLabel(Scopes.edit(scope).flairMode(flair)));
        row.setOnPreferenceClickListener(p -> {
            chooseFlair(context, p, scope, flair, changed);
            return true;
        });
        return row;
    }

    private static void chooseFlair(Context context, Preference row, String scope, String flair, Runnable changed) {
        int current = Arrays.asList(FLAIR_MODES).indexOf(Scopes.edit(scope).flairMode(flair));
        new AlertDialog.Builder(context)
                .setTitle(flair)
                .setSingleChoiceItems(FLAIR_LABELS, current, (dialog, which) -> {
                    Scopes.Entry entry = Scopes.edit(scope);
                    Scopes.Entry.setMode(entry.flairs, flair, FLAIR_MODES[which]);
                    Scopes.save(scope, entry);
                    row.setSummary(FLAIR_LABELS[which]);
                    run(changed);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // endregion

    // region Summaries

    private static String flairLabel(String mode) {
        int index = Arrays.asList(FLAIR_MODES).indexOf(mode);
        return FLAIR_LABELS[index < 0 ? 1 : index];
    }

    private static String rulesSummary(List<String> lines) {
        int count = lines.size();
        return count == 0 ? "No rules" : count + (count == 1 ? " rule" : " rules");
    }

    private static String onlySummary(Store modes) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < TYPE_KEYS.length; i++) {
            if (MODE_ONLY.equals(modes.get(TYPE_KEYS[i]))) names.add(TYPE_LABELS[i]);
        }
        return names.isEmpty() ? "No limit" : "Only: " + String.join(", ", names);
    }

    private static String fieldsSummary() {
        return labelsOf(fields(), FIELD_KEYS, FIELD_LABELS, "Nothing (keyword rules match no post)");
    }

    private static String placesSummary() {
        return labelsOf(places(), PLACE_KEYS, PLACE_LABELS, "Nowhere (post filters are off)");
    }

    private static String labelsOf(Set<String> chosen, String[] keys, String[] labels, String none) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            if (chosen.contains(keys[i])) names.add(labels[i]);
        }
        return names.isEmpty() ? none : String.join(", ", names);
    }

    private static String topicsSummary() {
        Set<String> topics = getSet(TOPICS, Collections.emptySet());
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Topics.KEYS.length; i++) {
            if (Topics.chosen(topics, Topics.KEYS[i])) names.add(Topics.LABELS[i]);
        }
        int extra = Filter.topicExtra().size();
        if (extra > 0) names.add(extra + (extra == 1 ? " community" : " communities") + " of your own list");
        return names.isEmpty() ? "None hidden" : "Hidden: " + String.join("; ", names);
    }

    private static String topicExtraSummary() {
        int count = Filter.topicExtra().size();
        return count == 0 ? "None" : count + (count == 1 ? " community" : " communities");
    }

    private static Long parseBound(String value) {
        value = value == null ? "" : value.trim().replace(",", "");
        if (value.isEmpty()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String rangeSummary(Store bounds, String minKey, String maxKey) {
        Long min = parseBound(bounds.get(minKey));
        Long max = parseBound(bounds.get(maxKey));
        if (min == null && max == null) return "All shown";
        if (max == null) return "Shown: " + min + " or more; the rest hidden";
        if (min == null) return "Shown: " + max + " or fewer; the rest hidden";
        return "Shown: " + min + " to " + max + "; the rest hidden";
    }

    private static String listSummary(String kind) {
        List<String> scopes = new ArrayList<>(Scopes.scopes(kind));
        if (scopes.isEmpty()) return "None";
        String first = String.join(", ", scopes.subList(0, Math.min(3, scopes.size())));
        return scopes.size() <= 3 ? first : first + ", and " + (scopes.size() - 3) + " more";
    }

    /**
     * One line naming every filter of a subreddit or profile.
     */
    static String scopeSummary(String scope) {
        List<String> parts = new ArrayList<>();
        if (Scopes.hiddenWhole(scope)) parts.add(scope.startsWith("r/") ? "All posts hidden" : "All posts and comments hidden");
        int posts = Scopes.rulesOf(POST_RULES, scope).size();
        if (posts > 0) parts.add(posts + (posts == 1 ? " keyword rule" : " keyword rules"));
        int comments = Scopes.rulesOf(COMMENT_RULES, scope).size();
        if (comments > 0) parts.add(comments + (comments == 1 ? " comment rule" : " comment rules"));
        Scopes.Entry entry = Scopes.all().get(scope);
        if (entry != null) {
            List<String> hidden = new ArrayList<>();
            List<String> only = new ArrayList<>();
            for (int i = 0; i < TYPE_KEYS.length; i++) {
                String mode = entry.mode(TYPE_KEYS[i]);
                if (MODE_HIDE.equals(mode)) hidden.add(TYPE_LABELS[i]);
                if (MODE_ONLY.equals(mode)) only.add(TYPE_LABELS[i]);
            }
            for (Map.Entry<String, String> flair : entry.flairs.entrySet()) {
                if (MODE_HIDE.equals(flair.getValue())) hidden.add("flair " + flair.getKey());
                if (MODE_ONLY.equals(flair.getValue())) only.add("flair " + flair.getKey());
            }
            if (!hidden.isEmpty()) parts.add("hidden: " + String.join(", ", hidden));
            if (!only.isEmpty()) parts.add("only: " + String.join(", ", only));
            Store bounds = scopeBounds(scope);
            String score = rangeSummary(bounds, POST_SCORE_MIN, POST_SCORE_MAX);
            if (!score.equals("All shown")) parts.add("score " + score.replace("Shown: ", "").replace("; the rest hidden", ""));
            String count = rangeSummary(bounds, POST_COMMENTS_MIN, POST_COMMENTS_MAX);
            if (!count.equals("All shown")) parts.add("comments " + count.replace("Shown: ", "").replace("; the rest hidden", ""));
        }
        if (parts.isEmpty()) return "No filters";
        String text = String.join("; ", parts);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // endregion

    // region Rows and dialogs

    private static void run(Runnable runnable) {
        if (runnable != null) runnable.run();
    }

    private static PreferenceCategory newCategory(Context context, PreferenceGroup group, String title) {
        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(title);
        group.addPreference(category);
        return category;
    }

    /**
     * The screen's category holding the preference {@code key}, or null.
     */
    private static PreferenceCategory categoryHolding(PreferenceScreen screen, String key) {
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference child = screen.getPreference(i);
            if (child instanceof PreferenceCategory && ((PreferenceCategory) child).findPreference(key) != null) {
                return (PreferenceCategory) child;
            }
        }
        return null;
    }

    /**
     * The app's string resource {@code name} (one Morphe Patches adds), or null.
     */
    private static String morpheString(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? null : context.getString(id);
    }

    private static PreferenceCategory categoryTitled(Context context, PreferenceScreen screen, String titleName) {
        String title = morpheString(context, titleName);
        if (title == null) return null;
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference child = screen.getPreference(i);
            if (child instanceof PreferenceCategory && child.getTitle() != null
                    && title.contentEquals(child.getTitle())) {
                return (PreferenceCategory) child;
            }
        }
        return null;
    }

    /**
     * Morphe's category holding {@code key}, else a new category with Morphe's title (or {@code fallback}).
     */
    private static PreferenceCategory morpheCategory(Context context, PreferenceScreen screen, String key,
                                                     String titleName, String fallback) {
        PreferenceCategory category = categoryHolding(screen, key);
        if (category != null) return category;
        category = categoryTitled(context, screen, titleName);
        if (category != null) return category;
        String title = morpheString(context, titleName);
        return newCategory(context, screen, title == null ? fallback : title);
    }

    /**
     * Moves {@code category} right after {@code anchor} (to the top when anchor is null); the screen sorts its
     * categories by order when the list is drawn.
     */
    private static void placeAfter(PreferenceScreen screen, PreferenceCategory category, Preference anchor) {
        int base = anchor == null ? -1 : anchor.getOrder();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference child = screen.getPreference(i);
            if (child != category && child.getOrder() > base) child.setOrder(child.getOrder() + 1);
        }
        category.setOrder(base + 1);
    }

    private static Preference item(Context context, PreferenceGroup group, String title, Summary summary, Click click) {
        Preference preference = new Preference(context);
        preference.setTitle(title);
        preference.setPersistent(false);
        group.addPreference(preference);
        preference.setSummary(summary.get());
        preference.setOnPreferenceClickListener(p -> {
            try {
                click.on(p);
            } catch (Exception ex) {
                Log.e(TAG, "Settings dialog failure", ex);
            }
            return true;
        });
        return preference;
    }

    /**
     * A switch like Morphe's own, stored in this bundle's settings file.
     */
    private static SwitchPreference toggle(Context context, PreferenceGroup group, String title, String summary,
                                           Getter getter, Setter setter) {
        SwitchPreference preference = new SwitchPreference(context);
        preference.setPersistent(false);
        preference.setTitle(title);
        if (summary != null) preference.setSummary(summary);
        group.addPreference(preference);
        preference.setChecked(getter.get());
        preference.setOnPreferenceChangeListener((p, value) -> {
            try {
                setter.set((Boolean) value);
            } catch (Exception ex) {
                Log.e(TAG, "Settings switch failure", ex);
            }
            return true;
        });
        return preference;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout layout(Context context, String help) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0);
        if (help != null) {
            TextView text = new TextView(context);
            text.setText(help);
            text.setTextIsSelectable(true);
            layout.addView(text);
        }
        return layout;
    }

    private static void askText(Context context, String title, String hint, Text done) {
        EditText editor = new EditText(context);
        editor.setSingleLine(true);
        editor.setHint(hint);
        LinearLayout layout = layout(context, null);
        layout.addView(editor);
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> done.on(editor.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void editRules(Context context, Preference preference, String listKey, String scope, String title,
                                  String help, Runnable changed) {
        EditText editor = new EditText(context);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setSingleLine(false);
        editor.setMinLines(4);
        editor.setText(String.join("\n", Scopes.rulesOf(listKey, scope)));
        editor.setHint(scope.isEmpty() ? "r/community\nu/account\nflair:Meme\n\"whole phrase\"\nany text"
                : "\"whole phrase\"\nany text\nflair:Meme");
        LinearLayout layout = layout(context, help);
        layout.addView(editor);
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Scopes.setRulesOf(listKey, scope, editor.getText().toString());
                    preference.setSummary(rulesSummary(Scopes.rulesOf(listKey, scope)));
                    run(changed);
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

    private static void chooseOnly(Context context, Preference preference, Store modes, SwitchPreference[] switches,
                                   Runnable changed) {
        boolean[] checked = new boolean[TYPE_KEYS.length];
        for (int i = 0; i < TYPE_KEYS.length; i++) checked[i] = MODE_ONLY.equals(modes.get(TYPE_KEYS[i]));
        new AlertDialog.Builder(context)
                .setTitle("Show only these post types")
                .setMultiChoiceItems(TYPE_LABELS, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    for (int i = 0; i < TYPE_KEYS.length; i++) {
                        if (checked[i]) {
                            modes.set(TYPE_KEYS[i], MODE_ONLY);
                            switches[i].setChecked(true);
                        } else if (MODE_ONLY.equals(modes.get(TYPE_KEYS[i]))) {
                            modes.set(TYPE_KEYS[i], MODE_SHOW);
                        }
                    }
                    preference.setSummary(onlySummary(modes));
                    run(changed);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void editRange(Context context, Preference preference, String title, Store bounds, String minKey,
                                  String maxKey, Runnable changed) {
        EditText min = new EditText(context);
        min.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        min.setHint("Lowest shown (blank: no lower bound)");
        min.setText(bounds.get(minKey));
        EditText max = new EditText(context);
        max.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        max.setHint("Highest shown (blank: no upper bound)");
        max.setText(bounds.get(maxKey));
        LinearLayout layout = layout(context, "Posts outside this range are hidden. Leave a box blank for no "
                + "bound. A count the app shows rounded (for example 1.2k) is compared as 1,200.");
        layout.addView(min);
        layout.addView(max);
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    bounds.set(minKey, min.getText().toString().trim());
                    bounds.set(maxKey, max.getText().toString().trim());
                    preference.setSummary(rangeSummary(bounds, minKey, maxKey));
                    run(changed);
                })
                .setNeutralButton("Clear", (dialog, which) -> {
                    bounds.set(minKey, "");
                    bounds.set(maxKey, "");
                    preference.setSummary(rangeSummary(bounds, minKey, maxKey));
                    run(changed);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void editTopicExtra(Context context, Preference preference, Runnable changed) {
        EditText editor = new EditText(context);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setSingleLine(false);
        editor.setMinLines(4);
        editor.setText(getString(TOPIC_EXTRA, ""));
        editor.setHint("r/community");
        LinearLayout layout = layout(context, "Communities to hide, one a line, in addition to the built-in lists "
                + "checked.");
        layout.addView(editor);
        new AlertDialog.Builder(context)
                .setTitle("Communities to hide")
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    putString(TOPIC_EXTRA, editor.getText().toString().trim());
                    preference.setSummary(topicExtraSummary());
                    run(changed);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // endregion
}
