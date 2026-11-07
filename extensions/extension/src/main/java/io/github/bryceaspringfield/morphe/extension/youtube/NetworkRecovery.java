package io.github.bryceaspringfield.morphe.extension.youtube;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * Recovers a video stuck loading after the connection changes or returns: when a network becomes the default, the
 * player's position is read twice 4 s apart (from 3 s on), and a player whose position has not moved is sent a seek
 * to that same position, which makes it request the stream again on the new connection.
 */
final class NetworkRecovery {

    private static boolean registered;

    private NetworkRecovery() {
    }

    static synchronized void register() {
        if (registered) return;
        registered = true;
        try {
            ConnectivityManager cm = (ConnectivityManager) Utils.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (XSettings.FIX_NETWORK_RECOVERY.get()) Utils.runOnMainThreadDelayed(() -> check(0), 3000);
                }
            });
        } catch (Throwable t) {
            Logger.printException(() -> "YouTube Extras: network callback not registered", t);
        }
    }

    private static void check(int round) {
        try {
            if (PlayerType.getCurrent().isNoneOrHidden() || VideoInformation.getVideoLength() <= 0) return;
            final long before = VideoInformation.getVideoTime();
            Utils.runOnMainThreadDelayed(() -> {
                long after = VideoInformation.getVideoTime();
                if (after != before) return;
                Logger.printDebug(() -> "YouTube Extras: playback stuck at " + after + " ms after the connection changed; reloading");
                VideoInformation.seekTo(after);
                if (round < 2) Utils.runOnMainThreadDelayed(() -> check(round + 1), 5000);
            }, 4000);
        } catch (Throwable t) {
            Logger.printException(() -> "YouTube Extras: network recovery failed", t);
        }
    }
}
