package io.github.bryceaspringfield.morphe.extension.kick;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * A chat socket's listener, passing on every message but the chat messages the filters hide (Pusher events whose
 * data is a chat message from a hidden user or with a hidden word).
 */
final class KickChat extends WebSocketListener {

    private static final Set<String> EVENTS_SEEN = Collections.synchronizedSet(new HashSet<>());
    private final WebSocketListener inner;

    KickChat(WebSocketListener inner) {
        this.inner = inner;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        inner.onOpen(webSocket, response);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        String out = filter(text);
        if (out != null) inner.onMessage(webSocket, out);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        if (Kick.debug() && framesLogged < 6) {
            framesLogged++;
            Kick.log("socket binary frame, " + bytes.size() + " bytes: " + bytes.hex().substring(0, Math.min(200, bytes.size() * 2)));
        }
        inner.onMessage(webSocket, bytes);
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        inner.onClosing(webSocket, code, reason);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        inner.onClosed(webSocket, code, reason);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        inner.onFailure(webSocket, t, response);
    }

    private static int framesLogged;

    /**
     * The frame without its hidden chat messages; null when every message of the frame is hidden. KICK's chat runs
     * on Centrifugo (one JSON reply a line, a publication at push.pub.data); Pusher frames (event and data) are read
     * too.
     */
    static String filter(String text) {
        try {
            if (text == null || text.length() > 500_000) return text;
            if (Kick.debug() && framesLogged < 6 && text.contains("content")) {
                framesLogged++;
                Kick.log("socket frame: " + text.substring(0, Math.min(900, text.length())));
            }
            KickHttp.Filters f = KickHttp.Filters.now();
            if (f.chatWords.isEmpty() && f.chatUsers.isEmpty()) return text;
            String[] lines = text.split("\n", -1);
            StringBuilder out = new StringBuilder();
            int kept = 0, hidden = 0;
            for (String line : lines) {
                if (hiddenLine(line, f)) {
                    hidden++;
                    continue;
                }
                if (kept++ > 0) out.append('\n');
                out.append(line);
            }
            if (hidden == 0) return text;
            return kept == 0 ? null : out.toString();
        } catch (Throwable t) {
            return text;
        }
    }

    private static boolean hiddenLine(String line, KickHttp.Filters f) {
        try {
            if (!line.startsWith("{") || !line.contains("content")) return false;
            JSONObject e = new JSONObject(line);
            Object d = null;
            JSONObject push = e.optJSONObject("push");
            JSONObject pub = push == null ? e.optJSONObject("pub") : push.optJSONObject("pub");
            if (pub != null) d = pub.opt("data");
            else if (e.has("event")) {
                String event = e.optString("event");
                if (Kick.debug() && EVENTS_SEEN.add(event)) Kick.log("socket event " + event);
                d = e.opt("data");
            }
            JSONObject data = d instanceof JSONObject j ? j : d instanceof String s && s.startsWith("{") ? new JSONObject(s) : null;
            if (data == null) return false;
            String why = f.hides(data);
            if (why != null) Kick.log("chat message hidden: " + why);
            return why != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
