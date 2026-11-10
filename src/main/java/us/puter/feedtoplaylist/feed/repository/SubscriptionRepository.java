package us.puter.feedtoplaylist.feed.repository;

import generated.jooq.obj.tables.daos.SubscriptionDao;
import generated.jooq.obj.tables.pojos.Subscription;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import us.puter.feedtoplaylist.youtube.YouTubeClient.SubscribedChannel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static generated.jooq.obj.Tables.SUBSCRIPTION;

@Repository
public class SubscriptionRepository extends SubscriptionDao {

    private static final int CHUNK = 500;

    private final DSLContext dsl;

    public SubscriptionRepository(DSLContext dsl) {
        super(dsl.configuration());
        this.dsl = dsl;
    }

    public List<Subscription> findByAccount(UUID accountId) {
        return dsl.selectFrom(SUBSCRIPTION)
                .where(SUBSCRIPTION.ACCOUNT_ID.eq(accountId))
                .fetchInto(Subscription.class);
    }

    /**
     * 구독 목록 동기화: 신규 구독은 baselined=false 로 추가, 구독 해지된 채널은 삭제.
     * @return {추가 수, 삭제 수}
     */
    public int[] sync(UUID accountId, List<SubscribedChannel> channels) {
        Set<String> existing = findByAccount(accountId).stream().map(Subscription::channelId).collect(Collectors.toSet());
        Set<String> fetched = channels.stream().map(SubscribedChannel::channelId).collect(Collectors.toSet());

        if (!channels.isEmpty()) {
            BatchBindStep batch = dsl.batch(
                    dsl.insertInto(SUBSCRIPTION, SUBSCRIPTION.ACCOUNT_ID, SUBSCRIPTION.CHANNEL_ID, SUBSCRIPTION.TITLE)
                            .values((UUID) null, null, null)
                            .onConflict(SUBSCRIPTION.ACCOUNT_ID, SUBSCRIPTION.CHANNEL_ID)
                            .doUpdate()
                            .set(SUBSCRIPTION.TITLE, DSL.excluded(SUBSCRIPTION.TITLE)));
            for (SubscribedChannel channel : channels) {
                batch.bind(accountId, channel.channelId(), channel.title());
            }
            batch.execute();
        }

        Set<String> removed = new HashSet<>(existing);
        removed.removeAll(fetched);
        List<String> removedIds = List.copyOf(removed);
        for (int from = 0; from < removedIds.size(); from += CHUNK) {
            dsl.deleteFrom(SUBSCRIPTION)
                    .where(SUBSCRIPTION.ACCOUNT_ID.eq(accountId))
                    .and(SUBSCRIPTION.CHANNEL_ID.in(removedIds.subList(from, Math.min(from + CHUNK, removedIds.size()))))
                    .execute();
        }

        int added = (int) fetched.stream().filter(id -> !existing.contains(id)).count();
        return new int[]{added, removed.size()};
    }

    public void markBaselined(UUID accountId, String channelId) {
        dsl.update(SUBSCRIPTION)
                .set(SUBSCRIPTION.BASELINED, true)
                .where(SUBSCRIPTION.ACCOUNT_ID.eq(accountId))
                .and(SUBSCRIPTION.CHANNEL_ID.eq(channelId))
                .execute();
    }
}
