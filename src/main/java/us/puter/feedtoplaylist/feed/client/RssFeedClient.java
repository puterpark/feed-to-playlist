package us.puter.feedtoplaylist.feed.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import us.puter.feedtoplaylist.feed.model.FeedEntry;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 채널 RSS 폴링 (API 쿼터를 소모하지 않음). 최신 15개 영상만 제공된다.
 */
@Component
@RequiredArgsConstructor
public class RssFeedClient {

    private static final String FEED_URL = "https://www.youtube.com/feeds/videos.xml?channel_id={channelId}";

    private final RestClient restClient;

    public List<FeedEntry> fetch(String channelId) throws Exception {
        byte[] body = restClient.get()
                .uri(FEED_URL, channelId)
                .retrieve()
                .body(byte[].class);

        if (body == null || body.length == 0) {
            return List.of();
        }
        return FeedParser.parse(new ByteArrayInputStream(body));
    }
}
