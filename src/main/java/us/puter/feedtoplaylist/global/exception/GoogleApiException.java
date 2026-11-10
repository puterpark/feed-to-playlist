package us.puter.feedtoplaylist.global.exception;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import org.springframework.web.client.RestClientResponseException;

import java.util.Set;

/**
 * Google OAuth / YouTube Data API 호출 실패.
 * OAuth 에러({"error":"invalid_grant"})와 YouTube 에러({"error":{"errors":[{"reason":"..."}]}}) 형식을 모두 처리한다.
 */
@Getter
public class GoogleApiException extends RuntimeException {

    private static final Set<String> QUOTA_REASONS = Set.of("quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded");

    private final int status;
    private final String reason;

    public GoogleApiException(int status, String reason, String message, Throwable cause) {
        super("[" + status + "/" + reason + "] " + message, cause);
        this.status = status;
        this.reason = reason;
    }

    public static GoogleApiException from(RestClientResponseException e) {
        String reason = null;
        String message = e.getMessage();

        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            if (body != null) {
                JsonNode error = body.path("error");
                if (error.isTextual()) {
                    reason = error.asText();
                    message = body.path("error_description").asText(message);
                } else if (error.isObject()) {
                    reason = error.path("errors").path(0).path("reason").asText(null);
                    message = error.path("message").asText(message);
                }
            }
        } catch (Exception ignored) {
            // 본문 파싱 실패 시 상태 코드만 사용
        }

        return new GoogleApiException(e.getStatusCode().value(), reason, message, e);
    }

    /**
     * refresh token 이 폐기/만료됨 (사용자가 권한 철회 등)
     */
    public boolean isInvalidGrant() {
        return "invalid_grant".equals(reason);
    }

    public boolean isQuotaExceeded() {
        return reason != null && QUOTA_REASONS.contains(reason);
    }

    public boolean isAuthError() {
        return status == 401;
    }

    public boolean isDuplicate() {
        return status == 409 || "videoAlreadyInPlaylist".equals(reason);
    }

    public boolean isVideoNotFound() {
        return "videoNotFound".equals(reason) || (status == 404 && !isPlaylistNotFound());
    }

    public boolean isPlaylistNotFound() {
        return "playlistNotFound".equals(reason);
    }

    public boolean isPlaylistFull() {
        return "playlistContainsMaximumNumberOfVideos".equals(reason);
    }
}
