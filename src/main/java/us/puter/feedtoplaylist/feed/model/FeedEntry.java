package us.puter.feedtoplaylist.feed.model;

import java.time.Instant;

public record FeedEntry(
        String videoId,
        String channelId,
        String title,
        Instant publishedAt
) {

}
