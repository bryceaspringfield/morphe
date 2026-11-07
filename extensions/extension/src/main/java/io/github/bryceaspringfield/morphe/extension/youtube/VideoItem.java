package io.github.bryceaspringfield.morphe.extension.youtube;


import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.patches.components.BufferPhraseFilter;

/**
 * What a feed item's Litho buffer states about the item: its video, channel (ID, handle, and name), length, and type.
 * Read from the buffer's text, since the item's model is not reachable from the filter. Example strings of a video
 * lockup (YouTube 21.44): "UCuAXFkgsw1L7xaCfnd5JJOw", "/@RickAstleyYT", "3:33", "Go to channel Rick Astley",
 * "https://www.youtube.com/watch?v=dQw4w9WgXcQ", and for a live stream "yt_outline_radar_live_black_24".
 */
final class VideoItem {

    private static final Pattern CHANNEL_ID = Pattern.compile("(?<![\\w-])(UC[\\w-]{22})(?![\\w-])");
    private static final Pattern HANDLE = Pattern.compile("/@([\\w.\\-]{3,30}?)(?=[^\\w.\\-]|.?https?://|$)");
    /** The live badge's icon: yt_outline_radar_live_black_24 (Nov 6, 2026 early), yt_outline_experimental_live_black_24 (later). */
    private static final Pattern LIVE_ICON = Pattern.compile("yt_(?:outline|fill)_[a-z_]*?live_(?:black|white)_\\d+");
    private static final Pattern DURATION = Pattern.compile("(?<![\\d:])(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?![\\d:])");
    /** "Go to channel X" in the languages the bundle knows; the buffer states the channel name only this way. */
    private static final String[] GO_TO_CHANNEL = {"Go to channel ", "Ir al canal ", "Aller sur la chaîne ",
            "Zum Kanal ", "Vai al canale ", "Ir para o canal "};

    final String videoId;
    final String channelId;
    final String handle;
    final String channelName;
    /** Length in seconds, or -1 where the item states none (live streams, playlists, posts). */
    final int seconds;
    final boolean live;
    final boolean playlist;
    final boolean mix;
    final boolean shorts;
    /**
     * The buffer's readable strings (runs with a space, outside URLs and tokens), lowercase and joined by newlines,
     * for keyword and topic rules; random tokens in the buffer would match short patterns by chance.
     */
    final String text;

    private VideoItem(String videoId, String channelId, String handle,
                      String channelName, int seconds, boolean live, boolean playlist, boolean mix,
                      boolean shorts, String text) {
        this.videoId = videoId;
        this.channelId = channelId;
        this.handle = handle;
        this.channelName = channelName;
        this.seconds = seconds;
        this.live = live;
        this.playlist = playlist;
        this.mix = mix;
        this.shorts = shorts;
        this.text = text;
    }

    static VideoItem parse(byte[] buffer, CharSequence path) {
        String text = new String(buffer, StandardCharsets.UTF_8);
        String videoId = null;
        try {
            videoId = BufferPhraseFilter.extractVideoIdFromBuffer(buffer);
        } catch (Throwable ignored) {
        }
        Matcher m = CHANNEL_ID.matcher(text);
        String channelId = m.find() ? m.group(1) : null;
        m = HANDLE.matcher(text);
        String handle = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
        String name = null;
        for (String prefix : GO_TO_CHANNEL) {
            int i = text.indexOf(prefix);
            if (i >= 0) {
                int start = i + prefix.length();
                int end = start;
                while (end < text.length() && text.charAt(end) >= ' ' && text.charAt(end) != '\uFFFD' && end - start < 100) end++;
                name = text.substring(start, end).trim();
                break;
            }
        }
        int seconds = -1;
        m = DURATION.matcher(text);
        while (m.find()) {
            int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            int s = Integer.parseInt(m.group(3));
            if (s < 60 && (h == 0 || min < 60)) {
                seconds = h * 3600 + min * 60 + s;
                break;
            }
        }
        String p = path.toString();
        // Path only: "shorts-lockup-image" is in every buffer.
        boolean shorts = p.startsWith("shorts") || p.startsWith("inline_shorts") || p.contains("shorts_lockup");
        boolean live = LIVE_ICON.matcher(text).find() || text.contains("BADGE_STYLE_TYPE_LIVE_NOW");
        boolean playlist = text.contains("youtube.com/playlist?list=PL") && !text.contains("youtube.com/watch?v=");
        boolean mix = !playlist && text.contains("youtube.com/playlist?list=RD") && !text.contains("youtube.com/watch?v=");
        if (live || playlist || mix) seconds = -1;
        return new VideoItem(videoId, channelId, handle, name, seconds, live, playlist, mix, shorts,
                withoutQuery(readable(text), text));
    }

    /**
     * Removes the search query from the readable text: every search result's buffer repeats it after the marker
     * "searchR", and keyword or topic rules would otherwise hide every result of a search naming them.
     */
    private static String withoutQuery(String readable, String text) {
        int at = text.indexOf("searchR");
        if (at < 0) return readable;
        int i = at + "searchR".length();
        while (i < text.length() && (text.charAt(i) < ' ' || text.charAt(i) == '\uFFFD')) i++;
        int end = i;
        while (end < text.length() && text.charAt(end) >= ' ' && text.charAt(end) != '\uFFFD') end++;
        String query = text.substring(i, end).trim().toLowerCase(Locale.ROOT);
        if (query.length() < 2) return readable;
        // Only the query's own string goes; a title containing the query stays.
        StringBuilder out = new StringBuilder();
        for (String line : readable.split("\n")) if (!line.equals(query)) out.append(line).append('\n');
        return out.toString();
    }

    private static String readable(String text) {
        StringBuilder out = new StringBuilder();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            char ch = i < text.length() ? text.charAt(i) : '\u0000';
            if (ch < ' ' || ch == '\uFFFD') {
                if (i - start >= 4) {
                    String run = text.substring(start, i).trim();
                    if (run.indexOf(' ') > 0 && !run.contains("://") && !run.startsWith("yt_")) {
                        out.append(run.toLowerCase(Locale.ROOT)).append('\n');
                    }
                }
                start = i + 1;
            }
        }
        return out.toString();
    }

    /** Whether the item belongs to the channel a rule names (an ID starting with UC, an @handle, or a name). */
    boolean isChannel(ChannelRules.Channel c) {
        if (c.id != null && (c.id.equals(channelId) || (handle != null && handle.equals(Channels.handleOf(c.id))))) return true;
        if (c.handle != null && (c.handle.equals(handle) || (channelId != null && channelId.equals(Channels.idOf(c.handle))))) return true;
        if (c.name == null) return false;
        if (channelName != null && c.name.equalsIgnoreCase(channelName)) return true;
        return channelId != null && channelId.equals(Channels.idOfName(c.name));
    }

    @Override
    public String toString() {
        return "video=" + videoId + " channel=" + channelId + " @" + handle + " name=" + channelName
                + " seconds=" + seconds + (live ? " live" : "") + (playlist ? " playlist" : "")
                + (mix ? " mix" : "") + (shorts ? " shorts" : "");
    }
}
