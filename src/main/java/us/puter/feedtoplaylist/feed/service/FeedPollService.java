package us.puter.feedtoplaylist.feed.service;

import generated.jooq.obj.tables.pojos.Account;
import generated.jooq.obj.tables.pojos.Subscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import us.puter.feedtoplaylist.feed.client.RssFeedClient;
import us.puter.feedtoplaylist.feed.client.ShortsDetector;
import us.puter.feedtoplaylist.feed.model.FeedEntry;
import us.puter.feedtoplaylist.feed.repository.AccountRepository;
import us.puter.feedtoplaylist.feed.repository.SeenVideoRepository;
import us.puter.feedtoplaylist.feed.repository.SubscriptionRepository;
import us.puter.feedtoplaylist.global.exception.GoogleApiException;
import us.puter.feedtoplaylist.global.properties.PollProperties;
import us.puter.feedtoplaylist.global.util.TokenCipher;
import us.puter.feedtoplaylist.google.GoogleOAuthClient;
import us.puter.feedtoplaylist.youtube.YouTubeClient;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedPollService {

    private final GoogleOAuthClient oauthClient;
    private final YouTubeClient youTubeClient;
    private final RssFeedClient rssFeedClient;
    private final ShortsDetector shortsDetector;
    private final AccountRepository accountRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SeenVideoRepository seenVideoRepository;
    private final TokenCipher tokenCipher;
    private final PollProperties properties;

    public record PollResult(int added, int skipped, int failed, int baselinedChannels) {

        public static final PollResult EMPTY = new PollResult(0, 0, 0, 0);

        public PollResult plus(PollResult o) {
            return new PollResult(added + o.added, skipped + o.skipped, failed + o.failed, baselinedChannels + o.baselinedChannels);
        }
    }

    private record Candidate(String channelId, FeedEntry entry) {

    }

    /**
     * 한 계정의 폴링 1회: 토큰 갱신 → (필요 시) 구독 동기화 → RSS 수집 → 신규 영상을 재생목록에 추가
     */
    public PollResult pollAccount(Account account) {
        String accessToken;
        try {
            accessToken = oauthClient.refreshAccessToken(tokenCipher.decrypt(account.refreshTokenEnc()));
        } catch (GoogleApiException e) {
            if (e.isInvalidGrant()) {
                log.warn("[{}] refresh token 이 폐기되어 계정을 비활성화합니다. 다시 로그인해야 합니다.", account.email());
                accountRepository.markRevoked(account.id());
                return PollResult.EMPTY;
            }
            throw e;
        }

        if (needsSubscriptionSync(account)) {
            syncSubscriptions(account, accessToken);
        }

        String playlistId = ensurePlaylist(account, accessToken);
        List<Subscription> subscriptions = subscriptionRepository.findByAccount(account.id());
        Map<String, List<FeedEntry>> feeds = fetchFeeds(subscriptions);

        return process(account, accessToken, playlistId, subscriptions, feeds);
    }

    /**
     * 재생목록 ID 확보: 저장된 값 → 제목으로 검색 → 신규 생성
     */
    public String ensurePlaylist(Account account, String accessToken) {
        if (account.playlistId() != null) {
            return account.playlistId();
        }

        String title = properties.playlistTitle();
        String playlistId = youTubeClient.findPlaylistIdByTitle(accessToken, title)
                .orElseGet(() -> {
                    log.info("[{}] 재생목록 '{}' 이(가) 없어 새로 생성합니다.", account.email(), title);
                    return youTubeClient.createPlaylist(accessToken, title);
                });
        accountRepository.updatePlaylistId(account.id(), playlistId);
        return playlistId;
    }

    private boolean needsSubscriptionSync(Account account) {
        return account.subscriptionsSyncedAt() == null
                || account.subscriptionsSyncedAt().atZone(ZoneId.systemDefault()).toInstant().plus(properties.subscriptionSyncInterval())
                .isBefore(Instant.now());
    }

    private void syncSubscriptions(Account account, String accessToken) {
        List<YouTubeClient.SubscribedChannel> channels = youTubeClient.listSubscriptions(accessToken);
        if (channels.isEmpty()) {
            // 일시적 응답 이상으로 전체 삭제되는 것을 방지
            log.warn("[{}] 구독 목록이 비어 있어 동기화를 건너뜁니다.", account.email());
            return;
        }
        int[] diff = subscriptionRepository.sync(account.id(), channels);
        accountRepository.updateSubscriptionsSyncedAt(account.id(), Instant.now());
        log.info("[{}] 구독 동기화: 총 {}개 (신규 {}, 해지 {})", account.email(), channels.size(), diff[0], diff[1]);
    }

    private Map<String, List<FeedEntry>> fetchFeeds(List<Subscription> subscriptions) {
        Map<String, List<FeedEntry>> result = new ConcurrentHashMap<>();
        Semaphore semaphore = new Semaphore(Math.max(1, properties.rssConcurrency()));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Subscription sub : subscriptions) {
                executor.submit(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        result.put(sub.channelId(), rssFeedClient.fetch(sub.channelId()));
                    } catch (Exception e) {
                        // 해당 채널만 건너뛰고 다음 주기에 재시도
                        log.warn("RSS 수집 실패: channel[{}] {} - {}", sub.channelId(), sub.title(), e.getMessage());
                    } finally {
                        semaphore.release();
                    }
                });
            }
        }
        return result;
    }

    private PollResult process(Account account, String accessToken, String playlistId,
            List<Subscription> subscriptions, Map<String, List<FeedEntry>> feeds) {
        UUID accountId = account.id();
        Instant cutoff = Instant.now().minus(properties.maxAge());

        Set<String> allVideoIds = feeds.values().stream()
                .flatMap(List::stream)
                .map(FeedEntry::videoId)
                .collect(Collectors.toSet());
        Set<String> seen = seenVideoRepository.findSeen(accountId, allVideoIds);

        int baselined = 0;
        List<Candidate> candidates = new ArrayList<>();

        for (Subscription sub : subscriptions) {
            List<FeedEntry> entries = feeds.get(sub.channelId());
            if (entries == null) {
                continue;
            }

            if (!sub.baselined()) {
                // 신규 구독 채널: 기존 영상은 모두 "본 것"으로 처리해 과거 영상이 쏟아지지 않게 한다.
                seenVideoRepository.markSeen(accountId, entries.stream().map(FeedEntry::videoId).toList());
                subscriptionRepository.markBaselined(accountId, sub.channelId());
                baselined++;
                continue;
            }

            for (FeedEntry entry : entries) {
                if (!seen.contains(entry.videoId()) && entry.publishedAt().isAfter(cutoff)) {
                    candidates.add(new Candidate(sub.channelId(), entry));
                }
            }
        }

        // 오래된 영상부터 추가해야 재생목록 순서가 게시 시간순이 된다.
        candidates.sort(Comparator.comparing(c -> c.entry().publishedAt()));

        Set<String> liveIds = Set.of();
        if (properties.skipLive() && !candidates.isEmpty()) {
            try {
                liveIds = youTubeClient.findLiveVideoIds(accessToken,
                        candidates.stream().map(c -> c.entry().videoId()).toList());
            } catch (GoogleApiException e) {
                // 판별 실패 시 라이브가 아닌 것으로 취급(fail-open)
                log.warn("[{}] 라이브 판별 실패: {}", account.email(), e.getMessage());
            }
        }

        int added = 0;
        int skipped = 0;
        int failed = 0;

        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            // 이 영상부터 중단될 때 처리하지 못하고 남는 영상 수
            int remaining = candidates.size() - i;
            String videoId = candidate.entry().videoId();

            if (liveIds.contains(videoId)
                    || (properties.skipShorts() && shortsDetector.isShort(videoId))) {
                seenVideoRepository.markSeen(accountId, List.of(videoId));
                skipped++;
                continue;
            }

            try {
                youTubeClient.addVideo(accessToken, playlistId, videoId);
                seenVideoRepository.markSeen(accountId, List.of(videoId));
                added++;
                log.info("[{}] 추가: {} ({})", account.email(), candidate.entry().title(), videoId);
            } catch (GoogleApiException e) {
                if (e.isDuplicate() || e.isVideoNotFound()) {
                    // 이미 있거나 비공개/삭제된 영상: 재시도해도 의미 없으므로 처리 완료로 기록
                    seenVideoRepository.markSeen(accountId, List.of(videoId));
                    skipped++;
                    log.debug("[{}] 건너뜀: {} - {}", account.email(), videoId, e.getMessage());
                } else if (e.isQuotaExceeded()) {
                    log.error("[{}] YouTube API 일일 쿼터 초과. 남은 영상은 다음 실행에서 처리합니다.", account.email());
                    failed += remaining;
                    break;
                } else if (e.isPlaylistNotFound()) {
                    log.error("[{}] 재생목록이 삭제되었습니다. 다음 실행에서 다시 생성합니다.", account.email());
                    accountRepository.updatePlaylistId(accountId, null);
                    failed += remaining;
                    break;
                } else if (e.isPlaylistFull()) {
                    log.error("[{}] 재생목록이 가득 찼습니다(최대 5000개). 정리가 필요합니다.", account.email());
                    failed += remaining;
                    break;
                } else if (e.isAuthError()) {
                    log.error("[{}] 인증 오류. 다음 실행에서 토큰을 갱신합니다. - {}", account.email(), e.getMessage());
                    failed += remaining;
                    break;
                } else {
                    failed++;
                    log.warn("[{}] 추가 실패(다음 실행에서 재시도): {} - {}", account.email(), videoId, e.getMessage());
                }
            }
        }

        return new PollResult(added, skipped, failed, baselined);
    }
}
