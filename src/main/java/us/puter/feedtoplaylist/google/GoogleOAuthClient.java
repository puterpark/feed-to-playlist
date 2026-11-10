package us.puter.feedtoplaylist.google;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import us.puter.feedtoplaylist.global.exception.GoogleApiException;
import us.puter.feedtoplaylist.global.properties.GoogleProperties;

@Component
@RequiredArgsConstructor
public class GoogleOAuthClient {

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    // 구독 목록 조회 + 재생목록 생성/수정을 모두 포함하는 범위
    private static final String SCOPE = "openid email https://www.googleapis.com/auth/youtube";

    private final RestClient restClient;
    private final GoogleProperties properties;

    public record TokenResponse(String accessToken, String refreshToken) {

    }

    public record UserInfo(String sub, String email) {

    }

    public String authorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTH_URL)
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPE)
                // refresh token 발급 보장
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    public TokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        JsonNode body = postToken(form);
        return new TokenResponse(body.path("access_token").asText(null), body.path("refresh_token").asText(null));
    }

    public String refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        return postToken(form).path("access_token").asText(null);
    }

    public UserInfo userInfo(String accessToken) {
        try {
            JsonNode body = restClient.get()
                    .uri(USERINFO_URL)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .body(JsonNode.class);
            return new UserInfo(body.path("sub").asText(), body.path("email").asText(""));
        } catch (RestClientResponseException e) {
            throw GoogleApiException.from(e);
        }
    }

    private JsonNode postToken(MultiValueMap<String, String> form) {
        try {
            return restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw GoogleApiException.from(e);
        }
    }
}
