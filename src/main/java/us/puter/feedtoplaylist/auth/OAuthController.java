package us.puter.feedtoplaylist.auth;

import generated.jooq.obj.tables.pojos.Account;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import us.puter.feedtoplaylist.feed.repository.AccountRepository;
import us.puter.feedtoplaylist.feed.service.FeedPollService;
import us.puter.feedtoplaylist.global.exception.GoogleApiException;
import us.puter.feedtoplaylist.global.properties.GoogleProperties;
import us.puter.feedtoplaylist.global.util.TokenCipher;
import us.puter.feedtoplaylist.google.GoogleOAuthClient;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Google OAuth 연결. 브라우저에서 /oauth/google/login 을 열면 동의 화면으로 이동하고,
 * 완료되면 refresh token 이 암호화되어 저장되며 대상 재생목록이 준비된다.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class OAuthController {

    private static final String STATE_ATTR = "oauth.state";

    private final GoogleOAuthClient oauthClient;
    private final GoogleProperties googleProperties;
    private final TokenCipher tokenCipher;
    private final AccountRepository accountRepository;
    private final FeedPollService feedPollService;
    private final SecureRandom random = new SecureRandom();

    @GetMapping("/oauth/google/login")
    public ResponseEntity<Void> login(HttpSession session) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(STATE_ATTR, state);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthClient.authorizationUrl(state)))
                .build();
    }

    @GetMapping(value = "/oauth/google/callback", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpSession session
    ) {
        String expectedState = (String) session.getAttribute(STATE_ATTR);
        session.removeAttribute(STATE_ATTR);

        if (StringUtils.isNotBlank(error)) {
            return page(HttpStatus.BAD_REQUEST, "연결 취소됨", "Google 에서 오류가 반환되었습니다: " + HtmlUtils.htmlEscape(error));
        }
        if (expectedState == null || !expectedState.equals(state) || StringUtils.isBlank(code)) {
            return page(HttpStatus.BAD_REQUEST, "잘못된 요청", "state 검증에 실패했습니다. /oauth/google/login 부터 다시 시도하세요.");
        }

        try {
            GoogleOAuthClient.TokenResponse token = oauthClient.exchangeCode(code);
            if (StringUtils.isBlank(token.refreshToken())) {
                return page(HttpStatus.BAD_REQUEST, "refresh token 없음",
                        "refresh token 이 발급되지 않았습니다. https://myaccount.google.com/permissions 에서 이 앱의 권한을 삭제한 뒤 다시 시도하세요.");
            }

            GoogleOAuthClient.UserInfo user = oauthClient.userInfo(token.accessToken());
            if (!googleProperties.allowedEmails().isEmpty() && !googleProperties.allowedEmails().contains(user.email())) {
                log.warn("허용되지 않은 계정의 연결 시도: {}", user.email());
                return page(HttpStatus.FORBIDDEN, "허용되지 않은 계정", "이 계정은 사용할 수 없습니다.");
            }

            Account account = accountRepository.upsert(user.sub(), user.email(), tokenCipher.encrypt(token.refreshToken()));
            String playlistId = feedPollService.ensurePlaylist(account, token.accessToken());

            log.info("계정 연결 완료: {} playlist[{}]", user.email(), playlistId);

            return page(HttpStatus.OK, "연결 완료",
                    HtmlUtils.htmlEscape(user.email()) + " 계정이 연결되었습니다.<br>새 영상은 "
                            + "<a href=\"https://www.youtube.com/playlist?list=" + HtmlUtils.htmlEscape(playlistId) + "\">이 재생목록</a>에 추가됩니다."
                            + "<br>연결 시점의 기존 영상은 추가되지 않고, 이후 올라오는 영상부터 추가됩니다.");
        } catch (GoogleApiException e) {
            log.error("OAuth 처리 실패", e);
            return page(HttpStatus.BAD_GATEWAY, "연결 실패", "Google API 호출에 실패했습니다. 서버 로그를 확인하세요.");
        }
    }

    private ResponseEntity<String> page(HttpStatus status, String title, String bodyHtml) {
        // title 은 고정 문자열, bodyHtml 은 호출부에서 이스케이프 처리됨 (error 파라미터만 아래에서 이스케이프)
        String html = "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>" + title + "</title></head>"
                + "<body style=\"font-family:sans-serif;max-width:560px;margin:15vh auto;line-height:1.6\"><h2>" + title + "</h2><p>"
                + bodyHtml + "</p></body></html>";
        return ResponseEntity
                .status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
