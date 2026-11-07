package io.github.bryceaspringfield.morphe.extension.youtube;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

/**
 * Layout pieces: the community panel of comment sections and the new-content dots of the subscribed channels bar.
 * With Log feed items on, it logs every component path under the channel bar and the comments panel, which is how
 * the paths below were chosen.
 */
@SuppressWarnings("unused")
public final class XLayoutFilter extends Filter {

    /** Each channel of the subscribed channels bar; the dot is a child component without text. */
    private final StringFilterGroup channelBarChannel = new StringFilterGroup(null, "subscriptions_channel_bar_channel.e");

    /** The comments panel's community entry ("Community of CHANNEL ... Visit"). */
    private final StringFilterGroup commentsCommunity = new StringFilterGroup(null,
            "community_entry_point", "comments_community", "community_header", "channel_community");

    public XLayoutFilter() {
        addPathCallbacks(channelBarChannel, commentsCommunity);
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface, String identifier, String accessibility,
                              CharSequence path, byte[] buffer, BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup, FilterContentType contentType, int contentIndex) {
        if (XSettings.DEBUG.get()) {
            Logger.printDebug(() -> "YouTube Extras layout: " + path + " id=" + identifier + " a11y=" + accessibility);
        }
        if (matchedGroup == commentsCommunity) return XSettings.HIDE_COMMENTS_COMMUNITY.get();
        // The dot's component is not yet identified (it needs a signed-in account), so the bar is only logged.
        return false;
    }

}
