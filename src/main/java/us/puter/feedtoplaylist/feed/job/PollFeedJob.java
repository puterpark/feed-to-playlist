package us.puter.feedtoplaylist.feed.job;

import generated.jooq.obj.tables.pojos.Account;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import us.puter.feedtoplaylist.feed.repository.AccountRepository;
import us.puter.feedtoplaylist.feed.repository.SeenVideoRepository;
import us.puter.feedtoplaylist.feed.service.FeedPollService;
import us.puter.feedtoplaylist.feed.service.FeedPollService.PollResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Component
@RequiredArgsConstructor
@Slf4j
public class PollFeedJob {

    private static final Duration SEEN_RETENTION = Duration.ofDays(60);

    private final String JOB_NAME = getClass().getSimpleName();

    private final AccountRepository accountRepository;
    private final SeenVideoRepository seenVideoRepository;
    private final FeedPollService feedPollService;

    private final ReentrantLock lock = new ReentrantLock();

    public void execute() {
        if (!lock.tryLock()) {
            log.warn("[{}]: 이전 실행이 아직 진행 중이라 건너뜁니다.", JOB_NAME);
            return;
        }

        try {
            log.info("[{}]: start", JOB_NAME);

            List<Account> accounts = accountRepository.findActive();
            if (accounts.isEmpty()) {
                log.info("[{}]: 처리 대상 없음", JOB_NAME);
                return;
            }

            PollResult total = PollResult.EMPTY;
            for (Account account : accounts) {
                try {
                    PollResult result = feedPollService.pollAccount(account);
                    total = total.plus(result);
                } catch (Exception e) {
                    log.error("[{}]: 계정 처리 중 예외 발생: account[{}]", JOB_NAME, account.email(), e);
                }
            }

            int purged = seenVideoRepository.deleteOlderThan(Instant.now().minus(SEEN_RETENTION));

            log.info("[{}]: end - 계정[{}], 추가[{}], 건너뜀[{}], 실패[{}], 신규채널 기준설정[{}], 정리[{}]",
                    JOB_NAME, accounts.size(), total.added(), total.skipped(), total.failed(), total.baselinedChannels(), purged);
        } finally {
            lock.unlock();
        }
    }
}
