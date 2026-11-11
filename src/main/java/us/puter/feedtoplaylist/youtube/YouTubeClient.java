package us.puter.feedtoplaylist.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import us.puter.feedtoplaylist.global.exception.GoogleApiException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * YouTube Data API v3. (쿼터: list = 1, playlistItems.insert / playlists.insert = 50)
 */
@Component
@RequiredArgsConstructor
public class YouTubeClient {

    private static final String BASE = "https://www.googleapis.com/youtube/v3";

    private final RestClient restClient;

    public record SubscribedChannel(String channelId, String title) {

    }

    /**
     * 내가 구독 중인 모든 채널
     */
    public List<SubscribedChannel> listSubscriptions(String accessToken) {
        List<SubscribedChannel> result = new ArrayList<>();
        String pageToken = null;

        do {
            JsonNode body = getPage(accessToken, "/subscriptions", Map.of("part", "snippet", "mine", "true"), pageToken);
            for (JsonNode item : body.path("items")) {
                JsonNode snippet = item.path("snippet");
                String channelId = snippet.path("resourceId").path("channelId").asText(null);
                if (channelId != null) {
                    result.add(new SubscribedChannel(channelId, snippet.path("title").asText("")));
                }
            }
            pageToken = body.path("nextPageToken").asText(null);
        } while (pageToken != null);

        return result;
    }

    /**
     * 내 재생목록 중 제목이 일치하는 것
     */
    public Optional<String> findPlaylistIdByTitle(String accessToken, String title) {
        String pageToken = null;

        do {
            JsonNode body = getPage(accessToken, "/playlists", Map.of("part", "snippet", "mine", "true"), pageToken);
            for (JsonNode item : body.path("items")) {
                if (title.equals(item.path("snippet").path("title").asText())) {
                    return Optional.of(item.path("id").asText());
                }
            }
            pageToken = body.path("nextPageToken").asText(null);
        } while (pageToken != null);

        return Optional.empty();
    }

    public String createPlaylist(String accessToken, String title) {
        Map<String, Object> request = Map.of(
                "snippet", Map.of("title", title, "description", "feed-to-playlist 가 구독 채널의 새 영상을 자동으로 추가합니다."),
                "status", Map.of("privacyStatus", "private")
        );

        JsonNode body = call(() -> restClient.post()
                .uri(BASE + "/playlists?part=snippet,status")
                .headers(h -> h.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class));

        return body.path("id").asText();
    }

    /**
     * 재생목록 끝에 영상 추가
     */
    public void addVideo(String accessToken, String playlistId, String videoId) {
        Map<String, Object> request = Map.of(
                "snippet", Map.of(
                        "playlistId", playlistId,
                        "resourceId", Map.of("kind", "youtube#video", "videoId", videoId)
                )
        );

        call(() -> restClient.post()
                .uri(BASE + "/playlistItems?part=snippet")
                .headers(h -> h.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class));
    }

    /**
     * 라이브 방송/예정/종료된 방송/프리미어(liveStreamingDetails 가 있는 영상)의 ID만 골라낸다. (쿼터: 50개당 1)
     */
    public Set<String> findLiveVideoIds(String accessToken, Collection<String> videoIds) {
        Set<String> result = new HashSet<>();
        List<String> ids = new ArrayList<>(videoIds);

        for (int from = 0; from < ids.size(); from += 50) {
            String joined = String.join(",", ids.subList(from, Math.min(from + 50, ids.size())));
            JsonNode body = call(() -> restClient.get()
                    .uri(BASE + "/videos?part=liveStreamingDetails&id={ids}&maxResults=50", joined)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .body(JsonNode.class));
            for (JsonNode item : body.path("items")) {
                if (item.has("liveStreamingDetails")) {
                    result.add(item.path("id").asText());
                }
            }
        }

        return result;
    }

    private JsonNode getPage(String accessToken, String path, Map<String, String> params, String pageToken) {
        return call(() -> restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.scheme("https").host("www.googleapis.com").path("/youtube/v3" + path);
                    params.forEach(uriBuilder::queryParam);
                    uriBuilder.queryParam("maxResults", 50);
                    if (pageToken != null) {
                        uriBuilder.queryParam("pageToken", pageToken);
                    }
                    return uriBuilder.build();
                })
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(JsonNode.class));
    }

    private JsonNode call(Supplier<JsonNode> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw GoogleApiException.from(e);
        }
    }
}
