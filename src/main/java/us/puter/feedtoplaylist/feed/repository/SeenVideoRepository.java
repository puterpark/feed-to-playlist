package us.puter.feedtoplaylist.feed.repository;

import generated.jooq.obj.tables.daos.SeenVideoDao;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static generated.jooq.obj.Tables.SEEN_VIDEO;

@Repository
public class SeenVideoRepository extends SeenVideoDao {

    private static final int CHUNK = 500;

    private final DSLContext dsl;

    public SeenVideoRepository(DSLContext dsl) {
        super(dsl.configuration());
        this.dsl = dsl;
    }

    /**
     * 주어진 영상 ID 중 이미 처리한 것
     */
    public Set<String> findSeen(UUID accountId, Collection<String> videoIds) {
        Set<String> result = new HashSet<>();
        List<String> ids = List.copyOf(videoIds);

        for (int from = 0; from < ids.size(); from += CHUNK) {
            result.addAll(dsl.select(SEEN_VIDEO.VIDEO_ID)
                    .from(SEEN_VIDEO)
                    .where(SEEN_VIDEO.ACCOUNT_ID.eq(accountId))
                    .and(SEEN_VIDEO.VIDEO_ID.in(ids.subList(from, Math.min(from + CHUNK, ids.size()))))
                    .fetch(SEEN_VIDEO.VIDEO_ID));
        }
        return result;
    }

    public void markSeen(UUID accountId, Collection<String> videoIds) {
        if (videoIds.isEmpty()) {
            return;
        }

        BatchBindStep batch = dsl.batch(
                dsl.insertInto(SEEN_VIDEO, SEEN_VIDEO.ACCOUNT_ID, SEEN_VIDEO.VIDEO_ID)
                        .values((UUID) null, null)
                        .onConflictDoNothing());
        for (String videoId : videoIds) {
            batch.bind(accountId, videoId);
        }
        batch.execute();
    }

    public int deleteOlderThan(Instant threshold) {
        return dsl.deleteFrom(SEEN_VIDEO)
                .where(SEEN_VIDEO.CREATED_AT.lt(LocalDateTime.ofInstant(threshold, ZoneId.systemDefault())))
                .execute();
    }
}
