package io.github.bryceaspringfield.morphe.extension.youtube;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;

/**
 * Automatic "Not interested" feedback for videos the filters hide and for watched videos. Each video is reported
 * once a session. Sending needs the signed-in account's feedback tokens, read from the feed (not yet implemented in
 * this version: the videos are only recorded).
 */
final class Feedback {

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private Feedback() {
    }

    static void onHidden(VideoItem item, String reason) {
        if (!XSettings.AUTO_DISLIKE_FILTERED.get() || item.videoId == null || !SEEN.add(item.videoId)) return;
        Logger.printDebug(() -> "YouTube Extras: would send Not interested (don't like) for " + item.videoId + ", " + reason);
    }
}
