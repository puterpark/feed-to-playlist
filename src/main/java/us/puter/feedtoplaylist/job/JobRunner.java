package us.puter.feedtoplaylist.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import us.puter.feedtoplaylist.feed.job.PollFeedJob;

@Profile("job")
@Component
@RequiredArgsConstructor
@Slf4j
public class JobRunner implements CommandLineRunner {

    private final PollFeedJob pollFeedJob;

    @Override
    public void run(String... args) throws Exception {
        String jobName = args.length > 0 ? args[0] : "";
        long start = System.currentTimeMillis();

        log.info("\n==========================================\n  JOB START : {}\n==========================================", jobName);

        if ("pollFeedJob".equals(jobName)) {
            pollFeedJob.execute();
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("\n==========================================\n  JOB END   : {} ({}ms)\n==========================================", jobName, elapsed);
    }

}
