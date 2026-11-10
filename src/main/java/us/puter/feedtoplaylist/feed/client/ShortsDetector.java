package us.puter.feedtoplaylist.feed.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * youtube.com/shorts/{id} 는 쇼츠면 200, 일반 영상이면 /watch 로 리다이렉트(3xx)된다.
 * 판별에 실패하면 쇼츠가 아닌 것으로 취급(fail-open)해 영상이 누락되지 않게 한다.
 */
@Component
@Slf4j
public class ShortsDetector {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public boolean isShort(String videoId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://www.youtube.com/shorts/" + videoId))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("쇼츠 판별 실패: videoId[{}] - {}", videoId, e.toString());
            return false;
        }
    }
}
