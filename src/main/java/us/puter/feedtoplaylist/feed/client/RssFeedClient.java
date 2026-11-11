package us.puter.feedtoplaylist.feed.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import us.puter.feedtoplaylist.feed.model.FeedEntry;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 채널 RSS 폴링 (API 쿼터를 소모하지 않음). 최신 15개 영상만 제공된다.
 */
@Component
@RequiredArgsConstructor
public class RssFeedClient {

    private static final String FEED_URL = "https://www.youtube.com/feeds/videos.xml?channel_id={channelId}";

    private final RestClient restClient;

    private static final int MAX_ATTEMPTS = 4;
    private static final long BASE_BACKOFF_MS = 500;

    /**
     * YouTube RSS 는 유효한 채널에도 동시 요청이 몰리면 일시적으로 404/500 을 돌려주므로 지수 백오프로 재시도한다.
     */
    public List<FeedEntry> fetch(String channelId) throws Exception {
        RestClientResponseException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                byte[] body = restClient.get()
                        .uri(FEED_URL, channelId)
                        .retrieve()
                        .body(byte[].class);

                if (body == null || body.length == 0) {
                    return List.of();
                }
                return FeedParser.parse(new ByteArrayInputStream(body));
            } catch (HttpClientErrorException.NotFound | HttpServerErrorException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1));
                    Thread.sleep(backoff + ThreadLocalRandom.current().nextLong(backoff));
                }
            }
        }
        throw new IllegalStateException("HTTP " + last.getStatusCode().value() + " (재시도 " + MAX_ATTEMPTS + "회 실패)", last);
    }
}
