package io.github.bryceaspringfield.morphe.extension.reddit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The filter's settings, kept in the app's own preferences file {@code reddit_keyword_filter}.
 * Every filter shows everything until the user sets it, so an upgrade adding a filter changes nothing.
 */
@SuppressWarnings("unused")
final class FilterSettings {

    static final String TAG = "RedditKeywordFilter";
    private static final String PREFERENCES = "reddit_keyword_filter";

    // Post rules (the key "rules" is the 1.0 keyword list, kept so existing lists carry over).
    static final String POST_RULES = "rules";
    static final String COMMENT_RULES = "comment_rules";

    // The parts of a post a keyword rule is matched against.
    static final String FIELDS = "fields";
    static final String FIELD_TITLE = "title";
    static final String FIELD_BODY = "body";
    static final String FIELD_FLAIR = "flair";
    static final String FIELD_POSTER = "poster";
    static final String FIELD_COMMUNITY = "community";
    static final String FIELD_DOMAIN = "domain";
    static final String[] FIELD_KEYS = {FIELD_TITLE, FIELD_BODY, FIELD_FLAIR, FIELD_POSTER, FIELD_COMMUNITY, FIELD_DOMAIN};
    static final String[] FIELD_LABELS = {"Post title", "Post text", "Flair", "Poster (u/name)", "Community (r/name)", "Link domain"};
    private static final Set<String> DEFAULT_FIELDS = new HashSet<>(Arrays.asList(FIELD_TITLE, FIELD_BODY));

    // Post types: shown (the default), hidden, or shown with every other type hidden. The stored values are those
    // of 1.1, where "off" meant no filter (shown), so a choice carries over.
    static final String MODE_SHOW = "off";
    static final String MODE_HIDE = "hide";
    static final String MODE_ONLY = "only";
    static final String TYPE_TEXT = "type_text";
    static final String TYPE_PHOTO = "type_photo";
    static final String TYPE_VIDEO = "type_video";
    static final String TYPE_REPOST = "type_repost";
    static final String TYPE_NSFW = "type_nsfw";
    static final String[] TYPE_KEYS = {TYPE_TEXT, TYPE_PHOTO, TYPE_VIDEO, TYPE_REPOST, TYPE_NSFW};
    static final String[] TYPE_LABELS = {"Text-only posts", "Photo posts", "Video posts", "Reposts (crossposts)", "18+ posts"};
    static final String[] TYPE_SWITCH_TITLES = {"Show text-only posts", "Show photo posts", "Show video posts",
            "Show reposts (crossposts)", "Show 18+ posts"};

    // Ranges; a blank bound is no bound.
    static final String POST_SCORE_MIN = "post_score_min";
    static final String POST_SCORE_MAX = "post_score_max";
    static final String POST_COMMENTS_MIN = "post_comments_min";
    static final String POST_COMMENTS_MAX = "post_comments_max";
    static final String COMMENT_SCORE_MIN = "comment_score_min";
    static final String COMMENT_SCORE_MAX = "comment_score_max";

    // Communities hidden by topic.
    static final String TOPICS = "topics";
    static final String TOPIC_EXTRA = "topic_extra";

    // Where the post filters apply.
    static final String PLACES = "places";
    static final String PLACE_HOME = "home";
    static final String PLACE_POPULAR = "popular";
    static final String PLACE_NEWS = "news";
    static final String PLACE_COMMUNITY = "community";
    static final String PLACE_SEARCH = "search";
    static final String PLACE_PROFILE = "profile";
    static final String PLACE_OTHER = "other";
    static final String[] PLACE_KEYS = {PLACE_HOME, PLACE_POPULAR, PLACE_NEWS, PLACE_COMMUNITY, PLACE_SEARCH, PLACE_PROFILE, PLACE_OTHER};
    static final String[] PLACE_LABELS = {"Home", "Popular", "News", "Community pages", "Search results", "Profiles",
            "Other feeds (custom feeds, Latest, and the rest)"};

    // Navigation.
    static final String HIDE_INBOX_TAB = "hide_inbox_tab";
    static final String HIDE_MENU_BADGE = "hide_menu_badge";
    static final String SIDEBAR_HIDDEN = "sidebar_hidden";

    /**
     * The sidebar rows the user can hide: the string resource name of each row's title, and the recently visited
     * section by its header's enum name.
     */
    static final String SIDEBAR_RECENT = "RECENTLY_VISITED";
    static final String[] SIDEBAR_KEYS = {"popular_feed_label", "news_feed_label", "latest_feed_label",
            "communities_cta_title", "label_start_a_community", "label_custom_feeds", SIDEBAR_RECENT};
    static final String[] SIDEBAR_SWITCH_TITLES = {"Hide Popular", "Hide News", "Hide Latest",
            "Hide Discover communities", "Hide Start a community", "Hide Custom feeds", "Hide Recently Visited shelf"};

    static final String DEBUG = "debug";

    @SuppressLint("StaticFieldLeak")
    private static volatile SharedPreferences preferences;

    private FilterSettings() {
    }

    @SuppressLint("PrivateApi")
    static SharedPreferences preferences() {
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
            Log.e(TAG, "Could not open the filter settings", ex);
            return null;
        }
    }

    static String getString(String key, String fallback) {
        SharedPreferences current = preferences();
        if (current == null) return fallback;
        try {
            return current.getString(key, fallback);
        } catch (ClassCastException ex) {
            return fallback;
        }
    }

    static void putString(String key, String value) {
        SharedPreferences current = preferences();
        if (current != null) current.edit().putString(key, value).apply();
    }

    static boolean getBoolean(String key) {
        SharedPreferences current = preferences();
        if (current == null) return false;
        try {
            return current.getBoolean(key, false);
        } catch (ClassCastException ex) {
            return false;
        }
    }

    static void putBoolean(String key, boolean value) {
        SharedPreferences current = preferences();
        if (current != null) current.edit().putBoolean(key, value).apply();
    }

    /**
     * A set stored as comma-separated keys; {@code fallback} applies when the key was never saved.
     */
    static Set<String> getSet(String key, Set<String> fallback) {
        SharedPreferences current = preferences();
        if (current == null) return fallback;
        String value;
        try {
            value = current.getString(key, null);
        } catch (ClassCastException ex) {
            // A string set (as other tools write) is read as it is; any other type counts as never saved.
            try {
                Set<String> stored = current.getStringSet(key, null);
                if (stored == null) return fallback;
                Set<String> set = new HashSet<>();
                for (String part : stored) if (part != null && !part.trim().isEmpty()) set.add(part.trim());
                return set;
            } catch (ClassCastException other) {
                return fallback;
            }
        }
        if (value == null) return fallback;
        Set<String> set = new HashSet<>();
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) set.add(part.trim());
        }
        return set;
    }

    static void putSet(String key, Set<String> value) {
        putString(key, String.join(",", value));
    }

    static Set<String> fields() {
        return getSet(FIELDS, DEFAULT_FIELDS);
    }

    static Set<String> places() {
        return getSet(PLACES, new HashSet<>(Arrays.asList(PLACE_KEYS)));
    }

    static String mode(String typeKey) {
        return getString(typeKey, MODE_SHOW);
    }

    /**
     * A stored bound, or null when blank or unreadable.
     */
    static Long bound(String key) {
        String value = getString(key, "").trim().replace(",", "");
        if (value.isEmpty()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
